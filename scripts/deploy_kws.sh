#!/usr/bin/env bash
#
# 把唤醒词（KWS）模型推到手机上的 Minis。
#
# 为什么需要这一步：唤醒词模型**已经内置在 APK 里**
# （src/android/app/src/main/assets/kws/，约 4.6MB），正常情况下不用推。
# 但内置模型一旦改词表就得重新打包，所以留一个外部覆盖的口子 ——
# 跟 SenseVoice 的部署模式一致（见 scripts/deploy_sensevoice.sh）。
#
# WakeWordDetector.resolveModel() 的查找顺序：
#   1. getExternalFilesDir(null)/models/kws   ← 本脚本推的位置
#   2. filesDir/models/kws
#   3. 都没有 → 退回 APK 内置的 assets/kws
#
# 四个文件缺一不可（decoder/encoder/joiner/tokens），少一个就整体退回内置模型。
# keywords.txt 必须是**空文件**：native 解析器会对每一行做 stof，
# 注释和非空内容都会让模型加载失败；真正的唤醒词是运行时生成后
# 通过 createStream() 传进去的，不走这个文件。
#
# 用法：
#   ./scripts/deploy_kws.sh                # 推到唯一连着的设备
#   ./scripts/deploy_kws.sh <serial>       # 指定设备（adb devices 里的 serial）
#   ./scripts/deploy_kws.sh --check        # 只校验，不推
#   ./scripts/deploy_kws.sh --clear        # 删掉外部模型，退回内置
#
# 幂等：设备上体积已经对得上的文件会跳过，重复跑几乎不耗时间。

set -euo pipefail

# Git Bash / MSYS 会把 `/data/local/tmp/...` 这类参数改写成 Windows 路径，
# adb push 的目标就变成 D:/Program Files/...（实测报 remote secure_mkdirs failed）。
# 只为这个脚本关掉路径转换，不影响调用方。
export MSYS_NO_PATHCONV=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# 模型来源：优先 models/kws/（不入库的本地目录），否则用 APK 内置的那份。
EXTERNAL_SRC="$PROJECT_ROOT/models/kws"
ASSET_SRC="$PROJECT_ROOT/src/android/app/src/main/assets/kws"
if [ -d "$EXTERNAL_SRC" ]; then
  MODEL_DIR="$EXTERNAL_SRC"
else
  MODEL_DIR="$ASSET_SRC"
fi

ENCODER_FILE="encoder.int8.onnx"
DECODER_FILE="decoder.onnx"
JOINER_FILE="joiner.int8.onnx"
TOKENS_FILE="tokens.txt"
KEYWORDS_FILE="keywords.txt"

# 与 WakeWordDetector 的 MIN_*_BYTES 对齐
MIN_ENCODER_BYTES=$((1 * 1024 * 1024))
MIN_DECODER_BYTES=$((100 * 1024))
MIN_JOINER_BYTES=$((20 * 1024))
MIN_TOKENS_BYTES=1024

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

# 关了 MSYS 路径转换之后，本地路径也得换成 Windows 形式喂给 adb，
# 否则 adb 看到的是 /d/minis-src/... 这种 MSYS 写法（它不认）。
winpath() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

say() { printf '%s\n' "$*" >&2; }
die() { printf '错误：%s\n' "$*" >&2; exit 1; }

# ── 1. 找设备 ──────────────────────────────────────────────────────────
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

# ── 2. 找包名 ──────────────────────────────────────────────────────────
# 开发机装的是 com.openminis.app.dev（applicationIdSuffix），正式包是 com.openminis.app。
PKG=""
for candidate in com.openminis.app.dev com.openminis.app; do
  if "${ADB[@]}" shell pm list packages "$candidate" 2>/dev/null | tr -d '\r' | grep -qx "package:$candidate"; then
    PKG="$candidate"
    break
  fi
done
[ -n "$PKG" ] || die "设备上没装 Minis（com.openminis.app[.dev]）"
say "包名：$PKG"

REMOTE_DIR="/sdcard/Android/data/$PKG/files/models/kws"

# 文件不存在时 stat 返回 1；脚本开了 pipefail，不加 || true 会让
# `rsize=$(remote_size ...)` 直接触发 set -e 退出。
remote_size() {
  "${ADB[@]}" shell "stat -c %s '$REMOTE_DIR/$1' 2>/dev/null" 2>/dev/null \
    | tr -d '\r' | head -1 || true
}

