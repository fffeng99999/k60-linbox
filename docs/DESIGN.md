# 设计说明（k60-linbox）

## 1. 目标与边界

目标：在 Android 上给出**最接近虚拟机**的 Linux 使用体验，且**不使用软件模拟**。

- 位于允许集合内：指令原生执行（无 TCG 逐条翻译）。
- 明确放弃：独立内核。同一个 EL1 内核里无法再长出第二个独立内核，要做到那点只能
  走 hypervisor（Gunyah）或 kexec，都不在本项目内。

因此本项目把「VM 体验」拆成可独立满足的维度，用内核自带的机制逐一实现。

## 2. 为什么是系统容器而不是 proot / 普通 chroot

| 方案 | 独立 rootfs | 独立网络 | 独立 PID 1 | 隔离强度 | 定位 |
|---|---|---|---|---|---|
| proot | ✅ | ❌ | ❌ | 弱（ptrace 拦截） | 免 root 玩具级 |
| chroot | ✅ | ❌ | ❌ | 弱（共享全部命名空间） | 换 rootfs |
| **系统容器（本项目）** | ✅ | ✅ | ✅（需补丁） | 强（命名空间） | 近 VM |

## 3. 架构

```text
su -c 'linbox start demo'
        │
        ├─ ensure_bridge          创建网桥 lbx0(172.30.0.1/24) + iptables NAT
        ├─ setup_net              veth 对；容器端 veth 移入 netns "linbox-demo"
        ├─ setup_cgroup           创建 /sys/fs/cgroup/linbox_demo，写 memory.max
        └─ ip netns exec linbox-demo \
              unshare -m -u [-p -f] -- linbox __enter demo &
                                        │
                                        ├─ chcon 镜像为 shell_data_file
                                        ├─ mount -o loop <disk.img> <mnt>（ext4）
                                        ├─ mount proc/sys/devtmpfs/devpts（本命名空间内）
                                        ├─ hostname <配置主机名>
                                        └─ exec chroot <mnt> <INIT>   ← 发行版 init
```

关键点：

- **每容器一张 ext4 磁盘镜像**：`create` 用 `mke2fs -t ext4 -F -d <rootfs目录>` 直接把
  发行版根文件灌进镜像；`start` 时在本命名空间内用 `mount -o loop` 挂载。
  这既有「磁盘」语义（贴近 VM），又避开了两个设备实测到的坑（见 §4）。
- **命名空间**：`ip netns exec` 提供网络命名空间，`unshare -m -u` 提供挂载与主机名，
  `-p -f` 提供 PID 命名空间（打补丁后可用）。
- **网络**：veth 一端在宿主网桥、一端在容器 netns，容器自带 `172.30.0.<n>`，
  经宿主机 `MASQUERADE` 出网。设备 `ip_forward` 已为 1。
- **cgroup**：v2 目录 + `memory.max`；把启动进程写入 `cgroup.procs`，子进程随 fork
  继承，从而覆盖容器内所有进程；停止时也以该 cgroup 成员为准逐个回收。
- **生命周期**：宿主侧记录启动进程 pid 判定存活；停止 = 结束容器进程 + 拆
  netns/veth/cgroup。挂载随命名空间销毁自动清理。

## 4. 设备实测发现（决定了上面的设计）

1. **overlayfs 不可用**：设备 `/data` 为 f2fs，内核日志报
   `overlayfs: filesystem on '...upper' not supported`，故放弃 overlay 方案，
   改用 ext4 镜像。
2. **子命名空间内挂载 loop 需要文件标签**：镜像文件若带 `system_data_file`
   标签，`mount -o loop` 会返回 `I/O error`；`chcon u:object_r:shell_data_file:s0`
   后正常。仅给目录改标签无效，必须改文件本身。
3. **不要预挂 loop 再跨命名空间挂载**：在宿主 `losetup` 后到子命名空间挂载会报
   `I/O error`；在本命名空间内 `mount -o loop` 则正常，且 loop 随挂载自动释放，
   不会残留。运行时因此在 `__enter` 内部挂载。

## 5. 与 VM 的差距（明确告知）

1. **没有独立内核**：`uname` / `dmesg` / 内核模块共享 Android 内核。
2. Android 内核崩则容器一起崩。
3. 无法跑嵌套内核（不能在里面再开一台 VM）。
4. cgroup v2 只开了 `memory` 控制器；cpu/cpuset/blkio 仍是 Android 的 v1 布局，
   项目不做改动以免破坏 Android。

## 6. 安全取舍

- 打开 `CONFIG_USER_NS` 会略微放宽内核攻击面。本项目认为这是为换取「近 VM 隔离」
  而可接受的最小代价，且在 `kernel/README.md` 中显著标注。
- 不改 Android 的 cgroup 布局、不替换厂商模块、不改 SELinux 策略；只对**自己创建的
  镜像文件**做 `chcon`，以满足 loop 挂载的必要标签。
- 容器 `/dev` 用**独立 tmpfs + mknod 最小设备集 + devpts**，不 bind 宿主 `/dev`。
- 所有容器数据落在 `/data/local/linbox`，与用户数据目录分离。

## 7. 未做与可扩展

- rootfs 在线引导（debootstrap/pacstrap）未内建，采用「自带 rootfs」模型。
- 图形输出（X11/Wayland）需配合 Termux:X11 或 VNC，不在运行时内。
- 镜像大小固定在创建时；可在停止后用 `resize2fs` 扩容（设备有 e2fsprogs）。
