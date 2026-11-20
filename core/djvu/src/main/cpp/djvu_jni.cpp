// JNI glue between DjvuNative (Kotlin) and DjVuLibre's public C API (ddjvuapi.h).
//
// One DjvuHandle per open document: its own ddjvu context, the document, and the pixel format used to
// render into Android ARGB_8888 bitmaps. The Kotlin side serializes every call on one handle, so the
// functions here never run concurrently for the same document.
//
// DjVuLibre decodes on its own threads and reports through a message queue. Every wait below drains
// that queue, keeps the DDJVU_ERROR texts, and re-checks the job status. Waiting uses the context's
// message callback and a condition variable with a timeout instead of the unbounded
// ddjvu_message_wait(), so a job that stalls without posting anything turns into an error instead of
// blocking the reader thread (and close()) forever.
//
// Nothing here throws a C++ exception across JNI: ddjvuapi catches its own, and every entry point
// catches the rest and turns it into a Java exception.

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>
#include <time.h>

#include <new>
#include <string>
#include <vector>

#include "ddjvuapi.h"
#include "miniexp.h"

namespace {

const char *const kLogTag = "KatanaDjvu";
const char *const kDjvuException = "com/example/unifiedreader/core/djvu/DjvuException";
const char *const kIllegalArgument = "java/lang/IllegalArgumentException";
const char *const kIndexOutOfBounds = "java/lang/IndexOutOfBoundsException";
const char *const kIllegalState = "java/lang/IllegalStateException";

// How long one wait sleeps before the job status is checked again, and how long a job may go
// without posting any message before it counts as stalled.
const long kWaitSliceMs = 200;
const long kStallTimeoutMs = 60000;

// Error texts kept per operation; the first ones are the useful ones.
const size_t kMaxErrors = 3;
const size_t kMaxErrorLength = 400;

// Bytes in memory: R, G, B, A (Android's RGBA_8888, little-endian 0xAABBGGRR). The fourth value is
// XORed into every pixel, which sets alpha to 0xff.
unsigned int kRgbaMasks[4] = {0x000000ffu, 0x0000ff00u, 0x00ff0000u, 0xff000000u};
const uint32_t kOpaqueWhite = 0xffffffffu;

struct DjvuHandle {
  ddjvu_context_t *context = nullptr;
  ddjvu_document_t *document = nullptr;
  ddjvu_format_t *format = nullptr;
  int pageCount = 0;

  pthread_mutex_t mutex;
  pthread_cond_t posted;
  unsigned long postedCount = 0;  // bumped by onMessagePosted, under mutex

  std::string errors;  // DDJVU_ERROR texts of the current operation
  size_t errorCount = 0;

