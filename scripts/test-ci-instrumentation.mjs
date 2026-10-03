import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// Offline only: every adb invocation resolves to a fixture in a private directory.
// Run on Linux CI (GNU timeout/process groups); Git Bash is a local convenience.
const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const bash = process.env.CI_TEST_BASH || (process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : 'bash');
const source = readFileSync(join(root, 'scripts/ci-instrumentation.sh'), 'utf8').replaceAll('\r\n', '\n');
const shellPath = path => path.replaceAll('\\', '/').replace(/^([A-Za-z]):/, (_, drive) => `/${drive.toLowerCase()}`);
const quote = value => `'${value.replaceAll("'", "'\\''")}'`;

function runCase({ mode = 'quiet', command = 'sleep 1; exit 0', env = {}, diagnostic = 'ok' } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'thesaurus-ci-watchdog-'));
  let completed = false;
  try {
    mkdirSync(join(dir, 'bin'));
    writeFileSync(join(dir, 'watchdog.sh'), source);
    writeFileSync(join(dir, 'runner.sh'), `#!/usr/bin/env bash\necho $$ > "$FIXTURE_DIR/runner.pid"\n${command}\n`, { mode: 0o755 });
    writeFileSync(join(dir, 'bin/adb'), `#!/usr/bin/env bash
[[ $1 == -s && $2 == fixture-emulator ]] || exit 99
shift 2
echo "$*" >> "$FIXTURE_DIR/adb.calls"
if [[ $1 == logcat && $2 != -d ]]; then
  echo $$ > "$FIXTURE_DIR/logger.pid"
  case "$FIXTURE_MODE" in
    stopped) exit 9 ;;
    once) echo 'I TestRunner: started: fixtureTest' ;;
    continuous) while true; do echo 'I TestRunner: finished: fixtureTest'; sleep 0.2; done ;;
  esac
  exec sleep 100
fi
case "$FIXTURE_DIAGNOSTIC" in
  failed) echo 'mock capture failure'; exit 17 ;;
  stuck) exec sleep 100 ;;
esac
echo 'mock diagnostic evidence'
`, { mode: 0o755 });
    const fixture = shellPath(dir);
    // PATH is overridden inside Bash after profile startup, before the watchdog.
    // The executable fixture is asserted first: a typo can never call real adb.
    const commandLine = `export PATH=${quote(`${fixture}/bin`)}:/usr/bin:/bin; [[ $(command -v adb) == ${quote(`${fixture}/bin/adb`)} ]] || exit 98; cd ${quote(fixture)}; timeout --kill-after=2s 22s bash watchdog.sh bash runner.sh`;
    const started = Date.now();
    const result = spawnSync(bash, ['--noprofile', '--norc', '-c', commandLine], {
      encoding: 'utf8', timeout: 28000,
      env: { ...process.env, ANDROID_SERIAL: 'fixture-emulator', FIXTURE_DIR: fixture, FIXTURE_MODE: mode,
        FIXTURE_DIAGNOSTIC: diagnostic, CI_INSTRUMENTATION_DIAGNOSTICS_DIR: `${fixture}/diagnostics`,
        CI_INSTRUMENTATION_TIMEOUT_SECONDS: '8', CI_INSTRUMENTATION_START_GRACE_SECONDS: '3',
        CI_INSTRUMENTATION_PROGRESS_TIMEOUT_SECONDS: '2', CI_INSTRUMENTATION_POLL_SECONDS: '1',
        CI_INSTRUMENTATION_TERM_GRACE_SECONDS: '1', CI_INSTRUMENTATION_ADB_TIMEOUT_SECONDS: '1', ...env },
    });
    assert.ifError(result.error);
    const output = `${result.stdout}\n${result.stderr}`;
    assert.notEqual(result.status, 98, output);
    const files = {};
    for (const name of ['watchdog-reason.txt', 'exit-status.txt', 'logcat.txt', 'activity.txt', 'window.txt', 'processes.txt', 'last-anr.txt']) {
      const path = join(dir, 'diagnostics', name);
      if (existsSync(path)) files[name] = readFileSync(path, 'utf8');
    }
    // Verify owned fixture processes disappeared, not merely the wrapper exit.
    for (const name of ['runner.pid', 'logger.pid']) {
      if (!existsSync(join(dir, name))) continue;
      const pid = readFileSync(join(dir, name), 'utf8').trim();
      const probe = spawnSync(bash, ['--noprofile', '--norc', '-c', `kill -0 ${pid} 2>/dev/null`], { timeout: 3000 });
      assert.equal(probe.status, 1, `left running ${name}: ${pid}\n${output}`);
    }
    completed = true;
    return { status: result.status, output, files, elapsed: Date.now() - started,
      calls: existsSync(join(dir, 'adb.calls')) ? readFileSync(join(dir, 'adb.calls'), 'utf8') : '' };
  } finally {
    // On a failed assertion or outer test deadline, release only the fixture
    // PIDs we recorded. Keep cleanup bounded and never target other adb/tasks.
    const ownedPids = ['runner.pid', 'logger.pid']
      .filter(name => existsSync(join(dir, name)))
      .map(name => readFileSync(join(dir, name), 'utf8').trim())
      .filter(pid => /^[1-9][0-9]*$/.test(pid));
    if (!completed && ownedPids.length) spawnSync(bash, ['--noprofile', '--norc', '-c',
      `for pid in ${ownedPids.join(' ')}; do kill -KILL "$pid" 2>/dev/null || true; done`], { timeout: 3000 });
    rmSync(dir, { recursive: true, force: true });
  }
}

