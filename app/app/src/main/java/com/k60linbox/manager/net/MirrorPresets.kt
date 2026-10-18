// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.net

/**
 * 镜像站预设。id 与设备端 `linbox mirror` 的取值一致（tuna/aliyun/ustc/official），
 * host 用于拼接发行版下载地址。
 */
object MirrorPresets {

    // 实测（2026-10-19，K60 + 设备侧代理环境）：清华 TUNA 对 pool/ 下的 .deb 返回
    // 403 Forbidden，dists/ 却能正常拉取——表现为 apt-get update 正常但 install 全部失败。
    // 阿里云同环境下 200。默认值因此从 tuna 改为 aliyun，仍可在设置里手动切回。
    const val DEFAULT = "aliyun"

    data class Preset(val id: String, val label: String, val host: String)

    val all: List<Preset> = listOf(
        Preset("tuna", "清华 TUNA", "mirrors.tuna.tsinghua.edu.cn"),
        Preset("aliyun", "阿里云", "mirrors.aliyun.com"),
        Preset("ustc", "中科大 USTC", "mirrors.ustc.edu.cn"),
        Preset("official", "官方源", ""),
    )

    fun byId(id: String): Preset = all.firstOrNull { it.id == id } ?: all.first()

    fun label(id: String): String = byId(id).label
}
