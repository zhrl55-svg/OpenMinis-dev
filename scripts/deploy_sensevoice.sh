#!/usr/bin/env bash
#
# 把端侧识别模型（SenseVoice-Small int8）推到手机上的 Minis。
#
# 为什么需要这一步：模型不进 APK，也不入库。LocalSenseVoiceEngine 运行时按
# 这个顺序找 model.int8.onnx + tokens.txt：
#   1. getExternalFilesDir(null)/models/asr-sensevoice   ← 本脚本推的位置
#   2. filesDir/models/asr-sensevoice
# 两个都没有时 isAvailable=false，语音输入自动回退到云端/系统识别。
#
# 模型来源（两份文件，别只拷一个）：
#   model.int8.onnx   229MB
#   tokens.txt        309KB
#   huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17
# 国内直连不通的话，从已有设备上拷：
#   adb shell run-as com.navagent cat files/models/asr-sensevoice/model.int8.onnx > model.int8.onnx
# 或者直接放进 models/asr-sensevoice/（该目录已 gitignore）。
#
# 用法：
#   ./scripts/deploy_sensevoice.sh              # 推到唯一连着的设备
#   ./scripts/deploy_sensevoice.sh <serial>     # 指定设备（adb devices 里的 serial）
#   ./scripts/deploy_sensevoice.sh --check      # 只校验，不推
#
# 幂等：设备上体积已经对得上的文件会跳过，重复跑几乎不耗时间。

set -euo pipefail

# Git Bash / MSYS 会把 `/data/local/tmp/...` 这类参数改写成 Windows 路径，
# adb push 的目标就变成 D:/Program Files/...（实测报 remote secure_mkdirs failed）。
# 只为这个脚本关掉路径转换，不影响调用方。
export MSYS_NO_PATHCONV=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MODEL_DIR="$PROJECT_ROOT/models/asr-sensevoice"

MODEL_FILE="model.int8.onnx"
TOKENS_FILE="tokens.txt"
# 与 LocalSenseVoiceEngine 的 MIN_MODEL_BYTES / MIN_TOKENS_BYTES 对齐
MIN_MODEL_BYTES=$((20 * 1024 * 1024))
MIN_TOKENS_BYTES=$((10 * 1024))

CHECK_ONLY=0
TARGET=""
for arg in "$@"; do
  case "$arg" in
    --check) CHECK_ONLY=1 ;;
    -h|--help) sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) TARGET="$arg" ;;
  esac
done

# 关了 MSYS 路径转换之后，本地路径也得换成 Windows 形式喂给 adb，
# 否则 adb 看到的是 /d/minis-src/... 这种 MSYS 写法（它不认）。
winpath() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

say() { printf '%s\n' "$*" >&2; }
die() { printf '错误：%s\n' "$*" >&2; exit 1; }

# ── 1. 本地模型检查 ────────────────────────────────────────────────────
[ -d "$MODEL_DIR" ] || die "没有模型目录：$MODEL_DIR（见脚本头部说明）"

for f in "$MODEL_FILE" "$TOKENS_FILE"; do
  [ -f "$MODEL_DIR/$f" ] || die "缺文件：models/asr-sensevoice/$f"
done

model_size=$(wc -c < "$MODEL_DIR/$MODEL_FILE" | tr -d ' ')
tokens_size=$(wc -c < "$MODEL_DIR/$TOKENS_FILE" | tr -d ' ')
[ "$model_size" -gt "$MIN_MODEL_BYTES" ] || die "$MODEL_FILE 只有 $model_size 字节，像是没下完"
[ "$tokens_size" -gt "$MIN_TOKENS_BYTES" ] || die "$TOKENS_FILE 只有 $tokens_size 字节，像是没下完"
say "本地模型：$MODEL_FILE $((model_size / 1024 / 1024))MB，$TOKENS_FILE $((tokens_size / 1024))KB"

# ── 2. 找设备 ──────────────────────────────────────────────────────────
command -v adb >/dev/null 2>&1 || die "找不到 adb（PATH 里没有）"

# 台面上经常同时连着真机和另一台测试机。比起让用户每次填 serial，
# 更省事的做法是：多台时只留装了 Minis 的那些。
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

REMOTE_DIR="/sdcard/Android/data/$PKG/files/models/asr-sensevoice"

# ── 4. 比对体积，只推缺的 ──────────────────────────────────────────────
# 文件不存在时 stat 返回 1；脚本开了 pipefail，不加 || true 会让
# `rsize=$(remote_size ...)` 直接触发 set -e 退出（这版踩过一次：
# 表现为只跳过第一个文件就静默结束）。
remote_size() {
  "${ADB[@]}" shell "stat -c %s '$REMOTE_DIR/$1' 2>/dev/null" 2>/dev/null \
    | tr -d '\r' | head -1 || true
}

need_push=0
for f in "$MODEL_FILE" "$TOKENS_FILE"; do
  local_size=$(wc -c < "$MODEL_DIR/$f" | tr -d ' ')
  rsize=$(remote_size "$f")
  if [ "${rsize:-0}" = "$local_size" ]; then
    say "已存在，跳过：$f"
  else
    say "待推送：$f（设备上 ${rsize:-无} → 本地 $local_size）"
    need_push=1
  fi
done

if [ "$CHECK_ONLY" -eq 1 ]; then
  [ "$need_push" -eq 0 ] && say "校验通过：模型就位" || say "校验未通过：需要部署"
  [ "$need_push" -eq 0 ] || exit 2
  exit 0
fi

# ── 5. 推送 ────────────────────────────────────────────────────────────
"${ADB[@]}" shell "mkdir -p '$REMOTE_DIR'"

for f in "$MODEL_FILE" "$TOKENS_FILE"; do
  local_size=$(wc -c < "$MODEL_DIR/$f" | tr -d ' ')
  # 注意别写成 `[ ... ] && continue`：set -e 下条件为假会让整个脚本静默退出，
  # 表现就是「跳过第一轮之后就什么都不做了」（这版踩过）。
  if [ "$(remote_size "$f")" = "$local_size" ]; then
    continue
  fi
  say "推送 $f …"
  # 先推到 /data/local/tmp 再 cp：Android 11+ 直接 push 到 Android/data
  # 有时会被 FUSE 挡住一半（大文件尤其），分两步最稳。
  "${ADB[@]}" push "$(winpath "$MODEL_DIR/$f")" "/data/local/tmp/$f" >/dev/null
  "${ADB[@]}" shell "cp '/data/local/tmp/$f' '$REMOTE_DIR/$f' && rm -f '/data/local/tmp/$f'"
done

# ── 6. 校验 ────────────────────────────────────────────────────────────
ok=1
for f in "$MODEL_FILE" "$TOKENS_FILE"; do
  local_size=$(wc -c < "$MODEL_DIR/$f" | tr -d ' ')
  rsize=$(remote_size "$f")
  if [ "${rsize:-0}" = "$local_size" ]; then
    say "✓ $f $local_size 字节"
  else
    say "✗ $f 体积不符（设备 ${rsize:-无} vs 本地 $local_size）"
    ok=0
  fi
done

[ "$ok" -eq 1 ] || die "推送后校验失败，重跑一次；仍失败就手动检查 $REMOTE_DIR"
say "完成。重启 Minis 后生效（LocalSenseVoiceEngine 在载入时解析模型目录）。"
