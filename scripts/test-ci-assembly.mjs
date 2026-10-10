import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// Execute the workflow's assembly block offline. The only gradlew is a private fixture:
// these tests never start Gradle, a device, Firebase or the developer app.
const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const workflow = readFileSync(join(root, '.github/workflows/android.yml'), 'utf8').replaceAll('\r\n', '\n');
const jobs = workflow.split(/^  verify-api-/m).slice(1);
const bash = process.env.CI_TEST_BASH || (process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : 'bash');
const variants = ['assembleDebug', 'assembleRelease', 'assembleDevDebug'];

function assemblyBlock(job) {
  const step = job.match(/      - name: Assemble debug, release and developer APKs\n([\s\S]*?)(?=      - name:|$)/);
  assert.ok(step, 'Both API jobs must retain APK assembly');
  const block = step[1].match(/        run: \|\n((?:          [^\n]*\n)+)/);
  assert.ok(block, 'APK variants must run as sequential shell commands');
  return block[1].replace(/^          /gm, '');
}

function runAssembly(block, failedTask = '') {
  const dir = mkdtempSync(join(tmpdir(), 'thesaurus-ci-assembly-'));
  try {
    writeFileSync(join(dir, 'assemble.sh'), block);
    writeFileSync(join(dir, 'gradlew'), `#!/usr/bin/env bash
printf '%s\\t' "$@" >> calls.txt
printf '\\n' >> calls.txt
[[ $1 != "$FIXTURE_FAIL_TASK" ]] || exit 41
`);
    // GitHub Actions runs its Linux shell steps with errexit; include pipefail too.
    const result = spawnSync(bash, ['--noprofile', '--norc', '-e', '-o', 'pipefail', 'assemble.sh'], {
      cwd: dir, encoding: 'utf8', timeout: 5000,
      env: { ...process.env, FIXTURE_FAIL_TASK: failedTask },
    });
    assert.ifError(result.error);
    const calls = existsSync(join(dir, 'calls.txt'))
      ? readFileSync(join(dir, 'calls.txt'), 'utf8').trim().split('\n').map(line => line.trimEnd().split('\t'))
      : [];
    return { ...result, calls };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test('both API jobs assemble every variant separately with bounded workers and a scoped heap', () => {
  assert.equal(jobs.length, 2);
  for (const job of jobs) {
    assert.match(job, /npm run test:ci-assembly/);
    const result = runAssembly(assemblyBlock(job));
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(result.calls.map(call => call[0]), variants);
    for (const call of result.calls) {
      assert.deepEqual(call, [call[0], '--stacktrace', '--no-daemon', '--max-workers=2',
        '-Dorg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8']);
    }
  }
});

test('a failed variant preserves its failure and prevents later APK builds in both API jobs', () => {
  for (const job of jobs) {
    for (const [index, failedTask] of variants.entries()) {
      const result = runAssembly(assemblyBlock(job), failedTask);
      assert.equal(result.status, 41, result.stderr);
      assert.deepEqual(result.calls.map(call => call[0]), variants.slice(0, index + 1));
    }
  }
});
