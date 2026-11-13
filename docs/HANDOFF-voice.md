# 语音线交接文档 —— 「全场景可交流 / 后台语音唤醒」

> 面向**电脑端 agent**。目标：让 Minis 在锁屏/后台也能被唤醒并对话。
> 状态：2026-11-13。**已实现并通过构建验证**；真机验收清单见 §8。

---

## 0. 一句话目标

用户原话：
> 「pilot 已经证实，后台时你仍然可以有麦克风的权限，现在**全场景下都可以和你交流**。」

即：手机揣兜里、屏幕黑着，喊一声就能对话，不用掏出来点开 App。

本次实现把 `D:\phone agent`（pilot）的唤醒词能力移植到 OpenMinis，
并额外做了**桌面悬浮球**，在其他应用之上实时显示语音状态。

---

## 1. 落地成果

新增包 `src/android/app/src/main/java/com/openminis/app/speech/wake/`：

| 文件 | 作用 | 移植来源 |
|---|---|---|
| `WakeWordSettings.kt` | 唤醒开关 / 唤醒词 / 悬浮球开关 / 提示音 / 悬浮球坐标，存 `voice_wake` prefs | 新写（沿用 Minis 设置风格） |
| `VoiceWakeState.kt` | 全局状态机 `OFF / WAITING / WAKING / PAUSED / ERROR` + `voice_wake.log` 诊断 | 新写 |
| `KwsKeywords.kt` | 汉字 → 带声调拼音（ICU 反射 + 预置词表） | ★ pilot `KwsKeywords.kt` |
| `WakeWordDetector.kt` | sherpa-onnx `KeywordSpotter` 封装，assets 或外部 `models/kws` | ★ pilot `WakeWordDetector.kt` |
| `WakeAudioCapture.kt` | AudioRecord 采集：16kHz / 单声道 / PCM16 / 20ms 帧 | ★ pilot `AudioCapture.kt` |
| `WakeWordEngine.kt` | 采集线程 + 喂帧 + 命中回调 | ★ pilot（合流 ASR 之前的部分） |
| `VoiceStatusOverlay.kt` | 独立悬浮球，可拖拽、点击进入语音对话 | 新写（**不复用** `ToolOverlayController`） |
| `WakeWordService.kt` | 麦克风前台服务、400ms 闸门循环、**唤醒后不打开 App**，直接进入聆听 → 识别 → 语音回复 | 新写（pilot 的 `VoiceInput` 状态机思路） |
| `WakeWordController.kt` | 统一启停入口 + 「启动被系统拒绝」的前台重试 | 新写 |
| `WakeEnergyVad.kt` | 能量 VAD：自适应噪声底 + 双阈值切句 | ★ pilot `EnergyVad.kt` |
| `WakeVoiceprintGate.kt` | 声纹门禁：sherpa `SpeakerEmbeddingExtractor` + 余弦阈值 | ★ pilot `VoiceprintGate.kt` |
| `WakeConversation.kt` | 无 UI 对话：建会话 → `HeadlessChatRunner` 跑一轮 → 独立 TTS 播报 | 新写 |

配套改动：

- `AndroidManifest.xml`：`FOREGROUND_SERVICE_MICROPHONE` / `USE_FULL_SCREEN_INTENT` 权限，
  `.speech.wake.WakeWordService`（`foregroundServiceType="microphone"`）。
- `MinisApp.kt`：`onCreate` 里 `WakeWordController.init(this)`；
  `onActivityStarted` 从后台回前台时 `WakeWordController.onAppForegrounded(this)`。
- `AlarmReceiver.kt`：`BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` 时重新 `WakeWordController.init`，
  让开机/升级后唤醒词自动恢复。
- `BackgroundSettingsScreen.kt`：设置页新增「语音唤醒」分区（唤醒开关 / 唤醒词 / 悬浮球 / 唤醒回应 / 声纹开关 / 声纹阈值滑杆）。
- `SettingsScreen.kt`：主设置页新增顶层「语音唤醒」入口，直接跳到后台设置页的语音唤醒分区
  （`Routes.background(voiceWake = true)` → `?voice=1` → 滚动定位）。
