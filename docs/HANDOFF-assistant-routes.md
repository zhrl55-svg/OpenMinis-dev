# 助理模式可配置路由与生活能力直达 — 交接文档

日期：2026-11-13（夜），2026-11-14 上午续
状态：主体实现完成，构建/安装成功。2026-11-14 已修复天气结果不显示、
补充语音确认、修复“提到天气就触发”的误路由；4.1 / 4.2 已定位。
下一步：完成剩余设备验收（见 §9）。

---

## 0. 2026-11-14 上午新增（先读这里）

### 0.1 天气结果不显示

现象：`weather` 命中本地规则后，助手气泡只有 `✅ Weather`，正文没有天气信息。

原因：路由改造后 `persistLocalExecution()` 只把结果写进 `tool_use` 工具块
（胶囊，需点开），气泡正文只有标题。旧版 `AssistantFastPath.formatResult`
本来会把结果拼进正文，改造时丢了。

修复：

- 新增 `assistant/execution/LocalResultFormatter.kt`：
  - 天气 JSON → 中文一句话（温度/体感/湿度/风速/降水/天气现象）；
  - 电量/存储/设备信息 JSON → 中文摘要；
  - 纯文本（shell/文件/记忆）原样显示；
  - 其他结构化 JSON 不倾倒到气泡，只留在工具块。
- `LocalExecutionResult` 增加 `answer` 字段；`persistLocalExecution()` 用
  `header + "\n\n" + answer` 作为助手正文。
- 单测：`LocalResultFormatterTest`（7 个）。

真机验证：`weather` → `✅ Weather` + `现在（09:00）：多云，-0.3°C（体感 -4.1°C）…`。

### 0.2 语音确认（用户新需求）

需求：本地写操作除确认卡外，还要语音确认；语音问是否确认，用户回答确认
后执行并清除卡片；确认卡弹出时同时播报、同时进入聆听（不必先喊唤醒词），
输入框/卡片要有动态效果。

实现：`ui/chat/voice/LocalActionVoiceConfirmer.kt`

1. 卡片一出现**立刻 TTS 播报**（短句：`要创建日程吗？请说“确认”或“取消”。`）；
2. 播报结束 + 1.5s 尾音静默后自动开麦 5s，用户直接说「确认」即可，
   不需要唤醒词；没听清会再问一次，仍不明白就把卡片留给手指；
3. 「确认 / 执行 / 好的 / yes」→ 执行并清卡片；
   「取消 / 不用 / 别 / no」→ 取消；
   同时含确认与取消词（含 TTS 尾音「确认取消」）→ 判为歧义，不执行；
4. 多候选（同名日程）只播报“请在屏幕上选择”，不做语音选择；
5. TTS/ASR 任一不可用都静默退回确认卡；TTS 不可用时仍自动开麦；
6. 执行完成后**简短播报结果**：成功「已创建日程：meeting」/「已运行命令」，
   失败「操作失败：<首行错误>」，取消「已取消」；唤醒确认的短应答缩成「好」，
   避免与结果播报抢话；
7. 动态效果：
   - 确认卡内蓝色呼吸圆点 +「正在播报确认提示… / 正在聆听：说“确认”或“取消”」；
   - 输入框复用 `holdToTalkGlow` 流光，确认期间一直亮着；
8. 会话错位修复：`LocalConfirmationBridge` 把待确认卡注册为全局回调；
   语音唤醒转写出的「确认/取消」`LocalActionSpeech.isDirectAnswer()` 命中时，
   直接落到卡片所在会话并播报「已确认/已取消」，不再发到「语音对话」会话；
   键盘/按住说话输入短「确认/取消」也会被 `ChatViewModel.sendMessage()` 拦截。

文案与回答解析在 `assistant/execution/LocalActionSpeech.kt`（纯函数，单测 9 个）。
桥的单测：`LocalConfirmationBridgeTest`（3 个，Robolectric）。

诊断轨迹新增（`assistant_fastpath.log`）：
`confirm-wait` / `voice: ...` / `confirm-input: voice-confirm|voice-cancel|touch-confirm|touch-cancel|voice-wake-*` /
`confirm-result`。

### 0.3 “提到天气就触发规则”

现象：语音里只要包含「天气」，比如「可以直接对接高德地图的天气接口」，
就命中 weather 本地规则。

修复（两层）：

- 天气规则增加问句/指令模式（今天/明天…天气、天气怎么样/多少度、
  what's the weather 等），真正的天气查询能命中模式；
- `AssistantRouter` 增加“话题提及保护”：
  - 只命中关键词、未命中模式，且句子长度 > 14、关键词占比 < 30% → 云端
    （`cloud reason=topic-mention`）；
  - 状态类能力（weather/device/media/calendar）没有自由文本槽时，出现
    讨论词（讨论/方案/接口/规则/触发/代码/实现/优化/排查/日志/设计/开发/对接）
    且长度 > 8 → 云端；
  - 带已抽取自由文本槽的能力不适用（如「找笔记 Project Alpha」）。
- `SEED_VERSION` 3 → 4；迁移只把新 patterns 合并进已有 `life.weather`，
  不覆盖用户自定义关键词。

真机验证：`we can use the weather api in the design` → `cloud reason=topic-mention`。

### 0.4 4.1 / 4.2 结论

