#!/system/bin/sh
# SPDX-License-Identifier: GPL-2.0-only
# Magisk Action 按钮：切换「开机自动启动容器」。
MODDIR=${0%/*}
STATE=/data/adb/k60_linbox
mkdir -p "$STATE" 2>/dev/null

if [ -f "$MODDIR/autostart" ]; then
    rm -f "$MODDIR/autostart"
    printf '%s\n' "下一次开机：关闭容器自动启动"
else
    for f in /data/local/linbox/containers/*/config; do
        [ -f "$f" ] || continue
        printf '%s\n' "$(basename "$(dirname "$f")")"
    done > "$MODDIR/autostart"
    if [ ! -s "$MODDIR/autostart" ]; then
        rm -f "$MODDIR/autostart"
        printf '%s\n' "没有已创建的容器，先运行 linbox create"
        exit 0
    fi
    rm -f "$STATE/fail_count" "$STATE/boot_ok" "$MODDIR/disable"
    printf '%s\n' "下一次开机：自动启动以下容器（已重置失败计数）"
    cat "$MODDIR/autostart"
fi