- `scripts/deploy_voiceprint.sh`：把声纹模型（28MB）推到 `models/voiceprint/`。
- `ConfigBuiltins.kt`：`voice.wake` / `voice.wakeWord` / `voice.wakeOverlay` 三个配置项。
- `strings.xml`（`values` + `values-zh`）：文案中英双语。
- `build.gradle.kts`：`androidResources { noCompress += "onnx" }`（KWS 模型必须不压缩）。
- `scripts/deploy_kws.sh`：把 KWS 模型推到外部目录覆盖内置模型。
- `src/android/app/src/main/assets/kws/`：内置 KWS 模型（4.6MB，来自 pilot）。
- `src/android/app/src/test/java/com/openminis/app/speech/wake/KwsKeywordsTest.kt`：拼音映射单测。

---

## 2. 架构：一条闸门环

```
Application.onCreate
   └─ WakeWordController.init()  ──► startForegroundService(WakeWordService)
                                            │
                                    ┌───────▼────────┐
                                    │ 400ms 闸门循环  │
                                    └───────┬────────┘
              ┌─────────────────────────────┼─────────────────────────────┐
        条件满足（该听）              条件不满足（该让）            唤醒命中
              │                             │                          │
     startEngine(AudioRecord+KWS)   stopEngine()（释放麦克风）    KWS 关掉、麦克风留着
     acquireWakeLock()               状态 → PAUSED/ERROR           ＋ VAD 接管同一路音频
                                                                      ↓
                                                        「我在」→ 聆听 → 识别 → 语音回复
                                                        （全程不打开 App）
```

**闸门条件**（任一命中就不占用麦克风）：

1. 功能未开启
2. 没有 `RECORD_AUDIO`
3. App 在前台
4. 语音识别进行中（`SpeechRecognitionManager.state != IDLE`）
5. TTS 正在播报 / 合成
6. 刚唤醒完的 8 秒抑制期
7. 启动后的 5 秒初始化宽限期（见 §5.2）

**为什么必须有闸门**：同时持有第二个 `AudioRecord` 会让两边都只拿到静音；
在自己播报时继续听，助手会把自己唤醒。

**注意不要用 `VoiceModePrefs.isVoiceActive` 当闸门**：它只是输入框的 UI 状态，
在「带着语音面板退到后台」时会一直是 true —— 真机上验证过：后台 90 秒里
唤醒词被完全静音，必须重新打开 App 才恢复。真正持有麦克风的是
`SpeechRecognitionManager.state`（`STARTING`/`RECORDING`/`FINISHING`），
那个才是该看的信号。

**回前台的麦克风交接**：App 回到前台时除了靠闸门循环（≤400ms）释放麦克风，
还有一条快路径 —— `WakeWordController.onAppForegrounded()` →
`WakeWordService.releaseMicrophone()` 立刻释放，避免和用户点麦克风的动作抢设备。

---

## 3. ★ 三个反直觉的坑（pilot 实测踩过，直接继承）

### 3.1 音源必须用 `VOICE_RECOGNITION`，不能用 `VOICE_COMMUNICATION`

pilot `AudioCapture.kt:43` 的原话：

> `VOICE_RECOGNITION`：保留系统 AEC，同时对近场人声衰减比 `VOICE_COMMUNICATION` 小。
> **换 `VOICE_COMMUNICATION` 会把人声一起压低，实测识别率明显下降。**

直觉上会选 `VOICE_COMMUNICATION`（因为它专为通话设计、AEC 更激进），但对**近场唤醒词**来说，那套 AEC 是过度处理。

Minis 侧实现：`WakeAudioCapture.kt` 用 `MediaRecorder.AudioSource.VOICE_RECOGNITION`。

### 3.2 播报结束的瞬间，VAD 会把尾音切成一帧「用户说话」

pilot `AudioGate.kt:45` 的原话：

> 为什么需要它：播报**结束的瞬间**，VAD 缓冲里还有一段尾音，出来后会被切成一句话送 ASR，
> 识别成「音量已经是最大百分之一百」——**正好是刚才播的内容**。
> 实测直接导致 **agent 自己跟自己对话三轮**。

Minis 侧等价处理：闸门条件 6（TTS 播报中不放行）+ 唤醒后 8 秒抑制期。
Minis 的 ASR 链路本身已有 `MicInUse.kt` 占用闸，这里不重复造。

### 3.3 播报优先级不能用 `stop()` 互斥

pilot `Speaker.kt` 的设计：导航播报不可被打断但能打断普通播报；正在播导航则其他排队（最多 2 条）。

