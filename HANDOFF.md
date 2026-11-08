# Minis 自编译 —— 交接文档

> 2026-11-06 建立。给下一个会话（或未来的我）看的。

## 一句话现状

**Minis App 已能自编译、能覆盖安装、数据不丢。** 官方源码 + 补丁栈已在
`/var/minis/workspace/minis-build/`，Windows 构建树在 `D:\minis-src`。
2026-11-06 21:59 完成首次 `assembleGithubPerf` 成功编译并覆盖安装。

---

## 1. 仓库结构

```
/var/minis/workspace/minis-build/
├── upstream/          官方源码 git clone（github.com/OpenMinis/OpenMinis）
│                       HEAD 1a71df0（2026-10-26），--filter=blob:none
│                       ⚠️ clone 时**没带 submodule**，deps/ish、deps/proot 是空的
├── local/patches/
│   ├── 0001-windows-build-and-dev-suffix.patch   ← .dev 后缀 + NDK + 内存
│   ├── 0002-press-to-talk-and-auto-send.patch    ← 按住录音/松开识别/完成即发
│   └── 0003-windows-gradlew-wrapper.patch        ← gradlew.bat（Windows 必需）
├── sync.sh            拉官方 → 校验补丁 → 套补丁
│                       用法：sh sync.sh          # 快进+套补丁
│                             sh sync.sh check    # 只检查补丁能否应用
│                             sh sync.sh reset    # 强制对齐 origin/main
├── win.sh             远程执行 Windows 命令
├── HANDOFF.md         完整交接文档（9 节）
├── WINDOWS-BUILD-AGENT.md   给 Windows 上那个 agent 的操作手册
└── docs/BUILD-STATUS.md   官方要求 vs 本机现状对照表
```

**升级官方源码的完整流程**：
```sh
cd /var/minis/workspace/minis-build
sh sync.sh                 # fetch + ff-only + 套补丁
# 然后把改动打到 Windows:
#   scp -r upstream/... → D:\minis-src\...  或直接在 D:\minis-src 里 git pull
```

---

## 2. ★ 唯一的补丁（改这 3 个文件）

`local/patches/0001-windows-build-and-dev-suffix.patch`

| 文件 | 改动 | 为什么 |
|---|---|---|
| `deps/build_proot.sh` | 加 `MINGW*\|MSYS*\|CYGWIN*) host_tag="windows-x86_64"` | 官方脚本不支持 Windows 交叉编译 proot，不加直接 `log_error Unsupported host` |
| `src/android/app/build.gradle.kts` | `ndkVersion = "27.0.12077973"` | 本机只装了 NDK 27（官方建议 r28+） |
| 同上 | perf 变体加 `applicationIdSuffix = ".dev"` / `versionNameSuffix = "-dev"` | **和官方原版共存 + 覆盖安装不丢数据**，这是用户明确要求 |
| `src/android/gradle.properties` | 堆加到 `-Xmx4096m` + `kotlin.daemon.jvmargs=-Xmx3072m` | 官方 2048m 在本机编译时 OOM |

> ★ **`.dev` 后缀是保住数据的唯一保障**。去掉它 = 换包名 = 换 App = 数据全毁。
> 官方原版 `com.openminis.app` 已于 2026-11-06 卸载，现在只有 `.dev` 一个实例。

---

## 3. Windows 构建环境

| 项 | 值 | 备注 |
|---|---|---|
| 源码树 | `D:\minis-src` | 含 `.git`、完整原生产物、构建缓存 |
| Gradle | **wrapper 8.11.1**（`gradlew.bat`） | ★ 官方版本，见下方「坑」 |
| JDK | **17.0.21**（`D:\Android\jdk-17`） | 官方要求 17（系统默认是 21） |
| SDK | `D:\Android\Sdk` | 由 `src/android/local.properties` 指定 |
| NDK | `27.0.12077973` | 补丁写死 |
| 变体 | `assembleGithubPerf` | → `com.openminis.app.dev` |
| 日志 | `D:\minis-build-out\build-githubperf.log` | |

