// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.data

import android.content.Context
import com.k60linbox.manager.net.Downloader
import com.k60linbox.manager.net.GitHubReleaseClient
import com.k60linbox.manager.runtime.Linbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Magisk 模块的查找、下载与安装。 */
class ModuleRepository(private val context: Context) {

    companion object {
        const val REPO = "fffeng99999/k60-linbox"
        const val MODULE_ASSET = "k60-linbox-magisk.zip"
    }

    private val releases = GitHubReleaseClient()
    private val downloader = Downloader()

    data class ModuleRelease(val tag: String, val url: String, val size: Long)

    /** 结果：zip 落在本地，以及是否已经由 Magisk CLI 直接装好。 */
    data class InstallResult(val tag: String, val zipPath: String, val installedByCli: Boolean)

    suspend fun latest(): Result<ModuleRelease> = withContext(Dispatchers.IO) {
        releases.latest(REPO).mapCatching { release ->
            val asset = release.asset(MODULE_ASSET)
                ?: throw IllegalStateException("该 Release 里没有 $MODULE_ASSET")
            ModuleRelease(release.tag, asset.url, asset.size)
        }
    }

    /** 下载模块 zip 并尝试安装；CLI 不可用时把 zip 路径交回 UI 让用户手动装。 */
    suspend fun install(onProgress: (Downloader.Progress) -> Unit): Result<InstallResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val release = latest().getOrThrow()
                val target = File(context.cacheDir, MODULE_ASSET)
                downloader.download(release.url, target, onProgress).getOrThrow()
                InstallResult(release.tag, target.absolutePath, installFrom(target))
            }
        }

    private fun installFrom(zip: File): Boolean {
        if (!Linbox.shell("command -v magisk >/dev/null 2>&1").ok) return false
        return Linbox.shell("magisk --install-module ${Linbox.quote(zip.absolutePath)}").ok
    }
}
