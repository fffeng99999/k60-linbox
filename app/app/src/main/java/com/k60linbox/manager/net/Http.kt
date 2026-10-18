// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 全局共享的 HTTP 客户端（发行版镜像动辄上百 MB，超时放宽）。 */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}
