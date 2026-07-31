#!/usr/bin/env node
import path from 'node:path';
import process from 'node:process';
import { chromium } from 'playwright';

import { loadConfigFile, resolveApplicationURL } from './lib/config.js';
import { createRecorder, ensureDirectory, writeJson } from './lib/diagnostics.js';

async function main() {
  const configPath = getArg('--config') || 'config.json';
  const config = loadConfigFile(configPath);
  ensureDirectory(config.artifactsDir);

  const recorder = createRecorder(config);
  recorder.event('run-start', {
    users: config.users.map(user => user.id),
    editURL: config.editURL,
    artifactsDir: config.artifactsDir
  });

  const browser = await chromium.launch({
    headless: config.headless,
    slowMo: config.browser.slowMoMs
  });

  const sessions = [];
  try {
    for (const [index, user] of config.users.entries()) {
      sessions.push(await createSession(browser, config, recorder, user, index));
    }

    await Promise.all(sessions.map(session => prepareEditor(session, config, recorder)));

    const typingResults = await Promise.all(sessions.map(session => typeMarkers(session, config, recorder)));
    const tableResult = config.tableActions.enabled
      ? await runTableActions(sessions[config.tableActions.userIndex], config, recorder)
      : { skipped: true };

    if (config.save.enabled && config.save.afterTyping) {
      await runSave(sessions[config.save.userIndex], config, recorder);
    }

    await waitBeforeVerification(sessions, config, recorder);
    const verification = await verifyMarkers(sessions, typingResults, config, recorder);
    const summary = summarize(config, typingResults, tableResult, verification, recorder.events);
    writeJson(path.join(config.artifactsDir, 'summary.json'), summary);
    recorder.event('run-finished', { missingMarkers: summary.missingMarkers.length, severeInputs: summary.severeInputs });

    process.exitCode = summary.missingMarkers.length || summary.severeInputs ? 2 : 0;
  } finally {
    await Promise.allSettled(sessions.map(session => closeSession(session, config, recorder)));
    recorder.flush();
    await browser.close();
  }
}

async function waitBeforeVerification(sessions, config, recorder) {
  if (config.verification.settleMs <= 0) {
    return;
  }
  recorder.event('verification-wait-start', { durationMs: config.verification.settleMs });
  await Promise.all(sessions.map(session => session.page.waitForTimeout(config.verification.settleMs)));
  recorder.event('verification-wait-finished', { durationMs: config.verification.settleMs });
}

