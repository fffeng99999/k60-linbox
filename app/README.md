# k60-linbox manager（管理 App）

k60-linbox 运行时的原生 Android 管理前端：Kotlin + Jetpack Compose，通过
`su -c linbox ...` 调用设备端的 [linbox 运行时](../runtime/bin/linbox)。

## 功能

- 容器列表与运行状态
- 启动 / 停止 / 重启 / 销毁
- 查看某个容器的详细状态（pid / ip / hostname）
- 建快照（复制容器的 ext4 磁盘镜像）
- 新建容器（名称、base、镜像大小、内存上限、init 路径）
- 运行时能力自检（对应 `linbox caps`，可查看是否已启用 PID 命名空间）
- 内置控制台，展示每条命令的原始输出

## 构建

本地没有 Android SDK，APK 由 CI 构建：

```text
.github/workflows/build-app.yml
```

手动触发后，在 Actions 页面下载 `k60-linbox-manager-apk-*` 产物。

本地构建（需自备 Android SDK + JDK 17 + Gradle 8.9）：

```bash
gradle -p app assembleDebug
# 产物：app/app/build/outputs/apk/debug/app-debug.apk
```

## 前置

- 设备已 root（Magisk），`su` 可用。
- 已安装 k60-linbox 的 Magisk 模块（提供 `/system/bin/linbox`），或把
  `runtime/bin/linbox` 放到 `/data/local/bin/linbox`。
- 已在终端用 `linbox base add` 登记至少一个 rootfs 目录，App 才能新建容器。

## 说明

- 所有操作均以 root 执行；命令参数只允许安全字符，其余按 shell 规则加引号。
- App 只读取 `linbox` 命令的输出，不访问用户数据目录。
