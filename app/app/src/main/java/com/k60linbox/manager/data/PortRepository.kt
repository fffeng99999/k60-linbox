// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.data

import com.k60linbox.manager.runtime.ActionInfo
import com.k60linbox.manager.runtime.Linbox
import com.k60linbox.manager.runtime.PortMapping
import com.k60linbox.manager.runtime.toResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 端口映射（宿主端口 -> 容器端口）。映射持久化在容器目录，start 时自动重放。 */
class PortRepository {

    suspend fun list(name: String): Result<List<PortMapping>> = io {
        Linbox.call<List<PortMapping>>("port", "list", name).toResult()
    }

    suspend fun add(name: String, proto: String, hostPort: Int, containerPort: Int): Result<ActionInfo> = io {
        Linbox.call<ActionInfo>("port", "add", name, proto, hostPort.toString(), containerPort.toString()).toResult()
    }

    suspend fun remove(name: String, proto: String, hostPort: Int, containerPort: Int): Result<ActionInfo> = io {
        Linbox.call<ActionInfo>("port", "del", name, proto, hostPort.toString(), containerPort.toString()).toResult()
    }

    /** 网络切换后 netd 会重刷 iptables，用它重放全部映射。 */
    suspend fun sync(name: String): Result<ActionInfo> = io {
        Linbox.call<ActionInfo>("port", "sync", name).toResult()
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
