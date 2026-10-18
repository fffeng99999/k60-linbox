// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.term

import android.util.Base64
import android.webkit.JavascriptInterface

/**
 * 暴露给 xterm.js 的 JS 桥。
 *
 * 这些方法由 WebView 的 JavaBridge 线程调用（不是主线程），
 * 所以里面只做纯数据搬运，把线程切换留给调用方。
 */
class TerminalBridge(
    private val onInput: (ByteArray) -> Unit,
    private val onReady: (cols: Int, rows: Int) -> Unit,
    private val onLog: (String) -> Unit,
) {

    /** 键盘输入：base64 承载 UTF-8 字节。 */
    @JavascriptInterface
    fun write(base64: String) {
        val bytes = try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (_: Throwable) {
            return
        }
        onInput(bytes)
    }

    /** 页面就绪，附带它量出来的终端行列数；此时才真正启动会话。 */
    @JavascriptInterface
    fun ready(cols: Int, rows: Int) {
        onReady(cols, rows)
    }

    @JavascriptInterface
    fun log(message: String) {
        onLog(message)
    }
}
