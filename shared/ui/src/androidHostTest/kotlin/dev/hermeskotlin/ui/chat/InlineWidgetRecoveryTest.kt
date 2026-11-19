package dev.hermeskotlin.ui.chat

import android.content.ComponentName
import android.content.pm.PackageInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ReplyMedia
import dev.hermeskotlin.core.media.FileText
import dev.hermeskotlin.core.media.GatewayFile
import dev.hermeskotlin.designsystem.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowWebView
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h700dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InlineWidgetRecoveryTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(
                    ComponentName(app.packageName, ComponentActivity::class.java.name),
                )
                base.evaluate()
            }
        }
    }).around(composeRule)

    @Test
    fun updateOpensTheProviderListingAndRetryRechecksWithoutRefetching() {
        setProviderVersion("124.0.0.0")
        var loads = 0
        val file = GatewayFile("/tmp/checklist.html")
        val files = WidgetFiles(
            identity = "test-profile",
            load = {
                loads++
                FileLoad.Ready(FileText("<p>Checklist</p>", 16, truncated = false, binary = false))
            },
            download = { FileDownload.Failed(FileProblem.WidgetNotFetched) },
        )
        composeRule.setContent {
            HermesTheme(ColorScheme.Dark) {
                InlineWidget(ReplyMedia(file.path, file.name, isImage = false, preview = true), file, files, "reply")
            }
        }

        composeRule.onNodeWithText(file.name).assertExists()
        composeRule.onNodeWithContentDescription("Save ${file.name}").assertExists()
        composeRule.onNodeWithContentDescription("Share: ${file.name}").assertExists()
        composeRule.onNodeWithText("This widget needs a newer Android System WebView.").assertExists()
        val update = composeRule.onNodeWithText("Update WebView")
        val retry = composeRule.onNodeWithText("Retry")
        val updateBounds = update.fetchSemanticsNode().boundsInRoot
        val retryBounds = retry.fetchSemanticsNode().boundsInRoot
        assertTrue(updateBounds.right <= retryBounds.left)
        assertEquals(updateBounds.top, retryBounds.top, absoluteTolerance = 1f)
        update.performClick()
        assertEquals(
            "market://details?id=com.android.chrome",
            Shadows.shadowOf(composeRule.activity).nextStartedActivity.dataString,
        )
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        update.performClick()
        composeRule.onNodeWithText("Check your app store or system settings for a WebView update.").assertExists()

        composeRule.runOnIdle { setProviderVersion("152.0.0.0") }
        retry.performClick()
        update.assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, loads) }
    }

    private fun setProviderVersion(version: String) {
        ShadowWebView.setCurrentWebViewPackage(PackageInfo().apply {
            packageName = "com.android.chrome"
            versionName = version
        })
    }
}