  DjvuHandle() {
    pthread_mutex_init(&mutex, nullptr);
    pthread_cond_init(&posted, nullptr);
  }
  ~DjvuHandle() {
    pthread_cond_destroy(&posted);
    pthread_mutex_destroy(&mutex);
  }
  DjvuHandle(const DjvuHandle &) = delete;
  DjvuHandle &operator=(const DjvuHandle &) = delete;
};

// ---------------------------------------------------------------------------------------------------
// Java exceptions

// Keeps printable ASCII only: JNI wants modified UTF-8, and DjVuLibre's texts are ASCII anyway.
std::string asciiOnly(const std::string &text) {
  std::string out;
  out.reserve(text.size());
  for (char c : text) {
    unsigned char u = static_cast<unsigned char>(c);
    if (u == '\n' || u == '\t') {
      out.push_back(' ');
    } else if (u >= 0x20 && u < 0x7f) {
      out.push_back(c);
    } else if (u >= 0x80) {
      out.push_back('?');
    }
  }
  return out;
}

void throwJava(JNIEnv *env, const char *className, const std::string &message) {
  if (env->ExceptionCheck()) return;
  jclass cls = env->FindClass(className);
  if (cls == nullptr) return;  // NoClassDefFoundError is pending
  env->ThrowNew(cls, asciiOnly(message).c_str());
  env->DeleteLocalRef(cls);
}

// ---------------------------------------------------------------------------------------------------
// Messages

void onMessagePosted(ddjvu_context_t *, void *closure) {
  // Called by DjVuLibre (any thread, under the context's lock) right before it queues a message.
  DjvuHandle *handle = static_cast<DjvuHandle *>(closure);
  pthread_mutex_lock(&handle->mutex);
  handle->postedCount++;
  pthread_cond_broadcast(&handle->posted);
  pthread_mutex_unlock(&handle->mutex);
}

unsigned long postedCount(DjvuHandle *handle) {
  pthread_mutex_lock(&handle->mutex);
  unsigned long count = handle->postedCount;
  pthread_mutex_unlock(&handle->mutex);
  return count;
}

// Without DjVuLibre's message catalogs an error reads "** Unrecognized DjVu Message: ** Message name:
// ByteStream.EOF". Keep just the message name and what follows it.
std::string cleanErrorText(const char *raw) {
  if (raw == nullptr) return std::string();
  std::string text = asciiOnly(raw);
  const char *const marker = "Message name:";
  size_t at = text.find(marker);
  if (at != std::string::npos) text = text.substr(at + strlen(marker));
  size_t first = text.find_first_not_of(' ');
  if (first == std::string::npos) return std::string();
  size_t last = text.find_last_not_of(' ');
  text = text.substr(first, last - first + 1);
  if (text.size() > kMaxErrorLength) text.resize(kMaxErrorLength);
  return text;
}

void recordError(DjvuHandle *handle, const ddjvu_message_t *message) {
  std::string text = cleanErrorText(message->m_error.message);
  if (text.empty()) return;
  __android_log_print(ANDROID_LOG_WARN, kLogTag, "DjVuLibre: %s", text.c_str());
  if (handle->errorCount >= kMaxErrors || handle->errors.find(text) != std::string::npos) return;
  if (!handle->errors.empty()) handle->errors += "; ";
  handle->errors += text;
  handle->errorCount++;
}

void drainMessages(DjvuHandle *handle) {
  const ddjvu_message_t *message;
  while ((message = ddjvu_message_peek(handle->context)) != nullptr) {
    if (message->m_any.tag == DDJVU_ERROR) recordError(handle, message);
    ddjvu_message_pop(handle->context);
  }
}

void startOperation(DjvuHandle *handle) {
  drainMessages(handle);  // leftovers from earlier work are not this operation's errors
  handle->errors.clear();
  handle->errorCount = 0;
}

// Sleeps until DjVuLibre posts a message after `seen`, or timeoutMs passes. True if one was posted.
bool waitForPost(DjvuHandle *handle, unsigned long seen, long timeoutMs) {
  struct timespec deadline;
  clock_gettime(CLOCK_REALTIME, &deadline);
  deadline.tv_sec += timeoutMs / 1000;
  deadline.tv_nsec += (timeoutMs % 1000) * 1000000L;
  if (deadline.tv_nsec >= 1000000000L) {
    deadline.tv_sec += 1;
    deadline.tv_nsec -= 1000000000L;
  }
  pthread_mutex_lock(&handle->mutex);
  int rc = 0;
  while (handle->postedCount == seen && rc != ETIMEDOUT) {
    rc = pthread_cond_timedwait(&handle->posted, &handle->mutex, &deadline);
  }
  bool posted = handle->postedCount != seen;
  pthread_mutex_unlock(&handle->mutex);
  return posted;
}

// Pumps messages until poll() reports the job done (DDJVU_JOB_OK or worse). A job that posts nothing
// for kStallTimeoutMs counts as failed.
template <typename Poll>
ddjvu_status_t waitForJob(DjvuHandle *handle, Poll poll) {
  long idleMs = 0;
  for (;;) {
    unsigned long seen = postedCount(handle);
    drainMessages(handle);
    ddjvu_status_t status = poll();
    if (status >= DDJVU_JOB_OK) {
      drainMessages(handle);
      return status;
    }
    if (waitForPost(handle, seen, kWaitSliceMs)) {
      idleMs = 0;
      continue;
    }
    // Quiet for a whole slice. A job that never started and has already reported errors won't start now.
    if (status == DDJVU_JOB_NOTSTARTED && !handle->errors.empty()) return DDJVU_JOB_FAILED;
    idleMs += kWaitSliceMs;
    if (idleMs >= kStallTimeoutMs) {
      if (handle->errors.empty()) handle->errors = "decoding stalled";
      return DDJVU_JOB_FAILED;
    }
  }
}

std::string failure(const DjvuHandle *handle, const char *what) {
  std::string message(what);
  if (!handle->errors.empty()) message += " (" + handle->errors + ")";
  return message;
}

// ---------------------------------------------------------------------------------------------------
// Lifetime

void destroyHandle(DjvuHandle *handle) {
  if (handle == nullptr) return;
  if (handle->context != nullptr) {
    // After this returns DjVuLibre's threads can no longer call back into this handle.
    ddjvu_message_set_callback(handle->context, nullptr, nullptr);
  }
  if (handle->format != nullptr) ddjvu_format_release(handle->format);
  if (handle->document != nullptr) ddjvu_document_release(handle->document);
  if (handle->context != nullptr) {
    drainMessages(handle);
    ddjvu_context_release(handle->context);
  }
  delete handle;
}

DjvuHandle *fromJava(JNIEnv *env, jlong value) {
  DjvuHandle *handle = reinterpret_cast<DjvuHandle *>(static_cast<intptr_t>(value));
  if (handle == nullptr) throwJava(env, kIllegalState, "DjVu document is closed");
  return handle;
}

bool checkPageIndex(JNIEnv *env, const DjvuHandle *handle, jint pageIndex) {
  if (pageIndex >= 0 && pageIndex < handle->pageCount) return true;
  throwJava(env, kIndexOutOfBounds,
            "Page " + std::to_string(pageIndex) + " of " + std::to_string(handle->pageCount));
  return false;
}

// Opens the document and waits for its directory. Returns null after throwing.
DjvuHandle *openDocument(JNIEnv *env, const char *path) {
  DjvuHandle *handle = new DjvuHandle();
  handle->context = ddjvu_context_create("katana");
  if (handle->context == nullptr) {
    destroyHandle(handle);
    throwJava(env, kDjvuException, "Could not start the DjVu decoder");
    return nullptr;
  }
  ddjvu_message_set_callback(handle->context, onMessagePosted, handle);
  handle->format = ddjvu_format_create(DDJVU_FORMAT_RGBMASK32, 4, kRgbaMasks);
  if (handle->format == nullptr) {
    destroyHandle(handle);
    throwJava(env, kDjvuException, "Could not set up the DjVu pixel format");
    return nullptr;
  }
  ddjvu_format_set_row_order(handle->format, 1);    // first row at the top, like a Bitmap
  ddjvu_format_set_y_direction(handle->format, 1);  // y grows downwards

  startOperation(handle);
  handle->document = ddjvu_document_create_by_filename_utf8(handle->context, path, 1 /* cache */);
  if (handle->document == nullptr) {
    drainMessages(handle);
    std::string message = failure(handle, "Could not open the DjVu document");
    destroyHandle(handle);
    throwJava(env, kDjvuException, message);
    return nullptr;
  }
  ddjvu_document_t *document = handle->document;
  ddjvu_status_t status = waitForJob(handle, [document] { return ddjvu_document_decoding_status(document); });
  if (status != DDJVU_JOB_OK) {
    std::string message = failure(handle, "Not a readable DjVu document");
    destroyHandle(handle);
    throwJava(env, kDjvuException, message);
    return nullptr;
  }
  handle->pageCount = ddjvu_document_get_pagenum(document);
  if (handle->pageCount <= 0) {
    destroyHandle(handle);
    throwJava(env, kDjvuException, "The DjVu document has no pages");
    return nullptr;
  }
  return handle;
}

void fillWhite(void *pixels, const AndroidBitmapInfo &info) {
  for (uint32_t y = 0; y < info.height; y++) {
    uint32_t *row = reinterpret_cast<uint32_t *>(static_cast<char *>(pixels) + static_cast<size_t>(y) * info.stride);
    for (uint32_t x = 0; x < info.width; x++) row[x] = kOpaqueWhite;
  }
}

// Draws a decoded page over the whole bitmap. Throws (and returns) on a bitmap error.
void drawPage(JNIEnv *env, ddjvu_page_t *page, const DjvuHandle *handle, jobject bitmap,
              const AndroidBitmapInfo &info) {
  void *pixels = nullptr;
  if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == nullptr) {
    throwJava(env, kIllegalArgument, "Could not lock the bitmap's pixels");
    return;
  }
  ddjvu_rect_t rect;
  rect.x = 0;
  rect.y = 0;
  rect.w = info.width;
  rect.h = info.height;
  int rendered = ddjvu_page_render(page, DDJVU_RENDER_COLOR, &rect, &rect, handle->format, info.stride,
                                   static_cast<char *>(pixels));
  if (!rendered) {
    // Nothing DjVuLibre can draw (for example a page that only has a JPEG layer): leave it blank.
    fillWhite(pixels, info);
  }
  AndroidBitmap_unlockPixels(env, bitmap);
}

