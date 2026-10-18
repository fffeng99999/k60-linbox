// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.k60linbox.manager.data.copyUriTo
import com.k60linbox.manager.data.uriDisplayName
import com.k60linbox.manager.net.DistroCatalog
import com.k60linbox.manager.net.MirrorPresets
import com.k60linbox.manager.runtime.BaseInfo
import com.k60linbox.manager.runtime.ContainerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val TABS = listOf("容器", "发行版", "设置")

/** 主界面：容器 / 发行版 / 设置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: MainViewModel = viewModel(factory = SimpleFactory { MainViewModel(app) })
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableIntStateOf(0) }
    var createOpen by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }

    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbar.showSnackbar(message)
            vm.consumeMessage()
        }
    }

    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { pendingImport = prepareImport(context, uri) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("k60-linbox") },
                actions = { TextButton(onClick = { vm.refresh() }) { Text("刷新") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (tab == 0) {
                FloatingActionButton(onClick = { createOpen = true }) { Text("＋") }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                TABS.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title) },
                    )
                }
            }
            when (tab) {
                0 -> ContainersTab(state, onOpen)
                1 -> DistrosTab(
                    state = state,
                    onDownload = { distro -> vm.downloadDistro(distro) },
                    onImport = {
                        picker.launch(
                            arrayOf(
                                "application/gzip",
                                "application/x-xz",
                                "application/x-compressed-tar",
                                "application/octet-stream",
                                "*/*",
                            ),
                        )
                    },
                    onRemove = { name, force -> vm.removeBase(name, force) },
                )
                else -> SettingsTab(state, vm)
            }
        }
    }

    if (createOpen) {
        CreateDialog(
            bases = state.bases.map { it.name },
            defaultSize = state.prefs?.defaultSize ?: "4G",
            defaultMem = state.prefs?.defaultMemMb ?: 2048,
            defaultInit = state.prefs?.defaultInit ?: "/sbin/init",
            onDismiss = { createOpen = false },
            onCreate = { name, base, size, mem, host, init ->
                vm.createContainer(name, base, size, mem, host, init)
                createOpen = false
            },
        )
    }

    val pending = pendingImport
    if (pending != null) {
        ImportNameDialog(
            suggestion = pending.suggestedName,
            onDismiss = { pendingImport = null },
            onConfirm = { name ->
                vm.importArchive(pending.path, name)
                pendingImport = null
            },
        )
    }
}

// ---------------------------------------------------------------- 容器

@Composable
private fun ContainersTab(state: MainViewModel.State, onOpen: (String) -> Unit) {
    if (state.loading) {
        LoadingScreen()
        return
    }
    if (state.containers.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("还没有容器。点右下角「＋」新建。")
            Spacer(Modifier.height(6.dp))
            Text(
                "若没有可用的发行版，请先到「发行版」页导入一个 rootfs 归档。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.containers, key = { it.name }) { container ->
            ContainerCard(container = container, onOpen = onOpen)
        }
    }
}

@Composable
private fun ContainerCard(container: ContainerInfo, onOpen: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(container.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    text = if (container.running) "运行中" else "已停止",
                    color = if (container.running) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${container.ip.ifEmpty { "无 IP" }}  ·  ${container.host.ifEmpty { "无主机名" }}" +
                    (container.memoryMB?.let { "  ·  ${it}MB" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onOpen(container.name) }) { Text("管理") }
            }
        }
    }
}

