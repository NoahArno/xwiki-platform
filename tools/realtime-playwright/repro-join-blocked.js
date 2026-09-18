/**
 * Reproduces the report: "after 5 users collaborate on a page, other users can no longer enter
 * collaborative editing, not even on other pages — and closing the test scripts fixes it".
 *
 * Method:
 *   1. Open N editor sessions on the SAME page and confirm they are all connected to the realtime
 *      session.
 *   2. With those sessions still OPEN, try to open the realtime editor as an additional user
 *      (a) on the same page and (b) on a different page.
 *   3. Report for each observer whether it reached the realtime session, plus any WebSocket errors
 *      and consoles messages (the reported symptom was `ERR_INVALID_HTTP_RESPONSE` during the
 *      WebSocket handshake).
 *   4. Keep the sessions open so the server-side resource usage can be inspected meanwhile.
 *
 * RESULT (measured on a local 18.1.0 instance, 5 holders): NOT reproduced. With five sessions open on
 * the same page, a sixth client reached the realtime session on the SAME page and a seventh reached it
 * on ANOTHER page. So simply having N collaborators does not block new joiners -- the reported symptom
 * has a different cause. The actual cause of that report was the WebSocket upgrade failing
 * ("Failed to connect to the Netflux WebSocket", loader.js:540), i.e. a transport-layer failure that
 * happens before any of this code runs.
 *
 * Usage: node repro-join-blocked.js [--holders 5] [--hold-seconds 0]
 *   --hold-seconds > 0 keeps the sessions open for that long after the checks (useful to inspect the
 *   server: lsof -p <tomcat-pid> | wc -l).
 */
import { chromium } from 'playwright';

const args = process.argv.slice(2);
const argValue = (name, fallback) => {
  const index = args.indexOf(name);
  return index >= 0 ? Number(args[index + 1]) : fallback;
};
const HOLDERS = argValue('--holders', 5);
const HOLD_SECONDS = argValue('--hold-seconds', 0);

const BASE_URL = 'http://localhost:8080/xwiki';
const PAGE_A = 'Sandbox/WebHome';
const PAGE_B = 'Main/WebHome';
const editUrl = page => `${BASE_URL}/bin/edit/${page}?editor=wysiwyg`;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

async function login(page) {
  await page.goto(`${BASE_URL}/bin/login/XWiki/XWikiLogin`, { waitUntil: 'domcontentloaded' });
  if (await page.locator('input[name="j_username"]').count() > 0) {
    await page.fill('input[name="j_username"]', 'admin');
    await page.fill('input[name="j_password"]', 'admin');
    await Promise.all([
      page.waitForLoadState('networkidle').catch(() => {}),
      page.click('#loginForm input[type="submit"], #loginForm button[type="submit"]')
    ]);
  }
}

/** Open the realtime editor and report whether the realtime session was reached. */
async function openEditor(context, page, label) {
  const result = {
    label,
    page,
    wsOpened: 0,
    wsErrors: [],
    consoleErrors: [],
    realtimeToolbar: false,
    editorReady: false,
    failureText: null
  };
  const p = await context.newPage();
  p.on('websocket', ws => {
    result.wsOpened++;
    ws.on('socketerror', error => result.wsErrors.push(String(error)));
  });
  p.on('console', message => {
    const text = message.text();
    if (message.type() === 'error' && /websocket|netflux|realtime|ERR_/i.test(text)) {
      result.consoleErrors.push(text.slice(0, 200));
    }
  });
  p.on('pageerror', error => result.consoleErrors.push(`pageerror: ${error.message.slice(0, 160)}`));

  try {
    await p.goto(editUrl(page), { waitUntil: 'domcontentloaded' });
    await p.waitForLoadState('networkidle').catch(() => {});
    // Give the realtime toolbar a chance to appear (the smoke test uses 15s).
    result.realtimeToolbar = await p
      .waitForSelector('.realtime-edit-toolbar, .realtime-save-status', { timeout: 15000 })
      .then(() => true)
      .catch(() => false);
    result.editorReady = await p
      .waitForFunction(() => globalThis.CKEDITOR?.instances
        && Object.values(CKEDITOR.instances).some(i => i?.editable?.()?.$), { timeout: 20000 })
      .then(() => true)
      .catch(() => false);
    // Any user-visible warning/error notification?
    result.failureText = await p.evaluate(() => {
      const box = document.querySelector('.xnotification-error, .xnotification-warning, .box.errormessage, .box.warningmessage');
      return box ? box.textContent.trim().replace(/\s+/g, ' ').slice(0, 200) : null;
    });
  } catch (error) {
    result.failureText = `navigation failed: ${error.message.slice(0, 160)}`;
  }
  result.pageHandle = p;
  return result;
}

