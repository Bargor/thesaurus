import assert from 'node:assert/strict';
import test from 'node:test';
import { parseResults } from './ci-shard.mjs';

const result = status => 'INSTRUMENTATION_STATUS: class=example.Test\nINSTRUMENTATION_STATUS: test=case\nINSTRUMENTATION_STATUS: stack=detail <&>\nINSTRUMENTATION_STATUS_CODE: ' + status + '\nINSTRUMENTATION_CODE: -1\n';
test('successful tests produce XML', () => {
  const report = parseResults(result(0));
  assert.equal(report.failures, 0);
  assert.match(report.xml, /tests="1"/);
});
test('test failures fail even with successful runner completion', () => {
  for (const status of [-1, -2]) {
    const report = parseResults(result(status));
    assert.equal(report.failures, 1);
    assert.match(report.xml, /detail &lt;&amp;&gt;/);
  }
});
test('ignored tests and assumption failures are skipped', () => {
  for (const status of [-3, -4]) {
    assert.equal(parseResults(result(status)).failures, 0);
    assert.match(parseResults(result(status)).xml, /<skipped\/>/);
  }
});
test('empty, crashed and truncated runs fail', () => {
  for (const output of ['', 'INSTRUMENTATION_CODE: -1', result(0).replace('INSTRUMENTATION_CODE: -1', ''),
    result(0) + 'INSTRUMENTATION_RESULT: shortMsg=Process crashed\n']) {
    assert.ok(parseResults(output).failures > 0);
  }
});
