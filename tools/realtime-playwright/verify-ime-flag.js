/**
 * Focused verification of the fork's IME (composition) patch in the realtime WYSIWYG editor.
 *
 * Claim under test (wysiwygEditor.js:706-716):
 *   `_onLocal()` returns immediately while `this._isComposing` is true, and `_onLocal()` is the ONLY
 *   caller of `saver.contentModifiedLocally()`. So while a composition is "active" according to the
 *   flag, local edits are neither propagated to collaborators nor marked dirty -> auto-save never runs.
 *
 * The flag is set true ONLY on `compositionstart` and false ONLY on `compositionend` (or session
 * teardown). There is no blur / focusout / timeout / visibility reset.
 *
 * A real browser is driven and composition events are injected exactly as the browser fires them.
 * Whether local content actually reaches ChainPad is observed through instrumentation that already
 * ships in the deployed bundle: `window.REALTIME_DEBUG.logs` captures every `console.debug(...)`,
 * including `"Push local content: <hyperJSON>"`.
 *
 * The decisive part is steps 3-4: the ONLY difference between "edit is lost" and "edit is delivered"
 * is whether a `compositionend` event arrives.
 *
 * Usage: node verify-ime-flag.js [--url <editURL>] [--headful]
 */
import { chromium } from 'playwright';

const args = process.argv.slice(2);
const headful = args.includes('--headful');
// Which document to inject composition events on:
//   iframe (default) = the editor's own editable, i.e. where the user actually composes Chinese text
//   main             = anywhere else on the page (page title, edit summary, macro/link dialog inputs)
const targetIndex = args.indexOf('--target');
const COMPOSITION_TARGET = targetIndex >= 0 ? args[targetIndex + 1] : 'iframe';
const urlIndex = args.indexOf('--url');
const EDIT_URL = urlIndex >= 0
  ? args[urlIndex + 1]
  : 'http://localhost:8080/xwiki/bin/edit/Sandbox/WebHome?editor=wysiwyg';
const BASE_URL = new URL(EDIT_URL).origin + '/xwiki';
const USERNAME = 'admin';
const PASSWORD = 'admin';

