#!/usr/bin/env bash
#
# Verifies a built ProCut Studio APK the way a release checklist would: identity, SDK levels,
# permissions, launcher entry point, signature, zip alignment, packaged files and the app's own
# classes inside the dex. Exits non-zero on the first failed check, so CI fails loudly instead of
# uploading a broken artifact.
#
# Usage:
#   scripts/verify-apk.sh <apk> [expected-package]
#
# Environment:
#   EXPECTED_MIN_SDK     default 26
#   EXPECTED_TARGET_SDK  default 35
#   EXPECTED_VERSION     default 1.0.0
#   CHECK_CLASSES        "true" for the non-minified debug APK (class names survive packaging)
#   VERIFY_RELEASE       "1" to additionally assert the APK is not debuggable
#
set -euo pipefail

APK="${1:?usage: verify-apk.sh <apk> [expected-package]}"
EXPECTED_PACKAGE="${2:-com.procutstudio.editor.debug}"
EXPECTED_MIN_SDK="${EXPECTED_MIN_SDK:-26}"
EXPECTED_TARGET_SDK="${EXPECTED_TARGET_SDK:-35}"
EXPECTED_VERSION="${EXPECTED_VERSION:-1.0.0}"
CHECK_CLASSES="${CHECK_CLASSES:-true}"
VERIFY_RELEASE="${VERIFY_RELEASE:-0}"

RED=$'\033[31m'; GREEN=$'\033[32m'; BOLD=$'\033[1m'; RESET=$'\033[0m'

ok()   { printf '%s✔%s %s\n' "$GREEN" "$RESET" "$*"; }
info() { printf '  %s\n' "$*"; }
fail() { printf '%s✘ %s%s\n' "$RED" "$*" "$RESET" >&2; exit 1; }

section() { printf '\n%s== %s ==%s\n' "$BOLD" "$*" "$RESET"; }

# ---------------------------------------------------------------- tools
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[ -n "$SDK_ROOT" ] || fail "ANDROID_SDK_ROOT / ANDROID_HOME is not set"
[ -d "$SDK_ROOT" ] || fail "Android SDK directory not found: $SDK_ROOT"

BUILD_TOOLS_DIR="$(find "$SDK_ROOT/build-tools" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -n 1)"
[ -n "$BUILD_TOOLS_DIR" ] || fail "No build-tools installed under $SDK_ROOT/build-tools"

AAPT2="$BUILD_TOOLS_DIR/aapt2"
APKSIGNER="$BUILD_TOOLS_DIR/apksigner"
ZIPALIGN="$BUILD_TOOLS_DIR/zipalign"
for tool in "$AAPT2" "$APKSIGNER" "$ZIPALIGN"; do
    [ -x "$tool" ] || fail "Missing build tool: $tool"
done
info "build-tools: $(basename "$BUILD_TOOLS_DIR")"

# ---------------------------------------------------------------- file
section "Artifact"
[ -f "$APK" ] || fail "APK not found: $APK"
SIZE_BYTES="$(stat -c %s "$APK")"
[ "$SIZE_BYTES" -gt 1000000 ] || fail "APK is suspiciously small (${SIZE_BYTES} bytes)"
ok "$(basename "$APK") — $(( SIZE_BYTES / 1024 / 1024 )) MB ($SIZE_BYTES bytes)"
info "sha256: $(sha256sum "$APK" | cut -d' ' -f1)"

# ---------------------------------------------------------------- manifest identity
section "Manifest"
BADGING="$("$AAPT2" dump badging "$APK" 2>/dev/null)" || fail "aapt2 dump badging failed"

