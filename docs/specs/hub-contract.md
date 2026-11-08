# Minis Hub 契约 v0.1

**场景 ②：手机指挥电脑干活。**手机是发起方，电脑是执行方。

状态：草案，未实现。本文编号即条款号，改约定必须改编号并在 §13 记一笔。

---

## 0. 方言与边界

**D1（已定）：线上格式对齐 nanoMuse Hub 0.31，不自造协议。**

理由：PC 侧执行器是这条路上最贵的部分，而 nanoMuse 的 runtime 已经把 `shell / files / file.get / file.put / open / screen / notify` 跑通了，中继也是现成的（`cloud/nanomuse_cloud/hub.py`）。Minis 只需实现**手机侧**这一半，就能立刻和真实对端联调——而不是两个假实现互相点头。

代价：协议演进跟着 nanoMuse 走；`approve` 的语义、错误码、推送限额都要按它的实现来，不能加私有字段。接受。

**不在本版范围**

- `task`（整句话交给对方 agent）——需要对方有 LLM 与会话，②场景不需要。
- `/v1/sync/*`（会话同步）——与 hub 正交，单列旁线。
- 手机作为**执行方**被电脑指挥——协议天然对称，但 v0.1 只做「PC 执行」这一向，反向只要求不崩。

**证据标注**

- `[源]` 已在 `nanomuse/hub/client.py` 或 `docs/hub.md` 原文核对。
- `[推]` 由帧结构推断，需联调确认。
- `[定]` 本文约定，非上游行为。

---

## 1. 威胁模型

**资产**：电脑的 shell 执行权、文件系统读写、屏幕内容；手机的 a11y 操作权、通知。

| 编号 | 攻击者 | 能拿到什么 | 对策 |
|---|---|---|---|
| T1 | 拿到 relay 凭据的人 | 该账号全部设备的 `call` 权 | 凭据绑定单一 device id（C1.3）；任一端吊销即全端失效；凭据不落磁盘明文 |
| T2 | 同账号的另一台设备 | 同上 | 执行侧按**发起方 device id** 授权（C4.1），不是「同账号即放行」 |
| T3 | 被攻陷的中继 | 全部明文帧 | 中继只转发不做决策；危险动作的批准必须由人产生，中继无法伪造 `approval_result` 的 `by`（只能伪造内容，见 Q3） |
| T4 | 被提示注入的手机 agent | 借 agent 之手发 `shell` | 执行侧判定**不可由 agent 代答**：`shell` 与 blind 动作一律要人批（C4.4） |
| T5 | 重放攻击 / 中继重启 | 重复执行 | 幂等规约 C7 |

**边界（四层，双侧都要判）**

1. 中继：凭据有效 + 设备 id 匹配（`bad_key` / `bad_device`）。
2. 执行侧总开关：本机 `Remote control` 打开。
3. 执行侧逐动作：`RiskGate.check("hub:<发起方 device id>", DEVICE, REMOTE, …)`，授权主体是**设备**，不是会话。授权粒度：`once` / `schedule`（仅任务内）/ `always`。
4. 发起侧：Minis agent 决定是否发这条 `call`；本机 `ShellGuard` 在**发起侧**只做「这条命令我愿不愿意发出去」的自检，**不能替代**执行侧判定。

> 上一版把第 4 层写成「校验在发起侧，不在执行侧」，是错的，此处更正。执行侧被攻陷即失去全部防护——所以第 2、3 层不可省。

---

## 2. 连接（C1）

**C1.1 端点**：`wss://<relay>/v1/hub`，`Authorization: Bearer nm_…` `[源]`。浏览器无法设请求头时改在 `hello` 里带 key `[源]`。

**C1.2 握手时序**：连接后 **15 秒内**必须发第一帧且必须是 `hello`；否则 `4000 hello expected`。二进制帧亦视为违规 `[源]`。

