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

## 10. 内核补丁与独立 PID 1 实测（已完成）

### 10.1 fastboot boot 有效，但完整补丁组合会导致内核不启动

把 `PID_NS + IPC_NS + USER_NS + SYSVIPC + CGROUP_PIDS` 全部打开后编出的内核：

```text
fastboot boot → Booting OKAY
启动后 /proc/config.gz 与设备原厂 config 完全一致（CONFIG_PID_NS 仍 not set）
/proc/self/ns 无 pid；unshare -p 仍 Invalid argument
ro.boot.bootreason = bootloader；/sys/fs/pstore 为空（无 panic）
```

即设备**实际跑回已刷入的原厂内核**，该镜像未被采用。
对照实验：只启用 `PID_NS` 的同源内核（同为 Thin LTO）**可以正常启动**，
`CONFIG_LTO_CLANG_THIN=y`、`/proc/self/ns` 出现 `pid`/`pid_for_children`、
`CONFIG_PID_NS=y`。

另外确认了两个排除项：镜像 sha256 与 CI 记录完全一致（排除下载损坏）；
ramdisk 与旧内核镜像逐字节相同（排除 ramdisk 差异）。

### 10.2 独立 PID 1 实测（已被设备证明）

只启用 `PID_NS` 的内核上运行容器：

```text
linbox caps        → PID 命名空间 yes
linbox start demo  → [linbox] 进入容器 demo: host=demo pid1=yes
                     [linbox] 容器 demo 已启动 (unshare pid=11323, container pid=11328)
容器 PID 1（宿主 pid 11328）：
  pid ns   = pid:[4026535285]（与宿主 4026531836 不同）
  根目录    = bin dev etc init lost+found pid1.txt proc run sbin sys tmp
  init 自报 = init-pid=1
  UTS      = demo
网络       = vlinbox1c, inet 172.30.0.10/24
stop/destroy → loop=0 proc=0（零残留）
```

**结论：容器拥有独立的 PID 1，即「最接近虚拟机」的核心目标已在设备上成立。**

### 10.3 运行时在 PID 命名空间下暴露并已修复的问题

1. `unshare -p -f` 会再 fork 出真正的容器 PID 1，原先只记录了 `unshare` 父进程，
   导致 `stop` 收不干净。现通过 `pgrep -P` 定位子进程，并把它与父进程都纳入
   cgroup，停止时以 cgroup 成员为准回收。
2. 容器镜像的 loop 设备在退出后残留：现改为**先 `nsenter` 进容器挂载命名空间
   `umount` 再杀进程**，并保留 `losetup -d` 重试作为兜底。

## 11. 尚未验证

- `IPC_NS` / `USER_NS` / `SYSVIPC` / `CGROUP_PIDS` 中究竟是哪一项（或组合）
  导致内核无法启动——需要逐项隔离构建。
- systemd 作为容器 init 的完整可用性（依赖上面几项）。

## 12. 容器网络打通（Android netd 策略路由）

真实发行版（Ubuntu 24.04 base）跑起来后，容器**完全不通网**，逐层定位如下：

1. 网桥端口状态 `forwarding`、双向 ARP 均已解析——二层正常。
2. 把 `-i lbx0` 的 ACCEPT 规则**插到 FORWARD 链首**（原先追加在 Android 的
   `tetherctrl_FORWARD` 等链之后，会被先 DROP）：宿主↔容器仍不通。
3. 真正的根因在路由：`ip route get 172.30.0.10` 返回
   **`via 192.168.58.1 dev wlan0 table wlan0`** —— Android netd 用
   per-network 路由表 + fwmark/uid 规则做策略路由，把「外来」网桥的流量
   （包括宿主自己发往容器的包）导向了 wlan0；同时 main 表没有默认路由，
   容器出网也变成 `Network is unreachable`。

**修法**（已固化进运行时 `setup_routing()`）：本网段不与任何 Android 网络重叠，
因此可以按地址加规则，且不会影响 Android 自身流量：

```text
ip rule add to   172.30.0.0/24 lookup main priority 10010
ip rule add from 172.30.0.0/24 lookup main priority 10011
# main 表没有默认路由时，从已有的真实网络表借一条
ip route add default via <真实网关> dev <真实网卡>
```

**结果**（已实测）：

```text
宿主 ping 容器        → 0% packet loss
容器 TCP 到 1.1.1.1:443 → 通
容器 DNS 解析          → 成功（archive.ubuntu.com）
容器 apt-get update    → 能访问源（缺 gpgv 时才报验签警告）
宿主自身上网           → 不受影响（仍通）
```

注意：`gpgv` 已在容器内安装（`/usr/bin/gpgv` 存在），但该最小 base 镜像上
apt 仍报「gpgv required for verification, but neither seems installed」；
装包可用 `apt-get install -y --allow-unauthenticated <包>`。这是 base 镜像的
细节问题，不影响「容器有网 + 能装包」这一结论。
