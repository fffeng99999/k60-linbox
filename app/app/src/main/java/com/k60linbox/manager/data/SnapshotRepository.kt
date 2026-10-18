// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.data

import com.k60linbox.manager.runtime.ActionInfo
import com.k60linbox.manager.runtime.Linbox
import com.k60linbox.manager.runtime.SnapshotInfo
import com.k60linbox.manager.runtime.toResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 快照管理（停止态复制/写回 ext4 镜像）。 */
class SnapshotRepository {

    suspend fun list(name: String): Result<List<SnapshotInfo>> = io {
        Linbox.call<List<SnapshotInfo>>("snapshot-list", name).toResult()
    }

    suspend fun create(name: String, label: String): Result<ActionInfo> = io {
        Linbox.call<ActionInfo>("snapshot", name, label).toResult()
    }

    suspend fun restore(name: String, label: String): Result<ActionInfo> = io {
        Linbox.call<ActionInfo>("restore", name, label).toResult()
    }

    suspend fun remove(name: String, label: String): Result<ActionInfo> = io {
        Linbox.call<ActionInfo>("snapshot-rm", name, label).toResult()
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
