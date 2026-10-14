#!/bin/bash
# 纯命令行 Android 构建流水线（无需 gradle）：aapt2 → javac → d8 → zipalign → apksigner
# 依赖：bash + JDK(javac) + Android SDK(build-tools 36.1.0 / platform android-36.1)
# 机器相关路径可用环境变量覆盖：ANDROID_SDK / ANDROID_BT_VERSION / JAVA_BIN / CHECKIN_OUT_DIR
set -e
SDK="${ANDROID_SDK:-C:/Users/ABC/AppData/Local/Android/Sdk}"
BT_VERSION="${ANDROID_BT_VERSION:-36.1.0}"
BT="$SDK/build-tools/$BT_VERSION"
PLATFORM="$SDK/platforms/android-36.1/android.jar"
JAVA_BIN="${JAVA_BIN:-/d/JAVA/bin}"
if [ ! -x "$JAVA_BIN/javac" ]; then
  JAVA_BIN="$(dirname "$(command -v javac)")"   # 回退：使用 PATH 中的 JDK
fi
PROJ="$(cd "$(dirname "$0")" && pwd)"
BUILD="$PROJ/build"
VER="$(grep -o 'versionName="[^"]*"' "$PROJ/AndroidManifest.xml" | head -1 | cut -d'"' -f2)"
if [ -n "$1" ]; then SUFFIX="-$1"; else SUFFIX=""; fi
OUT_DIR="${CHECKIN_OUT_DIR:-$(cd "$PROJ/.." && pwd)}"
mkdir -p "$OUT_DIR"
OUT_APK="$OUT_DIR/平安打卡-v${VER}${SUFFIX}.apk"

rm -rf "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "[1/7] aapt2 compile"
"$BT/aapt2.exe" compile --dir "$PROJ/res" -o "$BUILD/res.zip"

echo "[2/7] aapt2 link"
"$BT/aapt2.exe" link -o "$BUILD/base.apk" -I "$PLATFORM" \
  --manifest "$PROJ/AndroidManifest.xml" -R "$BUILD/res.zip" \
  --java "$BUILD/gen" --auto-add-overlay

echo "[3/7] javac"
"$JAVA_BIN/javac" -encoding UTF-8 --release 8 -classpath "$PLATFORM" -d "$BUILD/classes" \
  "$PROJ/src/com/csuft/checkin/MainActivity.java" \
  "$PROJ/src/com/csuft/checkin/CheckinCore.java" \
  "$PROJ/src/com/csuft/checkin/CheckinScheduler.java" \
  "$PROJ/src/com/csuft/checkin/TriggerReceiver.java" \
  "$PROJ/src/com/csuft/checkin/Auth.java" \
  "$PROJ/src/com/csuft/checkin/Notify.java" \
  "$PROJ/src/com/csuft/checkin/Store.java" \
  "$PROJ/src/com/csuft/checkin/Secrets.java" \
  "$BUILD/gen/com/csuft/checkin/R.java"

echo "[4/7] d8"
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --lib "$PLATFORM" --min-api 24 \
  --output "$BUILD/dex" "$BUILD/classes/com/csuft/checkin/"*.class

echo "[5/7] 打包 classes.dex"
cd "$BUILD"
python - <<'PYEOF'
import zipfile
z = zipfile.ZipFile('base.apk', 'a', zipfile.ZIP_DEFLATED)
z.write('dex/classes.dex', 'classes.dex')
print('classes.dex added')
PYEOF
cd "$PROJ"

echo "[6/7] zipalign"
"$BT/zipalign.exe" -f 4 "$BUILD/base.apk" "$BUILD/aligned.apk"

echo "[7/7] apksigner sign"
if [ ! -f "$PROJ/debug.keystore" ]; then
  "$JAVA_BIN/keytool" -genkeypair -v -keystore "$PROJ/debug.keystore" \
    -alias androiddebugkey -storepass android -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" > /dev/null 2>&1
fi
java -jar "$BT/lib/apksigner.jar" sign \
  --ks "$PROJ/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT_APK" "$BUILD/aligned.apk"

echo "OK: $OUT_APK"