**本次未做** —— Minis 的 `TextToSpeechManager` 仍是「每次 speak() 打断上一次」。
这属于 §10 的 P1 范围，不影响唤醒功能本身。

---

## 4. 唤醒词与模型

### 4.1 默认唤醒词

**「你好米尼斯」**。

不能默认用「Minis」：KWS 是**中文拼音**模型，ICU 的汉字→拉丁转写不处理 ASCII，
`Minis` 会变成模型从没见过的 token。内置模型词表共 202 token，默认词的音节
`n ǐ h ǎo m ǐ n í s ī` 全部命中。

支持最多 4 个唤醒词，用逗号分隔（`WakeWordSettings.parseWords`）。
含模型词表里没有的音节的词会被**丢弃并写日志**，不会让整个引擎起不来。

### 4.2 模型位置与查找顺序

`WakeWordDetector.resolveModel()`：

1. `getExternalFilesDir(null)/models/kws` ← `scripts/deploy_kws.sh` 推的位置
2. `filesDir/models/kws`
3. 都没有 → 退回 APK 内置 `assets/kws/`（4.6MB）

内置资源（`src/android/app/src/main/assets/kws/`）：

| 文件 | 大小 | 说明 |
|---|---|---|
| `encoder.int8.onnx` | 3.99MB | zipformer2 encoder |
| `decoder.onnx` | 675KB | transducer decoder |
| `joiner.int8.onnx` | 65KB | transducer joiner |
| `tokens.txt` | 1.6KB | 202 个 token 的词表 |
| `keywords.txt` | **0 字节** | 必须为空文件，见下 |

**`keywords.txt` 为什么是空的**：native 解析器对每一行做 `stof`，
注释和非空内容都会让模型加载失败。真正的唤醒词是运行时用 `KwsKeywords` 生成
拼音行后，通过 `createStream()` 传进去的，不走这个文件。

**`.onnx` 必须不压缩**：`build.gradle.kts` 里 `noCompress += "onnx"`，
否则 sherpa 的 mmap 读取路径会失败。已用 zipfile 校验 APK 内三个 `.onnx` 均为 `ZIP_STORED`。

### 4.3 换词表 / 换模型

```sh
# 把 models/kws/ 推到手机（覆盖内置模型），改词表后不用重打包
./scripts/deploy_kws.sh
./scripts/deploy_kws.sh --check     # 只校验
./scripts/deploy_kws.sh --clear     # 删外部模型，退回内置
```

---

## 5. Android 14+ 的两个硬限制（本实现最花时间的地方）

### 5.1 后台起 microphone 前台服务会被拒绝

Android 12+ 禁止从后台启动前台服务，Android 14+ 更进一步：
`FOREGROUND_SERVICE_MICROPHONE` 必须声明，且应用得有**麦克风后台访问资格**
（用户切到过 App、或被临时加入白名单）。

应对（`WakeWordController` + `WakeWordService`）：

- `WakeWordService.start()` **返回 Boolean**，捕获 `ForegroundServiceStartNotAllowedException`。
- 启动被拒 → `WakeWordController.startRefused = true`，并把状态置为
  `ERROR /「后台录音服务未运行，打开 Minis 后会自动恢复」`。
- `MinisApp.onActivityStarted` 检测到「从后台回前台」→ `onAppForegrounded()` → 重试启动。
- `WakeWordService.onDestroy()` 时若功能仍开着，也走 `onServiceStartRefused()`，
  覆盖「服务被系统杀掉」的情况。

### 5.2 启动瞬间会抢麦克风

服务是在 `Application.onCreate` 里启动的 —— 那时 **Activity 还没起来**，
App 看起来像「在后台」，闸门会立刻放行去开麦克风，然后几百毫秒后又因为
App 变成前台而释放。这个来回既浪费又可能和应用内识别器抢占设备。

应对：`WakeWordService` 记 `serviceStartedAt`，闸门里加一条
`inGrace = now - serviceStartedAt < STARTUP_GRACE_MS`（5 秒），
宽限期内状态显示「正在初始化，稍后开始聆听」。

---

## 6. 唤醒之后发生了什么（★ 不打开 App）

**唤醒不再拉起 App**。整轮对话在后台服务里跑完，用户该干嘛干嘛。

