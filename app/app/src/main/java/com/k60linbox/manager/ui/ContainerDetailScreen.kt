// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.k60linbox.manager.net.MirrorPresets

private val DETAIL_TABS = listOf("概览", "端口", "快照", "日志")

/** 单个容器的管理界面。终端与文件页将在后续阶段加入。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerDetailScreen(name: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: ContainerDetailViewModel = viewModel(
        key = "detail-$name",
        factory = SimpleFactory { ContainerDetailViewModel(app, name) },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableIntStateOf(0) }
    var destroyAsk by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbar.showSnackbar(message)
            vm.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = { TextButton(onClick = { vm.refresh() }) { Text("刷新") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            TabRow(selectedTabIndex = tab) {
                DETAIL_TABS.forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> OverviewTab(state, vm, onDestroy = { destroyAsk = true })
                1 -> PortsTab(state, vm)
                2 -> SnapshotsTab(state, vm)
                else -> LogsTab(state, vm)
            }
        }
    }

    if (destroyAsk) {
        ConfirmDialog(
            title = "销毁容器 $name",
            body = "将停止容器并删除它的磁盘镜像与快照，无法恢复。",
            confirmText = "销毁",
            onConfirm = { vm.destroy(); destroyAsk = false },
            onDismiss = { destroyAsk = false },
        )
    }
}

// ---------------------------------------------------------------- 概览

@Composable
private fun OverviewTab(
    state: ContainerDetailViewModel.State,
    vm: ContainerDetailViewModel,
    onDestroy: () -> Unit,
) {
    val info = state.info
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(if (info?.running == true) "运行中" else "已停止", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text("IP：${info?.ip.orEmpty().ifEmpty { "—" }}", style = MaterialTheme.typography.bodySmall)
                Text("主机名：${info?.host.orEmpty().ifEmpty { "—" }}", style = MaterialTheme.typography.bodySmall)
                Text("内存上限：${info?.memoryMB?.let { "$it MB" } ?: "—"}", style = MaterialTheme.typography.bodySmall)
                info?.pid?.let { Text("PID：$it", style = MaterialTheme.typography.bodySmall) }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (info?.running == true) {
                Button(onClick = { vm.stop() }) { Text("停止") }
                OutlinedButton(onClick = { vm.restart() }) { Text("重启") }
            } else {
                Button(onClick = { vm.start() }) { Text("启动") }
            }
            OutlinedButton(onClick = onDestroy) { Text("销毁") }
        }

        Spacer(Modifier.height(20.dp))
        Text("资源调整", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        var memText by remember(info?.memoryMB) { mutableStateOf((info?.memoryMB ?: 2048).toString()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = memText,
                onValueChange = { memText = it },
                label = { Text("内存上限 MB（运行中即时生效）") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                enabled = memText.toIntOrNull() != null,
                onClick = { memText.toIntOrNull()?.let { vm.setMemory(it) } },
            ) { Text("应用") }
        }

        Spacer(Modifier.height(12.dp))
        var sizeText by remember { mutableStateOf("8G") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = sizeText,
                onValueChange = { sizeText = it },
                label = { Text("磁盘容量（停止态扩容）") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                enabled = sizeText.isNotBlank() && info?.running != true,
                onClick = { vm.resize(sizeText.trim()) },
            ) { Text("扩容") }
        }
        if (info?.running == true) {
            Text("扩容需先停止容器。", style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(16.dp))
        Text("容器内 apt 换源", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MirrorPresets.all.forEach { preset ->
                OutlinedButton(onClick = { vm.applyMirror(preset.id) }) { Text(preset.label) }
            }
        }
        Text("换源不需要容器运行；首次改写会备份原文件，可再点「官方源」还原。",
            style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("开机自启", style = MaterialTheme.typography.titleSmall)
                Text("开机后自动启动该容器", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = state.autostart, onCheckedChange = { vm.setAutostart(it) })
        }

        Spacer(Modifier.height(20.dp))
        Text("快捷执行", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        var command by remember { mutableStateOf("cat /etc/os-release") }
        var output by remember { mutableStateOf("") }
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            label = { Text("在容器内执行（需容器运行中）") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Button(
            enabled = command.isNotBlank() && info?.running == true,
            onClick = { vm.exec(command) { output = it } },
        ) { Text("运行") }
        if (output.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(output, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------- 端口

@Composable
private fun PortsTab(state: ContainerDetailViewModel.State, vm: ContainerDetailViewModel) {
    var proto by remember { mutableStateOf("tcp") }
    var hostPort by remember { mutableStateOf("8022") }
    var containerPort by remember { mutableStateOf("22") }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            Text("端口映射（宿主端口 -> 容器端口）", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ChoiceRow("TCP", proto == "tcp") { proto = "tcp" }
                ChoiceRow("UDP", proto == "udp") { proto = "udp" }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = hostPort,
                    onValueChange = { hostPort = it },
                    label = { Text("宿主端口") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = containerPort,
                    onValueChange = { containerPort = it },
                    label = { Text("容器端口") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = hostPort.toIntOrNull() != null && containerPort.toIntOrNull() != null,
                    onClick = {
                        vm.addPort(proto, hostPort.toIntOrNull() ?: 0, containerPort.toIntOrNull() ?: 0)
                    },
                ) { Text("添加") }
                OutlinedButton(onClick = { vm.syncPorts() }) { Text("重放全部") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "网络切换后系统会重刷防火墙，映射可能失效；点「重放全部」可恢复。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        HorizontalDivider()

        if (state.ports.isEmpty()) {
            Text("（暂无映射）", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.ports, key = { "${it.proto}:${it.hostPort}:${it.containerPort}" }) { mapping ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${mapping.proto.uppercase()}  宿主 ${mapping.hostPort} → 容器 ${mapping.containerPort}",
                            modifier = Modifier.weight(1f),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = {
                            vm.removePort(mapping.proto, mapping.hostPort, mapping.containerPort)
                        }) { Text("删除") }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 快照

@Composable
private fun SnapshotsTab(state: ContainerDetailViewModel.State, vm: ContainerDetailViewModel) {
    var label by remember { mutableStateOf("snap-1") }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            Text("快照会复制容器的 ext4 镜像，要求容器先停止。", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("快照标签") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = label.isNotBlank() && state.info?.running != true,
                    onClick = { vm.createSnapshot(label.trim()) },
                ) { Text("建快照") }
            }
        }
        HorizontalDivider()

        if (state.snapshots.isEmpty()) {
            Text("（暂无快照）", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.snapshots, key = { it.label }) { snapshot ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(snapshot.label, style = MaterialTheme.typography.titleMedium)
                        Text(humanBytes(snapshot.bytes), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = state.info?.running != true,
                                onClick = { vm.restoreSnapshot(snapshot.label) },
                            ) { Text("恢复") }
                            OutlinedButton(onClick = { vm.removeSnapshot(snapshot.label) }) { Text("删除") }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 日志

@Composable
private fun LogsTab(state: ContainerDetailViewModel.State, vm: ContainerDetailViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("容器控制台输出", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.loadLogs() }) { Text("刷新") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            state.logs.joinToString("\n").ifEmpty { "（暂无日志；日志在容器运行期间产生，停止后会被清理）" },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        )
    }
}

private fun humanBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f G".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f M".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.1f K".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}
