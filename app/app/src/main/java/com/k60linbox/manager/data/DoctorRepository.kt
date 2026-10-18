// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.data

import com.k60linbox.manager.runtime.DoctorInfo
import com.k60linbox.manager.runtime.Linbox
import com.k60linbox.manager.runtime.VersionInfo
import com.k60linbox.manager.runtime.toResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** root / Magisk 模块 / 内核能力的一次性自检，供首次引导使用。 */
class DoctorRepository {

    suspend fun doctor(): Result<DoctorInfo> = io {
        Linbox.call<DoctorInfo>("doctor").toResult()
    }

    suspend fun version(): Result<VersionInfo> = io {
        Linbox.call<VersionInfo>("version").toResult()
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
