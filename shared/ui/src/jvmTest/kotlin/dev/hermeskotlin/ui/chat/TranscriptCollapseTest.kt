package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.ui.LocalShortcuts
import dev.hermeskotlin.ui.Shortcut
import dev.hermeskotlin.ui.ShortcutBus
import dev.hermeskotlin.ui.preview.PreviewChatActions
import dev.hermeskotlin.ui.voice.DictationState
import dev.hermeskotlin.ui.voice.VoiceChatState
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.TranscriptEvent
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class TranscriptCollapseTest {
    private val long = (1..100).joinToString("\n\n") { "Reasoning paragraph $it." }
    private val events = TranscriptEvent.SystemMessages((1..120).map { TranscriptEvent.Label("Background event $it") }, "first")

    @Test fun reasoningBannerWaitsForAScreenThenClosesFromTheMiddle() = runComposeUiTest {
        show(later = true)
        onNodeWithText("Close reasoning").assertDoesNotExist()
        onNodeWithText("Reasoning").performClick()
        onNodeWithText("Close reasoning").assertDoesNotExist()
        onNodeWithTag("transcript").performTouchInput { swipeUp(startY = height * .7f, endY = height * .55f, durationMillis = 1000) }
        onNodeWithText("Close reasoning").assertDoesNotExist()
        scrollPage()
        onNodeWithText("Close reasoning").assertIsDisplayed().performClick()
        onNodeWithText("Close reasoning").assertDoesNotExist()
        onNodeWithText("Reasoning").assertIsDisplayed()
        onNodeWithText("The following answer.").assertIsDisplayed()
    }

    @Test fun systemMessagesCanCloseFromTheMiddleInLightTheme() = runComposeUiTest {
        show(system = true, light = true)
        onNodeWithText("System messages", substring = true).performClick()
        onNodeWithText("Close system messages").assertDoesNotExist()
        scrollPage()
        onNodeWithText("Close system messages").assertIsDisplayed().performClick()
        onNodeWithText("System messages", substring = true).assertIsDisplayed()
        onNodeWithText("The following answer.").assertIsDisplayed()
        onNodeWithText("Close system messages").assertDoesNotExist()
    }

    @Test fun shortReasoningHasNoBanner() = runComposeUiTest {
        show(reasoning = "Short reasoning.")
        onNodeWithText("Reasoning").performClick()
        onNodeWithText("Short reasoning.").assertIsDisplayed()
        onNodeWithText("Close reasoning").assertDoesNotExist()
    }

    @Test fun changingChatsRemovesTheOldBanner() = runComposeUiTest {
        var owner by mutableStateOf("first")
        show(owner = { owner })
        onNodeWithText("Reasoning").performClick()
        scrollPage()
        onNodeWithText("Close reasoning").assertIsDisplayed()
        runOnIdle { owner = "second" }
        onNodeWithText("Close reasoning").assertDoesNotExist()
    }

    @Test fun leavingReasoningHidesTheBanner() = runComposeUiTest {
        show(reasoning = (1..60).joinToString("\n\n") { "Paragraph $it." }, later = true)
        onNodeWithText("Reasoning").performClick()
        scrollPage()
        onNodeWithText("Close reasoning").assertIsDisplayed()
        repeat(12) { onNodeWithTag("transcript").performTouchInput { swipeUp() } }
        onNodeWithText("Close reasoning").assertDoesNotExist()
    }

    @Test fun longMarkdownCanCloseInShortLandscapeViewport() = runComposeUiTest {
        show(reasoning = "```text\r\n${"longword".repeat(40)}\r\n```\r\n\r\n" + long, height = 320, light = true)
        onNodeWithText("Reasoning").performClick()
        scrollPage()
        onNodeWithText("Close reasoning").assertIsDisplayed().performClick()
        onNodeWithText("The following answer.").assertIsDisplayed()
    }

    @Test fun bannerIsWiredIntoTheRealConversationAndClearsOnOwnerChange() = runComposeUiTest {
        var owner by mutableStateOf("first")
        setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(LocalDesktopLayout provides false) {
                    Box(Modifier.size(360.dp, 640.dp)) {
                        CollapseConversation(long, owner = owner)
                    }
                }
            }
        }
        onNodeWithText("Reasoning").performClick()
        repeat(3) { onNode(hasScrollToIndexAction()).performTouchInput { swipeUp(startY = height * .65f, endY = height * .1f, durationMillis = 1000) } }
        onNodeWithText("Close reasoning").assertIsDisplayed()
        onNodeWithContentDescription("Jump to latest").assertDoesNotExist()
        runOnIdle { owner = "second" }
        onNodeWithText("Close reasoning").assertDoesNotExist()
    }

    @Test fun latestReplyCanCloseAfterScrollingBackAndReadingDownAgain() = runComposeUiTest {
        setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(LocalDesktopLayout provides false) {
                    Box(Modifier.size(360.dp, 640.dp)) { CollapseConversation(long, later = false) }
                }
            }
        }
        onNodeWithText("Reasoning").performClick()
        repeat(12) { onNode(hasScrollToIndexAction()).performTouchInput { swipeDown(startY = height * .15f, endY = height * .65f, durationMillis = 1000) } }
        onNodeWithText("Reasoning").assertIsDisplayed()
        onNodeWithText("Close reasoning").assertDoesNotExist()
        repeat(3) { onNode(hasScrollToIndexAction()).performTouchInput { swipeUp(startY = height * .65f, endY = height * .1f, durationMillis = 1000) } }
        onNodeWithText("Close reasoning").assertIsDisplayed().performClick()
        onNodeWithText("The following answer.").assertIsDisplayed()
    }

    @Test fun pageDownOffersTheSameBannerOnDesktop() = runComposeUiTest {
        val shortcuts = ShortcutBus()
        setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(LocalDesktopLayout provides true, LocalShortcuts provides shortcuts) {
                    Box(Modifier.size(360.dp, 640.dp)) { CollapseConversation(long) }
                }
            }
        }
        onNodeWithText("Reasoning").assertIsDisplayed().performClick()
        repeat(3) { runOnIdle { shortcuts.fire(Shortcut.PageDown) }; waitForIdle() }
        onNodeWithText("Close reasoning").assertIsDisplayed().performClick()
        onNodeWithText("The following answer.").assertIsDisplayed()
    }

    @Test fun streamingGrowthAloneDoesNotEnableTheBanner() = runComposeUiTest {
        var text by mutableStateOf("Short reasoning.")
        setContent {
            HermesTheme(ColorScheme.Dark) {
                TranscriptCollapseHost(Modifier.size(360.dp, 640.dp)) {
                    LazyColumn { item { Reasoning(text, streaming = true) } }
                }
            }
        }
        onNodeWithText("Reasoning").performClick()
        runOnIdle { text = long }
        onNodeWithText("Close reasoning").assertDoesNotExist()
    }

    private fun ComposeUiTest.scrollPage() {
        repeat(2) { onNodeWithTag("transcript").performTouchInput { swipeUp(durationMillis = 1000) } }
        waitForIdle()
    }

    private fun ComposeUiTest.show(
        reasoning: String = long, system: Boolean = false, light: Boolean = false,
        height: Int = 640, later: Boolean = false, owner: () -> String = { "chat" },
    ) {
        setContent {
            HermesTheme(if (light) ColorScheme.Light else ColorScheme.Dark) {
                CompositionLocalProvider(LocalDesktopLayout provides false) {
                    key(owner()) {
                        TranscriptCollapseHost(Modifier.size(360.dp, height.dp)) {
                            LazyColumn(Modifier.fillMaxSize().testTag("transcript")) {
                                item {
                                    if (system) {
                                        TranscriptEventRow(events)
                                        Text("The following answer.")
                                    } else AssistantReply(ChatMessage.Assistant("reply", text = "The following answer.", reasoning = reasoning), null, null, last = true)
                                }
                                if (later) item { Box(Modifier.height(1500.dp)) { Text("Later messages") } }
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
internal fun CollapseConversation(reasoning: String, system: TranscriptEvent.SystemMessages? = null, owner: String = "sample", later: Boolean = true) {
    val first = if (system == null) listOf(ChatMessage.Assistant("reply", text = "The following answer.", reasoning = reasoning, timestamp = 1793700001.0))
        else listOf(ChatMessage.Event("system", system, timestamp = 1793700001.0), ChatMessage.Assistant("answer", text = "The following answer.", timestamp = 1793700002.0))
    val messages = listOf(ChatMessage.User("prompt", "Review the options and explain the result.", timestamp = 1793700000.0)) + first + if (later) (1..8).map { ChatMessage.Assistant("later-$it", text = "Later answer $it.\n\n" + "The conversation continues here.\n\n".repeat(3), timestamp = 1793700002.0 + it) } else emptyList()
    ChatView(
        title = "Conversation", state = ChatState(messages = messages, storedSessionId = "sample", historyLoaded = true),
        conversationKey = owner to "sample", picker = ModelPickerState(), connected = true,
        attachments = emptyList(), attachmentError = null, voiceChat = VoiceChatState(), dictation = DictationState(),
        suggestions = emptyList(), sprite = null, placeholder = "Message", notice = null, actions = PreviewChatActions(), readUpTo = 1793699000.0,
        onOpenSidebar = {}, onNewChat = null, onOpenMenu = null, onOpenModels = {}, onAttach = {}, onDictate = {},
        onVoiceChat = {}, onOpenPets = {}, onViewImage = {}, onNotice = {},
    )
}
