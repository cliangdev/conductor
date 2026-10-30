# HTML documents

Work Item documents can be standalone HTML pages: PRDs written by `/conductor:prd`, claude.ai artifacts imported through Claude Code, or any `.html` file uploaded in the web app. They render in the document pane, can be reviewed with text-selection comments, and are readable by agents.

A document is **HTML** when its media type is `text/html` (parameters ignored, case-insensitive) or its filename ends in `.html`/`.htm` (case-insensitive). The client and server use the same rule. The CLI and daemon send `text/html` for those filenames and `text/markdown` for everything else (`contentTypeFor` in `conductor-tools/src/lib/content-type.ts`); an explicit `contentType` on `write_document` wins.

## Rendering sandbox

The web app renders a document in an `<iframe srcDoc>` with `sandbox="allow-scripts"` only: no popups, no same-origin. The page's scripts run in an opaque origin with no access to the Conductor app, its cookies, storage or API. The app inserts this policy as a `<meta http-equiv="Content-Security-Policy">` right after the doctype (or at offset 0 when there is none), before any page content:

```
default-src 'none';
script-src 'unsafe-inline' https://cdnjs.cloudflare.com https://cdn.jsdelivr.net https://unpkg.com https://cdn.tailwindcss.com https://code.jquery.com;
style-src 'unsafe-inline' https://fonts.googleapis.com https://cdn.jsdelivr.net https://unpkg.com;
font-src https://fonts.gstatic.com data:;
img-src data: blob: https:; media-src data: blob: https:;
connect-src 'none'; form-action 'none'; base-uri 'none'
```

Stored HTML is served with `Content-Disposition: attachment`, so a signed storage URL downloads the file instead of rendering it.

What this does and does not guarantee:
- The page cannot `fetch`, XHR or open WebSockets, and cannot reach the Conductor app, cookies or API.
- It can still load `https:` images and media and navigate its own frame, so the sandbox is **not a confidentiality boundary for the document's own content**. Do not put secrets in a document that you would not want an embedded script to leak.
- The script host list above is the single written source. Two places must stay in sync with it: `conductor-frontend/src/components/markdown/htmlDocument.ts` (the policy) and the allowlist in `conductor-tools/src/commands/lint.ts`.

## Commenting in the app

The document gets the full width. Selecting text shows a **Comment** button just above the selection; the form opens below it, so the quoted text stays visible. The comment rail appears only once the document has comments: it docks beside the document when there is room (1024px or more), and otherwise floats over the document's right edge, closed until the reader opens it with **Comments · N**. Clicking a highlight opens the rail and focuses that thread. **Full view** (beside **Upload document**) shows the document over the whole window, with the same commenting and, in review mode, the review bar; Escape closes it.

## Comment bridge

A bridge script is added to the document at render time (never stored). It runs inside the frame after the page's own scripts and talks to the app with `postMessage`. The app accepts a message only when `event.source` is the frame's window, `event.origin === 'null'` and `source === 'conductor-bridge'`, and it validates each message's shape. The page's own scripts can send fake messages, so messages only drive UI; the comment body is always typed in the app.

| Direction | Message | Purpose |
|---|---|---|
| frame to app | `ready` | Bridge loaded; the app sends `anchors`. |
| frame to app | `selection {exact,prefix,suffix,sectionId,sectionTitle,rect}` | Show the floating "Comment" button at `rect`. |
| frame to app | `selection-cleared` | Hide the button. |
| app to frame | `anchors [{id,exact,prefix,suffix}]` | Find each quote and highlight it (CSS Custom Highlight API, no DOM changes). |
| frame to app | `anchored [{id,found,top}]` | Order threads by position; list quotes not found as Unplaced. |
| frame to app | `open {id}` | A highlight was clicked; focus its thread. |
| app to frame | `scrollTo {id}` | A thread was clicked; scroll to and flash its highlight. |
| frame to app | `openLink {href}` | An `http(s)`, `mailto:` or `tel:` link was clicked; the app re-parses the URL, allows only those protocols, opens web links with `noopener,noreferrer` and hands `mailto:`/`tel:` to the system. |

`#hash` links scroll inside the frame. Popups from page script (`window.open`) are blocked, so use plain links.

## Anchors and `outdated`

Comments use the W3C Web Annotation TextQuoteSelector model. An HTML comment must carry `anchor`; a markdown comment must carry `lineNumber`; the server returns 400 otherwise. Anchored comments return `lineNumber: 0`, so clients use `anchor != null` to tell them apart.

```
anchor: { exact, prefix?, suffix?, sectionId?, sectionTitle? } | null
outdated: boolean
```