**C1.3 身份绑定**：一个凭据只拥有一个 Hub 身份。`device.id` 与 `device.kind` 在凭据签发时固定（`POST /v1/auth/verify`、`/login`），或在首次被接受的 `hello` 时固定；之后换 id 或换 kind 一律 `bad_device`。同一凭据重连会**顶替**自己的旧 socket `[源][relay 0.27]`。

> Minis 约束：`device.id` 必须持久化在 App 私有目录，重装视为新设备（格式 `phone-<uuid8>`）。`kind` 恒为 `phone`。

**C1.4 心跳**：两层，都要有。

- WS 层：`ping_interval = 20s`，`ping_timeout = 20s` `[源]`
- 应用层：每 25s 发 `{"type":"ping"}`，收 `pong` `[源]`

> Minis 硬约束：OkHttp 默认 `pingInterval = 0`（**不发心跳**）。必须显式 `pingInterval(20, SECONDS)`。这是「显示在线其实已死」的头号来源。
> 断线判定对齐桌面端行为：两次 `ping` 之间**没有任何入站帧**（约 50s）即主动断开重连，不等 OS 的 TCP 超时。

**C1.5 重连退避**：起始 1s，指数退避上限 60s；收到任何帧即重置 `[源]`。

| 关闭码 | 含义 | Minis 行为 |
|---|---|---|
| 4000 | 15s 内无 hello | 内部 bug，重连并上报诊断 |
| 4001 | `bad_key` / `account_deleted` / `account_gone` / 运行中被吊销 | **停止退避**，hub 状态置 `refused`，提示重新登录；每分钟仍试一次 `[源]` |
| 4002 | `bad_device` | 同上，且提示「设备身份冲突」 |
| 4003 | 同 id 新连接顶替；或 reason `hub_paused`（运维关闭） | 顶替：重连；`hub_paused`：等 60s，不退避 |
| 4008 | 帧太多无视限流；或 `too_many_connections`（单 IP 60 socket/min，或账号已连 32 台） | 退避，并把「同账号设备过多」显示出来 |
| 4009 | 慢消费者（排队 512 帧或 32MB） | 按断网处理，退避重连；**并降级为不拉图** |

---

## 3. 帧（C2）

严格按上游，不加私有字段。

```
→ hello    {device:{id,name,kind,os,version,actions[],llm?}}     kind: phone | computer | web
← welcome  {device_id, devices:[…], server:{version,frame_limit,time,features?}}
← devices  {devices:[{id,name,kind,os,version,actions[],online,last_seen,controllable,ip}]}

→ call     {id, to, action, args}
← call     {id, from:{id,name,kind}, action, args}               (投递给目标)
→ event    {id, body}          ← event  {id, from, body}         有 id：挂在某次 call 上
→ event    {to, body}          ← event  {from, body}             无 id：账号级推送（0.31）
→ result   {id, ok, body | error, message}                       回到发起方
← error    {code, message, id?}

→ devices {}   → rename {name}   → forget {device_id}   → ping   ← pong
← profile {rev, device}      ← sync {what:"conversations", cursor, from}      ← working {…}
```

**C2.1 未知帧**：回 `error bad_frame`，**socket 保持打开** `[源]`。不要学「未知帧就断连接」，那会让新旧版本无法共存。

**C2.2 `actions[]` 必须诚实**：`hello` 里声明什么就代表「我能执行什么」，对方据此派活。声明了却没实现，等于给自己埋一个必超时的洞。`[定]`

**C2.3 web 设备不是目标**：`app` 永不把 `kind:"web"` 放进取目标列表 `[源]`。

---

## 4. 动作白名单（C3）

**C3.0 分层原则**：风险可判定性决定审批档位，而不是「读/写」决定。

