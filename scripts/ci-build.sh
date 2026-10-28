#!/usr/bin/env bash
#
# CI build driver: runs the JVM test suite and assembles both APKs, keeping a plain-text log per
# step in build-logs/ so a failure can be published readably (see ci-diagnostics.sh).
#
set -euo pipefail

mkdir -p build-logs

run_step() {
    local name="$1"
    shift
    echo "── ${name} ──"
    set +e
    ./gradlew --no-daemon --stacktrace --console=plain "$@" 2>&1 | tee "build-logs/${name}.log"
    local status=${PIPESTATUS[0]}
    set -e
    echo "exit=${status}" >> "build-logs/${name}.log"
    return "${status}"
}

run_step unit-tests :app:testDebugUnitTest
run_step assemble :app:assembleDebug :app:assembleRelease

echo "Build finished; artifacts:"
find app/build/outputs/apk -name '*.apk' -exec ls -lh {} \;
