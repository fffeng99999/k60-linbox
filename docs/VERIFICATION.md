# 设备能力验证（k60-linbox）

状态：已完成（只读探测）。生成时间：2026-10-18。
设备：Redmi K60 `mondrian` / SM8475 / Android 16 / 内核 `5.10.252-gki-g19f54b03cc87`。

本文件记录决定项目走向的实测证据。所有命令只读取系统层信息
（`getprop` / `uname` / `/proc` / `/sys` / `/dev` / iproute2 查询），未读取用户数据，
未抓 `logcat` / `dumpsys`。

## 1. 命名空间（核心判据）

```text
$ ls -l /proc/self/ns
cgroup -> cgroup:[4026531835]
mnt    -> mnt:[4026532493]
net    -> net:[4026531983]
time   -> time:[4026531834]
uts    -> uts:[4026531838]

$ su -c 'unshare -p -- true'
unshare: Invalid argument            ← PID 命名空间不可用

$ su -c 'unshare -n -m -u -- true'; echo $?
0                                    ← mnt/net/uts 可用
```

内核配置佐证（来自 `kernel/config/config.gz`）：

```text
CONFIG_NAMESPACES=y
CONFIG_UTS_NS=y
CONFIG_NET_NS=y
# CONFIG_PID_NS is not set
# CONFIG_USER_NS is not set
# CONFIG_SYSVIPC is not set     ← 故 IPC_NS 亦不可用
# CONFIG_CGROUP_PIDS is not set
```

**结论**：容器可拥有独立 rootfs / 网络 / 主机名 / 挂载树 **今天即可用**；
但**独立 PID 1 需要内核补丁**（`kernel/`）。

## 2. cgroup

```text
$ grep cgroup /proc/mounts
none /dev/blkio   cgroup  ... blkio
none /sys/fs/cgroup cgroup2 ... memory_recursiveprot
none /dev/cpuctl  cgroup  ... cpu
none /dev/cpuset  cgroup  ... cpuset,coprefix,cpuset_v2_mode

$ su -c 'cat /sys/fs/cgroup/cgroup.controllers'
memory
```

**结论**：`/sys/fs/cgroup` 是 cgroup v2，但只启用了 `memory` 控制器；cpu/cpuset/blkio
仍在 v1。本项目用 v2 `memory.max` 做内存限额，不改动 Android 的 cgroup 布局。

## 3. 文件系统与网络

```text
$ su -c 'cat /proc/filesystems'  →  ext4 / f2fs / overlay / fuse / virtiofs 均在列
$ su -c 'losetup -f'             →  /dev/block/loop40
$ su -c 'cat /proc/sys/net/ipv4/ip_forward'  →  1
$ which iptables                →  /system/bin/iptables
$ ip -V                         →  ip utility, iproute2-ss171113（完整 iproute2）
$ ip netns                      →  （空，说明支持 netns 子命令）
```

**结论**：overlay 根文件系统、veth/网桥/NAT 出网、loop 设备全部可用。

## 4. 工具链

```text
unshare / nsenter / ip / chroot / mount / losetup / busybox / tar / gzip 均可用
/system/xbin/mke2fs（可选，用于将来做 ext4 镜像模式）
```

## 5. 运行环境

```text
$ getenforce   →  Enforcing
$ su -c id     →  uid=0(root) context=u:r:magisk:s0
```

**结论**：root 可用，SELinux 保持 Enforcing；运行时不需要关闭 SELinux。

## 6. 对项目的影响

| 判据 | 结果 | 项目动作 |
|---|---|---|
| mnt/net/uts ns | ✅ | 直接使用，容器今天可跑 |
| PID ns | ❌ | 提供 `kernel/` 配置补丁解锁 |
| IPC/USER ns | ❌ | 同上（systemd 场景需要） |
| cgroup v2 | 部分 | 用 `memory.max` 限额，不动 Android 布局 |
| ext4 + loop | ✅ | 每容器磁盘镜像（overlay 在 f2fs 上不可用） |
| iproute2 / iptables | ✅ | veth + 网桥 + NAT |
| root / SELinux | ✅ | 直接运行，无需放宽 SELinux |

## 7. 复现

```bash
ADB=/path/to/adb ./scripts/verify_device.sh
# 报告输出到 reports/device-caps.txt
```

## 8. 运行时端到端实测（已在设备通过）

在设备上推送 `runtime/bin/linbox`，用最小 busybox rootfs 跑通全流程，结果如下：

```text
create  → mke2fs -d 生成 128M ext4 镜像                    ✔
start   → 网桥 lbx0 / netns linbox-demo / veth / IP 172.30.0.10  ✔
        → cgroup linbox_demo: memory.max=268435456(256MB), procs=[容器pid]  ✔
        → 容器根目录 = 挂载的镜像（bin/dev/etc/init/lost+found/...）      ✔
        → UTS 主机名 = demo                                          ✔
stop    → 无残留 loop / 进程 / cgroup                         ✔
snapshot/restore → 镜像复制与写回                              ✔
destroy → 清理干净；网桥与 NAT 规则手工清理                     ✔
```

容器进程命令行实测为 `/bin/sh /init`，说明 `chroot` 已成功进入发行版 init。

## 9. 运行时两个关键实测结论

1. **overlayfs 在本设备不可用**：`/data` 为 f2fs，内核日志
   `overlayfs: filesystem on '...upper' not supported`。因此运行时改用
   **ext4 磁盘镜像 + loop 挂载**。
2. **loop 挂载要求镜像文件为 `shell_data_file` 标签**：同一镜像内容，
   带 `system_data_file` 标签时 `mount -o loop` 返回 `I/O error`，
   `chcon u:object_r:shell_data_file:s0` 后立刻成功；只给目录改标签无效。
   另外，在宿主预挂 loop 后到子命名空间挂载同样报 `I/O error`，而在子命名空间内
   `mount -o loop` 正常且不残留 loop 设备。运行时的实现遵循这些实测结论。

## 10. 尚未验证（需要内核补丁）

- PID 命名空间可用后，容器 init 是否如预期成为真正的 PID 1。
- IPC / USER 命名空间对 systemd 的实际支持程度。

这两项要在 `kernel/` 的自编译内核 `fastboot boot` 之后才能验证。
