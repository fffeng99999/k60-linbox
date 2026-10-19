// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.runtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** linbox CLI 返回的机器可读错误。 */
class LinboxException(val code: String, message: String) : Exception(message)

/** 命令结果：成功带数据，失败带 code。 */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val code: String, val message: String) : Outcome<Nothing>
}

fun <T> Outcome<T>.toResult(): Result<T> = when (this) {
    is Outcome.Success -> Result.success(value)
    is Outcome.Failure -> Result.failure(LinboxException(code, message))
}

/** 协议握手常量；App 与运行时协议不一致时禁止写操作。 */
object LinboxContract {
    const val PROTOCOL = 1
}

/** `linbox --json` 的契约解析。 */
object LinboxJson {

    @PublishedApi
    internal val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    @Serializable
    data class ApiError(val code: String, val message: String)

    @PublishedApi
    @Serializable
    internal data class Envelope<T>(
        val ok: Boolean,
        val data: T? = null,
        val error: ApiError? = null,
    )

    /** 解析单行 JSON（容忍混在输出里的 `[linbox]` 日志行，取最后一条 `{"ok":…}`）。 */
    inline fun <reified T> parse(raw: String): Outcome<T> {
        val payload = extractJson(raw)
            ?: return Outcome.Failure("NO_JSON", "没有收到 JSON 输出（linbox 运行时是否已部署？）")
        val env = try {
            json.decodeFromString<Envelope<T>>(payload)
        } catch (t: Throwable) {
            return Outcome.Failure("PARSE_ERROR", t.message ?: "JSON 解析失败")
        }
        val error = env.error
        val data = env.data
        return when {
            error != null -> Outcome.Failure(error.code, error.message)
            env.ok && data != null -> Outcome.Success(data)
            env.ok -> Outcome.Failure("EMPTY_DATA", "命令成功但未返回数据")
            else -> Outcome.Failure("UNKNOWN", "未知错误")
        }
    }

    @PublishedApi
    internal fun extractJson(raw: String): String? {
        var found: String? = null
        for (line in raw.lineSequence()) {
            val text = line.trim()
            if (!text.startsWith("{") || !text.endsWith("}")) continue
            val element = try {
                json.parseToJsonElement(text)
            } catch (_: Throwable) {
                null
            }
            if (element is JsonObject && element.containsKey("ok")) found = text
        }
        return found
    }
}

// ---------------------------------------------------------------- 数据模型

/** 容器列表项，也是 `status` 的结果。 */
@Serializable
data class ContainerInfo(
    val name: String,
    val status: String,
    val pid: Int? = null,
    val ip: String = "",
    val host: String = "",
    @SerialName("memoryMB") val memoryMB: Int? = null,
) {
    val running: Boolean get() = status == "running"
}

/** 已登记的发行版根文件系统。 */
@Serializable
data class BaseInfo(
    val name: String,
    val path: String = "",
    val containers: String = "",
) {
    val containerList: List<String>
        get() = containers.split(' ').filter { it.isNotBlank() }
}

/** 端口映射。 */
@Serializable
data class PortMapping(
    val proto: String,
    @SerialName("hostPort") val hostPort: Int,
    @SerialName("containerPort") val containerPort: Int,
)

/** 快照。 */
@Serializable
data class SnapshotInfo(
    val label: String,
    val bytes: Long = 0,
    /** 快照在设备上的绝对路径（导出时按字节流搬运）。 */
    val path: String = "",
)

/** 容器里的一个目录项。 */
@Serializable
data class FileEntry(
    val name: String,
    /** dir / file / link */
    val type: String = "file",
    val size: Long = 0,
    val mode: String = "",
) {
    val isDir: Boolean get() = type == "dir"
}

/** `linbox ls` 的结果。 */
@Serializable
data class FileListing(
    val path: String = "/",
    val entries: List<FileEntry> = emptyList(),
)

/** 容器内执行命令的结果。 */
@Serializable
data class ExecResult(
    @SerialName("stdout") val stdout: String = "",
    @SerialName("exitCode") val exitCode: Int = 0,
)

/** 版本与协议握手结果。 */
@Serializable
data class VersionInfo(
    val cli: String,
    val protocol: Int,
    val module: String? = null,
)

@Serializable
data class ModuleInfo(
    val status: String,
    val version: String? = null,
    val path: String = "",
) {
    val installed: Boolean get() = status != "missing"
    val enabled: Boolean get() = status == "enabled"
}

@Serializable
data class NsInfo(
    val pid: String = "no",
    val mnt: String = "no",
    val net: String = "no",
    val uts: String = "no",
)

@Serializable
data class ToolsInfo(
    @SerialName("resize2fs") val resize2fs: String = "",
    @SerialName("e2fsck") val e2fsck: String = "",
    val busybox: String = "",
    val unxz: String = "no",
    val iptables: String = "",
)

@Serializable
data class CapsInfo(
    val kernel: String = "",
    val arch: String = "",
    val ns: NsInfo = NsInfo(),
    @SerialName("cgroupV2") val cgroupV2: String = "no",
    @SerialName("cgroupControllers") val cgroupControllers: String = "",
    val ext4: String = "no",
    @SerialName("mke2fsLosetup") val mke2fsLosetup: String = "no",
    val iproute2: String = "",
    val tools: ToolsInfo = ToolsInfo(),
)

/** `doctor` 一次性返回的全部引导信息。 */
@Serializable
data class DoctorInfo(
    val root: String,
    val cli: String,
    val protocol: Int,
    val module: ModuleInfo,
    val caps: CapsInfo,
) {
    val hasRoot: Boolean get() = root == "yes"
    val canRun: Boolean get() = protocol == LinboxContract.PROTOCOL
    val hasPidNs: Boolean get() = caps.ns.pid == "yes"
}

/** 各种写操作的通用返回（只取用到的字段，其余忽略）。 */
@Serializable
data class ActionInfo(
    val name: String = "",
    val status: String? = null,
    val pid: Int? = null,
    val ip: String? = null,
    val host: String? = null,
    @SerialName("memoryMB") val memoryMB: Int? = null,
    val size: String? = null,
    val bytes: Long? = null,
    val init: String? = null,
    val mirror: String? = null,
    val distro: String? = null,
    val synced: Int? = null,
    val files: Int? = null,
    val ok: Boolean? = null,
    val base: String? = null,
    val label: String? = null,
    val path: String? = null,
    val proto: String? = null,
    @SerialName("hostPort") val hostPort: Int? = null,
    @SerialName("containerPort") val containerPort: Int? = null,
    val autostart: Boolean? = null,
)
