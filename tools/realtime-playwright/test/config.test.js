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
  assert.equal(config.randomEditing.enabled, false);
  assert.equal(config.randomEditing.durationMs, 0);
  assert.equal(config.randomEditing.minPauseMs, 3000);
  assert.equal(config.randomEditing.maxPauseMs, 8000);
  assert.equal(config.login.submitSelector, '#loginForm input[type="submit"], #loginForm button[type="submit"]');
  assert.equal(path.isAbsolute(config.artifactsDir), true);
  assert.equal(config.artifactsDir.endsWith('artifacts'), true);
  assert.equal(config.selectors.editor.length > 0, true);
  assert.equal(config.selectors.randomTextBlock.includes('p'), true);
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

test('allows continuous random editing mode with low-frequency actions', () => {
  const config = loadConfigFromObject({
    baseURL: 'http://localhost:8080/xwiki',
    editURL: 'http://localhost:8080/xwiki/bin/edit/Test/Page?editor=wysiwyg',
    minUsers: 1,
    users: [{ id: 'U1', username: 'user1', password: 'secret' }],
    randomEditing: {
      enabled: true,
      durationMs: 5000,
      minPauseMs: 1000,
      maxPauseMs: 2000
    }
  });

  assert.equal(config.randomEditing.enabled, true);
  assert.equal(config.randomEditing.durationMs, 5000);
  assert.equal(config.randomEditing.minPauseMs, 1000);
  assert.equal(config.randomEditing.maxPauseMs, 2000);
  assert.equal(config.randomEditing.actions.insertText, 60);
});

test('rejects random editing pause ranges with a minimum above the maximum', () => {
  assert.throws(() => loadConfigFromObject({
    baseURL: 'http://localhost:8080/xwiki',
    editURL: 'http://localhost:8080/xwiki/bin/edit/Test/Page?editor=wysiwyg',
    minUsers: 1,
    users: [{ id: 'U1', username: 'user1', password: 'secret' }],
    randomEditing: {
      minPauseMs: 9000,
      maxPauseMs: 1000
    }
  }), /randomEditing.minPauseMs must be less than or equal to randomEditing.maxPauseMs/);
});

test('accepts an explicit browser channel such as system Chrome', () => {
  const config = loadConfigFromObject({
    baseURL: 'http://localhost:8080/xwiki',
    editURL: 'http://localhost:8080/xwiki/bin/edit/Test/Page?editor=wysiwyg',
    minUsers: 1,
    users: [{ id: 'U1', username: 'user1', password: 'secret' }],
    browser: { channel: 'chrome' }
  });

  assert.equal(config.browser.channel, 'chrome');
});

test('normalizes an empty browser channel to the bundled Chromium', () => {
  const config = loadConfigFromObject({
    baseURL: 'http://localhost:8080/xwiki',
    editURL: 'http://localhost:8080/xwiki/bin/edit/Test/Page?editor=wysiwyg',
    minUsers: 1,
    users: [{ id: 'U1', username: 'user1', password: 'secret' }],
    browser: { channel: '' }
  });

  assert.equal(config.browser.channel, undefined);
});

test('rejects invalid browser channels', () => {
  assert.throws(() => loadConfigFromObject({
    baseURL: 'http://localhost:8080/xwiki',
    editURL: 'http://localhost:8080/xwiki/bin/edit/Test/Page?editor=wysiwyg',
    minUsers: 1,
    users: [{ id: 'U1', username: 'user1', password: 'secret' }],
    browser: { channel: 42 }
  }), /browser.channel must be a string/);
});

test('auto-prepends http:// to an editURL that is missing its scheme', () => {
  const config = loadConfigFromObject({
    baseURL: 'http://192.168.1.10:8080/xwiki',
    editURL: '192.168.1.10:8080/xwiki/bin/edit/Test/Page?editor=wysiwyg',
    minUsers: 1,
    users: [{ id: 'U1', username: 'user1', password: 'secret' }]
  });

  assert.equal(
    config.editURL,
    'http://192.168.1.10:8080/xwiki/bin/edit/Test/Page?editor=wysiwyg'
  );
});

test('resolves a path-only editURL against baseURL', () => {
  const config = loadConfigFromObject({
    baseURL: 'http://192.168.1.10:8080/xwiki',
    editURL: '/xwiki/bin/edit/Test/Page?editor=wysiwyg',
    minUsers: 1,
    users: [{ id: 'U1', username: 'user1', password: 'secret' }]
  });

  assert.equal(config.editURL, 'http://192.168.1.10:8080/xwiki/xwiki/bin/edit/Test/Page?editor=wysiwyg');
});

test('keeps an absolute editURL unchanged', () => {
  const config = loadConfigFromObject({
    baseURL: 'http://192.168.1.10:8080/xwiki',
    editURL: 'https://edit.example.com/xwiki/bin/edit/Test/Page?editor=wysiwyg',
    minUsers: 1,
    users: [{ id: 'U1', username: 'user1', password: 'secret' }]
  });

  assert.equal(config.editURL, 'https://edit.example.com/xwiki/bin/edit/Test/Page?editor=wysiwyg');
});

test('rewrites a view URL with a #edit fragment into the edit URL', () => {
  const config = loadConfigFromObject({
    baseURL: 'http://localhost:8080/xwiki',
    editURL: 'http://localhost:8080/xwiki/bin/view/Main/#edit',
    minUsers: 1,
    users: [{ id: 'U1', username: 'user1', password: 'secret' }]
  });

  assert.equal(config.editURL, 'http://localhost:8080/xwiki/bin/edit/Main/');
});

test('rewrites a scheme-less view URL with #edit into the edit URL', () => {
  const config = loadConfigFromObject({
    baseURL: 'http://192.168.1.10:8080/xwiki',
    editURL: '192.168.1.10:8080/xwiki/bin/view/Main/WebHome#edit',
    minUsers: 1,
    users: [{ id: 'U1', username: 'user1', password: 'secret' }]
  });

  assert.equal(config.editURL, 'http://192.168.1.10:8080/xwiki/bin/edit/Main/WebHome');
});

test('rewrites a path-only view URL with #edit into the edit URL', () => {
  const config = loadConfigFromObject({
    baseURL: 'http://localhost:8080/xwiki',
    editURL: '/xwiki/bin/view/Space/Page#edit',
    minUsers: 1,
    users: [{ id: 'U1', username: 'user1', password: 'secret' }]
  });

  assert.equal(config.editURL, 'http://localhost:8080/xwiki/xwiki/bin/edit/Space/Page');
});

test('leaves a proper edit URL untouched', () => {
  const config = loadConfigFromObject({
    baseURL: 'http://localhost:8080/xwiki',
    editURL: 'http://localhost:8080/xwiki/bin/edit/Main/WebHome?editor=wysiwyg',
    minUsers: 1,
    users: [{ id: 'U1', username: 'user1', password: 'secret' }]
  });

  assert.equal(config.editURL, 'http://localhost:8080/xwiki/bin/edit/Main/WebHome?editor=wysiwyg');
});
