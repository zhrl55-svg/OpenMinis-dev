# Minis 助理模式（端侧快速路径）—— 调试任务书

> 面向**电脑端 agent**。目标：验证并打通「规则选工具 + 端侧抽参 → 直接执行 → 不调云端」这条主链路。
> 状态：2026-11-13 13:55。**已在真机跑通主链路与回退分支。**

---

## 0. 先看这个：当前设备上的 APK 是旧版

```
设备：com.openminis.app.dev（华为 P40 Pro，arm64）
已装 APK：2026-11-13 12:44:47   ← 旧版，不含主链路改动
待装 APK：2026-11-13 13:18       ← 新版，69,780,890 字节
        D:\minis-src\src\android\app\build\outputs\apk\github\perf\app-github-perf.apk
```

**新版多了什么**（旧版完全没有）：
- `llm/mnn/AssistantFastPath.kt` —— 助理模式开关
- `ChatViewModel.runLocalAssistantFastPath()` —— 端侧快速路径
- `minis://action/assistant_mode?on=true|false` —— 开关 deep-link

**所以第一步必须是装新版。** 之前那次「助理模式已开启」是误判——
开关 deep-link 是 13:18 才加的，旧版不认识这个 action，收到后走 `else -> Unknown`，什么都没做。

---

## 1. 环境与通道（重要，别走错路）

| 用途 | 地址 | 说明 |
|---|---|---|
| Windows SSH | **`zhrl5@192.168.2.14`** | ★ **走局域网，不要用 Tailscale**（`100.88.138.66`）。Tailscale 隧道绕路且会随机断连，实测多次 `Connection reset` / `waiting for device` |
| Windows adb | `C:\Users\zhrl5\platform-tools\adb.exe` | 设备 serial 用 **`192.168.2.32:5555`**（USB 那条 `MDX0220A21035172` 经常掉） |
| 手机 | `192.168.2.32` | 和 Windows 同一网段 |
| relay (hub) | `ws://192.168.2.102:8787/v1/hub` | buntu 上，Windows 可用 `hub_call` |

ssh 命令一律加 `-o ProxyCommand=none`：
```bash
ssh -o StrictHostKeyChecking=no -o ConnectTimeout=15 -o ProxyCommand=none zhrl5@192.168.2.14 '<cmd>'
```

---

## 2. 已验证的部分（不用重测）

| 项 | 结果 | 怎么验的 |
|---|---|---|
| 端侧 MNN 推理 | ✅ 加载 0.4s / 生成 1.0s / 中文通顺 | `minis://action/local_llm?prompt=...` |
| 规则选工具 | ✅ **13/13** | `minis://action/local_intent` |
| 端侧抽参数 | ✅ **9/9**，零误触发 | 同上 |
| 规则不命中→转云端 | ✅ 4/4 | 同上 |
| 模型文件已部署 | ✅ `/sdcard/Android/data/com.openminis.app.dev/files/models/mnn-llm-0.3b/`（171MB） | — |

**验收报告读法**：
```cmd
adb shell cat /sdcard/Android/data/com.openminis.app.dev/files/local_intent_probe.txt
```

---

## 3. 任务一：装新版 APK

```cmd
set ADB=C:\Users\zhrl5\platform-tools\adb.exe
set DEV=192.168.2.32:5555
%ADB% -s %DEV% install -r "D:\minis-src\src\android\app\build\outputs\apk\github\perf\app-github-perf.apk"
```

★ **华为 EMUI 的 install 会弹确认框，没人点就自动拒绝**：
```
INSTALL_FAILED_ABORTED: User rejected permissions
```
`settings put global adb_install_need_confirm 0` 在本机无效（实测）。
→ **这一步必须人工点确认**。

装完核对：
```cmd
%ADB% -s %DEV% shell dumpsys package com.openminis.app.dev | findstr lastUpdateTime
```
必须是 **13:2x 之后**，不是 12:44。

---

## 4. 任务二：加外部诊断输出（**建议先做这个**）

**为什么必须做**：fast path 现在只往聊天里写一条助手消息，**不留任何诊断痕迹**。
如果端侧没被触发，你无法区分下面四种情况：

1. 助理模式没开（开关写失败）
2. 规则没命中（那句话不含关键词）
3. 端侧抽参失败（缺必填参数）
4. 工具执行失败

有诊断文件就能一眼看出走到哪一步、为什么停。

**实现建议**（照抄 `LocalIntentProbe` 的现成模式）：

在 `llm/mnn/AssistantFastPath.kt` 里加：

```kotlin
/** 诊断轨迹：写到 getExternalFilesDir(null)/assistant_fastpath.log，adb 可直接读。 */
fun trace(context: Context, line: String) {
    runCatching {
        val dir = context.getExternalFilesDir(null) ?: return@runCatching
        val f = java.io.File(dir, "assistant_fastpath.log")
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        f.appendText("[$stamp] $line\n")
    }
}
```