- **4.1 创建日程绕过确认：未复现。** 用完全准确的
  `schedule a meeting tomorrow at 6pm` 在真机发送，确认卡正常弹出
  （截图见 `LocalActionConfirmDialog`，取消/执行都在）。
  此前日志中该句 2 秒后执行，最可能是人已点「执行」；没有本地确认的
  情况是**输入被 adb/调试输入丢字导致规则未命中，走了云端 Agent**
  （`schdul a meeting…` 实测复现，云端按 Agent 模式直接执行，属既有行为）。
- **4.2 `metting`：输入丢字，不是抽取器 bug。** `delete event meeting`
  正确输入时卡片正常弹出、抽取结果正确；日志里实际收到的是
  `delete event meetng`。`adb shell input text` 与 `debug.inputText`
  在本机都会偶发丢字符/空格，测试时必须先 dump 输入框核对原文。


---

## 1. 任务目标

把助理模式从“硬编码 ruleHints + 端侧模型分类”改造成：

- 本地可配置的 `AssistantRoute` 规则库，可在设置页和 `minis-config` 中增删改；
- 确定性槽位解析优先，生活指令不依赖 0.3B 模型；
- 闹钟、计时器、提醒、日历、笔记、设置、定时任务、天气、电量、媒体、联系人、打开应用可本地直达；
- 写操作必须确认，失败不自动重试；
- 助理模式默认关闭，Agent 模式行为不变。

---

## 2. 已完成内容

### P0 路由地基

- 新增 `assistant/routing/`：
  - `AssistantRoute.kt`：规则、槽位、能力、风险模型；
  - `AssistantRouteStore.kt`：`filesDir/assistant/routes.json`，原子写入、`StateFlow`、增删改/恢复默认/导入导出；
  - `AssistantRouteMatcher.kt`：NFKC/大小写/全半角/空白归一化，最佳匹配排序；
  - `AssistantRouter.kt`：统一返回 `RouteDecision.Local` 或 `RouteDecision.Cloud`；
  - `DefaultAssistantRoutes.kt`：44 条中英文默认规则，按类别组织；
  - `LocalCapabilityRegistry.kt`：本地能力/动作/槽位/风险注册表；
  - `SlotExtractor.kt`：确定性槽位抽取；
  - `ChineseTimeParser.kt`：中文相对/绝对时间、时长、重复规则；
  - `AssistantDiagnostics.kt`：JSONL 决策日志；
  - `AssistantRouteMatcher` 等单元测试。
- `AssistantRoutesCollection.kt`：`basePath = "assistantroutes"` 配置集合。
- `LocalAssistantPrefs` / `AssistantFastPath` 已统一助理开关，旧 `assistant_fast_path` 迁移到 `local_assistant`。

### P1/P2 生活能力

- `assistant/execution/LocalCapabilityExecutor.kt`：
  - 日历 list/create/update/delete/freebusy；
  - 闹钟 set/timer/open；
  - 提醒 schedule/list/pending/cancel；
  - 笔记 create/search/list/delete；
  - 设置 open/set（Shizuku 优先，失败打开设置页）；
  - 定时任务 create/list/cancel；
  - 天气、设备状态、媒体、联系人、打开应用。
- `CalendarCandidateMatcher.kt`：日程标题解析、单选/多候选处理。
- `assistant/notes/NotesRepository.kt`：
  - Markdown 文件存 `filesDir/assistant/notes`；
  - `index.json` 索引；
  - 内容原文保存，不做模型改写。
- 快速路径：
  - `ChatViewModel.runLocalAssistantFastPath` 不再先检查模型；
  - 读操作直接执行，写操作弹 `LocalActionConfirmDialog`；
  - 本地执行写 `tool_use` + `tool_result` 块；
  - 读失败允许云端重试，写失败不自动重试。
- 设置页：
  - 设置 → Agent Runtime → 端侧助理开关；
  - “助理直达规则”入口，路由 `settings/assistant-routes`；
  - `AssistantRoutesScreen`：分组列表、编辑、测试面板、导入导出、恢复默认、诊断入口；
  - deep link：`minis://settings/assistant-routes`。

### 构建与安装

- 构建命令成功：
  ```powershell
  $env:JAVA_HOME='D:\Android\jdk-17'
  $env:ANDROID_HOME='D:\Android\Sdk'
  $env:ANDROID_SDK_ROOT='D:\Android\Sdk'
  .\gradlew.bat :app:assembleGithubPerf --no-daemon
  ```
- APK：
  `src/android/app/build/outputs/apk/github/perf/app-github-perf.apk`
- 设备：华为 `MDX0220A21035172`
- 包名：`com.openminis.app.dev`
- 启动 Activity：`com.openminis.app.dev/com.openminis.app.MainActivityIconAuto`
- 已覆盖安装。应用 **非 debuggable**，`run-as` 不可用，无法直接读取应用私有目录。

---

## 3. 已通过的设备验证

- 设置页可见端侧助理总开关、助理直达规则入口；44 条规则按分类显示。
- 规则测试面板：
  - 输入 `明天8点提醒我开会`
  - 命中 `reminder/schedule`
  - 参数：`at=2026-11-14T08:00:00`，标题“开会”
  - 显示“本地并需确认”
- 笔记：
  - 创建、搜索、删除全链路成功；
  - `tool_use` / `tool_result` 块正常；
  - 测试笔记已删除。
- 计时器：
  - `1分钟计时器` 直达系统 Clock 并开始倒计时；
  - 测试计时器已停止并关闭。
