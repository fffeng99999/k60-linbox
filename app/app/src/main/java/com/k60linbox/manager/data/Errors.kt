// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.data

import com.k60linbox.manager.runtime.LinboxException

/** 把失败压成一行，供 Snackbar 展示。 */
fun Throwable.describe(): String = when (this) {
    is LinboxException -> "$code: $message"
    else -> message ?: toString()
}
