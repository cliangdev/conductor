# HTML documents

Work Item documents can be standalone HTML pages: PRDs written by `/conductor:prd`, claude.ai artifacts imported through Claude Code, or any `.html` file uploaded in the web app. They render in the document pane, can be reviewed with text-selection comments, and are readable by agents.

## Rendering sandbox

The web app renders a document in an `<iframe srcDoc>` with `sandbox="allow-scripts allow-popups allow-popups-to-escape-sandbox"`. There is no `allow-same-origin`, so the page's scripts run in an opaque origin with no access to the app's cookies or storage. Before rendering, the app prepends this policy as a `<meta http-equiv="Content-Security-Policy">` (first element of `<head>`):

```
default-src 'none';
script-src 'unsafe-inline' https://cdnjs.cloudflare.com https://cdn.jsdelivr.net https://unpkg.com https://cdn.tailwindcss.com https://code.jquery.com;
style-src 'unsafe-inline' https://fonts.googleapis.com https://cdn.jsdelivr.net https://unpkg.com;
font-src https://fonts.gstatic.com data:;
img-src data: blob: https:; media-src data: blob: https:;
connect-src 'none'; form-action 'none'; base-uri 'none'
```

Stored HTML is served with `Content-Disposition: attachment`, so a signed storage URL downloads the file instead of rendering it.

## Comment bridge

A bridge script is added to the document at render time (never stored). It runs inside the frame after the page's own scripts and talks to the app with `postMessage`:

| Direction | Message | Purpose |
|---|---|---|
| frame to app | `selection {exact,prefix,suffix,sectionId,sectionTitle,rect}` | Show the floating "Comment" button at `rect`. |
| app to frame | `anchors [{id,exact,prefix,suffix}]` | Find each quote and highlight it (CSS Custom Highlight API, no DOM changes). |
| frame to app | `anchored [{id,found,top}]` | Order threads by position; list quotes not found as Unplaced. |
| frame to app | `open {id}` | A highlight was clicked; focus its thread. |
| app to frame | `scrollTo {id}` | A thread was clicked; scroll to and flash its highlight. |

The page's own scripts can send fake messages, so messages only drive UI. The app checks `event.source === iframe.contentWindow`, validates every message's shape, and always takes the comment body from its own composer.

## Anchors and `outdated`

Comments use the W3C Web Annotation TextQuoteSelector model. A comment on an HTML document carries:

```
anchor: { exact, prefix?, suffix?, sectionId?, sectionTitle? } | null
outdated: boolean
```

- `exact` is the selected text; `prefix`/`suffix` are about 32 characters either side, used to pick the right match when the quote repeats. `sectionId`/`sectionTitle` name the nearest ancestor with an `id` and its heading. `quotedText` equals `anchor.exact`.
- The bridge flattens visible text (skipping `script`, `style`, `noscript`, collapsing whitespace) and re-runs when the DOM changes, so text that scripts add after load can be anchored.
- `outdated` is server-side: the comment's stored `document_hash` differs from the document's current `content_hash`. If either hash is missing (older comments), `outdated` is `false`.
- Whether a quote still exists is decided by the client, because only the client can run the page. Quotes it cannot find are listed under **Unplaced** with their quoted text.
- Line comments on markdown documents are unchanged (`lineNumber`, `anchor: null`). If a document switches between markdown and HTML, its old line comments appear as Unplaced in the HTML viewer.
- Agents read these through `list_work_item_comments`: search the document for the quote, or jump to the section id.

## The HTML PRD contract (`prd.html`, schema v2)

The same content rules as the markdown PRD, expressed with attributes. `conductor lint` (`validatePrdHtml`) enforces it and prefers `prd.html` over `prd.md`.

- `<title>` and `<meta name="conductor:doc-type" content="prd">`, `<meta name="conductor:schema-version" content="2">`, `<meta name="conductor:issue-id" content="{issueId}">`.
- Seven `<section id="…" data-prd-section="…">` elements, each with an `<h2>`, in this order: `problem`, `glossary`, `goals`, `non-goals`, `users`, `reference-patterns`, `features`. Optional after them: `out-of-scope`, `open-questions`, `supporting-documents`.
- `glossary` and `reference-patterns` hold real entries or exactly `<p><em>None.</em></p>` (lint also accepts `_None._`).
- Each feature is `<article id="P0-1" data-feature="P0-1">` with an `<h3>`, a `<dl>` whose `<dt>`s are What, Not, Inputs, Outputs, Invariants, and a `<ul data-acs>` of `<li data-ac="AC-P0-1.1">`. P0 features need all of them; P1 features may omit Not, Inputs, Outputs and Invariants.
- AC ids match `^AC-P[01]-\d+\.\d+$`, are unique, and carry their feature's id (`AC-P0-3.2` only inside `P0-3`). `tasks.json` `covers_acs` is cross-checked against them.
- Self-contained: inline `<style>`/`<script>`, Google Fonts, and pinned CDN scripts only from cdnjs.cloudflare.com, cdn.jsdelivr.net or unpkg.com. Lint warns on `fetch`/XHR, `window.claude` and other script hosts.

## Importing a claude.ai artifact

`/conductor:prd import <claude.ai artifact URL or .html path>` runs in Claude Code, using the user's own access. The backend cannot fetch private claude.ai pages, so there is no server-side import.

1. The Artifact tool reads the page. For someone else's artifact it returns only a summary, so the command stops and asks for the `.html` file. Declared runtime capabilities (live data, Claude calls) trigger a warning: they will not work in Conductor.
2. A page that already meets the contract is kept verbatim, apart from setting `conductor:issue-id`. Otherwise the user chooses: convert it to the PRD contract (content and styling kept), attach it as-is as a supporting document of a new or existing Work Item, or cancel.
3. A PRD is saved with the usual sequence: `create_work_item`, `scaffold_document({filename: "prd.html"})`, Write, transition to IN_REVIEW.

claude.ai artifact comments are not synced; once imported, review happens on the Work Item.

## Upload in the web app

The document tab bar has an "Upload document" action for members who can write documents. It accepts `.html`, `.htm` and `.md` (up to 5 MB), reads the file as text, and PUTs it to `/documents/{filename}` with the content type from the extension. It asks before overwriting a document with the same filename. The CLI and daemon derive the content type the same way (`contentTypeFor` in `conductor-tools/src/lib/content-type.ts`), so saving `mockup.html` is stored as `text/html`.

## Limits

- No network from the page (`connect-src 'none'`): no `fetch`, XHR or WebSocket. Pages that load their data at runtime will render empty.
- No `window.claude` runtime: artifact capabilities (live data, shared state, asking Claude) are unavailable.
- `./other.md` links inside an HTML document do not open document tabs; only the markdown viewer does that. `/conductor:prd` still writes them in the Supporting Documents section, with the filename shown as text. `#hash` links scroll inside the frame and `http(s)` links open in a new tab.
- Highlights need the CSS Custom Highlight API; without it, comments still work but are not painted.
