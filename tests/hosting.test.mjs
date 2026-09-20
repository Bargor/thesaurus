import { test } from 'node:test';
import assert from 'node:assert/strict';

test('invitation URL serves Polish fallback instructions over hosting rewrite', async () => {
  const response = await fetch('http://127.0.0.1:5000/zaproszenie/test-household/test-token');
  assert.equal(response.status, 200);
  assert.match(response.headers.get('content-type') ?? '', /text\/html/);
  const page = await response.text();
  assert.match(page, /lang="pl"/);
  assert.match(page, /Zaproszenie do rodziny w Thesaurus/);
  assert.doesNotMatch(page, /test-token/);
});