| 层 | 动作 | 风险可判定？ | 审批 |
|---|---|---|---|
| L0 只读 | `info` `files` `file.get` | 是（路径白名单） | 可 `always` |
| L1 语义写 | `tap{label\|text}` `type{text→nodeId}` `open` | 是（判定器读元素文本） | `once` / `always` |
| L2 盲操作 | `tap{x,y}` `swipe` `scroll` | **否**（不知道坐标下面是啥） | **每次人批，不可 `always`** |
| L3 执行 | `shell` | 部分（命令内容可判，副作用不可判） | 写操作 `once`；读操作可 `always` |
| L4 采集 | `screen` `notify` | 是 | 可 `always` |

**C3.1 v0.1 只实现 L0 + L3 + L4**（PC 侧执行器提供，Minis 声明同集合）。`open` 归入 L1 但 v0.1 不上——它会拉起 GUI，语义依赖桌面环境，先放着。

**C3.2 参数与返回**（逐条，v0.1 定为契约）

```
info     {}                                    → {os, host, home, cwd, version, ...}   [推]
shell    {command, cwd?, timeout_ms?}          → {exit_code, stdout, stderr, truncated?, duration_ms}
files    {path?, depth?}                       → {path, entries:[{name,kind,size,mtime}], truncated?, total?}  [源: truncated/total]
file.get {path, offset?, length?}              → {path, mime, size, data(base64), eof}
file.put {path, mime, data(base64)}            → {path, size, sha256}
screen   {display?}                            → {mime, data(base64), width, height}
notify   {title, body}                         → {}
```

**C3.3 二进制**：`file.*` 与 `screen` 的字节放 `data` 字段 base64，配 `mime` `[源]`。单文件上限 **8MB**（上游桌面端拒收更大）`[源]`；超过就分块 `file.get{offset,length}`，`eof` 指示是否读完。

> 提醒：8MB 原始字节 base64 后约 10.7MB，还得塞进单帧。所以实际分块大小按 `server.frame_limit` 倒算，留 20% 余量——别按 8MB 一把梭。

**C3.4 截断**：`shell.stdout`、`files` 结果超限时**必须**带 `truncated:true`（`files` 另带 `total`），调用方必须能处理不带这些字段的响应 `[源]`。

**C3.5 手机侧动作（反向，v0.1 不实现，但白名单先定死）**：`shot` `nodes` `back` `notify` 属 L0/L4；`tap/type` 属 L1/L2。**先不做坐标动作**——理由见 C3.0 L2：判不了风险。若日后要做，必须每次人批且卡片上附坐标附近的节点快照。

---

## 5. 审批回合（C4）

**C4.1 授权主体是设备。**执行侧判定键 `hub:<发起方 device id>`，档位 `BYPASS / ASK_ONCE / NOT_ALLOWED`（沿用 `OffloadPermissionManager`），但**新增「跨设备」维度**——现在的三档是按会话隔离的，远程调用不属于任何本地会话，直接复用会把授权串到错的会话上。

**C4.2 `approve` 是一个 `call`，不是独立帧。**`[源]`

```
→ call {id, to, action:"approve", args:{approval_id, allow, scope?}}   scope: "once" | "schedule"
```

我上一版写成 `approve{approval_id, allow}` 独立帧，是错的。实现时它要和普通 call 一样走 id、走超时、走 result。

**C4.3 两种推送，机制不同，都要做。**

- **挂 call 的**：执行侧在还有 call 顶着时，用 `event{id, body:{stage:"approval",…}}` 转发给发起方。发起方答完，`approve` 以 call 回执行侧。`[源]`
- **账号级 push**：`event{to:"*", body:{…any_device:true}}`，无 id。用于「没有 call 顶着」的场景（定时任务、PC 自己发起的步骤）。限额：**每设备 30 条/分钟、单条 body ≤16KB**，且只有 `approval` 与 `approval_result` 两个 stage 能过，其它静默丢弃 `[源]`。

> 这两条路不是二选一。只做第一条，PC 主动发起的卡片永远到不了手机；只做第二条，超限（30/min）时审批会丢。

**C4.4 卡片字段**（`stage:"approval"`）`[源]`

