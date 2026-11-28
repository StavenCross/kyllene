package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.ui.DesktopBack
import dev.hermeskotlin.ui.LocalDesktopBack
import dev.hermeskotlin.ui.preview.PreviewChatActions
import dev.hermeskotlin.ui.voice.DictationState
import dev.hermeskotlin.ui.voice.VoiceChatState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ToolTimelineTest {
    private val tools = listOf(
        ToolActivity("read", "read_file", startedAt = 50.0, detail = "Read the backup log", output = "All archives found"),
        ToolActivity(
            "check", "terminal", startedAt = 50.2, detail = "Check backup snapshots",
            input = "backupctl snapshots --latest", output = "Snapshot listing timed out", failed = true, durationSeconds = 2.0,
        ),
    )

    private val opensToolDetails = SemanticsMatcher("opens tool details") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == "Tool details"
    }

    private fun opensDetails(name: String) = hasText(name) and opensToolDetails

    private fun expandsCall(name: String) = hasText(name) and SemanticsMatcher("expands an individual call") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == "Show details"
    }

    @Test
    fun tappingACallOpensItWithFullInputAndOutput() = runComposeUiTest {
        setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(LocalDesktopLayout provides true) {
                    Tools(tools, "backup-reply")
                }
            }
        }

        onNodeWithText("Check backup snapshots").assertDoesNotExist()
        onNodeWithText("Ran 2 tools").performClick()
        onNodeWithText("+0.2s").assertExists()
        onNodeWithText("Read the backup log").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Completion unconfirmed"))
        onNodeWithText("Check backup snapshots").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Failed"))
        onNodeWithText("2.0s").assertExists()
        onNodeWithText("View tool details").assertDoesNotExist()
        onNode(opensDetails("Check backup snapshots")).performClick()
        onNodeWithText("Tool details").assertExists()
        onNodeWithText("Error").assertExists()
        onNodeWithText("backupctl snapshots --latest").assertExists()
        onNodeWithText("Snapshot listing timed out").assertExists()
        onNode(expandsCall("Read the backup log")).performClick()
        onNodeWithText("All archives found").assertExists()
        onNodeWithText("Snapshot listing timed out").assertExists()
        onNodeWithText("View full output").assertDoesNotExist()

        onNodeWithContentDescription("Back").performClick()
        onNodeWithText("Tool details").assertDoesNotExist()
        onNode(opensDetails("Check backup snapshots")).assertExists()
    }

    @Test
    fun onlyTheLatestTenCallsAreInlineAndEarlierCallsAreInDetails() = runComposeUiTest {
        val calls = (1..12).map { index ->
            ToolActivity("call-$index", "terminal", detail = "Call $index", startedAt = index.toDouble(), output = "Result $index")
        }
        setContent {
            HermesTheme(ColorScheme.Dark) { Tools(calls, "many-tools") }
        }

        onNodeWithText("Ran 12 tools").performClick()
        onAllNodes(opensToolDetails).assertCountEquals(10)
        onNodeWithText("Call 1").assertDoesNotExist()
        onNodeWithText("Call 2").assertDoesNotExist()
        onNodeWithText("+0.0s").assertDoesNotExist()
        onNodeWithText("+2.0s").assertExists()
        onNode(opensDetails("Call 12")).assertExists()
        onNode(opensDetails("Call 3")).performClick()

        onNodeWithText("Tool details").assertExists()
        onNodeWithText("Result 3").assertExists()
        onNode(expandsCall("Call 1")).performClick()
        onNodeWithText("Result 1").assertExists()
        onNode(expandsCall("Call 12")).assertExists()
    }

    @Test
    fun newRunningCallsDisplaceOlderLiveRowsAndKeepFullHistoryInDetails() = runComposeUiTest {
        val completed = (1..4).map { index ->
            ToolActivity("old-$index", "terminal", detail = "Finished call $index", completed = true)
        }
        val running = (1..3).map { index ->
            ToolActivity("new-$index", "terminal", detail = "Running call $index", running = true)
        }
        val calls = mutableStateOf(completed)
        setContent { HermesTheme(ColorScheme.Dark) { LiveSteps(calls.value, "live-cap") } }
        for (index in 1..4) onNodeWithText("Ran Finished call $index").assertExists()

        runOnIdle { calls.value = completed + running }

        onNodeWithText("3 earlier steps").assertExists()
        for (index in 1..3) onNodeWithText("Ran Finished call $index").assertDoesNotExist()
        onNodeWithText("Ran Finished call 4").assertExists()
        for (index in 1..3) onNodeWithText("Running call $index").assertExists()
        onNodeWithText("Running call 3").performClick()
        onNodeWithText("Running call 3").performClick()
        onNodeWithText("Tool details").assertExists()
        onNode(expandsCall("Finished call 1")).assertExists()
    }

    @Test
    fun liveStepCanCollapseWithChevronOrOpenDetailsFromTheRow() = runComposeUiTest {
        val tool = tools.last().copy(summary = "Snapshot check timed out.", completed = true)
        val running = ToolActivity("live", "terminal", running = true, detail = "Check the backup server", startedAt = 51.0)
        fun action(label: String) = SemanticsMatcher("tool row action $label") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == label
        }
        setContent { HermesTheme(ColorScheme.Dark) { LiveSteps(listOf(tool, running), "live-reply") } }
        onNodeWithText("Check the backup server").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Still running…"))
        onNodeWithText("+0.8s").assertExists()
        onNodeWithText("2.0s").assertExists()
        onNodeWithText("Snapshot check timed out.").assertDoesNotExist()
        val collapsed = onNode(action("Show details") and hasText("Ran Check backup snapshots"))
        val expanded = onNode(action("Tool details") and hasText("Ran Check backup snapshots"))
        val chevron = onNodeWithContentDescription("Hide details")
        val duration = onNodeWithText("2.0s", useUnmergedTree = true)
        val durationRight = duration.getUnclippedBoundsInRoot().right
        chevron.assertDoesNotExist()
        collapsed.performTouchInput { click() }
        onNodeWithText("Snapshot check timed out.").assertExists()
        onNodeWithText("Snapshot listing timed out").assertDoesNotExist()
        val expandedDuration = duration.getUnclippedBoundsInRoot()
        assertEquals(durationRight, expandedDuration.right, "The duration column should stay fixed")
        assertTrue(chevron.getUnclippedBoundsInRoot().right <= expandedDuration.left)

        chevron.performTouchInput { click() }
        chevron.assertDoesNotExist()
        onNodeWithText("Snapshot check timed out.").assertDoesNotExist()
        onNodeWithText("Tool details").assertDoesNotExist()

        collapsed.performTouchInput { click() }
        expanded.performTouchInput { click() }
        onNodeWithText("Tool details").assertExists()
        onNodeWithText("Snapshot listing timed out").assertExists()
    }

    @Test
    fun narrowDesktopKeepsTheReplyVisibleBesideExpandedTools() = runDesktopComposeUiTest(width = 800, height = 750) {
        val back = DesktopBack()
        val state = ChatState(historyLoaded = true, messages = listOf(
            ChatMessage.Assistant("backup-reply", text = "The backup can be retried.", tools = tools),
        ))
        setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(LocalDesktopLayout provides true, LocalDesktopBack provides back) {
                    ChatView(
                        title = "Backups", state = state, picker = ModelPickerState(), connected = true,
                        attachments = emptyList(), attachmentError = null, voiceChat = VoiceChatState(),
                        dictation = DictationState(), suggestions = emptyList(), sprite = null,
                        placeholder = "Message", notice = null, actions = PreviewChatActions(),
                        onOpenSidebar = {}, onNewChat = null, onOpenMenu = null, onOpenModels = {},
                        onAttach = {}, onDictate = {}, onVoiceChat = {}, onOpenPets = {}, onViewImage = {}, onNotice = {},
                    )
                }
            }
        }

        onNodeWithText("Ran 2 tools").performClick()
        onNode(opensDetails("Check backup snapshots")).performClick()
        val reply = onNodeWithText("The backup can be retried.").assertIsDisplayed().getUnclippedBoundsInRoot()
        val details = onNodeWithText("Tool details").assertIsDisplayed().getUnclippedBoundsInRoot()
        onAllNodesWithText("+0.2s").assertCountEquals(2)
        onAllNodesWithText("2.0s").assertCountEquals(2)
        assertTrue(details.left > reply.right, "Tool details should be to the right of the reply")
        onNodeWithText("backupctl snapshots --latest").assertIsDisplayed()
        onNode(expandsCall("Read the backup log")).performClick()
        onNodeWithText("All archives found").assertIsDisplayed()
        onNodeWithText("Snapshot listing timed out").assertIsDisplayed()
        val divider = onNodeWithContentDescription("Details panel width")
        divider.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(420f)) }
        assertTrue(onNodeWithText("Tool details").getUnclippedBoundsInRoot().left < details.left)
        divider.performMouseInput { doubleClick(center) }
        assertEquals(details.left, onNodeWithText("Tool details").getUnclippedBoundsInRoot().left)
        divider.performMouseInput {
            moveTo(center)
            press()
            moveTo(center - Offset(36f, 0f))
            moveTo(center - Offset(68f, 0f))
            release()
        }
        val draggedLeft = onNodeWithText("Tool details").getUnclippedBoundsInRoot().left
        assertTrue(draggedLeft < details.left)
        divider.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        divider.performKeyInput { pressKey(Key.DirectionRight) }
        assertTrue(onNodeWithText("Tool details").getUnclippedBoundsInRoot().left > draggedLeft)
        runOnIdle { assertTrue(back.back()) }
        onNodeWithText("Tool details").assertDoesNotExist()
        onNode(opensDetails("Check backup snapshots")).assertIsDisplayed()
    }
}