在 `ChatViewModel.runLocalAssistantFastPath()` 的每个 return 点写一行：

| 位置 | 该写的行 |
|---|---|
| 方法入口 | `enter enabled=${AssistantFastPath.isEnabled(context)} abi=${model.abiSupported()} model=${model.isAvailable()}` |
| 规则未命中 | `no-rule: 「$userText」` |
| 抽参失败 | `no-args: tool=${def.name} raw=${planner.lastRaw}` |
| 工具抛异常 | `tool-threw: ${t.javaClass.simpleName}: ${t.message}` |
| 成功 | `handled: tool=${ex.tool} success=${result.success} len=${result.output.length}` |

`LocalIntentPlanner` 里已经有 `lastRaw` 字段（电脑端 agent 之前加的），直接用。

```cmd
%ADB% -s %DEV% shell cat /sdcard/Android/data/com.openminis.app.dev/files/assistant_fastpath.log
```

---

## 5. 任务三：开开关 + 发一条测试消息 + 验证

### 5.1 开助理模式

```cmd
%ADB% -s %DEV% shell am start -a android.intent.action.VIEW -d "minis://action/assistant_mode?on=true"
```

★ cmd 下带 `&` 的 URL 要转义；更省事的是**从手机端发 intent**（沙箱里的 `minis-open`）。

开关状态存在 `SharedPreferences("assistant_fast_path")` 的 `enabled` 键，**默认 false**。

### 5.2 发一条命中规则的消息

**必须命中规则关键词**（否则规则不命中，直接走云端，这是设计如此）：
- `shell_execute` ← 运行 / 执行 / 跑一下 / 跑个 / 命令 / 终端 / shell / 脚本
- `file_read` ← 读一下 / 读取 / 打开文件 / 看看文件 / 文件内容 / 读文件
- `file_write` ← 写入 / 写文件 / 保存到 / 存到 / 新建文件 / 写一个
- `file_edit` ← 修改 / 编辑文件 / 改一下 / 改成 / 替换掉 / 编辑
- `browser_use` ← 网页 / 浏览器 / 打开网址 / 上网查 / 搜网页
- `memory_write` ← 记住 / 记一下 / 存进记忆 / 别忘了
- `memory_get` ← 我说过 / 我的记忆 / 之前说过 / 查记忆
- `hub_call` ← 另一台电脑 / 远程电脑 / 那台机器 / 远程执行
- `read_image` ← 这张图 / 看图 / 图片内容 / 识别图

**推荐测试句**（一句就够，含可验证的输出）：
```
运行命令 echo hi-from-local-model
```

**怎么发**（中文，`adb shell input text` 不支持中文）：
- **方案 A（最省事）**：让用户在手机上发这一句。
- **方案 B（自动化）**：剪贴板 + a11y 粘贴。
  ```bash
  android-clipboard set "运行命令 echo hi-from-local-model"
  android-a11y-cli ui dump        # 找输入框 nodeId
  android-a11y-cli tap node <id>  # 聚焦
  ```
  `android-a11y-cli` 没有 `paste` 子命令；`input text` 对中文的支持要实测。
- **方案 C（最干净，但要改代码）**：加一个 deep-link 模拟用户发消息
  （`minis://action/test_send?text=...`），走**非 headless** 路径。
  ⚠️ headless 路径被快路径排除了（`!headless` 条件），所以定时任务/子 agent 测不了。

### 5.3 判定标准

**端侧接管了**（助理模式生效）：
- 助手回复以 **`✅ `** 开头（`AssistantFastPath.formatResult` 的格式）
- 回复内容形如：
  ```
  ✅ 运行命令 echo hi-from-local-model

  hi-from-local-model
  ```
- 诊断文件有 `handled: tool=shell_execute success=true ...`
- **没有**云端的自然语言解释、没有 token 用量增长

**没接管**（看诊断文件定位）：

| 诊断行 | 原因 | 怎么办 |
|---|---|---|
| `enter enabled=false` | 开关没生效 | 重发 deep-link；或查 SharedPreferences |
| `enter enabled=true abi=false` | ABI 不支持 | 端侧推理只能真机；确认不是模拟器 |
| `enter enabled=true model=false` | 模型文件缺失 | 跑 `scripts/deploy_mnn_llm.sh` |
| `no-rule:` | 规则没命中 | 换一句含关键词的话（**这是正常行为，不是 bug**） |
| `no-args:` | 端侧抽参失败 | 看 `raw=` 里模型吐了什么；参考 §6 的调优顺序 |
| `tool-threw:` | 工具执行抛异常 | 看异常类型；工具实现问题 |

**完全没反应**：说明没走到 fast path —— 检查 `!headless && promptAttachments.isEmpty()` 两个前置条件。

---

## 6. 如果抽参失败：调优顺序

看诊断文件里的 `raw=`（模型原始输出）决定怎么调：

