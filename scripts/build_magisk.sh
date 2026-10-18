#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-only
#
# 把运行时打包成 Magisk 模块：运行时 CLI 放到 system/bin，由 Magisk 覆盖挂载。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_ZIP="${1:-$ROOT/out/k60-linbox-magisk.zip}"
STAGE="$ROOT/out/magisk-stage"

[[ -x "$ROOT/runtime/bin/linbox" ]] || chmod +x "$ROOT/runtime/bin/linbox"

mkdir -p "$(dirname "$OUT_ZIP")"
OUT_ZIP="$(cd "$(dirname "$OUT_ZIP")" && pwd)/$(basename "$OUT_ZIP")"

rm -rf "$STAGE"
mkdir -p "$STAGE"
cp -a "$ROOT/magisk/." "$STAGE/"
mkdir -p "$STAGE/system/bin"
install -m 0755 "$ROOT/runtime/bin/linbox" "$STAGE/system/bin/linbox"

(
    cd "$STAGE"
    rm -f "$OUT_ZIP"
    zip -9 -r "$OUT_ZIP" . -x '*.DS_Store'
)

echo "已生成 $OUT_ZIP"
unzip -l "$OUT_ZIP"
