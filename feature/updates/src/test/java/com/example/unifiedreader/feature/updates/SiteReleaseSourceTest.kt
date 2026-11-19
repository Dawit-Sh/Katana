package com.example.unifiedreader.feature.updates

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SiteReleaseSourceTest {
    private val fire = listOf("armeabi-v7a", "armeabi")
    private val pixel = listOf("arm64-v8a", "armeabi-v7a", "armeabi")

    @Test
    fun `latest json becomes the release with this device's apk, its checksum and the notes`() {
        val release = parseSiteRelease(LatestJson, fire)!!

        assertEquals(AppVersion(listOf(1, 0, 6)), release.version)
        assertEquals("v1.0.6", release.tagName)
        assertEquals("https://github.com/Dawit-Sh/Katana/releases/tag/v1.0.6", release.pageUrl)
        assertEquals("katana-1.0.6-fire7-armeabi-v7a.apk", release.apk.name)
        assertEquals(FireUrl, release.apk.downloadUrl)
        assertEquals(37_180_015L, release.apk.sizeBytes)
        assertEquals(FireSha, release.apk.sha256)
        assertEquals(listOf("Pull down to refresh the library", "Update checks no longer use GitHub's API"), release.notes)
        assertEquals("katana-1.0.6-arm64-v8a.apk", parseSiteRelease(LatestJson, pixel)!!.apk.name)
    }

    @Test
    fun `a file that isn't whole is refused so GitHub is asked instead`() {
        // The file before this change: no digests. The SHA-256 check must keep working.
        val withoutDigest = LatestJson.replace(""""digest": "sha256:$FireSha",""", "")
        assertThrows { parseSiteRelease(withoutDigest, fire) }
        assertThrows { parseSiteRelease(LatestJson.replace("sha256:$FireSha", "sha256:not-hex"), fire) }
        assertThrows { parseSiteRelease(LatestJson.replace(FireUrl, FireUrl.replace("https", "http")), fire) }
        assertThrows { parseSiteRelease("""{"version": "1.0.6", "apks": {}}""", fire) }
        assertThrows { parseSiteRelease("""{"apks": {}}""", fire) }
        assertThrows { parseSiteRelease("<html>Not found</html>", fire) }
    }

    @Test
    fun `no apk for this device means no update, as from GitHub`() {
        val armOnly = """{"version": "1.0.6", "apks": {"fire7": {"name": "katana-1.0.6-fire7-armeabi-v7a.apk",
            "url": "$FireUrl", "size": 1, "digest": "sha256:$FireSha"}}}"""
        assertNull(parseSiteRelease(armOnly, listOf("x86_64")))
    }

    @Test
    fun `the updater reads the site first and asks GitHub only when the file can't be read`() = runTest {
        var gitHubAsked = 0
        val gitHub = AppReleaseSource {
            gitHubAsked += 1
            parseGitHubRelease(GitHubJson)
        }
        val requested = mutableListOf<String>()
        fun site(status: HttpStatusCode, body: String) = SiteReleaseSource(
            httpClient = HttpClient(
                MockEngine { request ->
                    requested += request.url.toString()
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ),
            supportedAbis = fire,
        )

        val fromSite = FallbackReleaseSource(site(HttpStatusCode.OK, LatestJson), gitHub).latestRelease()
        assertEquals("v1.0.6", fromSite?.tagName)
        assertEquals(0, gitHubAsked)
        assertEquals(listOf(SiteReleaseSource.LATEST_RELEASE_URL), requested)

        val siteDown = FallbackReleaseSource(site(HttpStatusCode.NotFound, "Not found"), gitHub).latestRelease()
        assertEquals("0.13.0", siteDown?.tagName)
        assertEquals(1, gitHubAsked)

        val oldFile = LatestJson.replace(""""digest": "sha256:$FireSha",""", "")
        FallbackReleaseSource(site(HttpStatusCode.OK, oldFile), gitHub).latestRelease()
        assertEquals(2, gitHubAsked)
    }

    @Test
    fun `a site that can't be reached and a GitHub that fails make the check fail`() = runTest {
        val down = SiteReleaseSource(HttpClient(MockEngine { throw IOException("no route") }))
        val gitHub = AppReleaseSource { throw IOException("GitHub answered 403 for the latest release") }
        try {
            FallbackReleaseSource(down, gitHub).latestRelease()
            fail("expected the check to fail")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("403"))
        }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            fail("expected the file to be refused")
        } catch (expected: Exception) {
            // Refused.
        }
    }

    private companion object {
        const val FireSha = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        const val FireUrl = "https://github.com/Dawit-Sh/Katana/releases/download/v1.0.6/katana-1.0.6-fire7-armeabi-v7a.apk"

        // As the Website workflow writes it.
        val LatestJson = """
            {
              "version": "1.0.6",
              "tag": "v1.0.6",
              "published": "2026-11-20T10:00:00Z",
              "page": "https://github.com/Dawit-Sh/Katana/releases/tag/v1.0.6",
              "notes": "Katana 1.0.6\n\n- Pull down to refresh the library\n- Update checks no longer use **GitHub's** API\n",
              "apks": {
                "arm64": {
                  "name": "katana-1.0.6-arm64-v8a.apk",
                  "url": "https://github.com/Dawit-Sh/Katana/releases/download/v1.0.6/katana-1.0.6-arm64-v8a.apk",
                  "size": 42292691,
                  "digest": "sha256:2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae"
                },
                "fire7": {
                  "name": "katana-1.0.6-fire7-armeabi-v7a.apk",
                  "url": "$FireUrl",
                  "size": 37180015,
                  "digest": "sha256:$FireSha",
                  "x": 1
                },
                "universal": {
                  "name": "katana-1.0.6-universal.apk",
                  "url": "https://github.com/Dawit-Sh/Katana/releases/download/v1.0.6/katana-1.0.6-universal.apk",
                  "size": 72942595,
                  "digest": "sha256:fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9"
                }
              }
            }
        """.trimIndent()

        val GitHubJson = """
            {
              "tag_name": "0.13.0",
              "html_url": "https://github.com/bostannnn/yabai/releases/tag/0.13.0",
              "assets": [
                {
                  "name": "yabai-0.13.0.apk",
                  "browser_download_url": "https://github.com/bostannnn/yabai/releases/download/0.13.0/yabai-0.13.0.apk",
                  "size": 1
                }
              ]
            }
        """.trimIndent()
    }
}
