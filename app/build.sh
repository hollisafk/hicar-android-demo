#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to an Android SDK with platform 35 and build-tools 35.0.0}"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or newer}"
bt="$ANDROID_SDK_ROOT/build-tools/35.0.0"
platform="$ANDROID_SDK_ROOT/platforms/android-35/android.jar"
version=$(python3 -c 'import xml.etree.ElementTree as E; print(E.parse("AndroidManifest.xml").getroot().get("{http://schemas.android.com/apk/res/android}versionName"))')
for abi in armeabi-v7a arm64-v8a; do
    if [ ! -f "native-libs/lib/$abi/libcarlink_hichain.so" ]; then
        echo "Missing $abi native library. Follow docs/BUILD.md to build native-auth first." >&2
        exit 1
    fi
done
mkdir -p build/classes build/dex
"$bt/aapt2" link -I "$platform" --manifest AndroidManifest.xml -A assets -o build/base.apk
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$platform:$bt/core-lambda-stubs.jar" -d build/classes src/local/hicarlab/*.java
rm -f build/classes.jar
(cd build/classes && zip -q -r ../classes.jar .)
"$bt/d8" --min-api 26 --lib "$platform" --output build/dex build/classes.jar
cp build/base.apk build/unsigned.apk
(cd native-libs && zip -q -r ../build/unsigned.apk lib)
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$bt/zipalign" -f -P 16 4 build/unsigned.apk build/aligned.apk
if [ -n "${HICAR_KEYSTORE:-}" ]; then
    : "${HICAR_STORE_PASSWORD:?Set HICAR_STORE_PASSWORD for your release key}"
    : "${HICAR_KEY_PASSWORD:?Set HICAR_KEY_PASSWORD for your release key}"
    : "${HICAR_KEY_ALIAS:?Set HICAR_KEY_ALIAS for your release key}"
    export HICAR_STORE_PASSWORD HICAR_KEY_PASSWORD
    apk="build/HiCarLab-$version.apk"
    "$bt/apksigner" sign --ks "$HICAR_KEYSTORE" --ks-key-alias "$HICAR_KEY_ALIAS" --ks-pass env:HICAR_STORE_PASSWORD --key-pass env:HICAR_KEY_PASSWORD --out "$apk" build/aligned.apk
else
    # Development key only. Never commit it or use it for public releases.
    mkdir -p ../.local-signing
    if [ ! -f ../.local-signing/development.keystore ]; then
        "$JAVA_HOME/bin/keytool" -genkeypair -keystore ../.local-signing/development.keystore -alias development -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=HiCar Receiver Development'
    fi
    apk="build/HiCarLab-$version-development.apk"
    "$bt/apksigner" sign --ks ../.local-signing/development.keystore --ks-key-alias development --ks-pass pass:android --key-pass pass:android --out "$apk" build/aligned.apk
fi
"$bt/apksigner" verify "$apk"
"$bt/zipalign" -c -P 16 4 "$apk"
echo "Built: $apk"
