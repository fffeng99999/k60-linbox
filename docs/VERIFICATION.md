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
- ~~systemd 作为容器 init 的完整可用性（依赖上面几项）~~ → **已于 §15 实测可行**：
  只补 `CONFIG_PID_NS` 就够，`SYSVIPC`/`IPC_NS`/`USER_NS` 并非 systemd 启动的前提。

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

## 13. 设备自带工具的坑（2026-10-19 实测，改运行时前必看）

### 13.1 `sed` 是 toybox，不支持 GNU 的 `\?`

```text
$ sed --version
toybox 0.8.12-android (is not GNU sed 9.0)

# 同一份内容（URIs: http://ports.ubuntu.com/ubuntu-ports/）：
sed 's|https\?://ports\.ubuntu\.com/ubuntu-ports|http://X/ubuntu-ports|g'  → 不匹配（原地未变）
sed 's|https*://ports\.ubuntu\.com/ubuntu-ports|http://X/ubuntu-ports|g'  → 匹配成功
sed 's|http://ports\.ubuntu\.com/ubuntu-ports|http://X/ubuntu-ports|g'    → 匹配成功
```

即：**`\?`（可选量词）在设备上完全无效且不报错**，会静默地什么都不替换——
`linbox mirror` 一开始就是用 `https\?://...` 写的，实测「改了 2 个文件」但
`sources.list.d/ubuntu.sources` 里的 `ports.ubuntu.com` 一个都没变。
改用 `https*://`（BRE 里 `s*` 表示零个或多个 `s`）后可同时匹配 `http://` 与 `https://`。
`\.`、`.*`、`[!.]` 这类基本用法正常。

### 13.2 `busybox script` 不会因 stdin EOF 而结束

`linbox attach` 里 `script -q -c "<cmd>" /dev/null`，当它的 stdin（管道）关闭时
`script` 并不退出，容器内的交互式 shell 会一直挂着。因此 App 侧关闭终端时必须
**先往 PTY 写一个 Ctrl-D（0x04）** 让里面的 shell 自己退出，再 `destroy()` 兜底
（只 `destroy()` 作用到的是 `su`，里面的 `script`/`sh` 会被留下并占着 PTY）。

### 13.3 经 `/proc/<pid>/root` 可以直接读写运行中容器的根文件系统

实测 `echo x > /proc/<pid>/root/root/txt` 与 `cp`、`tar` 均正常，因此
`push` / `pull` / `mirror` / `ls` 在容器运行中一律走这条路，只有停止态才临时挂镜像。

## 14. 容器内基础软件安装过程中定位并修复的四个缺陷（2026-10-19 实测）

这一轮从「App 里对容器做什么都失败」出发，最终把 Ubuntu 24.04 容器从 112 个包
补到 225 个包（apt 全程可用）。四个缺陷都在 `runtime/bin/linbox`，均已修复并上机复验。

### 14.1 `run/pid` 记录的是 unshare 中间进程，不是容器 PID 1

启动链实测为
`(后台子 shell) → ip netns exec 的 fork → unshare(-p -f) → chroot 后的 init`，
原实现只取 `pgrep -P <子 shell>` 的第一层子进程，拿到的是 **unshare**：

```text
$ ps -A -o PID,PPID,ARGS | grep -E 'unshare|init'
17810     1 sh /system/bin/linbox start ubuntu24
17811 17810 unshare -m -u -p -f -- /system/bin/linbox __enter ubuntu24
17815 17811 sh /init                      ← 真正的容器 PID 1
$ readlink /proc/17811/root   → /        ← 记录错了：unshare 的 root 还是宿主 /
$ readlink /proc/17815/root   → /data/local/linbox/containers/ubuntu24/mnt
```

后果：`exec`/`shell`/`attach`/`status` 全部打在 unshare 上——`exec` 实际落在
**Android 根目录**（表现为 `apt-get: inaccessible or not found`、没有 `/etc/os-release`），
`stop` 也收不掉容器，残余 init 变 PPID=1 的孤儿。
修法：沿子进程链记录祖先，取链上**第一个 root 不再是宿主 `/`** 的进程。
注意不能写成「见到子进程就下钻、root 一变就停」——init 在 chroot 前会先 fork 出子进程
（如 `sleep`），竞态下会误把 init 的子进程当成 PID 1（实测记录成了 `sleep` 的 PID）。