- 设备/天气/设置：
  - `battery level` 本地直达成功；
  - `weather` 本地直达成功；
  - `open wifi settings`（输入有笔误仍命中）打开系统 WLAN 页。
- 日历：
  - 只读 `calendar` 查询本地执行成功（创建前为空）。

此前助理专项测试：87 个测试通过（`com.openminis.app.assistant.*`、`AssistantRoutesCollectionTest`、`AlarmTimeParserTest`）。

---

## 4. 两个未解决问题（明天优先处理）

### 4.1 创建日程疑似绕过确认（安全优先）

复现：

```text
schedule a meeting tomorrow at 3pm
```

聊天直接出现 `Create calendar event: meeting` 并执行成功，**没有出现确认卡**。

已产生的测试日程：

- `id = 251`
- `title = "meeting"`
- `start = "2026-11-14T15:00:00+08:00"`
- `end = "2026-11-14T16:00:00+08:00"`

当前代码静态检查结果：

- `LocalCapabilityRegistry` 中 `calendar/create` 已是 `readOnly = false`；
- 默认规则 `life.calendar-create` 未显式覆盖 `confirm`，应为 `ConfirmMode.WRITE`；
- 按 `AssistantRouter` 逻辑，`WRITE + !readOnly` 应得到 `needsConfirmation = true`。

因此**不要先盲目加确认参数**。明天先判定这句话到底走的是本地快速路径还是云端 Agent：

1. 清空 logcat 后重发，抓：
   ```powershell
   adb -s MDX0220A21035172 logcat -c
   adb -s MDX0220A21035172 logcat -v time | Select-String "assistant-routes|AssistantDiagnostics|LocalCapability"
   ```
2. 若日志显示 `route: ... local calendar/create` 但无 `confirmation:`，检查：
   - `routes.json` 是否为旧版本种子，`confirm` 是否为 `NEVER`；
   - `ChatScreen` 是否因弹窗状态未刷新而跳过确认卡；
   - `awaitLocalActionConfirmation` 是否被其他状态提前完成。
3. 若日志显示 `cloud reason=...` 或根本没有本地日志，说明该句未命中本地规则，是云端 Agent 自己执行的：
   - 这属于 Agent 模式既有行为，不应为了本地确认去改 Agent；
   - 需要调整默认规则/匹配分数，让该说法稳定命中 `life.calendar-create`；
   - 或明确验收标准：只有本地直达写操作强制确认。
4. 重测时用点击发送按钮，不用回车，排除 Enter 键与确认按钮冲突：
   - 键盘隐藏时发送按钮约 `(1055, 2460)`；
   - 键盘显示时约 `(1055, 2390)`；
   - 输入框约 `y = 2350`。

### 4.2 删除日程的查询词疑似被破坏

复现：

```text
delete event meeting
```

未弹确认，直接报错：

```text
没有找到匹配“metting”的日程，未执行。
```

注意错误里是 `metting`（少一个 e），而聊天气泡显示 `delete event meeting`。

当前 `SlotExtractor.extractCalendar("delete")` 逻辑：

- `numberIdRegex` 先取 id；
- `calendarTitle(text)` → `stripTimeExpressions` → `extractAfterTrigger`；
- 触发词按长度降序，`delete event` 命中后应得到 `meeting`。

静态看不应产生 `metting`，所以先不要改代码，明天先确认事实：

1. 用 UI XML 读取真实错误文本，避免截图误读：
   ```powershell
   adb -s MDX0220A21035172 shell uiautomator dump /sdcard/x.xml
   adb -s MDX0220A21035172 shell cat /sdcard/x.xml
   ```
2. 检查输入框实际发送内容：`adb shell input text` 可能丢字符，尤其英文长串。
3. 若确认输入就是 `meeting`、抽取结果却是 `metting`，再检查：
   - `AssistantRouteMatcher.normalizeTextPreservingCase` 是否被误用于内容槽；
   - `ChineseTimeParser.stripTimeExpressions` 是否误删字符；
   - `calendarTitle` 的连续 `replace` 是否误伤单词；
   - 规则测试面板显示的参数是否同样错误（测试面板走同一抽取器，可快速定位）。
4. 修复后必须验证：
   - 能匹配 `id=251` 的 `meeting`；
   - 删除前弹确认卡；
   - 确认后删除成功；
   - 清理测试日程。
5. 再创建两个同名短时日程，验证多候选单选流程，完成后全部清理。

---

## 5. 明天执行顺序

1. **抓日志定位 4.1**：确认 `schedule a meeting tomorrow at 3pm` 是本地还是云端路径。
2. **修复创建日程确认**：按日志结论修规则/弹窗/确认状态，不做无依据改动。
3. **抓 UI XML 定位 4.2**：确认输入与错误文本。
4. **修复删除查询词**：若确为抽取器问题，补单元测试后再改。
5. 重新编译并跑助理专项测试：
   ```powershell
   $env:JAVA_HOME='D:\Android\jdk-17'
   $env:ANDROID_HOME='D:\Android\Sdk'
   $env:ANDROID_SDK_ROOT='D:\Android\Sdk'
   .\gradlew.bat :app:compileGithubDebugKotlin --no-daemon
   .\gradlew.bat :app:testGithubDebugUnitTest --tests 'com.openminis.app.assistant.*' --tests 'com.openminis.app.config.AssistantRoutesCollectionTest' --tests 'com.openminis.app.sandbox.offload.AlarmTimeParserTest' --no-daemon
   ```
