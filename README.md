# 息屏速录 ScreenOffRec

> **单文件 ~25KB 的安卓息屏速录工具**：免 Root，息屏状态**长按音量键**立即开始 / 停止后台录音。纯本地存储，**零联网权限**，无广告、无内购、完全开源。

| | |
|---|---|
| 📱 系统 | Android 8.0 ～ 16（实测 Android 14 / 16 均正常）|
| 📦 体积 | 约 25 KB（对，没多打一个零）|
| 🔒 权限 | 无 INTERNET 权限，录音永远不离开手机 |
| 💰 收费 | 无广告 / 无内购 / 无统计SDK，MIT 开源 |

## ✨ 功能

- 🔇 **息屏录音**：黑屏、锁屏、放口袋里，长按音量下立即开始录音，长按音量上停止
- 📳 **触觉确认**：开始 / 停止各一次震动，无需点亮屏幕
- 📳 **短按不受影响**：音量键短按仍是正常音量调节
- 🔔 **通知栏快捷键**：锁屏直接点「● 开始录音 / ■ 停止录音」（此功能不需要 ADB 授权）
- ▶️ **内置播放 / 删除**：录音列表直接试听，删除带二次确认
- 🥷 **后台隐身**：不出现在最近任务列表，无法被误划掉（通知栏常驻是系统强制要求）
- 🔄 **开机自启**：重启手机自动恢复监听（需系统自启动权限，见 FAQ）
- ⏱️ **防忘关**：可设单次最长 15 / 30 / 60 分钟自动停止

## 📥 安装（共 3 步，5 分钟）

### 第 1 步：安装 App

到 [**Releases 页面**](https://github.com/jzp520520/ScreenOffRec/releases) 下载最新的 `ScreenOffRec_vX.X.apk`，安装（需允许"未知来源"）。

### 第 2 步：ADB 授权音量键监听（唯一需要电脑的一步）

**为什么需要这一步？** "息屏时监听音量键长按"是 Android 的系统级能力，权限级别为 `signature|privileged|development`，普通 App 无权静默获取。开发者模式的一条 ADB 命令可以把它授予 App（仅此一次，重启不失效，卸载重装才需要重新执行）。

#### 手机端准备

1. 设置 → 关于手机 → 连续点击「OS 版本 / 版本号」**7 次** → 提示已进入开发者模式
2. 设置 → 更多设置 → 开发者选项 → 打开「**USB 调试**」
3. 数据线连接电脑，手机弹出「**是否允许 USB 调试？**」→ 勾选"一律允许" → **允许**

#### 电脑端：执行一条命令

```
adb shell pm grant com.screenoff.rec android.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER
```

<details>
<summary><b>🪟 Windows 新手：不会用命令行？点开看保姆级步骤</b></summary>

1. 下载 [Android 平台工具（platform-tools）](https://developer.android.com/tools/releases/platform-tools)，解压到任意目录，比如 `D:\platform-tools`
2. 把本仓库 `scripts/一键授权-windows.bat` 复制到 `platform-tools` 文件夹里
3. 双击运行，按提示操作即可。脚本会自动检测手机、自动执行授权、告诉你成功还是失败

也可以不用脚本，手动执行：在 `platform-tools` 文件夹的地址栏输入 `cmd` 回车，然后粘贴上面的授权命令回车。

</details>

<details>
<summary><b>🍎 macOS / 🐧 Linux 用户</b></summary>

```bash
# 安装 adb（任选其一）
brew install android-platform-tools      # macOS (Homebrew)
sudo apt install adb                     # Debian / Ubuntu

# 手机插上后执行
adb shell pm grant com.screenoff.rec android.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER
```
或使用仓库里的 `scripts/一键授权-mac-linux.sh`。

</details>

<details>
<summary><b>📶 没有电脑？无线调试替代方案（Android 11+）</b></summary>

手机自己就能完成 ADB 授权，全程不需要电脑：

1. 开发者选项 → 无线调试 → 开启
2. 安装一个本机 ADB 工具（如开源的 **aShell You**、**ADB OTG** 等）
3. 在该工具里执行同一条授权命令即可

</details>

### 第 3 步：开始使用

打开「息屏速录」→ 点「**开始监听**」→ 状态显示"监听中"→ 完成！

之后：**息屏 → 长按音量下 → 震一下 → 说话 → 长按音量上 → 再震一下**，录音已保存在 `手机存储/Rec/`。

## ❓ 常见问题

| 现象 | 原因 / 解决 |
|---|---|
| 长按音量键没反应 | ① ADB 命令没执行成功，重新执行；② 没给 App「通知使用权」以外的通知权限；③ App 内状态显示"❌ 未授权"= 授权没生效 |
| 点「开始监听」闪退 | 升级到 v1.2+（早期版本有通知渠道 bug）|
| 录出来是静音 | 麦克风被其他 App 占用（通话/语音/其它录音应用）。关掉占用方再录，或用 `termux-microphone-record -i` 类工具查占用 |
| 录音中途断了 | 省电策略杀后台：应用信息 → 省电策略 → **无限制**；HyperOS/MIUI 再开「自启动」 |
| 重启手机后监听没了 | ① 需开启系统「自启动」开关（应用信息页）；② **Android 14+** 系统限制：开机后需打开一次 App 再点「开始监听」 |
| 状态栏一直有个通知 | Android 对前台服务的强制要求，无法隐藏（录音时还会出现麦克风隐私点），这是系统的隐私设计 |
| 双击音量键触发可以吗 | 不支持。系统隐藏接口只提供"长按"事件（App 内可把"长按音量上/下"互换）|
| 会录音失败吗？麦克风冲突 | 麦克风被占时启动录音会失败并震动提示 + 通知提示，不会崩溃，也不会录出坏文件 |

## 🔒 隐私与安全

- 本 App **没有申请 INTERNET 权限**——这在技术上保证了它不可能把录音传到任何地方
- 不需要的话可自行验证：`aapt2 dump badging ScreenOffRec.apk` 输出中没有 `android.permission.INTERNET`
- 无任何统计 / 崩溃上报 / 广告 SDK

## ⚖️ 法律与使用边界

请仅录制**你本人参与的对话**（如会议、访谈、维权取证）。偷录无关第三方的私人谈话可能违反当地法律，由此产生的后果与本项目无关。

## 🛠️ 从源码构建

无需 Gradle，一条脚本搞定（Windows）：

```powershell
# 需要：JDK 17 + Android SDK（platforms;android-30 + build-tools;30.0.3）
.\scripts\build-windows.ps1
```

手动构建流程：`aapt2 compile/link → javac → d8 → zipalign → apksigner`，详见脚本。

## 🧩 工作原理

通过 Android 系统隐藏接口 `MediaSessionManager#setOnVolumeKeyLongPressListener`（AOSP 中权限级别 `signature|privileged|development`，其中 `development` 允许 ADB `pm grant` 授予普通应用）注册**系统级音量键长按监听**，息屏状态由系统服务直接分发长按事件给 App 的前台服务，随后用 `MediaRecorder` 完成本地录音。Macrodroid 等自动化工具的"音量键长按"触发器即同一机制。

> ⚠️ 该接口属于 Android 隐藏 API，理论上未来大版本可能收紧（目前至 Android 16 实测可用）。届时 App 的通知栏按钮功能不受影响。

## 📄 许可证

[MIT](LICENSE) © 2026 ScreenOffRec Contributors

欢迎 Issue 反馈机型兼容性 / PR 改进。如果帮到了你，点个 ⭐ 让更多人看到。
