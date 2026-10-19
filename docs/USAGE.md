# 使用说明（k60-linbox）

## 0. 前置

- Root（Magisk）可用；`su -c id` 返回 `uid=0(root)`。
- 运行时用 `su -c 'linbox ...'` 调用（脚本也会在非 root 时自动 `su` 一次）。
- 若要让容器拥有独立 PID 1：先按 `kernel/README.md` 构建并 `fastboot boot` 补丁内核。

## 1. 准备 rootfs

项目采用「自带根文件系统」模型：`create` 会用 `mke2fs -d` 把你提供的目录灌成一张
ext4 磁盘镜像，之后容器就运行在这张镜像上。任选一个 Linux 发行版的根文件系统目录，例如：

```sh
# 在设备上准备目录（示例路径，可自行更换）
mkdir -p /data/local/rootfs
# 把发行版 rootfs（tar 或目录）放到 /data/local/rootfs/debian
```

> 也可在开发机上解包发行版 rootfs tarball，再 `adb push` 到设备。

## 2. 安装运行时

方式 A（推荐）：安装 CI 产物 Magisk 模块 `k60-linbox-magisk.zip`：

```sh
magisk --install-module k60-linbox-magisk.zip
# 重启后 /system/bin/linbox 可用
```

方式 B：手动推送脚本：

```sh
adb push runtime/bin/linbox /data/local/tmp/linbox
adb shell su -c 'cp /data/local/tmp/linbox /data/local/bin/linbox; chmod 0755 /data/local/bin/linbox'
```

## 3. 能力自检

```sh
su -c 'linbox caps'
```

输出会明确告诉你 PID 命名空间是否可用（决定容器是否有独立 PID 1）。

## 4. 登记 base 与创建容器

```sh
su -c 'linbox base add debian /data/local/rootfs/debian'
su -c 'linbox base list'

su -c 'linbox create demo --base debian --host demo --mem 2048'
su -c 'linbox list'
```

`create` 参数：

| 参数 | 说明 | 默认 |
|---|---|---|
| `--base 名` | 必填，引用已登记的 base | — |
| `--host 名` | 容器主机名（UTS） | `linbox-<容器名>` |
| `--ip 地址` | 容器 IP | 自动从 `172.30.0.0/24` 分配 |
| `--mem 兆` | 内存上限（cgroup v2） | `2048` |
| `--size 大小` | 磁盘镜像大小（`truncate` 语法，如 `4G`） | `4G` |
| `--init 路径` | 容器内 init 路径（相对 rootfs） | `/sbin/init` |

## 5. 启停与进入

```sh
su -c 'linbox start demo'
su -c 'linbox status demo'
su -c 'linbox shell demo'      # 进入容器交互 shell，exit 退出
su -c 'linbox stop demo'
su -c 'linbox restart demo'
```

容器启动后，它拥有独立 IP（`ip netns exec linbox-demo ip addr`），从宿主机可直接
访问；容器经 NAT 出网。

## 6. 快照与恢复

```sh
su -c 'linbox stop demo'
su -c 'linbox snapshot demo before-upgrade'
su -c 'linbox restore demo before-upgrade'   # 恢复前需先停止
```

快照 = 复制容器的 ext4 磁盘镜像；恢复即把镜像写回。两者都要求容器先停止，
以保证镜像一致。

## 7. 开机自动启动（可选）

安装 Magisk 模块后，点 Action 按钮会在 `autostart` 与关闭之间切换。
`service.sh` 具备防 bootloop：连续两次启动失败会自动写 `disable` 并停用。

## 8. 清理

```sh
su -c 'linbox destroy demo'
```

## 9. SSH 登录容器（可选）

容器里装好 `openssh-server` 后，还要（1）让 sshd 被拉起来，（2）用运行时的端口映射把它暴露出来。

- **用 systemd 当 init**（见 §10）：发行版默认已启用 `ssh.socket`，开容器即自动监听，不用做任何事。
- **用脚本 `/init`**：把下面几行加进容器 `/init`：

```sh
mkdir -p /run/sshd
[ -f /etc/ssh/ssh_host_ed25519_key ] || ssh-keygen -A
[ -x /usr/sbin/sshd ] && /usr/sbin/sshd
```

> 坑：`linbox push` 是 `cp -a`，权限沿用源文件；而经 `adb push` 传上去的文件是 0644，
> 直接推成 `/init` 会让容器「启动后立即退出」。先在宿主侧 `chmod 0755` 再 push。

开放 root 登录（容器内执行）：

```sh
mkdir -p /etc/ssh/sshd_config.d
printf 'PermitRootLogin yes\nPasswordAuthentication yes\n' > /etc/ssh/sshd_config.d/10-linbox.conf
echo 'root:你的密码' | chpasswd
mkdir -p /root/.ssh && chmod 700 /root/.ssh   # 想用密钥就再写入 authorized_keys(0600)
su -c 'linbox port add demo tcp 8022 22'
```