### 编译命令
```cmd
set JAVA_HOME=D:\Android\jdk-17
set ANDROID_HOME=D:\Android\Sdk
set ANDROID_SDK_ROOT=D:\Android\Sdk
cd /d D:\minis-src\src\android
call gradlew.bat assembleGithubPerf --no-daemon --stacktrace
```

### ★ 三个必须知道的坑

**坑 1：SSH 断连会杀掉后台编译**
`Start-Process` 起的编译在 SSH 退出后立刻消失。
**必须用 schtasks 投递到交互式会话**：
```cmd
schtasks /Create /TN MinisBuild /TR "C:\Users\zhrl5\build.bat" /SC ONCE /ST 23:59 /RL LIMITED /F
schtasks /Run /TN MinisBuild
```

**坑 2：`Select-Object -First N` 会杀掉原生 adb 命令**
```powershell
& $adb shell dumpsys package X | Select-String "..." | Select-Object -First 3
```
→ 管道提前终止 → adb 被杀 → 脚本异常退出，日志只写了一半。
**在 cmd 里用 `findstr` 代替**，不要在 PowerShell 里用 `Select-Object -First` 接原生命令。

**坑 3：华为 EMUI 的 adb 安装弹窗**
`adb install` 会弹确认框，**没人点就自动拒绝**：
```
INSTALL_FAILED_ABORTED: User rejected permissions
```
解决：让用户在手机上点确认。`settings put global adb_install_need_confirm 0` 在本机无效（实测）。

**坑 4：PowerShell 重定向写 UTF-16**
`git diff --binary > file.patch` 在 PowerShell 里写出的是 UTF-16LE+BOM，
`git apply` 直接报 `No valid patches in input`。
→ 在沙箱侧用 Python 转 UTF-8；二进制补丁（gradle-wrapper.jar）必须带 `--binary`。

**坑 5：切分补丁时会把前面的补丁卷进来**
upstream 里若已应用过 0001，此时 `git diff > 0002.patch` 会把 0001 的内容也写进去，
Windows 上重复应用就冲突（`patch does not apply`）。
→ 正确切分流程见 `WINDOWS-BUILD-AGENT.md` 第 7 节。

---

## 4b. Windows 上的 agent 怎么干活

**完整手册在 `/var/minis/workspace/minis-build/WINDOWS-BUILD-AGENT.md`**，
已复制到 Windows 的 `C:\Users\zhrl5\MINIS-BUILD.md`。

要点：
- 变体 `assembleGithubPerf`（不是 assemblePerf / assembleDebug / assembleGithub）
- 装之前必须核对包名 `com.openminis.app.dev` 与签名 SHA-256
  `d3c5d4c293492430f0ab6c7dfe8b44f09e183b5d28e3645320c4d729b1ce20d0`
- 改代码走「沙箱改 → 生成补丁 → scp 到 Windows → git apply」，不直接改 Windows

---

## 4. 安装流程（已验证可用）

```cmd
set ADB=C:\Users\zhrl5\platform-tools\adb.exe
set DEV=192.168.2.32:5555        :: 手机的 Tailscale/LAN IP，会变
set APK=D:\minis-src\src\android\app\build\outputs\apk\perf\app-perf.apk

:: 1. 先比对签名（必做，签名不同 = 装不了 = 会失败）
D:\Android\Sdk\build-tools\35.0.0\apksigner.bat verify --print-certs %APK%

:: 2. 装（弹窗需要用户点）
%ADB% -s %DEV% install -r %APK%

:: 3. 启动（★ Activity 名不带 .dev 后缀！）
%ADB% -s %DEV% shell am start -n com.openminis.app.dev/com.openminis.app.MainActivityIconAuto
```

### ★ Activity 名坑
`applicationIdSuffix = ".dev"` 只改包名，**不改 Activity 类名**。
- ✅ `com.openminis.app.dev/com.openminis.app.MainActivityIconAuto`
- ❌ `com.openminis.app.dev/com.openminis.app.MainActivityIconLight`（不存在，报 Error type 3）

