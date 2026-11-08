# Minis Hub 手机侧 —— 交接文档

> 2026-11-07 建立。给接下 Android 侧实现的 agent。
> 与 `HANDOFF.md`（编译安装）是两件事，本文件只管 hub。

## 0. 一句话任务

**PC 侧执行器已经跑通了真机实测，你要只做手机这一半。**

连上中继 → 拿到设备表 → 把 `hub_call` 暴露给模型 → 把审批卡接进现有 UI。
协议不动：线上格式是 nanoMuse Hub 0.31，契约在 `docs/specs/hub-contract.md`（编号即条款号）。
实现与契约冲突时改实现；契约本身错了，改契约并在 §14 记一笔，不要私自偏离。

## 1. 先读什么（半小时，别跳）

| 顺序 | 文件 | 看什么 |
|---|---|---|
| 1 | `docs/specs/hub-contract.md` §1–§10 | 权威条款。§1 威胁模型、§3 帧、§5 审批回合最关键 |
| 2 | `tools/hub/README.md` §「与契约的偏差」 | PC 侧已经有意偏离的地方，别当 bug 修 |
| 3 | `tools/hub/executor.py:311-365` `_ask()` | **审批卡真实字段**。这是唯一不会骗人的来源 |
| 4 | `tools/hub/fake_phone.py` 全文（187 行） | 你要写的东西的 Python 参考实现，连帧顺序都是对的 |
| 5 | 本文件 §4 | 硬约束，违反即返工 |

## 2. 现状对照

| 状态 | 内容 | 位置 |
|---|---|---|
| ✅ | 契约 v0.1（含威胁模型、动作白名单、验收判据） | `docs/specs/hub-contract.md` |
| ✅ | PC 执行器，7 个动作全部实测通过 | `tools/hub/executor.py` |
| ✅ | 本地中继（hello/welcome/devices/call/result/event/ping + 账号级 push） | `tools/hub/dev_relay.py` |
| ✅ | 假手机端（对照实现，可直接当验收脚本用） | `tools/hub/fake_phone.py` |
| ✅ | 执行侧判定策略（按发起方 device id 授权 + 只读命令白名单） | `tools/hub/policy.py` |
| ❌ | **Kotlin 侧全部** | 你来做 |

PC 侧已实测（不是设计推演）：只读 `ls -la` 免审批直通 **43ms**；写操作弹卡 `risk=high` → 允许 → 真执行；拒绝后文件未被触碰；路径越界回 `not_allowed`；本机关远程控制时中继回 `not_controllable`；中继重启后 1s→2s 退避自愈。

## 3. 联调环境（要你自己拉起，不会一直活着）

> **中继和执行器不是常驻服务。** 它们随上一个会话结束就被回收了，端口 8787 现在是空的。
> 每次联调前都要按下面的命令重新起一遍，跑完自己收掉。

| 项 | 值 |
|---|---|
| 中继 | `ws://127.0.0.1:8787/v1/hub`（**仅本机**，`--host` 默认 127.0.0.1） |
| 凭据 | PC 用 `nm_dev`，手机用 `nm_phone`（一把 key 只认领一个 device id，这是 C1.3，不是 bug） |
| PC 设备 | id `pc-ausa`，name `AUSA`，kind `computer` |
| 手机设备 | 你自己声明，建议 id `phone-<uuid8>`、kind `phone` |

```sh
cd tools/hub
python dev_relay.py --port 8787 --key nm_dev --key nm_phone
python executor.py --config config.dev.json
# 对照组（Python 侧的正确答案）：
python fake_phone.py --target AUSA --cmd "cat hello.txt"
```

中继和执行器各占一个后台任务，**和你的测试放在同一个会话里**——它们活到会话结束，跨会话不会留。别指望上个会话留下的进程（实测活了 21 分钟随会话回收，日志末行是 `reconnecting in 1s`，看着像崩溃其实是回收）。

**手机真机联调要改两件事**（现在的中继手机连不上）：
1. 中继用 `--host 0.0.0.0` 重启；
2. App 里填 PC 的局域网 IP（`192.168.2.x`，不是 `127.0.0.1`），Windows 防火墙要放行 8787。

明文流量**已经允许**（`AndroidManifest.xml:189` `usesCleartextTraffic="true"` + `@xml/network_security_config`），不要再加配置。

## 4. 十四条硬约束

每条后面是证据，不是我的偏好。

