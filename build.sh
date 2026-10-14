#!/usr/bin/env bash
# 无 Gradle 手动构建连点器 APK：aapt2 -> javac -> d8 -> 打包dex -> zipalign -> apksigner
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="/c/Users/Administrator/AppData/Local/Android/Sdk"
BT="$SDK/build-tools/36.0.0"
AJ="$SDK/platforms/android-37.0/android.jar"
JB="/d/Android Studio/jbr/bin"
PKG="com.l1.autoclicker"
PASS="l1pass123"
PY="/c/Users/Administrator/.workbuddy/binaries/python/versions/3.13.12/python.exe"

export JAVA_HOME="D:\\Android Studio\\jbr"
export PATH="$JB:$PATH"

cd "$ROOT"
mkdir -p build/gen build/classes build/dex keystore out

echo "== [1/7] 注入 package =="
sed "s|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$PKG\">|" \
    app/src/main/AndroidManifest.xml > build/AndroidManifest.xml

echo "== [2/7] aapt2 compile 资源 =="
"$BT/aapt2.exe" compile --dir "$(cygpath -w app/src/main/res)" -o "$(cygpath -w build/res.zip)"

echo "== [3/7] aapt2 link + 生成 R.java =="
"$BT/aapt2.exe" link -o "$(cygpath -w build/base.apk)" -I "$(cygpath -w "$AJ")" \
  --manifest "$(cygpath -w build/AndroidManifest.xml)" --java "$(cygpath -w build/gen)" \
  --min-sdk-version 24 --target-sdk-version 33 --version-code 21 --version-name 1.0.0ap \
  --auto-add-overlay "$(cygpath -w build/res.zip)"

echo "== [4/7] javac 编译 =="
find app/src/main/java build/gen -name '*.java' | while read -r f; do cygpath -m "$f"; done > build/sources.txt
"$JB/javac.exe" --release 17 -encoding UTF-8 -nowarn \
  -classpath "$(cygpath -m "$AJ")" -d "$(cygpath -m build/classes)" "@$(cygpath -m build/sources.txt)"

echo "== [5/7] d8 dex =="
find build/classes -name '*.class' > build/classes.txt
"$BT/d8.bat" --release --min-api 24 --lib "$(cygpath -m "$AJ")" --output "$(cygpath -m build/dex)" \
  $(while read -r f; do cygpath -m "$f"; done < build/classes.txt)

echo "== [6/7] 打包 classes.dex + zipalign =="
cp build/base.apk build/unsigned.apk
"$PY" -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED); z.write(sys.argv[2],'classes.dex'); z.close()" \
  "$(cygpath -m build/unsigned.apk)" "$(cygpath -m build/dex/classes.dex)"
"$BT/zipalign.exe" -f -p 4 "$(cygpath -w build/unsigned.apk)" "$(cygpath -w build/aligned.apk)"

echo "== [7/7] 生成密钥并签名 =="
if [ ! -f keystore/app.jks ]; then
  "$JB/keytool.exe" -genkeypair -v -keystore "$(cygpath -m keystore/app.jks)" \
    -storepass "$PASS" -keypass "$PASS" -alias l1key -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=L1 Tools,O=L1,C=CN" 2>/dev/null
fi
OUT="out/L1Tap-v1.0.0ap.apk"
"$BT/apksigner.bat" sign --ks "$(cygpath -m keystore/app.jks)" --ks-pass "pass:$PASS" \
  --key-pass "pass:$PASS" --ks-key-alias l1key --out "$(cygpath -m "$OUT")" "$(cygpath -m build/aligned.apk)"

echo "== 校验 =="
"$BT/aapt2.exe" dump badging "$(cygpath -w "$OUT")" | head -4
"$BT/aapt2.exe" dump permissions "$(cygpath -w "$OUT")"
"$BT/apksigner.bat" verify -v "$(cygpath -w "$OUT")" | head -6
echo "BUILD_OK -> $OUT"