查真实名字：`adb shell cmd package resolve-activity --brief com.openminis.app.dev`

---

## 5. 安装前必做的备份

### Windows 侧（源码，305.8 MB）
```
D:\minis-backup\20261106T131500Z\minis-src-app-20261106T131500Z.tar.gz
sha256 614df6283635fa5cdb94932a920bf82245414c7df38d9e0905de6522af641350
9391 个成员
```
备份内容：`.git/` + `deps/`（含 submodule）+ `app/src/main/` + `app/libs/rclone.aar` + `app/build/outputs/apk/`
**不备份**：`app/build/intermediates`（578 MB，可再生）、`app/.cxx`、`src/android/.gradle`

### 手机侧（App 数据）
```
/storage/emulated/0/backup/App/app-perf-v37-20261106.apk        61.6 MB
/storage/emulated/0/backup/Data/pre-install-20261106T214500Z/
    var-minis.tar.gz          144 MB（沙箱内打的，完整 /var/minis）
    var-minis-sandbox.tar.gz  4.5 MB（精简版）
    sessions/sessions-list.json
```
★ **`adb shell` 是 shell uid，读不到 `/data/data`**（App 非 debuggable，`run-as` 也失败）
→ **App 数据只能从沙箱内（root）打包**：
```sh
cd /var/minis && tar -czf /mnt/androidwritable/0/emulated/0/backup/Data/<名字>/var-minis.tar.gz \
    --exclude='offloads' --exclude='mounts' memory skills shared workspace attachments mcp-servers browser _tasks.json
```

---

## 6. 安装后 App 数据验证清单（本次已全过）

| 项 | 命令 | 本次结果 |
|---|---|---|
| 会话 | `minis-sessions-cli list` | 4 个全在 ✅ |
| 定时任务 | `minis-scheduled list` | 10 条全 enabled ✅ |
| Provider | `minis-config get providers` | 5 个全在 ✅ |
| 记忆 | `ls /var/minis/memory/` | GLOBAL.md 179 行 + 19 个日志 ✅ |
| 技能 | `ls -d /var/minis/skills/*/` | 11 个全在 ✅ |

### ⚠️ App 重启会杀掉沙箱后台进程
安装后 **8801 语音网关、8802 LLM 网关都要重新拉起**：
```sh
sh /var/minis/shared/local-asr/ensure.sh start
sh /var/minis/shared/minis-llm-gateway/ctl.sh start
```
（验证用 python urllib，**不要用 curl 包装器**，会卡住导致 proot 被杀）

---

## 7. 接下来要做的（用户已定调）

### ① 语音改「按住麦克风录音 / 松开识别 / 完成自动发送」
官方源码位置（已核实）：
- `src/android/app/src/main/java/com/openminis/app/speech/VoiceActivityDetector.kt:155`
  `private const val END_FRAMES = 156`（≈5.0s 尾静音 —— **这才是「太慢」的根源**，
  不是 ASR 本身。MiniMax 只要 0.9~1.5s）
- `src/android/app/src/main/java/com/openminis/app/ui/chat/voice/InlineVoiceInputPanel.kt:427`
  `fun onRecognized(text, isFinal)` —— 目前只 `setTranscript()`，**不发**
- `src/android/app/src/main/java/com/openminis/app/sandbox/SeccompFallbackPolicy.kt`
  `RETRYABLE_EXIT_CODES = setOf(132, 135, 139, 159)` —— 终端 `-159` 是 seccomp
  （华为内核），但 `sawOutput` 为真时不重试，**需要改成 159 无条件重试一次**

### ② 上下文压缩换方案（用户提案 + 我的修正）
用户要「子 agent 定时把会话整理成标签，注入提纲，需要时展开」。
**我的修正**：不要「定时」→ 改成「有活动 + 未整理条数 > K 才触发」；
整理产物分两层（注入层=标签+一行结论，展开层=原文落盘）。

源码依据：
- `data/ContextPolicy.kt` —— `forUserCap(window)` 阈值 = window×0.85
  → 用户设 `contextWindowOverride=1,000,000` 时**压缩永远不触发**（触发线 810K）