1. **`pingInterval` 必须显式设 20 秒。** OkHttp 默认 `pingInterval = 0` = 一个心跳都不发，这是「对方显示在线其实已死」的头号来源（契约 C1.4）。
2. **心跳是两层，都要有。** WS 层 ping 20s；应用层每 25s 发 `{"type":"ping"}` 收 `pong`。另加：约 50s 无任何入站帧就主动断开重连，不等 TCP 超时（C1.4）。`ping_timeout` 也是 20s——超时值不等于心跳周期，两者都设。
3. **`hello` 必须是连接后的第一帧，且 15 秒内发出**，否则 `4000`。二进制帧视为违规（C1.2）。
4. **`device.id` 必须持久化，`kind` 恒为 `"phone"`。** 别自己造 UUID——仓库已有 `data/DeviceIdentity.kt:27` 的 `deviceId(context)`（加密 prefs，卸载才变），直接复用并拼成 `phone-<uuid 前 8 位>`。
5. **`call` 的 id 格式 `"<device_id>:<monotonic_seq>:<rand4>"`，seq 必须落盘、重启继续递增。** 不落盘就等于放弃幂等（C7.1）。
6. **`approve` 是一个 `call`，不是独立帧。** `{type:"call", id, to, action:"approve", args:{approval_id, allow, scope}}`，它要走 id、走超时、走 result（C4.2）。我上一版写成独立帧，是错的。
7. **两种 event 机制不同，都要留位置。** 挂 call 的 `event{id, body}`（有 id）+ 账号级 `event{to, body}`（无 id，`features` 里含 `event_push` 时才有）。v0.1 可先只实现前者，但帧层别写死「event 必有 id」。
8. **远程动作绝不走 offload 抽象。** `OffloadGate.kt:20-22` 注释原文：*the IPC reply already gates further guest progress on its return*；`NativeOffload.kt` 的 `execve` 在 handler 返回前不完成。一次远程 `ASK_ONCE` 落在里面 = 那条 guest 命令永久挂住 + 占一个 worker（`MAX_CONCURRENT_CALLS = 128`，超限全 `android-*` 一起拒客）。**直接调 service API。**
9. **审批的等待发生在 WS 帧层**，不在 offload 调用栈里（C4.6）。工具执行是 `suspend` 函数，用 `withTimeout` + `CompletableDeferred` 等结果，不要 `runBlocking`。
10. **`actions[]` 声明什么就必须实现什么**（C2.2）。声明了没实现的动作，等于给自己埋一个必超时的洞。
11. **结果回灌必须带来源标记**（M4）。模型分不清「远程输出」和「自己的本地工具结果」，不加标记语义就乱。建议 `"[来自 AUSA] "` 前缀 + 结构化字段。
12. **待发队列硬上限 64 帧 / 8MB**，超了直接失败并提示，不要「攒着等会发」（C8.2）。`screen` 在**采集时就降分辨率**，别指望链路兜（C8.1）。
13. **关闭码要分语义处理**（C1.5）：`4001`/`4002` **停止退避**、状态置 `refused`、每分钟试一次并提示重登；`4003` 若 reason 是 `hub_paused` 等 60s 不退避，否则立刻重连；`4008`/`4009` 退避且 `4009` 要降级为不拉图。
14. **未知帧回 `error bad_frame` 并保持 socket 打开**（C2.1）。不要「未知帧就断连接」，那会让新旧版本无法共存。

## 5. 任务分解

按序做，每步都能独立验证。T0–T2 是「手机一句话，电脑真执行」的闭环；T3 起是「敢长期开着」的门槛。

### T0 — `HubIdentity` + `HubClient` 帧层

**新建** `src/android/app/src/main/java/com/openminis/app/hub/`
- `HubIdentity.kt`：`deviceId`（复用 `DeviceIdentity`）、`apiKey`（`util/EncryptedPrefsFactory.safeCreate`）、`seq`（持久化单调序号）
- `HubClient.kt`：OkHttp WS + hello/welcome + 双层心跳 + 退避 + 帧编解码
- `HubFrames.kt`：帧的构造与解析（用 `org.json`，与仓库现有风格一致，不要引入 kotlinx.serialization——见 §6 说明）

**验收**：连上 dev_relay，日志里看到 `hello` → `welcome{devices:[pc-ausa]}` → 每 25s 一次 ping；杀掉中继，1s→2s→4s 退避重连，恢复后自动上线。

### T1 — 帧层单测（先于联调）

**依赖已经有了，别装新东西**：`app/build.gradle.kts:440` 是 `okhttp:4.12.0`，`:495` 是 `testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")`。

至少覆盖五条（对应契约 §12 的失败面）：
- 15s 内不发 hello → 收到 `4000` 后应当自愈重连，不是死循环刷日志
- `4001` → 停止退避、状态 `refused`（断言不再发起连接）
- 未知帧 → 回 `bad_frame` 且 socket 仍活（再发一条 ping 仍能收到 pong）
- 发送队列超 64 帧 → 立即失败，不静默堆积
- `welcome` 缺 `features` → 不崩（上游老版本没有这个字段）