void renderPage(JNIEnv *env, DjvuHandle *handle, jint pageIndex, jobject bitmap) {
  AndroidBitmapInfo info;
  if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
    throwJava(env, kIllegalArgument, "Not a usable bitmap");
    return;
  }
  if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0) {
    throwJava(env, kIllegalArgument, "The bitmap must be a non-empty ARGB_8888 bitmap");
    return;
  }
  startOperation(handle);
  ddjvu_page_t *page = ddjvu_page_create_by_pageno(handle->document, pageIndex);
  if (page == nullptr) {
    drainMessages(handle);
    throwJava(env, kDjvuException, failure(handle, "Could not open the page"));
    return;
  }
  ddjvu_status_t status = waitForJob(handle, [page] { return ddjvu_page_decoding_status(page); });
  if (status != DDJVU_JOB_OK) {
    ddjvu_page_release(page);
    throwJava(env, kDjvuException, failure(handle, "Could not decode the page"));
    return;
  }
  drawPage(env, page, handle, bitmap, info);
  ddjvu_page_release(page);
}

// ---------------------------------------------------------------------------------------------------
// Hidden text (the OCR layer many scanned DjVu books carry)

// Per word (seven numbers): the line it is on, its first and past-last byte in the text, and its box (xmin,
// ymin, xmax, ymax: pixels from the stored page's bottom left, before the page's own rotation).
// Ahead of the words: the stored page's width and height, and its rotation (quarter turns anticlockwise).
const int kNumbersAhead = 3;
// page > column > region > para > line > word is six deep; anything deeper is a damaged file.
const int kMaxZoneDepth = 16;

