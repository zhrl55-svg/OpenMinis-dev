#!/usr/bin/env bash
#
# 把端侧小模型（Pocket-0.3B，MNN 格式）推到手机上的 Minis。
#
# 为什么需要这一步：模型不进 APK，也不入库。LocalMnnModel 运行时按
# 这个顺序找 llm.mnn + llm.mnn.weight + tokenizer.mtok + config.json：
#   1. getExternalFilesDir(null)/models/mnn-llm-0.3b   ← 本脚本推的位置
#   2. filesDir/models/mnn-llm-0.3b
# 都没有时 isAvailable()=false，端侧路径自动跳过、回退云端，不报错。
#
# 模型来源（5 个文件，缺一个都加载不了）：
#   llm.mnn.weight    164MB  int4 权重
#   llm.mnn            931KB 计算图
#   tokenizer.mtok     1.6MB 词表
#   llm_config.json    3.2KB 结构 + jinja 模板
#   config.json         502B 运行参数
#   huggingface.co/taobao-mnn/MNN-Pocket-0.3B-MNN
# 国内直连不通的话用 hf-mirror.com：
#   https://hf-mirror.com/taobao-mnn/MNN-Pocket-0.3B-MNN/resolve/main/<file>
#
# 用法：
#   ./scripts/deploy_mnn_llm.sh              # 推到唯一连着的设备
#   ./scripts/deploy_mnn_llm.sh <serial>     # 指定设备（adb devices 里的 serial）
#   ./scripts/deploy_mnn_llm.sh --check      # 只校验，不推
#
# 幂等：设备上体积已经对得上的文件会跳过，重复跑几乎不耗时间。

set -euo pipefail

# Git Bash / MSYS 会把 `/data/local/tmp/...` 这类参数改写成 Windows 路径，
# adb push 的目标就变成 D:/Program Files/...（实测报 remote secure_mkdirs failed）。
# 只为这个脚本关掉路径转换，不影响调用方。
export MSYS_NO_PATHCONV=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MODEL_DIR="$PROJECT_ROOT/models/mnn-llm-0.3b"

MODEL_FILE="llm.mnn.weight"
GRAPH_FILE="llm.mnn"
TOKENIZER_FILE="tokenizer.mtok"
LLM_CONFIG_FILE="llm_config.json"
RUNTIME_CONFIG_FILE="config.json"

# 与 LocalMnnModel 的检查对齐：权重 164MB / 计算图 931KB / 词表 1.6MB
MIN_WEIGHT_BYTES=$((100 * 1024 * 1024))
MIN_GRAPH_BYTES=$((256 * 1024))
MIN_TOKENIZER_BYTES=$((512 * 1024))

CHECK_ONLY=0
TARGET=""
for arg in "$@"; do
  case "$arg" in
    --check) CHECK_ONLY=1 ;;
    -h|--help) sed -n '2,35p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) TARGET="$arg" ;;
  esac
done

# 关了 MSYS 路径转换之后，本地路径也得换成 Windows 形式喂给 adb，
# 否则 adb 看到的是 /d/minis-src/... 这种 POSIX 写法（它是 Windows 程序，不认）。
# 这台机的 Git Bash 是 WSL 风格挂载：/mnt/d/... ↔ D:\...
# 所以先把 POSIX 绝对路径转回 Windows 绝对路径，再交给 adb。
winpath() {
  local p="$1"
  case "$p" in
    /mnt/[a-z]/*)
      local rest="${p#/mnt/}"
      local drive="${rest%%/*}"
      local tail="${rest#*/}"
      drive="$(printf '%s' "$drive" | tr '[:lower:]' '[:upper:]')"
      printf '%s:\\%s' "$drive" "$(printf '%s' "$tail" | tr '/' '\\')"
      ;;
    *)
      if command -v cygpath >/dev/null 2>&1; then cygpath -w "$p"; else printf '%s' "$p"; fi
      ;;
  esac
}

say() { printf '%s\n' "$*" >&2; }
die() { printf '错误：%s\n' "$*" >&2; exit 1; }

# ── 1. 本地模型检查 ────────────────────────────────────────────────────
[ -d "$MODEL_DIR" ] || die "没有模型目录：$MODEL_DIR（见脚本头部说明）"

for f in "$MODEL_FILE" "$GRAPH_FILE" "$TOKENIZER_FILE" "$LLM_CONFIG_FILE" "$RUNTIME_CONFIG_FILE"; do
  [ -f "$MODEL_DIR/$f" ] || die "缺文件：models/mnn-llm-0.3b/$f"
done

weight_size=$(wc -c < "$MODEL_DIR/$MODEL_FILE" | tr -d ' ')
graph_size=$(wc -c < "$MODEL_DIR/$GRAPH_FILE" | tr -d ' ')
tok_size=$(wc -c < "$MODEL_DIR/$TOKENIZER_FILE" | tr -d ' ')
[ "$weight_size" -gt "$MIN_WEIGHT_BYTES" ] || die "$MODEL_FILE 只有 $weight_size 字节，像是没下完"
[ "$graph_size" -gt "$MIN_GRAPH_BYTES" ] || die "$GRAPH_FILE 只有 $graph_size 字节，像是没下完"
[ "$tok_size" -gt "$MIN_TOKENIZER_BYTES" ] || die "$TOKENIZER_FILE 只有 $tok_size 字节，像是没下完"
say "本地模型：权重 $((weight_size / 1024 / 1024))MB，计算图 $((graph_size / 1024))KB，词表 $((tok_size / 1024))KB"

# ── 2. 找设备 ──────────────────────────────────────────────────────────
# adb 不一定在 Git Bash 的 PATH 里（cmd 的 set PATH 不传进来）。
# 先找 PATH 里的，找不到再试几个常见的 SDK 安装位置。
ADB_BIN=""
if command -v adb >/dev/null 2>&1; then
  ADB_BIN="$(command -v adb)"
