// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** 带断点续传与摘要校验的下载器。 */
class Downloader(private val client: OkHttpClient = Http.client) {

    data class Progress(val received: Long, val total: Long) {
        /** 0..100；总量未知时为 -1。 */
        val percent: Int
            get() = if (total > 0) ((received * 100) / total).toInt().coerceIn(0, 100) else -1
    }

    /**
     * 下载到 target。target 已存在且服务端支持 Range（返回 206）时从断点继续，
     * 否则从头重写，避免把新旧内容拼在一起。
     */
    suspend fun download(
        url: String,
        target: File,
        onProgress: (Progress) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val existing = if (target.exists()) target.length() else 0L
            val request = Request.Builder().url(url).apply {
                if (existing > 0) header("Range", "bytes=$existing-")
            }.build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("响应为空")
                val append = existing > 0 && response.code == 206
                val start = if (append) existing else 0L
                val length = body.contentLength()
                val total = if (length > 0) start + length else -1L

                FileOutputStream(target, append).use { output ->
                    body.source().use { source ->
                        val buffer = ByteArray(64 * 1024)
                        var received = start
                        while (true) {
                            val read = source.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            received += read
                            onProgress(Progress(received, total))
                        }
                    }
                }
            }
            target
        }
    }

    /** 用官方 sums 文件（两列：摘要  文件名）校验已下载的文件。 */
    suspend fun verify(
        sumsUrl: String,
        algorithm: String,
        fileName: String,
        file: File,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val text = client.newCall(Request.Builder().url(sumsUrl).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("校验文件 HTTP ${response.code}")
                response.body?.string() ?: throw IOException("校验文件为空")
            }
            val expected = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val parts = line.split(Regex("\\s+"), limit = 2)
                    if (parts.size == 2 && parts[1].trimStart('*').trim() == fileName) {
                        parts[0].lowercase()
                    } else {
                        null
                    }
                }
                .firstOrNull() ?: throw IOException("校验文件里找不到 $fileName")
            val actual = digest(algorithm, file)
            if (actual != expected) throw IOException("摘要不匹配：期望 $expected，实际 $actual")
        }
    }

    private fun digest(algorithm: String, file: File): String {
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
