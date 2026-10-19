// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.k60linbox.manager.data.BaseRepository
import com.k60linbox.manager.data.ContainerRepository
import com.k60linbox.manager.data.DistroRepository
import com.k60linbox.manager.data.SettingsStore
import com.k60linbox.manager.data.describe
import com.k60linbox.manager.net.DistroCatalog
import com.k60linbox.manager.runtime.BaseInfo
import com.k60linbox.manager.runtime.ContainerInfo
import com.k60linbox.manager.service.ContainerMonitorService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 主界面：容器列表、发行版（base）列表与设置。 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val containers = ContainerRepository()
    private val bases = BaseRepository()
    private val settings = SettingsStore(app)

    private var distroRepo: DistroRepository? = null

    data class State(
        val loading: Boolean = true,
        val busy: Boolean = false,
        val containers: List<ContainerInfo> = emptyList(),
        val bases: List<BaseInfo> = emptyList(),
        val prefs: SettingsStore.Snapshot? = null,
        val message: String? = null,
        /** 正在下载的发行版 id；同时只允许一个下载任务。 */
        val distroBusy: String? = null,
        val distroProgress: Int = -1,
        val distroStage: String = "",
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

    fun createContainer(
        name: String,
        base: String,
        size: String,
        memMb: Int,
        host: String?,
        init: String?,
        mirror: String? = null,
    ) = act("创建容器") { containers.create(name, base, size, memMb, host, init, mirror) }

    /**
     * 下载某个发行版并导入为 base。断点续传由 Downloader 负责，
     * 中断后重按同一个按钮会从已下载的部分继续。
     */
    fun downloadDistro(distro: DistroCatalog.Distro) {
        if (_state.value.distroBusy != null) return
        viewModelScope.launch {
            val mirror = settings.downloadMirror.first()
            _state.update {
                it.copy(distroBusy = distro.id, distroProgress = -1, distroStage = "解析下载地址")
            }
            val repo = distroRepo ?: DistroRepository(getApplication<Application>()).also { distroRepo = it }
            val result = repo.downloadAndImport(distro, mirror) { progress ->
                _state.update {
                    it.copy(
                        distroProgress = progress.percent,
                        distroStage = if (progress.percent < 0) "下载中" else "下载中 ${progress.percent}%",
                    )
                }
            }
            val message = result.fold(
                { outcome -> "${distro.name} 已导入为 ${outcome.baseName}（${outcome.archive}）" },
                { throwable ->
                    val hint = if (mirror != "official" && !DistroCatalog.hasDomesticMirror(distro)) {
                        "；该发行版没有国内镜像，请在设置里把下载镜像站换成「官方源」"
                    } else if (mirror != "official") {
                        "；可到设置里把下载镜像站换成「官方源」再试"
                    } else {
                        ""
                    }
                    "下载失败：${throwable.describe()}$hint"
                },
            )
            _state.update {
                it.copy(distroBusy = null, distroProgress = -1, distroStage = "", message = message)
            }
            refresh()
        }
    }

    fun removeBase(name: String, force: Boolean) = act("删除发行版") {
        bases.remove(name, force)
    }

    fun setDownloadMirror(id: String) = act("下载镜像站") { settings.setDownloadMirror(id); Result.success(Unit) }
    fun setAptMirror(id: String) = act("容器内换源") { settings.setAptMirror(id); Result.success(Unit) }
    /** 开关退出/崩溃通知，同时把监控前台服务拉起来或停掉。 */
    fun setMonitorEnabled(on: Boolean) = act(if (on) "开启退出通知" else "关闭退出通知") {
        settings.setMonitorEnabled(on)
        val context = getApplication<Application>()
        if (on) ContainerMonitorService.start(context) else ContainerMonitorService.stop(context)
        Result.success(Unit)
    }
    fun setDefaultMem(mb: Int) = act("默认内存") { settings.setDefaultMemMb(mb); Result.success(Unit) }
    fun setDefaultSize(size: String) = act("默认大小") { settings.setDefaultSize(size); Result.success(Unit) }
    fun setDefaultInit(path: String) = act("默认 init") { settings.setDefaultInit(path); Result.success(Unit) }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
