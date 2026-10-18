#!/system/bin/sh
# SPDX-License-Identifier: GPL-2.0-only
# 安装前做最保守的机型校验；只读取系统层信息，不读取任何用户数据。
ARCH_OK=0
case "$ARCH" in
    arm64|aarch64) ARCH_OK=1 ;;
esac
if [ "$ARCH_OK" -ne 1 ]; then
    abort "仅支持 arm64 设备"
fi

# 运行时可执行文件放到 system/bin，由 Magisk 覆盖挂载成全局命令。
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
if [ -d "$MODPATH/system/bin" ]; then
    set_perm_recursive "$MODPATH/system/bin" 0 0 0755 0755
fi
