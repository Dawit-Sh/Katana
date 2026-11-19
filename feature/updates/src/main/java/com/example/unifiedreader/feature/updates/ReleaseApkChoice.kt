package com.example.unifiedreader.feature.updates

/**
 * A Katana release carries three APKs with the same versionCode and key (see app/build.gradle.kts):
 * `katana-<version>-fire7-armeabi-v7a.apk`, `katana-<version>-arm64-v8a.apk` and
 * `katana-<version>-universal.apk`. This picks the one that installs on this device: the APK made for
 * its main ABI ([supportedAbis] is `Build.SUPPORTED_ABIS`, best first), else the universal one, else an
 * APK with no ABI in its name (an older single-APK release), else one for another ABI the device runs.
 * Null when none of them would install, so no update is offered rather than one that fails.
 */
internal fun chooseReleaseApk(
    apkNames: List<String>,
    supportedAbis: List<String>,
): String? {
    val primaryAbi = supportedAbis.firstOrNull()
    return apkNames.firstOrNull { name -> primaryAbi != null && apkAbi(name) == primaryAbi }
        ?: apkNames.firstOrNull { name -> name.contains(UNIVERSAL, ignoreCase = true) }
        ?: apkNames.firstOrNull { name -> apkAbi(name) == null }
        ?: supportedAbis.firstNotNullOfOrNull { abi -> apkNames.firstOrNull { name -> apkAbi(name) == abi } }
        ?: apkNames.firstOrNull().takeIf { supportedAbis.isEmpty() }
}

/** The single ABI an APK's name says it was built for, or null for a universal or unmarked APK. */
private fun apkAbi(name: String): String? {
    if (name.contains(UNIVERSAL, ignoreCase = true)) return null
    return KNOWN_ABIS.firstOrNull { abi -> name.contains(abi, ignoreCase = true) }
}

private const val UNIVERSAL = "universal"

/** Longest first: "x86_64" also contains "x86". */
private val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
