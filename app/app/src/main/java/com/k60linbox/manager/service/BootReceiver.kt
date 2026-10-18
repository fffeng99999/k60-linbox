// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.k60linbox.manager.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 开机后按用户设置决定要不要把容器监控重新拉起来。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (SettingsStore(appContext).monitorEnabled.first()) {
                    ContainerMonitorService.start(appContext)
                }
            } catch (_: Throwable) {
                // 开机阶段失败不影响系统启动，下次打开 App 时还能再开
            } finally {
                pending.finish()
            }
        }
    }
}
