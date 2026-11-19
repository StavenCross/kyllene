package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.designsystem.HermesTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ReasoningFooterTest {
    private val long = (1..80).joinToString("\n\n") { "Reasoning paragraph $it." }

    @Test fun longReasoningCanCloseAfterScrollingAndKeepTheAnswerVisible() = runComposeUiTest {
        show(long, ColorScheme.Dark)
        onNodeWithText("Reasoning").performClick()
        scrollPage()
        onNodeWithText("Close reasoning").assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithText("Close reasoning").assertDoesNotExist()
        onNodeWithText("The following answer.").assertIsDisplayed()
        onNodeWithText("Reasoning").performClick()
        onNodeWithText("Close reasoning").assertDoesNotExist()
        scrollPage()
        onNodeWithText("Close reasoning").assertIsDisplayed()
    }

    @Test fun shortReasoningKeepsOnlyTheUsualHeader() = runComposeUiTest {
        show("Short reasoning.", ColorScheme.Light)
        onNodeWithText("Reasoning").performClick()
        onNodeWithText("Short reasoning.").assertIsDisplayed()
        onNodeWithText("Close reasoning").assertDoesNotExist()
        onNodeWithText("Reasoning").performClick()
        onNodeWithText("Short reasoning.").assertDoesNotExist()
    }

    @Test fun longMarkdownReasoningAlsoHasTheCloseAction() = runComposeUiTest {
        show("```text\r\n${"longword".repeat(40)}\r\n```\r\n\r\n" + long, ColorScheme.Light)
        onNodeWithText("Reasoning").performClick()
        scrollPage()
        onNodeWithText("Close reasoning").assertIsDisplayed().performClick()
        onNodeWithText("The following answer.").assertIsDisplayed()
    }

    private fun ComposeUiTest.scrollPage() {
        repeat(2) { onNodeWithTag("transcript").performTouchInput { swipeUp(durationMillis = 1000) } }
        waitForIdle()
    }

    private fun ComposeUiTest.show(reasoning: String, scheme: ColorScheme) {
        setContent {
            HermesTheme(scheme) {
                TranscriptCollapseHost(Modifier.size(360.dp, 640.dp)) {
                    LazyColumn(Modifier.fillMaxSize().testTag("transcript")) {
                        item {
                            AssistantReply(ChatMessage.Assistant("reply", text = "The following answer.", reasoning = reasoning), null, null, last = true)
                        }
                    }
                }
            }
        }
    }
}
