#!/bin/sh
# Publishes the built APK for one-tap updates: copies it to the website (doable/android/) with version.json.
# Usage: ./release.sh "What's new, one line per change"   (then commit and deploy the website)
set -e
cd "$(dirname "$0")"
CODE=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' AndroidManifest.xml)
NAME=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)
OUT=../doable/android
mkdir -p "$OUT"
cp release/Doable.apk "$OUT/Doable.apk"
python3 -c 'import json,sys; print(json.dumps({"versionCode": int(sys.argv[1]), "versionName": sys.argv[2], "apk": "android/Doable.apk", "notes": sys.argv[3]}, ensure_ascii=False, indent=1))' "$CODE" "$NAME" "$1" > "$OUT/version.json"
echo "Published Doable $NAME ($CODE) to $OUT"