从同一局域网的电脑连接（手机 IP 见「设置→关于手机→状态信息」，或 `ip -4 addr show wlan0`）：

```sh
ssh -p 8022 root@<手机IP>
```

- `手机IP:8022` 由 DNAT 转到容器 `172.30.0.10:22`；网络切换后 netd 会重刷防火墙，
  失效时用 `su -c 'linbox port sync demo'` 重放映射。
- 在手机本机上连 `8022` 无效（DNAT 只在 PREROUTING 生效，本机流量不经过它），
  手机内部直接连 `172.30.0.10`（Android 自带没有 ssh 客户端，用 App 的终端或第三方客户端）。
- 推荐改用密钥登录：公钥写进容器 `/root/.ssh/authorized_keys` 后，
  把 `10-linbox.conf` 里的 `PasswordAuthentication yes` 去掉。

## 10. 用发行版自带 init（systemd）

默认 init 是极简脚本（`/init`）；也可以让容器跑发行版真正的 init：

```sh
su -c 'linbox exec demo -- /bin/sh -c "apt-get install -y systemd systemd-sysv"'
su -c 'linbox stop demo'
su -c 'linbox set demo --init /sbin/init'
su -c 'linbox start demo'
```

实测（K60，内核只补了 `PID_NS`）：**systemd 255 能正常起来**——
`systemctl is-system-running` = `running`、0 个 failed 单元，
`ssh`（socket 激活）、`dbus`、`systemd-logind`、`systemd-journald` 都在运行；
内存限额仍生效（systemd 建的子 cgroup 都落在运行时的 `linbox_<名>` 之下）。

已知限制（内核层面，非 systemd 版本问题）：

| 限制 | 影响 |
|---|---|
| 内核无 `CONFIG_SYSVIPC` / `POSIX_MQUEUE` | 用到 SysV IPC / POSIX 消息队列的服务会失败（如部分数据库）；systemd 自身不需要 |
| 内核无 `USER_NS` / `CGROUP_PIDS` | `unshare -U`、`TasksMax` 等不可用 |
| cgroup 为混合模式（Android 的 cpu/blkio 仍在 v1） | systemd 自标 `tainted: cgroupsv1`，不影响使用 |
| 没有 udev | `dev-*.device` 单元长期停在 `tentative`，不影响 `is-system-running` |
| systemd 把本机识别为虚拟机（`vm-other`） | 会为不存在的 `hvc0` 起 getty 并卡 90 秒；`systemctl mask serial-getty@hvc0.service` 一次即可 |

容器重启后 systemd 的 `/etc/hostname` 会覆盖运行时设的 UTS 主机名，
运行时会在每次进入前按配置重写 `/etc/hostname`，两边名字保持一致。

回退到脚本 init：`su -c 'linbox set demo --init /init'`（原 `/init` 仍在镜像里）。

## 11. 内核补丁（解锁独立 PID 1）

```bash
# CI：手动触发 .github/workflows/build-kernel.yml
# 本地：
KERNEL_SRC=/path/to/kernel OUT_DIR=out/kernel ./kernel/scripts/build_kernel.sh
python3 kernel/scripts/repack_boot.py \
  --stock-boot boot_a.img --kernel out/kernel/Image --output boot-linbox.img
fastboot boot boot-linbox.img      # 临时启动，不刷分区
```

启动后 `linbox caps` 中 PID 命名空间应变为 `yes`，容器 init 即成为真正的 PID 1。

## 12. 常见问题

- **镜像挂载报 I/O error**：设备 `/data` 为 f2fs，镜像文件必须带 `shell_data_file`
  标签；运行时每次启动都会自动 `chcon`。若手工移动过镜像，执行
  `chcon u:object_r:shell_data_file:s0 <镜像>` 即可修复。
- **容器内没有网络**：确认 `iptables` 可用、`ip_forward=1`（设备默认已为 1）。
  运行时会自动把 `-i/-o <网桥>` 的 ACCEPT 插到 FORWARD 链首，并按地址给
  `172.30.0.0/24` 加策略路由指向 main 表（Android netd 的 per-network 路由表
  与 fwmark 规则会把外来网桥的流量导错，详见 docs/VERIFICATION.md 第 12 节）。
- **`linbox caps` 显示 PID 命名空间 no**：属预期，打内核补丁后变为 yes。
- **新建的容器启动后立刻退出**：运行时默认 `--init /sbin/init`，而普通 debootstrap/归档
  的 base 里并没有 `/sbin/init`（没装 systemd）。两条路：给 base 装 systemd（见 §10），
  或创建时显式 `--init /init` 用脚本 init。`su -c 'linbox log <名>'` 会看到
  「容器启动后立即退出，请检查 rootfs 的 <init> 是否存在且可执行」这条告警。
