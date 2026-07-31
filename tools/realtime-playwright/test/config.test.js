import assert from 'node:assert/strict';
import path from 'node:path';
import { test } from 'node:test';

import { loadConfigFromObject, resolveApplicationURL } from '../lib/config.js';

test('fills defaults for an eight-user realtime run', () => {
  const config = loadConfigFromObject({
    baseURL: 'http://localhost:8080/xwiki',
    editURL: 'http://localhost:8080/xwiki/bin/edit/Test/Page?editor=wysiwyg',
    users: Array.from({ length: 8 }, (_, index) => ({
      id: `U${index + 1}`,
      username: `user${index + 1}`,
      password: 'secret'
    }))
  });

  assert.equal(config.users.length, 8);
  assert.equal(config.headless, false);
  assert.equal(config.typing.iterations, 100);
  assert.equal(config.typing.delayMs, 20);
  assert.equal(config.verification.settleMs, 2000);
  assert.equal(config.login.submitSelector, '#loginForm input[type="submit"], #loginForm button[type="submit"]');
  assert.equal(path.isAbsolute(config.artifactsDir), true);
  assert.equal(config.artifactsDir.endsWith('artifacts'), true);
  assert.equal(config.selectors.editor.length > 0, true);
});

test('rejects runs that do not define eight users by default', () => {
  assert.throws(() => loadConfigFromObject({
    baseURL: 'http://localhost:8080/xwiki',
    editURL: 'http://localhost:8080/xwiki/bin/edit/Test/Page?editor=wysiwyg',
    users: [{ id: 'U1', username: 'user1', password: 'secret' }]
  }), /at least 8 users/);
});

test('allows a smaller smoke test when explicitly requested', () => {
  const config = loadConfigFromObject({
    baseURL: 'http://localhost:8080/xwiki',
    editURL: 'http://localhost:8080/xwiki/bin/edit/Test/Page?editor=wysiwyg',
    minUsers: 1,
    users: [{ id: 'U1', username: 'user1', password: 'secret' }]
  });

  assert.equal(config.users.length, 1);
});

test('resolves application-relative paths below the configured XWiki base URL', () => {
  const config = loadConfigFromObject({
    baseURL: 'http://localhost:8080/xwiki',
    editURL: 'http://localhost:8080/xwiki/bin/edit/Test/Page?editor=wysiwyg',
    minUsers: 1,
    users: [{ id: 'U1', username: 'user1', password: 'secret' }]
  });

  assert.equal(
    resolveApplicationURL(config, config.login.path),
    'http://localhost:8080/xwiki/bin/login/XWiki/XWikiLogin'
  );
});