1. KWS 命中 → `onWakeHit(word)`：写日志、发 `WAKING` 状态。
2. **麦克风不释放**：只把关键词检测关掉（`engine.setDetecting(false)`），
   同一路 AudioRecord 交给 VAD —— 用户下一句话就是请求，不能重新开麦。
3. `startConversation()`：
   - 立刻武装 VAD（`installVad()`）。**不能等「我在」说完再武装** ——
     用户是听到回应才接话的，等 1 秒多再武装，请求的前半句就没了。
   - 起一个协程说「我在」（旧版是 `ToneGenerator` 的 120ms 蜂鸣；
     「唤醒提示音」这个开关现在管的是这段语音）。
4. 助手自己的声音靠**时间窗**过滤，不靠延迟：
   - `ackPlaying` 为真时切出的片段 → 直接丢；
   - TTS 停后再等 `ECHO_TAIL_MS`（700ms）切出的片段 → 直接丢；
   - 片段文本开头的「我在」→ 剥掉（用户和回应挤进同一句时）。
5. VAD 切出一句 → `onUtterance()`：
   - **声纹门禁**（`passesVoiceprint`）：没有模板就用这句注册主人；
     有模板就比余弦分数，低于阈值（可调，默认 0.30）丢弃这句，继续等。
   - 端侧 SenseVoice 离线转写（`SpeechRecognitionManager.transcribeAudio`）。
   - 剥掉唤醒词残留（`stripWakeEcho`）。
6. `WakeConversation.ask()` → `HeadlessChatRunner.prompt(wait = true)` 跑一轮，
   会话是常驻的「语音对话」，所以「今天天气」→「那明天呢」能接上下文。
7. `WakeConversation.speak()` 用服务自己的 `TextToSpeechManager` 播报，
   同时镜像 `VoiceOutputState.isSpeaking`，让 App 内其它模块知道在放音。
8. 播完 + 1 秒回声保护 → 回到聆听，**8 秒窗口重新计时**。
9. 窗口到期还没有通过声纹的语音 → `sleepConversation()`，回到待命。

### 6.1 8 秒窗口的两个细节（真机踩出来的）

- **窗口到点时 VAD 里可能还留着半句**（用户没留够 600ms 静音）。
  这时候不能直接睡 —— 窗口一关，`segment` 就跟着 `reset()` 一起没了，
  用户说的话被静默丢弃。`handleConversationTick` 里先 `vad.flush()`
  把半句交出去处理，再给 20 秒 `FLUSH_GRACE_MS` 让它转写 + 回复。
- **识别为空不续期**：转写没出字就 `resumeListening(renewWindow = false)`，
  原来的 8 秒死线不变 —— 「8 秒内没有通过声纹的语音就休眠」是硬要求。

---

## 7. 悬浮球（桌面语音状态）

`VoiceStatusOverlay` 是**独立**实现，故意不复用 `ToolOverlayController`：
后者是左下角半屏胶囊，只在 agent turn 运行时存在；
悬浮球是贴右边的小圆球，只要唤醒功能开着就存在。

**形态：只有一个球，没有底板，没有文字。** 胶囊底板会挡住用户在做的事，
文字在 40dp 的圆里根本读不出来 —— 状态由颜色 + 图标 + 动画表达。

| 行为 | 说明 |
|---|---|
| 显示条件 | 功能开启 + 悬浮球开关打开 + **App 不在前台** |
| 尺寸 | 直径 40dp（比里面的麦克风图标 28dp 略大），绿色 85% 不透明度 |
| 状态颜色 | 绿=待命/聆听/回复 / 橙=PAUSED / 红=ERROR / 灰=OFF（同样 85% 透明度） |
| 待命（WAITING） | **无动画**，只有一圈静止描边 |
| 聆听（LISTENING / WAKING） | 原来的脉动动画（`RingMode.PULSE`） |
| 回复（REPLYING） | 麦克风图标换成小喇叭，动画变成三圈向外的声波（`RingMode.WAVE`） |
| 拖拽 | 触摸移动超过 slop 即进入拖拽，坐标按横竖屏分别存 |
| 点击 | 打开语音对话（同一个 `minis://action/voice_chat` deep link） |
| 权限 | 需要「显示在其他应用上层」；没有就静默不显示 |

