import fs from 'node:fs';
import path from 'node:path';

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
    tableCell: ['td', 'th']
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
  trace: {
    enabled: true,
    screenshots: true,
    snapshots: true,
    sources: false
  },
  browser: {
    slowMoMs: 0
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
  config.artifactsDir = path.resolve(baseDir, config.artifactsDir);
  config.selectors.editor = normalizeSelectorList(config.selectors.editor, 'selectors.editor');
  config.selectors.realtimeConnected = normalizeSelectorList(config.selectors.realtimeConnected,
    'selectors.realtimeConnected');
  config.selectors.saveButton = normalizeSelectorList(config.selectors.saveButton, 'selectors.saveButton');
  config.selectors.tableCell = normalizeSelectorList(config.selectors.tableCell, 'selectors.tableCell');

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
