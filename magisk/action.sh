#!/system/bin/sh
# SPDX-License-Identifier: GPL-2.0-only
# Magisk Action 按钮：切换「开机自动启动容器」。
# 列表与 App 共用 /data/local/linbox/autostart，避免按钮和 App 各写一份、互相覆盖。
MODDIR=${0%/*}
STATE=/data/adb/k60_linbox
LIST=/data/local/linbox/autostart
mkdir -p "$STATE" /data/local/linbox 2>/dev/null

# 清掉旧版模块目录里的列表，防止两套列表并存时“关不掉”。
rm -f "$MODDIR/autostart" 2>/dev/null

if [ -s "$LIST" ]; then
    rm -f "$LIST"
    printf '%s\n' "下一次开机：关闭容器自动启动"
else
    for f in /data/local/linbox/containers/*/config; do
        [ -f "$f" ] || continue
        printf '%s\n' "$(basename "$(dirname "$f")")"
    done > "$LIST"
    if [ ! -s "$LIST" ]; then
        rm -f "$LIST"
        printf '%s\n' "没有已创建的容器，先运行 linbox create"
        exit 0
    fi
    rm -f "$STATE/fail_count" "$STATE/boot_ok" "$MODDIR/disable"
    printf '%s\n' "下一次开机：自动启动以下容器（已重置失败计数）"
    cat "$LIST"
fi
