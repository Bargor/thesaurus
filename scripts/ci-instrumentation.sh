#!/usr/bin/env bash
# Run inside firebase emulators:exec so Firebase remains available until cleanup.
set -uo pipefail

total_seconds=${CI_INSTRUMENTATION_TIMEOUT_SECONDS:-1200}
start_seconds=${CI_INSTRUMENTATION_START_GRACE_SECONDS:-300}
progress_seconds=${CI_INSTRUMENTATION_PROGRESS_TIMEOUT_SECONDS:-180}
poll_seconds=${CI_INSTRUMENTATION_POLL_SECONDS:-5}
term_seconds=${CI_INSTRUMENTATION_TERM_GRACE_SECONDS:-15}
adb_seconds=${CI_INSTRUMENTATION_ADB_TIMEOUT_SECONDS:-10}
diagnostics_dir=${CI_INSTRUMENTATION_DIAGNOSTICS_DIR:-app/build/ci-instrumentation}

for value in "$total_seconds" "$start_seconds" "$progress_seconds" "$poll_seconds" "$term_seconds" "$adb_seconds"; do
    if [[ ! $value =~ ^[1-9][0-9]*$ ]] || (( ${#value} > 8 )); then
        echo "CI instrumentation limits must be positive integers (at most eight digits)." >&2
        exit 2
    fi
done
if [[ -z ${ANDROID_SERIAL:-} ]]; then
    echo "ANDROID_SERIAL must identify the CI emulator explicitly." >&2
    exit 2
fi

mkdir -p "$diagnostics_dir" || exit 2
runner_log="$diagnostics_dir/test-runner.log"
watchdog_reason="$diagnostics_dir/watchdog-reason.txt"
# This directory belongs to this invocation; discard stale progress markers only.
: > "$runner_log"
: > "$watchdog_reason"
gradle_pid=''
logcat_pid=''
tail_pid=''
watchdog_pid=''

bounded_adb() {
    timeout --kill-after=2s "${adb_seconds}s" adb -s "$ANDROID_SERIAL" "$@"
}

capture_diagnostics() {
    # Each capture is independently bounded, including when adb itself is stuck.
    bounded_adb logcat -d -v threadtime -t 2000 > "$diagnostics_dir/logcat.txt" 2>&1
    bounded_adb shell dumpsys activity > "$diagnostics_dir/activity.txt" 2>&1
    bounded_adb shell dumpsys window > "$diagnostics_dir/window.txt" 2>&1
    bounded_adb shell ps -A > "$diagnostics_dir/processes.txt" 2>&1
    bounded_adb shell dumpsys activity lastanr > "$diagnostics_dir/last-anr.txt" 2>&1
    if [[ ${CI_CAPTURE_VISUAL_EVIDENCE:-false} == true ]]; then
        # Only synthetic test frames from this disposable CI emulator are collected.
        # Missing/failed captures must not replace Gradle's actual exit status.
        # MediaStore images survive UTP's target-app uninstall at suite teardown.
        mkdir -p "$diagnostics_dir/visual-evidence" || true
        bounded_adb pull /sdcard/Pictures/ThesaurusTestEvidence/issue94 "$diagnostics_dir/visual-evidence/" > "$diagnostics_dir/visual-evidence-capture.txt" 2>&1 || true
        bounded_adb pull /sdcard/Pictures/ThesaurusTestEvidence/issue96 "$diagnostics_dir/visual-evidence/" > "$diagnostics_dir/visual-evidence-issue96-capture.txt" 2>&1 || true
    fi
}

stop_monitor() {
    local pid=$1 group=${2:-false} attempt
    if [[ -n $pid ]]; then
        if [[ $group == true ]]; then
            kill -TERM -- "-$pid" 2>/dev/null || true
        else
            kill -TERM "$pid" 2>/dev/null || true
        fi
        for ((attempt = 0; attempt < 2; attempt++)); do
            if ! kill -0 "$pid" 2>/dev/null; then break; fi
            sleep 1
        done
        if [[ $group == true ]]; then
            kill -KILL -- "-$pid" 2>/dev/null || true
        else
            kill -KILL "$pid" 2>/dev/null || true
        fi
        wait "$pid" 2>/dev/null || true
    fi
}

cleanup() {
    local result=$?
    trap - EXIT INT TERM
    stop_monitor "$watchdog_pid"
    if [[ -n $gradle_pid ]]; then
        # GNU timeout owns this process group, including the Gradle launcher.
        # Its --kill-after grace also applies when termination is requested here.
        kill -TERM -- "-$gradle_pid" 2>/dev/null || true
        wait "$gradle_pid" 2>/dev/null || true
    fi
    stop_monitor "$tail_pid" true
    stop_monitor "$logcat_pid" true
    capture_diagnostics
    if [[ -s $watchdog_reason ]]; then
        cat "$watchdog_reason" >&2
        # A watchdog expiry is always a failed run, even if Gradle exits zero.
        if (( result == 0 )); then result=124; fi
    fi
    printf 'Instrumentation exit status: %s\n' "$result" | tee "$diagnostics_dir/exit-status.txt"
    exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# No logcat clear: existing emulator evidence is retained in the final snapshot.
# -T 1 avoids counting old tests as progress; these tags also expose app crashes.
timeout --kill-after=2s "$((total_seconds + term_seconds + 60))s" adb -s "$ANDROID_SERIAL" logcat -v threadtime -T 1 TestRunner:I AndroidRuntime:E '*:S' > "$runner_log" 2>&1 &
logcat_pid=$!
timeout --kill-after=2s "$((total_seconds + term_seconds + 60))s" tail --pid="$logcat_pid" -n +1 -F "$runner_log" &
tail_pid=$!

if (( $# == 0 )); then
    set -- bash ./gradlew connectedDebugAndroidTest --stacktrace
fi
echo "Instrumentation limits: total=${total_seconds}s, first TestRunner event=${start_seconds}s, progress=${progress_seconds}s."
timeout --kill-after="${term_seconds}s" "${total_seconds}s" "$@" &
gradle_pid=$!

watch_progress() {
    local started_at=$SECONDS last_progress=$SECONDS previous_count=0 count elapsed reason='' sleep_pid=''
    # The watcher owns only its polling sleep, and releases it on script exit.
    trap 'if [[ -n $sleep_pid ]]; then kill "$sleep_pid" 2>/dev/null || true; wait "$sleep_pid" 2>/dev/null || true; fi; exit 0' TERM INT
    while kill -0 "$gradle_pid" 2>/dev/null; do
        count=$(grep -Ec 'TestRunner.*(started:|finished:|run started:|run finished:)' "$runner_log" || true)
        if (( count > previous_count )); then
            previous_count=$count
            last_progress=$SECONDS
        fi
        elapsed=$((SECONDS - started_at))
        if ! kill -0 "$logcat_pid" 2>/dev/null; then
            reason='TestRunner logcat stream stopped unexpectedly; progress cannot be verified.'
        elif (( previous_count == 0 && elapsed >= start_seconds )); then
            reason="No TestRunner start event within ${start_seconds}s."
        elif (( previous_count > 0 && SECONDS - last_progress >= progress_seconds )); then
            reason="No TestRunner progress for ${progress_seconds}s; inspect the last started/finished test and teardown diagnostics."
        fi
        if [[ -n $reason ]]; then
            printf '%s\n' "$reason" | tee "$watchdog_reason" >&2
            kill -TERM -- "-$gradle_pid" 2>/dev/null || true
            # GNU timeout enforces the TERM grace even if Gradle ignores it.
            return
        fi
        sleep "$poll_seconds" &
        sleep_pid=$!
        wait "$sleep_pid"
        sleep_pid=''
    done
}
watch_progress &
watchdog_pid=$!
wait "$gradle_pid"
result=$?
gradle_pid=''
if (( result == 124 || result == 137 )); then
    printf 'Total instrumentation limit (%ss) or TERM grace expired.\n' "$total_seconds" >&2
fi
exit "$result"
