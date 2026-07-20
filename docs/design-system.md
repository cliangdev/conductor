# Conductor Design System

The visual and interaction system for `conductor-frontend`. Both themes are first-class: **every token ships a light and a dark value**, and no surface may hardcode palette colors. Direction and rendered mockups live in the design proposal (July 2026); this doc is the implementable spec.

References: Linear (shell, lists, density, theming), GitHub PR review (PRD review flow), Notion (Knowledge Center, documents), Attio (tables, saved views).

## Principles

1. **Tokens or it doesn't ship.** Every color, radius, and space comes from the token set below. No raw Tailwind palette classes (`bg-gray-100`, `text-green-600`, `bg-white`) in app code.
2. **Borders, not shadows.** 1px hairlines separate surfaces; the surface ladder creates depth. Shadows only on true overlays (menus, popovers, modals, toasts).
3. **Chrome is neutral, state is color.** The teal accent marks the one primary action and active nav. All other color belongs to status chips and semantic feedback — never decoration.
4. **Every async edge has a face.** One `Skeleton` for loading, one `EmptyState` pattern, one toast for mutation failures. A mutation that fails always says so and reverts — no silent `catch`.
5. **One way to do each thing.** One `StatusBadge`, one dropdown (`DropdownMenu`), one input, one `timeAgo` helper, one icon set (lucide). No emoji/text glyphs (`▼ ✅ ▶ ×`) as UI chrome (sole exception: agent identity emoji via `AgentAvatar` — see "Avatar identity tokens"); no native `confirm()`.

## Tokens

Defined as CSS variables in `globals.css`, mapped in `tailwind.config.ts`. Values below are the target palette (hex shown for clarity; store as HSL to match the existing setup).

### Neutrals & accent

| Token | Light | Dark | Role |
|---|---|---|---|
| `canvas` | `#fbfbfc` | `#0e1013` | App background |
| `surface` | `#ffffff` | `#14171b` | Cards, rows, panels |
| `surface-2` | `#f4f5f7` | `#1a1e23` | Sidebar, table heads, wells |
| `surface-3` | `#eceef1` | `#22272d` | Hover, active tabs |
| `border` | `#e3e6ea` | `#262b32` | Hairlines everywhere |
| `border-strong` | `#d0d5db` | `#364049` | Inputs, emphasized dividers |
| `text-1` | `#191c20` | `#eceef0` | Primary text |
| `text-2` | `#5a6270` | `#a3abb6` | Secondary text |
| `text-3` | `#8d95a3` | `#6d7581` | Metadata, placeholders |
| `accent` | `#0e8276` | `#3ab5a8` | Primary actions, active nav, focus rings |
| `accent-hover` | `#0b6b61` | `#55c8bb` | Hover state |
| `accent-soft` | `#e4f2f0` | `rgba(58,181,168,.13)` | Selected rows, active nav bg |

### Status ramp

Status color is applied **only** through `StatusBadge` (soft tinted background at ~11% + strong text + leading dot). All seven workflow categories are visually distinct — never collapse them.

| Hue | Light | Dark | Maps to |
|---|---|---|---|
| gray | `#6b7380` | `#9aa3b0` | Draft / Backlog |
| blue | `#2f6fd0` | `#6ea8ff` | In Review / Running |
| amber | `#a06f0a` | `#d9a439` | In Progress / warnings |
| violet | `#7856cf` | `#a687ea` | Code Review |
| teal | `#0e8276` | `#46bfb2` | Approved |
| green | `#178a50` | `#4cc07e` | Done / Succeeded |
| slate | `#9199a5` | `#7d8591` | Closed / Skipped |
| red | `#ce3b3b` | `#e46969` | Failed / destructive |

### Avatar identity tokens