### 14.2 `stop` / `destroy` 收不掉两类残留

除 14.1 的孤儿 init 外，`linbox attach` 起的 `nsenter` + `busybox script` 在客户端
被强杀（App 崩溃/被杀、`timeout` 打断）后会留下，且**它们持有容器的挂载命名空间引用**，
于是 `stop` 之后 `losetup -a` 里堆着同一个 `disk.img` 的多个 loop（实测堆到 5 个）。
修法：`kill_stragglers()` 双判据清理——`/proc/<pid>/root` 指向容器挂载点，
**或** `/proc/<pid>/ns/mnt` 与容器 init 相同。复验：stop 后 loop=0、cgroup.procs 为空、
容器根下无残留进程。

### 14.3 `linbox start` 会挂住调用方（PTY 被容器长期占用）

容器要一直跑，而 `unshare` / 容器进程 / 后台子 shell 都会继承调用方的
stdin/stdout。从 `adb shell`（或任何带 PTY 的终端）里执行 `start` 后，
调用方读不到 EOF 就一直挂着（实测卡满 2 分钟以上）。
修法：后台子 shell 一开头就 `exec </dev/null >> console.log 2>&1`，整条链都不再持有 PTY。
复验：`start` 4 秒内返回。

### 14.4 `mknod` 出的设备节点是 0660，非 root 沙箱用户打不开 `/dev/null`

`cmd_enter` 里 `mknod` 默认给出 `crw-rw---- root:root`。apt 会以沙箱用户 `_apt`
打开 `/dev/null`，非 root 打不开时 dash 报
`/usr/bin/apt-key: 95: cannot create /dev/null: Permission denied`，
连带 `>/dev/null` 重定向失败的 `command -v gpgv` 判断也失败，最终表现为
**「gpgv … seems not installed」而拒绝验签、装不了任何包**（网络其实是通的：
`apt-get update` 能拉到 InRelease）。
修法：设备节点补成标准权限（`null/zero/full/random/urandom/tty` → 0666，`console` → 0600），
并补 `/dev/shm`（tmpfs）、`/dev/fd`、`/dev/stdin|stdout|stderr` 软链。
复验：apt-get update rc=0，装包成功。

### 14.5 附：镜像站可用性（环境相关，非代码缺陷）

同一台设备上：`mirrors.tuna.tsinghua.edu.cn` 的 `dists/` 正常、`pool/` 下的 `.deb`
一律 **403 Forbidden**（设备侧解析到 198.18.x.x，走的是本机代理出口）；
`mirrors.aliyun.com` 与 `ports.ubuntu.com` 同环境 200。
App 的默认镜像站因此由 tuna 改为 aliyun（仍可在设置里切换）。

### 14.6 容器崩溃退出会挡住下一次 start（`Cannot create namespace file ...: File exists`）

若上一次不是走 `stop`，而是容器自己退出（例如 `/init` 缺失/不可执行），
`/mnt/run/linbox-<名>` 这个 netns 文件会残留，且**不出现在 `ip netns list` 里**，
下次 `start` 直接报：

```text
Cannot create namespace file "/mnt/run/linbox-ubuntu24": File exists
[linbox] 错误(NET_NS): 无法创建网络命名空间 linbox-ubuntu24
```

修法（两层）：`cmd_start` 在启动前先把残留进程/网络命名空间/cgroup 回收一遍；
`setup_net` 里 `ip netns add` 失败时先 `ip netns del` + 删半截 veth 再重试一次。
复验：把 `/init` 换成不可执行文件制造崩溃，再 `start` 一次成功。

### 14.7 `linbox exec <名> -- <命令>` 的参数是「拼接后整体交给 /bin/sh -c」

