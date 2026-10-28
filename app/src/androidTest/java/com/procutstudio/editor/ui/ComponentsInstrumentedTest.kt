package com.procutstudio.editor.ui

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.procutstudio.editor.ui.components.ProCutChip
import com.procutstudio.editor.ui.components.SecondaryButton
import com.procutstudio.editor.ui.theme.ProCutTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation smoke test for the shared design-system components.
 *
 * These components are used by every screen, so a device test that renders them through the real
 * theme catches the failures JVM tests cannot see (missing resources, theme crashes, broken touch
 * targets). Run with `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class ComponentsInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun chip_renders_and_reports_clicks() {
        var clicks = 0
        composeRule.setContent {
            ProCutTheme {
                ProCutChip(label = "1080p", selected = true, onClick = { clicks++ })
            }
        }

        composeRule.onNodeWithText("1080p").assertExists()
        composeRule.onNodeWithText("1080p").performClick()

        composeRule.runOnIdle { assertTrue(clicks == 1) }
    }

    @Test
    fun button_renders_its_label() {
        composeRule.setContent {
            ProCutTheme {
                SecondaryButton(text = "Back to projects", onClick = {})
            }
        }

        composeRule.onNodeWithText("Back to projects").assertExists()
    }
}
