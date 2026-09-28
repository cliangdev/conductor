// layouts/index.js — the layout registry as a JS module: the same data as
// layouts/index.json plus every layouts/<name>/layout.json, assembled into one
// object. Ships alongside the .json files so a bundler can import the
// registry directly; test/registry.test.mjs asserts the two never drift.
//
// A layout is a structural family (where the photo goes, how the panel is
// built, which themes it allows, per-placement overrides) with its look coming
// entirely from the CSS custom properties a brand and a placement set — never
// a brand value baked in here. Adding a layout is a new folder plus an entry
// in both this file and layouts/index.json.
export const layoutNames = ['stacked', 'bleed', 'card', 'split'];

export const layouts = {
  stacked: {
    name: 'stacked',
    description: 'Photo band pinned top over a solid text panel.',
    themes: ['dark'],
    lockupInPanel: true,
    photo: 'band',
    panel: 'plain',
    band: { '9x16': 1080, '4x5': 800 },
    dropBody: ['4x5'],
    perPlacement: {
      '1x1': { layout: 'bleed', panel: 'bottom' },
    },
  },
  bleed: {
    name: 'bleed',
    description: 'Photo fills the artboard, copy sits over a scrim.',
    themes: ['dark'],
    lockupInPanel: true,
    photo: 'background',
    panel: 'fill',
    dropBody: [],
    perPlacement: {
      '1x1': { layout: 'bleed', panel: 'bottom' },
    },
  },
  card: {
    name: 'card',
    description: 'The light layout: inset rounded photo on a light page.',
    themes: ['dark', 'light'],
    lockupInPanel: false,
    photo: 'card',
    panel: 'plain',
    band: { '9x16': 700, '4x5': 520, '1x1': 320 },
    dropBody: [],
  },
  split: {
    name: 'split',
    description: 'Photo on the top half, copy on a solid panel below, no scrim.',
    themes: ['dark'],
    lockupInPanel: true,
    photo: 'band',
    panel: 'plain',
    band: { '9x16': 960, '4x5': 675, '1x1': 540 },
    dropBody: [],
    bandScrim: false,
  },
};

export default layouts;
