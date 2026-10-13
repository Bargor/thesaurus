import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const harness = fileURLToPath(new URL('./dev-launcher/tests.ps1', import.meta.url));
const shells = process.env.DEV_LAUNCHER_TEST_SHELL
  ? [process.env.DEV_LAUNCHER_TEST_SHELL]
  : process.platform === 'win32' ? ['powershell', 'pwsh'] : ['pwsh'];

for (const shell of shells) {
  for (const suite of ['Helpers', 'Prerequisites', 'Processes', 'Orchestration']) {
    test(`${shell}: DEV launcher ${suite}`, { timeout: 90_000 }, (t) => {
      const result = spawnSync(shell, ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
        '-File', harness, '-Suite', suite], { encoding: 'utf8', timeout: 80_000 });
      assert.ifError(result.error);
      assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
      assert.match(result.stdout, /PASS \d+ assertions/);
      t.diagnostic(result.stdout.match(/PASS \d+ assertions[^\r\n]*/)[0]);
    });
  }
}