# ── 3. --clear：删外部模型，退回内置 ───────────────────────────────────
if [ "$CLEAR_ONLY" -eq 1 ]; then
  "${ADB[@]}" shell "rm -rf '$REMOTE_DIR'" || true
  say "已删除 $REMOTE_DIR，下次唤醒词载入时退回 APK 内置模型。"
  exit 0
fi

# ── 4. 本地模型检查 ────────────────────────────────────────────────────
[ -d "$MODEL_DIR" ] || die "没有模型目录：$MODEL_DIR（见脚本头部说明）"
say "模型来源：$MODEL_DIR"

for f in "$ENCODER_FILE" "$DECODER_FILE" "$JOINER_FILE" "$TOKENS_FILE"; do
  [ -f "$MODEL_DIR/$f" ] || die "缺文件：$MODEL_DIR/$f"
done

encoder_size=$(wc -c < "$MODEL_DIR/$ENCODER_FILE" | tr -d ' ')
decoder_size=$(wc -c < "$MODEL_DIR/$DECODER_FILE" | tr -d ' ')
joiner_size=$(wc -c < "$MODEL_DIR/$JOINER_FILE" | tr -d ' ')
tokens_size=$(wc -c < "$MODEL_DIR/$TOKENS_FILE" | tr -d ' ')
[ "$encoder_size" -gt "$MIN_ENCODER_BYTES" ] || die "$ENCODER_FILE 只有 $encoder_size 字节，像是没下完"
[ "$decoder_size" -gt "$MIN_DECODER_BYTES" ] || die "$DECODER_FILE 只有 $decoder_size 字节，像是没下完"
[ "$joiner_size" -gt "$MIN_JOINER_BYTES" ] || die "$JOINER_FILE 只有 $joiner_size 字节，像是没下完"
[ "$tokens_size" -gt "$MIN_TOKENS_BYTES" ] || die "$TOKENS_FILE 只有 $tokens_size 字节，像是没下完"
say "本地模型：encoder $((encoder_size / 1024 / 1024))MB，decoder $((decoder_size / 1024))KB，joiner $((joiner_size / 1024))KB，tokens $((tokens_size / 1024))KB"

# ── 5. 比对体积，只推缺的 ──────────────────────────────────────────────
need_push=0
for f in "$ENCODER_FILE" "$DECODER_FILE" "$JOINER_FILE" "$TOKENS_FILE"; do
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
  [ "$need_push" -eq 0 ] && say "校验通过：外部模型就位" || say "校验未通过：需要部署（当前会退回 APK 内置模型）"
  [ "$need_push" -eq 0 ] || exit 2
  exit 0
fi

# ── 6. 推送 ────────────────────────────────────────────────────────────
"${ADB[@]}" shell "mkdir -p '$REMOTE_DIR'"

for f in "$ENCODER_FILE" "$DECODER_FILE" "$JOINER_FILE" "$TOKENS_FILE"; do
  local_size=$(wc -c < "$MODEL_DIR/$f" | tr -d ' ')
  if [ "$(remote_size "$f")" = "$local_size" ]; then
    continue
  fi
  say "推送 $f …"
  # 先推到 /data/local/tmp 再 cp：Android 11+ 直接 push 到 Android/data
  # 有时会被 FUSE 挡住一半（大文件尤其），分两步最稳。
  "${ADB[@]}" push "$(winpath "$MODEL_DIR/$f")" "/data/local/tmp/$f" >/dev/null
  "${ADB[@]}" shell "cp '/data/local/tmp/$f' '$REMOTE_DIR/$f' && rm -f '/data/local/tmp/$f'"
done

# keywords.txt 必须是空文件：native 解析器对每行做 stof，非空即失败。
say "写空 keywords.txt …"
"${ADB[@]}" shell ": > '$REMOTE_DIR/$KEYWORDS_FILE'"

# ── 7. 校验 ────────────────────────────────────────────────────────────
ok=1
for f in "$ENCODER_FILE" "$DECODER_FILE" "$JOINER_FILE" "$TOKENS_FILE"; do
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
say "完成。重新打开 Minis（或等下一次唤醒词载入）后生效。"
