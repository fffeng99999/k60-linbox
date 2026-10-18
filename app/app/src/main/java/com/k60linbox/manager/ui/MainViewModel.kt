// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.k60linbox.manager.data.BaseRepository
import com.k60linbox.manager.data.ContainerRepository
import com.k60linbox.manager.data.SettingsStore
import com.k60linbox.manager.data.describe
import com.k60linbox.manager.runtime.BaseInfo
import com.k60linbox.manager.runtime.ContainerInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 主界面：容器列表、发行版（base）列表与设置。 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val containers = ContainerRepository()
    private val bases = BaseRepository()
    private val settings = SettingsStore(app)

    data class State(
        val loading: Boolean = true,
        val busy: Boolean = false,
        val containers: List<ContainerInfo> = emptyList(),
        val bases: List<BaseInfo> = emptyList(),
        val prefs: SettingsStore.Snapshot? = null,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val list = containers.list()
            val baseList = bases.list()
            _state.update {
                it.copy(
                    loading = false,
                    containers = list.getOrDefault(emptyList()),
                    bases = baseList.getOrDefault(emptyList()),
                    prefs = settings.snapshot(),
                )
            }
        }
    }

    /** 执行一个写操作，完成后刷新列表并把结果作为提示。 */
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

    /** 首次引导完成后再进主界面，这里也允许从设置里重跑引导。 */
    fun resetOnboarding() = act("重置引导") { settings.setOnboarded(false); Result.success(Unit) }

    fun importArchive(archivePath: String, name: String) = act("导入发行版") {
        bases.import(archivePath, name)
    }

    fun createContainer(name: String, base: String, size: String, memMb: Int, host: String?, init: String?) =
        act("创建容器") { containers.create(name, base, size, memMb, host, init) }

    fun removeBase(name: String, force: Boolean) = act("删除发行版") {
        bases.remove(name, force)
    }

    fun setDownloadMirror(id: String) = act("下载镜像站") { settings.setDownloadMirror(id); Result.success(Unit) }
    fun setAptMirror(id: String) = act("容器内换源") { settings.setAptMirror(id); Result.success(Unit) }
    fun setMonitorEnabled(on: Boolean) = act("退出通知") { settings.setMonitorEnabled(on); Result.success(Unit) }
    fun setDefaultMem(mb: Int) = act("默认内存") { settings.setDefaultMemMb(mb); Result.success(Unit) }
    fun setDefaultSize(size: String) = act("默认大小") { settings.setDefaultSize(size); Result.success(Unit) }
    fun setDefaultInit(path: String) = act("默认 init") { settings.setDefaultInit(path); Result.success(Unit) }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
