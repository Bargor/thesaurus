import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const workflow = readFileSync(new URL('../.github/workflows/android.yml', import.meta.url), 'utf8');
function job(name) {
  const start = workflow.indexOf('  ' + name + ':');
  assert.ok(start >= 0);
  const remaining = workflow.slice(start);
  return remaining.split(/\n  [a-z][a-z0-9-]*:/)[0];
}
test('APK builds use isolated runners with bounded workers and heap', () => {
  for (const [name, tasks] of [
    ['build-test-apks', 'assembleDebug assembleDebugAndroidTest'],
    ['build-release', 'assembleRelease'],
    ['verify-dev', 'assembleDevDebug'],
  ]) {
    assert.ok(job(name).includes('bash ./gradlew ' + tasks +
      " --stacktrace --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8'"));
    assert.ok(!job(name).includes('android-emulator-runner'));
  }
});
test('instrumentation consumes the shared APK artifact without building', () => {
  assert.ok(job('build-test-apks').includes('if-no-files-found: error'));
  for (const name of ['instrumentation-api31', 'instrumentation-api37']) {
    assert.match(job(name), /needs: build-test-apks/);
    assert.match(job(name), /name: instrumentation-apks/);
    assert.ok(!job(name).includes('./gradlew'));
    assert.match(job(name), /shard: \[0, 1, 2\]/);
  }
});
test('resource policy tests remain wired into CI', () => {
  assert.ok(job('firebase-and-tooling').includes('npm run test:ci-assembly'));
});
