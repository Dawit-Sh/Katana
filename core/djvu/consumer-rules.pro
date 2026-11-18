# libkatana_djvu.so throws this class by name (JNI FindClass/ThrowNew), so a minified app must keep its
# name and its (String) constructor. The native methods of DjvuNative are kept by the default Android rules.
-keep class com.example.unifiedreader.core.djvu.DjvuException {
    <init>(java.lang.String);
}
