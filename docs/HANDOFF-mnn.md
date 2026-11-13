# Minis 端侧模型（MNN）集成 —— 交接文档

> 面向**电脑端 agent**：从这份文档接手，不需要问人。
> 状态：2026-11-13 下午。技术前提已验证通过，接入主链路未完成。

---

## 0. 一句话现状

**Minis App 内已能跑进程内 MNN 推理**（真机实测通过：加载 0.4s、生成 1.0s、输出通顺中文）。
端侧模型文件已部署到真机、so 已进 APK、探针 deep-link 已通。
**尚未做**：把端侧模型接进对话主链路（助理模式）。

---

## 1. 目标（用户定的方案）

导航交给高德 App；本机只负责**地点查询**（已用 Web API 完成，见 §7）。
重点是完善**语音 + 助理功能**。建立两种模式：

- **助理模式**：规则范围内的事**端侧模型直达**；规则范围外的事**云端模型对话**
- **Agent 模式**：保持 Minis 现有架构不动

端侧模型用 **Pocket-0.3B**（MNN 格式）。

**分工已经定案（用户确认 A 方案）**：
> **端侧模型只做意图分类 + 槽位抽取，不直接生成回复。**
> 0.3B 做自由对话会明显弱于云端，用户会感知到降级。

但实测又推翻了「分类也交给端侧」的部分，见 §3。

---

## 2. 代码在哪

| 内容 | 位置 |
|---|---|
| **权威源码树** | `D:\minis-src`（Windows，git 仓库，HEAD `00dc699`，分支 main） |
| 新增源码 | `D:\minis-src\src\android\app\src\main\java\com\openminis\app\llm\mnn\` |
| MNN JNI 桥（从 pilot 移植） | `D:\minis-src\src\android\app\src\main\java\com\alibaba\mnnllm\android\llm\` |
| 原生库 | `D:\minis-src\src\android\app\src\main\jniLibs\arm64-v8a\libMNN.so` + `libmnnllmapp.so` |
| 模型文件 | `D:\minis-src\models\mnn-llm-0.3b\`（5 个文件，171MB，不入库） |
| 部署脚本 | `D:\minis-src\scripts\deploy_mnn_llm.sh` |
| 编译脚本 | `D:\minis-src\scripts\build-mnn.bat` |
| 手机端沙箱副本（手机侧才有） | `/var/minis/workspace/minis-mnn/` |

新增文件清单：
```
llm/mnn/LocalMnnModel.kt      模型管理（加载/生成/释放），进程内单例
llm/mnn/LocalLlmProbe.kt      单次推理探针，结果写外部目录
llm/mnn/LocalIntentPlanner.kt 规则选工具 + 端侧抽参数
llm/mnn/LocalIntentProbe.kt   端侧抽参的验收用例集
alibaba/mnnllm/android/llm/LlmSession.kt              ← 从 pilot 移植，包名不能改
alibaba/mnnllm/android/llm/GenerateProgressListener.kt ← 同上
```

改动文件：
```
src/android/app/build.gradle.kts   加 allAbis 开关（模拟器需要）
src/android/app/src/main/java/com/openminis/app/deeplink/DeepLinkHandler.kt   加两个 action 分支
src/android/app/src/main/java/com/openminis/app/MainActivity.kt                加两个执行分支
```

---

## 3. ★ 已验证的结论（别推翻，除非有新证据）

### 3.1 端侧模型能跑（真机 arm64）

```
ok: true
loadMs: 399          （二次加载；首次约 2 秒）
generateMs: 1008     生成 100+ 字中文，1.0 秒
output: 你好！我是一个由MNN团队开发的端侧轻量语言模型，名为MNN-Pocket-0.3B…
```

### 3.2 ★ 0.3B 不会「从列表里选」，但会「从一句话里找值」

`LocalIntentProbe` 第一轮（端侧同时负责选工具+填参数），11 条用例：

```
用例11：选对 1，漏选 7，误触发 0，正确不命中 3
端侧命中率：1/8
```

模型原始输出（关键证据）：
```
输入「运行命令 ls -la」→ {"tool":"file_read","args":{"tool_title":"读取文件","path":"/tmp/a.txt"}}
   ↑ 完全照抄 few-shot 示例
