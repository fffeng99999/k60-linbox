// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** 读取 SAF 文档的显示名（用于建议文件名）。 */
fun uriDisplayName(context: Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    cursor?.use { if (it.moveToFirst() && it.columnCount > 0) return it.getString(0) }
    return uri.lastPathSegment
}

/** 把 SAF 选中的内容复制到本地文件，以便 root 侧的 linbox 命令能读到。 */
fun copyUriTo(context: Context, uri: Uri, target: File) {
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IllegalStateException("无法读取所选内容")
    input.use { source -> target.outputStream().use { sink -> source.copyTo(sink) } }
}

/** 把本地文件写进 SAF 文档（导出用）。 */
fun copyFileTo(context: Context, source: File, uri: Uri) {
    val output = context.contentResolver.openOutputStream(uri)
        ?: throw IllegalStateException("无法写入所选位置")
    output.use { sink -> source.inputStream().use { it.copyTo(sink) } }
}
