#!/usr/bin/env bash
# ============================================================
#  息屏速录 ScreenOffRec - macOS / Linux 一键授权脚本
#  作用：给 App 授予"息屏音量键长按监听"权限（仅此一次）
# ============================================================

ADB="adb"
if ! command -v adb >/dev/null 2>&1; then
    for p in "$HOME/Library/Android/sdk/platform-tools/adb" "/opt/homebrew/bin/adb" "/usr/local/bin/adb"; do
        [ -x "$p" ] && ADB="$p" && break
    done
fi
if ! command -v "$ADB" >/dev/null 2>&1 && [ ! -x "$ADB" ]; then
    echo "[X] 没有找到 adb。请先安装："
    echo "    macOS : brew install android-platform-tools"
    echo "    Linux : sudo apt install adb"
    exit 1
fi

echo "[1/2] 正在检测手机连接..."
"$ADB" get-state >/dev/null 2>&1 || { echo "[X] 未检测到手机：请开启USB调试、插好数据线、允许USB调试弹窗"; exit 1; }

echo "[2/2] 正在授权（请确保手机上已安装「息屏速录」App）..."
if "$ADB" shell pm grant com.screenoff.rec android.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER; then
    echo ""
    echo "[OK] 授权成功！手机上打开「息屏速录」→ 点「开始监听」"
else
    echo "[X] 授权失败：请确认手机上已安装 App、设备已授权"
    exit 1
fi
