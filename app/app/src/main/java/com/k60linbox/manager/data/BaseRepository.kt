// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.data

import com.k60linbox.manager.runtime.ActionInfo
import com.k60linbox.manager.runtime.BaseInfo
import com.k60linbox.manager.runtime.Linbox
import com.k60linbox.manager.runtime.toResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 发行版根文件系统（base）的登记、导入与删除。 */
class BaseRepository {

    suspend fun list(): Result<List<BaseInfo>> = io {
        Linbox.call<List<BaseInfo>>("base", "list").toResult()
    }

    /** 从压缩包导入；CLI 按魔数自动识别 gz/xz。 */
    suspend fun import(archivePath: String, name: String): Result<ActionInfo> = io {
        Linbox.call<ActionInfo>("base", "import", archivePath, "--name", name).toResult()
    }

    suspend fun remove(name: String, force: Boolean = false): Result<ActionInfo> = io {
        val args = mutableListOf("base", "remove", name)
        if (force) args += "--force"
        Linbox.callList<ActionInfo>(args).toResult()
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
