package dev.hermeskotlin.ui.chat

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.R
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import java.io.ByteArrayInputStream
import kotlin.io.encoding.Base64
import kotlin.math.hypot

/** Untrusted widget HTML gets no gateway origin, cookies, storage, native bridge or network. */
@Composable
internal actual fun WidgetWebView(html: String, theme: WidgetTheme, name: String, modifier: Modifier, onReport: (WidgetReport) -> Unit, onFailure: (String) -> Unit) {
    val context = LocalContext.current
    var provider by remember { mutableStateOf(WebView.getCurrentWebViewPackage()) }
    if (!supportsWidgetIsolation(provider?.versionName)) {
        WidgetWebViewRecovery(
            openUpdate = { openWebViewUpdate(context, provider?.packageName) },
            onRetry = { provider = WebView.getCurrentWebViewPackage() },
        )
        return
    }
    val failure = rememberUpdatedState(onFailure)
    val report = rememberUpdatedState(onReport)
    val failed = rememberUpdatedState(stringResource(Res.string.android_widget_failed))
    val font = remember { widgetFont(context) }
    val document = remember(html, theme, font) { widgetDocument(html, theme, font) }
    val currentDocument = rememberUpdatedState(document)
    val view = remember { createWidgetWebView(context, name, { currentDocument.value }, { report.value(it) }) { failure.value(failed.value) } }
    DisposableEffect(view) {
        onDispose { view.stopLoading(); view.destroy() }
    }
    AndroidView(factory = { view }, modifier = modifier, update = {
        // By identity: the document is remembered, and comparing its text would read it all on each update.
        if (it.tag !== document) {
            it.tag = document
            it.loadUrl(WIDGET_URL)
        }
    })
}

@Composable
private fun WidgetWebViewRecovery(openUpdate: () -> Boolean, onRetry: () -> Unit) {
    var updateFailed by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(Res.string.android_widget_update_webview),
            style = Theme[typography][bodySmall],
            color = Theme[colors][text],
        )
        if (updateFailed) {
            Text(
                stringResource(Res.string.android_widget_update_unavailable),
                style = Theme[typography][bodySmall],
                color = Theme[colors][textSecondary],
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stack = maxWidth < 240.dp * LocalDensity.current.fontScale
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val button = Modifier.heightIn(min = 48.dp)
                Button(
                    stringResource(Res.string.android_widget_update_action),
                    onClick = { updateFailed = !openUpdate() },
                    modifier = if (stack) button.fillMaxWidth() else button.weight(1.4f),
                )
                Button(
                    stringResource(Res.string.chat_retry),
                    onClick = {
                        updateFailed = false
                        onRetry()
                    },
                    modifier = if (stack) button.fillMaxWidth() else button.weight(1f),
                    variant = ButtonVariant.Outline,
                )
            }
        }
    }
}

private fun openWebViewUpdate(context: Context, providerPackage: String?): Boolean {
    fun launch(intent: Intent): Boolean {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    // The Android Open Source Project provider is supplied by the system image.
    if (providerPackage == "com.android.webview") {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
            Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1 &&
            launch(Intent(Settings.ACTION_SYSTEM_UPDATE_SETTINGS))
        ) return true
        return launch(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
    }
    val packageId = Uri.encode(providerPackage ?: "com.google.android.webview")
    return launch(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageId"))) ||
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageId")))
}

private const val WIDGET_URL = "https://herald-widget.invalid/"

/** Geist as base64, which every widget's document embeds; read once, on the main thread like the widgets. */
private var cachedFont: String? = null

private fun widgetFont(context: Context): String = cachedFont
    ?: context.resources.openRawResource(R.font.geist_regular).use { Base64.encode(it.readBytes()) }.also { cachedFont = it }

/** The same restricted host for each widget; its document always arrives as app-supplied text. */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
internal fun createWidgetWebView(context: Context, name: String, document: () -> String, onReport: (WidgetReport) -> Unit, onFailure: () -> Unit): WebView =
    WebView(context).apply {
        // A wrap-content WebView gives percentage-height frames a zero-height document.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.TRANSPARENT)
        contentDescription = name
        // Each drag goes to the widget or to the transcript, decided once from the widget's last report (as the title)
        // after a little movement, before the transcript's own touch slop: widgetKeepsDrag. A second finger (a pinch)
        // always stays in the widget. WebView owns clicks.
        var report = WidgetReport()
        var downX = 0f
        var downY = 0f
        var decided = false
        val decideAfter = ViewConfiguration.get(context).scaledTouchSlop / 2f
        val density = resources.displayMetrics.density
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    // A fast flick can pass the transcript's touch slop on its first move, before it's decided here.
                    decided = widgetKeepsTouch(report, downX / density, downY / density)
                    if (decided) view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    decided = true
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> if (!decided) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (hypot(dx, dy) >= decideAfter) {
                        decided = true
                        view.parent?.requestDisallowInterceptTouchEvent(widgetKeepsDrag(report, downX / density, downY / density, dx, dy))
                    }
                }
            }
            false
        }
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            // LocalDensity already includes Herald's and the system's text scales.
            textZoom = 100
            blockNetworkLoads = true
        }
        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }

            override fun onReceivedTitle(view: WebView, title: String?) {
                widgetReport(title)?.let { report = it; onReport(it) }
            }
        }
        webViewClient = object : WebViewClient() {
            // A reloaded document (a theme change) reports for itself; the last one's areas are gone.
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                report = WidgetReport()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                if (request.isForMainFrame && request.url.toString() == WIDGET_URL) {
                    // CSP and blockNetworkLoads don't cover WebRTC. This policy is inherited by srcdoc.
                    WebResourceResponse("text/html", "UTF-8", 200, "OK", mapOf("Connection-Allowlist" to "()"),
                        ByteArrayInputStream(document().encodeToByteArray()))
                } else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) view.post { onFailure() }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                view.post { onFailure() }
                return true
            }
        }
    }
