# libkatana_djvu.so: DjVuLibre's libdjvu (vendored in djvulibre/, see djvulibre/KATANA.md) plus the JNI
# glue in djvu_jni.cpp, as one shared library.
cmake_minimum_required(VERSION 3.22.1)
project(katana_djvu CXX)

# libdjvulibre_la_SOURCES from DjVuLibre 3.5.28's libdjvu/Makefile.am (the .cpp files).
set(DJVULIBRE_SOURCES
    Arrays.cpp BSByteStream.cpp BSEncodeByteStream.cpp ByteStream.cpp DataPool.cpp DjVmDir.cpp
    DjVmDir0.cpp DjVmDoc.cpp DjVmNav.cpp DjVuAnno.cpp DjVuDocEditor.cpp DjVuDocument.cpp
    DjVuDumpHelper.cpp DjVuErrorList.cpp DjVuFile.cpp DjVuFileCache.cpp DjVuGlobal.cpp
    DjVuGlobalMemory.cpp DjVuImage.cpp DjVuInfo.cpp DjVuMessage.cpp DjVuMessageLite.cpp
    DjVuNavDir.cpp DjVuPalette.cpp DjVuPort.cpp DjVuText.cpp DjVuToPS.cpp GBitmap.cpp
    GContainer.cpp GException.cpp GIFFManager.cpp GMapAreas.cpp GOS.cpp GPixmap.cpp GRect.cpp
    GScaler.cpp GSmartPointer.cpp GString.cpp GThreads.cpp GURL.cpp GUnicode.cpp
    IFFByteStream.cpp IW44EncodeCodec.cpp IW44Image.cpp JB2EncodeCodec.cpp JB2Image.cpp
    JPEGDecoder.cpp MMRDecoder.cpp MMX.cpp UnicodeByteStream.cpp XMLParser.cpp XMLTags.cpp
    ZPCodec.cpp atomic.cpp ddjvuapi.cpp debug.cpp miniexp.cpp
)
list(TRANSFORM DJVULIBRE_SOURCES PREPEND "${CMAKE_CURRENT_SOURCE_DIR}/djvulibre/")

add_library(katana_djvu SHARED djvu_jni.cpp ${DJVULIBRE_SOURCES})

# DjVuLibre predates C++17 (GBitmap.h still uses `register`), so build it as C++14.
set_target_properties(katana_djvu PROPERTIES CXX_STANDARD 14 CXX_STANDARD_REQUIRED ON CXX_EXTENSIONS ON)

# config.h sits next to this file, outside the vendored tree.
target_include_directories(katana_djvu PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}/djvulibre"
)
target_compile_definitions(katana_djvu PRIVATE HAVE_CONFIG_H)
target_compile_options(katana_djvu PRIVATE
    -O2
    -fexceptions
    -frtti
    -ffunction-sections
    -fdata-sections
    # Only the JNI entry points (JNIEXPORT) stay exported, so the linker can drop the unused rest of libdjvu.
    -fvisibility=hidden
    -fvisibility-inlines-hidden
)
# DjVuLibre's own sources are old C++ and noisy under a modern clang; keep the build log readable.
set_source_files_properties(${DJVULIBRE_SOURCES} PROPERTIES COMPILE_OPTIONS "-w")

target_link_options(katana_djvu PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
)
target_link_libraries(katana_djvu PRIVATE jnigraphics log)
