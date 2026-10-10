import { spawnSync } from 'node:child_process';
import { mkdirSync, readdirSync, writeFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';

const escapeXml = value => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');

// adb may exit zero even when the instrumentation runner reports a failure.
export function parseResults(output) {
  const cases = [];
  let fields = {}, key;
  let complete = false, fatal = false;
  for (const line of output.replaceAll('\r', '').split('\n')) {
    const field = line.match(/^INSTRUMENTATION_STATUS: ([^=]+)=(.*)$/);
    const code = line.match(/^INSTRUMENTATION_STATUS_CODE: (-?\d+)$/);
    if (field) {
      key = field[1];
      fields[key] = field[2];
    } else if (code) {
      const status = Number(code[1]);
      if ([0, -1, -2, -3, -4].includes(status) && fields.class && fields.test) {
        cases.push({ classname: fields.class, name: fields.test,
          failed: status === -1 || status === -2, skipped: status === -3 || status === -4,
          detail: fields.stack || '' });
      } else if (status < 0) fatal = true;
      fields = {}; key = undefined;
    } else if (line.startsWith('INSTRUMENTATION_CODE:')) {
      complete = line.trim() === 'INSTRUMENTATION_CODE: -1';
    } else if (/^INSTRUMENTATION_(FAILED|ABORTED):/.test(line) || /^INSTRUMENTATION_RESULT: (shortMsg|longMsg)=/.test(line)) {
      fatal = true;
    } else if (key && !line.startsWith('INSTRUMENTATION_')) {
      fields[key] += '\n' + line;
    }
  }
  if (!complete || fatal || !cases.length) {
    cases.push({ classname: 'CI', name: 'runner', failed: true, detail: output });
  }
  const failures = cases.filter(test => test.failed).length;
  const xml = '<?xml version="1.0" encoding="UTF-8"?>\n' +
    '<testsuite name="instrumentation" tests="' + cases.length + '" failures="' + failures + '" skipped="' + cases.filter(test => test.skipped).length + '">' +
    cases.map(test => '<testcase classname="' + escapeXml(test.classname) + '" name="' + escapeXml(test.name) + '">' +
      (test.failed ? '<failure>' + escapeXml(test.detail) + '</failure>' : test.skipped ? '<skipped/>' : '') + '</testcase>').join('') +
    '</testsuite>\n';
  return { failures, xml };
}

function main() {
  const serial = process.env.ANDROID_SERIAL;
  const count = Number(process.env.CI_NUM_SHARDS);
  const index = Number(process.env.CI_SHARD_INDEX);
  if (!serial || !Number.isInteger(count) || count < 1 || !Number.isInteger(index) || index < 0 || index >= count) {
    throw new Error('Explicit serial and valid CI_NUM_SHARDS / CI_SHARD_INDEX are required');
  }
  const directory = 'app/build/ci-instrumentation';
  mkdirSync(directory, { recursive: true });
  const adb = args => spawnSync('adb', ['-s', serial, ...args], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
  for (const folder of ['debug', 'androidTest/debug']) {
    const dir = join('test-apks', folder);
    const apks = readdirSync(dir).filter(file => file.endsWith('.apk'));
    if (apks.length !== 1) throw new Error('Expected exactly one APK in ' + dir);
    const result = adb(['install', '-r', '-t', join(dir, apks[0])]);
    if (result.error || result.status !== 0) throw new Error(result.error?.message || result.stdout + result.stderr);
  }
  const result = adb(['shell', 'am', 'instrument', '-w', '-r', '-e', 'numShards', String(count),
    '-e', 'shardIndex', String(index), 'pl.bargor.thesaurus.test/androidx.test.runner.AndroidJUnitRunner']);
  const output = (result.stdout || '') + (result.stderr || '');
  process.stdout.write(output);
  writeFileSync(join(directory, 'instrumentation.txt'), output);
  const report = parseResults(output);
  writeFileSync(join(directory, 'results.xml'), report.xml);
  process.exitCode = result.error || result.status !== 0 || report.failures ? 1 : 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) main();
