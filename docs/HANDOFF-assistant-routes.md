# 助理模式可配置路由与生活能力直达 — 交接文档

日期：2026-11-13（夜）
状态：主体实现完成，构建/安装成功，设备验证进行中；**尚未提交 Git**。
下一步：先排查并修复下面两个安全/参数问题，再完成剩余设备验收。

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

