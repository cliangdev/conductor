import { describe, it, expect } from 'vitest';
import {
  slideTextFromAltText,
  isDetailSlide,
  readingDuration,
  paceSlides,
  pacingWarnings,
} from '../lib/slideshow-pacing.js';

describe('slideTextFromAltText', () => {
  it('reads quoted lines separated by slashes', () => {
    expect(slideTextFromAltText('Photo of a turkey. Text: "Thanksgiving in 20 min" / "No stress"')).toEqual({
      text: 'Thanksgiving in 20 min\nNo stress',
      words: 6,
      lines: 2,
    });
  });
  it('reads unquoted text with newlines or slashes', () => {
    expect(slideTextFromAltText('A table. Text: Pick a recipe\nCook it')?.lines).toBe(2);
    expect(slideTextFromAltText('A table. text: Pick a recipe / Cook it')?.words).toBe(5);
  });
  it('returns null without a Text part or without words', () => {
    expect(slideTextFromAltText('Photo of a turkey')).toBeNull();
    expect(slideTextFromAltText(undefined)).toBeNull();
    expect(slideTextFromAltText('Photo. Text: "—"')).toBeNull();
  });
  it('ignores tokens with no letter or digit', () => {
    expect(slideTextFromAltText('Photo. Text: "Dinner - ready"')?.words).toBe(2);
  });
});

describe('isDetailSlide', () => {
  it('flags screens, charts and maps on word boundaries', () => {
    expect(isDetailSlide('Screenshot of the app')).toBe(true);
    expect(isDetailSlide('A bar chart of savings')).toBe(true);
    expect(isDetailSlide('Phone screen showing a recipe')).toBe(true);
    expect(isDetailSlide('A UI mockup')).toBe(true);
    expect(isDetailSlide('A bouquet of tulips')).toBe(false);
    expect(isDetailSlide('A turkey on a plate. Text: "Open the app screen"')).toBe(false);
    expect(isDetailSlide(undefined)).toBe(false);
  });
});

describe('readingDuration', () => {
  it('clamps and adds the first and detail bonuses', () => {
    expect(readingDuration({ words: 0 })).toBe(3);
    expect(readingDuration({ words: 4 })).toBe(3);
    expect(readingDuration({ words: 10 })).toBe(3.8);
    expect(readingDuration({ words: 40 })).toBe(4.8);
    expect(readingDuration({ words: 10, first: true })).toBe(4.3);
    expect(readingDuration({ words: 10, first: true, detail: true })).toBe(4.8);
  });
});

describe('paceSlides', () => {
  it('uses alt text first, then OCR words, then none, and subtracts the cross-fades', () => {
    const r = paceSlides([
      { altText: 'Photo. Text: "One two three four five six"' },
      { words: 12 },
      {},
    ]);
    expect(r.basis).toEqual(['altText', 'ocr', 'none']);
    expect(r.slideDurations).toEqual([3.7, 4.1, 3]);
    const sum = r.slideDurations.reduce((a, b) => a + b, 0);
    expect(r.totalSeconds).toBeCloseTo(sum - 1, 5);
  });
  it('gives Rexipe-style decks of 4-6 slides a 12-20 s total', () => {
    const slide = (t: string, extra = '') => ({ altText: `Photo of dinner${extra}. Text: ${t}` });
    for (const n of [4, 5, 6]) {
      const slides = [
        slide('"Dinner in 20 minutes" / "no stress"'),
        slide('"Pick a recipe your family loves"'),
        slide('"Rexipe plans the week"', ' on an app screen'),
        slide('"Shop the list in one tap" / "done"'),
        slide('"Cook it"'),
        slide('"Try Rexipe free today"'),
      ].slice(0, n);
      const { totalSeconds } = paceSlides(slides);
      expect(totalSeconds).toBeGreaterThanOrEqual(12);
      expect(totalSeconds).toBeLessThanOrEqual(20);
    }
  });
});

describe('pacingWarnings', () => {
  it('warns on too many words or two lines in under 3 s, and names the slide', () => {
    const slides = [
      { altText: 'Photo. Text: "a b c d e f g h i j k l"' },
      { altText: 'Photo. Text: "short" / "two lines"' },
      { altText: 'Photo. Text: "fine here"' },
    ];
    const w = pacingWarnings([2, 2.5, 3], slides);
    expect(w).toHaveLength(2);
    expect(w[0]).toContain('Slide 1');
    expect(w[0]).toContain('12 words');
    expect(w[0]).toContain('2 s');
    expect(w[0]).toContain('4.6 s');
    expect(w[1]).toContain('Slide 2');
  });
  it('is quiet for well-paced slides', () => {
    const slides = [{ altText: 'Photo. Text: "Hello there"' }];
    expect(pacingWarnings([3.5], slides)).toEqual([]);
  });
});
