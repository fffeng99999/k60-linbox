// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.k60linbox.manager.data.DoctorRepository
import com.k60linbox.manager.data.ModuleRepository
import com.k60linbox.manager.data.SettingsStore
import com.k60linbox.manager.data.describe
import com.k60linbox.manager.runtime.DoctorInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 首次引导：查 root、Magisk 模块（可自动下载安装）、PID 命名空间能力。 */
class OnboardingViewModel(app: Application) : AndroidViewModel(app) {

    private val doctor = DoctorRepository()
    private val modules = ModuleRepository(app)
    private val settings = SettingsStore(app)

    data class State(
        val checking: Boolean = true,
        val info: DoctorInfo? = null,
        val error: String? = null,
        val installing: Boolean = false,
        val progress: Int = -1,
        val installNote: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init { check() }

    fun check() {
        viewModelScope.launch {
            _state.update { it.copy(checking = true, error = null) }
            val result = doctor.doctor()
            _state.update {
                it.copy(
                    checking = false,
                    info = result.getOrNull(),
                    error = result.exceptionOrNull()?.describe(),
                )
            }
        }
    }

    /**
     * 从 GitHub Release 下载模块 zip 并安装。
     * CLI 装不了（例如没有 magisk 命令）时不算失败，把 zip 路径告诉用户手动装。
     */
    fun installModule() {
        if (_state.value.installing) return
        viewModelScope.launch {
            _state.update { it.copy(installing = true, progress = -1, installNote = null) }
            val result = modules.install { progress -> _state.update { it.copy(progress = progress.percent) } }
            val note = result.fold(
                { r ->
                    if (r.installedByCli) {
                        "模块 ${r.tag} 已安装：请重启设备，回来后点「重新检测」。"
                    } else {
                        "已下载到 ${r.zipPath}，请在 Magisk 里手动安装后重启。"
                    }
                },
                { throwable -> "安装失败：${throwable.describe()}" },
            )
            _state.update { it.copy(installing = false, installNote = note) }
            check()
        }
    }

    /** 记下「已引导」，之后直接进主界面（失败也照走，避免把用户锁在引导页）。 */
    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            settings.setOnboarded(true)
            onDone()
        }
    }
}
