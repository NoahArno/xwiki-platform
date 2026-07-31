import assert from 'node:assert/strict';
import { test } from 'node:test';

import { createRandomActionPool } from '../lib/random-editing.js';

test('expands positive random editing action weights into a pick pool', () => {
  assert.deepEqual(createRandomActionPool({
    insertText: 2,
    newline: 1,
    deleteText: 0
  }), ['insertText', 'insertText', 'newline']);
});

test('rejects random editing action weights when no action is enabled', () => {
  assert.throws(() => createRandomActionPool({
    insertText: 0,
    newline: 0
  }), /At least one random editing action must have a positive weight/);
});
