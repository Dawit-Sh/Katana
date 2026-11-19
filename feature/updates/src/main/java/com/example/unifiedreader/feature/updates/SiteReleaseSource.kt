package com.example.unifiedreader.feature.updates

import com.example.unifiedreader.core.model.runSuspendCatching
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The newest release as the download site publishes it: `latest.json`, which the Website workflow
 * (`.github/workflows/pages.yml`) writes from the GitHub release each time one is published. Reading it
 * is a plain file from GitHub Pages, so it has none of the API's limit of 60 requests an hour per
 * address (an address many people share on some networks).
 *
 * Throws when the file can't be read or isn't whole (no version, no APKs, an APK without its SHA-256 or
 * not on https), so [FallbackReleaseSource] asks GitHub instead; null, as from GitHub, when no APK in it
 * installs on this device.
 */
class SiteReleaseSource(
    private val httpClient: HttpClient,
    /** `Build.SUPPORTED_ABIS`, best first: picks the release's APK for this device. */
    private val supportedAbis: List<String> = emptyList(),
    private val url: String = LATEST_RELEASE_URL,
) : AppReleaseSource {
    override suspend fun latestRelease(): AppRelease? {
        val response = httpClient.get(url) {
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.UserAgent, "katana-android")
        }
        if (!response.status.isSuccess()) {
            throw IOException("The download site answered ${response.status.value} for latest.json")
        }
        return parseSiteRelease(response.bodyAsText(), supportedAbis)
    }

    companion object {
        /** The download site's record of the newest release; the one place the app names it. */
        const val LATEST_RELEASE_URL = "https://dawitsh.dev/Katana/latest.json"
    }
}

/**
 * The download site first, GitHub's API only when the site's file can't be read (the site down, the file
 * missing or not whole). A site that answers with no APK for this device is believed: GitHub has the same
 * release.
 */
class FallbackReleaseSource(
    private val primary: AppReleaseSource,
    private val fallback: AppReleaseSource,
) : AppReleaseSource {
    override suspend fun latestRelease(): AppRelease? =
        runSuspendCatching { primary.latestRelease() }.getOrElse { fallback.latestRelease() }
}

private val siteJson = Json { ignoreUnknownKeys = true }

/**
 * The release in a `latest.json`. Throws when it isn't whole, so the caller can ask GitHub instead: every
 * check the GitHub release gets (the SHA-256 of the APK among them) must hold for this one too.
 */
internal fun parseSiteRelease(
    body: String,
    supportedAbis: List<String> = emptyList(),
): AppRelease? {
    val release = siteJson.decodeFromString<SiteReleaseDto>(body)
    val tagName = release.tag ?: release.version?.let { "v$it" }
    val version = AppVersion.parse(tagName)
    check(version != null && tagName != null) { "latest.json names no version" }
    check(release.apks.isNotEmpty()) { "latest.json lists no APKs" }
    val apks = release.apks.values.filter { apk -> apk.name.endsWith(".apk", ignoreCase = true) }
    val apkName = chooseReleaseApk(apks.map(SiteApkDto::name), supportedAbis) ?: return null
    val apk = apks.first { it.name == apkName }
    check(apk.url.startsWith("https://")) { "latest.json's link to ${apk.name} isn't https" }
    val sha256 = apk.digest
        ?.takeIf { it.startsWith("sha256:") }
        ?.removePrefix("sha256:")
        ?.lowercase()
        ?.takeIf { it.length == SHA256_HEX_LENGTH && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }
    checkNotNull(sha256) { "latest.json has no SHA-256 for ${apk.name}" }
    return AppRelease(
        version = version,
        tagName = tagName,
        notes = parseReleaseNotes(release.notes),
        pageUrl = release.page.orEmpty(),
        apk = AppReleaseApk(
            name = apk.name,
            downloadUrl = apk.url,
            sizeBytes = apk.size,
            sha256 = sha256,
        ),
    )
}

private const val SHA256_HEX_LENGTH = 64

@Serializable
private data class SiteReleaseDto(
    val version: String? = null,
    val tag: String? = null,
    val page: String? = null,
    /** The release's notes as written on GitHub (Markdown); only its bullet lines are shown. */
    val notes: String? = null,
    val apks: Map<String, SiteApkDto> = emptyMap(),
)

@Serializable
private data class SiteApkDto(
    val name: String,
    val url: String,
    val size: Long = 0,
    /** "sha256:<hex>", as GitHub gives it for the asset. */
    val digest: String? = null,
)
