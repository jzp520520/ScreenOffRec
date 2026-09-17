@echo off
chcp 65001 >nul
title 息屏速录 - ADB 一键授权
rem ============================================================
rem  息屏速录 ScreenOffRec - Windows 一键授权脚本
rem  作用：给 App 授予"息屏音量键长按监听"权限（仅此一次）
rem ============================================================

set "ADB=adb"
where adb >nul 2>&1
if %errorlevel%==0 goto :hasadb

if exist "C:\Android\Sdk\platform-tools\adb.exe" (
    set "ADB=C:\Android\Sdk\platform-tools\adb.exe"
    goto :hasadb
)
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
    set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
    goto :hasadb
)

echo.
echo [X] 没有找到 adb。
echo     1. 到 https://developer.android.com/tools/releases/platform-tools
echo        下载 Android 平台工具并解压；
echo     2. 把本脚本放进解压出的 platform-tools 文件夹里；
echo     3. 重新双击运行。
echo.
pause
exit /b 1

:hasadb
echo 使用 adb: %ADB%
echo.
echo [1/2] 正在检测手机连接...
"%ADB%" get-state >nul 2>&1
if %errorlevel% neq 0 goto :noconn
"%ADB%" devices | findstr /v "List" | findstr "device" >nul
if %errorlevel% neq 0 goto :noconn

echo [2/2] 正在授权（请确保手机上已安装「息屏速录」App）...
"%ADB%" shell pm grant com.screenoff.rec android.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER
if %errorlevel% neq 0 goto :fail

echo.
echo ============================================
echo  [OK] 授权成功！
echo  手机上打开「息屏速录」→ 点「开始监听」
echo  然后息屏长按 音量下=开始录音 / 音量上=停止
echo ============================================
goto :end

:noconn
echo.
echo [X] 未检测到已授权的手机，请检查：
echo     1. 手机已开启 开发者选项 - USB调试
echo     2. 数据线已连接（USB模式选"传输文件"）
echo     3. 手机弹出「是否允许USB调试」时点了 允许
echo 处理后重新运行本脚本。
goto :end

:fail
echo.
echo [X] 授权命令执行失败，常见原因：
echo     - 手机上还没安装「息屏速录」App → 先安装再运行
echo     - 设备显示 unauthorized → 手机上重新允许USB调试
echo 如仍失败，请把本窗口截图发给开发者。
goto :end

:end
echo.
pause