输入「执行一下 echo hello」→ {"tool":"echo",...}          ← 把命令名当工具名
输入「在终端里跑个 python 脚本」→ {"tool":"python\_run"}  ← 编不存在的工具
输入「打开网页看看这个链接」→ {"tool":"web_opening"}      ← 编工具名
输入「修改文件里的某一行」→ {"tool":"file_modifier"}      ← 编工具名
输入「读一下 /var/minis/...」→ {"tool":"file_read","args":{...,"path":"/var/minis/memory/GLOBAL.md"}}  ← 对
输入「记住我更喜欢简短回答」→ {"tool":"memory_write","args":{"tool_title":"记住我更喜欢简短回答"}}  ← 工具对但缺 content
```

**诊断**：
- JSON **格式完全正确**，字段名全对 —— **不是格式问题**
- 端侧在**照抄 few-shot 示例**，或把用户话里的词当工具名编出来
- 但**抽值很准**：`"path":"/var/minis/memory/GLOBAL.md"` 抽对了，
  `"tool_title":"执行Echohello"` 也把命令抓出来了，只是放错了字段
- 同一个用例里 **规则圈候选 8/8 全对、3/3 正确不命中、0 误触发**

### 3.3 ★ 因此改了分工（当前代码就是这个状态）

```
规则选工具（已验证 8/8 准）  →  端侧只抽参数值  →  校验必填齐全  →  执行或转云端
```

端侧 prompt 极简（~120 字），只给：
- 一句指令
- 要抽的参数名（一行）
- 一个用当前参数名写的示例
- 用户原话

`tool_title` 由规则从用户话截取，**不经模型**（模型最容易在这里抄示例把参数抽歪）。

**这一版还没跑过验收** —— 这是接手后的第一件事（§5）。

---

## 4. 构建 / 安装 / 验证 全流程

### 4.1 编译

```cmd
set "JAVA_HOME=D:\Android\jdk-17"
set "ANDROID_HOME=D:\Android\Sdk"
set "ANDROID_SDK_ROOT=D:\Android\Sdk"
cd /d D:\minis-src\src\android
call gradlew.bat assembleGithubPerf --no-daemon > D:\minis-build-out\b.log 2>&1
```

★ **必须用引号形式 `set "JAVA_HOME=..."`**。写成 `set JAVA_HOME=D:\Android\jdk-17 && set ...` 时，
`&&` 前的空格会进变量，gradlew 报 `JAVA_HOME is set to an invalid directory`。

产物：`D:\minis-src\src\android\app\build\outputs\apk\github\perf\app-github-perf.apk`

也可以用 `D:\minis-src\scripts\build-mnn.bat`（已写成独立行，无空格问题），
用 schtasks 投递可避免 SSH 断连杀编译：

```cmd
schtasks /Create /TN MinisBuildMNN /TR "C:\Users\zhrl5\build-mnn.bat" /SC ONCE /ST 23:59 /RL LIMITED /F
schtasks /Run /TN MinisBuildMNN
```

日志：`D:\minis-build-out\build-mnn.log`

### 4.2 安装

```cmd
C:\Users\zhrl5\platform-tools\adb.exe -s MDX0220A21035172 install -r app-github-perf.apk
```

★ **华为 EMUI 的 adb install 会弹确认框，没人点就自动拒绝**：
```
INSTALL_FAILED_ABORTED: User rejected permissions
```
`settings put global adb_install_need_confirm 0` 在本机无效（实测）。
→ **这一步必须人工点确认**。

### 4.3 模型部署（只需一次，已完成）

```bash
cd /d D:\minis-src && bash scripts/deploy_mnn_llm.sh MDX0220A21035172
```

模型落在 `/sdcard/Android/data/com.openminis.app.dev/files/models/mnn-llm-0.3b/`（5 个文件，171MB）。

### 4.4 触发验证

```cmd
C:\Users\zhrl5\platform-tools\adb.exe -s MDX0220A21035172 shell am start -a android.intent.action.VIEW -d "minis://action/local_intent"
```

★ **cmd 下带 `&` 的 URL 会被打散**，用 `am start` 时要转义；更省事的是从手机端发 intent。

读结果：

```cmd
C:\Users\zhrl5\platform-tools\adb.exe -s MDX0220A21035172 shell cat /sdcard/Android/data/com.openminis.app.dev/files/local_intent_probe.txt
```

另一个探针（纯推理，不做分类）：
`minis://action/local_llm?prompt=你好`
→ `/sdcard/Android/data/com.openminis.app.dev/files/local_llm_probe.txt`

---

