package com.example.unifiedreader.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Back through [AppScreenHost]: a host with somewhere to go takes Back there, a host inside it
 * (a settings page over the settings hub) goes first, and with nowhere left Back leaves the app.
 */
@RunWith(RobolectricTestRunner::class)
class AppScreenHostBackTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun `back goes to the host's target, then leaves the app`() {
        var screen by mutableStateOf("Settings")
        compose.setContent {
            AppScreenHost(
                screen = screen,
                back = if (screen != "Library") AppScreenBack("Library") { screen = "Library" } else null,
            ) { shown -> Text(shown) }
        }

        pressBack()

        assertEquals("Library", screen)
        compose.onNodeWithText("Library").assertExists()
        compose.onNodeWithText("Settings").assertDoesNotExist()
        assertFalse(compose.activity.isFinishing)

        pressBack()

        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun `a page inside a screen goes back first, then the screen`() {
        var topLevel by mutableStateOf("Settings")
        var page by mutableStateOf<String?>("Theme")
        compose.setContent {
            AppScreenHost(
                screen = topLevel,
                back = if (topLevel != "Library") AppScreenBack("Library") { topLevel = "Library" } else null,
            ) { shownTopLevel ->
                if (shownTopLevel == "Settings") {
                    AppScreenHost(
                        screen = page,
                        back = page?.let { AppScreenBack<String?>(null) { page = null } },
                    ) { shownPage -> Text(shownPage ?: "Hub") }
                } else {
                    Text(shownTopLevel)
                }
            }
        }

        pressBack()

        assertEquals(null, page)
        assertEquals("Settings", topLevel)
        compose.onNodeWithText("Hub").assertExists()

        pressBack()

        assertEquals("Library", topLevel)
        compose.onNodeWithText("Library").assertExists()
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    fun `a change ends with only the new screen`() {
        var screen by mutableStateOf("Library")
        compose.setContent {
            AppScreenHost(screen = screen, ground = true) { shown -> Text(shown) }
        }

        compose.runOnUiThread { screen = "Journal" }
        compose.waitForIdle()

        compose.onNodeWithText("Journal").assertExists()
        compose.onNodeWithText("Library").assertDoesNotExist()
    }
}
