#!/bin/bash
# ============================================================================
# 饥荒手机版纹理转换工具 构建脚本
#
# 功能：把 PNG / DXT 纹理转换为饥荒手机版 ASTC 8x8 压缩 .tex
#
# 构建依赖：
#   - JDK 17
#   - Android SDK（build-tools 34.0.0、platform android-34）
#   - Android NDK（r26+，含 aarch64 clang）
#
# 环境变量（未设置时按常见默认值探测）：
#   ANDROID_SDK_ROOT 或 ANDROID_HOME : Android SDK 路径
#   ANDROID_NDK_HOME 或 ANDROID_NDK_ROOT : Android NDK 路径
#
# 用法：bash build_apk.sh
# 产物：dst_tex_converter.apk（项目根目录）
# ============================================================================
set -e

# ---------- 定位 SDK / NDK ----------
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
if [ -n "$ANDROID_NDK_HOME" ]; then
    NDK="$ANDROID_NDK_HOME"
elif [ -n "$ANDROID_NDK_ROOT" ]; then
    NDK="$ANDROID_NDK_ROOT"
else
    NDK=$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)
fi

BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang++"

command -v javac >/dev/null 2>&1 || { echo "错误：缺少 javac，请安装 JDK 17"; exit 1; }
[ -f "$BT/aapt2" ] || { echo "错误：缺少 build-tools 34.0.0：$BT"; exit 1; }
[ -f "$JAR" ] || { echo "错误：缺少 platform android-34：$JAR"; exit 1; }
[ -f "$CLANG" ] || { echo "错误：缺少 NDK aarch64 clang：$CLANG"; exit 1; }

ROOT="$(cd "$(dirname "$0")" && pwd)"
WORK="$ROOT/build"
ASTCENC="$ROOT/third_party/astcenc/Source"
SQUISH="$ROOT/third_party/squish"
NATIVE="$ROOT/native"

rm -rf "$WORK"
mkdir -p "$WORK/lib/arm64-v8a" "$WORK/classes" "$WORK/gen" "$WORK/src/com/dsttex"

# ---------- 生成 astcenc 版本头文件 ----------
cat > "$WORK/gen/astcenccli_version.h" <<'EOF'
#ifndef ASTCENCCLI_VERSION_INCLUDED
#define ASTCENCCLI_VERSION_INCLUDED
#define VERSION_STRING "5.0.0"
#define YEAR_STRING "2026"
#endif
EOF

# ---------- 编译 native 动态库 ----------
echo "=== 编译 libastcenc.so ==="
( cd "$ASTCENC" && SOURCES=$(ls *.cpp | grep -v astcenccli_entry.cpp); \
  "$CLANG" -O2 -fPIC -shared -std=c++14 -static-libstdc++ -pthread \
    -DASTCENC_NEON=1 -DASTCENC_SVE=0 -DASTCENC_SSE=0 -DASTCENC_AVX=0 \
    -DASTCENC_POPCNT=0 -DASTCENC_F16C=0 \
    -I. -I"$WORK/gen" \
    $SOURCES "$NATIVE/jni_astcenc.cpp" \
    -o "$WORK/lib/arm64-v8a/libastcenc.so" -lm )

echo "=== 编译 libtex2png.so ==="
"$CLANG" -O2 -fPIC -shared -std=c++14 -static-libstdc++ \
  -I"$SQUISH" -I"$NATIVE" \
  "$NATIVE/tex2png.cpp" "$NATIVE/jni_tex2png.cpp" \
  "$SQUISH/alpha.cpp" "$SQUISH/clusterfit.cpp" "$SQUISH/colourblock.cpp" \
  "$SQUISH/colourfit.cpp" "$SQUISH/colourset.cpp" "$SQUISH/maths.cpp" \
  "$SQUISH/rangefit.cpp" "$SQUISH/singlecolourfit.cpp" "$SQUISH/squish.cpp" \
  -o "$WORK/lib/arm64-v8a/libtex2png.so" -lm

# ---------- 编译 Java ----------
echo "=== 编译 Java ==="
cp "$ROOT/app/src/com/dsttex/TexConverter.java" "$WORK/src/com/dsttex/"
javac -source 8 -target 8 -classpath "$JAR" -d "$WORK/classes" "$WORK/src/com/dsttex/TexConverter.java"

# ---------- d8 转 dex ----------
echo "=== d8 转 dex ==="
"$BT/d8" --release --min-api 21 --lib "$JAR" --output "$WORK" "$WORK/classes/com/dsttex/"*.class

# ---------- aapt2 打包 ----------
echo "=== aapt2 link ==="
"$BT/aapt2" link -o "$WORK/base.apk" -I "$JAR" --manifest "$ROOT/app/AndroidManifest.xml"

cd "$WORK"
python3 - <<'PYEOF'
import zipfile, os
src = zipfile.ZipFile("base.apk", "r")
with zipfile.ZipFile("unsigned.apk", "w") as dst:
    for item in src.infolist():
        if item.filename == "classes.dex":
            continue
        dst.writestr(item, src.read(item.filename))
    dst.write("classes.dex", "classes.dex")
    for root, dirs, files in os.walk("lib"):
        for f in files:
            p = os.path.join(root, f)
            dst.write(p, p.replace(os.sep, "/"))
src.close()
print("classes.dex 和 native 库已打包")
PYEOF

# ---------- 签名 ----------
echo "=== 签名 ==="
if [ ! -f "$WORK/keystore.jks" ]; then
  keytool -genkeypair -keystore "$WORK/keystore.jks" -alias dsttex \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass 123456 -keypass 123456 -dname "CN=DSTTex" -noprompt
fi
"$BT/zipalign" -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign --ks "$WORK/keystore.jks" --ks-pass pass:123456 --key-pass pass:123456 \
  --out "$ROOT/dst_tex_converter.apk" "$WORK/aligned.apk"

echo "=== 完成 ==="
ls -la "$ROOT/dst_tex_converter.apk"
