package com.remoteviber.client.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.remoteviber.client.ui.theme.ViberBg

class XtermController {
    var webViewRef: WebView? = null
    var isReady = false
    private val pendingQueue = mutableListOf<String>()

    fun writeBase64(b64: String) {
        if (isReady && webViewRef != null) {
            webViewRef?.post {
                webViewRef?.evaluateJavascript("window.writeB64Data('$b64');", null)
            }
        } else {
            pendingQueue.add(b64)
        }
    }

    fun clear() {
        pendingQueue.clear()
        webViewRef?.post {
            webViewRef?.evaluateJavascript("window.clearTerminal();", null)
        }
    }

    fun setFontSize(sizeSp: Int) {
        webViewRef?.post {
            webViewRef?.evaluateJavascript("window.setFontSize($sizeSp);", null)
        }
    }

    fun refit() {
        webViewRef?.post {
            webViewRef?.evaluateJavascript("window.refit();", null)
        }
    }

    internal fun onTerminalReady() {
        isReady = true
        if (pendingQueue.isNotEmpty() && webViewRef != null) {
            val copy = ArrayList(pendingQueue)
            pendingQueue.clear()
            webViewRef?.post {
                for (chunk in copy) {
                    webViewRef?.evaluateJavascript("window.writeB64Data('$chunk');", null)
                }
            }
        }
    }
}

class AndroidXtermBridge(
    private val controller: XtermController,
    private val onSendInputBase64: (String) -> Unit,
    private val onResize: (Int, Int) -> Unit
) {
    @JavascriptInterface
    fun onInput(b64: String) {
        onSendInputBase64(b64)
    }

    @JavascriptInterface
    fun onResize(rows: Int, cols: Int) {
        onResize(rows, cols)
    }

    @JavascriptInterface
    fun onReady() {
        controller.onTerminalReady()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Xterm2DView(
    controller: XtermController,
    fontSizeSp: Int,
    modifier: Modifier = Modifier,
    onSendInputBase64: (String) -> Unit,
    onResize: (Int, Int) -> Unit
) {
    LaunchedEffect(fontSizeSp) {
        controller.setFontSize(fontSizeSp)
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(0xFF090D16.toInt())
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    useWideViewPort = false
                    loadWithOverviewMode = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                }

                val bridge = AndroidXtermBridge(
                    controller = controller,
                    onSendInputBase64 = onSendInputBase64,
                    onResize = onResize
                )
                addJavascriptInterface(bridge, "AndroidBridge")

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        controller.onTerminalReady()
                    }
                }

                controller.webViewRef = this
                loadUrl("file:///android_asset/xterm_view.html")
            }
        },
        update = { webView ->
            controller.webViewRef = webView
        },
        modifier = modifier
            .fillMaxSize()
            .background(ViberBg)
    )
}
