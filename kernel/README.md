# 内核补丁说明（k60-linbox）

k60-linbox 的「容器拥有独立 PID 1」依赖内核命名空间。设备原厂 5.10.252 内核
**没有**打开这些选项，所以要用自编译内核补上。本目录只做 **配置级补丁**：
不改一行内核源码，只翻转若干 `CONFIG_*`，因此对厂商模块 ABI 影响最小。

## 1. 需要打开的选项

| 选项 | 原厂状态 | 状态 | 作用 |
|---|---|---|---|
| `CONFIG_PID_NS` | `not set` | **已启用（实测可启动）** | 容器独立的 PID 命名空间 → 发行版 init 成为真正的 PID 1 |
| `CONFIG_IPC_NS` | `not set` | 待隔离 | System V IPC 隔离（systemd / 数据库等需要） |
| `CONFIG_USER_NS` | `not set` | 待隔离 | 用户/能力隔离 |
| `CONFIG_SYSVIPC` | `not set` | 待隔离 | IPC_NS 的前置 |
| `CONFIG_CGROUP_PIDS` | `not set` | 待隔离 | 限制容器内进程数 |
| `CONFIG_NAMESPACES` / `NET_NS` / `UTS_NS` / `CGROUPS` | 已 `=y` | — | 原厂配置已开，无需改动 |

> **设备实测（重要）**：把上面 5 项**全部**打开后编出的内核 **无法启动**——
> `fastboot boot` 报告 OKAY，但设备实际跑回已刷入的原厂内核
> （`/proc/config.gz` 与设备原厂 config 完全一致，`CONFIG_PID_NS` 仍 `not set`，
> 无 panic、无 pstore 记录）。而只启用 `PID_NS` 的内核可以正常启动。
> 因此当前 `build_kernel.sh` 只启用 `PID_NS`；其余几项需要逐项隔离后再加，
> 结论与证据见 `docs/VERIFICATION.md`。

`kernel/scripts/build_kernel.sh` 会自动把启用的选项打开，其余配置保持设备原样。

## 2. 构建

CI：`.github/workflows/build-kernel.yml`（手动触发）。
本地：

```bash
KERNEL_SRC=/path/to/kernel OUT_DIR=/path/to/out ./kernel/scripts/build_kernel.sh
```

产物：`out/kernel/{Image,System.map,kernel.config,vmlinux.symvers,Module.symvers}`。

同 k60-gunyah 的经验：设备原配置是 Full LTO，16 GB runner 会在最终链接被 OOM kill，
因此改用 Thin LTO；源码、vermagic、boot v4 参数均不变。

## 3. 组装 boot 镜像（不刷分区）

```bash
python3 kernel/scripts/repack_boot.py \
  --stock-boot /path/to/boot_a.img \
  --kernel out/kernel/Image \
  --output boot-linbox.img
```

`repack_boot.py` 只替换 boot v4 里的 kernel，保留原 ramdisk 与 header。

## 4. 验证与回滚

```powershell
# 临时启动（只从 RAM 启动，不写分区）
fastboot boot boot-linbox.img
# 回滚：重启即回到原内核；或刷回备份
fastboot flash boot_a .\k60-stock-backup\k60_stock_backup\boot_a.img
```

启动后应能看到：

```text
ls /proc/self/ns           # 出现 pid -> pid:[...]
su -c 'unshare -p -- true' # 返回 0（不再是 Invalid argument）
```

## 5. 风险

- 打开命名空间只新增代码路径，不修改既有导出符号签名，理论上不破坏厂商模块；
  但 `build_kernel.sh` 仍会用 `scripts/compare_symvers.py` 与设备符号表交叉核对。
- 打开 `USER_NS` 会略微放宽内核攻击面；这是本项目唯一的安全取舍，已在
  `docs/DESIGN.md` 中说明。
