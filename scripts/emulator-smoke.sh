#!/usr/bin/env bash
#
# Emulator smoke test used by CI after the APKs have been verified.
#
# 1. installs the *debug* artifact, launches MainActivity, confirms the process survives, checks the
#    crash buffer and takes a screenshot;
# 2. does the same for the *release* artifact — this is the only check that proves R8/minification
#    did not break Hilt, Room or kotlinx.serialization at runtime;
# 3. runs the Compose instrumentation tests against a locally built debug APK.
#
# Output is tee'd to build-logs/emulator-<api>.log and every failure prints a "SMOKE FAILED AT"
# marker, so ci-diagnostics.sh can publish a compact, readable report.
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

DEBUG_PACKAGE="com.procutstudio.editor.debug"
RELEASE_PACKAGE="com.procutstudio.editor"
ACTIVITY="MainActivity"
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

# Installs one APK, launches it and asserts that it is alive with a clean crash buffer.
smoke_variant() {
    local apk="$1" package="$2" label="$3"
    local component="${package}/${package}.${ACTIVITY}"

    STAGE="${label}-install"
    echo "── ${label}: installing ${apk} ──"
    adb install -r "$apk" || fail_at "adb install failed for ${label}"
    adb shell pm list packages | grep -q "$package" || fail_at "$package is not listed after install"

    STAGE="${label}-launch"
    adb logcat -c > /dev/null 2>&1
    local launch_output
    launch_output="$(adb shell am start -W -n "$component" 2>&1)"
    echo "$launch_output"
    grep -q "Status: ok" <<<"$launch_output" || fail_at "am start did not report Status: ok for ${label}"

    STAGE="${label}-process-check"
    sleep 20
    local pid
    pid="$(adb shell pidof "$package" | tr -d '\r')"
    [ -n "$pid" ] || fail_at "the ${label} process is not running 20 s after launch"
    echo "✔ ${label} alive (pid ${pid})"

    STAGE="${label}-crash-check"
    if adb logcat -d -b crash 2>/dev/null | grep -q "FATAL EXCEPTION"; then
        fail_at "FATAL EXCEPTION in the crash buffer for ${label}"
    fi
    echo "✔ ${label} crash buffer is clean"

    echo "   resumed: $(adb shell dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity' | tr -d '\r')"
    adb exec-out screencap -p > "launch-${label}-${API_LEVEL}.png" 2>/dev/null || true
}

echo "── Device ──"
adb devices
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.release

STAGE="locate-apks"
DEBUG_APK="$(find apk -name 'app-debug.apk' | head -n 1)"
RELEASE_APK="$(find apk -name 'app-release.apk' | head -n 1)"
[ -n "$DEBUG_APK" ] || fail_at "app-debug.apk not found in the downloaded artifact"
[ -n "$RELEASE_APK" ] || fail_at "app-release.apk not found in the downloaded artifact"
echo "debug  : $DEBUG_APK ($(stat -c %s "$DEBUG_APK") bytes)"
echo "release: $RELEASE_APK ($(stat -c %s "$RELEASE_APK") bytes)"

smoke_variant "$DEBUG_APK" "$DEBUG_PACKAGE" "debug"
smoke_variant "$RELEASE_APK" "$RELEASE_PACKAGE" "release"

STAGE="instrumentation"
echo "── Compose instrumentation tests ──"
# Gradle builds and installs its own debug APK signed with this runner's debug keystore. The
# artifact installed above was signed with the build job's keystore, so it is removed first —
# otherwise the reinstall aborts with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
adb uninstall "$DEBUG_PACKAGE" > /dev/null 2>&1 || true
adb uninstall "$RELEASE_PACKAGE" > /dev/null 2>&1 || true
./gradlew --no-daemon --stacktrace --console=plain :app:connectedDebugAndroidTest
STATUS=$?
if [ "$STATUS" -ne 0 ]; then
    fail_at "connectedDebugAndroidTest exited with $STATUS"
fi

echo "✔ SMOKE TEST PASSED on API ${API_LEVEL} (debug + release + instrumentation)"
exit 0
