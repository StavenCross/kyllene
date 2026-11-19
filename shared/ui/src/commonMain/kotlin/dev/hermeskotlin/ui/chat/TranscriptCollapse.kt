package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.ui.isDesktop
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.MinTouchTarget

internal val LocalTranscriptCollapse = staticCompositionLocalOf<TranscriptCollapseState?> { null }

internal class TranscriptCollapseTarget(val label: String, val openedAt: Int, val close: () -> Unit) {
    var bounds by mutableStateOf(Rect.Zero)
}

internal class TranscriptCollapseState : NestedScrollConnection {
    val targets = mutableStateListOf<TranscriptCollapseTarget>()
    var viewport by mutableStateOf(Rect.Zero)
    var navigation by mutableIntStateOf(0)
    val active by derivedStateOf {
        targets.firstOrNull {
            viewport.height > 0 && navigation != it.openedAt &&
                viewport.top - it.bounds.top >= viewport.height && it.bounds.bottom > viewport.top + 1
        }
    }

    fun navigated() { navigation++ }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // Layout changes from streaming or resizing do not count as reading a page.
        if (consumed.y != 0f) navigated()
        return Offset.Zero
    }
}

/** One close action above the composer, scoped to the expanded section currently being read. */
@Composable
internal fun TranscriptCollapseHost(
    modifier: Modifier = Modifier, bottomInset: Dp = 0.dp,
    state: TranscriptCollapseState = remember { TranscriptCollapseState() }, content: @Composable () -> Unit,
) {
    val inset = with(LocalDensity.current) { bottomInset.toPx() }
    CompositionLocalProvider(LocalTranscriptCollapse provides state) {
        Box(modifier.fillMaxSize().nestedScroll(state).onGloballyPositioned {
            val top = it.positionInWindow()
            state.viewport = Rect(top.x, top.y, top.x + it.size.width, (top.y + it.size.height - inset).coerceAtLeast(top.y))
        }) {
            content()
            state.active?.let { target ->
                Button(
                    target.label,
                    onClick = target.close,
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = bottomInset + 8.dp)
                        .heightIn(min = MinTouchTarget)
                        .transcriptControlRim(CircleShape),
                )
            }
        }
    }
}

/** Measuring the whole disclosure keeps closing anchored to its header after its body disappears. */
@Composable
internal fun TranscriptCollapseSection(expanded: Boolean, label: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val host = LocalTranscriptCollapse.current
    val anchor = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val close by rememberUpdatedState(onClose)
    val target = remember(host, expanded, label) {
        TranscriptCollapseTarget(label, host?.navigation ?: 0) {
            scope.launch {
                // Reach the header before shrinking the row, so lazy layout cannot dispose its anchor.
                anchor.bringIntoView(Rect(0f, 0f, 1f, 1f))
                close()
            }
        }
    }
    if (host != null && expanded) DisposableEffect(host, target) {
        host.targets.add(target)
        onDispose { host.targets.remove(target) }
    }
    Box(Modifier.bringIntoViewRequester(anchor).onGloballyPositioned {
        val top = it.positionInWindow()
        target.bounds = Rect(top.x, top.y, top.x + it.size.width, top.y + it.size.height)
    }) { content() }
}

/** A small opaque rim clears transcript text around an Android control without changing its layout. */
@Composable
internal fun Modifier.transcriptControlRim(shape: Shape): Modifier {
    if (isDesktop) return this
    val backdrop = Theme[colors][background].copy(alpha = 1f)
    val edge = Theme[colors][textSecondary]
    return drawWithCache {
        val rim = 3.dp.toPx()
        val border = 1.dp.toPx()
        val outer = shape.createOutline(Size(size.width + rim * 2, size.height + rim * 2), layoutDirection, this)
        val inner = shape.createOutline(Size(size.width - border, size.height - border), layoutDirection, this)
        onDrawWithContent {
            translate(-rim, -rim) { drawOutline(outer, backdrop) }
            drawContent()
            translate(border / 2, border / 2) { drawOutline(inner, edge, style = Stroke(border)) }
        }
    }
}
