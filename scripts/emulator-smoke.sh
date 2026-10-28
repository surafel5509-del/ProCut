#!/usr/bin/env bash
#
# Emulator smoke test used by CI after the APK has been verified.
#
# Installs the artifact built by the "build" job, launches MainActivity, confirms the process
# survives, checks the crash buffer, takes a screenshot, and finally runs the Compose
# instrumentation tests. Output is tee'd to build-logs/emulator-<api>.log and every failure prints a
# "SMOKE FAILED AT" marker, so ci-diagnostics.sh can publish a compact, readable report.
#
# Usage: scripts/emulator-smoke.sh <api-level>
#
set -uo pipefail

API_LEVEL="${1:-unknown}"
mkdir -p build-logs
LOG="build-logs/emulator-${API_LEVEL}.log"
CRASH_LOG="build-logs/logcat-crash-${API_LEVEL}.log"
FULL_LOG="build-logs/logcat-full-${API_LEVEL}.log"
: > "$LOG"
exec > >(tee -a "$LOG") 2>&1

PACKAGE="com.procutstudio.editor.debug"
ACTIVITY="com.procutstudio.editor.MainActivity"
STAGE="setup"

fail_at() {
    echo
    echo "SMOKE FAILED AT: ${STAGE} — $*"
    dump_diagnostics
    exit 1
}

dump_diagnostics() {
    echo "── crash buffer ──"
    adb logcat -d -b crash 2>/dev/null | tail -n 120
    echo "── app + AndroidRuntime lines ──"
    adb logcat -d 2>/dev/null | grep -E "procutstudio|AndroidRuntime" | tail -n 160
    echo "── last 40 lines of the full buffer ──"
    adb logcat -d 2>/dev/null | tail -n 40
    adb logcat -d > "$FULL_LOG" 2>/dev/null
    adb logcat -d -b crash > "$CRASH_LOG" 2>/dev/null
    echo "logs: $FULL_LOG / $CRASH_LOG"
}

echo "── Device ──"
adb devices
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.release

STAGE="locate-apk"
APK_PATH="$(find apk -name 'app-debug.apk' | head -n 1)"
if [ -z "$APK_PATH" ]; then
    find . -maxdepth 4 -name '*.apk' | head -n 20
    fail_at "app-debug.apk not found in the downloaded artifact"
fi
echo "APK: $APK_PATH ($(stat -c %s "$APK_PATH") bytes)"

STAGE="install"
adb install -r "$APK_PATH" || fail_at "adb install failed"
adb shell pm list packages | grep -q "$PACKAGE" || fail_at "$PACKAGE is not listed after install"
echo "✔ installed"

STAGE="launch"
adb logcat -c
LAUNCH_OUTPUT="$(adb shell am start -W -n "$PACKAGE/$ACTIVITY" 2>&1)"
echo "$LAUNCH_OUTPUT"
grep -q "Status: ok" <<<"$LAUNCH_OUTPUT" || fail_at "am start did not report Status: ok"

STAGE="process-check"
sleep 20
PID="$(adb shell pidof "$PACKAGE" | tr -d '\r')"
[ -n "$PID" ] || fail_at "the app process is not running 20 s after launch"
echo "✔ app process alive (pid $PID)"

STAGE="crash-check"
if adb logcat -d -b crash 2>/dev/null | grep -q "FATAL EXCEPTION"; then
    fail_at "FATAL EXCEPTION in the crash buffer"
fi
echo "✔ crash buffer is clean"

echo "── Resumed activity ──"
adb shell dumpsys activity activities | grep -m2 -E "mResumedActivity|topResumedActivity" || true

echo "── Screenshot ──"
adb exec-out screencap -p > "launch-${API_LEVEL}.png" || true
ls -l "launch-${API_LEVEL}.png" 2>/dev/null || true

STAGE="instrumentation"
echo "── Compose instrumentation tests ──"
# Gradle builds and installs its own debug APK signed with this runner's debug keystore. The
# artifact installed above was signed with the build job's keystore, so it is removed first —
# otherwise the reinstall aborts with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
adb uninstall "$PACKAGE" > /dev/null 2>&1 || true
./gradlew --no-daemon --stacktrace --console=plain :app:connectedDebugAndroidTest
STATUS=$?
if [ "$STATUS" -ne 0 ]; then
    fail_at "connectedDebugAndroidTest exited with $STATUS"
fi

echo "✔ SMOKE TEST PASSED on API ${API_LEVEL}"
exit 0
