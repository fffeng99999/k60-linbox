#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-only
#
# 设备运行时能力自检（只读）。只使用 getprop / uname / /proc / /sys / /dev 与
# iproute2 查询，不读取 /sdcard、用户 /data、应用数据，也不抓 logcat/dumpsys。
#
# 用法：ADB=/path/to/adb ./scripts/verify_device.sh
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-adb}"
REPORT="${REPORT:-$ROOT/reports/device-caps.txt}"

mkdir -p "$(dirname "$REPORT")"

sh_dev() { "$ADB" shell "$1"; }
su_dev() { "$ADB" shell "su -c '$1'"; }

exec > >(tee "$REPORT") 2>&1

echo "k60-linbox 设备能力报告"
echo "生成时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo

echo "== 设备 =="
sh_dev 'getprop ro.product.device; getprop ro.soc.model; getprop ro.build.version.release' | tr -d '\r'
sh_dev 'uname -r' | tr -d '\r'
echo "selinux: $(sh_dev 'getenforce' | tr -d '\r')"
echo "root:    $(su_dev 'id' | tr -d '\r')"
echo

echo "== 命名空间（/proc/self/ns）=="
sh_dev 'ls /proc/self/ns' | tr -d '\r'
echo "unshare -p : $(su_dev 'unshare -p -- true && echo supported || echo missing' | tr -d '\r')"
echo "unshare -m : $(su_dev 'unshare -m -- true && echo supported || echo missing' | tr -d '\r')"
echo "unshare -n : $(su_dev 'unshare -n -- true && echo supported || echo missing' | tr -d '\r')"
echo "unshare -u : $(su_dev 'unshare -u -- true && echo supported || echo missing' | tr -d '\r')"
echo

echo "== cgroup =="
sh_dev 'grep cgroup /proc/mounts' | tr -d '\r'
echo "v2 controllers: $(su_dev 'cat /sys/fs/cgroup/cgroup.controllers' | tr -d '\r')"
echo "v2 subtree:     $(su_dev 'cat /sys/fs/cgroup/cgroup.subtree_control' | tr -d '\r')"
echo

echo "== 文件系统与网络 =="
su_dev "grep -E 'overlay|ext4|veth' /proc/filesystems" | tr -d '\r'
echo "bridge/bridge-nf: $(su_dev 'ip link show type bridge | head -3' | tr -d '\r')"
echo "ip_forward:       $(su_dev 'cat /proc/sys/net/ipv4/ip_forward' | tr -d '\r')"
echo "iptables:         $(sh_dev 'which iptables' | tr -d '\r')"
echo "losetup -f:       $(su_dev 'losetup -f' | tr -d '\r')"
echo

echo "== 工具链 =="
sh_dev 'which unshare nsenter ip chroot mount losetup busybox tar gzip' | tr -d '\r'
echo
echo "报告已写入 $REPORT"
