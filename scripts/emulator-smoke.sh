#!/usr/bin/env bash
#
# Emulator smoke test used by CI after the APK has been verified.
#
# Installs the artifact built by the "build" job, launches MainActivity, confirms the process
# survives, checks logcat for crashes, takes a screenshot, and finally runs the Compose
# instrumentation tests. Everything is tee'd to build-logs/emulator-<api>.log so a failure can be
# published by ci-diagnostics.sh (the emulator runner's own output is not downloadable everywhere).
#
# Usage: scripts/emulator-smoke.sh <api-level>
#
set -uo pipefail

API_LEVEL="${1:-unknown}"
mkdir -p build-logs
LOG="build-logs/emulator-${API_LEVEL}.log"
: > "$LOG"
exec > >(tee -a "$LOG") 2>&1

PACKAGE="com.procutstudio.editor.debug"
ACTIVITY="com.procutstudio.editor.MainActivity"

echo "── Device ──"
adb devices
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.release

echo "── Locating the verified APK ──"
APK_PATH="$(find apk -name 'app-debug.apk' | head -n 1)"
if [ -z "$APK_PATH" ]; then
    echo "✘ app-debug.apk not found in the downloaded artifact"
    find . -maxdepth 3 -name '*.apk' -o -maxdepth 3 -name '*.zip' | head -n 20
    exit 1
fi
echo "APK: $APK_PATH ($(stat -c %s "$APK_PATH") bytes)"

echo "── Installing ──"
adb install -r "$APK_PATH" || exit 1
adb shell pm list packages | grep -q "$PACKAGE" || {
    echo "✘ $PACKAGE is not installed"
    exit 1
}
echo "✔ installed"

echo "── Launching ──"
adb logcat -c
adb shell am start -W -n "$PACKAGE/$ACTIVITY" || exit 1

# Give Compose a moment to inflate the first screen, then make sure the process is alive.
sleep 20
PID="$(adb shell pidof "$PACKAGE" | tr -d '\r')"
if [ -z "$PID" ]; then
    echo "✘ The app is not running 20 s after launch"
    echo "── logcat (tail) ──"
    adb logcat -d | tail -n 300
    exit 1
fi
echo "✔ app process alive (pid $PID)"

if adb logcat -d -s AndroidRuntime:E | grep -q "FATAL EXCEPTION"; then
    echo "✘ Crash during launch"
    adb logcat -d -s AndroidRuntime:E
    exit 1
fi
echo "✔ no fatal exception in logcat"

echo "── Resumed activity ──"
adb shell dumpsys activity activities | grep -m2 -E "mResumedActivity|topResumedActivity" || true

echo "── Screenshot ──"
adb exec-out screencap -p > "launch-${API_LEVEL}.png" || true
ls -l "launch-${API_LEVEL}.png" || true

echo "── Compose instrumentation tests ──"
./gradlew --no-daemon --stacktrace --console=plain :app:connectedDebugAndroidTest
STATUS=$?
if [ "$STATUS" -ne 0 ]; then
    echo "✘ Instrumentation tests failed (exit $STATUS)"
    adb logcat -d | tail -n 300
    exit "$STATUS"
fi
echo "✔ Instrumentation tests passed"
exit 0
