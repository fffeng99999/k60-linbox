// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.data

import com.k60linbox.manager.runtime.ActionInfo
import com.k60linbox.manager.runtime.ContainerInfo
import com.k60linbox.manager.runtime.ExecResult
import com.k60linbox.manager.runtime.Linbox
import com.k60linbox.manager.runtime.toResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 容器的生命周期与运行时调整。全部走 `linbox --json` 契约。 */
class ContainerRepository {

    suspend fun list(): Result<List<ContainerInfo>> = io {
        Linbox.call<List<ContainerInfo>>("list").toResult()
    }

    suspend fun status(name: String): Result<ContainerInfo> = io {
        Linbox.call<ContainerInfo>("status", name).toResult()
    }

    suspend fun create(
        name: String,
        base: String,
        size: String,
        memMb: Int,
        host: String? = null,
        init: String? = null,
    ): Result<ActionInfo> = io {
        val args = mutableListOf("create", name, "--base", base, "--size", size, "--mem", memMb.toString())
        if (!host.isNullOrBlank()) args += listOf("--host", host)
        if (!init.isNullOrBlank()) args += listOf("--init", init)
        Linbox.callList<ActionInfo>(args).toResult()
    }

    suspend fun start(name: String): Result<ActionInfo> = io {
        Linbox.call<ActionInfo>("start", name).toResult()
    }

    suspend fun stop(name: String): Result<ActionInfo> = io {
        Linbox.call<ActionInfo>("stop", name).toResult()
    }

    suspend fun restart(name: String): Result<ActionInfo> = io {
        Linbox.call<ActionInfo>("restart", name).toResult()
    }

    suspend fun destroy(name: String): Result<ActionInfo> = io {
        Linbox.call<ActionInfo>("destroy", name).toResult()
    }

    /** 内存限额可在运行中即时生效；ip/host/init 需先停止。 */
    suspend fun setMemory(name: String, memMb: Int): Result<ActionInfo> = io {
        Linbox.call<ActionInfo>("set", name, "--mem", memMb.toString()).toResult()
    }

    suspend fun setAutostart(name: String, enabled: Boolean): Result<ActionInfo> = io {
        Linbox.call<ActionInfo>("set", name, "--autostart", if (enabled) "1" else "0").toResult()
    }

    suspend fun autostartList(): Result<List<String>> = io {
        Linbox.call<List<String>>("autostart", "list").toResult()
    }

    suspend fun resize(name: String, size: String, dryRun: Boolean = false): Result<ActionInfo> = io {
        val args = mutableListOf("resize", name, "--size", size)
        if (dryRun) args += "--dry-run"
        Linbox.callList<ActionInfo>(args).toResult()
    }

    suspend fun mirror(name: String, preset: String): Result<ActionInfo> = io {
        Linbox.call<ActionInfo>("mirror", name, preset).toResult()
    }

    suspend fun exec(name: String, command: String): Result<ExecResult> = io {
        Linbox.call<ExecResult>("exec", name, "--", command).toResult()
    }

    suspend fun log(name: String, lines: Int = 200): Result<List<String>> = io {
        Linbox.call<List<String>>("log", name, "-n", lines.toString()).toResult()
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
