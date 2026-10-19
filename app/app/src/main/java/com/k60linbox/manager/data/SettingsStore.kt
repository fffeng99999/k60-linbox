// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.k60linbox.manager.net.MirrorPresets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** App 偏好设置（DataStore）。 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val onboarded = booleanPreferencesKey("onboarded")
        val downloadMirror = stringPreferencesKey("download_mirror")   // 下载发行版用的镜像站
        val aptMirror = stringPreferencesKey("apt_mirror")             // 容器内默认换源
        val monitorEnabled = booleanPreferencesKey("monitor_enabled")   // 退出/崩溃通知
        val defaultMemMb = intPreferencesKey("default_mem_mb")
        val defaultSize = stringPreferencesKey("default_size")
        val defaultInit = stringPreferencesKey("default_init")
    }

    /** 首次引导是否已完成；没写过的键按 false 处理（加载态由调用方用「是否已收到首个值」判断）。 */
    val onboarded: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.onboarded] ?: false }
    val downloadMirror: Flow<String> = context.settingsDataStore.data.map { it[Keys.downloadMirror] ?: MirrorPresets.DEFAULT }
    val aptMirror: Flow<String> = context.settingsDataStore.data.map { it[Keys.aptMirror] ?: MirrorPresets.DEFAULT }
    val monitorEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.monitorEnabled] ?: false }
    val defaultMemMb: Flow<Int> = context.settingsDataStore.data.map { it[Keys.defaultMemMb] ?: 2048 }
    val defaultSize: Flow<String> = context.settingsDataStore.data.map { it[Keys.defaultSize] ?: "4G" }
    // 空 = 自动：运行时优先 /sbin/init，其次 /init，都没有就生成最小 /init。
    val defaultInit: Flow<String> = context.settingsDataStore.data.map { it[Keys.defaultInit] ?: "" }

    suspend fun snapshot(): Snapshot = Snapshot(
        onboarded = onboarded.first(),
        downloadMirror = downloadMirror.first(),
        aptMirror = aptMirror.first(),
        monitorEnabled = monitorEnabled.first(),
        defaultMemMb = defaultMemMb.first(),
        defaultSize = defaultSize.first(),
        defaultInit = defaultInit.first(),
    )

    suspend fun setOnboarded(value: Boolean) { context.settingsDataStore.edit { it[Keys.onboarded] = value } }
    suspend fun setDownloadMirror(value: String) { context.settingsDataStore.edit { it[Keys.downloadMirror] = value } }
    suspend fun setAptMirror(value: String) { context.settingsDataStore.edit { it[Keys.aptMirror] = value } }
    suspend fun setMonitorEnabled(value: Boolean) { context.settingsDataStore.edit { it[Keys.monitorEnabled] = value } }
    suspend fun setDefaultMemMb(value: Int) { context.settingsDataStore.edit { it[Keys.defaultMemMb] = value } }
    suspend fun setDefaultSize(value: String) { context.settingsDataStore.edit { it[Keys.defaultSize] = value } }
    suspend fun setDefaultInit(value: String) { context.settingsDataStore.edit { it[Keys.defaultInit] = value } }

    data class Snapshot(
        val onboarded: Boolean,
        val downloadMirror: String,
        val aptMirror: String,
        val monitorEnabled: Boolean,
        val defaultMemMb: Int,
        val defaultSize: String,
        val defaultInit: String,
    )
}
