#!/usr/bin/env bash
# Build, install on the TV, keep SimpleHome as the default home, and bring it to the front.
set -e
export MSYS_NO_PATHCONV=1
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
TV=${TV:-172.30.20.106:5555}
cd "$(dirname "$0")/.."
./gradlew -q assembleDebug testDebugUnitTest
mkdir -p /c/Temp/shtv
cp app/build/outputs/apk/debug/app-debug.apk /c/Temp/shtv/app.apk
adb connect "$TV" >/dev/null
adb -s "$TV" install -r -g C:/Temp/shtv/app.apk
adb -s "$TV" shell cmd package set-home-activity --user 0 com.simplehometv/.HomeActivity >/dev/null
adb -s "$TV" shell am force-stop com.simplehometv
adb -s "$TV" logcat -c
adb -s "$TV" shell input keyevent KEYCODE_HOME
echo deployed