6. 重新构建并安装：
   ```powershell
   .\gradlew.bat :app:assembleGithubPerf --no-daemon
   adb -s MDX0220A21035172 install -r app\build\outputs\apk\github\perf\app-github-perf.apk
   ```
7. 设备重测创建/删除/多候选确认，清理 `id=251` 及所有测试日程。
8. 验证助理关闭后消息走云端，无本地确认卡、无本地 `tool_use` 块。
9. 验证规则页导入/导出/恢复默认/诊断入口（上次滚动时误触编辑弹窗，尚未完整验证）。
10. 验证 `minis-config`：
    ```text
    minis-config get assistantroutes
    minis-config add assistantroutes '<json>'
    minis-config set assistantroutes.<id>.keywords '["日程","会议"]'
    minis-config remove assistantroutes <id>
    ```
11. 最后运行：
    ```powershell
    git diff --check
    git status --short
    ```
12. 整理改动说明并汇报；**用户未要求提交，不要 commit，不要切分支**。

---

## 6. 设备交互备忘

- 所有 adb / gradle 命令需在 PowerShell 中执行，并按当前环境要求申请 escalated 权限。
- 中文输入：设备已开启拼音，可用 `adb shell input text <拼音>` 后点击候选词。
- 英文空格用 `%s`，推荐嵌套引号：
  ```powershell
  adb -s MDX0220A21035172 shell "input text 'open%swifi%ssettings'"
  ```
- 工具结果 sheet 会遮挡输入区：先点“关闭工作表”或按 Back。
- 新建聊天 deep link：
  ```powershell
  adb -s MDX0220A21035172 shell am start -a android.intent.action.VIEW -d 'minis://action/new_chat' -p com.openminis.app.dev
  ```
- 规则页 deep link：
  ```powershell
  adb -s MDX0220A21035172 shell am start -a android.intent.action.VIEW -d 'minis://settings/assistant-routes' -p com.openminis.app.dev
  ```

---

## 7. 关键文件

```text
src/android/app/src/main/java/com/openminis/app/assistant/routing/
src/android/app/src/main/java/com/openminis/app/assistant/execution/LocalCapabilityExecutor.kt
src/android/app/src/main/java/com/openminis/app/assistant/execution/CalendarCandidateMatcher.kt
src/android/app/src/main/java/com/openminis/app/assistant/notes/NotesRepository.kt
src/android/app/src/main/java/com/openminis/app/config/collections/AssistantRoutesCollection.kt
src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt
src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt
src/android/app/src/main/java/com/openminis/app/ui/chat/LocalActionConfirmDialog.kt
src/android/app/src/main/java/com/openminis/app/ui/settings/AssistantRoutesScreen.kt
src/android/app/src/main/java/com/openminis/app/sandbox/offload/AlarmOffloadHandler.kt
src/android/app/src/main/java/com/openminis/app/sandbox/offload/AlarmTimeTarget.kt
src/android/app/src/test/java/com/openminis/app/assistant/
```

---

## 8. 工作区状态

- 已修改 16 个既有文件（路由、快速路径、设置入口、字符串等）。
- 新增未跟踪目录/文件：
  - `assistant/`
  - `AssistantRoutesCollection.kt`
  - `AlarmTimeTarget.kt`
  - `LocalActionConfirmDialog.kt`
  - `AssistantRoutesScreen.kt`
  - 助理相关测试。
- 工作区还有若干与本次任务无关的既有未跟踪文件（`models/`、`.tmp_*`、旧 patch、截图等），**不要清理**。
- 暂无 Git 提交。


---

## 9. 2026-11-14 上午状态与剩余验收

当前 APK：`src/android/app/build/outputs/apk/github/perf/app-github-perf.apk`
（2026-11-14 09:2x 构建，已覆盖安装到 ELS-AN00 `192.168.2.32:5555`）。

已完成的真机验证：

- `weather` → 本地执行，气泡显示完整天气摘要（不再是单词 Weather）。
- `schedule a meeting tomorrow at 6pm` / `delete event meeting` →
  确认卡正常弹出。
- 语音确认：卡片出现后 `tts-ready=true` 立即播报，播报结束自动开麦；
  说「确认」后执行并清卡片，随后播报「已创建日程：meeting」
  （轨迹 `confirm-wait` → `tts-ready` → `voice: listen` → `voice: heard 确认`
  → `handled` → `voice: announce`）。卡片显示
  「正在播报 / 正在聆听」呼吸提示，输入框流光同步点亮。
- 唤醒路径：待确认卡注册到 `LocalConfirmationBridge`；唤醒转写结果先问桥，
  命中「确认/取消」就落回卡片所在会话（单测覆盖，真机待用户复测）。
- 助理关闭（`minis://action/assistant_mode?on=false`）→ 轨迹 `skip: disabled`，
  `weather` 走云端，无本地 tool_use、无确认卡；之后已恢复开启。
- `we can use the weather api in the design` → `cloud reason=topic-mention`。
- 测试日程（id 251/252/253/254/255）已全部清理。

2026-11-14 下午补齐（原“尚未完成”1/2/4/5，均已在真机验证）：

1. 规则页工具区已验证：导入 JSON / 导出 JSON / 恢复默认 / 诊断四个入口
   都能打开对应弹窗；诊断显示未匹配候选与最近命中记录。
