// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 读 GitHub Release 的资产列表。
 *
 * 用 latest release 的资产名定位（不解析 release 正文），配 release.json 做版本/协议判断。
 */
class GitHubReleaseClient(private val client: OkHttpClient = Http.client) {

    data class Asset(val name: String, val url: String, val size: Long)

    data class Release(val tag: String, val assets: List<Asset>) {
        fun asset(name: String): Asset? = assets.firstOrNull { it.name == name }
    }

    @Serializable
    private data class GhAsset(
        val name: String = "",
        @SerialName("browser_download_url") val url: String = "",
        val size: Long = 0,
    )

    @Serializable
    private data class GhRelease(
        @SerialName("tag_name") val tag: String = "",
        val assets: List<GhAsset> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun latest(repo: String): Result<Release> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            val text = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("GitHub API HTTP ${response.code}")
                response.body?.string() ?: throw IOException("GitHub API 响应为空")
            }
            val release = json.decodeFromString<GhRelease>(text)
            Release(release.tag, release.assets.map { Asset(it.name, it.url, it.size) })
        }
    }
}
