// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.runtime

import java.io.File
import java.io.OutputStream

/**
 * 设备端 `linbox` 运行时的进程级封装。
 *
 * 一切操作都经 `su -c` 以 root 执行；参数只允许安全字符，其余用单引号包裹，避免注入。
 * stderr 与 stdout 合并返回，JSON 契约的解析交给 [LinboxJson]。
 */
object Linbox {

    private const val CANDIDATE_SYSTEM = "/system/bin/linbox"
    private const val CANDIDATE_LOCAL = "/data/local/bin/linbox"

    @Volatile
    private var cachedBin: String? = null

    /** 一次命令执行的原始结果。 */
    data class Raw(val code: Int, val output: String) {
        val ok: Boolean get() = code == 0
    }

    /** linbox 可执行文件路径；找不到时回退到 PATH 上的名字。 */
    fun bin(): String {
        cachedBin?.let { return it }
        val found = listOf(CANDIDATE_SYSTEM, CANDIDATE_LOCAL)
            .firstOrNull { runCatching { File(it).exists() }.getOrDefault(false) }
            ?: "linbox"
        cachedBin = found
        return found
    }

    /** 以 root 执行一条原始 shell 命令。 */
    fun shell(command: String): Raw = try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        Raw(process.waitFor(), output)
    } catch (t: Throwable) {
        Raw(-1, "执行失败: ${t.message}")
    }

    /** 执行 `linbox <args>`（不解析，返回原始输出）。 */
    fun run(args: List<String>): Raw = shell(buildLine(args))

    /**
     * 执行命令并把 stdout 按字节流写入 sink。
     *
     * 用于把容器里的快照/文件导出到用户选定的位置：这类内容动辄上百 MB，
     * 不能当成字符串读进内存。stderr 重定向到临时文件读取，避免管道写满导致死锁。
     */
    fun stream(command: String, sink: OutputStream): Raw = try {
        val errFile = File.createTempFile("linbox-err", null)
        try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectError(errFile)
                .start()
            process.inputStream.use { it.copyTo(sink) }
            val code = process.waitFor()
            Raw(code, if (code == 0) "" else errFile.readText().trim())
        } finally {
            errFile.delete()
        }
    } catch (t: Throwable) {
        Raw(-1, "执行失败: ${t.message}")
    }

    /** 执行 `linbox <args> --json` 并按 JSON 契约解析；T 为 data 字段的类型。 */
    inline fun <reified T> callList(args: List<String>): Outcome<T> =
        LinboxJson.parse(run(args + "--json").output)

    inline fun <reified T> call(vararg args: String): Outcome<T> = callList(args.toList())

    private fun buildLine(args: List<String>): String = buildString {
        append(bin())
        for (a in args) {
            append(' ')
            append(quote(a))
        }
    }

    /** 容器名/标签所用的安全字符直接使用，其余用单引号包裹。 */
    fun quote(s: String): String =
        if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "-_./" }) s
        else "'" + s.replace("'", "'\\''") + "'"
}
