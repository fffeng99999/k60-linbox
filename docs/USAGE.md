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

## 9. 内核补丁（解锁独立 PID 1）

```bash
# CI：手动触发 .github/workflows/build-kernel.yml
# 本地：
KERNEL_SRC=/path/to/kernel OUT_DIR=out/kernel ./kernel/scripts/build_kernel.sh
python3 kernel/scripts/repack_boot.py \
  --stock-boot boot_a.img --kernel out/kernel/Image --output boot-linbox.img
fastboot boot boot-linbox.img      # 临时启动，不刷分区
```

启动后 `linbox caps` 中 PID 命名空间应变为 `yes`，容器 init 即成为真正的 PID 1。

## 10. 常见问题

- **镜像挂载报 I/O error**：设备 `/data` 为 f2fs，镜像文件必须带 `shell_data_file`
  标签；运行时每次启动都会自动 `chcon`。若手工移动过镜像，执行
  `chcon u:object_r:shell_data_file:s0 <镜像>` 即可修复。
- **容器内没有网络**：确认 `iptables` 可用、`ip_forward=1`（设备默认已为 1）。
  运行时会自动把 `-i/-o <网桥>` 的 ACCEPT 插到 FORWARD 链首，并按地址给
  `172.30.0.0/24` 加策略路由指向 main 表（Android netd 的 per-network 路由表
  与 fwmark 规则会把外来网桥的流量导错，详见 docs/VERIFICATION.md 第 12 节）。
- **`linbox caps` 显示 PID 命名空间 no**：属预期，打内核补丁后变为 yes。