通知：常驻通知 ID `9004`，频道 `minis_voice_wake`；唤醒提醒 ID `9005`，频道 `minis_voice_wake_alert`。

---

## 8. 验收清单（真机）

设备：`C:\Users\zhrl5\platform-tools\adb.exe -s 192.168.2.32:5555`
（华为安装会弹确认框，必须人工点）

| # | 场景 | 期望 |
|---|---|---|
| 1 | 亮屏、App 在前台 | 不监听（省电），悬浮球隐藏；App 内语音输入正常 |
| 2 | 退到后台 | 悬浮球出现，状态「待命」；常驻通知出现 |
| 3 | 退到后台喊「你好米尼斯」×3 | ≥2/3 命中；悬浮球变绿「唤醒中」；语音面板自动打开 |
| 4 | 锁屏喊「你好米尼斯」×3 | ≥2/3 命中；全屏 intent 拉起语音面板 |
| 5 | 息屏 10 分钟 | `voice_wake.log` 持续更新（不是停更） |
| 6 | 助手播报中 | 不自唤醒（闸门生效） |
| 7 | 播报结束立刻说半句 | 不被截断（8 秒抑制期不吞 ASR 结果） |
| 8 | 杀掉 App 再打开 | 功能自动恢复（`onAppForegrounded` 重试） |
| 9 | 重启手机 | 功能自动恢复（`BOOT_COMPLETED` 分支） |
| 10 | 悬浮球拖到边缘、点击 | 位置保留；点击进入语音对话 |

诊断日志：

```sh
ADB='/c/Users/zhrl5/platform-tools/adb.exe'; DEV=192.168.2.32:5555
$ADB -s $DEV shell "tail -40 /sdcard/Android/data/com.openminis.app.dev/files/voice_wake.log"
```

### 2026-11-13 已跑通的部分

设备：华为 ELS-AN00 / Android 12（`MDX0220A21035172`，USB）。
APK：`app-perf.apk` → `com.openminis.app.dev`。

| # | 结果 | 证据 |
|---|---|---|
| 2 | ✅ | 退到后台后 `WakeWordService` 变成前台服务（`foregroundId=9004`，频道 `minis_voice_wake`），桌面出现悬浮球 |
| 3 | ✅ | 真人喊「你好米尼斯」命中 → 悬浮球变绿「唤醒中」→ 语音面板自动打开（`START u0 ... MainActivity from uid 10491`） |
| 10 | ✅ | 悬浮球可拖拽，位置按横竖屏分别持久化（拖动后重新安装仍保留） |
| — | ✅ | 启动宽限期生效：日志 `PAUSED 正在初始化，稍后开始聆听` → 5 秒后才开麦 |
| — | ✅ | 唤醒时先停 `AudioRecord` 再拉起 App：`dumpsys audio` 显示 `rec stop` 早于 `START MainActivity`，音源是 `VOICE_RECOGNITION` |
| — | ✅ | 静默 90 秒无误唤醒 |
| — | ✅ | **后台带语音面板时唤醒词仍可用**（见下面的 bug 修复） |
| 1 | ✅ | App 在前台时悬浮球隐藏、麦克风不放行（`PAUSED 应用在前台`） |
| 4 | ⬜ | 锁屏 / 息屏唤醒待人工验收（需要手动锁屏） |
| 5 | ⬜ | 息屏 10 分钟持续收音待人工验收 |
| 9 | ⬜ | 开机自恢复待人工验收（需要重启手机） |

命中时会打一行诊断，区分「听到了用户」和「听到了自己」：

```
Minis.VoiceWake: 唤醒词命中「你好米尼斯」 peak=0.214 tts=false/false stt=IDLE fg=false
```

- `peak` —— 麦克风这段时间的最大 RMS（0..1）。命中时数值很高 → 现场很吵；
  数值很低 → 可能是模型阈值偏松。
- `tts` —— 命中瞬间 `VoiceOutputState.isSpeaking/isSynthesizing`。
  **如果这里是 `true`，就是助手听到了自己**（pilot §3.2 的坑），
  要修的是 TTS 闸门而不是模型。
- `stt` / `fg` —— 识别状态与前后台状态。

#### 真机上发现并修掉的一个 bug

**现象**：带语音面板退到后台后，唤醒词被完全静音，日志停在
`PAUSED 语音输入进行中`，直到重新打开 App 才恢复。

