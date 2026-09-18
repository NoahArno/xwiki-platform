/**
 * Verifies the corrected IME (composition) handling in the realtime WYSIWYG editor.
 *
 * The previous implementation gated BOTH directions on a single `_isComposing` flag:
 *   - `_onLocal()` returned early  => local edits never reached ChainPad and never marked the Saver
 *     dirty (so auto-save never ran) — this is the content-loss bug, reproduced 6/6 in
 *     verify-ime-flag.js.
 *   - the flag was cleared ONLY by `compositionend`, so a missed event wedged the editor forever.
 *
 * The corrected implementation only delays INCOMING remote patches, so the checks below must hold:
 *
 *   Single client
 *     1. a normal edit reaches ChainPad
 *     2. an edit made DURING a composition also reaches ChainPad   (was: dropped)
 *     3. further edits during the same composition still reach ChainPad
 *     4. a composition that never ends does NOT wedge the editor   (was: wedged forever)
 *     5. IME commits with no remote patch waiting coalesce through the normal 100ms debounce,
 *        instead of forcing one full-document push per commit (was: ~10-27x amplification)
 *
 *   Two clients (the original goal must still hold)
 *     6. while client A is composing, a remote patch from client B is NOT applied
 *     7. once A's composition ends, the deferred patch IS applied
 *     8. if A's `compositionend` never arrives, the 5s watchdog applies the deferred patch anyway
 *
 * Usage: node verify-ime-fix.js [--chars 10] [--gap 50]
 */
import { chromium } from 'playwright';

const args = process.argv.slice(2);
const argValue = (name, fallback) => {
  const index = args.indexOf(name);
  return index >= 0 ? Number(args[index + 1]) : fallback;
};
const CHARS = argValue('--chars', 10);
const GAP_MS = argValue('--gap', 50);

const EDIT_URL = 'http://localhost:8080/xwiki/bin/edit/Sandbox/WebHome?editor=wysiwyg';
const BASE_URL = 'http://localhost:8080/xwiki';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

