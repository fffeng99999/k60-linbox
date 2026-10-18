// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/**
 * 发行版归档目录与地址解析。
 *
 * - Ubuntu 用官方 base tarball：`<prefix>/ubuntu-base/releases/<版本>/release/`，
 *   目录里会并存多个点版本（如 24.04.3 与 24.04.4），所以运行时抓目录索引挑最新的一个，
 *   避免把点版本写死导致以后 404。
 * - Debian 用官方 cloud 镜像：`<prefix>/images/cloud/<代号>/latest/`，
 *   `latest` 是稳定软链接，文件名固定为 debian-<大版本>-genericcloud-arm64.tar.xz。
 *
 * 校验用的是官方 sums 文件：Ubuntu 有 SHA256SUMS，Debian 是 SHA512SUMS。
 */
object DistroCatalog {

    data class Distro(
        val id: String,
        val family: String,
        val name: String,
        /** URL 里的版本段：Ubuntu 是 24.04，Debian 是 bookworm 这样的代号。 */
        val urlSegment: String,
        /** Debian 用于拼文件名的大版本号；Ubuntu 用不到。 */
        val major: String,
        val suggestedBase: String,
    )

    val ubuntu: List<Distro> = listOf(
        Distro("ubuntu-22.04", "ubuntu", "Ubuntu 22.04 LTS", "22.04", "", "ubuntu2204"),
        Distro("ubuntu-24.04", "ubuntu", "Ubuntu 24.04 LTS", "24.04", "", "ubuntu2404"),
        Distro("ubuntu-26.04", "ubuntu", "Ubuntu 26.04 LTS", "26.04", "", "ubuntu2604"),
    )

    val debian: List<Distro> = listOf(
        Distro("debian-12", "debian", "Debian 12 (bookworm)", "bookworm", "12", "debian12"),
        Distro("debian-13", "debian", "Debian 13 (trixie)", "trixie", "13", "debian13"),
    )

    val all: List<Distro> = ubuntu + debian

    data class Resolved(
        val url: String,
        val fileName: String,
        val sumsUrl: String,
        val algorithm: String,
    )

    private const val UBUNTU_OFFICIAL = "https://cdimage.ubuntu.com"
    private const val DEBIAN_OFFICIAL = "https://cloud.debian.org"

    /** Ubuntu 的镜像前缀；未知镜像回退官方。 */
    fun ubuntuPrefix(mirrorId: String): String = when (mirrorId) {
        "tuna" -> "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage"
        "aliyun" -> "https://mirrors.aliyun.com/ubuntu-cdimage"
        "ustc" -> "https://mirrors.ustc.edu.cn/ubuntu-cdimage"
        else -> UBUNTU_OFFICIAL
    }

    /** Debian 云镜像国内暂无镜像，一律走官方。 */
    fun debianBase(): String = DEBIAN_OFFICIAL

    fun hasDomesticMirror(distro: Distro): Boolean = distro.family == "ubuntu"

    /** 解析出真实下载地址；失败时给出可操作的原因。 */
    suspend fun resolve(distro: Distro, mirrorId: String): Result<Resolved> = runCatching {
        if (distro.family == "ubuntu") {
            val dir = "${ubuntuPrefix(mirrorId)}/ubuntu-base/releases/${distro.urlSegment}/release/"
            val html = fetchText(dir)
            val pattern = Regex("ubuntu-base-([0-9]+(?:\\.[0-9]+)+)-base-arm64\\.tar\\.gz")
            val best = pattern.findAll(html)
                .map { it.groupValues[1] to it.value }
                .maxWithOrNull { a, b -> compareVersions(a.first, b.first) }
                ?: throw IOException("没有在 $dir 找到 arm64 base 归档")
            Resolved(dir + best.second, best.second, dir + "SHA256SUMS", "SHA-256")
        } else {
            val dir = "${debianBase()}/images/cloud/${distro.urlSegment}/latest/"
            val html = fetchText(dir)
            val found = Regex("debian-${distro.major}-genericcloud-arm64\\.tar\\.xz")
                .find(html)?.value
                ?: throw IOException("没有在 $dir 找到 arm64 cloud 归档")
            Resolved(dir + found, found, dir + "SHA512SUMS", "SHA-512")
        }
    }

    private fun versionKey(version: String): List<Int> =
        version.split('.').map { it.toIntOrNull() ?: 0 }

    /** 逐段比较版本号（List 本身不是 Comparable，故自己实现）。 */
    private fun compareVersions(a: String, b: String): Int {
        val x = versionKey(a)
        val y = versionKey(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val diff = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string() ?: throw IOException("空响应")
        }
    }
}
