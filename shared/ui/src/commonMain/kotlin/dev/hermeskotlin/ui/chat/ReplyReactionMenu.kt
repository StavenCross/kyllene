package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Lucide
import com.composeunstyled.Text
import dev.hermeskotlin.core.chat.ReplyReaction
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.ContextMenu
import dev.hermeskotlin.designsystem.components.MinTouchTarget
import dev.hermeskotlin.ui.sessions.RowMenuItem
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.MenuRow
import org.jetbrains.compose.resources.stringResource
import dev.hermeskotlin.ui.resources.*

@Composable
internal fun ReplyReactionMenu(
    reaction: String?,
    onReact: ((ReplyReaction) -> Unit)?,
    modifier: Modifier = Modifier,
    actions: List<RowMenuItem> = emptyList(),
    showLabel: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (reaction != null) Text(reaction, modifier = Modifier.padding(horizontal = 6.dp))
        if (onReact != null || actions.isNotEmpty()) {
            Box(Modifier.onGloballyPositioned { anchor = it.boundsInWindow() }) {
                if (showLabel) {
                    Button(
                        text = stringResource(Res.string.sessions_drawer_more),
                        onClick = { open = true },
                        modifier = Modifier.heightIn(min = MinTouchTarget),
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.Small,
                        leadingIcon = Lucide.Ellipsis,
                    )
                } else {
                    IconButton(
                        Lucide.Ellipsis,
                        contentDescription = stringResource(Res.string.chat_message_actions),
                        onClick = { open = true },
                        iconSize = 16.dp,
                    )
                }
            }
            ContextMenu(open, onDismiss = { open = false }, anchor = anchor) {
                if (onReact != null) {
                    for (item in ReplyReaction.entries) {
                        val label = stringResource(when (item) {
                            ReplyReaction.Acknowledge -> Res.string.chat_react_acknowledge
                            ReplyReaction.Continue -> Res.string.chat_react_continue
                            ReplyReaction.Verify -> Res.string.chat_react_verify
                            ReplyReaction.Explain -> Res.string.chat_react_explain
                        })
                        MenuRow(
                            text = if (showLabel) label else "${item.emoji}  $label",
                            icon = null,
                            leadingEmoji = item.emoji.takeIf { showLabel },
                            onClick = {
                                open = false
                                onReact(item)
                            },
                        )
                    }
                }
                for (action in actions) {
                    MenuRow(action.label, action.icon, onClick = {
                        open = false
                        action.run()
                    })
                }
            }
        }
    }
}
