import fs from 'node:fs';
import path from 'node:path';

import { createRandomActionPool } from './random-editing.js';

export const DEFAULT_CONFIG = Object.freeze({
  headless: false,
  minUsers: 8,
  artifactsDir: 'artifacts',
  navigationTimeoutMs: 60000,
  actionTimeoutMs: 30000,
  login: {
    path: '/bin/login/XWiki/XWikiLogin',
    usernameSelector: 'input[name="j_username"], input[name="username"], input#j_username',
    passwordSelector: 'input[name="j_password"], input[name="password"], input#j_password',
    submitSelector: '#loginForm input[type="submit"], #loginForm button[type="submit"]'
  },
  selectors: {
    editor: [
      '.cke_wysiwyg_frame',
      'iframe[title*="Rich Text Editor"]',
      'iframe[title*="rich text editor"]',
      '[contenteditable="true"]',
      'textarea[name="content"]',
      '#content'
    ],
    realtimeConnected: [
      '.realtime-toolbar [value="clean"]',
      '.realtime-edit-toolbar',
      '.realtime-save-status',
      '.realtime-user'
    ],
    saveButton: [
      '.realtime-edit-toolbar button[type="submit"]',
      'input[name="action_saveandcontinue"]',
      'input[name="action_save"]',
      'button[name="action_saveandcontinue"]',
      'button[name="action_save"]'
    ],
    tableCell: ['td', 'th'],
    randomTextBlock: ['p', 'li', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'blockquote', 'pre', 'td', 'th']
  },
  typing: {
    iterations: 100,
    delayMs: 20,
    pauseBetweenMarkersMs: 300,
    markerPrefix: 'RT'
  },
  tableActions: {
    enabled: true,
    userIndex: 0,
    iterations: 20,
    pauseMs: 1000
  },
  save: {
    enabled: false,
    userIndex: 0,
    afterTyping: true,
    timeoutMs: 120000
  },
  verification: {
    settleMs: 2000
  },
  randomEditing: {
    enabled: false,
    durationMs: 0,
    minPauseMs: 3000,
    maxPauseMs: 8000,
    users: 'all',
    markerPrefix: 'RANDOM',
    actions: {
      insertText: 60,
      deleteText: 10,
      newline: 10,
      tableContextMenu: 10,
      tableCellText: 10
    }
  },
  trace: {
    enabled: true,
    screenshots: true,
    snapshots: true,
    sources: false
  },
  browser: {
    slowMoMs: 0,
    channel: undefined
  }
});

export function loadConfigFile(configPath) {
  const absolutePath = path.resolve(configPath);
  const raw = fs.readFileSync(absolutePath, 'utf8');
  return loadConfigFromObject(JSON.parse(raw), path.dirname(absolutePath));
}

export function loadConfigFromObject(input, baseDir = process.cwd()) {
  if (!input || typeof input !== 'object') {
    throw new Error('Configuration must be a JSON object.');
  }

  const config = mergeDeep(DEFAULT_CONFIG, input);

  requireString(config.baseURL, 'baseURL');
  requireString(config.editURL, 'editURL');

  if (!Array.isArray(config.users)) {
    throw new Error('Configuration field users must be an array.');
  }
  if (config.users.length < config.minUsers) {
    throw new Error(`This diagnostics run requires at least ${config.minUsers} users. ` +
      'Set minUsers lower only for a smoke test.');
  }

  config.users.forEach((user, index) => {
    requireString(user.id, `users[${index}].id`);
    requireString(user.username, `users[${index}].username`);
    requireString(user.password, `users[${index}].password`);
  });

  config.baseURL = trimTrailingSlash(config.baseURL);
  config.editURL = resolveEditURL(config, config.editURL);
  config.artifactsDir = path.resolve(baseDir, config.artifactsDir);
  config.selectors.editor = normalizeSelectorList(config.selectors.editor, 'selectors.editor');
  config.selectors.realtimeConnected = normalizeSelectorList(config.selectors.realtimeConnected,
    'selectors.realtimeConnected');
  config.selectors.saveButton = normalizeSelectorList(config.selectors.saveButton, 'selectors.saveButton');
  config.selectors.tableCell = normalizeSelectorList(config.selectors.tableCell, 'selectors.tableCell');
  config.selectors.randomTextBlock = normalizeSelectorList(config.selectors.randomTextBlock,
    'selectors.randomTextBlock');
  validateRandomEditingConfig(config.randomEditing);
  validateBrowserConfig(config.browser);

  return config;
}