## 4.5 ★ 已完成并验证（2026-11-13 14:50）

三项真机验证全部通过，**不用再测**：

| 场景 | 输入 | 结果 |
|---|---|---|
| 端侧查周边 | 「附近有超市吗」 | `✅ 查周边 超市` + 附近 3000m 内 8 个 POI，**2 秒**，诊断 `handled: tool=amap_search success=true len=338` |
| 安全闸拦危险命令 | 「运行命令 rm -rf /tmp/x」 | 诊断 `unsafe: tool=shell_execute` → 转云端 → 云端用工具卡执行 → 回复后显示「ⓘ 这条涉及高风险操作，已转云端确认」 |
| title 格式 | 同上 | 「执行」→「查周边 超市」（动作 + 关键参数） |

**测端侧的造法**（必须新开会话，别在工作会话里发）：
```sh
android-open "minis://action/new_chat"
# a11y 找 EditText 的 nodeId，然后：
android-a11y-cli input text --node <id> "附近有超市吗"   # 支持中文
android-shizuku-cli exec "input keyevent 66"            # 发送
# 查结果：
android-shizuku-cli exec "tail -3 /sdcard/Android/data/com.openminis.app.dev/files/assistant_fastpath.log"
```

诊断文件（都在 `getExternalFilesDir(null)/`，adb 可直接 cat）：
- `assistant_fastpath.log` —— 每次 fast path 的决策轨迹
- `local_intent_probe.txt` —— 13 条验收用例结果
- `local_llm_probe.txt` —— 单次推理结果

## 5. 接手后的第一件事：跑新验收

**当前代码（规则选工具 + 端侧抽参数）从未跑过验收**，上一轮通过的是旧版。

1. 编译 + 安装（§4.1 / §4.2）
2. 触发 `minis://action/local_intent`
3. 读 `local_intent_probe.txt`
4. **验收标准**（写在 `LocalIntentProbe.kt` 里）：
   - 规则选对 **13/13**（13 条用例，其中 9 条期望命中、4 条期望不命中）
   - 期望不命中的 **0 误触发**
   - 端侧抽参：抽出的参数值包含期望关键词（不要求逐字相等）

**若抽参不达标，按这个顺序调**（每改一次要重新编译安装，约 2 分钟 + 人工点确认）：
1. 看 `原始输出` 那行 —— 模型到底吐了什么
2. prompt 加/改 few-shot 示例（示例必须用**当前要抽的参数名**，别写死别的工具）
3. 参数名给中文提示（`command` → `command（要执行的命令）`）
4. 仍不行就换 0.6B（pilot 的 `files/models/mnn-llm/`，335MB，设备上已有一份 0.6B；
   改 `LocalMnnModel.MODEL_DIR_NAME` 即可，或按 pilot 的做法目录名 `mnn-llm-0.3b` 优先、
   `mnn-llm` 兜底）

---

## 6. 接入主链路（还没做）

**接入点**：`ChatViewModel.executeTool(name, argsJson, toolId, toolBlocks, assistantId, currentText)`
（`src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt` 约 14155 行）

**大致形状**：在 agent loop 入口，助理模式下先问一次端侧：
```
规则命中？
  ├─ 否 → 走现有云端链路（不动）
  └─ 是 → 端侧抽参数
            ├─ 必填齐全 → executeTool(...) → 出结果 → 结束（不调云端）
            └─ 缺参数   → 走现有云端链路（不动）
```

**注意**：
- 工具执行涉及 `toolBlocks` / `assistantId` / `currentText` 等上下文参数，
  直接复用 `executeTool` 最省事，但要先搞清楚这几个参数从哪来。
- 端侧执行完要不要让云端总结？**第一版不做**（单步任务端侧直接出结果），
  多步任务仍走云端。
- 模式开关（助理 / Agent）需要加 UI + 配置项，Minis 已有 `minis-config` 的
  `defaults.agentLoopEntries` 等机制可参考。

---

## 7. 已完成的相关工作（同一批，别重复做）

### 高德地点查询已改成 Web API
`/var/minis/shared/amap-nav/amap.py`（手机沙箱内）已重写为纯查询工具：
`poi` / `near` / `where` / `which`。key 存 `amap_key.txt`（权限 600），
由 `deploy_amap_key.sh` 从 pilot 的 `D:\phone agent\local.properties` 拉取。

