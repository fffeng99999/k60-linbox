#!/system/bin/sh
# SPDX-License-Identifier: GPL-2.0-only
# 开机后期脚本：默认什么都不做；只有存在 autostart 列表时才启动指定容器。
# 具备防 bootloop 逻辑：连续启动失败会写 disable 标记并自动停用。
MODDIR=${0%/*}
STATE=/data/adb/k60_linbox
LIST="$MODDIR/autostart"
MAX_FAILURES=2
LINBOX="$MODDIR/system/bin/linbox"
[ -x "$LINBOX" ] || LINBOX="/system/bin/linbox"

mkdir -p "$STATE" 2>/dev/null
log_msg() {
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$STATE/service.log"
}

# 上一次开机成功则清零
if [ -f "$STATE/boot_ok" ]; then
    rm -f "$STATE/boot_ok"
    printf '0\n' > "$STATE/fail_count"
fi

if [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ]; then
    log_msg "module disabled; skip autostart"
    exit 0
fi
if [ ! -f "$LIST" ]; then
    log_msg "no autostart list; nothing to do"
    exit 0
fi

failures="$(cat "$STATE/fail_count" 2>/dev/null || printf '0')"
case "$failures" in
    ''|*[!0-9]*) failures=0 ;;
esac

started=0
failed=0
while IFS= read -r name; do
    [ -n "$name" ] || continue
    case "$name" in \#*) continue ;; esac
    if "$LINBOX" start "$name" >> "$STATE/service.log" 2>&1; then
        started=$((started + 1))
    else
        failed=$((failed + 1))
        log_msg "failed to start container: $name"
    fi
done < "$LIST"

if [ "$failed" -gt 0 ]; then
    failures=$((failures + 1))
    printf '%s\n' "$failures" > "$STATE/fail_count"
    if [ "$failures" -ge "$MAX_FAILURES" ]; then
        touch "$MODDIR/disable"
        log_msg "failure count $failures reached limit; module auto-disabled"
        exit 0
    fi
fi

rm -f "$STATE/boot_ok"
(
    i=0
    while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$i" -lt 240 ]; do
        sleep 2
        i=$((i + 1))
    done
    if [ "$(getprop sys.boot_completed)" = "1" ]; then
        date '+%s' > "$STATE/boot_ok"
        printf '0\n' > "$STATE/fail_count"
        log_msg "boot completed; started=$started failed=$failed"
    else
        log_msg "boot completion timeout; stability not confirmed"
    fi
) >/dev/null 2>&1 &

log_msg "autostart done: started=$started failed=$failed"