2. `minis-config` 写操作已验证：`set`（数值字段）、`set …append`（新增
   路由）、`set …remove`（删除路由）都会弹出「确认更改」对话框，应用后
   写入成功并生成审计记录（`audit-list` 可见），`get` 读回一致。
   注：本版本 CLI 的 `get` 需要完整字段路径（`get <topic>` 会报
   unknown_path），用 `topic-help <topic>` 查路径。
3. 语音确认的 TTS 可听性：已由用户复测通过（同日语音确认链路整体验收）。
4. 多候选删除的语音选条：已实现——TTS 念「有 N 个匹配：第一个 xxx；
   第二个 yyy。请说第几个，或说取消」，支持「第一个/第2个/选 2/
   first/number two/直接说标题」，选完即执行；单测覆盖
   `LocalActionSpeech.choice` / `choicePrompt`。
5. 端侧天气失败提示：读失败转云端前，聊天里插入一条灰色系统提示
   「本地天气查询失败，已转由云端继续：…」，不再静默。

测试注意（2026-11-14 实测）：

- `adb shell input text` 和 `debug.inputText` 在本机都会偶发丢字符/空格；
  发送前必须用 uiautomator dump 核对输入框原文。
- `minis://action/new_chat` 在聊天页已打开时可能只投递到当前实例、
  不真正新建会话；测试写操作会混进用户当前会话。
- 日历测试数据可用 `debug.shellExecute` 跑
  `android-calendar list/delete` 清理（见 §6 的 RPC 端口转发）。

## 10. 2026-11-14 下午：音乐播放器（新功能）

当前 APK：`src/android/app/build/outputs/apk/github/perf/app-github-perf.apk`
（2026-11-14 12:4x 构建，已覆盖安装到 ELS-AN00 `192.168.2.32:5555`）。

### 10.1 需求

用户要求重做 app 里的音乐播放：「简单漂亮」，播放时自动出现在聊天页顶端，
音源仍是 buntu 上自建的 NetEase API，逻辑参考 pilot（`D:\phone agent`）。
后续补充：取消封面图；样式参考 pilot；播放条底色白色 90% 透明；
设置里加扫码登录；确认卡只对破坏性操作弹出。

### 10.2 实现

> 2026-11-14 下午第二轮：按用户要求**整体移植 pilot 的播放器**——
> 引擎生命周期、推荐/队列下拉、紧凑播放条样式全部对齐（不再有全屏 sheet）。

新增（全部在 `com.openminis.app.media.music` / `ui.media.music`）：

- `MusicPreferences.kt` — 音源地址 + cookie 存本地。
  默认 LAN `http://192.168.2.102:3001`，备用 tailnet `http://100.95.180.77:3001`。
- `NeteaseMusicApi.kt` — `/search`、`/top/song`、`/song/url`（不要用
  `/song/url/v1`，会触发 -460）、`/lyric`、扫码登录三件套
  （`/login/qr/key` → `/login/qr/create` → `/login/qr/check`）、`/login/status`。
  请求经 `NetworkHttp.client("music", …)` 走 app 内嵌 Tailscale 路由：
  在家走 LAN，不在家走 tailnet，两个地址都实测可用。
- `MusicPlayerController.kt` — **移植 pilot 的播放逻辑**：一首歌一个
  ExoPlayer，放完/出错/停止立即 `release()`（空载零占用）；队列、顺序/
  列表循环/单曲循环、随机+历史回退、连续失败自动跳过；播放地址每首现取
  （URL ~20 分钟过期），歌词同时解析。`playQuery()` 的命令分词和 pilot
  一致：最近 / 每日推荐 / 我喜欢 / 新歌 / 飙升 / 热门 → 对应列表，其余
  走搜索（含「周杰伦的稻香」= 稻香 by 周杰伦 的匹配打分）。
- `MusicHistoryStore.kt` — 本地最近播放 30 首（网易云 `/user/record` 为空
  时兜底），移植自 pilot。
- `NeteaseMusicApi` 新增 `/user/record`、`/playlist/detail`（榜单 id：
  3778678 热歌 / 3779629 新歌 / 19723756 飙升）、`/recommend/songs`、
  `/likelist` + `/song/detail`、`loginProfile`（uid）。
- `ui/media/music/MusicPlayerUi.kt` — **pilot 的紧凑播放条 1:1 移植**：
  第一行 音阶 + 曲名·歌手 + 「推荐 ▾」（最近播放/热歌榜/每日推荐/我喜欢的/
  新歌榜/飙升榜）+ ×；第二行 同步歌词 + 时间；2dp 进度；第三行
  随机 / 上一首 / 播放 / 下一首 / 模式胶囊 + 「候选 N/M ▾」（队列下拉，
  点条目直接切歌）。样式用 pilot 的渐变卡（primary 28% → surfaceVariant →
  surface）。
- `ui/media/music/MusicSettingsSheet.kt` — 设置 › 音乐：扫码登录 + 音源地址。

接入：

- `ChatScreen`：播放条以 `AnimatedVisibility` 悬浮在消息列表上方
  （`zIndex(2f)`）—— 放 topBar 里时后面没有内容，透明效果看不出来；
  悬浮后消息从下面滚过才有层次感。全屏 sheet 与菜单里的「音乐」入口已移除，
  交互回到 pilot 的「播放条 + 下拉」。
- `LocalCapabilityRegistry` / `DefaultAssistantRoutes` / `SlotExtractor`：
  `media.play` 增加可选 `query` 槽；新增 `life.music-play` / `next` / `previous`
  路由（锚定句式，排除「设计/接口/逻辑/播放器」等讨论词）；
  播放/暂停/下一首等媒体控制 `confirm = NEVER`。