**原因**：闸门里用了 `VoiceModePrefs.isVoiceActive`。它是**输入框的 UI 状态**，
不是「谁持有麦克风」——带着语音面板退到后台时它一直是 `true`。

**修法**：闸门改看 `SpeechRecognitionManager.state`
（`STARTING`/`RECORDING`/`FINISHING` 才算真的在录音）。
`foreground` 已经挡住了前台抢麦的场景，`isVoiceActive` 是多余的。

### 厂商后台限制（华为 EMUI，真机必踩）

App 只能引导，**必须用户手动设置**：

- 设置 → 应用 → 应用启动管理 → Minis → **手动管理**：允许自启动、允许关联启动、允许后台活动。
- 设置 → 电池 → 应用耗电管理 → Minis → **允许后台活动** / 不受限制。
- 设置 → 显示和亮度 → **允许后台弹出界面**（锁屏唤醒靠它）。
- 设置 → 通知 → Minis → 允许通知（常驻通知是前台服务的必需品）。

---

## 9. 构建与通道（同端侧模型项目）

| 项 | 值 |
|---|---|
| 源码 | `D:\minis-src`（权威），沙箱副本 `/var/minis/workspace/minis-mnn` |
| 编译 | `set "JAVA_HOME=D:\Android\jdk-17"` + `gradlew.bat assembleGithubPerf` |
| 改完代码没同步就编译 | `BUILD SUCCESSFUL in 12s` 是 UP-TO-DATE，APK 没变；**必须核对 APK 时间戳** |
| adb | `C:\Users\zhrl5\platform-tools\adb.exe -s 192.168.2.32:5555` |
| 装机 | `adb install -r`，★ **华为会弹确认框，必须人工点** |

已验证的构建命令（2026-11-13）：

```sh
export JAVA_HOME='D:\Android\jdk-17'
export ANDROID_HOME='D:\Android\Sdk'
cd D:\minis-src\src\android
./gradlew.bat :app:compileGithubPerfKotlin --no-daemon
./gradlew.bat :app:testDebugUnitTest --tests 'com.openminis.app.speech.wake.KwsKeywordsTest' --no-daemon
./gradlew.bat assembleGithubPerf --no-daemon
```

APK：`src/android/app/build/outputs/apk/perf/app-perf.apk`

---

## 10. 还没做的（按优先级）

| # | 项 | 说明 |
|---|---|---|
| P1 | **声纹门控** | WeSpeaker 25MB + 余弦相似度 0.3，实现「只有我能叫醒」。pilot 的教训：声纹拒绝后若用户确实没开口，1.2 秒后要收回聆听，否则一直「正在聆听」不休眠。 |
| P1 | **TTS 播报队列** | 见 §3.3，`TextToSpeechManager` 还没有队列概念。 |
| P1 | **确认 TTS 闸门可靠** | `VoiceOutputState.isSpeaking` 只由「持有悬浮胶囊的播放器」写入；聊天页的回话播放器未必是它。本轮真人唤醒未触发自唤醒，但**助手自己说话时唤醒词仍可能误触发**，需要专门测一轮（让助手播报时后台静默观察 `tts=true` 的命中）。 |
| P2 | **功耗实测** | sherpa KWS 约 2–5% 单核；息屏 8 小时耗电必须实测。这条线的最大风险不是技术，是功耗。 |
| P2 | **多唤醒词并发** | 目前一次只喂 4 个词进同一个 spotter。 |
| P3 | **声纹模型可插拔** | 跟 KWS 一样走外部目录覆盖。 |

---

## 11. 与端侧模型项目的关系

两者独立，但共享基础设施：

- 共用 `AssistantFastPath` 的模式开关思路（默认关、deep-link 可切、失败即回退）
- 共用诊断文件机制（`getExternalFilesDir(null)/*.log`）
- **唤醒词唤醒后可以直接走端侧快速路径** —— 喊一声 → 说「附近有超市吗」
  → 端侧 2 秒出结果，**全程不联网、不调云端**。这是这个方案最终的产品形态。

---

## 12. 一句话提醒

**语音这条线最大的风险不是技术，是功耗和稳定性。**
一个每 5 秒误唤醒一次、或者息屏 8 小时掉 40% 电的语音助手，功能再全也没人用。

所以 §8 的第 5、6、7 项必须在真机上过一遍，不能只看编译通过。