struct PageText {
  std::string utf8;
  std::vector<jint> numbers;
  int lines = 0;
};

bool isZone(miniexp_t type, const char *name) {
  return miniexp_symbolp(type) && strcmp(miniexp_to_name(type), name) == 0;
}

// One zone of DjVuLibre's s-expression: (type xmin ymin xmax ymax "text") at the finest level asked for,
// else (type xmin ymin xmax ymax zone...). Text coarser than a line (a paragraph's) counts as a line of its own.
void collectZone(miniexp_t zone, int line, PageText &out, int depth) {
  if (!miniexp_consp(zone) || depth > kMaxZoneDepth) return;
  miniexp_t type = miniexp_car(zone);
  if (!miniexp_symbolp(type)) return;
  jint box[4];
  miniexp_t rest = miniexp_cdr(zone);
  for (int i = 0; i < 4; i++) {
    miniexp_t number = miniexp_car(rest);
    if (!miniexp_numberp(number)) return;
    box[i] = miniexp_to_int(number);
    rest = miniexp_cdr(rest);
  }
  if (isZone(type, "line")) line = out.lines++;
  miniexp_t first = miniexp_car(rest);
  if (miniexp_stringp(first)) {
    const char *text = miniexp_to_str(first);
    if (text == nullptr || *text == '\0') return;
    if (line < 0) line = out.lines++;
    jint start = static_cast<jint>(out.utf8.size());
    out.utf8 += text;
    out.numbers.push_back(line);
    out.numbers.push_back(start);
    out.numbers.push_back(static_cast<jint>(out.utf8.size()));
    for (int i = 0; i < 4; i++) out.numbers.push_back(box[i]);
    return;
  }
  for (; miniexp_consp(rest); rest = miniexp_cdr(rest)) collectZone(miniexp_car(rest), line, out, depth + 1);
}

