#!/bin/sh
# Builds FMHYPlay-TV-v<version>.apk and FMHYPlay-Android-v<version>.apk with plain SDK tools (no Gradle).
# Needs: an Android SDK (platform 34 + build-tools), a JDK 17+, and the WARP helper built once with warp/build.sh (needs Go).
# Env: ANDROID_HOME, VERSION_NAME, VERSION_CODE, TEST_EXPORT=1 (emulator test build).
# TV: Android TV launcher + banner, remote navigation with the cursor on by default.  Android: phone/tablet launcher, touch.
set -e
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)   # newest build-tools installed
JAR=$SDK/platforms/android-34/android.jar
LIBS=$(ls libs/*.jar | tr '\n' ':')
VERSION_CODE=${VERSION_CODE:-21403}
VERSION_NAME=${VERSION_NAME:-2.14.3}   # CI overrides both from the release tag
APP_SOURCE_PACKAGE=me.champdev.weblock
TEST_PACKAGE_ID=${TEST_PACKAGE_ID:-me.champdev.weblock.vltmed}
VERSION="--version-code $VERSION_CODE --version-name $VERSION_NAME"
cd "$(dirname "$0")"
scripts/fetch-assets.sh   # site lists and blocklist (kept out of the repository)
rm -rf build && mkdir -p build/classes

# Release key, created once. Back up release.jks + release.pass: updates need the same key.
if [ ! -f release.jks ]; then
  (umask 077; openssl rand -hex 24 > release.pass)
  keytool -genkeypair -keystore release.jks -storetype PKCS12 -alias weblock -keyalg RSA -keysize 4096 \
    -validity 10000 -dname "CN=WebLock" -storepass "$(cat release.pass)" >/dev/null
  chmod 600 release.jks
fi

python3 scripts/generate-ui-translations.py
$BT/aapt2 compile --dir res -o build/res.zip
# Link once to generate R.java for the compiler, then once per flavor for the real packages
mkdir -p build/flavor-tv && echo tv > build/flavor-tv/flavor
$BT/aapt2 link -o build/r.apk -I "$JAR" --manifest AndroidManifest.xml build/res.zip -A assets -A build/flavor-tv --java build \
  --custom-package "$APP_SOURCE_PACKAGE" --min-sdk-version 26 --target-sdk-version 34 $VERSION
javac -encoding UTF-8 -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" -classpath "$LIBS" \
  -d build/classes $(find src build/me -name '*.java')
$BT/d8 --lib "$JAR" --min-api 26 --output build $(find build/classes -name '*.class') $(ls libs/*.jar)
mkdir -p build/lib && cp -R jni/* build/lib/

pack() { # $1 = tv | android, $2 = manifest, $3 = output name
  mkdir -p build/flavor-$1 && echo $1 > build/flavor-$1/flavor
  $BT/aapt2 link -o build/$1.apk -I "$JAR" --manifest "$2" build/res.zip -A assets -A build/flavor-$1 \
    --custom-package "$APP_SOURCE_PACKAGE" --min-sdk-version 26 --target-sdk-version 34 $VERSION
  (cd build && zip -q $1.apk classes*.dex && zip -qr $1.apk lib)
  $BT/zipalign -f -p 4 build/$1.apk build/$1-aligned.apk
  $BT/apksigner sign --ks release.jks --ks-pass file:release.pass --v4-signing-enabled false --out "$3" build/$1-aligned.apk
  echo "built $(pwd)/$3"
}
# The phone build has no TV launcher entry and no TV banner
sed -e '/LEANBACK_LAUNCHER/d' -e 's| android:banner="@drawable/banner"||' AndroidManifest.xml > build/AndroidManifest-android.xml
# The version is in the file name, so you can tell builds apart in Telegram, Downloads and on the TV
if [ -n "$TEST_EXPORT" ]; then # emulator testing: WebActivity + IptvActivity exported so `adb shell am start` can open a test page, and Home answers the HOME key (the emulator has no other launcher)
  sed -e 's|\(\.WebActivity" android:exported="\)false|\1true|' -e 's|\(\.IptvActivity" android:exported="\)false|\1true|' \
    -e "s|package=\"$APP_SOURCE_PACKAGE\"|package=\"$TEST_PACKAGE_ID\"|" \
    -e 's|android:name="\.\([^"]*\)"|android:name="me.champdev.weblock.\1"|g' \
    -e 's|<category android:name="android.intent.category.LEANBACK_LAUNCHER"/>|&<category android:name="android.intent.category.HOME"/><category android:name="android.intent.category.DEFAULT"/>|' \
    AndroidManifest.xml > build/AndroidManifest-test.xml
  pack tv build/AndroidManifest-test.xml FMHYPlay-TV-test.apk
  sed -e '/LEANBACK_LAUNCHER/d' -e 's| android:banner="@drawable/banner"||' build/AndroidManifest-test.xml > build/AndroidManifest-android-test.xml
  pack android build/AndroidManifest-android-test.xml FMHYPlay-Android-test.apk
  exit 0
fi
rm -f FMHYPlay*.apk
pack tv AndroidManifest.xml FMHYPlay-TV-v$VERSION_NAME.apk
pack android build/AndroidManifest-android.xml FMHYPlay-Android-v$VERSION_NAME.apk