@Composable
private fun CreateDialog(
    bases: List<String>,
    defaultSize: String,
    defaultMem: Int,
    defaultInit: String,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, Int, String?, String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var base by remember(bases) { mutableStateOf(bases.firstOrNull().orEmpty()) }
    var size by remember { mutableStateOf(defaultSize) }
    var mem by remember { mutableStateOf(defaultMem.toString()) }
    var host by remember { mutableStateOf("") }
    var init by remember { mutableStateOf(defaultInit) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建容器") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                    label = { Text("发行版（base 名）") },
                    supportingText = {
                        Text(
                            if (bases.isEmpty()) "还没有已导入的发行版，请先到「发行版」页导入"
                            else "可用：${bases.joinToString(", ")}",
                        )
                    },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = size,
                    onValueChange = { size = it },
                    label = { Text("磁盘镜像大小（如 4G）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = mem,
                    onValueChange = { mem = it },
                    label = { Text("内存上限（MB）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("主机名（留空自动生成）") },
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
                        name.trim(),
                        base.trim(),
                        size.trim(),
                        mem.trim().toIntOrNull() ?: defaultMem,
                        host.trim().ifBlank { null },
                        init.trim().ifBlank { null },
                    )
                },
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------------------------------------------------------------- 发行版

@Composable
private fun DistrosTab(
    state: MainViewModel.State,
    onDownload: (DistroCatalog.Distro) -> Unit,
    onImport: () -> Unit,
    onRemove: (String, Boolean) -> Unit,
) {
    var removeTarget by remember { mutableStateOf<BaseInfo?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(16.dp)) {
            Text("在线安装发行版", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "下载官方归档后自动导入为可直接建容器的发行版。Ubuntu 走 base tarball，" +
                    "Debian 走 cloud 镜像（国内暂无镜像，走官方源）。",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val busy = state.distroBusy
        if (busy != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                LinearProgressIndicator(
                    progress = { if (state.distroProgress < 0) 0f else state.distroProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(state.distroStage.ifEmpty { "处理中…" }, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
        }

        CatalogGroup("Ubuntu", DistroCatalog.ubuntu, busy, onDownload)
        CatalogGroup("Debian", DistroCatalog.debian, busy, onDownload)

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Column(Modifier.padding(16.dp)) {
            Text("已导入的发行版", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onImport) { Text("从本地文件导入") }
        }
        if (state.bases.isEmpty()) {
            Text("（尚未导入任何发行版）", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        } else {
            state.bases.forEach { base ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(base.name, style = MaterialTheme.typography.titleMedium)
                        Text(base.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        if (base.containerList.isNotEmpty()) {
                            Text(
                                "被容器引用：${base.containerList.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { removeTarget = base }) { Text("删除") }
                    }
                }
            }
        }
    }

    val target = removeTarget
    if (target != null) {
        val referenced = target.containerList.isNotEmpty()
        ConfirmDialog(
            title = "删除发行版 ${target.name}",
            body = if (referenced) "仍有容器引用它：${target.containerList.joinToString(", ")}。删除后这些容器将无法再创建，确定继续？"
            else "将删除该发行版并清理其 rootfs 目录。",
            confirmText = "删除",
            onConfirm = {
                onRemove(target.name, referenced)
                removeTarget = null
            },
            onDismiss = { removeTarget = null },
        )
    }
}

@Composable
private fun CatalogGroup(
    title: String,
    distros: List<DistroCatalog.Distro>,
    busy: String?,
    onDownload: (DistroCatalog.Distro) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        distros.forEach { distro ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(distro.name, modifier = Modifier.weight(1f))
                    OutlinedButton(
                        enabled = busy == null,
                        onClick = { onDownload(distro) },
                    ) { Text(if (busy == distro.id) "下载中…" else "下载并安装") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ImportNameDialog(suggestion: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(suggestion.ifBlank { "distro" }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入为发行版") },
        text = {
            Column {
                Text("归档已复制到应用缓存目录，将用运行时导入并生成磁盘镜像。")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("发行版名称（base 名）") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) },
            ) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------------------------------------------------------------- 设置

@Composable
private fun SettingsTab(state: MainViewModel.State, vm: MainViewModel) {
    val prefs = state.prefs
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("下载发行版用的镜像站", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        MirrorPresets.all.forEach { preset ->
            ChoiceRow(
                label = preset.label,
                selected = prefs?.downloadMirror == preset.id,
                onSelect = { vm.setDownloadMirror(preset.id) },
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("容器内默认换源（apt）", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        MirrorPresets.all.forEach { preset ->
            ChoiceRow(
                label = preset.label,
                selected = prefs?.aptMirror == preset.id,
                onSelect = { vm.setAptMirror(preset.id) },
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("容器退出/崩溃通知", style = MaterialTheme.typography.titleSmall)
                Text("开启后监控容器异常退出并通知", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = prefs?.monitorEnabled == true,
                onCheckedChange = { vm.setMonitorEnabled(it) },
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("新建容器默认值", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))

        var mem by remember(prefs?.defaultMemMb) { mutableStateOf((prefs?.defaultMemMb ?: 2048).toString()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = mem,
                onValueChange = { mem = it },
                label = { Text("内存 MB") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                enabled = mem.toIntOrNull() != null,
                onClick = { mem.toIntOrNull()?.let { vm.setDefaultMem(it) } },
            ) { Text("保存") }
        }

        var size by remember(prefs?.defaultSize) { mutableStateOf(prefs?.defaultSize ?: "4G") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = size,
                onValueChange = { size = it },
                label = { Text("磁盘大小") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                enabled = size.isNotBlank(),
                onClick = { vm.setDefaultSize(size.trim()) },
            ) { Text("保存") }
        }

        var init by remember(prefs?.defaultInit) { mutableStateOf(prefs?.defaultInit ?: "/sbin/init") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = init,
                onValueChange = { init = it },
                label = { Text("init 路径") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                enabled = init.isNotBlank(),
                onClick = { vm.setDefaultInit(init.trim()) },
            ) { Text("保存") }
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { vm.resetOnboarding() }) { Text("重新运行首次引导") }
    }
}

// ---------------------------------------------------------------- 辅助

private data class PendingImport(val path: String, val suggestedName: String)

/** 把 SAF 选中的归档复制到应用缓存目录，返回 root 可读的路径与建议的 base 名。 */
private suspend fun prepareImport(context: Context, uri: Uri): PendingImport? = withContext(Dispatchers.IO) {
    runCatching {
        val target = File(context.cacheDir, "linbox-import-${System.currentTimeMillis()}.archive")
        copyUriTo(context, uri, target)
        PendingImport(target.absolutePath, sanitizeName(uriDisplayName(context, uri) ?: "distro"))
    }.getOrNull()
}

/** base 名只允许字母数字/短横线/下划线，故把压缩包名清洗成合法名字。 */
private fun sanitizeName(raw: String): String {
    val base = raw.substringBefore(".tar").ifBlank { raw }
    val cleaned = base.map { if (it.isLetterOrDigit() || it in "-_") it else '_' }.joinToString("")
    return cleaned.take(32).ifBlank { "distro" }
}
