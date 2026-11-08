# Minis App 编译交接文档（给 Windows 上的 agent）

> 面向：在 `ASUS`（Windows）上执行编译的 agent。
> 环境：源码树 `D:\minis-src`，SSH `zhrl5@192.168.2.14`，手机 `192.168.2.32:5555`。
> 写于 2026-11-06 22:40。

---

## 0. 你的任务

**在 `D:\minis-src` 上编译 Minis App 的 `githubPerf` 变体，产出 APK，校验后安装到手机。**

不要改 `applicationId`、不要改 `versionCode`、不要动 `local.properties` 里的 `sdk.dir`。
这三个是「不丢用户数据」的保证。

---

## 1. 环境（已就绪，不要重装）

| 项 | 值 |
|---|---|
| 源码树 | `D:\minis-src`（官方 HEAD `1a71df0` + 已应用补丁） |
| JDK | `D:\Android\jdk-17`（Temurin 17.0.21，**官方要求 17，不要用系统默认的 21**） |
| Android SDK | `D:\Android\Sdk` |
| NDK | `27.0.12077973`（本机唯一可用版本） |
| Gradle | **wrapper 8.11.1**（`src\android\gradlew.bat`，已生成） |
| `local.properties` | `sdk.dir=D:/Android/Sdk`（已存在，**不要删**） |
| 输出 | `D:\minis-src\src\android\app\build\outputs\apk\perf\app-perf.apk` |
| 日志 | `D:\minis-build-out\build2.log` |

已应用的补丁（`git status` 里会看到这些为 modified，属正常）：
```
M deps/build_proot.sh                            ← 0001 Windows 交叉编译
M src/android/app/build.gradle.kts               ← 0001 ndkVersion + .dev 后缀
M src/android/gradle.properties                  ← 0001 内存
M src/android/gradle/wrapper/gradle-wrapper.jar  ← 0003 wrapper
M src/android/gradlew                            ← 0003 wrapper
?? src/android/gradlew.bat                       ← 0003 wrapper（新增）
```

---

## 2. 编译命令（照抄）

`cmd.exe` 里执行：

```cmd
set JAVA_HOME=D:\Android\jdk-17
set ANDROID_HOME=D:\Android\Sdk
set ANDROID_SDK_ROOT=D:\Android\Sdk
cd /d D:\minis-src\src\android
call gradlew.bat assembleGithubPerf --no-daemon --stacktrace
```

**变体必须是 `assembleGithubPerf`**，不是 `assemblePerf`、`assembleDebug`、`assembleGithub`。
- `githubPerf` → `com.openminis.app.dev` ← **手机里装的就是这个**
- `github` → `com.openminis.app` ← 装了会变成另一个 App，用户数据全毁

**首次编译约 7 分钟**（6m54s 实测），增量约 1~2 分钟。

### 如果你的会话会断
`gradlew` 跑在 SSH 里，**SSH 一断进程就被杀**。必须用计划任务：

```cmd
schtasks /Create /TN MinisBuild /TR "C:\Users\zhrl5\build.bat" /SC ONCE /ST 23:59 /RL LIMITED /F
schtasks /Run /TN MinisBuild
```
`build.bat` 内容：
```bat
@echo off
set JAVA_HOME=D:\Android\jdk-17
set ANDROID_HOME=D:\Android\Sdk
set ANDROID_SDK_ROOT=D:\Android\Sdk
cd /d D:\minis-src\src\android
call gradlew.bat assembleGithubPerf --no-daemon --stacktrace > D:\minis-build-out\build2.log 2>&1
echo === EXIT=%ERRORLEVEL% %DATE% %TIME% === >> D:\minis-build-out\build2.log
```
查进度：`type D:\minis-build-out\build2.log`

---

## 3. 编译成功后必须做的 4 项校验

**一步都不能省**。签名或包名错了 = 装不上，或更糟：装成另一个包名把用户数据搞没。

```cmd
:: ① 包名 + 版本（必须是 com.openminis.app.dev）
D:\Android\Sdk\build-tools\35.0.0\aapt2.exe dump badging ^
  D:\minis-src\src\android\app\build\outputs\apk\perf\app-perf.apk | findstr /i "package:"

:: ② 签名（必须等于下面这个 SHA-256）
D:\Android\Sdk\build-tools\35.0.0\apksigner.bat verify --print-certs ^
  D:\minis-src\src\android\app\build\outputs\apk\perf\app-perf.apk

:: ③ 大小（应在 58~62 MB）
dir D:\minis-src\src\android\app\build\outputs\apk\perf\app-perf.apk

:: ④ 产物存在
dir /s /b D:\minis-src\src\android\app\build\outputs\apk\*.apk
```