test('success captures diagnostics and cleans up fixture processes', () => {
  const result = runCase();
  assert.equal(result.status, 0, result.output);
  assert.match(result.files['exit-status.txt'], /status: 0/);
  for (const name of ['logcat.txt', 'activity.txt', 'window.txt', 'processes.txt', 'last-anr.txt']) assert.match(result.files[name], /mock diagnostic/);
  assert.match(result.calls, /logcat -v threadtime -T 1/);
  assert.doesNotMatch(result.calls, /logcat -c/);
});
test('Gradle failure survives failed diagnostic captures', () => {
  const result = runCase({ command: 'sleep 1; exit 42', diagnostic: 'failed' });
  assert.equal(result.status, 42, result.output);
  assert.match(result.files['exit-status.txt'], /status: 42/);
});
test('stuck diagnostic commands are bounded and preserve Gradle status', () => {
  const result = runCase({ command: 'sleep 1; exit 42', diagnostic: 'stuck' });
  assert.equal(result.status, 42, result.output);
  assert.ok(result.elapsed < 16000, `captures took ${result.elapsed}ms`);
});
test('missing first TestRunner event expires initial grace', () => {
  const result = runCase({ command: 'sleep 100' });
  assert.notEqual(result.status, 0, result.output);
  assert.match(result.files['watchdog-reason.txt'], /No TestRunner start event within 3s/);
});
test('stalled progress terminates a TERM-ignoring launcher', () => {
  const result = runCase({ mode: 'once', command: "trap '' TERM; while true; do sleep 1; done" });
  assert.notEqual(result.status, 0, result.output);
  assert.match(result.files['watchdog-reason.txt'], /No TestRunner progress for 2s/);
});
test('total timeout expires despite continuous TestRunner progress', () => {
  const result = runCase({ mode: 'continuous', command: 'sleep 100', env: { CI_INSTRUMENTATION_TIMEOUT_SECONDS: '3' } });
  assert.equal(result.status, 124, result.output);
  assert.match(result.output, /Total instrumentation limit/);
});
test('unexpected logcat exit fails the run', () => {
  const result = runCase({ mode: 'stopped', command: 'sleep 100' });
  assert.notEqual(result.status, 0, result.output);
  assert.match(result.files['watchdog-reason.txt'], /logcat stream stopped unexpectedly/);
});
test('all numeric limits and explicit emulator serial are validated before adb', () => {
  const variables = ['TIMEOUT', 'START_GRACE', 'PROGRESS_TIMEOUT', 'POLL', 'TERM_GRACE', 'ADB_TIMEOUT'];
  for (const name of variables) {
    const result = runCase({ env: { [`CI_INSTRUMENTATION_${name}_SECONDS`]: '0' } });
    assert.equal(result.status, 2, result.output);
    assert.equal(result.calls, '');
  }
  for (const value of ['-1', '1.5', 'x', '999999999']) assert.equal(runCase({ env: { CI_INSTRUMENTATION_TIMEOUT_SECONDS: value } }).status, 2);
  assert.equal(runCase({ env: { ANDROID_SERIAL: '' } }).status, 2);
});
test('both workflow jobs run the harness, wrapper and always-upload diagnostics within budget', () => {
  const workflow = readFileSync(join(root, '.github/workflows/android.yml'), 'utf8');
  const jobs = workflow.split(/^  verify-api-/m).slice(1);
  assert.equal(jobs.length, 2);
  for (const job of jobs) {
    assert.match(job, /timeout-minutes: 45/);
    assert.match(job, /npm run test:ci-instrumentation/);
    assert.match(job, /timeout-minutes: 25/);
    assert.match(job, /ANDROID_SERIAL: emulator-5554/);
    assert.match(job, /firebase emulators:exec[^\n]+"bash scripts\/ci-instrumentation.sh"/);
    assert.match(job, /Upload instrumentation diagnostics\s+if: always\(\)/);
    assert.match(job, /app\/build\/ci-instrumentation\//);
    for (const name of ['firebase-debug.log', 'firestore-debug.log', 'ui-debug.log']) assert.ok(job.includes(name));
  }
  assert.match(source, /CI_INSTRUMENTATION_TIMEOUT_SECONDS:-1200/);
  // The 25m step also includes emulator boot and Firebase startup/shutdown.
  // This checks headroom for those phases, not a guarantee of their duration.
  // 20m instrumentation + TERM grace + monitor cleanup + five bounded captures.
  assert.ok(1200 + 15 + 6 + 5 * (10 + 2) < 25 * 60);
});