`cmd_exec` 会把 `--` 之后的所有参数用空格拼成一个字符串，再作为**一个** `sh -c` 的参数执行。
所以像 `linbox exec demo -- /bin/sh -c "a; b"` 这种写法会变成「再套一层 sh -c」，
引号被二次解析后语义跑偏（实测 `mkdir -p /run/sshd` 被拆成 `mkdir` + 丢失 `-p`，
`ss -lntp | grep :22` 静默无输出，容易误判成「sshd 没在监听」）。
正确用法是把整条命令作为**一个**参数传：`linbox exec demo -- 'a; b'`，
或干脆把脚本 push 进容器再 `linbox exec demo -- /bin/sh /root/x.sh`。

## 15. systemd 作为容器 init 实测可行（2026-10-19，推翻 §1/§6/§11 的悲观假设）

§1/§6 曾推断「systemd 场景需要 `IPC_NS`/`USER_NS`/`SYSVIPC`」，§11 把「systemd 作为容器 init 的
完整可用性」列为未验证。实际做法是：容器内 `apt-get install -y systemd systemd-sysv`，
再把容器 `--init` 改成 `/sbin/init`，其余不改。

实测结果（Ubuntu 24.04 容器，内核仍只补了 `CONFIG_PID_NS=y`）：

```text
/proc/1/cmdline            /sbin/init                        ← systemd 255 就是 PID 1
systemctl is-system-running running
systemctl --failed          0 loaded units listed
运行中的服务                dbus.service / ssh.service（socket 激活） /
                           systemd-logind.service / systemd-journald.service
systemd-detect-virt         vm-other
/proc/1/cgroup              0::/linbox_ubuntu24               ← 子 cgroup 全在本容器之下
宿主 /sys/fs/cgroup/linbox_ubuntu24  cgroup.procs=2、memory.current≈7MB、memory.max 生效
宿主 cgroup.subtree_control 仍只有 memory（未被 systemd 改动），宿主布局无新增顶层目录
```

即：**只补 `PID_NS` 就足以让 systemd 正常当 PID 1**；它并不需要 `SYSVIPC`/`IPC_NS`/`USER_NS`
才能启动。默认状态下 `systemd` 会自己挂 cgroup2（`/sys/fs/cgroup/unified`）并复用 Android 已有的
v1 控制器，故自标 `tainted: cgroupsv1`——不影响使用。

过程中修掉的一个真实回归与三个已知限制：

1. **主机名被改掉**：systemd 按 `/etc/hostname` 重设主机名，把运行时设的 UTS 名字
   （`ubuntu24`）覆盖成镜像默认的 `localhost.localdomain`。修法：`cmd_enter` 在 chroot 前
   按配置重写 `$_mnt/etc/hostname`，脚本 init 与 systemd 两种情况下名字一致。
2. `systemd-detect-virt` = `vm-other`（不是容器），于是 systemd 会为不存在的 `hvc0`
   起 getty 并等 90 秒超时。屏蔽 `serial-getty@hvc0.service` 后重启即直接 `running`。
3. 无 udev → `dev-*.device` 单元长期 `tentative`（不阻塞 `is-system-running`）。
4. 无 `SYSVIPC`/`POSIX_MQUEUE`/`USER_NS`/`CGROUP_PIDS` → 依赖 SysV IPC/POSIX mqueue/用户
   命名空间的服务会失败；systemd 本身不需要。

容器停止后仍然零残留：loop=0、`linbox_<名>` cgroup 已删除、无容器 root 的残留进程。

## 16. 「容器启动不了」：netns 残骸与并发启动（2026-10-19 实测）

现象：容器一启动就以 rc=1 退出，`linbox log <名>` 只有一行

```text
setting the network namespace "linbox-ubuntu24" failed: Invalid argument
```

### 16.1 根因：`/mnt/run/linbox-<名>` 成了残骸

该文件还在，但已经不是有效的 netns 挂载（`ip netns del` 只完成了 unmount 而没删掉文件，
或并发启动互相删掉对方刚建好的命名空间）。关键点是：**`ip netns list` 仍会把它列出来**，
所以「列表里有名字就跳过创建」的旧逻辑会直接复用它，`setns` 随即报 `Invalid argument`。

100% 稳定复现：