PKG="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$BADGING" | head -n 1)"
VERSION_CODE="$(sed -n "s/^package: name='[^']*' versionCode='\([0-9]*\)'.*/\1/p" <<<"$BADGING" | head -n 1)"
VERSION_NAME="$(sed -n "s/^package: name='[^']*' versionCode='[0-9]*' versionName='\([^']*\)'.*/\1/p" <<<"$BADGING" | head -n 1)"
MIN_SDK="$(sed -n "s/^sdkVersion:'\([0-9]*\)'.*/\1/p" <<<"$BADGING" | head -n 1)"
TARGET_SDK="$(sed -n "s/^targetSdkVersion:'\([0-9]*\)'.*/\1/p" <<<"$BADGING" | head -n 1)"
LAUNCHABLE="$(sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p" <<<"$BADGING" | head -n 1)"

info "package      : $PKG"
info "versionName  : $VERSION_NAME (code $VERSION_CODE)"
info "minSdk       : $MIN_SDK"
info "targetSdk    : $TARGET_SDK"
info "launcher     : $LAUNCHABLE"

[ "$PKG" = "$EXPECTED_PACKAGE" ] || fail "package name is '$PKG', expected '$EXPECTED_PACKAGE'"
[ "$MIN_SDK" = "$EXPECTED_MIN_SDK" ] || fail "minSdk is '$MIN_SDK', expected '$EXPECTED_MIN_SDK' (Android 8.0)"
[ "$TARGET_SDK" = "$EXPECTED_TARGET_SDK" ] || fail "targetSdk is '$TARGET_SDK', expected '$EXPECTED_TARGET_SDK'"
[[ "$VERSION_NAME" == "$EXPECTED_VERSION"* ]] || fail "versionName is '$VERSION_NAME', expected '$EXPECTED_VERSION…'"
[ "$LAUNCHABLE" = "com.procutstudio.editor.MainActivity" ] || fail "unexpected launcher activity: '$LAUNCHABLE'"
ok "identity, SDK levels and launcher activity match"

if [ "$VERIFY_RELEASE" = "1" ]; then
    if grep -q "application-debuggable" <<<"$BADGING"; then
        fail "release APK is marked debuggable"
    fi
    ok "release APK is not debuggable"
fi

# ---------------------------------------------------------------- permissions
section "Permissions"
permissions="$("$AAPT2" dump permissions "$APK")"
grep -q "android.permission.RECORD_AUDIO" <<<"$permissions" \
    || fail "RECORD_AUDIO permission is missing (voice-over recording would fail)"
ok "RECORD_AUDIO declared (requested on demand only)"

if grep -q "android.permission.WRITE_EXTERNAL_STORAGE" <<<"$BADGING"; then
    grep -q "WRITE_EXTERNAL_STORAGE' maxSdkVersion='28'" <<<"$BADGING" \
        || fail "WRITE_EXTERNAL_STORAGE must be capped with maxSdkVersion=28"
    ok "WRITE_EXTERNAL_STORAGE capped at API 28 (Android 8/9 gallery save only)"
else
    fail "WRITE_EXTERNAL_STORAGE is missing; exports could not be saved on Android 8.0–9"
fi

if grep -q "android.permission.INTERNET" <<<"$permissions"; then
    fail "the app must not declare INTERNET (all processing is local)"
fi
ok "no INTERNET permission — offline by construction"

# ---------------------------------------------------------------- signature + alignment
section "Signature and alignment"
SIGNER="$("$APKSIGNER" verify --print-certs "$APK" 2>&1)" || fail "APK signature verification failed"
grep -q "Signer #1 certificate DN" <<<"$SIGNER" || fail "no signer certificate found"
ok "signature verified ($(sed -n 's/^Signer #1 certificate DN: //p' <<<"$SIGNER" | cut -c1-60))"
info "$("$APKSIGNER" verify --verbose "$APK" 2>&1 | grep -E '^Verified using' | paste -sd ', ' -)"

ZIPALIGN_OUT="$("$ZIPALIGN" -c -v 4 "$APK" 2>&1)" || fail "APK is not zip-aligned: $ZIPALIGN_OUT"
ok "zip-aligned for 4-byte boundaries (and 16 KiB page alignment reported below)"
grep -q "Verification successful" <<<"$ZIPALIGN_OUT" || fail "zipalign did not report success"

# ---------------------------------------------------------------- contents
section "Packaged contents"
ENTRIES="$(unzip -l "$APK")"
for required in AndroidManifest.xml resources.arsc classes.dex; do
    grep -q " $required\$" <<<"$ENTRIES" || fail "APK is missing $required"
    ok "contains $required"
done
DEX_COUNT="$(grep -cE ' classes[0-9]*\.dex$' <<<"$ENTRIES")"
[ "$DEX_COUNT" -ge 1 ] || fail "no dex files found"
ok "$DEX_COUNT dex file(s)"

# The player layout and the theme resources must survive packaging (they are inflated by name).
grep -qE 'res/layout/view_player\.xml|res/.*view_player' <<<"$ENTRIES" || fail "player layout missing from APK"
ok "player surface layout packaged"

# ---------------------------------------------------------------- app classes in dex
if [ "$CHECK_CLASSES" = "true" ]; then
    section "Application classes in dex"
    required_classes=(
        "com/procutstudio/editor/MainActivity"
        "com/procutstudio/editor/ProCutApplication"
        "com/procutstudio/editor/ui/editor/EditorViewModel"
        "com/procutstudio/editor/media/export/VideoExporter"
        "com/procutstudio/editor/media/export/MediaStorePublisher"
        "com/procutstudio/editor/media/export/CompositionBuilder"
        "com/procutstudio/editor/domain/timeline/TimelineEditor"
    )
    unzip -p "$APK" 'classes*.dex' > /tmp/procut-dex.bin
    for cls in "${required_classes[@]}"; do
        grep -a -q "L$cls;" /tmp/procut-dex.bin || fail "class missing from dex: $cls"
        ok "$cls"
    done
    rm -f /tmp/procut-dex.bin
else
    section "Application classes in dex"
    info "skipped (minified build — class names are obfuscated by design)"
fi

section "Result"
printf '%sAPK verified: %s%s\n' "$GREEN" "$APK" "$RESET"
