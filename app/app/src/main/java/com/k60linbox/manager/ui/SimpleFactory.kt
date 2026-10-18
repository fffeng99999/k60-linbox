// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/** 简单的 ViewModel 工厂：把已经构造好的实例交给 Compose 的 viewModel()。 */
class SimpleFactory<T : ViewModel>(private val builder: () -> T) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = builder() as VM
}