- `LocalCapabilityExecutor`：`media` 优先驱动内置播放器，找不到再退回
  原来的 `MediaPlayerManager` 会话。工具胶囊显示真正解析出的歌名
  （「放一首周杰伦的稻香」→ `Play music: 稻香`）。

### 10.3 已解决的坑

- **所有请求返回 null**：`client.newCall().execute().use { … return@use json }`
  的 `use` 值被丢弃，整段 `withContext` 返回 null。改成 `return@withContext json`。
- **扫码二维码不显示**：data URI 交给 Coil 渲染不出来。改为
  `Base64.decode` + `BitmapFactory` 直接生成 ImageBitmap（`decodeQrBitmap`）。
- **`放一首周杰伦的稻香` 搜成整句**：控制器先搜整句，再搜「的」后的歌名，
  按标题/歌手匹配打分；原唱周杰伦在网易云已下架，搜到的是翻唱
  （如「稻香(治愈版)」），这是曲库现状，不是解析 bug。
- **播放条透明看不出来**：见上，浮动在消息列表上方。
- **焦点回不到输入框**：真机复测当前构建，播放中点输入框键盘正常弹出并
  可输入（`mInputShown=true`）；播放条已移出 topBar，不再挤占布局。

### 10.4 确认策略变更（用户要求）

只有破坏性操作才弹确认卡：

- `AssistantRouter`：`ConfirmMode.WRITE` → 仅 `CapabilityRisk.HIGH`；
  `ALWAYS` 强制、`NEVER` 永不确认。
- 时间歧义不再弹卡（「记笔记：明天买牛奶」里的「明天」不是时间）。
- `file_write` / `file_edit` 风险提升为 HIGH（会覆盖文件）。
- 测试改为 `destructive actions require confirmation` /
  `everyday commands run without confirmation`，全部通过；
  真机 `暂停播放` → `handled locally: media/pause success=true`，无卡片。

### 10.4.1 确认卡自动退出（用户要求）

- 卡片带倒计时（右下角「N 秒后自动取消」），到时自动收起并回一句
  「本地操作等待确认超时，已取消」；语音确认的麦克风同时停止。
- 超时 15 秒（`ChatViewModel.LOCAL_CONFIRM_TIMEOUT_MS`；用户反馈 30 秒太长）。
- 倒计时属于卡片本身（`LocalActionConfirmation.timeoutSeconds`），
  换卡会重置。

### 10.5 单测

- `NeteaseMusicApiTest`：两种歌曲 JSON 形状、歌词解析（作词/作曲行过滤）。
- `AssistantRouteCorpusTest`：新增音乐路由样例与「音乐播放逻辑」等
  反例；确认策略用例。
- 全量：`:app:testGithubDebugUnitTest --tests 'com.openminis.app.assistant.*'
  --tests 'com.openminis.app.media.*'` 108 个用例通过。

### 10.6 待用户确认/后续

1. 起床/离家场景切到 tailnet 时音源是否顺畅（代码已走路由，实测
   `debug.net.httpGet` tailnet 200）。
2. 原唱版权：周杰伦等歌手在网易云无版权，只能放翻唱；如需原唱要换音源。
3. 播放器没有后台常驻/通知栏控制（进程被系统回收时停止）。

---

## 11. 新闻与 A 股行情（P0，2026-11-14）

### 11.1 新增工具

- `tools/NewsSearchTool.kt` — `news_search`
  - Google News RSS，24 小时优先、结果不足 5 条回退 7 天；
  - 输出 5 条标题 + 来源 + 时间 + 摘要，**不抓正文**（正文交给 `browser_use`，
    避免重复实现 HTML 提取）；
  - 解析器是纯 JDK DOM，可在 JVM 单测里直接测。
- `tools/StockQuoteTool.kt` — `stock_quote`
  - 新浪 `hq.sinajs.cn`，必须带 `Referer`，响应按 **GBK** 解码；
  - 支持上证/深证/创业板/沪深300 和「大盘」等泛称；
  - 个股、基金、ETF、美股/港股、上证50/中证500 等明确返回「暂不支持」，
    不再像 pilot 的 StockTool 那样静默回退三大指数。

两个工具都已加入 `AgentTools.makeAgentTools()`，所以云端 Agent 和定时任务
可以直接调用，不需要 API Key。

### 11.2 本地助理路由

- `LocalCapabilityRegistry`：`news_search/search`、`stock_quote/quote`（只读）。
- `DefaultAssistantRoutes`：新增 `info.news`（priority 168）和
  `info.stock`（158）；**`SEED_VERSION` 升到 6**，已有安装通过 migrate 自动补齐。
- `SlotExtractor`：两个能力的 `query` 都是**可选槽**，直接把用户原句交给工具
  归一化；因此端侧快路径不需要调用 0.3B 模型。`missing` 为空即直接执行。
- `LocalActionSpeech` / `AssistantFastPath` / `ChatToolFormatting`：补了
  「查询新闻」「查询行情」、工具图标和英文名。

### 11.3 路由边界（防止互相抢词）

