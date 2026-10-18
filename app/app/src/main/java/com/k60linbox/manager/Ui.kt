// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinboxApp(vm: LinboxViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var createOpen by remember { mutableStateOf(false) }
    var capsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbar.showSnackbar(message)
            vm.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("k60-linbox") },
                actions = {
                    TextButton(onClick = { vm.refresh() }) { Text("刷新") }
                    TextButton(onClick = { capsOpen = true }) { Text("能力") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { createOpen = true }) { Text("＋") }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ContainerList(state = state, vm = vm, modifier = Modifier.weight(1f))
            HorizontalDivider()
            ConsolePanel(log = state.log, onClear = { vm.clearLog() })
        }
    }

    if (createOpen) {
        CreateDialog(
            bases = state.bases,
            onDismiss = { createOpen = false },
            onCreate = { args ->
                vm.run(*args.toTypedArray())
                createOpen = false
            },
        )
    }

    if (capsOpen) {
        InfoDialog(title = "运行时能力自检", body = state.caps.ifEmpty { "（无输出）" }, onDismiss = { capsOpen = false })
    }
}

@Composable
private fun ContainerList(state: UiState, vm: LinboxViewModel, modifier: Modifier = Modifier) {
    if (state.containers.isEmpty()) {
        Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
            Text("当前没有容器。")
            Spacer(Modifier.height(4.dp))
            Text(
                "先准备一个 rootfs 目录并用 `linbox base add` 登记，再点右下角新建容器。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.bases.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("（尚未登记任何 base）", style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(state.containers, key = { it.name }) { container ->
            ContainerCard(container = container, vm = vm)
        }
    }
}

@Composable
private fun ContainerCard(container: Container, vm: LinboxViewModel) {
    var snapshotTarget by remember { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = container.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (container.running) "运行中" else "已停止",
                    color = if (container.running) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (container.running) {
                    Button(onClick = { vm.run("stop", container.name) }) { Text("停止") }
                    OutlinedButton(onClick = { vm.run("restart", container.name) }) { Text("重启") }
                } else {
                    Button(onClick = { vm.run("start", container.name) }) { Text("启动") }
                    OutlinedButton(onClick = { snapshotTarget = container.name }) { Text("建快照") }
                }
                OutlinedButton(onClick = { vm.status(container.name) }) { Text("状态") }
                OutlinedButton(onClick = { vm.run("destroy", container.name) }) { Text("销毁") }
            }
        }
    }

    val target = snapshotTarget
    if (target != null) {
        SnapshotDialog(
            container = target,
            onDismiss = { snapshotTarget = null },
            onConfirm = { label ->
                vm.run("snapshot", target, label)
                snapshotTarget = null
            },
        )
    }
}

@Composable
private fun ConsolePanel(log: String, onClear: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("控制台", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClear) { Text("清空") }
        }
        Text(
            text = log.ifEmpty { "（暂无输出）" },
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp, max = 200.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun CreateDialog(
    bases: List<String>,
    onDismiss: () -> Unit,
    onCreate: (List<String>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var base by remember { mutableStateOf(bases.firstOrNull().orEmpty()) }
    var size by remember { mutableStateOf("2G") }
    var mem by remember { mutableStateOf("2048") }
    var init by remember { mutableStateOf("/sbin/init") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建容器") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（字母数字/下划线/短横线）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = base,
                    onValueChange = { base = it },
                    label = { Text("base 名称") },
                    supportingText = {
                        Text(
                            if (bases.isEmpty()) "尚未登记 base，请先在终端运行 linbox base add"
                            else "可用 base：${bases.joinToString(", ")}",
                        )
                    },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = size,
                    onValueChange = { size = it },
                    label = { Text("镜像大小（如 2G）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = mem,
                    onValueChange = { mem = it },
                    label = { Text("内存上限 MB") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = init,
                    onValueChange = { init = it },
                    label = { Text("容器内 init 路径") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && base.isNotBlank(),
                onClick = {
                    onCreate(
                        listOf(
                            "create", name,
                            "--base", base,
                            "--size", size,
                            "--mem", mem,
                            "--init", init,
                        ),
                    )
                },
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SnapshotDialog(container: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var label by remember { mutableStateOf("snap-1") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("为 $container 建快照") },
        text = {
            Column {
                Text("快照会复制容器的 ext4 镜像，要求容器先停止。")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("快照标签") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank(),
                onClick = { onConfirm(label) },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun InfoDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                text = body,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
