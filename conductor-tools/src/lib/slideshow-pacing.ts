/**
 * Reading-based slideshow pacing. Each slide is timed from the words on it:
 *   clamp(2.4 + 0.14 x words, 3.0, 4.8) s, +0.5 s for the first slide, +0.5 s for a detail slide
 * (an app screen, chart, screenshot...). The 0.5 s cross-fade comes out of each slide's time, so the
 * video lasts sum(durations) - 0.5 x (n - 1), the same as conductor-creative's slideshowDuration.
 */

export const CROSSFADE_SEC = 0.5;

export interface PacingSlide {
  altText?: string;
  words?: number;
  lines?: number;
}

export type PacingBasis = 'altText' | 'ocr' | 'none';

const round1 = (n: number): number => Math.round(n * 10) / 10;
const round3 = (n: number): number => Math.round(n * 1000) / 1000;

/**
 * The on-slide text from an alt text of the form `Photo of a turkey. Text: "Thanksgiving in 20 min" / "No stress"`.
 * Lines are separated by slashes, pipes, newlines or separate quoted strings. Null when there is no "Text:" part
 * or it holds no words.
 */
export function slideTextFromAltText(alt: string | undefined): { text: string; words: number; lines: number } | null {
  if (!alt) return null;
  const m = /\btext\s*:\s*([\s\S]*)$/i.exec(alt);
  if (!m) return null;
  const raw = m[1];
  const quoted = [...raw.matchAll(/["“]([^"”]*)["”]/g)].map((q) => q[1]);
  const parts = quoted.length > 0 ? quoted : raw.split(/\s*(?:\/|\||\r?\n)\s*/);
  const lines = parts
    .flatMap((p) => p.split(/\s*(?:\/|\||\r?\n)\s*/))
    .map((l) => l.trim())
    .filter((l) => countWords(l) > 0);
  if (lines.length === 0) return null;
  const text = lines.join('\n');
  return { text, words: countWords(text), lines: lines.length };
}

function countWords(s: string): number {
  return s.split(/\s+/).filter((t) => /[\p{L}\p{N}]/u.test(t)).length;
}

const DETAIL_RE = /\b(app screens?|screens?|screenshots?|charts?|graphs?|tables?|maps?|ui|interface|dashboards?)\b/i;

/** True when the alt text mentions something with small detail to read: a screen, screenshot, chart, graph, table, map or UI. */
export function isDetailSlide(alt?: string): boolean {
  if (!alt) return false;
  // Only the picture description counts, not the words printed on it.
  const description = alt.replace(/\btext\s*:[\s\S]*$/i, '');
  return DETAIL_RE.test(description);
}

/** Seconds a slide needs: clamp(2.4 + 0.14 x words, 3.0, 4.8), +0.5 first slide, +0.5 detail slide, to 0.1 s. */
export function readingDuration(opts: { words: number; first?: boolean; detail?: boolean }): number {
  const base = Math.min(4.8, Math.max(3.0, 2.4 + 0.14 * opts.words));
  return round1(base + (opts.first ? 0.5 : 0) + (opts.detail ? 0.5 : 0));
}

function slideFacts(slide: PacingSlide): { words: number; lines: number; basis: PacingBasis } {
  const fromAlt = slideTextFromAltText(slide.altText);
  if (fromAlt) return { words: fromAlt.words, lines: fromAlt.lines, basis: 'altText' };
  if (slide.words != null) return { words: slide.words, lines: slide.lines ?? (slide.words > 0 ? 1 : 0), basis: 'ocr' };
  return { words: 0, lines: 0, basis: 'none' };
}

/** Per-slide durations, the video's total length and where each slide's word count came from. */
export function paceSlides(slides: PacingSlide[]): {
  slideDurations: number[];
  totalSeconds: number;
  basis: PacingBasis[];
} {
  const facts = slides.map(slideFacts);
  const slideDurations = slides.map((s, i) =>
    readingDuration({ words: facts[i].words, first: i === 0, detail: isDetailSlide(s.altText) }),
  );
  return { slideDurations, totalSeconds: totalSeconds(slideDurations), basis: facts.map((f) => f.basis) };
}

/** sum(d) - 0.5 x (n - 1), rounded to 1 ms. */
export function totalSeconds(slideDurations: number[]): number {
  const sum = slideDurations.reduce((a, b) => a + b, 0);
  return round3(sum - CROSSFADE_SEC * Math.max(0, slideDurations.length - 1));
}

/**
 * Plain-English warnings for slides held too briefly: under 0.25 s per word, or under 3 s with 2+ lines.
 * The suggested time is always the reading formula's, which passes both rules. A slide so wordy that even
 * the formula's capped time is under 0.25 s per word gets "too many words" advice instead of a time.
 */
export function pacingWarnings(slideDurations: number[], slides: PacingSlide[]): string[] {
  const warnings: string[] = [];
  slides.forEach((slide, i) => {
    const d = slideDurations[i];
    if (d == null) return;
    const { words, lines } = slideFacts(slide);
    const formula = readingDuration({ words, first: i === 0, detail: isDetailSlide(slide.altText) });
    const perWord = 0.25 * words;
    const tooWordy = perWord > formula;
    const tooFast = d < Math.min(perWord, formula);
    const tooShortForLines = lines >= 2 && d < 3;
    const label = `Slide ${i + 1} has ${words} word${words === 1 ? '' : 's'} on ${d} s`;
    if (tooWordy && d < perWord) {
      warnings.push(`${label}, too many words to read in one slide. Cut some words or split the slide.`);
      return;
    }
    if (!tooFast && !tooShortForLines) return;
    const why = tooFast ? 'under a quarter second per word' : 'under 3 seconds for two or more lines';
    warnings.push(`${label}, ${why}. About ${formula} s would be easier to read.`);
  });
  return warnings;
}