**验收**：`./gradlew :app:testGithubPerfUnitTest --tests "*Hub*"` 全绿。

### T2 — `Hub` + `hub_call` 工具 + 回灌

**新建** `hub/Hub.kt`：`devices` 表、`find(name)`、`call(action, args)`（挂起 Map<callId, CompletableDeferred>）、`approval` 的收发。

**改两个文件，落点已核**：
- `tools/AgentTools.kt:53` `buildList { … }` 里加一行 `if (hubEnabled) add(hubCallDefinition())`，并在同文件按 `shellExecuteDefinition()`（`:118`）的写法补一个 `hubCallDefinition()`
- `ui/chat/ChatViewModel.kt:14168` 的 `when (name)` 里加分支，参照 `"shell_execute" ->`（`:14196`）；同时 `:2500` 的 `makeAgentTools(...)` 调用要传新参数

**工具 schema 建议**（`device` 用动态 enum，别让模型猜 id —— M4）：
```json
{ "tool_title": "string, 5-10 词摘要",
  "device":    "string, enum = 在线设备名（每次构造 schema 时重建）",
  "action":    "string, enum = info|shell|files|file.get|file.put|screen",
  "args":      "object, 见契约 C3.2" }
```
`notify` 先不给模型——它是给人用的，模型发通知没意义。

**验收**：A1。手机发 `hub_call{device:"AUSA", action:"shell", args:{command:"ls -la"}}`，6 秒内拿到 stdout，输出带 `[来自 AUSA]`。

### T3 — `HubService` 前台服务

**落点**：`service/AgentForegroundService.kt`（`:38`，`:158` `startService`，`:409` `onBind` 返回 null）旁边新建 `hub/HubService.kt`，复用同一条通知渠道的思路，但**用独立的 channel 和 notification id**，别抢 `9001`。

华为 EMUI 没有绕过保活的办法，只能前台化 + 常驻通知，通知文案别写得像广告（用户会关通知，服务随之降级 —— M5）。

**验收**：App 切后台 30 分钟，中继侧 `devices` 里该设备仍是 `online:true`。

### T4 — 审批卡接进 UI

**落点**：`agent/userask/UserAskCenter.kt`（`:39` object，`:118` `begin`，`:171` `suspend fun wait`，`:261` `dismiss`）。

要做的是**双向**：
- **收**：收到 `event{stage:"approval"}` → 转成一张 `PendingAsk` 显示；用户点击 → 发 `call{action:"approve"}`；`approval_result` 回来 → `dismiss`。
- **发**（v0.1 可后置）：本机审批卡需要能转发到对端。

字段照 `executor.py:325-339`：`approval_id / preview / preview_zh / risk / reason / app / task{id,title} / chat / scopes / timeout / device / any_device`。
`timeout` 是**秒数**（PC 侧配的 120），超时按拒绝处理。`any_device:true` 表示同账号任意设备首个应答者决定。

**验收**：A2 + A3。
- A2：手机发 `shell{command:"rm -rf hubroot/trash"}` → 手机弹卡 → 点「允许一次」→ PC 真删 → 结果回会话；`journal.jsonl` 里 `by` 记到你的 device id。
- A3：同 A2 但 2 分钟内不点 → 卡片消失，call 以 `timeout` 失败，**PC 侧未执行**，再发一次仍能正常走。

### T5 — 反向（可后置）

`RemoteActionGate` + 手机作为执行方（`shot/nodes/back`）。契约 C3.5 已把白名单定死：**先不做坐标动作**。要做时必须每次人批、不可 `always`、卡片上附坐标附近节点快照。

## 6. 代码落点速查

| 要改/新建 | 位置 | 说明 |
|---|---|---|
| 新建包 | `com.openminis.app.hub` | 六个类见 §5 |
| 工具定义 | `tools/AgentTools.kt:53` / `:118` 旁 | `buildList` 加一行 + 仿 `shellExecuteDefinition()` |
| 工具分发 | `ui/chat/ChatViewModel.kt:14168` `when(name)`、`:14196` 参照 | `executeTool` 是 `suspend`，可直接等审批 |
| 工具列表装配 | `ui/chat/ChatViewModel.kt:2500` | `makeAgentTools(...)` 传开关参数 |
| 设备 id | `data/DeviceIdentity.kt:27` | `deviceId()` + `encryptedPrefs` 模式，别另起炉灶 |
| 加密存储 | `util/EncryptedPrefsFactory.safeCreate` | 存 api key，别写明文 |
| 前台服务 | `service/AgentForegroundService.kt:158` 参照 | 独立 channel / id |
| 审批 UI | `agent/userask/UserAskCenter.kt` | `PendingAsk` 数据源 |
| 工具结果类型 | `tools/ToolExecutionResult.kt:3` | `imageData/imageMimeType/imageFilePath` 都有；**但见下** |