- `ui/chat/ContextTrimPlanner.kt` —— 官方已有「阶梯裁剪」
  （L1 清 reasoning → L2 工具结果落盘 → L3 工具参数占位 → SK 骨架 → L4 折叠）
  但它是**发送前临时裁剪**，不是会话记忆压缩；每轮重算
- `data/MemoryRepository.kt:24,34` —— `MAX_INJECT_LINES = 200`、`MAX_RECENT_FILES = 3`
  ★ **bug**：`lines.take(200)` 取的是文件**开头**，而 `memory_write` 是前插的
  → 注入的是当天**最早**的记录。应该改 `takeLast(200)`

### ③ 终端 `-159` 兼容
华为内核 seccomp 拒绝 proot 的规则。终端清屏后一直闪光标 = 进程活着但没输出。
**候选**：改 `SeccompFallbackPolicy` 让 159 无条件重试 `PROOT_NO_SECCOMP=1`；
或换 32 位 loader；或检查是否用了 `clone3`。
（用户已清屏验证过，进程仍活着）

---

## 7b. 排队消息「修改」功能（2026-11-06 晚，已完成并装机）

待办 ① 的语音改动和本项都已编译进 23:02 安装的那版（`versionCode=37`）。

排队中的用户消息原来是「只能撤回、不能改」——`onEdit` 在 `isQueued` 时被强制置空。
现在加了**原地改写**：不截断历史、不进 composer（composer 在流式中发送会被当成新排队项，
旧的还留在队列里，同一句话发两遍）。

| 文件 | 改动 |
|---|---|
| `ChatViewModel.kt` | 新增 `editQueuedMessage(messageId, newText): Boolean` — **必须同时改 `_promptQueue[].text` 和 `_messages[].content`**，只改气泡是装饰性的，模型照样读旧文本。仅 `origin == USER` 可改（PROGRAMMATIC 的正文带 envelope，改了会破坏 loop 的识别标记） |
| `ChatUserMessageUI.kt` | `UserMessageBubble` 新增 `onEditQueued`；虚线气泡右侧在红色 ✕ 左边多一个 44dp 铅笔按钮；长按菜单加「修改排队消息」 |
| `ChatScreen.kt` | 订阅 `viewModel.promptQueue` 算出 `editableQueuedIds`；`queuedEditTargetId` 状态 + `QueuedMessageEditDialog`（多行 `OutlinedTextField`，空文本且有附件才允许保存） |
| `values{,-zh,-zh-rTW}/strings.xml` | `chat_queued_edit` / `_title` / `_hint` / `_placeholder` |

新增补丁 **`0004-android-queued-message-edit.patch`**（6 文件 13 hunk）。
已在干净基线（HEAD + 0002）上验证 `git apply` 通过，且重建结果与工作区逐字节一致。
★ 生成方式：`git diff` 里 ChatScreen/strings 同时混着 0002 的改动，
按 hunk 过滤（只留含 `queued_edit` / `editableQueuedIds` / `T-android-queued-message-edit` 的 hunk），
否则就是坑 5。

沙箱侧 `/var/minis/workspace/minis-build/local/patches/` **还没有**这份 0004，下次 `sync.sh` 前要拷过去。

---

## 8. 沙箱修复（2026-11-06）

`/etc/profile.d/minis.sh` 是 **CRLF 行尾** → 登录 shell 刷 `: not found`，
终端显示为「坏掉」。已 `sed -i 's/\r$//'` 修复（备份 `minis.sh.bak.20261106T123540Z`）。
其余 6 个 profile.d 脚本都是 LF。
★ **从 Windows/网页复制的文件必须先查 CRLF**。

---

## 9. GLOBAL.md 精简（2026-11-06）

791 行 54.5 KB → **179 行 10.9 KB**（省约 1.3 万 token/轮）。
备份：`/var/minis/memory/GLOBAL.md.bak.20261106T123540Z`
删除的都是一次性事件（扩容记录、已完成项目、历史实测延迟），
保留跨会话仍生效的约定、服务地址、已知坑。
