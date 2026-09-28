/* copy-rules.test.mjs — checkCopyRules, checkAccentPhrase, accentPhraseCount,
 * checkCreativeCopy, against a neutral fixture kit (no Rexipe/nexus copy).
 * Ports the relevant cases from nexus-marketing's core.test.mjs copyErrors/
 * validate coverage onto the data-driven copyRules shape.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { checkCopyRules, checkAccentPhrase, accentPhraseCount, checkCreativeCopy } from '../copy-rules.js';

/* A neutral fixture kit: nothing about any real brand. One plain rule (no
 * exclamation marks), and one rule with an exceptPattern carve-out, the same
 * shape nexus-marketing used for its one verbatim-quotable button name. */
const KIT = {
  accentPhraseRequired: true,
  copyRules: [
    {
      id: 'no-exclamation',
      pattern: '!',
      message: 'No exclamation marks.',
      fields: ['headline', 'body'],
    },
    {
      id: 'no-drop-everything',
      pattern: 'drop everything',
      flags: 'i',
      message: 'Say "act now", not "drop everything".',
      fields: ['headline', 'body', 'caption'],
      exceptPattern: 'Drop Everything and Save Button',
    },
  ],
};

/* ── checkCopyRules: exclamation-mark rule ─────────────────────────────── */

test('checkCopyRules: exclamation mark triggers the rule, a full stop does not', () => {
  const hit = checkCopyRules(KIT.copyRules, { headline: 'Dinner is ready!' });
  assert.equal(hit.length, 1);
  assert.equal(hit[0].id, 'no-exclamation');
  assert.equal(hit[0].field, 'headline');
  assert.equal(hit[0].message, 'No exclamation marks.');

  const clean = checkCopyRules(KIT.copyRules, { headline: 'Dinner is ready.' });
  assert.equal(clean.length, 0);
});

test('checkCopyRules: only the fields a rule lists are checked', () => {
  // "no-exclamation" lists headline and body, not caption: an exclamation in
  // caption alone must not trigger it.
  const errs = checkCopyRules(KIT.copyRules, { caption: 'Great deal!' });
  assert.equal(errs.filter((e) => e.id === 'no-exclamation').length, 0);
});

/* ── checkCopyRules: exceptPattern carve-out ────────────────────────────── */

test('checkCopyRules: exceptPattern text is stripped before the pattern is tested', () => {
  const bare = checkCopyRules(KIT.copyRules, { body: 'Just drop everything and go.' });
  assert.ok(bare.some((e) => e.id === 'no-drop-everything'));

  const exempt = checkCopyRules(KIT.copyRules, { body: 'Tap Drop Everything and Save Button to continue.' });
  assert.equal(exempt.filter((e) => e.id === 'no-drop-everything').length, 0);
});

test('checkCopyRules: the exempt phrase does not shield the rest of the text', () => {
  // Stripping only the exact exempt phrase, not the whole field, so a second,
  // un-exempted occurrence still triggers.
  const errs = checkCopyRules(KIT.copyRules, {
    body: 'Tap Drop Everything and Save Button, or just drop everything now.',
  });
  assert.ok(errs.some((e) => e.id === 'no-drop-everything'));
});

/* ── accentPhraseCount / checkAccentPhrase ──────────────────────────────── */

test('accentPhraseCount: counts complete *phrase* pairs', () => {
  assert.equal(accentPhraseCount('No accent phrase at all.'), 0);
  assert.equal(accentPhraseCount('One *thing* here.'), 1);
  assert.equal(accentPhraseCount('*One* thing and *two* things.'), 2);
});

test('checkAccentPhrase: required and missing fails, required and present passes', () => {
  const missing = checkAccentPhrase('No accent phrase at all.', true);
  assert.equal(missing.length, 1);
  assert.equal(missing[0].id, 'accent-phrase');
  assert.equal(missing[0].field, 'headline');

  const two = checkAccentPhrase('*One* thing and *two* things.', true);
  assert.equal(two.length, 1);

  const ok = checkAccentPhrase('One *thing* here.', true);
  assert.equal(ok.length, 0);
});

test('checkAccentPhrase: not required means no check at all', () => {
  assert.deepEqual(checkAccentPhrase('No accent phrase at all.', false), []);
  assert.deepEqual(checkAccentPhrase('No accent phrase at all.', undefined), []);
});

/* ── checkCreativeCopy: the combined save-time check ────────────────────── */

test('checkCreativeCopy: combines the accent-phrase rule and the kit\'s copyRules', () => {
  const errs = checkCreativeCopy(KIT, { headline: 'Dinner is ready!', body: 'Drop everything now.' });
  const ids = errs.map((e) => e.id).sort();
  assert.deepEqual(ids, ['accent-phrase', 'no-drop-everything', 'no-exclamation']);
});

test('checkCreativeCopy: a clean, on-brand headline and body pass with no errors', () => {
  const errs = checkCreativeCopy(KIT, { headline: 'Plan the week in *one sentence*.', body: 'A calm plan for busy nights.' });
  assert.deepEqual(errs, []);
});

test('checkCreativeCopy: a kit with no rules and no accent requirement never fails', () => {
  const errs = checkCreativeCopy({}, { headline: 'Anything at all!', body: 'Drop everything!!!' });
  assert.deepEqual(errs, []);
});
