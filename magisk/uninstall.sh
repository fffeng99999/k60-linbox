#!/system/bin/sh
# SPDX-License-Identifier: GPL-2.0-only
# 卸载：停止所有仍在运行的容器并清理状态目录。
LINBOX=/system/bin/linbox
[ -x "$LINBOX" ] || LINBOX="$MODDIR/system/bin/linbox"
for d in /data/local/linbox/containers/*; do
    [ -d "$d" ] || continue
    "$LINBOX" stop "$(basename "$d")" 2>/dev/null || true
done
rm -rf /data/adb/k60_linbox
