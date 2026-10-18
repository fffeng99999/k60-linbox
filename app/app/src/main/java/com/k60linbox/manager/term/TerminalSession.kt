// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.term

import com.k60linbox.manager.runtime.Linbox
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 一个容器终端会话。
 *
 * 真正的 PTY 由设备端的 `busybox script` 提供（见 `linbox attach`），
 * 这里只负责起进程、搬运字节，以及与 WebView 之间的回调。
 * 输出走 [onOutput]（原始字节，避免 UTF-8 被切断后还原不出来），
 * 输入由 [write] 写入进程 stdin。
 */
class TerminalSession(private val containerName: String) {

    private companion object {
        /** Ctrl-D：交互式 shell 收到它就当作 EOF。 */
        const val EOF: Int = 4
    }

    private var process: Process? = null
    private var reader: Thread? = null

    @Volatile
    private var closed = false

    var onOutput: ((ByteArray) -> Unit)? = null
    var onExit: ((Int) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    val running: Boolean get() = process?.isAlive == true

    /** 用终端给的行列数启动会话；busybox script 之后无法改窗口大小，所以只设置一次。 */
    fun start(cols: Int, rows: Int) {
        if (process != null) return
        val command = "${Linbox.bin()} attach ${Linbox.quote(containerName)} --cols $cols --rows $rows"
        val started = try {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .apply { environment()["TERM"] = "xterm-256color" }
                .start()
        } catch (t: Throwable) {
            onLog?.invoke("启动终端失败: ${t.message}")
            return
        }
        process = started

        reader = thread(name = "linbox-term-$containerName", isDaemon = true) {
            val buffer = ByteArray(8192)
            try {
                started.inputStream.use { input ->
                    while (!closed) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        onOutput?.invoke(buffer.copyOf(read))
                    }
                }
            } catch (_: Throwable) {
                // 会话被关掉时 read 会抛异常，属于正常路径
            }
            val code = try {
                started.waitFor()
            } catch (_: Throwable) {
                -1
            }
            if (!closed) onExit?.invoke(code)
        }
    }

    fun write(data: ByteArray) {
        val sink: OutputStream = process?.outputStream ?: return
        try {
            sink.write(data)
            sink.flush()
        } catch (_: Throwable) {
            // 进程已退出时写入会失败，忽略
        }
    }

    /**
     * 结束会话。
     *
     * 先往 PTY 里送一个 EOF（Ctrl-D）让容器内的交互式 shell 自己退出，再兜底 destroy。
     * 设备实测：只 destroy 的话作用到的是 `su`，里面的 `busybox script` 与 shell 会被留下，
     * 一直占着 PTY；单独给 stdin 关掉（EOF）也不会让 script 退出。
     */
    fun close() {
        closed = true
        val current = process ?: return
        try {
            current.outputStream?.use {
                it.write(EOF)
                it.flush()
            }
            current.waitFor(500, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
        }
        try {
            current.destroy()
        } catch (_: Throwable) {
        }
        process = null
    }
}