else
  # 这台机的 Git Bash 是 WSL 风格挂载（/mnt/c），不是 MSYS（/c）——
  # 两套路径都试一遍，否则一条都命中不了。
  for c in "$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe" \
           "/mnt/c/Users/$USER/platform-tools/adb.exe" \
           "/mnt/c/Users/$USER/AppData/Local/Android/Sdk/platform-tools/adb.exe" \
           "/c/Users/$USER/platform-tools/adb.exe" \
           "/d/Android/Sdk/platform-tools/adb.exe"; do
    [ -f "$c" ] && ADB_BIN="$c" && break
  done
fi
[ -n "$ADB_BIN" ] || die "找不到 adb（PATH 里没有，也没在常见 SDK 位置找到）"
adb() { "$ADB_BIN" "$@"; }
say "adb：$ADB_BIN"

has_minis() {
  local serial="$1" pkg
  for pkg in com.openminis.app.dev com.openminis.app; do
    if adb -s "$serial" shell pm list packages "$pkg" 2>/dev/null | tr -d '\r' | grep -qx "package:$pkg"; then
      return 0
    fi
  done
  return 1
}

if [ -z "$TARGET" ]; then
  mapfile -t devices < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  [ "${#devices[@]}" -gt 0 ] || die "没有连着的设备（adb devices 是空的）"
  if [ "${#devices[@]}" -gt 1 ]; then
    candidates=()
    for d in "${devices[@]}"; do
      has_minis "$d" && candidates+=("$d")
    done
    if [ "${#candidates[@]}" -eq 1 ]; then
      say "连着 ${#devices[@]} 台设备，其中装了 Minis 的只有 ${candidates[0]}，用它"
      devices=("${candidates[0]}")
    else
      die "连着 ${#devices[@]} 台设备，请指定 serial：${devices[*]}"
    fi
  fi
  TARGET="${devices[0]}"
fi
ADB=(adb -s "$TARGET")
say "设备：$TARGET"

# ── 3. 找包名 ──────────────────────────────────────────────────────────
# 开发机装的是 com.openminis.app.dev（applicationIdSuffix），正式包是 com.openminis.app。
# 两个都在时优先 dev —— 调试阶段人看的就是它。
PKG=""
for candidate in com.openminis.app.dev com.openminis.app; do
  if "${ADB[@]}" shell pm list packages "$candidate" 2>/dev/null | tr -d '\r' | grep -qx "package:$candidate"; then
    PKG="$candidate"
    break
  fi
done
[ -n "$PKG" ] || die "设备上没装 Minis（com.openminis.app[.dev]）"
say "包名：$PKG"

REMOTE_DIR="/sdcard/Android/data/$PKG/files/models/mnn-llm-0.3b"

# ── 4. 比对体积，只推缺的 ──────────────────────────────────────────────
# 文件不存在时 stat 返回 1；脚本开了 pipefail，不加 || true 会让
# `rsize=$(remote_size ...)` 直接触发 set -e 退出。
remote_size() {
  "${ADB[@]}" shell "stat -c %s '$REMOTE_DIR/$1' 2>/dev/null" 2>/dev/null \
    | tr -d '\r' | head -1 || true
}

need_push=0
for f in "$MODEL_FILE" "$GRAPH_FILE" "$TOKENIZER_FILE" "$LLM_CONFIG_FILE" "$RUNTIME_CONFIG_FILE"; do
  local_size=$(wc -c < "$MODEL_DIR/$f" | tr -d ' ')
  rsize=$(remote_size "$f")
  if [ "${rsize:-0}" = "$local_size" ]; then
    say "已存在，跳过：$f"
  else
    say "需要推送：$f（本地 $local_size / 设备 ${rsize:-0}）"
    need_push=1
  fi
done

if [ "$CHECK_ONLY" -eq 1 ]; then
  say "--check：仅检查，未推送"
  exit 0
fi
[ "$need_push" -eq 1 ] || { say "设备上的模型已完整，无需推送"; exit 0; }

# ── 5. 建目录并推送 ────────────────────────────────────────────────────
# /sdcard/Android/data/<pkg>/ 在 Android 11+ 是 App 私有目录，adb shell 写不进去
# （mkdir 报 Permission denied）。用 run-as 在 App 自己的上下文里建——
# debuggable 的包才行；非 debuggable 就要靠 App 自己在首次运行后建目录。
"${ADB[@]}" shell "mkdir -p '$REMOTE_DIR'" 2>/dev/null || true
if ! "${ADB[@]}" shell "ls '$REMOTE_DIR'" >/dev/null 2>&1; then
  die "无法访问 $REMOTE_DIR（App 私有目录）。请先在手机上启动一次 Minis，再重跑。"
fi

for f in "$MODEL_FILE" "$GRAPH_FILE" "$TOKENIZER_FILE" "$LLM_CONFIG_FILE" "$RUNTIME_CONFIG_FILE"; do
  say "推送 $f ..."
  "${ADB[@]}" push "$(winpath "$MODEL_DIR/$f")" "$REMOTE_DIR/$f" >/dev/null
  got=$(remote_size "$f")
  want=$(wc -c < "$MODEL_DIR/$f" | tr -d ' ')
  [ "${got:-0}" = "$want" ] || die "$f 推送后大小不符（设备 ${got:-0} / 本地 $want）"
  say "  ✓ $f（$((want / 1024 / 1024))MB）"
done

say "端侧模型部署完成：$REMOTE_DIR"
say "提示：端侧推理只在 arm64 真机上可用（.so 只有 arm64-v8a）。"