```
{approval_id, preview, preview_zh, risk, reason, app, task:{id,title}, chat,
 scopes:["once","schedule"?], timeout, device, any_device:true}
```

`timeout` 为手机显示秒数（上游取 **600**）。`any_device:true` 表示**账户内任意设备都可应答，首个应答者决定结果**。

**C4.5 我改一条主张。**上一轮我说「危险动作只认发起方」。在 ② 场景下这是错的：PC 无人值守时，若只认发起方，发起方离线就意味着永远没人能批。改为：

- 默认 `any_device:true`（上游行为），**任何设备的人**都能批。
- 但 `approval_result` 必须带 `by:{id,name}` 并**落审计**（C9）。谁批的必须可查，否则审计链断。
- **例外**：L2 盲操作与 `shell` 的写操作，`scopes` 只给 `once`，不给 `schedule`；且**不允许 agent 自批**（T4）——人的点击才算。

**C4.6 审批等待的位置**：必须发生在 **WS 帧层**，绝不能落在 offload 调用栈里。原因见 C10.2。

---

## 6. 错误码（C5）

| code | 来源 | Minis 呈现 | 重试 |
|---|---|---|---|
| `device_offline` | 中继 | 「电脑不在线」，保留 call 待重连后询问是否重发 | 不自动 |
| `not_controllable` | 中继 | 「电脑未开启远程控制」 | 不 |
| `self_call` | 中继 | 内部 bug | 不 |
| `duplicate_call` | 中继 | 幂等命中，按成功处理（见 C7.3） | — |
| `unknown_call` | 中继/执行侧 | 审批已过期或已答，丢弃卡片 | 不 |
| `timeout` | 中继 | call 15 分钟无人应答 `[源]` | 询问后重发 |
| `too_large` | 中继 | 改用分块 | 是（分块） |
| `rate_limited` | 中继 | 退避 1s 后重试一次 | 是（一次） |
| `bad_frame` | 中继 | 本地 bug，上报诊断 | 不 |
| `bad_key` / `bad_device` | 中继 | 引导重新登录 | 不 |
| `not_allowed` | **执行侧** | 「电脑拒绝了这次操作」 | 不 |
| `cancelled` | 执行侧 | 「已停止」 | 不 |

> `not_allowed` / `cancelled` 是执行侧塞进 `result.error` 的业务码，不在中继错误码全集里 `[定]`。发起侧必须把「中继拒绝」和「对方拒绝」区分开——前者是通道问题，后者是人/策略问题，提示语完全不同。

---

## 7. 超时与取消（C6）

| 阶段 | 值 | 依据 |
|---|---|---|
| 连接后发 hello | 15s | `[源]` 4000 |
| WS ping / pong | 20s / 20s | `[源]` |
| 应用层 ping | 25s | `[源]` `PING_EVERY_S` |
| 入站静默判死 | ~50s | `[源]` 桌面端行为 |
| call 无人应答 | **15 分钟** | `[源]` |
| 审批卡显示 | 600s | `[源]` timeout 字段 |
| 退避上限 | 60s | `[源]` |

**C6.1 停止**：`stop{call}` 传发起时那条 `task`/`call` 的 id；执行侧在**当前步骤之后**取消，回 `{stopped:true}`，原 call 以 `cancelled` 失败；`{stopped:false}` 表示没有可停的东西 `[源]`。

> v0.1 没有 `task`，`stop{call}` 对 `shell` 的含义需要定：**执行侧收到 stop 时若命令已在跑，不强杀进程**（强杀一个正在 `rm` 的进程比让它跑完更危险），只拒答后续结果并标记 `cancelled_after_start`。`[定]`

---

## 8. 幂等与重放（C7）

**C7.1 call id 构造**：`<device_id>:<monotonic_seq>:<rand4>`。单调序号持久化，重启后继续递增，不回绕。

**C7.2 中继语义**：call id 重复 → `duplicate_call`；未知 id 的 result → `unknown_call` `[源]`。