```text
$ touch /mnt/run/linbox-ubuntu24
$ ip netns exec linbox-ubuntu24 true
setting the network namespace "linbox-ubuntu24" failed: Invalid argument   (rc=1)
```

### 16.2 修法一：用实探测代替「列表里有名字」

`setup_net` 改成先 `ip netns exec <ns> true` 探测能不能真进去；进不去就
`ip netns del`（删不掉时 `umount` + `rm -f` 兜底）重建，并顺带校验 veth 对端确实在命名
空间里（`ip netns exec <ns> ip link show <veth>c`），地址/默认路由改为幂等 `replace`。
复验：造出残骸文件后 `linbox start` 直接成功（rc=0，容器 running）。

### 16.3 修法二：start/stop/destroy 串行化

同一秒内两次 `start` 会互相删掉对方刚建的命名空间——实测出现**两个实例、两个 loop 设备**：

```text
$ linbox stop ubuntu24
$ linbox start ubuntu24 & linbox start ubuntu24
[两个都打印「已启动」]   status → pid=139159   losetup -a → 2 个同镜像 loop
```

改用 `mkdir` 原子锁（`/data/local/linbox/lock/<名>`，锁内记持有者 pid，持有者已退出就自动清）。
**锁必须在「是否已在运行」检查之前拿**：先查后锁时两个进程都会在对方写好 `run/pid` 之前
通过检查。复验：并发两次 `start` → 一个成功、另一个报
`错误(ALREADY_RUNNING): 容器已在运行`；loops=1。

### 16.4 附带：systemd 容器的 cgroup 目录残留

容器里跑 systemd 会建 `linbox_<名>/init.scope`、`system.slice/*.service` 等子目录，
`rmdir` 因「非空」失败 → 目录留在宿主上（进程已清空，属外观残留）。`teardown_cgroup`
改为先把进程挪回根、再自底向上清 4 轮空目录。复验：stop 后 `cgroup=0`。

## 17. 设备的 sh 整数是 32 位：扩容被误判成「缩容」（2026-10-19 实测）

现象：当前 1G 的容器执行 `linbox resize <名> --size 3G` 报

```text
[linbox] 当前镜像 1073741824 字节，目标 3G
[linbox] 错误(RESIZE_SHRINK_UNSUPPORTED): 不支持缩容
```

镜像被回滚，扩容失败；但 3G → 45G 却「成功」了（所以 `SIZE=45G` 一直在）。

根因：**Android 的 sh 整数只有 32 位**：

```text
$ A=3221225472; B=1073741824          # 3GiB / 1GiB
$ echo $((A + 1))                     → -1073741823     （不是 3221225473）
$ [ "$A" -lt "$B" ]                   → TRUE            （3G 溢出成负数 → 「3G < 1G」成立）
$ awk -v a=$A -v b=$B 'BEGIN{print (a<b)}'  → 0          （awk 双精度，结论正确）
```

于是「目标 > 当前」的判断在**任一侧 ≥ 2GiB** 时都不可靠：
`1G→3G` 时 3G 溢出为负 → 被判成缩容而拒绝；`3G→45G` 时两边都溢出
（45G ≡ +1G、3G ≡ −1G）→ 比较恰好为假 → 侥幸通过。

修法：字节数一律交给 awk 换算与比较（双精度，1e13 以内精确）：

- 新增 `size_to_bytes()`（`8G`/`512M`/`1048576` → 字节）与 `num_lt()`（awk 版比较）；
- `cmd_resize` 的「缩容判断」「truncate」「truncate 是否生效」全部改用换算后的字节数，
  不再用 `[ -lt ]`。

复验：`1G→3G`、`3G→5G`（跨 2GiB）都 rc=0，且容器内 `df` 随之变大（976M → 3.0G → 4.9G）；
`5G→1G` 被正确拒绝且镜像不变；`--size abc` 报 `BAD_ARGS`。

**命名坑**：本运行时的变量全是全局的，`has_flag` 内部会写 `_want`。新加变量若重名会被它
悄悄覆盖——这次就把 `_want` 覆盖成 `--dry-run`，错误信息里出现「请求 --dry-run 字节」。
新增变量名要避开 `_want`（已改名 `_want_bytes`）。





