// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.k60linbox.manager.term.TerminalBridge
import com.k60linbox.manager.term.TerminalSession

private const val TERMINAL_URL = "https://appassets.androidplatform.net/assets/xterm/index.html"
private const val TAG = "k60linbox-term"

/**
 * 内嵌终端。
 *
 * xterm.js 渲染在 WebView 里（静态资源走 WebViewAssetLoader），PTY 由设备端的
 * `linbox attach`（busybox script）提供。行列数在页面加载后由 JS 量一次并回传，
 * 之后不再变化——busybox script 不支持运行中 resize，所以「重开会话」是唯一的改尺寸办法。
 */
@Composable
fun TerminalTab(containerName: String, running: Boolean) {
    var reloadKey by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }

    if (!running) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("容器未运行，终端不可用。")
            Text("先到「概览」页启动容器，再回到这里。", style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            TextButton(onClick = { reloadKey++ }) { Text("重开会话（按当前尺寸）") }
            if (status.isNotEmpty()) {
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }
        // key 变化会重建整棵子树，从而重开 WebView 与会话
        key(reloadKey) {
            TerminalView(
                containerName = containerName,
                onStatus = { status = it },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TerminalView(
    containerName: String,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val session = remember(containerName) { TerminalSession(containerName) }

    DisposableEffect(session) {
        onDispose { session.close() }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val webView = WebView(context).apply {
                setBackgroundColor(Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
            }

            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .build()

            webView.webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ) = assetLoader.shouldInterceptRequest(request.url)
            }

            val bridge = TerminalBridge(
                onInput = { bytes -> session.write(bytes) },
                onReady = { cols, rows ->
                    session.onOutput = { bytes ->
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        mainHandler.post {
                            webView.evaluateJavascript(
                                "window.linboxFeed && window.linboxFeed('$base64')",
                                null,
                            )
                        }
                    }
                    session.onExit = { code ->
                        mainHandler.post {
                            webView.evaluateJavascript(
                                "window.linboxExit && window.linboxExit($code)",
                                null,
                            )
                        }
                        onStatus("会话已结束（退出码 $code）")
                    }
                    session.onLog = { message -> onStatus(message) }
                    session.start(cols, rows)
                    onStatus("已连接（$cols x $rows）")
                },
                onLog = { message -> Log.i(TAG, message) },
            )

            webView.addJavascriptInterface(bridge, "LinboxBridge")
            webView.loadUrl(TERMINAL_URL)
            webView
        },
    )
}