**C7.3 断线窗口**：中继重启会丢 in-flight。发起方此时**无法区分**「对方没收到」和「对方执行了但结果丢了」。三种处理，按动作分层：

- L0 只读：可重放，天然幂等。
- L3 `shell` 读操作：可重放。
- L3 `shell` 写操作 / `file.put`：**禁止自动重放**。发起侧进入 `unknown` 状态，UI 显示「结果未知，请核对」，并给出重发按钮（人点）。
- `file.put` 用 `sha256` 做幂等键：执行侧比对目标文件哈希一致即回成功，不重写 `[定]`。

**C7.4 执行侧 journal**：执行侧把每个 `call` 的 `{id, action, args_hash, started_at, finished_at, exit_code}` 追加写本地日志（循环保留 N 天）。这是「结果丢了怎么核对」的唯一依据，也是审计（C9）的底稿。`[定]`

---

## 9. 背压与限流（C8）

| 限制 | 值 | 来源 |
|---|---|---|
| 单帧上限 | `server.frame_limit`（连接后从 welcome 读） | `[源]` |
| 客户端 MAX_FRAME | 16MB | `[源]` `MAX_FRAME` in client.py |
| 中继发送队列 | 512 帧 或 32MB → `4009` | `[源]` |
| 发送速率 | 60 帧/s 或 8MB/s → `rate_limited`，持续则 `4008` | `[源]` |
| push 速率 | 30 条/min，body ≤16KB | `[源]` |
| 单文件 | 8MB（上游桌面端拒收） | `[源]` |

**C8.1 Minis 侧规则**：`screen` 在**采集时就降分辨率**（长边 ≤1600，JPEG q80），别指望链路兜。一张 4K PNG base64 就是 10MB+，等于一发打满队列。

**C8.2 不允许无界内存缓冲**：客户端待发队列设硬上限（建议 64 帧 / 8MB），超了直接失败并提示，不要「攒着等会发」。

---

## 10. Minis 侧落地（M 条款）

**M1 新增包 `com.openminis.app.hub`**

| 类 | 职责 | 依赖 |
|---|---|---|
| `HubIdentity` | 持久化 `device_id` / 凭据 / seq | DataStore |
| `HubClient` | OkHttp WS、hello/welcome、ping、退避、帧编解码 | OkHttp |
| `Hub` | `devices[]` 表、`call(action,args)`、`find(name)`、挂起 call 的 Map | HubClient |
| `HubActions` | 执行侧动作实现 + `RemoteActionGate` | service API |
| `HubService` | 前台服务 + 常驻通知，宿主 | `AgentForegroundService` |

**M2 执行侧判定 `RemoteActionGate`**：入参 `(senderDeviceId, action, args)`，返回 `ALLOW / ASK / DENY`。档位存 `OffloadPermissionManager` 但**以 device id 为 key**（`hub:<id>`），与本地会话隔离。

**M3 绝不走 offload。**`[源]` `OffloadGate.kt:20-22` 注释写明 *the IPC reply already gates further guest progress on its return*；`NativeOffload.kt` 的 `execve` 在 handler 返回前不完成，`MAX_CONCURRENT_CALLS = 128`，超限回 exit 75（GH #453）。一次远程 `ASK_ONCE` 落在 offload 栈里 = 那条 guest 命令永久阻塞 + 一个 worker 被占，且 `runBlocking` 连协程都退不出来。

> 所以：远程动作**直接调** `MinisAccessibilityService` / `NotificationListenerService` / 文件 API，审批的等待发生在 WS 帧层（C4.6）。

**M4 工具暴露与回灌**：模型侧看到的是一个 `hub_call` 工具（参数：`device`, `action`, `args`）。

- `device` 取值来自 `Hub.devices`，工具描述里动态列出在线设备名——不要让模型猜 id。
- 结果回灌**必须带来源标记**（例如 `"[来自 Desk] "` 前缀或结构化 provenance 字段），否则模型会把自己发出去的远程输出当成本地工具结果，语义直接乱。

