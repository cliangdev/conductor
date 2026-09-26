/* copy-rules.js — pure functions checking a creative's copy against a brand
 * kit's rules. No DOM, no fetch: a kit and some text in, a list of errors out.
 *
 * Used two ways: the backend's CreativeValidator ports the same semantics in
 * Java for the save-time gate (source of truth: this file's tests), and the
 * frontend imports this file directly for live feedback as someone types and
 * for the brand settings page's "test a line" box.
 *
 * A brand kit's `copyRules` is data:
 *   [{ id, pattern, flags?, message, fields: ['headline'|'body'|'caption'],
 *      exceptPattern? }]
 * `pattern` and `exceptPattern` are regex source strings (no literal slashes),
 * `flags` defaults to 'i'. Nothing here is Rexipe-specific — the six rules
 * nexus-marketing hardcoded (no em/en dash, no "shopping list", no emoji, no
 * exclamation marks, no free-plan pitch, no price) become one kit's data; a
 * different workspace's kit can carry entirely different rules.
 */

/* Strips 'g' so a single .test() call is stateless (a global regex's lastIndex
 * would otherwise make repeated calls flip between matching and not). */
function stripGlobal(flags) {
  return (flags || '').replace(/g/g, '');
}

/* Checks one field's text against one rule. `exceptPattern` matches are
 * removed from the text BEFORE `pattern` is tested against it — this is what
 * lets a kit forbid a phrase everywhere except one exact, cleared string
 * (nexus-marketing's example: forbid "shopping list" except the verbatim
 * in-app button name "Add All to Shopping List"). */
function stripExcept(text, exceptPattern, flags) {
  if (!exceptPattern) return text;
  const exceptRe = new RegExp(exceptPattern, flags.includes('g') ? flags : flags + 'g');
  return text.replace(exceptRe, '');
}

function textTriggersRule(text, rule) {
  if (!text) return false;
  const flags = stripGlobal(rule.flags || 'i');
  const pattern = new RegExp(rule.pattern, flags);
  const scrubbed = stripExcept(text, rule.exceptPattern, flags);
  return pattern.test(scrubbed);
}

/* Runs every rule in `rules` against `fields` ({ headline?, body?, caption? }).
 * Only the fields a rule lists are checked. Each error carries the rule's own
 * `id` and `message`, plus which field it came from — never a rewritten
 * message, so the kit author's wording is what a user sees. */
export function checkCopyRules(rules, fields) {
  const errors = [];
  for (const rule of rules || []) {
    for (const field of rule.fields || []) {
      const text = fields && fields[field];
      if (textTriggersRule(text, rule)) {
        errors.push({ id: rule.id, field, message: rule.message });
      }
    }
  }
  return errors;
}

/* Counts the accent phrases in a headline: complete `*...*` pairs. A headline
 * with no asterisks or a stray unpaired one counts as 0 for that leftover
 * text; each `*phrase*` pair counts once. */
export function accentPhraseCount(headline) {
  const text = headline || '';
  const pairs = text.match(/\*[^*]*\*/g);
  return pairs ? pairs.length : 0;
}

/* The one structural rule nexus-marketing's design system enforced beyond
 * regex rules: exactly one accent phrase per headline, never zero, never two.
 * A kit opts in with `accentPhraseRequired: true`; a kit that does not care
 * about this convention simply never sets it, and this returns no errors. */
export function checkAccentPhrase(headline, required) {
  if (!required) return [];
  const count = accentPhraseCount(headline);
  if (count === 1) return [];
  return [{
    id: 'accent-phrase',
    field: 'headline',
    message: `headline needs exactly one *accent phrase* (found ${count})`,
  }];
}

/* The combined check a save-time validator (or a live-typing feedback panel)
 * runs: the kit's own copyRules plus the accent-phrase structural rule, in
 * that order. `fields` is `{ headline?, body?, caption? }`; a caller with only
 * a headline to check (e.g. the "test a line" box) can pass just that. */
export function checkCreativeCopy(kit, fields) {
  const k = kit || {};
  return [
    ...checkAccentPhrase(fields && fields.headline, k.accentPhraseRequired),
    ...checkCopyRules(k.copyRules, fields),
  ];
}