- 「每天帮我总结新闻」仍走 `scheduled_task/create`（news 路由 exclude 每天/每周/定时）。
- 「股票新闻 / 财经新闻 / 股市消息」走 `news_search`，stock 路由 exclude 新闻/消息。
- 「上证指数现在多少 / 今天大盘怎么样」走 `stock_quote`。
- 「指数基金怎么买」「新闻学专业怎么样」「我们在讨论股票接口的方案」留在云端。
- 「附近有超市吗」仍走已有的 `amap_search`，本次没有新增任何 POI/导航能力。

### 11.4 测试

- `NewsSearchToolTest`：查询归一化、RSS 解析、HTML 清洗、时间解析。
- `StockQuoteToolTest`：GBK 响应解析、方向和点数格式化、指数选择、个股拒绝。
- `AssistantRouteCorpusTest`：新增 6 条新闻/行情样例 + 4 条反例。
- `SlotExtractorTest`：确认两个能力的 query 可选且不需要模型。
- 已通过：
  `AssistantRouteCorpusTest`、`SlotExtractorTest`、`AssistantRouteStoreTest`、
  `AssistantRoutesCollectionTest`、`AgentToolSwitchTest`、
  `SubAgentToolSchemaTest`、`NewsSearchToolTest`、`StockQuoteToolTest`。
- `:app:assembleGithubDebug` / `:app:assembleGithubPerf` 成功。
- 真机（ELS-AN00）：
  - `今天有什么科技新闻` → `news_search` 1.6s，日志 `voice: announce 找到 5 条新闻。第 1 条…`
  - `上证指数现在多少` → `stock_quote` 131ms，日志 `voice: announce 上证指数 3634.35 点，跌 0.59%`

### 11.5 已知限制 / 后续

1. Google News RSS 在纯国内网络可能不可达；失败时返回明确错误，不静默当成
   「没有新闻」。后续可把 RSS base 做成可配置或加备用源。
2. 个股行情未做代码解析（新浪 suggest）；当前明确拒绝，P1 再接。
3. 本地快路径现在会播报 `spokenSummary`：新闻念前 3 条标题（带来源），
   行情念全部返回指数的「名称 点数，涨跌」，由
   `LocalActionVoiceConfirmer.announceSummary()` 走 TTS；气泡仍显示完整结果。
   2026-11-14 下午起该播报改走 `ReadAloudPlayer`，见 §12。
4. 语音唤醒（`WakeConversation`）仍走 `HeadlessChatRunner`（headless=true），
   会绕过本地快路径；它播报的是云端模型的最终回复。若要让唤醒词也用本地
   新闻/行情工具，需要给 headless 路径加显式的本地快速路径开关（P2）。

---

## 12. 本地工具语音播报：三层策略 + 输入框小喇叭/胶囊（2026-11-14 下午）

需求：新闻/行情之外的工具也要有语音反馈；播报要复用云端回复朗读时的输入框
小喇叭和浮动胶囊动画，支持手动停止，也支持语音关键词（「停一下」等）打断。

### 12.1 三层播报策略

`LocalActionSpeech.shouldSpeakCompletion(capability, action, readOnly)`：

1. **内容型只读结果** → 播 `spokenSummary`（`ToolExecutionResult` 或
   `LocalSpokenSummary` 生成）。覆盖：
   - `news_search`：前 3 条标题 + 来源；
   - `stock_quote`：「名称 点数，涨/跌 x%」；
   - `amap_search`：「找到 N 个“query”：最近 3 个名字 + 距离」；
   - `file_read`：「已读取 <文件名>，共 N 行」/ 二进制说明；
   - `weather`/`device`：`LocalResultFormatter.answer` 的一句中文；
   - `calendar list`、`calendar freebusy`（第一个空闲时段）、`reminder
     list/pending`、`note search/list`、`contacts search`、`media status/list`、
     `scheduled_task list`、`memory_get`：条数 + 前 2~3 个名字。
2. **改变状态的动作** → 播通用完成句「已<动作>：<目标>」（无需确认卡也播）。
   目标参数链扩展为 title → name → app → id_or_title → label → url → prompt → query。
3. **媒体控制与读图保持安静**（`media` / `read_image`）：播放器 UI 已经反映新
   状态，图片内容不适合一句话朗读。

`LocalSpokenSummary`（`assistant/execution/`）只放纯函数：
`forCapability()` 处理生活能力，`forTool()` 处理 `memory_get` 等工具；
JSON 数组/对象优先找 `items/notes/reminders/events/contacts/sessions/results/
tasks/data`，取 `title/name/label/content/text` 作为条目名；失败、空结果分别
返回空串或「没有找到结果。」，最终统一切到 220 字以内。

### 12.2 复用云端朗读的播放器与胶囊

`LocalActionVoiceConfirmer` 的结果播报改用独立的 `ReadAloudPlayer`，不再用确认
提示的 `TextToSpeechManager`：

- `ReadAloudPlayer.speakLocal(text)`：新增入口，**不受「朗读回复」开关限制**
  （语音问的问题必须语音答），但仍尊重静音、麦克风占用与文本清洗；
- 播报前 `VoiceOutputState.registerPlayer(reader)`、播完
  `unregisterPlayer`，所以 `VoiceOutputState.isSpeaking` / 输入框
  `ComposerSpeakingButton` / `SpeechPlayerCapsule` 都按云端回复同样的动画显示；
  只有播报期间占据胶囊，不会永久抢走回复通道的播放器；
- 手动停止：胶囊关闭/静音、输入框小喇叭、发送键旁停止按钮都走
  `VoiceOutputState.activePlayer?.stop()`，行为与云端朗读一致；
