package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ReplyReaction
import dev.hermeskotlin.designsystem.HermesTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ReplyReactionMenuTest {
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
}
