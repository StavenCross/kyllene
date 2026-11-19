package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.GitBranch
import com.composables.icons.lucide.Lucide
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.ui.sessions.RowMenuItem
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ReplyReaction
import dev.hermeskotlin.core.chat.TurnUsage
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.ui.LocalAppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ReplyReactionMenuTest {
    @Test
    fun desktopReplyKeepsCopyMoreAndUsageOnOneRowAndRoutesTheMenuActions() = runComposeUiTest {
        val reaction = mutableStateOf<ReplyReaction?>(null)
        val reading = mutableStateOf(false)
        var branched = false
        var regenerated = false
        var spoken: String? = null
        setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(
                    LocalDesktopLayout provides true,
                    LocalAppSettings provides AppSettings(showUsage = true),
                ) {
                    Box(Modifier.size(420.dp, 480.dp).padding(20.dp)) {
                        AssistantReply(
                            message = ChatMessage.Assistant(
                                key = "reply", text = "Ready to review.", reactionRowId = 1,
                                userReaction = reaction.value?.emoji, usage = TurnUsage(640, 321, 0, 1),
                            ),
                            onBranch = { branched = true },
                            onRegenerate = { regenerated = true },
                            last = true,
                            reading = reading.value,
                            onReadAloud = { spoken = it; reading.value = true },
                            onStopReading = { reading.value = false },
                            onReact = { reaction.value = it },
                        )
                    }
                }
            }
        }

        val copy = onNodeWithText("Copy").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val more = onNodeWithText("More").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val usage = onNodeWithText("640 ↓ · 321 ↑").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(copy.right <= more.left && more.right <= usage.left)
        assertTrue(kotlin.math.abs(copy.center.y - more.center.y) < 1f)
        onNodeWithContentDescription("Message actions").assertDoesNotExist()
        onNodeWithContentDescription("Regenerate").assertDoesNotExist()
        onNodeWithContentDescription("Branch from here").assertDoesNotExist()

        onNodeWithText("More").performClick()
        val labels = listOf("Got it", "Continue", "Verify", "Explain", "Branch from here", "Regenerate", "Read aloud")
        val starts = labels.map { onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.left }
        assertTrue(starts.max() - starts.min() < 1f)
        onNodeWithText("Verify").performClick()
        runOnIdle { assertEquals(ReplyReaction.Verify, reaction.value) }
        onNodeWithText("🔍").assertIsDisplayed()

        onNodeWithText("More").performClick()
        onNodeWithText("Branch from here").performClick()
        onNodeWithText("More").performClick()
        onNodeWithText("Regenerate").performClick()
        onNodeWithText("More").performClick()
        onNodeWithText("Read aloud").performClick()
        runOnIdle {
            assertTrue(branched && regenerated)
            assertEquals("Ready to review.", spoken)
        }
        onNodeWithText("More").performClick()
        onNodeWithText("Stop reading").performClick()
        runOnIdle { assertEquals(false, reading.value) }
    }

    @Test
    fun desktopMenuOffersFourActionsAndAttachesTheSelectedEmoji() = runComposeUiTest {
        val selected = mutableStateOf<ReplyReaction?>(null)
        setContent {
            HermesTheme(ColorScheme.Dark) {
                Box(Modifier.size(360.dp, 640.dp)) {
                    ReplyReactionMenu(selected.value?.emoji, onReact = { selected.value = it })
                }
            }
        }
        onNodeWithContentDescription("Message actions").performClick()
        onNodeWithText("👍  Got it").assertIsDisplayed()
        onNodeWithText("▶️  Continue").assertIsDisplayed()
        onNodeWithText("🔍  Verify").assertIsDisplayed()
        onNodeWithText("❓  Explain").assertIsDisplayed()
        onNodeWithText("👍  Got it").performClick()
        assertEquals(ReplyReaction.Acknowledge, selected.value)
        onNodeWithText("👍").assertIsDisplayed()
    }

    @Test
    fun labeledMenuOffersReplyControlsEvenWithoutReactionSupport() = runComposeUiTest {
        var branched = false
        setContent {
            HermesTheme(ColorScheme.Dark) {
                Box(Modifier.size(360.dp, 640.dp)) {
                    ReplyReactionMenu(
                        reaction = "👍",
                        onReact = null,
                        actions = listOf(RowMenuItem("Branch here", Lucide.GitBranch) { branched = true }),
                        showLabel = true,
                    )
                }
            }
        }
        onNodeWithText("👍").assertIsDisplayed()
        onNodeWithText("More").performClick()
        onNodeWithText("👍  Got it").assertDoesNotExist()
        onNodeWithText("Branch here").performClick()
        assertEquals(true, branched)
        onNodeWithText("Branch here").assertDoesNotExist()
    }
}