const results = [];
function record(step, ok, detail) {
  results.push({ step, ok, ...detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${step}\n      ${JSON.stringify(detail)}`);
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

/** All "Push local content" entries captured by the upstream REALTIME_DEBUG instrumentation. */
function readPushes(page) {
  return page.evaluate(() => {
    const logs = globalThis.REALTIME_DEBUG?.logs ?? [];
    return logs
      .filter(entry => typeof entry[1] === 'string' && entry[1].startsWith('Push local content'))
      .map(entry => entry[1]);
  });
}

/**
 * Inject a composition event on the editor's own document. In standalone edit mode CKEditor renders
 * the editable inside an iframe, which is where a real IME composition happens.
 */
function dispatchComposition(page, type) {
  return page.evaluate(({ eventType, wantedTarget }) => {
    const editor = Object.values(globalThis.CKEDITOR?.instances ?? {})
      .find(instance => instance?.document?.$);
    const iframeDocument = editor?.document?.$;
    const target = (wantedTarget === 'main' || !iframeDocument) ? globalThis.document : iframeDocument;
    target.dispatchEvent(new CompositionEvent(eventType, { bubbles: true, composed: true }));
    return target === globalThis.document ? 'main-document' : 'editor-iframe-document';
  }, { eventType: type, wantedTarget: COMPOSITION_TARGET });
}

/** Insert text through CKEditor and make sure the change notification reaches the realtime plugin. */
function insertText(page, text) {
  return page.evaluate((value) => {
    const editor = Object.values(globalThis.CKEDITOR?.instances ?? {})
      .find(instance => instance?.editable?.()?.$);
    if (!editor) {
      throw new Error('No CKEditor instance with an editable found.');
    }
    editor.focus();
    editor.insertText(value);
    // Ensure the editor reports the change (the realtime plugin listens to `onChange`).
    editor.fire('change');
    return editor.name;
  }, text);
}

function readSaveStatus(page) {
  return page.evaluate(() => {
    const element = document.querySelector('.realtime-save-status');
    return element ? element.textContent.trim().replace(/\s+/g, ' ') : null;
  });
}

/** Simulate the tab becoming hidden, which is what triggers the XWIKI-23141 flush safety net. */
function simulateTabHidden(page) {
  return page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { get: () => 'hidden', configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
    return document.visibilityState;
  });
}

async function main() {
  const browser = await chromium.launch({
    headless: !headful,
    args: ['--no-proxy-server', '--no-sandbox']
  });
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();

  page.on('pageerror', error => console.log('  [pageerror]', error.message.slice(0, 300)));
  page.on('console', message => {
    if (message.type() === 'error') {
      console.log('  [console.error]', message.text().slice(0, 200));
    }
  });

  try {
    // ---- Login -------------------------------------------------------------------------------
    await page.goto(`${BASE_URL}/bin/login/XWiki/XWikiLogin`, { waitUntil: 'domcontentloaded' });
    await page.fill('input[name="j_username"]', USERNAME);
    await page.fill('input[name="j_password"]', PASSWORD);
    await Promise.all([
      page.waitForLoadState('networkidle').catch(() => {}),
      page.click('#loginForm input[type="submit"], #loginForm button[type="submit"]')
    ]);

    // ---- Open the realtime WYSIWYG editor -----------------------------------------------------
    await page.goto(EDIT_URL, { waitUntil: 'domcontentloaded' });
    await page.waitForSelector(
      '.realtime-edit-toolbar, .realtime-save-status, .realtime-toolbar [value="clean"], .realtime-user',
      { timeout: 60000 }
    );
    await page.waitForFunction(() => globalThis.CKEDITOR?.instances
      && Object.values(CKEDITOR.instances).some(i => i?.editable?.()?.$), { timeout: 60000 });
    await sleep(1500);

    const where = await dispatchComposition(page, 'compositionstart');
    await dispatchComposition(page, 'compositionend');
    await sleep(500);
    console.log(`composition events target: ${where}`);
    console.log('save status at start:', await readSaveStatus(page));

    // ---- Step 1: control - a normal local edit IS pushed to ChainPad ---------------------------
    const controlMarker = `CTRLX1-${Date.now()}`;
    await insertText(page, controlMarker);
    await sleep(2000);
    const afterControl = await readPushes(page);
    record('1. control - normal edit reaches ChainPad',
      afterControl.some(p => p.includes(controlMarker)),
      { marker: controlMarker, totalPushes: afterControl.length });

    // ---- Step 2: while a composition is active, the local edit is NOT pushed -------------------
    await dispatchComposition(page, 'compositionstart');
    const imeMarker = `IMEX1-${Date.now()}`;
    await insertText(page, imeMarker);
    await sleep(2000);
    const whileComposing = await readPushes(page);
    const imePushed = whileComposing.some(p => p.includes(imeMarker));
    record('2. composing - local edit is dropped',
      !imePushed,
      { marker: imeMarker, pushed: imePushed, saveStatus: await readSaveStatus(page) });

    // ---- Step 3: compositionend flushes it - proves the flag is the only blocker ---------------
    await dispatchComposition(page, 'compositionend');
    await sleep(1500);
    const afterEnd = await readPushes(page);
    record('3. after compositionend - the same edit IS pushed',
      afterEnd.some(p => p.includes(imeMarker)),
      { marker: imeMarker, totalPushes: afterEnd.length, saveStatus: await readSaveStatus(page) });

    // ---- Step 4: A MISSED compositionend wedges the flag ---------------------------------------
    // Identical to steps 2+3 except that compositionend is never delivered.
    await dispatchComposition(page, 'compositionstart');
    const stuckMarker = `STUCKX1-${Date.now()}`;
    await insertText(page, stuckMarker);
    await sleep(2500);
    const whileStuck = await readPushes(page);
    const stuckPushed = whileStuck.some(p => p.includes(stuckMarker));
    const statusWhileStuck = await readSaveStatus(page);
    record('4. missed compositionend - edit never reaches ChainPad',
      !stuckPushed,
      { marker: stuckMarker, pushed: stuckPushed, saveStatus: statusWhileStuck });

    // ---- Step 5: the safety net for unsaved content is defeated too ----------------------------
    // XWIKI-23141 flushes on tab hide and stores unsaved changes; it funnels through _onLocal().
    await simulateTabHidden(page);
    await sleep(2000);
    const afterFlush = await readPushes(page);
    const stuckPushedAfterFlush = afterFlush.some(p => p.includes(stuckMarker));
    record('5. missed compositionend - flush safety net is defeated',
      !stuckPushedAfterFlush,
      { marker: stuckMarker, pushedAfterFlush: stuckPushedAfterFlush });

    // ---- Step 6: delivering the missing event recovers everything ------------------------------
    await dispatchComposition(page, 'compositionend');
    await sleep(2000);
    const afterRecovery = await readPushes(page);
    record('6. delivering the missing compositionend recovers the edit',
      afterRecovery.some(p => p.includes(stuckMarker)),
      { marker: stuckMarker, totalPushes: afterRecovery.length });

    const failed = results.filter(r => !r.ok);
    console.log('\n================ SUMMARY ================');
    console.log(`${results.length - failed.length}/${results.length} checks passed`);
    for (const failure of failed) {
      console.log('  UNEXPECTED RESULT:', failure.step);
    }
  } finally {
    await browser.close();
  }
}

main().catch(error => {
  console.error('FATAL:', error);
  process.exit(1);
});
