#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-only
#
# 构建启用 k60-linbox 所需命名空间的 5.10.252 内核 Image。
#
# 在设备原始 config 之上只做两类改动：
#   1) 让 16 GB CI runner 能编译成功（Thin LTO、关闭 LOCALVERSION_AUTO 等）；
#   2) 打开容器所需的命名空间与 cgroup：PID_NS / IPC_NS / USER_NS /
#      SYSVIPC / CGROUP_PIDS。
# 其余厂商配置保持不变，因此厂商模块与 Gunyah 驱动继续兼容。
#
# 用法：KERNEL_SRC=/path/to/kernel OUT_DIR=/path/to/out ./scripts/build_kernel.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KERNEL_SRC="${KERNEL_SRC:-$ROOT/.work/kernel}"
OUT_DIR="${OUT_DIR:-$ROOT/out/kernel}"
TARGET_RELEASE="5.10.252-gki-g19f54b03cc87"
JOBS="${JOBS:-$(nproc)}"
MAKE_ARGS=(ARCH=arm64 LLVM=1 LLVM_IAS=1)
CONFIG_GZ="$ROOT/kernel/config/config.gz"

if [[ ! -f "$KERNEL_SRC/Makefile" ]]; then
    echo "错误：KERNEL_SRC 不是内核源码树：$KERNEL_SRC" >&2
    exit 2
fi
if ! grep -q '^SUBLEVEL = 252$' "$KERNEL_SRC/Makefile"; then
    echo "错误：内核源码不是 Linux 5.10.252" >&2
    exit 1
fi
[[ -f "$CONFIG_GZ" ]] || { echo "错误：缺少设备基线 config：$CONFIG_GZ" >&2; exit 2; }

mkdir -p "$OUT_DIR"

# 1. 用设备原始 config 起步，保留全部厂商驱动。
gzip -dc "$CONFIG_GZ" > "$KERNEL_SRC/.config"
"$KERNEL_SRC/scripts/config" --file "$KERNEL_SRC/.config" \
    --disable LOCALVERSION_AUTO \
    --set-str LOCALVERSION "-gki-g19f54b03cc87" \
    --enable MODULES \
    --enable MODVERSIONS \
    --disable MODULE_SIG \
    --enable MODULE_SCMVERSION \
    --disable LTO_CLANG_FULL \
    --enable LTO_CLANG_THIN

# 2. 打开容器所需命名空间（本项目的核心补丁）。
#
# 设备实测：单独启用 PID_NS 即可让容器获得独立 PID 1（已实测 init 自报 PID=1）
# 且内核能正常启动。但完整组合 IPC_NS + USER_NS + SYSVIPC + CGROUP_PIDS 会让该
# 内核无法启动——fastboot boot 会静默回落到已刷入的原厂内核（/proc/config.gz 与
# 原厂完全一致、无 panic 记录）。因此这里只启用 PID_NS，其余项待逐项隔离后再加。
# 详见 docs/VERIFICATION.md。
for opt in PID_NS; do
    "$KERNEL_SRC/scripts/config" --file "$KERNEL_SRC/.config" --enable "$opt"
done

printf '%s\n' 'g19f54b03cc87' > "$KERNEL_SRC/.scmversion"
export LOCALVERSION=
export KBUILD_BUILD_USER=keosh
export KBUILD_BUILD_HOST=ubuntu

make -C "$KERNEL_SRC" "${MAKE_ARGS[@]}" olddefconfig
RELEASE="$(make -s -C "$KERNEL_SRC" "${MAKE_ARGS[@]}" kernelrelease)"
if [[ "$RELEASE" != "$TARGET_RELEASE" ]]; then
    echo "错误：内核 release 为 $RELEASE，预期 $TARGET_RELEASE" >&2
    exit 1
fi

# 3. 确认命名空间确实被打开（kconfig 依赖可能把某项又关掉）。
for opt in CONFIG_PID_NS; do
    if ! grep -q "^$opt=y$" "$KERNEL_SRC/.config"; then
        echo "错误：$opt 未被启用（kconfig 依赖未满足）" >&2
        grep "^$opt" "$KERNEL_SRC/.config" >&2 || true
        exit 1
    fi
done

# 4. 一次性构建 Image（不要拆成两次 make，见 k60-gunyah 的经验）。
make -C "$KERNEL_SRC" "${MAKE_ARGS[@]}" -j"$JOBS" Image

install -m 0644 "$KERNEL_SRC/arch/arm64/boot/Image" "$OUT_DIR/Image"
install -m 0644 "$KERNEL_SRC/System.map" "$OUT_DIR/System.map"
install -m 0644 "$KERNEL_SRC/.config" "$OUT_DIR/kernel.config"
for symvers in vmlinux.symvers Module.symvers; do
    [[ -f "$KERNEL_SRC/$symvers" ]] && \
        install -m 0644 "$KERNEL_SRC/$symvers" "$OUT_DIR/$symvers"
done

echo "已生成 $OUT_DIR/Image"
file "$OUT_DIR/Image"
sha256sum "$OUT_DIR/Image"

# 5. 与设备符号表对比，确认 ABI 未被命名空间改动破坏。
if [[ -x "$ROOT/scripts/compare_symvers.py" && -f "$OUT_DIR/vmlinux.symvers" ]]; then
    python3 "$ROOT/scripts/compare_symvers.py" \
        --device "$ROOT/kernel/config/Module.symvers" \
        --built "$OUT_DIR/vmlinux.symvers" || true
fi