Agents have emoji avatars (`AgentAvatar`): an emoji on a soft tinted circle. The eight background
tokens (`--avatar-{gray,blue,amber,violet,teal,green,rose,slate}-bg`, light + dark in
`globals.css`, exposed as `bg-avatar-*`) are **identity, not status** — decorative, user-chosen,
never read by `StatusBadge` and never used to signal workflow state. Hues echo the status ramp's
families for visual kinship, but the values are separate pastel-lightness vars (and `rose` ≠ the
ramp's `red`). Token names mirror the backend's `AgentAvatarDefaults.COLOR_TOKENS`.

**Emoji exception to principle 5**: emoji are permitted *only* as agent identity content rendered
through `AgentAvatar` (and chosen via `AgentAvatarPicker`). Everywhere else the no-emoji-as-chrome
rule stands — lucide remains the one icon set for UI chrome.

## Typography

Inter (existing `--font-sans`), weights 400/500/600–650 only.

| Size | Weight | Use |
|---|---|---|
| 20px / −0.015em | 650 | Page & document titles |
| 14px / 1.6 | 400 | Long-form reading (PRDs, knowledge pages) |
| 13px | 400–500 | Dense UI chrome — lists, tables, sidebars, forms |
| 11.5px / caps / +0.06em | 600 | Section labels, table headers |
| 12px mono | 400 | Display IDs (`COND-142`), keys, code |

Reading columns cap at ~720px (`max-w-[45rem]`); dense views go full width.

## Spacing & radius

- 4px base grid: 4 / 8 / 16 / 24 / 40 (page gutters).
- Radius: **7px** controls, **10px** cards/panels, **999px** chips. Nothing else (`rounded-xl`/`rounded-2xl` are out).
- List rows: 38px tall, single line; sticky group headers on `surface-2`.

## Required primitives (`src/components/ui`)

Beyond the existing `Button`/`Badge`/`Avatar`/`DropdownMenu`/`Tabs`/`Toast`:

- `Input`, `Textarea`, `Label`, `Select` — kills the copy-pasted input class string.
- `Card` — the one list-container idiom (header + `divide-y` rows).
- `Switch` — replaces hand-rolled green/gray div toggles.
- `Skeleton`, `EmptyState`, `Alert`, `Tooltip` — one treatment each for loading / empty / warning states.
- `StatusBadge` — the **only** source of status color; replaces every local `STATUS_COLORS` map.
- Shared `timeAgo`/`formatDuration` in `lib/format.ts` — no per-file copies.

## Audience layers (IA)

Before adding chrome to any surface, sort every element into one of three layers — they have very
different audiences and visit frequencies, and each gets its own home:

1. **Do / read** — the daily-work layer (everyone, daily). This *is* the page. Content and the one
   primary action only.
2. **Trust / health** — "is the system keeping up?" (anyone, occasionally). At most **one quiet
   indicator** on the daily surface, linking to a dedicated detail page (e.g. Knowledge's rail
   health chip → Activity).
3. **Configure** — registries, credentials, provisioning, agent assignment (admins, rarely).
   Behind one admin-only entry (e.g. Knowledge → Manage), never inline on the daily surface.

Rules: a lower layer never leaks upward beyond its single indicator; nav entries a role can't use
are hidden, not disabled; system vocabulary is translated to human words at the UI boundary
(Waiting / Filing / Filed / Needs attention — never `DEAD`, `dispatch`, or schema jargon). The
Knowledge Center is the reference implementation (July 2026 redesign).

## Page chrome patterns

- **Work Items are authored by agents** (Conductor skill / MCP tools), not in the UI — intentionally no create/edit forms. The UI is the review, triage, and approval surface; its primary actions are review verdicts, status transitions, and assignment.
- **Every route** uses `PageContainer` + `PageHeader` (breadcrumb · title · status chip · actions). Detail pages included — no full-bleed exceptions; no duplicate breadcrumb-plus-H1 stacks. Exception: the Knowledge page detail view (`knowledge/page/page.tsx`) deliberately uses a Notion-style icon+title header instead of `PageHeader` — approved, not a review finding.
- **Shell:** sidebar owns workspace switcher, search/⌘K, nav sections, and the single user menu. No top navbar.
- **List views:** grouped single-line rows (status ring · mono ID · title · type tag · right-aligned meta), removable filter pills, explicit sort.
- **Detail views:** reading column + right properties panel (status, assignee, reviewers with per-person verdict icons, links). Review uses the batch model: pending comments → one verdict (Approve / Request changes / Comment).
- **Editors:** Monaco theme follows `next-themes` (`vs` / `vs-dark`) — never hardcoded.

## Anti-patterns (reject in review)

- Raw Tailwind palette classes or `bg-white`/`text-white` in app code
- A second implementation of anything in "Required primitives"
- Emoji or text glyphs as icons; native `confirm()`
- Silent `catch` on mutations
- Dark-only or light-only styling (e.g. hardcoded `zinc-900` panels, `vs-dark` Monaco)
- Interpolated Tailwind class names (`w-${size}`) — they break without a safelist
