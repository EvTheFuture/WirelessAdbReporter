#!/bin/sh
#
# Builds the APK straight from the SDK build tools, without Gradle. The
# Makefile uses Gradle, this is the fallback for machines without network
# access to Google's Maven repository (make direct).
#
# Needs: JDK 17+, an Android SDK with build-tools and platforms;android-36.
# Every tool can be overridden with an environment variable, see below.

set -eu

APP_ID="io.github.evthefuture.adbreporter"
MIN_SDK=30
TARGET_SDK=36

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/app/src/main"
OUT="$ROOT/out"

# Same version source and code scheme as app/build.gradle: two digits per part, 1.2 is 1020000
VERSION_NAME="${VERSION_NAME:-$(tr -d ' \n' < "$ROOT/VERSION")}"
VERSION_CODE="${VERSION_CODE:-$(echo "$VERSION_NAME" | tr -cd '0-9.' | awk -F. '{
    code = 0

    for (i = 1; i <= 4; i++) {
        code = code * 100 + (i <= NF ? $i : 0)
    }

    print code
}')}"

# Locate the SDK, Ubuntu packages put it in /usr/lib/android-sdk
if [ -z "${ANDROID_HOME:-}" ]; then
    ANDROID_HOME="${ANDROID_SDK_ROOT:-}"
fi

if [ -z "$ANDROID_HOME" ]; then
    ANDROID_HOME="/usr/lib/android-sdk"
    [ -d "$ANDROID_HOME" ] || ANDROID_HOME="$HOME/Android/Sdk"
fi

# The newest build-tools directory
BT_DIR="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -n 1 || true)"

AAPT2="${AAPT2:-$BT_DIR/aapt2}"
ZIPALIGN="${ZIPALIGN:-$BT_DIR/zipalign}"
D8="${D8:-$BT_DIR/d8}"
APKSIGNER="${APKSIGNER:-$BT_DIR/apksigner}"
ANDROID_JAR="${ANDROID_JAR:-$ANDROID_HOME/platforms/android-$TARGET_SDK/android.jar}"

# Optional fallbacks: dx.jar instead of d8, uber-apk-signer.jar instead of apksigner
DX_JAR="${DX_JAR:-}"
UBER_SIGNER_JAR="${UBER_SIGNER_JAR:-}"

# Signing key, generated on first build if it does not exist
KEYSTORE="${KEYSTORE:-$ROOT/keystore/adbreporter.p12}"
KS_ALIAS="${KS_ALIAS:-adbreporter}"
KS_PASS="${KS_PASS:-adbreporter}"

for tool in "$AAPT2" "$ZIPALIGN" "$ANDROID_JAR"; do

    if [ ! -e "$tool" ]; then
        echo "Missing: $tool (set ANDROID_HOME or override the variable)" >&2
        exit 1
    fi
done

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

# Compile and link resources, aapt2 wants the package attribute in the manifest
echo "[1/6] resources"
"$AAPT2" compile --dir "$SRC/res" -o "$OUT/res.zip"
sed "s|<manifest |<manifest package=\"$APP_ID\" |" "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$AAPT2" link \
    -o "$OUT/unsigned.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$OUT/AndroidManifest.xml" \
    --java "$OUT/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    "$OUT/res.zip"

# Java 8 bytecode keeps both d8 and the old dx happy
echo "[2/6] javac"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -Xlint:-options \
    -source 8 -target 8 \
    -encoding UTF-8 \
    -bootclasspath "$ANDROID_JAR" \
    -d "$OUT/classes" \
    @"$OUT/sources.txt"

echo "[3/6] dex"

if [ -n "$DX_JAR" ]; then
    java -jar "$DX_JAR" --dex --min-sdk-version=26 --output="$OUT/dex/classes.dex" "$OUT/classes"
else
    find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
    "$D8" --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output "$OUT/dex" @"$OUT/classes.txt"
fi

echo "[4/6] package"
(cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes.dex)
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[5/6] keystore"

if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -noprompt \
        -keystore "$KEYSTORE" -storetype PKCS12 \
        -alias "$KS_ALIAS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Wireless ADB Reporter"
fi

echo "[6/6] sign"
APK="$OUT/wireless-adb-reporter-$VERSION_NAME.apk"

if [ -n "$UBER_SIGNER_JAR" ]; then
    java -jar "$UBER_SIGNER_JAR" -a "$OUT/aligned.apk" -o "$OUT/signed" --skipZipAlign \
        --ks "$KEYSTORE" --ksAlias "$KS_ALIAS" --ksPass "$KS_PASS" --ksKeyPass "$KS_PASS"
    mv "$OUT"/signed/*.apk "$APK"
else
    "$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
        --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
        --out "$APK" "$OUT/aligned.apk"
fi

echo "Built $APK"
