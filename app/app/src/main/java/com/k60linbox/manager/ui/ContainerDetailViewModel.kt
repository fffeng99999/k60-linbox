// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.k60linbox.manager.data.ContainerRepository
import com.k60linbox.manager.data.PortRepository
import com.k60linbox.manager.data.SnapshotRepository
import com.k60linbox.manager.data.describe
import com.k60linbox.manager.runtime.ContainerInfo
import com.k60linbox.manager.runtime.Linbox
import com.k60linbox.manager.runtime.PortMapping
import com.k60linbox.manager.runtime.SnapshotInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 单个容器的详情：概览、端口、快照、日志。 */
class ContainerDetailViewModel(app: Application, val containerName: String) : AndroidViewModel(app) {

    private val containers = ContainerRepository()
    private val ports = PortRepository()
    private val snapshots = SnapshotRepository()

    data class State(
        val loading: Boolean = true,
        val busy: Boolean = false,
        val info: ContainerInfo? = null,
        val ports: List<PortMapping> = emptyList(),
        val snapshots: List<SnapshotInfo> = emptyList(),
        val logs: List<String> = emptyList(),
        val autostart: Boolean = false,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val info = containers.status(containerName)
            val portList = ports.list(containerName)
            val snapList = snapshots.list(containerName)
            val autostartList = containers.autostartList()
            _state.update {
                it.copy(
                    loading = false,
                    info = info.getOrNull(),
                    ports = portList.getOrDefault(emptyList()),
                    snapshots = snapList.getOrDefault(emptyList()),
                    autostart = autostartList.getOrDefault(emptyList()).contains(containerName),
                )
            }
        }
    }

    fun act(label: String, op: suspend () -> Result<*>) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val message = op().fold(
                { "$label：完成" },
                { "$label：失败 ${it.describe()}" },
            )
            _state.update { it.copy(busy = false, message = message) }
            refresh()
        }
    }

    fun start() = act("启动") { containers.start(containerName) }
    fun stop() = act("停止") { containers.stop(containerName) }
    fun restart() = act("重启") { containers.restart(containerName) }
    fun destroy() = act("销毁") { containers.destroy(containerName) }
    fun setMemory(mb: Int) = act("内存限额") { containers.setMemory(containerName, mb) }
    fun setAutostart(on: Boolean) = act("开机自启") { containers.setAutostart(containerName, on) }
    fun resize(size: String) = act("扩容") { containers.resize(containerName, size) }

    /** 换源后如果容器在跑，顺手跑一次 apt-get update 验证源是否可用。 */
    fun applyMirrorAndVerify(preset: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val note = containers.mirror(containerName, preset).fold(
                {
                    if (_state.value.info?.running == true) {
                        containers.exec(containerName, "apt-get update").fold(
                            { result -> "换源完成；apt-get update 退出码 ${result.exitCode}" },
                            { error -> "换源完成，但 apt-get update 失败：${error.describe()}" },
                        )
                    } else {
                        "换源完成（容器未运行，启动后再验证 apt）"
                    }
                },
                { error -> "换源失败：${error.describe()}" },
            )
            _state.update { it.copy(busy = false, message = note) }
            refresh()
        }
    }

    /** 把快照镜像导出到用户选定的位置（经 root 按字节流搬运，不过内存字符串）。 */
    fun exportSnapshot(snapshot: SnapshotInfo, uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (snapshot.path.isBlank()) {
                        throw IllegalStateException("运行时不支持导出（请更新 Magisk 模块）")
                    }
                    val sink = getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("无法打开所选位置")
                    sink.use { out ->
                        val raw = Linbox.stream("cat ${Linbox.quote(snapshot.path)}", out)
                        if (!raw.ok) throw IllegalStateException(raw.output.ifBlank { "读取快照失败" })
                    }
                }
            }
            _state.update {
                it.copy(
                    message = result.fold(
                        { "快照 ${snapshot.label} 已导出" },
                        { error -> "导出失败：${error.describe()}" },
                    ),
                )
            }
        }
    }

    fun addPort(proto: String, hostPort: Int, containerPort: Int) =
        act("添加端口") { ports.add(containerName, proto, hostPort, containerPort) }

    fun removePort(proto: String, hostPort: Int, containerPort: Int) =
        act("删除端口") { ports.remove(containerName, proto, hostPort, containerPort) }

    fun syncPorts() = act("重放端口") { ports.sync(containerName) }

    fun createSnapshot(label: String) = act("建快照") { snapshots.create(containerName, label) }
    fun restoreSnapshot(label: String) = act("恢复快照") { snapshots.restore(containerName, label) }
    fun removeSnapshot(label: String) = act("删除快照") { snapshots.remove(containerName, label) }

    fun loadLogs() {
        viewModelScope.launch {
            val result = containers.log(containerName)
            _state.update {
                it.copy(
                    logs = result.getOrDefault(emptyList()),
                    message = result.exceptionOrNull()?.let { e -> "读取日志失败 ${e.describe()}" } ?: it.message,
                )
            }
        }
    }

    /** 在容器里跑一条命令（概览页的快捷执行框）。 */
    fun exec(command: String, onOutput: (String) -> Unit) {
        viewModelScope.launch {
            val result = containers.exec(containerName, command)
            val text = result.fold(
                { "退出码 ${it.exitCode}\n${it.stdout}" },
                { "执行失败 ${it.describe()}" },
            )
            onOutput(text)
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
