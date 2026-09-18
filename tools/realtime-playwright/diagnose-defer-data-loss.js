/**
 * Does deferring the incoming remote patch (the IME protection) destroy a collaborator's concurrent
 * edit?
 *
 * Why this is the expected failure mode:
 *   chainpad's `contentUpdate(newContent)` computes its patch as `diff(authDoc, newContent)` where
 *   `authDoc` is the latest *committed* document. While A is composing, A's editor DOM lags behind
 *   `authDoc` (B's edit arrived in `authDoc` but has not been applied to A's DOM yet, because we
 *   deferred it). So as soon as something calls `_onLocal()` with that lagging DOM, the computed patch
 *   is "remove B's edit", and that removal is propagated to everyone.
 *
 * Scenario:
 *   1. A and B share one realtime session (control proves it).
 *   2. A starts an IME composition.
 *   3. B types a unique marker.
 *   4. A ends the composition.
 *   5. Check whether B still sees its own marker.
 *
 * If B's marker disappears from B's own editor, the deferral is losing other people's content.
 *
 * Usage: node diagnose-defer-data-loss.js
 */
import { chromium } from 'playwright';

const EDIT_URL = 'http://localhost:8080/xwiki/bin/edit/Sandbox/WebHome?editor=wysiwyg';
const BASE_URL = 'http://localhost:8080/xwiki';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

const insertText = (page, text) => page.evaluate((value) => {
  const editor = Object.values(globalThis.CKEDITOR.instances).find(i => i?.editable?.()?.$);
  editor.focus();
  editor.insertText(value);
  editor.fire('change');
}, text);

const composition = (page, type) => page.evaluate((eventType) => {
  const editor = Object.values(globalThis.CKEDITOR.instances).find(i => i?.editable?.()?.$);
  editor.document.$.dispatchEvent(new CompositionEvent(eventType, { bubbles: true }));
}, type);

const editorText = page => page.evaluate(() => Object.values(globalThis.CKEDITOR.instances)
  .find(i => i?.editable?.()?.$)?.getData() ?? '');

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
  await page.goto(EDIT_URL, { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForSelector('.realtime-edit-toolbar, .realtime-save-status', { timeout: 60000 });
  await page.waitForFunction(() => globalThis.CKEDITOR?.instances
    && Object.values(CKEDITOR.instances).some(i => i?.editable?.()?.$), { timeout: 60000 });
}

async function main() {
  const browser = await chromium.launch({ headless: true, args: ['--no-proxy-server', '--no-sandbox'] });
  const contextA = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const contextB = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const pageA = await contextA.newPage();
  const pageB = await contextB.newPage();

  try {
    await login(pageA);
    await login(pageB);
    await sleep(3000);

    // Control: the two clients really share one session.
    const shared = `SHARED-${Date.now()}`;
    await insertText(pageB, shared);
    await sleep(3000);
    const sharedVisible = (await editorText(pageA)).includes(shared);
    console.log(`control: B's edit visible to A -> ${sharedVisible}`);

    // The experiment.
    const victim = `VICTIM-${Date.now()}`;
    await composition(pageA, 'compositionstart');
    await sleep(300);
    await insertText(pageB, victim);
    await sleep(3000);

    const aSeesDuring = (await editorText(pageA)).includes(victim);
    const bSeesBefore = (await editorText(pageB)).includes(victim);
    console.log(`while A composes: B's marker in A -> ${aSeesDuring} ; still in B -> ${bSeesBefore}`);

    // A finishes composing (this triggers the flush).
    await insertText(pageA, 'A本地输入');
    await composition(pageA, 'compositionend');
    await sleep(4000);

    const aSeesAfter = (await editorText(pageA)).includes(victim);
    const bSeesAfter = (await editorText(pageB)).includes(victim);
    const bSeesSharedAfter = (await editorText(pageB)).includes(shared);
    const aSeesOwn = (await editorText(pageA)).includes('A本地输入');

    console.log('\n=========== RESULT ===========');
    console.log(JSON.stringify({
      victimMarker: victim,
      bMarkerVisibleToA_afterCompositionEnd: aSeesAfter,
      bMarkerStillVisibleToB: bSeesAfter,
      earlierSharedEditSurvives: bSeesSharedAfter,
      aLocalTextPreserved: aSeesOwn,
      verdict: bSeesAfter
        ? 'no loss of the collaborator edit in this run'
        : 'LOST: the collaborator edit was destroyed by the deferred-patch flush'
    }, null, 2));
  } finally {
    await browser.close();
  }
}

main().catch(error => {
  console.error('FATAL:', error);
  process.exit(1);
});