export function resolveApplicationURL(config, value) {
  if (/^https?:\/\//i.test(value)) {
    return value;
  }
  if (value.startsWith('/')) {
    return `${config.baseURL}${value}`;
  }
  return new URL(value, `${config.baseURL}/`).toString();
}

function resolveEditURL(config, value) {
  let url;
  if (/^https?:\/\//i.test(value)) {
    url = value;
  } else if (value.startsWith('/')) {
    url = `${config.baseURL}${value}`;
  } else {
    url = `http://${value}`;
  }
  return normalizeEditURL(url);
}

// editURL must point at the WYSIWYG edit page (.../bin/edit/...). A very common
// mistake is to paste the view page URL with a "#edit" fragment
// (.../bin/view/Space/Page#edit), which never opens the editor. Rewrite that
// pattern to the equivalent edit URL so the run can actually load the editor.
function normalizeEditURL(url) {
  const match = /^(.+?\/bin\/)view\/([^#]*)(?:#.*)?$/.exec(url);
  if (match) {
    return `${match[1]}edit/${match[2]}`;
  }
  return url;
}

function validateBrowserConfig(browser) {
  if (browser.channel === undefined || browser.channel === null || browser.channel === '') {
    browser.channel = undefined;
    return;
  }
  if (typeof browser.channel !== 'string') {
    throw new Error('Configuration field browser.channel must be a string like "chrome", "msedge" or empty.');
  }
  if (!/^[a-z0-9-]+$/i.test(browser.channel)) {
    throw new Error(`Configuration field browser.channel "${browser.channel}" contains invalid characters.`);
  }
}

function requireString(value, name) {
  if (typeof value !== 'string' || !value.trim()) {
    throw new Error(`Configuration field ${name} must be a non-empty string.`);
  }
}

function normalizeSelectorList(value, name) {
  if (typeof value === 'string') {
    return [value];
  }
  if (!Array.isArray(value) || value.some(selector => typeof selector !== 'string' || !selector.trim())) {
    throw new Error(`Configuration field ${name} must be a selector string or a non-empty selector array.`);
  }
  return value;
}

function validateRandomEditingConfig(randomEditing) {
  requireNonNegativeNumber(randomEditing.durationMs, 'randomEditing.durationMs');
  requireNonNegativeNumber(randomEditing.minPauseMs, 'randomEditing.minPauseMs');
  requireNonNegativeNumber(randomEditing.maxPauseMs, 'randomEditing.maxPauseMs');
  if (randomEditing.minPauseMs > randomEditing.maxPauseMs) {
    throw new Error('randomEditing.minPauseMs must be less than or equal to randomEditing.maxPauseMs.');
  }
  requireString(randomEditing.markerPrefix, 'randomEditing.markerPrefix');
  if (randomEditing.users !== 'all' && !Array.isArray(randomEditing.users)) {
    throw new Error('Configuration field randomEditing.users must be "all" or an array of user ids.');
  }
  createRandomActionPool(randomEditing.actions);
}

function requireNonNegativeNumber(value, name) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) {
    throw new Error(`Configuration field ${name} must be a non-negative number.`);
  }
}

function mergeDeep(base, override) {
  if (Array.isArray(base) || Array.isArray(override)) {
    return override === undefined ? clone(base) : clone(override);
  }
  if (!isPlainObject(base) || !isPlainObject(override)) {
    return override === undefined ? clone(base) : clone(override);
  }

  const result = { ...base };
  for (const [key, value] of Object.entries(override)) {
    result[key] = mergeDeep(base[key], value);
  }
  return result;
}

function clone(value) {
  if (Array.isArray(value)) {
    return value.map(clone);
  }
  if (isPlainObject(value)) {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, clone(item)]));
  }
  return value;
}

function isPlainObject(value) {
  return value && typeof value === 'object' && !Array.isArray(value);
}

function trimTrailingSlash(value) {
  return value.replace(/\/$/, '');
}
