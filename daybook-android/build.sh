#!/usr/bin/env bash
# Builds a signed, installable APK without Gradle/Android Studio.
# Needs (Ubuntu/Debian): sudo apt install openjdk-17-jdk android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
OUT=build
APK=release/Daybook.apk
KEYSTORE=debug.keystore

rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes" release

echo "1/5 resources"
aapt package -f -m -J "$OUT/gen" -M AndroidManifest.xml -S res -I "$ANDROID_JAR" \
    -F "$OUT/unsigned.apk"

echo "2/5 compile"
javac -nowarn -Xlint:-options --release 8 -classpath "$ANDROID_JAR" -d "$OUT/classes" \
    $(find src "$OUT/gen" -name '*.java') 2>&1 | grep -v '^Picked up' || true
test -f "$OUT/classes/com/sidsaxena/daybook/MainActivity.class"

echo "3/5 dex"
dalvik-exchange --dex --min-sdk-version=23 --output="$OUT/classes.dex" "$OUT/classes"
(cd "$OUT" && zip -q -u unsigned.apk classes.dex)

echo "4/5 align"
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "5/5 sign"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias daybook -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Daybook Debug" 2>&1 | grep -v '^Picked up' || true
fi
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out "$APK" "$OUT/aligned.apk"
apksigner verify "$APK"
rm -f "$APK.idsig"
echo "Built $APK ($(du -h "$APK" | cut -f1))"