- `exact` is the selected text; `prefix`/`suffix` are about 32 characters either side, used to pick the right match when the quote repeats. `sectionId`/`sectionTitle` name the nearest ancestor with an `id` and its heading. `quotedText` equals `anchor.exact`.
- **Text model.** The bridge flattens visible text nodes (skipping `script`, `style`, `noscript` and hidden elements), collapses whitespace and inserts a virtual space at block-element boundaries. `exact` therefore reads like rendered text (for example "Goals Ship it") and may not match the HTML source byte for byte. Agents should search for it loosely (ignoring tags and whitespace) or jump to `sectionId`.
- Hidden matches are ignored; if only hidden matches exist, the comment is Unplaced. The bridge re-runs when the DOM changes, so text that scripts add after load can be anchored.
- **Outdated.** Document responses expose `contentHash` (SHA-256 of the content). `POST /comments` takes an optional `documentHash`, the version the reviewer saw; review-mode drafts keep the hash they were written against, and old clients that omit it get the current hash. `outdated` is true when the comment's stamped hash differs from the document's current hash, and false when either is unknown (older comments).
- Whether a quote still exists is decided by the client, because only the client can run the page. Quotes it cannot find are listed under **Unplaced** with their quoted text. If a document switches between markdown and HTML, its old line comments appear as Unplaced in the HTML viewer.
- Agents read these through `list_work_item_comments`.

## The HTML PRD contract (`prd.html`, schema v2)

The same content rules as the markdown PRD, expressed with attributes. `conductor lint` (`validatePrdHtml`) enforces it and prefers `prd.html` over `prd.md`.

- `<title>` and `<meta name="conductor:doc-type" content="prd">`, `<meta name="conductor:schema-version" content="2">`, `<meta name="conductor:issue-id" content="{issueId}">`.
- Seven `<section id="…" data-prd-section="…">` elements, each with an `<h2>`, in this order: `problem`, `glossary`, `goals`, `non-goals`, `users`, `reference-patterns`, `features`. Optional after them: `out-of-scope`, `open-questions`, `supporting-documents`.
- `glossary` and `reference-patterns` hold real entries or exactly `<p><em>None.</em></p>` (lint also accepts `_None._`).
- Each feature is `<article id="P0-1" data-feature="P0-1">` with an `<h3>`, a `<dl>` whose `<dt>`s are What, Not, Inputs, Outputs, Invariants, and a `<ul data-acs>` of `<li data-ac="AC-P0-1.1">`. P0 features need all of them; P1 features may omit Not, Inputs, Outputs and Invariants.
- AC ids match `^AC-P[01]-\d+\.\d+$`, are unique, and carry their feature's id (`AC-P0-3.2` only inside `P0-3`). `tasks.json` `covers_acs` is cross-checked against them.
- Self-contained: inline `<style>`/`<script>`, Google Fonts, and pinned scripts only from the five CSP hosts. Lint warns on `fetch`/XHR, `window.claude`, `window.open` and scripts from other hosts. Links are plain `<a href>`: `#hash` scrolls in the frame and `http(s)` opens in a new tab through the app.

## Importing a claude.ai artifact

`/conductor:prd import <claude.ai artifact URL or .html path>` runs in Claude Code, using the user's own access. The backend cannot fetch private claude.ai pages, so there is no server-side import.

1. The Artifact tool reads the page. For someone else's artifact it returns only a summary, so the command stops and asks for the `.html` file. Declared runtime capabilities (live data, Claude calls) trigger a warning: they will not work in Conductor.
2. A page that already meets the contract is kept verbatim, apart from setting `conductor:issue-id`. Otherwise the user chooses: convert it to the PRD contract (content and styling kept), attach it as-is as a supporting document of a new or existing Work Item, or cancel.
3. A PRD is saved with the usual sequence: `create_work_item`, `scaffold_document({filename: "prd.html"})`, Write, transition to IN_REVIEW.

claude.ai artifact comments are not synced; once imported, review happens on the Work Item.

## Upload in the web app

The document tab bar has an "Upload document" action for members who can write documents. It accepts `.html`, `.htm` and `.md` (up to 5 MB), reads the file as text, and PUTs it to `/documents/{filename}` with the content type from the extension. It asks before overwriting a document with the same filename.

## Limits

- No `fetch`, XHR or WebSocket from the page (`connect-src 'none'`). Pages that load their data at runtime will render empty. `https:` images and media still load, and the page can navigate its own frame.
- No `window.claude` runtime: artifact capabilities (live data, shared state, asking Claude) are unavailable.
- No popups. `./other.md` links inside an HTML document do not open document tabs (only the markdown viewer does that); `/conductor:prd` still writes them in the Supporting Documents section, with the filename shown as text.
- Highlights need the CSS Custom Highlight API; without it, comments still work but are not painted.
