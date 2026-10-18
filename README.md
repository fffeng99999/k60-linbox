# k60-linbox

在 Redmi K60（`mondrian`，SM8475）上运行的**系统容器运行时**，目标是给出**最接近虚拟机**的
Linux 使用体验，而**不需要 hypervisor、也不是软件模拟**。

设备固件里的 Gunyah 在 EL2 运行，但内核缺少 VM manager 层，crosvm/DroidVM 这条
「真虚拟机」路线一直卡在 RM 策略上（见 `k60-gunyah`）。k60-linbox 换一条**确定能落地**
的路：用内核命名空间 + cgroup + ext4 磁盘镜像，让一个完整 Linux 发行版以**独立的根文件系统、
独立网络、独立主机名、独立 PID 1** 的形式跑起来——除了没有自己的内核，其余体验与一台
VM 高度一致。

## 它给什么

| 维度 | k60-linbox | 一台真 VM |
|---|---|---|
| 独立根文件系统 | ✅ 每容器 ext4 磁盘镜像（loop 挂载） | ✅ |
| 独立网络命名空间 + 独立 IP | ✅ veth + 网桥 + NAT | ✅ |
| 独立主机名（UTS） | ✅ | ✅ |
| 独立 PID 1 / 发行版自带 init | ⚠️ 需内核补丁（见下） | ✅ |
| 资源限额（内存/进程数） | ✅ cgroup | ✅ |
| 启停 / 重启 / 快照 / 克隆 | ✅ | ✅ |
| 玩崩不影响 Android | ✅（命名空间隔离） | ✅ |
| **独立内核** | ❌ 与 Android 共享内核 | ✅ |
| 硬件虚拟化 / 嵌套内核 | ❌ | ✅ |

> 唯一的结构性差距是「共享内核」。若你需要独立内核，那只能走 Gunyah/kexec，不在本项目范围。
>
> 实现说明：设备 `/data` 为 f2fs，内核不支持以 f2fs 作 overlayfs 的可写层，因此根文件
> 系统采用**每容器 ext4 磁盘镜像 + loop 挂载**，而非 overlay。

## 能力矩阵（实测）

当前原厂内核（`5.10.252-gki-g19f54b03cc87`）实测：

| 能力 | 原厂内核 | 打 k60-linbox 内核补丁后 |
|---|---|---|
| mnt / net / uts / cgroup 命名空间 | ✅ | ✅ |
| **PID 命名空间** | ❌ 未编译 | ✅ **已上机验证** |
| IPC / USER 命名空间 | ❌ 未编译 | ⏳ 逐项隔离中 |
| cgroup v2 | 仅 `memory` 控制器 | 不变（Android 布局） |
| ext4 镜像 / veth / bridge / NAT | ✅ | ✅ |
| 发行版 init 作为真正 PID 1 | ❌ | ✅ **已上机验证（init 自报 PID=1）** |

结论：**不打补丁也能用**（独立 rootfs/网络/主机名/cgroup）；打了只启用 `PID_NS` 的
补丁内核后，容器**已经拥有真正的独立 PID 1**（设备实测）。`IPC_NS/USER_NS/SYSVIPC/
CGROUP_PIDS` 同时打开会让内核无法启动，需要逐项隔离，详见
[docs/VERIFICATION.md](docs/VERIFICATION.md)。

## 快速开始

```sh
# 1. 准备一个 Linux 发行版根文件系统目录（例如 Arch Linux ARM / Debian rootfs）
#    放在设备上任意位置，例如 /data/local/rootfs/debian

# 2. 安装 Magisk 模块（CI 产物 k60-linbox-magisk.zip），或直接把 runtime/bin/linbox 推上去

# 3. 用运行自检确认能力
su -c 'linbox caps'

# 4. 登记 base 根文件系统并建容器
su -c 'linbox base add debian /data/local/rootfs/debian'
su -c 'linbox create demo --base debian --host demo --mem 2048'

# 5. 启动 / 进入 / 停止
su -c 'linbox start demo'
su -c 'linbox shell demo'
su -c 'linbox stop demo'
```

命令全集见 [docs/USAGE.md](docs/USAGE.md)，设计取舍见 [docs/DESIGN.md](docs/DESIGN.md)，
设备实测证据见 [docs/VERIFICATION.md](docs/VERIFICATION.md)。

## 仓库结构

```text
runtime/bin/linbox            设备端容器运行时 CLI（POSIX sh）
kernel/config/config.gz       设备基线内核 config（用于打补丁后重编）
kernel/config/Module.symvers  设备模块符号表（用于 ABI 交叉核对）
kernel/scripts/build_kernel.sh  在基线 config 上打开命名空间并编 Image
kernel/scripts/repack_boot.py   把新 Image 装回 boot v4，保留原 ramdisk
magisk/                       Magisk 模块（system/bin 覆盖 + 防 bootloop）
scripts/build_magisk.sh       组装 Magisk 包
scripts/verify_device.sh      设备能力只读自检
app/                          原生 Android 管理 App（Kotlin + Compose，CI 出 APK）
.github/workflows/            CI：打包模块 / 构建内核 / 构建 App
```

## 管理 App

`app/` 是一个原生 Android 管理前端（Kotlin + Jetpack Compose），通过
`su -c linbox ...` 操作容器：列表 / 启停 / 重启 / 状态 / 快照 / 新建 / 销毁，
以及查看运行时能力自检与控制台输出。

本地没有 Android SDK，APK 由 `.github/workflows/build-app.yml` 在 CI 构建，
产物为 `app-debug.apk`。详见 [app/README.md](app/README.md)。

## 内核补丁

`kernel/README.md` 说明需要打开的选项与构建、验证、回滚流程。要点：
**只翻转配置、不改源码**，把 `CONFIG_PID_NS / IPC_NS / USER_NS / SYSVIPC / CGROUP_PIDS`
打开，其余保持设备原样，并用 `scripts/compare_symvers.py` 与设备符号表核对 ABI。

## 隐私边界

- 运行时不读取 `/sdcard`、用户 `/data` 目录、应用数据、通讯录、短信、图片、文档。
- 容器数据只写 `/data/local/linbox`；Magisk 状态只写 `/data/adb/k60_linbox`。
- `scripts/verify_device.sh` 只使用 `getprop` / `uname` / `/proc` / `/sys` / `/dev` 与
  iproute2 查询，不抓 `logcat` / `dumpsys` / 完整 `dmesg`。

## 许可

GPL-2.0-only，见 [LICENSE](LICENSE)。
