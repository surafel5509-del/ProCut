#!/usr/bin/env bash
#
# Publishes the tail of the build logs as a GitHub check run ("ci-diagnostics") plus up to ten
# Kotlin/Java compiler errors as annotations. Action log blobs are only downloadable by clients
# that can reach Azure blob storage, so this makes a failed build diagnosable from the API alone:
#
#   gh api /repos/<owner>/<repo>/commits/<sha>/check-runs --jq '.check_runs[] | select(.name=="ci-diagnostics") | .output.text'
#
set -uo pipefail

LOG_DIR="${1:-build-logs}"
MAX_CHARS="${MAX_CHARS:-60000}"
CHECK_NAME="${CHECK_NAME:-ci-diagnostics}"
API="https://api.github.com"

: "${GH_TOKEN:?GH_TOKEN must be set}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY must be set}"
: "${GITHUB_SHA:?GITHUB_SHA must be set}"
: "${GITHUB_RUN_ID:=unknown}"

mkdir -p "$LOG_DIR"

# ---------------------------------------------------------------- compiler errors as annotations
shopt -s nullglob
for log in "$LOG_DIR"/*.log; do
    grep -E '^(e: |error: |FAILURE:|Execution failed)' "$log" 2>/dev/null | head -n 10 | while IFS= read -r line; do
        clean="${line//$'\r'/ }"
        clean="${clean//'%'/'%25'}"
        clean="${clean//$'\n'/' '}"
        echo "::error title=Build error::${clean}"
    done
done

# ---------------------------------------------------------------- combined tail
COMBINED="$LOG_DIR/combined.log"
: > "$COMBINED"
for log in "$LOG_DIR"/*.log; do
    [ "$log" = "$COMBINED" ] && continue
    {
        echo "════════ ${log} ════════"
        cat "$log"
        echo
    } >> "$COMBINED"
done

if [ -z "${GH_TOKEN:-}" ]; then
    echo "No GH_TOKEN; printing the tail instead."
    tail -c "$MAX_CHARS" "$COMBINED"
    exit 0
fi

PAYLOAD="$LOG_DIR/check-run.json"
LOG_PATH="$COMBINED" python3 - "$MAX_CHARS" <<'PY' > "$PAYLOAD"
import json, os, sys

limit = int(sys.argv[1])
path = os.environ["LOG_PATH"]
data = open(path, encoding="utf-8", errors="replace").read()
text = data[-limit:]
title = os.environ.get("CHECK_NAME", "CI diagnostics")
summary = (
    "The job failed. The tail of the captured output is in the details below.\n\n"
    f"Run: {os.environ.get('GITHUB_SERVER_URL', 'https://github.com')}/"
    f"{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}"
)
print(json.dumps({
    "name": os.environ.get("CHECK_NAME", "ci-diagnostics"),
    "head_sha": os.environ["GITHUB_SHA"],
    "status": "completed",
    "conclusion": "neutral",
    "output": {"title": title, "summary": summary, "text": text},
}))
PY

echo "Publishing diagnostics for ${GITHUB_SHA}…"
curl -sS -X POST \
    -H "Authorization: Bearer ${GH_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "${API}/repos/${GITHUB_REPOSITORY}/check-runs" \
    -d @"$PAYLOAD" | head -c 500
echo
