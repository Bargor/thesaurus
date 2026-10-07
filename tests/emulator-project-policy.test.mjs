import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { rulesProjectId } from './emulator-project-policy.mjs';

test('rules fixtures default to their own emulator-only namespace', () => {
  assert.equal(rulesProjectId(), 'demo-thesaurus-rules');
  assert.equal(rulesProjectId('demo-thesaurus-rules-95'), 'demo-thesaurus-rules-95');
});

test('production, developer and integration namespaces are rejected before SDK access', () => {
  for (const project of ['thesaurus', 'demo-thesaurus', 'demo-thesaurus-integration', 'demo-other',
    '', null, 95, 'demo-thesaurus-rules-', 'demo-thesaurus-rules/x', ' demo-thesaurus-rules']) {
    assert.throws(() => rulesProjectId(project), /Rules tests require/);
  }
});

test('the rules runner validates its namespace and CLI starts the same namespace', () => {
  const runner = readFileSync(new URL('./firestore.rules.test.mjs', import.meta.url), 'utf8');
  assert.match(runner, /rulesProjectId\(process\.env\.THESAURUS_RULES_PROJECT_ID\)/);
  const scripts = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')).scripts;
  assert.match(scripts['test:rules'], /emulators:exec --project demo-thesaurus-rules --only firestore/);
});