**基准值（2026-11-06 实测）**：
```
package: name='com.openminis.app.dev' versionCode='37' versionName='1.15-dev'
签名 SHA-256: d3c5d4c293492430f0ab6c7dfe8b44f09e183b5d28e3645320c4d729b1ce20d0
大小: 58.8 MB (61649541 bytes)
```

**签名不匹配 → 停，不要装。** 跟用户报告。

---

## 4. 安装

```cmd
set ADB=C:\Users\zhrl5\platform-tools\adb.exe
set DEV=192.168.2.32:5555

:: 先确认设备在线
%ADB% -s %DEV% devices -l

:: 安装（★ 手机会弹确认框，需要用户在手机上点「安装 / 允许」）
%ADB% -s %DEV% install -r D:\minis-src\src\android\app\build\outputs\apk\perf\app-perf.apk
```

### 安装的三个坑

**坑 1：手机弹框必须有人点**
不点就自动拒绝，报：
```
INSTALL_FAILED_ABORTED: User rejected permissions
```
这是**正常的**，不是构建失败。让用户在手机上点确认即可。
`settings put global adb_install_need_confirm 0` 在华为 EMUI 上**无效**（实测）。

**坑 2：别在 PowerShell 里用 `Select-Object -First` 接 adb 输出**
```powershell
& $adb shell dumpsys package X | Select-String "..." | Select-Object -First 3   # ← 会杀掉 adb
```
管道提前终止 → adb 进程被杀 → 脚本异常退出，日志只写一半。
**在 cmd 里用 `findstr`**，或者用 `| Out-File` 再单独读文件。

**坑 3：Activity 名不带 `.dev` 后缀**
```
✅ com.openminis.app.dev/com.openminis.app.MainActivityIconAuto
❌ com.openminis.app.dev/com.openminis.app.MainActivityIconLight   ← 不存在，报 Error type 3
```
查真名：`adb shell cmd package resolve-activity --brief com.openminis.app.dev`

安装后启动：
```cmd
%ADB% -s %DEV% shell am start -n com.openminis.app.dev/com.openminis.app.MainActivityIconAuto
```

---

## 5. 安装后验证数据没丢（手机侧）

```cmd
%ADB% -s %DEV% shell dumpsys package com.openminis.app.dev | findstr /i "versionCode= versionName= lastUpdateTime="
```
`lastUpdateTime` 应变成今天。

会话数据在 App 私有目录里，**adb shell 读不到**（App 非 debuggable）。用手机上的 Minis 自己查，或者：
```cmd
%ADB% -s %DEV% shell pm path com.openminis.app.dev
```

---

## 6. 编译失败时怎么排查

```cmd
:: 看错误
findstr /i /c:"^e: " /c:"error:" /c:"What went wrong" D:\minis-build-out\build2.log

:: 看完整错误上下文
more D:\minis-build-out\build2.log
```

### 已踩过的编译错误

**Kotlin 局部枚举不能加 `private`**
```
e: Modifier 'private' is not applicable to 'local class'.
```
→ 枚举要定义在**文件顶层**，不能定义在 composable 函数体内。

**`Modifier 'enum' is not applicable to 'local class'`** —— 同上。

**`unresolved reference: rememberUpdatedState`** → 补 import：
```kotlin
import androidx.compose.runtime.rememberUpdatedState
```

**改了 Compose 代码但编译没重编** → gradle 缓存。确认任务真的跑了（日志里应该有 `> Task :app:compileGithubPerfKotlin`）。

---

## 7. 补丁体系（以后改代码用这个）

源码不在 Windows 上改，而是**在沙箱里改 → 生成补丁 → 传到 Windows 应用**：

```
沙箱（手机）: /var/minis/workspace/minis-build/
├── upstream/                     官方源码 git clone
├── local/patches/
│   ├── 0001-windows-build-and-dev-suffix.patch   ← .dev 后缀等（已应用）
│   ├── 0002-press-to-talk-and-auto-send.patch    ← 语音改动（本次）
│   └── 0003-windows-gradlew-wrapper.patch        ← gradlew.bat（已应用）
├── sync.sh                       拉官方 + 校验 + 套补丁
└── HANDOFF.md                    完整交接文档
```

### 加新补丁的流程
1. 在沙箱 `upstream/` 里改代码
2. `git diff > local/patches/000N-xxx.patch`
3. `git checkout -- .` （把 upstream 复位到干净状态，**补丁才是唯一真相**）
4. `sh sync.sh check` 确认能干净应用
5. 传到 Windows：`scp local/patches/000N-xxx.patch zhrl5@192.168.2.14:C:/Users/zhrl5/000N.patch`
6. Windows 上：`cd /d D:\minis-src && git apply 000N.patch`

