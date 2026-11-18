# R8 for the release build (smaller code, faster start on the Fire 7). The libraries bring their own
# rules (Room, Hilt, WorkManager, kotlinx.serialization, OkHttp, ML Kit, DjVu in core/djvu); these are
# the rest.

# Names stay readable: crash logs from the Fire name the real classes, and nothing that looks a class up
# by name (saved state, WorkManager, Readium's fragments) can miss.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Readium ships no rules of its own and is used by reflection in places (its navigator fragments are
# restored by name, its JavaScript bridges, serialized preferences): keep it whole.
-keep class org.readium.** { *; }
-dontwarn org.readium.**

# isRetryableNetworkFailure (core:network) matches these by their simple names.
-keepnames class io.ktor.client.network.sockets.ConnectTimeoutException
-keepnames class io.ktor.client.network.sockets.SocketTimeoutException
-keepnames class io.ktor.client.plugins.HttpRequestTimeoutException