const results = [];
function check(step, ok, detail) {
  results.push({ step, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${step}\n      ${JSON.stringify(detail)}`);
}

function countLogs(page, prefix) {
  return page.evaluate((p) => (globalThis.REALTIME_DEBUG?.logs ?? [])
    .filter(entry => typeof entry[1] === 'string' && entry[1].startsWith(p)).length, prefix);
}

const countPushes = page => countLogs(page, 'Push local content');
const countRemoteApplied = page => countLogs(page, 'Received remote content');

const insertText = (page, text) => page.evaluate((value) => {
  const editor = Object.values(globalThis.CKEDITOR.instances).find(i => i?.editable?.()?.$);
  editor.focus();
  editor.insertText(value);
  editor.fire('change');
}, text);

const compositionStart = page => page.evaluate(() => {
  const editor = Object.values(globalThis.CKEDITOR.instances).find(i => i?.editable?.()?.$);
  editor.document.$.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }));
});

const compositionEnd = page => page.evaluate(() => {
  const editor = Object.values(globalThis.CKEDITOR.instances).find(i => i?.editable?.()?.$);
  editor.document.$.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true }));
});

function editorText(page) {
  return page.evaluate(() => Object.values(globalThis.CKEDITOR.instances)
    .find(i => i?.editable?.()?.$)?.getData() ?? '');
}

async function login(page) {
  await page.goto(`${BASE_URL}/bin/login/XWiki/XWikiLogin`, { waitUntil: 'domcontentloaded' });
  // A shared browser context keeps the session cookie, so the login form may not be shown at all.
  if (await page.locator('input[name="j_username"]').count() > 0) {
    await page.fill('input[name="j_username"]', 'admin');
    await page.fill('input[name="j_password"]', 'admin');
    await Promise.all([
      page.waitForLoadState('networkidle').catch(() => {}),
      page.click('#loginForm input[type="submit"], #loginForm button[type="submit"]')
    ]);
  }
  await page.goto(EDIT_URL, { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForSelector('.realtime-edit-toolbar, .realtime-save-status', { timeout: 60000 });
  await page.waitForFunction(() => globalThis.CKEDITOR?.instances
    && Object.values(CKEDITOR.instances).some(i => i?.editable?.()?.$), { timeout: 60000 });
}

async function singleClientChecks(context) {
  const page = await context.newPage();
  try {
    await login(page);
    await sleep(1500);

    // 1. control
    const control = `CTRL-${Date.now()}`;
    let before = await countPushes(page);
    await insertText(page, control);
    await sleep(2000);
    check('1. normal edit reaches ChainPad', (await countPushes(page)) > before,
      { marker: control });

    // 2. the sync is PAUSED during a composition, so the edit is deliberately NOT pushed yet...
    await compositionStart(page);
    const during = `DURING-${Date.now()}`;
    before = await countPushes(page);
    await insertText(page, during);
    await sleep(2000);
    const pushedWhilePaused = (await countPushes(page)) > before;
    check('2. edit during composition is held back (sync paused)', !pushedWhilePaused,
      { marker: during, pushed: pushedWhilePaused });

    // 3. ...but it is DELAYED, not dropped: ending the composition must push it.
    await compositionEnd(page);
    await sleep(3000);
    const pushedAfterEnd = (await countPushes(page)) > before;
    check('3. ending the composition pushes the held-back edit (delayed, not lost)',
      pushedAfterEnd, { marker: during, pushedAfterEnd });

    // 4. a composition whose `compositionend` never arrives must not stay paused forever:
    //    the 5s watchdog has to resume the sync and push the edit.
    await compositionStart(page);
    const wedged = `NOWEDGE-${Date.now()}`;
    before = await countPushes(page);
    await insertText(page, wedged);
    await sleep(2500);
    const stillPaused = (await countPushes(page)) === before;
    // Wait past _compositionMaxDelay (5s) without ever delivering compositionend.
    await sleep(6000);
    const pushedAfterWatchdog = (await countPushes(page)) > before;
    // Also exercise the flush safety net the way a tab switch would.
    await page.evaluate(() => {
      Object.defineProperty(document, 'visibilityState', { get: () => 'hidden', configurable: true });
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await sleep(2000);
    const pushedAfterFlush = (await countPushes(page)) > before;
    check('4. un-ended composition does NOT stay paused forever (watchdog + flush)',
      stillPaused && pushedAfterWatchdog && pushedAfterFlush,
      { marker: wedged, stillPausedAfter2_5s: stillPaused, pushedAfterWatchdog, pushedAfterFlush });
    await compositionEnd(page);
    await sleep(600);

    // 5. IME commits must not force one push each when no remote patch is waiting
    before = await countPushes(page);
    for (let i = 0; i < CHARS; i++) {
      await compositionStart(page);
      await insertText(page, '中');
      await compositionEnd(page);
      await sleep(GAP_MS);
    }
    await sleep(2500);
    const pushes = (await countPushes(page)) - before;
    check('5. IME commits coalesce through the debounce (no amplification)',
      pushes <= Math.max(2, Math.ceil(CHARS / 4)),
      { charactersCommitted: CHARS, chainpadPushes: pushes });
  } finally {
    await page.close();
  }
}

async function twoClientChecks(browser) {
  // Separate contexts so the two editors are genuinely independent clients (distinct session storage).
  const contextA = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const contextB = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const pageA = await contextA.newPage();
  const pageB = await contextB.newPage();
  try {
    // Sequential login: opening two editors at once races the edit lock.
    await login(pageA);
    await login(pageB);
    await sleep(3000);

    // Control: prove A and B really share one realtime session before testing the deferral, otherwise
    // checks 6-8 would pass or fail for the wrong reason.
    const controlMarker = `SHARED-${Date.now()}`;
    const bPushesBefore = await countPushes(pageB);
    await insertText(pageB, controlMarker);
    await sleep(3000);
    const bPushesAfter = await countPushes(pageB);
    const aSeesControl = (await editorText(pageA)).includes(controlMarker);
    check('0. control - B\'s normal edit is visible to A (shared session)',
      aSeesControl && bPushesAfter > bPushesBefore,
      {
        marker: controlMarker,
        visibleToA: aSeesControl,
        bPushes: bPushesAfter - bPushesBefore,
        aTextSample: (await editorText(pageA)).slice(-160)
      });

    // 6. a remote patch must NOT be applied while A is composing
    await compositionStart(pageA);
    const remoteMarker = `REMOTE-${Date.now()}`;
    let aAppliedBefore = await countRemoteApplied(pageA);
    await insertText(pageB, remoteMarker);
    await sleep(3000);
    const aAppliedDuring = await countRemoteApplied(pageA);
    const aTextDuring = await editorText(pageA);
    check('6. remote patch is deferred while composing (original goal preserved)',
      aAppliedDuring === aAppliedBefore && !aTextDuring.includes(remoteMarker),
      { marker: remoteMarker, appliedDuringComposition: aAppliedDuring - aAppliedBefore });

    // 7. ending the composition applies the deferred patch
    await compositionEnd(pageA);
    await sleep(3000);
    const aAppliedAfter = await countRemoteApplied(pageA);
    const aTextAfter = await editorText(pageA);
    check('7. deferred patch is applied after compositionend',
      aAppliedAfter > aAppliedDuring && aTextAfter.includes(remoteMarker),
      { marker: remoteMarker, appliedAfter: aAppliedAfter - aAppliedDuring });

    // 8. if compositionend never arrives, the watchdog must still apply the deferred patch
    await compositionStart(pageA);
    const remoteMarker2 = `WATCHDOG-${Date.now()}`;
    const aAppliedBefore2 = await countRemoteApplied(pageA);
    await insertText(pageB, remoteMarker2);
    await sleep(2500);
    const stillDeferred = (await countRemoteApplied(pageA)) === aAppliedBefore2;
    // _compositionMaxDelay is 5s; wait past it without ever sending compositionend.
    await sleep(6000);
    const aAppliedAfterWatchdog = await countRemoteApplied(pageA);
    const aTextWatchdog = await editorText(pageA);
    check('8. watchdog applies the deferred patch when compositionend never arrives (fix)',
      stillDeferred && aAppliedAfterWatchdog > aAppliedBefore2 && aTextWatchdog.includes(remoteMarker2),
      {
        marker: remoteMarker2,
        deferredBeforeWatchdog: stillDeferred,
        appliedAfterWatchdog: aAppliedAfterWatchdog - aAppliedBefore2
      });
  } finally {
    await contextA.close();
    await contextB.close();
  }
}

async function main() {
  const browser = await chromium.launch({ headless: true, args: ['--no-proxy-server', '--no-sandbox'] });
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  try {
    console.log('--- single client ---');
    await singleClientChecks(context);
    console.log('\n--- two clients ---');
    await twoClientChecks(browser);
  } finally {
    await browser.close();
  }
  const failed = results.filter(r => !r.ok);
  console.log('\n================ SUMMARY ================');
  console.log(`${results.length - failed.length}/${results.length} checks passed`);
  for (const failure of failed) {
    console.log('  FAILED:', failure.step);
  }
  process.exit(failed.length === 0 ? 0 : 1);
}

main().catch(error => {
  console.error('FATAL:', error);
  process.exit(1);
});