// {byte[] words, int[] numbers} for Java, or null (with an exception pending) when Java is out of memory.
jobjectArray toJava(JNIEnv *env, const PageText &text) {
  jclass objectClass = env->FindClass("java/lang/Object");
  if (objectClass == nullptr) return nullptr;
  jobjectArray result = env->NewObjectArray(2, objectClass, nullptr);
  env->DeleteLocalRef(objectClass);
  if (result == nullptr) return nullptr;
  jsize byteCount = static_cast<jsize>(text.utf8.size());
  jbyteArray bytes = env->NewByteArray(byteCount);
  if (bytes == nullptr) return nullptr;
  env->SetByteArrayRegion(bytes, 0, byteCount, reinterpret_cast<const jbyte *>(text.utf8.data()));
  jsize numberCount = static_cast<jsize>(text.numbers.size());
  jintArray numbers = env->NewIntArray(numberCount);
  if (numbers == nullptr) return nullptr;
  env->SetIntArrayRegion(numbers, 0, numberCount, text.numbers.data());
  env->SetObjectArrayElement(result, 0, bytes);
  env->SetObjectArrayElement(result, 1, numbers);
  return result;
}

// The words of a page's hidden text, or null when the page has none (or it can't be read).
jobjectArray pageText(JNIEnv *env, DjvuHandle *handle, jint pageIndex) {
  startOperation(handle);
  ddjvu_document_t *document = handle->document;
  ddjvu_pageinfo_t info;
  memset(&info, 0, sizeof(info));
  ddjvu_status_t status = waitForJob(handle, [document, pageIndex, &info] {
    return ddjvu_document_get_pageinfo(document, pageIndex, &info);
  });
  if (status != DDJVU_JOB_OK || info.width <= 0 || info.height <= 0) return nullptr;
  miniexp_t text = miniexp_dummy;
  status = waitForJob(handle, [document, pageIndex, &text] {
    text = ddjvu_document_get_pagetext(document, pageIndex, "word");
    return text == miniexp_dummy ? DDJVU_JOB_STARTED : DDJVU_JOB_OK;
  });
  if (status != DDJVU_JOB_OK || !miniexp_consp(text)) {
    // The empty list (no hidden text on this page), or the symbol failed or stopped.
    if (text != miniexp_dummy) ddjvu_miniexp_release(document, text);
    return nullptr;
  }
  PageText out;
  int rotation = info.rotation & 3;
  out.numbers.push_back((rotation & 1) ? info.height : info.width);
  out.numbers.push_back((rotation & 1) ? info.width : info.height);
  out.numbers.push_back(rotation);
  collectZone(text, -1, out, 0);
  ddjvu_miniexp_release(document, text);
  if (out.numbers.size() <= static_cast<size_t>(kNumbersAhead)) return nullptr;
  return toJava(env, out);
}

}  // namespace

// -----------------------------------------------------------------------------------------------------
// Entry points: com.example.unifiedreader.core.djvu.DjvuNative

#define DJVU_JNI(name) Java_com_example_unifiedreader_core_djvu_DjvuNative_##name

