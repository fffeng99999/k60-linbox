// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.data

import android.content.Context
import com.k60linbox.manager.net.DistroCatalog
import com.k60linbox.manager.net.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 发行版「下载 + 导入」流水线：解析地址 → 断点续传下载 → 校验 → `linbox base import`。
 *
 * 归档先落在应用缓存目录（root 可读），导入成功后即删除，避免缓存被几百 MB 占满。
 */
class DistroRepository(private val context: Context) {

    private val downloader = Downloader()
    private val bases = BaseRepository()

    data class Outcome(val baseName: String, val archive: String)

    suspend fun downloadAndImport(
        distro: DistroCatalog.Distro,
        mirrorId: String,
        onProgress: (Downloader.Progress) -> Unit,
    ): Result<Outcome> = withContext(Dispatchers.IO) {
        runCatching {
            val resolved = DistroCatalog.resolve(distro, mirrorId).getOrThrow()
            val target = File(context.cacheDir, resolved.fileName)
            downloader.download(resolved.url, target, onProgress).getOrThrow()

            // 校验尽力而为：镜像站可能没有 sums 文件，此时只提示、不阻断；
            // 内容损坏其实还会在 tar 解包时被 gzip/xz 校验拦下。
            val verifyNote: String = downloader
                .verify(resolved.sumsUrl, resolved.algorithm, resolved.fileName, target)
                .fold({ "校验通过（${resolved.algorithm}）" }, { "未校验（${it.message}）" })

            bases.import(target.absolutePath, distro.suggestedBase).getOrThrow()
            runCatching { target.delete() }
            Outcome(distro.suggestedBase, verifyNote)
        }
    }
}