- 新增 `VoiceOutputState.stopAllPlayers()`，供语音打断一次停掉所有播放器。

**播报结束后小喇叭仍闪的修复**：`ReadAloudPlayer` 原先在清
`VoiceOutputState.isSpeaking` 之前才检查 `ownsCapsule()`。播报协程在
`Dispatchers.Main.immediate` 上等待 `isSpeaking`，`_isSpeaking` 翻 false 时
collector 可能**内联恢复**并先执行 `finally { unregisterPlayer() }`，于是
`ownsCapsule()` 已经变 false，共享镜像没被清掉 —— 音频结束但输入框小喇叭
一直闪。现在 `clearSpeakingState()` 在翻转 `isSpeaking` **之前**采样所有权，
再清 `isSpeaking/isSynthesizing/currentSpeechText`；`stop()` 与
`suspendForCapture()` 共用同一入口。

### 12.3 语音关键词打断（空闲态也生效）

`WakeWordService`：

- 取消「正在播报就停引擎」的 gate；播报期间引擎继续听，`gateLoop` 安装
  barge-in VAD（`installBargeInVad`）；
- `onWakeHit` 在 `VoiceOutputState.isSpeaking` 时忽略唤醒命中（自己的声音），
  避免自唤醒，也避免误开抑制窗口；
- `onBargeInSegment` 放宽到 **IDLE + 正在播报**：转写后用
  `VoiceOutputState.currentSpeechText`（当前播放句，`ReadAloudPlayer` 发布）
  做回声剔除，命中 `WakeStopPhrase`（停一下/别说了/安静…）就
  `stopSpeakingNow()`；
- `stopSpeakingNow()` 现在先 `VoiceOutputState.stopAllPlayers()`，再清
  `isSpeaking` / `currentSpeechText`，所以云端回复、选区朗读、本地播报都能被
  「停一下」打断。

### 12.4 测试与真机验证（ELS-AN00 / MDX0220A21035172）

单测：

- `LocalSpokenSummaryTest`（11）：天气/设备原句、日程列表、freebusy、
  联系人、空结果、media status、写入/失败静默、memory_get、长度上限；
- `LocalActionSpeechTest`（+2）：目标参数链（label/url/prompt）、三层策略；
- `FileReadToolSpeechTest`（2）：行数摘要、二进制不朗读正文；
- 原有 `NewsSearchToolTest` / `StockQuoteToolTest` / `AssistantRouteCorpusTest`
  / `SlotExtractorTest` / `LocalResultFormatterTest` 全绿。

真机：

- 「今天有什么科技新闻」→ 结果后出现**输入框小喇叭 + 浮动胶囊**，播报前 3 条；
- 播报中点输入框小喇叭 → 立即停止，胶囊消失（日志 `ReadAloudFocus abandon`、
  `Minis.VoiceWake: 停止播报`）；
- 播报中对手机说「停一下」（PC 扬声器播 SAPI 语音）→
  `检测到语音停止指令「停一下」，中止播报` + `VoiceOutputState.stopAllPlayers()`
  → 胶囊消失；期间 VAD 日志显示 `忽略播报回声「新浪财」`，证明回声剔除生效；
- 「记个笔记：明天买牛奶」→ 无需确认卡，仍播「已新建笔记：明天买牛奶」，
  胶囊同步显示；媒体控制按策略保持安静；
- 自然播完后 12s 抓 `debug.viewTree`：不再存在「停止播报」节点（修复前存在）。

---

## 13. 长语音只进去前半段：按静音断句，不再固定限时（2026-11-14 晚）

现象（用户反馈）：语音输入长句时像被限时，只有前半段进入识别结果；
用户建议「应该检测中间的静音时长」。

根因有两层，都在共享的 `WakeEnergyVad` / 会话窗口：

1. `WakeEnergyVad` 默认 **600ms 静音就断句**、**15s 连续语音强制切段**。
   长听写中的自然停顿被当成句尾；每段都要重新过声纹，任何一段被拒就
   丢掉一截。唤醒对话里第一段通过后立即进入 REPLYING，后半段直接不再处理。
2. `WakeWordService` 的 `CONVERSATION_WINDOW_MS = 8s` 是**从唤醒/应答结束
   起算的墙钟**：用户连续说超过 8s，`handleConversationTick` 会
   `vad.flush()` 把没说完的半句提前送走 —— 正是「限时、只有前半段」。

修复：

- `WakeEnergyVad` 默认 `MAX_SEGMENT_MS` 15s → **60s**，并在注释里明确它只是
  安全阀，不是断句规则；barge-in 仍显式传 1.5s，行为不变。
- 请求录音（唤醒对话 `installVad()` 与按住说话 `HoldToTalkController`）：
  - `minSilenceMs = 1_200ms`：句中半秒级停顿不再断句；
  - `maxSegmentMs = 60_000ms`：连续长语音不再 15s 被切；
- `handleConversationTick`：8s 窗口改为**静音窗口**——`vad.isInSpeech == true`
  时续期，不再 flush；只有真正静音且 VAD 无缓存时才休眠。
- `HoldToTalkController` 仍逐段追加文本；更少的段 = 更少的声纹复检，
  降低「后段被声纹拒绝而丢失」的概率。

单测：`WakeEnergyVadTest`（3）——580ms 停顿不断句、15s 连续语音不切、
20s 安全阀仍生效。`:app:assembleGithubPerf` 构建并安装成功。
