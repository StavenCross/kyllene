package dev.hermeskotlin.ui.chat

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.TurnUsage
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.ui.LocalAppSettings
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

/**
 * The row under a finished reply on a narrow screen at the largest text: the app's Largest (1.3) on Android's
 * font scale 2.0. The token count used to take the row and leave Copy no width, and its end trim then laid it
 * out at a negative width, which crashed the chat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplyActionsFitTest {

    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    // The library ships no manifest declaring an activity, so register one before createComposeRule.
    @get:Rule
    val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(composeRule)

    @Test
    fun theActionsKeepTheirWidthBesideALongCount() {
        composeRule.setContent {
            HermesTheme(ColorScheme.Light) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, 2.6f),
                    // The count is what squeezed the actions, so it's shown whatever the default.
                    LocalAppSettings provides AppSettings(showUsage = true),
                ) {
                    Box(Modifier.width(300.dp)) {
                        AssistantReply(
                            ChatMessage.Assistant(
                                key = "reply",
                                text = "Done.",
                                reactionRowId = 1,
                                usage = TurnUsage(input = 184_000, output = 61_200, reasoning = 0, calls = 1),
                            ),
                            onBranch = {},
                            onRegenerate = {},
                            last = true,
                            onReadAloud = {},
                            onReact = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val touch = with(composeRule.density) { 48.dp.toPx() }
        val copy = composeRule.onNodeWithText("Copy").fetchSemanticsNode().boundsInRoot
        val more = composeRule.onNodeWithText("More").fetchSemanticsNode().boundsInRoot
        assertTrue(copy.width >= touch - 1 && more.width >= touch - 1)
        assertTrue(kotlin.math.abs(copy.center.y - more.center.y) < 1f)
        assertTrue(copy.right <= more.left)

        composeRule.onNodeWithText("More").performClick()
        val menuLabels = listOf("Got it", "Continue", "Verify", "Explain", "Branch from here", "Regenerate", "Read aloud")
        val labelStarts = menuLabels.map { label ->
            composeRule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        }
        assertTrue(labelStarts.max() - labelStarts.min() < 1f, "The emoji and command labels should share a column")
    }

    /** Registers the Compose host activity Robolectric would otherwise fail to resolve. */
    private class RegisterHostActivity : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(
                    ComponentName(app.packageName, ComponentActivity::class.java.name),
                )
                base.evaluate()
            }
        }
    }
}