**三条风格约束**（读了仓库才敢这么说）：
- 无 DI 框架（没有 Hilt/Koin），对象手工构造，别引入注入。
- 无 DataStore，持久化用 `SharedPreferences` / `EncryptedPrefsFactory` / Room（`data/db/AppDatabase.kt`）。
- JSON 用 `org.json`，与 `AgentTools.kt` 一致。不要为帧层引入 kotlinx.serialization。

**一个要你决定的点**：`screen` / `file.get` 回来的 base64 别直接塞 `ToolExecutionResult.imageData`。仓库有 request 级图像预算（见 `ToolExecutionResult.kt:18-26` 注释），一张 4K 图会撑爆。建议先落盘到沙箱可见路径，回**路径 + 尺寸 + 来源**，让模型自己决定要不要 `read_image`。

## 7. 验收怎么做

**每条都要测成功路径 + 三种失败（对端离线 / 超时 / 被拒）**，见契约 §12。最低通过线：

| 编号 | 操作 | 通过标准 |
|---|---|---|
| A1 | 手机发 `shell{"ls -la"}` | 6s 内 stdout，带来源标记，无审批卡 |
| A2 | 手机发 `shell{"rm -rf ..."}` | 弹卡 → 允许 → 真执行 → 结果回会话，审计记 `by` |
| A3 | A2 不点卡 | 超时消失，PC 未执行，重发可走 |
| A5 | PC 关远程控制 | 收 `not_allowed` 而非 `device_offline`，提示语区分开 |
| A4 | 飞行模式 30s 期间 PC 执行完 | 恢复后**不重复执行**；结果丢了就显示「结果未知，请核对」，不许静默重发 |

**对照组**：同样的动作用 `python fake_phone.py --target AUSA --action shell --args '{"command":"..."}'` 跑一遍，两边的 `journal.jsonl` 应当一致。不一致时先怀疑 Kotlin 侧帧构造，别怀疑 PC 侧——它已经过了 8 个场景。

## 8. 不要做的事

- **不要自造帧。** 也**不要给契约加字段**。上游不认识就回 `bad_frame`，你会在自己身上浪费一天。
- **不要让远程动作走 offload**（§4.8）。这条是硬红线，不是性能优化。
- **不要 `runBlocking`** 等审批。工具执行本身是 `suspend`，用 `CompletableDeferred`。
- **不要自动重放写操作**（C7.3）。断线窗口里你无法区分「没收到」和「执行了但结果丢了」，`rm` 重放一次就够出事——进 `unknown` 让人核对。
- **不要改 `AndroidManifest`** 的明文配置（已是对的）。
- **不要改 `tools/hub/`** 下任何 Python。要改先跟我确认，那是已验证的对照组。
- **不要顺手「优化」`OffloadGate` / `AccessibilityOffloadHandler`。** 那属于另一条工作线（把 a11y offload 改成 submit/poll 两段式），单独排期。

## 9. 编译与安装

跟 `WINDOWS-BUILD-AGENT.md` 走，别按 `HANDOFF.md` 老的 schtasks 方案（已失效，原因写在第 8b 节）。关键：PowerShell 后台任务直接跑 `gradlew.bat assembleGithubPerf --no-daemon`，`JAVA_HOME=D:\Android\jdk-17`、`ANDROID_HOME=D:\Android\Sdk`。

装机后校验三项（包名 `com.openminis.app.dev`、versionCode、签名指纹 `d3c5d4c2…1ce20d0`），别装错实例。

## 10. 要问的问题（不要自己拍板）

1. **`hub_call` 默认开关是开还是关？** 建议默认关，`Settings › Agent Tools` 里加一个 `hub` 开关，与 `browser` / `agents` 同级（`tools/AgentToolSwitch.kt`）。
2. **凭据谁填？** v0.1 建议设置页手填 relay 地址 + key；登录/配对流程属后一版。
3. **`notify` 给不给模型？** 我建议不给。
4. **`screen` 的落盘路径**用哪个（`/var/minis/attachments/generated/` 还是按会话分目录）？按现有 `file_read` 的宿主路径解析规则来，别新造。

## 11. 变更记录

- 2026-11-07：初稿。PC 侧执行器已实测；本文只覆盖 Android 侧 T0–T5。