1. **模型吐的不是 JSON** → prompt 加/改 few-shot
2. **模型吐了但值是占位符**（`"示例值"`）→ few-shot 示例不能用占位符，
   必须用「另一句真实用户话 + 从那句话抽出的真实值」
3. **值不是用户原话里的** → 逐词校验会拦掉它；检查 `verbatimSlots`
4. **缺必填参数** → 检查 `slotsOf()` 和 `extraSlots`

改完重新编译安装（见 §7），再跑一遍。

---

## 7. 编译（这台 Windows 上的坑）

```cmd
set "JAVA_HOME=D:\Android\jdk-17"
set "ANDROID_HOME=D:\Android\Sdk"
set "ANDROID_SDK_ROOT=D:\Android\Sdk"
cd /d D:\minis-src\src\android
call gradlew.bat assembleGithubPerf --no-daemon > D:\minis-build-out\b.log 2>&1
```

★ **必须用引号形式 `set "JAVA_HOME=..."`**。
写成 `set JAVA_HOME=D:\Android\jdk-17 && set ...` 时，`&&` 前的空格会进变量，
gradlew 报 `JAVA_HOME is set to an invalid directory`。

产物：`D:\minis-src\src\android\app\build\outputs\apk\github\perf\app-github-perf.apk`

---

## 8. 代码位置

| 内容 | 路径 |
|---|---|
| 快速路径实现 | `D:\minis-src\src\android\app\src\main\java\com\openminis\app\llm\mnn\AssistantFastPath.kt` |
| 规则 + 抽参 | `...\llm\mnn\LocalIntentPlanner.kt` |
| 模型管理 | `...\llm\mnn\LocalMnnModel.kt` |
| 探针 | `...\llm\mnn\LocalLlmProbe.kt` / `LocalIntentProbe.kt` |
| **主链路注入** | `...\ui\chat\ChatViewModel.kt` → `runLocalAssistantFastPath()`（约 11018 行），注入点约 9910 行 |
| 开关 deep-link | `...\deeplink\DeepLinkHandler.kt` + `...\MainActivity.kt` |
| 完整背景文档 | `D:\minis-src\docs\HANDOFF-mnn.md` |

**注入点长这样**（在 `runAgentLoop` 之前、同一个 try/finally 内，
这样 `releaseSlot` / `setInactive` / `isStreaming` 复位自动复用）：

```kotlin
val handledLocally = if (!headless && promptAttachments.isEmpty()) {
    runCatching { runLocalAssistantFastPath(trimmed, ...) }.getOrElse { false }
} else false

if (handledLocally) {
    // 端侧已处理，跳过云端
} else {
    runAgentLoop(...)   // 原来的云端路径，一行没改
    drainQueuedPrompts(...)
}
```

---

## 9. 设计红线（改的时候别破）

1. **失败即回退**：任何一步不成立都必须 return false 走云端。
   端侧是**可选的加速器**，不是单点故障。用户不该因为它的存在得到更差的结果。
2. **默认关**：`AssistantFastPath.isEnabled()` 默认 false。新路径不能默认改变现有行为。
3. **不改云端路径**：`runAgentLoop` 及之后的一切，一行都不要动。
4. **`headless` 和带附件的排除在外**：定时任务、子 agent、图片消息全走原路径。
5. **工具执行复用 `executeTool`**：端侧和云端共用一个执行器，行为/权限门才一致。
   不要为端侧另写一套工具实现。
6. **工具抛异常 → return false**，让云端重试，别把半截结果丢给用户。

---

## 10. 全部跑通后的下一步（不在本次任务内）

- 端侧结果要不要让云端总结一次？（第一版：不总结，格式化成一句话）
- 助理 / Agent 双模式的 UI 开关（现在是 deep-link）
- 端侧覆盖更多工具（当前只有 9 个关键词组，命中率有限）
- 端侧不可用时的降级提示（当前是静默转云端）

## 11. 本次调试结果（2026-11-13 13:55）

- 设备：`192.168.2.32:5555`（ELS-AN00），APK `lastUpdateTime=2026-11-13 13:51:55`。
- `shell echo OK` → 端侧接管，回复 `✅ shell echo OK` + `OK`，轨迹
  `handled: tool=shell_execute success=true`，未走云端。
- `hello there`（输入被 IME 打成 `helo there`）→ 轨迹 `no-rule:`，正常转云端。
- `shell rm -f /tmp/zzz` → 轨迹 `unsafe: tool=shell_execute`，转云端执行。
- 已补 `assistant_fastpath.log` 诊断，入口 / `no-rule` / `no-args` /
  `unsafe` / `tool-threw` / `handled` 均有记录。
- **修复**：新快速路径原先绕过了 `LocalAssistantRouter` 的安全校验，
  破坏性命令会被端侧直接执行；现在执行前补上 `isSafe` 与非人类信封检查。