### ★ 切分补丁时的坑
如果 upstream 里**已经应用过前面的补丁**，直接 `git diff` 会把前面补丁的内容也卷进来，
在 Windows 上重复应用就会冲突。正确做法：

```sh
cd upstream
git checkout -- .                              # 复位到干净
git apply local/patches/000N.patch             # 只应用这一个
git diff > local/patches/000N.patch            # 重新生成（此时 diff 只含该补丁）
git checkout -- .                              # 再复位
sh sync.sh check                               # 校验
```

### ★ PowerShell 重定向会写 UTF-16
```powershell
git diff --binary > C:\...\0003.patch     # ← 写出来是 UTF-16LE+BOM，git apply 读不了
```
必须在沙箱侧用 Python 转成 UTF-8：
```sh
python3 -c "
p='0003.patch'
d=open(p,'rb').read()
if d[:2] in (b'\xff\xfe', b'\xfe\xff'):
    t=d.decode('utf-16').replace('\r\n','\n')
    open(p,'w',encoding='utf-8',newline='\n').write(t)
"
```
二进制补丁（`gradle-wrapper.jar`）必须带 `--binary` 参数。

---

## 8. 备份位置（改代码前先确认这些还在）

| 内容 | 位置 |
|---|---|
| Windows 源码备份（305.8 MB） | `D:\minis-backup\20261106T131500Z\minis-src-app-20261106T131500Z.tar.gz` |
| 手机上的 APK | `/storage/emulated/0/backup/App/app-perf-v37-20261106.apk` |
| 手机上的 App 数据 | `/storage/emulated/0/backup/Data/pre-install-20261106T214500Z/var-minis.tar.gz` |
| 交接文档 | `D:\minis-src\..\minis-build\HANDOFF.md`（沙箱侧 `/var/minis/workspace/minis-build/HANDOFF.md`） |

---

## 9. 文档与文件在哪（都在 `D:\minis-src\` 根目录）

| 文件 | 内容 |
|---|---|
| `WINDOWS-BUILD-AGENT.md` | **本文档** —— 你的操作手册 |
| `HANDOFF.md` | 完整交接文档（9 节：仓库结构、补丁、构建环境、坑、备份、待办） |
| `build.bat`（在 `C:\Users\zhrl5\`） | 干净版编译脚本，只编译不打补丁 |
| `0002-press-to-talk-and-auto-send.patch` | 语音补丁 —— **已 apply，不要再 apply** |
| `0003-windows-gradlew-wrapper.patch` | wrapper 补丁 —— **已 apply，不要再 apply** |
| `0004-android-queued-message-edit.patch` | 排队消息「修改」—— **已 apply，不要再 apply** |
| `D:\minis-build-out\build2.log` | 编译日志 |

沙箱侧（手机）还有一份完整副本：
`/var/minis/workspace/minis-build/`（upstream + local/patches + sync.sh）

---

## 8b. ⚠️ 当前状态（2026-11-06 23:05）

**编译已跑过，装机完成。** `assembleGithubPerf` 23:00:48 产出 APK，
23:02:08 `adb install -r` 到 P40（`192.168.2.32:5555`，ELS-AN00）：
`versionCode=37 / versionName=1.15-dev / package=com.openminis.app.dev`，
签名 `d3c5d4c2…1ce20d0` 与基准一致，`lastUpdateTime=2026-11-06 23:02:08`。
本次改动 = 0002（按住说话/自动发送）+ **0004（排队消息可改）**。

★ **schtasks 那条路走不通了**：`/Run` 起的进程在发起它的 PowerShell 退出时会收到
Ctrl+C（`上次结果 = -1073741510`，即 STATUS_CONTROL_C_EXIT），`build2.log` 里只有一个 `^C`。
改用 PowerShell 工具的 `run_in_background` 直接跑 `gradlew.bat`，日志 `D:\minis-build-out\build3.log`
（**UTF-16**，读的时候 `tail -c 2000 file | tr -d '\000'`，别用 iconv 后直接 grep）。增量编译 4m05s。

★ 装机后 App 重启会杀掉沙箱后台进程，8801 语音网关 / 8802 LLM 网关要手动拉起：
```sh
sh /var/minis/shared/local-asr/ensure.sh start
sh /var/minis/shared/minis-llm-gateway/ctl.sh start
```

---

## 10. 做完要报告什么

1. 编译结果（SUCCESS/FAILED + 耗时）
2. 3 项校验的实际值（包名、签名 SHA-256、大小）与基准值是否一致
3. 安装结果（`Success` / 失败原因）
4. `lastUpdateTime` 是否更新
5. **如果签名或包名不一致 → 明确说「已停止安装」并给出实际值**
