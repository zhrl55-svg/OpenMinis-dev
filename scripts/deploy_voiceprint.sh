#!/usr/bin/env bash
#
# 把声纹模型推到手机上的 Minis。
#
# 为什么需要这一步：声纹模型 **没有内置在 APK 里**。
# `campplus_sv_zh_16k.onnx` 有 28MB，塞进 assets 会让每个安装包都变大，
# 而且它只在开启「声纹识别」时才用得上 —— 所以走外部文件，跟
# SenseVoice / KWS 的部署模式一致（见 scripts/deploy_sensevoice.sh、
# scripts/deploy_kws.sh）。
#
# WakeVoiceprintGate.modelFile() 的查找顺序：
#   1. getExternalFilesDir(null)/models/voiceprint   ← 本脚本推的位置
#   2. filesDir/models/voiceprint
#   3. 都没有 → 声纹门禁降级为「放行」（软门禁，绝不把主人挡在门外）
#
# 模型说明：
#   campplus_sv_zh_16k.onnx   sherpa 官方 3D-Speaker 构建，元数据带
#                              framework=3d-speaker，native 侧能正常初始化
#   cnceleb_resnet34_LM.onnx  原始 WeSpeaker resnet34，**没有** sherpa
#                              能识别的元数据：构造静默失败，下一次
#                              createStream 直接 SIGSEGV（pilot 崩过五次），
#                              只作为最后的兜底

set -euo pipefail

# Git Bash / MSYS 会把 `/data/local/tmp/...` 这类参数改写成 Windows 路径，
# adb push 的目标就变成 D:/Program Files/...（实测报 remote secure_mkdirs failed）。
export MSYS_NO_PATHCONV=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# 模型来源：优先 models/voiceprint/（不入库的本地目录），
# 否则退到 pilot 项目的 models/voiceprint/。
MODEL_FILE="campplus_sv_zh_16k.onnx"
MODEL_DIR="$PROJECT_ROOT/models/voiceprint"
if [ ! -f "$MODEL_DIR/$MODEL_FILE" ] && [ -d "/d/phone agent/models/voiceprint" ]; then
  MODEL_DIR="/d/phone agent/models/voiceprint"
fi

# 与 WakeVoiceprintGate.MODEL_CANDIDATES 对齐：小于 1MB 的文件不算模型
MIN_MODEL_BYTES=$((1 * 1024 * 1024))

CHECK_ONLY=0
CLEAR_ONLY=0
TARGET=""
for arg in "$@"; do
  case "$arg" in
    --check) CHECK_ONLY=1 ;;
    --clear) CLEAR_ONLY=1 ;;
    -h|--help) sed -n '2,36p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) TARGET="$arg" ;;
  esac
done

# 关了 MSYS 路径转换之后，本地路径也得换成 Windows 形式喂给 adb
winpath() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

say() { printf '%s\n' "$*" >&2; }
die() { printf '错误：%s\n' "$*" >&2; exit 1; }

# ── 1. 找设备 ──────────────────────────────────────────────────────────
command -v adb >/dev/null 2>&1 || die "找不到 adb（PATH 里没有）"

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

# ── 2. 找包名 ──────────────────────────────────────────────────────────
PKG=""
for candidate in com.openminis.app.dev com.openminis.app; do
  if "${ADB[@]}" shell pm list packages "$candidate" 2>/dev/null | tr -d '\r' | grep -qx "package:$candidate"; then
    PKG="$candidate"
    break
  fi
done
[ -n "$PKG" ] || die "设备上没装 Minis（com.openminis.app[.dev]）"
say "包名：$PKG"

REMOTE_DIR="/sdcard/Android/data/$PKG/files/models/voiceprint"

remote_size() {
  "${ADB[@]}" shell "stat -c %s '$REMOTE_DIR/$1' 2>/dev/null" 2>/dev/null \
    | tr -d '\r' | head -1 || true
}

# ── 3. --clear：删外部模型 ─────────────────────────────────────────────
if [ "$CLEAR_ONLY" -eq 1 ]; then
  "${ADB[@]}" shell "rm -rf '$REMOTE_DIR'" || true
  say "已删除 $REMOTE_DIR，声纹门禁会降级为放行。"
  exit 0
fi

# ── 4. 本地模型检查 ────────────────────────────────────────────────────
[ -d "$MODEL_DIR" ] || die "没有模型目录：$MODEL_DIR"
[ -f "$MODEL_DIR/$MODEL_FILE" ] || die "缺文件：$MODEL_DIR/$MODEL_FILE"
local_size=$(wc -c < "$MODEL_DIR/$MODEL_FILE" | tr -d ' ')
[ "$local_size" -gt "$MIN_MODEL_BYTES" ] || die "$MODEL_FILE 只有 $local_size 字节，像是没下完"
say "模型来源：$MODEL_DIR/$MODEL_FILE（$((local_size / 1024 / 1024))MB）"

rsize=$(remote_size "$MODEL_FILE")
if [ "${rsize:-0}" = "$local_size" ]; then
  say "已存在，跳过：$MODEL_FILE"
  need_push=0
else
  say "待推送：$MODEL_FILE（设备上 ${rsize:-无} → 本地 $local_size）"
  need_push=1
fi

if [ "$CHECK_ONLY" -eq 1 ]; then
  [ "$need_push" -eq 0 ] && say "校验通过：外部模型就位" || say "校验未通过：需要部署"
  [ "$need_push" -eq 0 ] || exit 2
  exit 0
fi

# ── 5. 推送 ────────────────────────────────────────────────────────────
if [ "$need_push" -eq 1 ]; then
  "${ADB[@]}" shell "mkdir -p '$REMOTE_DIR'"
  say "推送 $MODEL_FILE …"
  # 先推到 /data/local/tmp 再 cp：Android 11+ 直接 push 到 Android/data
  # 有时会被 FUSE 挡住一半（大文件尤其），分两步最稳。
  "${ADB[@]}" push "$(winpath "$MODEL_DIR/$MODEL_FILE")" "/data/local/tmp/$MODEL_FILE" >/dev/null
  "${ADB[@]}" shell "cp '/data/local/tmp/$MODEL_FILE' '$REMOTE_DIR/$MODEL_FILE' && rm -f '/data/local/tmp/$MODEL_FILE'"
fi

# ── 6. 校验 ────────────────────────────────────────────────────────────
rsize=$(remote_size "$MODEL_FILE")
if [ "${rsize:-0}" = "$local_size" ]; then
  say "✓ $MODEL_FILE $local_size 字节"
else
  die "推送后校验失败（设备 ${rsize:-无} vs 本地 $local_size），重跑一次"
fi
say "完成。下次唤醒命中并说第一句话时，会注册/校验声纹。"
