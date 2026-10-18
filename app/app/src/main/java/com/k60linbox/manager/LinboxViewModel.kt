// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val busy: Boolean = false,
    val containers: List<Container> = emptyList(),
    val bases: List<String> = emptyList(),
    val caps: String = "",
    val log: String = "",
    val message: String? = null,
)

class LinboxViewModel : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        refresh()
    }

    /** 重新读取容器列表、base 列表与能力自检。 */
    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val (list, bases, caps) = withContext(Dispatchers.IO) {
                Triple(
                    Linbox.call("list"),
                    Linbox.call("base", "list"),
                    Linbox.call("caps"),
                )
            }
            _state.value = _state.value.copy(
                busy = false,
                containers = Linbox.parseList(list.output),
                bases = Linbox.parseBases(bases.output),
                caps = caps.output.trim(),
            )
        }
    }

    /** 执行一条 linbox 命令，输出追加到控制台，然后刷新列表。 */
    fun run(vararg args: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { Linbox.call(*args) }
            _state.value = _state.value.copy(
                busy = false,
                log = _state.value.log +
                    "\n$ ${Linbox.bin()} ${args.joinToString(" ")}\n" +
                    result.output.trim() + "\n",
                message = if (result.ok) "执行完成" else "命令失败（退出码 ${result.code}）",
            )
            refresh()
        }
    }

    /** 查询某个容器的详细状态并打印到控制台。 */
    fun status(name: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { Linbox.call("status", name) }
            _state.value = _state.value.copy(log = _state.value.log + "\n" + result.output.trim() + "\n")
        }
    }

    fun clearLog() {
        _state.value = _state.value.copy(log = "")
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
