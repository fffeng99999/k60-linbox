// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.k60linbox.manager.runtime.LinboxContract

private enum class HelpKind { MODULE, KERNEL }

/** 首次引导：确认 root、Magisk 模块与 PID 命名空间能力；任何一项不满足都只降级、不硬封锁。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: OnboardingViewModel = viewModel(factory = SimpleFactory { OnboardingViewModel(app) })
    val state by vm.state.collectAsStateWithLifecycle()
    var help by remember { mutableStateOf<HelpKind?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("首次设置") },
                actions = { TextButton(onClick = { vm.check() }) { Text("重新检测") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("应用需要三样东西才能完整工作。逐项检查如下：", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            if (state.checking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }

            val info = state.info
            if (info == null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("没有检测到 linbox 运行时", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            state.error ?: "请先安装 k60_linbox 模块，或在终端执行 linbox doctor 排查。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else {
                StepCard(
                    index = 1,
                    title = "Root 权限",
                    ok = info.hasRoot,
                    detail = if (info.hasRoot) "已获得 root"
                    else "未授权 root：请在 Magisk / KernelSU 中允许本应用",
                )
                StepCard(
                    index = 2,
                    title = "Magisk 模块 k60_linbox",
                    ok = info.module.enabled,
                    detail = when {
                        !info.module.installed -> "未安装运行时模块"
                        !info.module.enabled -> "已安装但被禁用：请在 Magisk 中启用后重启"
                        else -> "已启用（版本 ${info.module.version ?: "未知"}）"
                    },
                    action = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!info.module.enabled) {
                                TextButton(
                                    enabled = !state.installing && info.hasRoot,
                                    onClick = { vm.installModule() },
                                ) { Text(if (state.installing) "安装中…" else "自动安装") }
                            }
                            TextButton(onClick = { help = HelpKind.MODULE }) { Text("安装说明") }
                        }
                    },
                )
                if (state.installing) {
                    LinearProgressIndicator(
                        progress = { if (state.progress < 0) 0f else state.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (state.progress < 0) "正在下载模块…" else "正在下载模块 ${state.progress}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val note = state.installNote
                if (note != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(note, style = MaterialTheme.typography.bodySmall)
                }
                val pidOk = info.hasPidNs
                val pidTitle = "独立 PID 命名空间（补丁内核）"
                val pidDetail = if (pidOk) {
                    "容器拥有独立 PID 1 —— 「最接近虚拟机」的核心条件已满足"
                } else {
                    "当前内核未启用 PID 命名空间，容器将没有独立 PID 1；不刷也能用其余功能"
                }
                if (pidOk) {
                    StepCard(index = 3, title = pidTitle, ok = true, detail = pidDetail)
                } else {
                    StepCard(index = 3, title = pidTitle, ok = false, detail = pidDetail) {
                        TextButton(onClick = { help = HelpKind.KERNEL }) { Text("刷入指引") }
                    }
                }
                if (!info.canRun) {
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "协议不匹配",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "应用协议 ${LinboxContract.PROTOCOL}，运行时协议 ${info.protocol}。请更新模块或应用后再执行写操作。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { vm.check() }, modifier = Modifier.weight(1f)) { Text("重新检测") }
                OutlinedButton(onClick = { vm.finish(onFinish) }, modifier = Modifier.weight(1f)) {
                    Text("开始使用")
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "即使某项未就绪也可以进入：应用只提供当前能力范围内的功能，不做硬封锁。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    when (help) {
        HelpKind.MODULE -> InfoDialog(
            title = "安装 Magisk 模块",
            body = """
                1. 打开 Magisk（或 KernelSU 的模块页）→ 模块 → 从本地安装
                2. 选择 k60-linbox-magisk.zip
                   （在项目 GitHub Release 的资产里下载）
                3. 安装完成后重启设备
                4. 回到本页点「重新检测」

                模块会把 linbox 运行时与 resize2fs/e2fsck 放到
                /data/adb/modules/k60_linbox/ 下。
            """.trimIndent(),
            onDismiss = { help = null },
        )

        HelpKind.KERNEL -> InfoDialog(
            title = "刷入补丁内核（可选）",
            body = """
                要让容器拥有独立 PID 1，需要内核开启 PID 命名空间。
                刷入步骤（请自行确认风险，应用不会写入任何分区）：

                1. 从项目 GitHub Release 的 kernel-v* 资产下载 Image
                2. 设备进入 fastboot，执行：
                   fastboot flash boot_a Image
                3. 重启后回来点「重新检测」
                4. 需要回滚时：fastboot flash boot_a <你备份的原厂 boot 镜像>

                提示：先备份原厂 boot，或在 Magisk 中保留一份可直接刷回的镜像。
            """.trimIndent(),
            onDismiss = { help = null },
        )

        null -> Unit
    }
}

@Composable
private fun StepCard(
    index: Int,
    title: String,
    ok: Boolean,
    detail: String,
    action: (@Composable () -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text("$index. ", style = MaterialTheme.typography.titleMedium)
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    text = if (ok) "就绪" else "待处理",
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall)
            if (action != null) {
                Spacer(Modifier.height(4.dp))
                action()
            }
        }
    }
}
