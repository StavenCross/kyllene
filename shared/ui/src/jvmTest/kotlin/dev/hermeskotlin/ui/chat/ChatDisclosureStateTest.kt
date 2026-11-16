package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ToolActivity
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.components.SidebarLayout
import dev.hermeskotlin.designsystem.components.rememberSidebarState
import kotlin.test.Test

/** The real reply's disclosures survive both a docking change and Android-style saved-state restoration. */
@OptIn(ExperimentalTestApi::class)
class ChatDisclosureStateTest {
    private val reply = ChatMessage.Assistant(
        "reply", reasoning = "Reasoning content",
        tools = listOf(ToolActivity("call", "fixture_tool", input = "Tool input")),
    )

    @Test fun dockingAndUndockingKeepDisclosuresOpen() = runComposeUiTest {
        var wide by mutableStateOf(false)
        setContent {
            HermesTheme(ColorScheme.Dark) {
                Box(Modifier.size(if (wide) 840.dp else 360.dp, 640.dp)) {
                    SidebarLayout(rememberSidebarState(), sidebar = {}) { AssistantReply(reply, null, null, last = true) }
                }
            }
        }
        expand()
        runOnIdle { wide = true }
        assertExpanded()
        runOnIdle { wide = false }
        assertExpanded()
    }

    @Test fun recreationRestoresDisclosures() = restoration(false)
    @Test fun recreationFromDrawerIntoDockedLayoutRestoresDisclosures() = restoration(true)

    private fun restoration(dockOnRestore: Boolean) = runComposeUiTest {
        var wide by mutableStateOf(false)
        var present by mutableStateOf(true)
        var registry = SaveableStateRegistry(null) { true }
        setContent {
            if (present) CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                HermesTheme(ColorScheme.Light) {
                    Box(Modifier.size(if (wide) 840.dp else 360.dp, 640.dp)) {
                        SidebarLayout(rememberSidebarState(), sidebar = {}) { AssistantReply(reply, null, null, last = true) }
                    }
                }
            }
        }
        expand()
        val saved = runOnIdle { registry.performSave().also { present = false } }
        waitForIdle()
        runOnIdle {
            registry = SaveableStateRegistry(saved) { true }
            wide = dockOnRestore
            present = true
        }
        assertExpanded()
    }

    @Test fun anotherReplyDoesNotInheritOpenDisclosures() = runComposeUiTest {
        var message by mutableStateOf(reply)
        setContent { HermesTheme(ColorScheme.Dark) { AssistantReply(message, null, null, last = true) } }
        expand()
        runOnIdle { message = reply.copy(key = "another-reply") }
        onNodeWithText("Reasoning content").assertDoesNotExist()
        onNodeWithText("fixture_tool").assertDoesNotExist()
    }

    @Test fun dockedContentStartsAfterTheSidebarAndDivider() = runComposeUiTest {
        setContent {
            HermesTheme(ColorScheme.Light) {
                Box(Modifier.size(840.dp, 640.dp)) {
                    SidebarLayout(rememberSidebarState(), sidebar = {}) {
                        Box(Modifier.fillMaxSize().testTag("content"))
                    }
                }
            }
        }
        waitForIdle()
        onNodeWithTag("content").assertLeftPositionInRootIsEqualTo(305.dp).assertWidthIsEqualTo(535.dp)
    }

    private fun ComposeUiTest.expand() {
        onNodeWithText("Reasoning").performClick()
        onNodeWithContentDescription("Ran 1 tool").performClick()
        onNodeWithText("fixture_tool").performClick()
        assertExpanded()
    }

    private fun ComposeUiTest.assertExpanded() {
        waitForIdle()
        onNodeWithText("Reasoning content").assertIsDisplayed()
        onNodeWithText("Tool input").assertIsDisplayed()
    }
}
