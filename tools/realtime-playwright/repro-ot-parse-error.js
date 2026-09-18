/**
 * A/B experiment: does the fork's debounce bypass cause the ChainPad OT failures that lead to lost
 * content and merge conflicts?
 *
 * Background (all verified by reading deployed code):
 *   - XWiki's realtime WYSIWYG editor syncs the document as a hyperJSON *string* and configures
 *     ChainPad with `patchTransformer: NaiveJSONTransformer`.
 *   - `NaiveJSONTransformer` does a purely TEXT-level OT transform of that JSON, then validates the
 *     result with `JSON.parse(text3)`. If the character-level merge produced invalid JSON it logs
 *     `REALTIME_DEBUG.ot_parseError` and -- critically -- `return []` (an EMPTY patch).
 *     So the local operation is silently dropped => replicas diverge => a later 3-way merge
 *     (`Patches.merge`) throws => "unsaved changes could not be restored" => local edits are
 *     discarded (`_merge()` returns the remote version).
 *   - The fork's IME patch cancels the 100ms debounce on EVERY IME commit and pushes immediately,
 *     which we measured to be ~10x more ChainPad content updates for the same characters.
 *
 * Hypothesis: more concurrent operations => more chances for the text-level OT to produce invalid
 * JSON => more `ot_parseError`. In other words the fork's patch is itself a driver of the corruption.
 *
 * Method: two real browsers edit the same page at the same caret position, typing the SAME number of
 * characters. Only the way the characters are committed differs:
 *   Phase A - plain typing      (debounce intact, ~1 push)
 *   Phase B - IME commits       (debounce bypassed per character, ~N pushes)
 * Then compare the number of `ot_parseError` occurrences.
 *
 * RESULT (measured on a local 18.1.0 instance): the push amplification WAS confirmed (10x-27x), but
 * `ot_parseError` was NOT reproduced in any phase -- 0 occurrences for plain ASCII, for Chinese text,
 * and for JSON punctuation, even under that amplification. So the amplification alone does not
 * demonstrably cause the invalid JSON, and the trigger of `ot_parseError` is still unidentified.
 * Treat the hypothesis below as unproven, not as a conclusion.
 *
 * Usage: node repro-ot-parse-error.js [--chars 40] [--gap 40]
 */
import { chromium } from 'playwright';

const args = process.argv.slice(2);
const argValue = (name, fallback) => {
  const index = args.indexOf(name);
  return index >= 0 ? Number(args[index + 1]) : fallback;
};
const CHARS = argValue('--chars', 40);
const GAP_MS = argValue('--gap', 40);

const EDIT_URL = 'http://localhost:8080/xwiki/bin/edit/Sandbox/WebHome?editor=wysiwyg';
const BASE_URL = 'http://localhost:8080/xwiki';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