async function createSession(browser, config, recorder, user, index) {
  const userDir = path.join(config.artifactsDir, user.id);
  ensureDirectory(userDir);

  const context = await browser.newContext({
    ignoreHTTPSErrors: true,
    recordVideo: { dir: path.join(userDir, 'video') }
  });
  context.setDefaultNavigationTimeout(config.navigationTimeoutMs);
  context.setDefaultTimeout(config.actionTimeoutMs);

  if (config.trace.enabled) {
    await context.tracing.start({
      screenshots: config.trace.screenshots,
      snapshots: config.trace.snapshots,
      sources: config.trace.sources
    });
  }

  const page = await context.newPage();
  attachPageDiagnostics(page, recorder, user.id);

  recorder.event('session-created', { userId: user.id, index });
  await login(page, config, user, recorder);
  await page.goto(config.editURL, { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('networkidle').catch(() => {});
  recorder.event('edit-page-opened', { userId: user.id, url: page.url() });

  return { user, context, page, userDir, editor: null };
}

function attachPageDiagnostics(page, recorder, userId) {
  page.on('console', message => {
    const text = message.text();
    if (/error|warn|realtime|netflux|websocket|merge|save|ckeditor/i.test(`${message.type()} ${text}`)) {
      recorder.event('console', { userId, level: message.type(), text });
    }
  });
  page.on('pageerror', error => recorder.event('page-error', { userId, message: error.message, stack: error.stack }));
  page.on('requestfailed', request => recorder.event('request-failed', {
    userId,
    method: request.method(),
    url: request.url(),
    error: request.failure()?.errorText
  }));
  page.on('response', response => {
    if (/save|docsave|rest|websocket|netflux/i.test(response.url()) && response.status() >= 400) {
      recorder.event('http-error', { userId, status: response.status(), url: response.url() });
    }
  });
  page.on('websocket', ws => {
    recorder.event('websocket-open', { userId, url: ws.url() });
    ws.on('close', () => recorder.event('websocket-close', { userId, url: ws.url() }));
  });
}

async function login(page, config, user, recorder) {
  const loginURL = resolveApplicationURL(config, config.login.path);
  await page.goto(loginURL, { waitUntil: 'domcontentloaded' });
  await page.fill(config.login.usernameSelector, user.username);
  await page.fill(config.login.passwordSelector, user.password);
  await Promise.all([
    page.waitForLoadState('networkidle').catch(() => {}),
    page.click(config.login.submitSelector)
  ]);
  recorder.event('login-submitted', { userId: user.id, url: page.url() });
}

async function prepareEditor(session, config, recorder) {
  await waitForRealtimeHint(session.page, config).catch(error => {
    recorder.event('realtime-hint-timeout', { userId: session.user.id, message: error.message });
  });
  session.editor = await findEditor(session.page, config);
  await session.editor.click();
  recorder.event('editor-ready', { userId: session.user.id, kind: session.editor.kind });
}

async function waitForRealtimeHint(page, config) {
  await page.waitForFunction(selectors => selectors.some(selector => document.querySelector(selector)),
    config.selectors.realtimeConnected, { timeout: 15000 });
}

async function findEditor(page, config) {
  const ckeditorHandle = await page.evaluateHandle(() => {
    const instances = globalThis.CKEDITOR?.instances ? Object.values(globalThis.CKEDITOR.instances) : [];
    const instance = instances.find(item => item?.editable?.()?.$ || item?.document?.$);
    return instance?.editable?.()?.$ || null;
  });
  const ckeditorElement = ckeditorHandle.asElement();
  if (ckeditorElement) {
    return { kind: 'ckeditor-instance', click: () => ckeditorElement.click(), locator: page.locator('body') };
  }

  for (const selector of config.selectors.editor) {
    const locator = page.locator(selector).first();
    if (await locator.count()) {
      const tagName = await locator.evaluate(element => element.tagName.toLowerCase()).catch(() => '');
      if (tagName === 'iframe') {
        const frame = await locator.elementHandle().then(handle => handle.contentFrame());
        const editable = frame.locator('[contenteditable="true"], body').first();
        await editable.waitFor({ state: 'visible' });
        return { kind: `iframe:${selector}`, click: () => editable.click(), locator: editable };
      }
      await locator.waitFor({ state: 'visible' });
      return { kind: selector, click: () => locator.click(), locator };
    }
  }

  throw new Error('Unable to locate a CKEditor/contenteditable editor. Adjust selectors.editor in config.json.');
}

async function typeMarkers(session, config, recorder) {
  const markers = [];
  for (let i = 0; i < config.typing.iterations; i++) {
    const marker = `${config.typing.markerPrefix}-${session.user.id}-${String(i + 1).padStart(4, '0')}`;
    const text = ` ${marker} `;
    const started = Date.now();

    await session.editor.click();
    await session.page.keyboard.type(text, { delay: config.typing.delayMs });
    const durationMs = Date.now() - started;
    markers.push({ marker, durationMs });

    recorder.event('typed-marker', { userId: session.user.id, marker, durationMs });
    await session.page.waitForTimeout(config.typing.pauseBetweenMarkersMs);
  }
  await session.page.screenshot({ path: path.join(session.userDir, 'after-typing.png'), fullPage: true });
  return { userId: session.user.id, markers };
}

async function runTableActions(session, config, recorder) {
  if (!session) {
    return { skipped: true, reason: 'No table action user session.' };
  }
  const result = { attempts: 0, contextMenus: 0, errors: [] };
  for (let i = 0; i < config.tableActions.iterations; i++) {
    try {
      const cell = await findTableCell(session.page, config);
      if (!cell) {
        result.errors.push('No table cell found.');
        break;
      }
      result.attempts++;
      await cell.click({ button: 'right' });
      await session.page.waitForTimeout(250);
      const visibleMenus = await session.page.locator('.cke_menu_panel:visible, .cke_panel:visible, [role="menu"]:visible')
        .count().catch(() => 0);
      if (visibleMenus > 0) {
        result.contextMenus++;
      }
      recorder.event('table-contextmenu', {
        userId: session.user.id,
        attempt: result.attempts,
        visibleMenus
      });
    } catch (error) {
      result.errors.push(error.message);
      recorder.event('table-action-error', { userId: session.user.id, message: error.message });
    }
    await session.page.waitForTimeout(config.tableActions.pauseMs);
  }
  await session.page.screenshot({ path: path.join(session.userDir, 'after-table-actions.png'), fullPage: true });
  return result;
}

async function findTableCell(page, config) {
  for (const selector of config.selectors.tableCell) {
    const locator = page.locator(selector).first();
    if (await locator.count()) {
      return locator;
    }
  }
  for (const frame of page.frames()) {
    for (const selector of config.selectors.tableCell) {
      const locator = frame.locator(selector).first();
      if (await locator.count()) {
        return locator;
      }
    }
  }
  return null;
}

async function runSave(session, config, recorder) {
  const started = Date.now();
  for (const selector of config.selectors.saveButton) {
    const locator = session.page.locator(selector).first();
    if (await locator.count()) {
      await locator.click();
      await session.page.waitForLoadState('networkidle', { timeout: config.save.timeoutMs }).catch(() => {});
      recorder.event('save-clicked', { userId: session.user.id, selector, durationMs: Date.now() - started });
      return;
    }
  }
  recorder.event('save-button-not-found', { userId: session.user.id });
}

async function verifyMarkers(sessions, typingResults, config, recorder) {
  const allMarkers = typingResults.flatMap(result => result.markers.map(marker => ({
    userId: result.userId,
    marker: marker.marker
  })));
  const checks = [];
  for (const session of sessions) {
    const pageText = await collectVisibleText(session.page);
    const missing = allMarkers.filter(item => !pageText.includes(item.marker));
    checks.push({ userId: session.user.id, missing });
    recorder.event('marker-verification', { userId: session.user.id, missing: missing.length });
  }
  writeJson(path.join(config.artifactsDir, 'marker-verification.json'), checks);
  return checks;
}

async function collectVisibleText(page) {
  const frameTexts = await Promise.all(page.frames().map(frame => frame.locator('body').innerText().catch(() => '')));
  return frameTexts.join('\n');
}

function summarize(config, typingResults, tableResult, verification, events) {
  const typed = typingResults.flatMap(result => result.markers.map(marker => ({ userId: result.userId, ...marker })));
  const missingMarkers = verification.flatMap(check => check.missing.map(item => ({ visibleTo: check.userId, ...item })));
  const severeInputs = typed.filter(item => item.durationMs >= 2000).length;
  const slowInputs = typed.filter(item => item.durationMs >= 500).length;
  return {
    editURL: config.editURL,
    users: config.users.map(user => user.id),
    totalMarkers: typed.length,
    slowInputs,
    severeInputs,
    maxInputDurationMs: typed.reduce((max, item) => Math.max(max, item.durationMs), 0),
    missingMarkers,
    tableResult,
    eventCounts: events.reduce((counts, event) => {
      counts[event.type] = (counts[event.type] || 0) + 1;
      return counts;
    }, {})
  };
}

async function closeSession(session, config, recorder) {
  if (!session) {
    return;
  }
  try {
    await session.page.screenshot({ path: path.join(session.userDir, 'final.png'), fullPage: true }).catch(() => {});
    if (config.trace.enabled) {
      await session.context.tracing.stop({ path: path.join(session.userDir, 'trace.zip') });
    }
  } finally {
    await session.context.close();
    recorder.event('session-closed', { userId: session.user.id });
  }
}

function getArg(name) {
  const index = process.argv.indexOf(name);
  return index >= 0 ? process.argv[index + 1] : null;
}

main().catch(error => {
  console.error(error);
  process.exit(1);
});