**M5 前台与保活**：`HubService` 前台化 + 常驻通知；被 EMUI 杀掉后由 `AccessibilityRecoveryManager` 的同类路径重启。通知文案不许写成广告（否则用户会关掉通知，服务随之降级）。

**M6 设备列表 UI**：会话页顶部显示「已连接：Desk / buntu」，离线灰掉并给重连按钮。

---

## 11. 中继落点（C11）

**C11.1 自托管，只监听 Tailscale 地址。**

```
relay: wss://<tailscale-name>:8787/v1/hub     (bind 100.x.y.z only)
```

理由：零公网 IP、零域名、零自签证书、零新增公网信任面——认证面就是 Tailscale 的身份。

代价（必须接受）：buntu 离线 = 互通全断；手机走 4G 时经 DERP 中继，延迟 100–300ms。对帧协议够用，对「实时画面串流」不够用（v0.1 不做串流）。

**C11.2 备选**：若日后要公网，Cloudflare Workers + Durable Objects（按账号分片，天然对上「账号 → 连接表」，自带 WS hibernation）。**不要自写认证。**

---

## 12. 验收判据（A 条款）

每条都要测**成功路径 + 三种失败**（对端离线 / 超时 / 被拒）。

| 编号 | 场景 | 通过标准 |
|---|---|---|
| A1 | 手机 4G，发「在 Desk 上 `ls ~`」 | 手机 6s 内看到 stdout；输出带来源标记；无审批卡（L0 已 `always`） |
| A2 | 手机发 `shell{command:"rm -rf ~/tmp/x"}` | Desk 生成审批卡 → 手机 2s 内弹出（走 call-scoped event）→ 点「允许一次」→ 执行 → 结果回会话；审计里记到 `by: phone-xxxx` |
| A3 | 同 A2，但手机 5 分钟内不点 | 卡片超时消失；call 以 `timeout`（或执行侧 `cancelled`）失败；Desk 侧未执行；再发一次能正常走 |
| A4 | 手机切飞行模式 30s，期间 Desk 执行完毕 | 恢复后会话**不出现重复执行**；若结果丢失，UI 显示「结果未知，请核对」而非静默重发 |
| A5 | Desk 端关闭远程控制开关 | 手机侧收到 `not_allowed`（不是 `device_offline`），提示语明确区分 |
| A6 | 用假 peer 打满 60 帧/s | 中继回 `rate_limited`；客户端退避，不雪崩 |

A1/A2 是 ② 的最小可用闭环；A3–A6 是「敢长期开着」的门槛。

---

## 13. 待定（Q）

| 编号 | 问题 | 影响 |
|---|---|---|
| Q1 | `info` 的返回字段上游未在 hub.md 列出 | 需联调取样，先按 C3.2 的并集做宽松解析 |
| Q2 | 执行侧 `result` 的 `error` 业务码（`not_allowed` / `cancelled`）上游是否有更细的枚举 | 影响 UI 文案，不阻塞 |
| Q3 | 中继被攻陷时能否伪造 `approval_result.by` | 决定审计可信度；缓解：执行侧把 `approval_result` 与本地 journal（C7.4）比对 |
| Q4 | `stop{call}` 对已在跑的 `shell` 语义（C6.1 暂定不强杀） | 需确认上游行为 |
| Q5 | 手机作为执行方时 `shot` 的分辨率与帧上限倒算 | v0.1 不实现，但影响白名单 |

---

## 14. 变更记录

- v0.1（2026-11-07）：初稿。场景 ② 定案；协议对齐 nanoMuse Hub 0.31；删去自造 `approve` 帧（改为 `call{action:"approve"}`）；更正「校验只在发起侧」为双侧判定；动作白名单按可判定性分层；新增幂等、背压、审计、验收条款。
