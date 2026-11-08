# tools/hub — PC 侧执行器

契约 ② 的电脑端：手机（Minis）发起 `call`，这里执行，结果回传。
协议是 nanoMuse Hub 0.31，见 [`docs/specs/hub-contract.md`](../../docs/specs/hub-contract.md)。

## 三个文件的分工

| 文件 | 角色 | 状态 |
|---|---|---|
| `executor.py` | **PC 执行器**（本次交付物） | 可用 |
| `dev_relay.py` | 本地中继，只为联调 | 开发用，单进程内存态 |
| `fake_phone.py` | 假手机端，只为验收 | 测试用 |

`executor.py` 不依赖另外两个：它只认 `wss://…/v1/hub` 和 `Bearer nm_…`，换成真中继不改一行代码。

## 跑起来

```sh
cd tools/hub
pip install -r requirements.txt

# 三个终端，或三个后台进程
python dev_relay.py --port 8787 --key nm_dev --key nm_phone
python executor.py --config config.dev.json
python fake_phone.py --target pc-ausa --action info
```

`fake_phone.py` 的 `--target` 可以写设备 id（`pc-ausa`）也可以写设备名（`AUSA`）。

## 配置

`config.dev.json` 逐项说明：

| 键 | 作用 |
|---|---|
| `relay` / `api_key` | 中继地址与凭据。一把 key 只能认领一个 `device.id`，多设备要多把 key |
| `device.id` | 必须持久化，`kind` 恒为 `computer`。改 id = 换设备身份 |
| `device.root` | **文件与命令的工作根**。所有 `files` / `file.*` / `shell.cwd` 都被限制在这个目录内 |
| `device.remote_control` | 本机总开关。设为 `false` 时 `hello` 声明空 `actions`，中继会直接拒投递（`not_controllable`） |
| `actions[]` | 对外声明的能力。**声明了就必须实现**，否则等于给自己挖一个必超时的洞 |
| `policy.default` | 默认档位：`bypass` / `ask` / `deny` |
| `policy.by_sender` | 按**发起方 device id** 放行，例如 `{"phone-xxxx": "bypass"}` |
| `policy.allow_read_only_shell` | 只读命令免审批（白名单见 `policy.py`） |
| `limits.approval_timeout_s` | 审批卡等人多久，超时按拒绝处理 |
| `shell.program` / `shell.args` | 留空则 Windows 上用 Git Bash，其次 `cmd /c`；类 Unix 用 `/bin/sh -lc` |
| `journal` | 每个 call 的审计记录（谁发起、什么动作、结果、耗时） |

## 已实测通过

在本机（Windows，AUSA）跑通的场景，均带真实耗时：

| 场景 | 结果 |
|---|---|
| 只读 shell `ls -la` | 免审批直通，**43ms** 返回 stdout |
| 写 shell `rm -rf trash` | 弹审批卡 `risk=high` → 允许 → 执行，目录确实被删 |
| 同上但拒绝 | 返回 `not_allowed: denied by a person`，目标文件未被触碰 |
| 路径越界 `files {"path":"../../.."}` | `not_allowed: path is outside this executor's root` |
| `file.get` / `file.put` | 正常；`file.put` 走审批，落盘后回 `sha256` |
| `screen` | 审批后截屏，降采样到 **1600×900 / JPEG q80**，约 190KB，远低于帧上限 |
| 本机关闭远程控制 | 中继回 **`not_controllable`**，call 根本到不了执行器 |
| 中继重启 | 执行器按 1s→2s 退避自动重连，无需人工介入 |

审计样例（`journal.jsonl`）：

```json
{"call":"phone-fake:1:f552","sender":"phone-fake","action":"shell","status":"ok","exit_code":0,"duration_ms":35,"note":"shell is read-only (read-only)"}
```

## 路径基准

**配置里的相对路径一律以「配置文件所在目录」为基准，与进程 cwd 无关。**

`device.root`、`journal` 以及 `file_max` 约束下的一切路径都相对配置文件目录展开；
绝对路径原样使用。执行器的日志文件也放在同一个目录（启动器把 stdout 重定向到
配置文件旁边），于是**日志 / journal / 文件根三者同处一地**，不会再出现
"本该写在这里却写到了那里"。

例：配置 `D:\hubtmp\config.ts.json` 且 `device.root="hubroot"`、`journal="journal.jsonl"`
→ 根目录 `D:\hubtmp\hubroot`、审计日志 `D:\hubtmp\journal.jsonl`、
执行器日志 `D:\hubtmp\executor.log`，无论从哪个 cwd 启动。

## 与契约的偏差（都是有意为之）

| 契约条款 | 本实现 | 原因 |
|---|---|---|
| C4.3 账号级 push（`event{to}`） | **未实现**，只做挂 call 的 `event{id}` | ② 场景下每次审批都有 call 顶着；push 留给「PC 主动发起」的后续版本 |
| C6.1 `stop{call}` | 返回 `{stopped:false}`，绝不强杀已在跑的命令 | 强杀一个写了一半的 `rm` 比让它跑完更危险 |
| C7.3 断线重放 | 执行器内存里去重（`_done` 保留 500 条） | 中继重启后能挡住「重连又重发」，跨进程重启挡不住 |
| C3.2 `open` | 未实现 | 它会拉起 GUI，语义依赖桌面环境，先放着 |
| `notify` | 需要 `shell.notify_command`，否则回 `{delivered:false}` | 不做平台相关的托盘/Toast 适配 |

## 已知限制

- `dev_relay.py` 是**内存单进程**：没有持久化、没有 TLS、重启丢 in-flight。生产要么跑在 Tailscale 上（地址本身就是信任边界），要么用 nanoMuse 的 `cloud/nanomuse_cloud/hub.py`。
- `fake_phone.py` 的 call id 序号不持久化（`phone-fake:1:…`），真客户端必须按 C7.1 落盘单调递增。
- `screen` 依赖 Pillow + 可用的桌面会话；无头环境下会回 `not_allowed`。
- 只读命令白名单刻意保守：`awk` / `sed` / `find` / `git` / `python` 一律不算只读（它们都有写入或执行路径）。宁多弹一次卡，不放行一次写。

## 下一步

1. 换真中继：把 `relay` 指向 Tailscale 上的实例，`api_key` 换成 `nm_…`，`executor.py` 不动。
2. Minis 侧：`HubClient` + `Hub` + `HubService`，把 `call` 包成工具的 `hub_call`（设备名动态列进工具描述，结果回灌必须带来源标记）。
3. 补 `event{to}` push，才能覆盖「电脑自己发起的方向」。
