// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.k60linbox.manager.data.DoctorRepository
import com.k60linbox.manager.data.SettingsStore
import com.k60linbox.manager.data.describe
import com.k60linbox.manager.runtime.DoctorInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 首次引导：查 root、Magisk 模块、PID 命名空间能力。 */
class OnboardingViewModel(app: Application) : AndroidViewModel(app) {

    private val doctor = DoctorRepository()
    private val settings = SettingsStore(app)

    data class State(
        val checking: Boolean = true,
        val info: DoctorInfo? = null,
        val error: String? = null,
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

    /** 记下「已引导」，之后直接进主界面（失败也照走，避免把用户锁在引导页）。 */
    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            settings.setOnboarded(true)
            onDone()
        }
    }
}
