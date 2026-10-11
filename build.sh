#!/usr/bin/env bash
# Builds and signs the APK without Gradle.
#
# First run scripts/fetch_deps.sh to download models and libraries.
#
# Requirements:
#   - JDK 11+ (java, keytool)
#   - Android SDK with platforms;android-34 and build-tools;34.0.0
#
# Usage:
#   ANDROID_HOME=/path/to/sdk ./build.sh
#
# Signing:
#   By default a throwaway debug key is generated in build/ for local testing.
#   For a release, point these at your own keystore:
#     KEYSTORE=/path/to/release.keystore KEY_ALIAS=myalias KEYSTORE_PASS=... ./build.sh
set -euo pipefail

: "${ANDROID_HOME:?set ANDROID_HOME to your Android SDK}"

cd "$(dirname "$0")"

BT="$ANDROID_HOME/build-tools/34.0.0"
AJ="$ANDROID_HOME/platforms/android-34/android.jar"
ECJ="${ECJ:-tools/ecj.jar}"
OUT="${OUT:-DYSafetyWalk.apk}"

for f in "$AJ" "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner"; do
    [ -e "$f" ] || { echo "missing: $f (install platforms;android-34 and build-tools;34.0.0)" >&2; exit 1; }
done
for f in "$ECJ" libs/tflite-api.jar libs/tflite-impl.jar assets/efficientdet_lite0.tflite assets/yamnet.tflite; do
    [ -e "$f" ] || { echo "missing: $f (run scripts/fetch_deps.sh first)" >&2; exit 1; }
done

rm -rf build
mkdir -p build/compiled build/gen build/classes build/dex build/pack/lib

echo "[1/6] Compiling resources"
"$BT/aapt2" compile --dir res -o build/compiled/res.zip
"$BT/aapt2" link -o build/base.apk -I "$AJ" \
    --manifest AndroidManifest.xml --java build/gen \
    --min-sdk-version 24 --target-sdk-version 34 \
    -A assets -0 .tflite -0 .f32 \
    build/compiled/res.zip

echo "[2/6] Compiling Java"
java -jar "$ECJ" -source 8 -target 8 -proc:none -nowarn \
    -bootclasspath "$AJ" -cp libs/tflite-api.jar:libs/tflite-impl.jar \
    -d build/classes $(find src build/gen -name "*.java")

echo "[3/6] Converting to DEX"
"$BT/d8" --release --min-api 24 --lib "$AJ" --output build/dex \
    libs/tflite-api.jar libs/tflite-impl.jar $(find build/classes -name "*.class")

echo "[4/6] Packaging"
cp build/base.apk build/app-unsigned.apk
(cd build/dex && zip -q ../app-unsigned.apk classes*.dex)
cp -r jni/* build/pack/lib/
(cd build/pack && zip -q -r ../app-unsigned.apk lib)

echo "[5/6] Aligning"
"$BT/zipalign" -f -p 4 build/app-unsigned.apk build/app-aligned.apk

echo "[6/6] Signing"
if [ -z "${KEYSTORE:-}" ]; then
    KEYSTORE=build/debug.keystore
    KEY_ALIAS=debug
    KEYSTORE_PASS="$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)"
    keytool -genkeypair -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 3650 \
        -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" \
        -dname "CN=Local Debug" >/dev/null 2>&1
    echo "  no KEYSTORE given: signed with a throwaway debug key (local testing only)"
fi
: "${KEY_ALIAS:?set KEY_ALIAS}"
: "${KEYSTORE_PASS:?set KEYSTORE_PASS}"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYSTORE_PASS" \
    --key-pass "pass:${KEY_PASS:-$KEYSTORE_PASS}" \
    --ks-key-alias "$KEY_ALIAS" --out "$OUT" build/app-aligned.apk
"$BT/apksigner" verify "$OUT"

echo "Done: $OUT"