**实测**：手机出口 IP **在高德白名单里**（`status=1 info=OK`）。
pilot 源码注释说设备上 `INVALID_USER_KEY 10001` —— 那是走移动网络时的事。

导航不再由本机控制（交给高德 App）。

### hub 跨设备调用已打通
relay 在 buntu `/home/rl/hub-relay/dev_relay.py`（:8787）。
Windows executor：`C:\Users\zhrl5\hub\hub_executor.py`，
schtasks 名 `MinisHubExec`（ONLOGON 自启），device_id `pc-asus`。
协议细节见 `/var/minis/skills/minis-app-reference/references/hub.md`。

---

## 8. 踩过的坑（会再踩）

### 编译 / 构建
- `set JAVA_HOME=... &&` → 变量带尾随空格 → gradlew 报 invalid directory。**必须用引号形式**。
- 这台机的 **Git Bash 是 WSL 风格挂载**（`/mnt/c`），不是 MSYS（`/c`）。
  `cygpath` 不适用；`/mnt/d/x` ↔ `D:\x` 要自己转。
- `set -u` 脚本里 `$USERNAME` 未定义（Git Bash 用 `$USER`）。
- SSH 断连会杀掉前台编译 → 用 schtasks 投递。
- `dumpsys window | grep mCurrentFocus` 在这台华为机会输出**多行**，
  桌面那行排前面 → 取**最后一个**才是真焦点。

### 部署 / 验证通道
- **logcat 在 EMUI 上被丢弃**，AppLogger 又落在 `filesDir/logs`（App 私有目录，
  非 debuggable 读不到）→ 探针结果必须写 `getExternalFilesDir(null)/`。
- 沙箱的 `/sdcard` 是 scoped 投影，**看不到 adb push 进去的普通文件**；
  完整挂载在 `/mnt/androidwritable/0/emulated/0/`（但 Minis 只挂了 `backup`）。
- cmd.exe 下多行 `python -c "..."` 会被 `&` 和引号打散 → 一律写 `.py` 文件再跑。
- PowerShell 的 `&` 是调用运算符，ssh 传参时会被 cmd 先吃掉 → 写 `.ps1` 文件。

### MNN 本身
- **MNN 只发 arm64-v8a 的 .so** → x86_64 模拟器上 `dlopen` 必失败。
  模拟器只能验「装得上、启动不崩、优雅降级」，**端侧推理必须真机**。
  （`LocalMnnModel.abiSupported()` 会判定并优雅降级。）
- **JNI 桥的包名/类名不能改**：必须 `com.alibaba.mnnllm.android.llm` + `LlmSession`，
  native 方法名和参数顺序要与 so 一致。直接复用官方 so，一行 C++ 都不用写。
- **`configPath` 必须是 `config.json` 这个「文件」的路径**，不是模型目录
  （传目录报 `Module load failed for config: <dir>`）。
- **`max_new_tokens` 必须写进 init 的合并配置**，事后调 `updateMaxNewTokens` 不生效
  （否则只吐 1 个 token）。
- 模型自带 `repetition_penalty=1.0` = 不惩罚 → 小模型复读用户原话，要设 **1.15**；
  `presence_penalty` 设 0.3。
- `keep_history=false`（指令间无上下文依赖，避免 KV cache 越滚越大）。
- **`onProgress` 返回值语义是「要不要【停止】」不是「要不要继续」** → 写 true 每轮只出 1 token。
- 文本要从 `onProgress` 流式攒，不是从返回的 map 取（map 里只有性能指标）。
- Qwen3 带 `enable_thinking` → native 不套 jinja 会默认思考，23 秒才出结果 → 加 `/no_think`（降到 0.6s）。

### 性能（决定 prompt 能有多长）
pilot 实测（麒麟 990，0.6B）：**系统提示词每多 100 字就多 0.6 秒首字延迟**。
700 字 → 首字 3.5 秒；260 字 → 1.4 秒。
→ **端侧小模型的瓶颈是 prompt 长度，不是模型大小。** 现在的 prompt 压到 ~120 字。

---

## 9. 一句话提醒

**端侧模型这条路的价值不在「模型多聪明」，在「省掉一次云端往返」。**
所以判断标准是：**端侧要能稳定地处理一批高频、明确的指令**（跑命令、读文件、查周边）。
遇到需要推理和多步的，老老实实转云端 —— 那才是云端的活。
端侧这条链路**必须是可选的、失败即回退的**，不能成为单点故障。