extern "C" {

JNIEXPORT jlong JNICALL DJVU_JNI(nativeOpen)(JNIEnv *env, jclass, jbyteArray utf8Path) {
  try {
    if (utf8Path == nullptr) {
      throwJava(env, kIllegalArgument, "No path");
      return 0;
    }
    jsize length = env->GetArrayLength(utf8Path);
    std::string path(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(utf8Path, 0, length, reinterpret_cast<jbyte *>(&path[0]));
    if (env->ExceptionCheck()) return 0;
    if (path.find('\0') != std::string::npos) {
      throwJava(env, kIllegalArgument, "The path contains a NUL byte");
      return 0;
    }
    DjvuHandle *handle = openDocument(env, path.c_str());
    return static_cast<jlong>(reinterpret_cast<intptr_t>(handle));
  } catch (const std::bad_alloc &) {
    throwJava(env, kDjvuException, "Out of memory while opening the DjVu document");
  } catch (...) {
    throwJava(env, kDjvuException, "Unexpected error while opening the DjVu document");
  }
  return 0;
}

JNIEXPORT jint JNICALL DJVU_JNI(nativePageCount)(JNIEnv *env, jclass, jlong value) {
  DjvuHandle *handle = fromJava(env, value);
  return handle == nullptr ? 0 : handle->pageCount;
}

// Returns {width, height, dpi}, width and height with the page's initial rotation applied
// (ddjvu_document_get_pageinfo already swaps them for 90 and 270 degrees).
JNIEXPORT jintArray JNICALL DJVU_JNI(nativePageInfo)(JNIEnv *env, jclass, jlong value, jint pageIndex) {
  try {
    DjvuHandle *handle = fromJava(env, value);
    if (handle == nullptr || !checkPageIndex(env, handle, pageIndex)) return nullptr;
    startOperation(handle);
    ddjvu_document_t *document = handle->document;
    ddjvu_pageinfo_t info;
    memset(&info, 0, sizeof(info));
    ddjvu_status_t status = waitForJob(handle, [document, pageIndex, &info] {
      return ddjvu_document_get_pageinfo(document, pageIndex, &info);
    });
    if (status != DDJVU_JOB_OK || info.width <= 0 || info.height <= 0) {
      throwJava(env, kDjvuException, failure(handle, "Could not read the page size"));
      return nullptr;
    }
    jint values[3] = {info.width, info.height, info.dpi};
    jintArray result = env->NewIntArray(3);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 3, values);
    return result;
  } catch (const std::bad_alloc &) {
    throwJava(env, kDjvuException, "Out of memory while reading the page size");
  } catch (...) {
    throwJava(env, kDjvuException, "Unexpected error while reading the page size");
  }
  return nullptr;
}

JNIEXPORT void JNICALL DJVU_JNI(nativeRenderPage)(JNIEnv *env, jclass, jlong value, jint pageIndex,
                                                  jobject bitmap) {
  try {
    DjvuHandle *handle = fromJava(env, value);
    if (handle == nullptr || !checkPageIndex(env, handle, pageIndex)) return;
    if (bitmap == nullptr) {
      throwJava(env, kIllegalArgument, "No bitmap");
      return;
    }
    renderPage(env, handle, pageIndex, bitmap);
  } catch (const std::bad_alloc &) {
    throwJava(env, kDjvuException, "Out of memory while rendering the page");
  } catch (...) {
    throwJava(env, kDjvuException, "Unexpected error while rendering the page");
  }
}

// Returns {byte[] utf8, int[] numbers} for a page's hidden text, or null when it has none. numbers holds the
// stored page's width, height and rotation, then per word: line, byte start, byte end, xmin, ymin, xmax, ymax.
JNIEXPORT jobjectArray JNICALL DJVU_JNI(nativePageText)(JNIEnv *env, jclass, jlong value, jint pageIndex) {
  try {
    DjvuHandle *handle = fromJava(env, value);
    if (handle == nullptr || !checkPageIndex(env, handle, pageIndex)) return nullptr;
    return pageText(env, handle, pageIndex);
  } catch (const std::bad_alloc &) {
    throwJava(env, kDjvuException, "Out of memory while reading the page's text");
  } catch (...) {
    throwJava(env, kDjvuException, "Unexpected error while reading the page's text");
  }
  return nullptr;
}

JNIEXPORT void JNICALL DJVU_JNI(nativeClose)(JNIEnv *, jclass, jlong value) {
  try {
    destroyHandle(reinterpret_cast<DjvuHandle *>(static_cast<intptr_t>(value)));
  } catch (...) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Error while closing a DjVu document");
  }
}

}  // extern "C"