async function login(page) {
  await page.goto(`${BASE_URL}/bin/login/XWiki/XWikiLogin`, { waitUntil: 'domcontentloaded' });
  await page.fill('input[name="j_username"]', 'admin');
  await page.fill('input[name="j_password"]', 'admin');
  await Promise.all([
    page.waitForLoadState('networkidle').catch(() => {}),
    page.click('#loginForm input[type="submit"], #loginForm button[type="submit"]')
  ]);
  await page.goto(EDIT_URL, { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForSelector('.realtime-edit-toolbar, .realtime-save-status', { timeout: 60000 });
  await page.waitForFunction(() => globalThis.CKEDITOR?.instances
    && Object.values(CKEDITOR.instances).some(i => i?.editable?.()?.$), { timeout: 60000 });
}

/** Put the caret at the very end so both users type into the same position (worst case for OT). */
const caretToEnd = page => page.evaluate(() => {
  const editor = Object.values(globalThis.CKEDITOR.instances).find(i => i?.editable?.()?.$);
  editor.focus();
  const range = editor.createRange();
  range.moveToElementEditEnd(editor.editable());
  range.select();
});

const insertText = (page, text) => page.evaluate((value) => {
  const editor = Object.values(globalThis.CKEDITOR.instances).find(i => i?.editable?.()?.$);
  editor.focus();
  editor.insertText(value);
  editor.fire('change');
}, text);

const composition = (page, type) => page.evaluate((eventType) => {
  const editor = Object.values(globalThis.CKEDITOR.instances).find(i => i?.editable?.()?.$);
  (editor?.document?.$ ?? globalThis.document)
    .dispatchEvent(new CompositionEvent(eventType, { bubbles: true, composed: true }));
}, type);

/** Read the diagnostics that chainpad itself publishes when the text-level OT produces bad JSON. */
const readDiagnostics = (page) => page.evaluate(() => {
  const debug = globalThis.REALTIME_DEBUG ?? {};
  const summarize = (entry) => entry && ({
    type: entry.type,
    message: entry.error?.message,
    // The invalid JSON that the naive text-level merge produced.
    text3AroundError: (() => {
      const position = Number(/(\d+)/.exec(entry.error?.message ?? '')?.[1] ?? -1);
      if (typeof entry.text3 !== 'string' || position < 0) {
        return null;
      }
      return entry.text3.slice(Math.max(0, position - 60), position + 60);
    })(),
    text1Length: entry.text1?.length,
    text3Length: entry.text3?.length
  });
  return {
    ot_parse_error: summarize(debug.ot_parseError) ?? null,
    ot_apply_error: summarize(debug.ot_applyError) ?? null,
    pushes: (debug.logs ?? [])
      .filter(e => typeof e[1] === 'string' && e[1].startsWith('Push local content')).length
  };
});

async function phasePlain(pages, label) {
  return phaseSequence(pages, label, () => 'a', false);
}

async function phaseIme(pages, label) {
  return phaseSequence(pages, label, () => '中', true);
}

/**
 * Type a sequence spelled with JSON punctuation. `NaiveJSONTransformer` merges the hyperJSON as plain
 * text and then asserts the result still parses, so concurrent edits that place structural characters
 * (`{`, `}`, `[`, `]`, `"`, `,`) at overlapping positions are the most direct way to make the
 * character-level merge produce syntactically invalid JSON.
 */
async function phaseJsonPunctuation(pages, label) {
  const sequence = ['[', '{', '"', 'a', '"', ':', '"', '中', '"', '}', ']', ',', '{', '[', '"', 'b', '"', ']', '}'];
  let index = 0;
  return phaseSequence(pages, label, () => sequence[index++ % sequence.length], true);
}

async function phaseSequence(pages, label, nextChar, useIme) {
  for (const page of pages) {
    await caretToEnd(page);
  }
  const before = await Promise.all(pages.map(readDiagnostics));
  await Promise.all(pages.map(async (page) => {
    for (let i = 0; i < CHARS; i++) {
      const char = nextChar();
      if (useIme) {
        await composition(page, 'compositionstart');
        await insertText(page, char);
        await composition(page, 'compositionend');
      } else {
        await insertText(page, char);
      }
      await sleep(GAP_MS);
    }
  }));
  await sleep(3000);
  const after = await Promise.all(pages.map(readDiagnostics));
  return { label, before, after };
}

function report(phase) {
  const pushes = phase.after.map((d, i) => d.pushes - phase.before[i].pushes);
  const parseErrors = phase.after.filter(d => d.ot_parse_error).length;
  const applyErrors = phase.after.filter(d => d.ot_apply_error).length;
  console.log(`\n--- ${phase.label} ---`);
  console.log(`  ChainPad pushes per client : ${JSON.stringify(pushes)}`);
  console.log(`  clients with ot_parseError : ${parseErrors}/${phase.after.length}`);
  console.log(`  clients with ot_applyError : ${applyErrors}/${phase.after.length}`);
  for (const [index, d] of phase.after.entries()) {
    if (d.ot_parse_error) {
      console.log(`  [client ${index}] parse error: ${d.ot_parse_error.message}`);
      console.log(`  [client ${index}] invalid JSON produced around the error position:`);
      console.log(`      ...${d.ot_parse_error.text3AroundError}...`);
    }
  }
  return { pushes: pushes.reduce((a, b) => a + b, 0), parseErrors, applyErrors };
}

async function main() {
  const browser = await chromium.launch({ headless: true, args: ['--no-proxy-server', '--no-sandbox'] });
  const contexts = await Promise.all([
    browser.newContext({ viewport: { width: 1280, height: 900 } }),
    browser.newContext({ viewport: { width: 1280, height: 900 } })
  ]);
  const pages = await Promise.all(contexts.map(c => c.newPage()));

  try {
    // Sessions must be created SEQUENTIALLY: opening two editors at the same instant makes the
    // second one race the first for the edit lock and it never reaches the realtime editor.
    for (const page of pages) {
      await login(page);
    }
    await sleep(3000);

    const plain = report(await phasePlain(pages, `A. plain typing (${CHARS} chars each, debounce intact)`));
    const ime = report(await phaseIme(pages, `B. IME commits (${CHARS} chars each, debounce bypassed)`));
    const json = report(await phaseJsonPunctuation(pages, `C. IME commits of JSON punctuation (${CHARS} chars each)`));

    console.log('\n================ CONCLUSION ================');
    console.log(JSON.stringify({
      charactersPerClient: CHARS,
      plainTyping: plain,
      imeCommits: ime,
      jsonPunctuation: json,
      pushAmplification: plain.pushes > 0 ? Number((ime.pushes / plain.pushes).toFixed(2)) : null
    }, null, 2));
  } finally {
    await browser.close();
  }
}

main().catch(error => {
  console.error('FATAL:', error);
  process.exit(1);
});
