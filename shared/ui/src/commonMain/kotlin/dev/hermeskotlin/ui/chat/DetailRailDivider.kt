package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.ResizeHorizontalPointer
import dev.hermeskotlin.ui.resources.Res
import dev.hermeskotlin.ui.resources.chat_detail_rail_width
import org.jetbrains.compose.resources.stringResource

/** Keep chat at least 360dp wide when space allows and share the room in narrower windows. */
internal fun detailRailWidths(pane: Dp): ClosedRange<Dp> {
    val least = minOf(240.dp, pane * 0.45f)
    val most = minOf(pane - 360.dp, pane * 0.65f).coerceAtLeast(least)
    return least..most
}

@Composable
internal fun DetailRailDivider(
    width: Dp,
    limits: ClosedRange<Dp>,
    onDragWidth: (Dp?) -> Unit,
    onSaveWidth: (Dp?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) -1f else 1f
    val label = stringResource(Res.string.chat_detail_rail_width)
    val saveWidth by rememberUpdatedState(onSaveWidth)
    var dragWidth by remember { mutableStateOf<Dp?>(null) }
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.width(12.dp).fillMaxHeight().pointerHoverIcon(ResizeHorizontalPointer)
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { saveWidth(null) }) }
            .draggable(
                state = rememberDraggableState { delta ->
                    val next = ((dragWidth ?: width) + with(density) { (delta * direction).toDp() }).coerceIn(limits)
                    dragWidth = next
                    onDragWidth(next)
                },
                orientation = Orientation.Horizontal,
                onDragStarted = { dragWidth = width },
                onDragStopped = {
                    dragWidth?.let(onSaveWidth)
                    dragWidth = null
                    onDragWidth(null)
                },
            )
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (it.key) {
                    Key.DirectionLeft -> onSaveWidth((width - 24.dp * direction).coerceIn(limits))
                    Key.DirectionRight -> onSaveWidth((width + 24.dp * direction).coerceIn(limits))
                    Key.MoveHome -> onSaveWidth(null)
                    else -> return@onKeyEvent false
                }
                true
            }
            .focusable()
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(width.value, limits.start.value..limits.endInclusive.value)
                setProgress { value ->
                    if (!value.isFinite()) false else {
                        onSaveWidth(value.dp.coerceIn(limits))
                        true
                    }
                }
            },
    ) {
        if (focused) Box(Modifier.align(Alignment.CenterStart).width(2.dp).fillMaxHeight().background(Theme[colors][accentText]))
    }
}