function report(result) {
  console.log(`  ${result.label.padEnd(34)} toolbar=${String(result.realtimeToolbar).padEnd(5)} ` +
    `editor=${String(result.editorReady).padEnd(5)} ws=${result.wsOpened} ` +
    `wsErrors=${result.wsErrors.length} consoleErrors=${result.consoleErrors.length}`);
  if (result.failureText) {
    console.log(`      notice: ${result.failureText}`);
  }
  for (const error of result.wsErrors.slice(0, 3)) {
    console.log(`      wsError: ${error}`);
  }
  for (const error of result.consoleErrors.slice(0, 3)) {
    console.log(`      console: ${error}`);
  }
}

async function main() {
  const browser = await chromium.launch({ headless: true, args: ['--no-proxy-server', '--no-sandbox'] });
  const keepAlive = [];
  try {
    console.log(`=== 1. opening ${HOLDERS} collaborating sessions on the SAME page (${PAGE_A}) ===`);
    const holders = [];
    for (let i = 0; i < HOLDERS; i++) {
      const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
      await login(await context.newPage());
      const result = await openEditor(context, PAGE_A, `holder-${i + 1}`);
      report(result);
      holders.push({ context, result });
      keepAlive.push(context);
      await sleep(1000);
    }

    const holdersConnected = holders.filter(h => h.result.realtimeToolbar).length;
    console.log(`\n  holders connected to the realtime session: ${holdersConnected}/${HOLDERS}`);

    console.log('\n=== 2. while those sessions are OPEN, try to join ===');
    const observerContextA = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    await login(await observerContextA.newPage());
    const observerSamePage = await openEditor(observerContextA, PAGE_A, 'observer (same page)');
    report(observerSamePage);
    keepAlive.push(observerContextA);

    const observerContextB = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    await login(await observerContextB.newPage());
    const observerOtherPage = await openEditor(observerContextB, PAGE_B, 'observer (other page)');
    report(observerOtherPage);
    keepAlive.push(observerContextB);

    console.log('\n================ RESULT ================');
    console.log(JSON.stringify({
      holdersRequested: HOLDERS,
      holdersConnected,
      observerSamePage: {
        realtimeToolbar: observerSamePage.realtimeToolbar,
        editorReady: observerSamePage.editorReady,
        wsOpened: observerSamePage.wsOpened,
        wsErrors: observerSamePage.wsErrors.slice(0, 2),
        consoleErrors: observerSamePage.consoleErrors.slice(0, 2),
        notice: observerSamePage.failureText
      },
      observerOtherPage: {
        realtimeToolbar: observerOtherPage.realtimeToolbar,
        editorReady: observerOtherPage.editorReady,
        wsOpened: observerOtherPage.wsOpened,
        wsErrors: observerOtherPage.wsErrors.slice(0, 2),
        consoleErrors: observerOtherPage.consoleErrors.slice(0, 2),
        notice: observerOtherPage.failureText
      },
      verdict: (observerSamePage.realtimeToolbar && observerOtherPage.realtimeToolbar)
        ? 'observers could still join - NOT reproduced'
        : 'REPRODUCED: an observer could not reach the realtime session'
    }, null, 2));

    if (HOLD_SECONDS > 0) {
      console.log(`\nholding all sessions open for ${HOLD_SECONDS}s - inspect the server now, e.g.`);
      console.log('  lsof -p <tomcat-pid> | wc -l');
      console.log('  ss -tan | grep :8080 | wc -l   (or: netstat -an | grep 8080 | wc -l)');
      await sleep(HOLD_SECONDS * 1000);
    }
  } finally {
    for (const context of keepAlive) {
      await context.close().catch(() => {});
    }
    await browser.close();
  }
}

main().catch(error => {
  console.error('FATAL:', error);
  process.exit(1);
});
