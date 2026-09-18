/**
 * Measures how much the fork's IME patch amplifies realtime traffic.
 *
 * Upstream coalesces local edits with a 100 ms debounce (`_scheduleLocalChangeHandling`). The fork's
 * `compositionend` handler cancels that timer and calls `_onLocal()` synchronously on EVERY commit:
 *
 *     clearTimeout(this._localContentChangeTimeout);
 *     this._onLocal();
 *
 * For a Chinese user an IME commit happens roughly once per word, so the debounce that used to turn a
 * burst of keystrokes into one ChainPad update is bypassed many times per sentence. Every
 * `contentUpdate` is broadcast to all peers, and each peer pays a full DiffDOM re-render of the whole
 * document in `_onRemote()`. That is a direct multiplier on the per-keystroke work that makes typing
 * lag grow with the number of editors.
 *
 * This script types the same number of characters two ways and counts the ChainPad pushes:
 *   A) plain typing  - debounce intact
 *   B) IME commits   - compositionstart/insertText/compositionend per character
 *
 * Usage: node measure-push-amplification.js [--chars 10] [--gap 50]
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

const pushCount = page => page.evaluate(() => (globalThis.REALTIME_DEBUG?.logs ?? [])
  .filter(entry => typeof entry[1] === 'string' && entry[1].startsWith('Push local content')).length);

const insertText = (page, text) => page.evaluate((value) => {
  const editor = Object.values(globalThis.CKEDITOR?.instances ?? {})
    .find(instance => instance?.editable?.()?.$);
  editor.focus();
  editor.insertText(value);
  editor.fire('change');
}, text);

const composition = (page, type) => page.evaluate((eventType) => {
  const editor = Object.values(globalThis.CKEDITOR?.instances ?? {})
    .find(instance => instance?.document?.$);
  (editor?.document?.$ ?? globalThis.document)
    .dispatchEvent(new CompositionEvent(eventType, { bubbles: true, composed: true }));
}, type);

async function main() {
  const browser = await chromium.launch({ headless: true, args: ['--no-proxy-server', '--no-sandbox'] });
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });

  try {
    await page.goto(`${BASE_URL}/bin/login/XWiki/XWikiLogin`, { waitUntil: 'domcontentloaded' });
    await page.fill('input[name="j_username"]', 'admin');
    await page.fill('input[name="j_password"]', 'admin');
    await Promise.all([
      page.waitForLoadState('networkidle').catch(() => {}),
      page.click('#loginForm input[type="submit"], #loginForm button[type="submit"]')
    ]);
    await page.goto(EDIT_URL, { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('.realtime-edit-toolbar, .realtime-save-status', { timeout: 60000 });
    await page.waitForFunction(() => globalThis.CKEDITOR?.instances
      && Object.values(CKEDITOR.instances).some(i => i?.editable?.()?.$), { timeout: 60000 });
    await sleep(1500);

    // ---- A) plain typing, debounce intact -----------------------------------------------------
    const beforeA = await pushCount(page);
    const startedA = Date.now();
    for (let i = 0; i < CHARS; i++) {
      await insertText(page, 'a');
      await sleep(GAP_MS);
    }
    // Let the debounce fire and settle.
    await sleep(2000);
    const afterA = await pushCount(page);
    const pushesA = afterA - beforeA;

    // ---- B) IME commits, debounce bypassed ----------------------------------------------------
    const beforeB = await pushCount(page);
    const startedB = Date.now();
    for (let i = 0; i < CHARS; i++) {
      await composition(page, 'compositionstart');
      await insertText(page, '中');
      await composition(page, 'compositionend');
      await sleep(GAP_MS);
    }
    await sleep(2000);
    const afterB = await pushCount(page);
    const pushesB = afterB - beforeB;

    console.log(JSON.stringify({
      charactersTyped: CHARS,
      gapMs: GAP_MS,
      plainTyping: { chainpadPushes: pushesA, elapsedMs: Date.now() - startedA },
      imeCommits: { chainpadPushes: pushesB, elapsedMs: Date.now() - startedB },
      amplification: pushesA > 0 ? Number((pushesB / pushesA).toFixed(2)) : null
    }, null, 2));

    // ---- Unblock the flag we left engaged, so the session is not left wedged -------------------
    await composition(page, 'compositionend');
  } finally {
    await browser.close();
  }
}

main().catch(error => {
  console.error('FATAL:', error);
  process.exit(1);
});
