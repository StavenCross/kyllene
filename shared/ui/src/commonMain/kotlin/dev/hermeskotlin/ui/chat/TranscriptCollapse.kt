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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.ceil
import androidx.compose.ui.draw.clip
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.ui.isDesktop
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.MinTouchTarget

internal val TranscriptControlRim = 12.dp

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
    state: TranscriptCollapseState = remember { TranscriptCollapseState() },
    controlHaze: HazeState? = null,
    content: @Composable () -> Unit,
) {
    val inset = with(LocalDensity.current) { bottomInset.toPx() }
    CompositionLocalProvider(LocalTranscriptCollapse provides state) {
        Box(modifier.fillMaxSize().nestedScroll(state).onGloballyPositioned {
            val top = it.positionInWindow()
            state.viewport = Rect(top.x, top.y, top.x + it.size.width, (top.y + it.size.height - inset).coerceAtLeast(top.y))
        }) {
            content()
            state.active?.let { target ->
                val rim = if (controlHaze == null) 0.dp else TranscriptControlRim
                TranscriptControlBackdrop(
                    CircleShape, controlHaze,
                    Modifier.align(Alignment.BottomCenter).padding(start = 16.dp - rim, end = 16.dp - rim, bottom = (bottomInset + 8.dp - rim).coerceAtLeast(0.dp)),
                ) {
                    Button(
                        target.label,
                        onClick = target.close,
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        modifier = Modifier.heightIn(min = MinTouchTarget).transcriptControlOutline(CircleShape),
                    )
                }
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

/** Blur a narrow area of the transcript around the control, following its own shape. */
@Composable
internal fun TranscriptControlBackdrop(
    shape: Shape, haze: HazeState?, modifier: Modifier = Modifier, content: @Composable () -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (haze != null) {
            val page = Theme[colors][background]
            val density = LocalDensity.current
            val direction = LocalLayoutDirection.current
            val style = remember(page, shape, density, direction) {
                val feather = featheredRimBrush(shape, density, direction)
                HazeBlurStyle {
                    blurEnabled(true)
                    blurRadius(12.dp)
                    backgroundColor(page)
                    progressive(HazeProgressive.Brush(feather))
                    mask(feather)
                }
            }
            Box(Modifier.matchParentSize().clip(shape).hazeBlur(input = HazeInput.Sources(haze), style = style))
            Box(Modifier.padding(TranscriptControlRim)) { content() }
        } else {
            content()
        }
    }
}

/** The blur fades smoothly from full at the button edge to transparent at the rim's outside. */
private fun featheredRimBrush(shape: Shape, density: Density, direction: LayoutDirection) = object : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val bitmap = ImageBitmap(ceil(size.width).toInt().coerceAtLeast(1), ceil(size.height).toInt().coerceAtLeast(1))
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val feather = with(density) { TranscriptControlRim.toPx() }.coerceAtMost(size.minDimension / 2)
        val innerSize = Size(size.width - 2 * feather, size.height - 2 * feather)
        val outline = shape.createOutline(innerSize, direction, density)
        val button = (outline as? Outline.Rounded)?.roundRect ?: RoundRect(Rect(Offset.Zero, innerSize), CornerRadius.Zero)
        val steps = ceil(feather).toInt().coerceAtLeast(1)
        var previousAlpha = 0f
        for (step in 1..steps) {
            val t = step.toFloat() / steps
            val inset = (step - 0.5f) * feather / steps
            val alpha = t * t * (3 - 2 * t)
            // Each inset fill is composited over the previous fill, which already has some alpha.
            paint.color = Color.White.copy(alpha = ((alpha - previousAlpha) / (1 - previousAlpha)).coerceIn(0f, 1f))
            // Expand each corner from the button's edge, so the feather follows the button's outline.
            val spread = feather - inset
            fun CornerRadius.outset() = CornerRadius(x + spread, y + spread)
            canvas.drawOutline(
                Outline.Rounded(RoundRect(
                    inset, inset, size.width - inset, size.height - inset,
                    button.topLeftCornerRadius.outset(), button.topRightCornerRadius.outset(),
                    button.bottomRightCornerRadius.outset(), button.bottomLeftCornerRadius.outset(),
                )),
                paint,
            )
            previousAlpha = alpha
        }
        return ImageShader(bitmap)
    }
}

@Composable
internal fun Modifier.transcriptControlOutline(shape: Shape): Modifier {
    if (isDesktop) return this
    val edge = Theme[colors][textSecondary]
    return drawWithCache {
        val border = 1.dp.toPx()
        val inner = shape.createOutline(Size(size.width - border, size.height - border), layoutDirection, this)
        onDrawWithContent {
            drawContent()
            translate(border / 2, border / 2) { drawOutline(inner, edge, style = Stroke(border)) }
        }
    }
}
