// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager

import java.io.File

/** 容器列表项。 */
data class Container(val name: String, val state: String) {
    val running: Boolean get() = state == "running"
}

/** 单个容器的详细状态。 */
data class ContainerStatus(
    val name: String,
    val state: String,
    val pid: String?,
    val host: String?,
    val ip: String?,
)

/**
 * 与设备端 linbox 运行时交互的最小封装。
 *
 * 全部操作都通过 `su -c ...` 以 root 执行，命令由本文件自行拼装，
 * 参数只允许安全字符，避免注入。
 */
object Linbox {

    private const val CANDIDATE_SYSTEM = "/system/bin/linbox"
    private const val CANDIDATE_LOCAL = "/data/local/bin/linbox"

    @Volatile
    private var cachedBin: String? = null

    data class Result(val code: Int, val output: String) {
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

    /** 以 root 身份执行一条命令；stdout/stderr 合并返回。 */
    fun su(command: String): Result = try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        Result(process.waitFor(), output)
    } catch (t: Throwable) {
        Result(-1, "执行失败: ${t.message}")
    }

    /** 执行 `linbox <args>`。 */
    fun call(vararg args: String): Result {
        val line = buildString {
            append(bin())
            for (a in args) {
                append(' ')
                append(quote(a))
            }
        }
        return su(line)
    }

    /** 容器名/标签所用的安全字符直接使用，其余用单引号包裹。 */
    private fun quote(s: String): String =
        if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "-_./" }) s
        else "'" + s.replace("'", "'\\''") + "'"

    /** 解析 `linbox list` 输出；跳过运行时的 [linbox] 日志行。 */
    fun parseList(output: String): List<Container> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("[") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 2) Container(parts[0], parts[1]) else null
            }
            .toList()

    /** 解析 `linbox status <名>` 输出。 */
    fun parseStatus(output: String): ContainerStatus? {
        val line = output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains(": ") && !it.startsWith("[") }
            ?: return null
        val re = Regex("^(\\S+):\\s+(\\S+)(?:\\s+\\(pid=(\\S+) ip=(\\S+) host=(\\S+)\\))?")
        val m = re.find(line) ?: return null
        fun g(i: Int) = m.groupValues.getOrNull(i)?.takeIf { it.isNotEmpty() && it != "-" }
        return ContainerStatus(
            name = m.groupValues[1],
            state = m.groupValues[2],
            pid = g(3),
            ip = g(4),
            host = g(5),
        )
    }

    /** 解析 `linbox base list` 输出为 base 名称列表。 */
    fun parseBases(output: String): List<String> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.contains(" -> ") && !it.startsWith("[") }
            .mapNotNull { it.substringBefore(" -> ").ifEmpty { null } }
            .toList()
}
