---
name: flux:ux-ui-design
description: Expert UX/UI design guidance for creating simple, user-focused experiences with clean, cohesive interfaces. Use when designing or reviewing UI components, layouts, navigation, forms, or any user-facing interface. Applies human-centered design principles and explicitly avoids AI slop patterns (generic, over-designed, feature-bloated interfaces). Triggers on UI implementation, design decisions, component creation, or layout work.
user-invocable: false
---

# UX/UI Design Skill

Design guidance for creating interfaces that are simple for users and visually clean—not generic AI slop.

## Core Philosophy

**UX**: Remove friction. Every interaction should feel effortless.
**UI**: Less is more. Every element earns its place.

---

## UX Principles

### 1. Reduce Cognitive Load

Users form opinions in 50ms. Simplify ruthlessly.

- **One primary action per screen** — Secondary actions stay subtle
- **Progressive disclosure** — Show basics upfront, reveal complexity on demand
- **Predictable patterns** — Consistency reduces learning curve

```
❌ Dashboard with 12 cards, 5 CTAs, sidebar, and floating action button
✅ Dashboard with 3 key metrics, one primary action, minimal navigation
```

### 2. Design for Intent, Not Features

Start with user goals, not feature lists.

| User Intent | Good Design | Bad Design |
|-------------|-------------|------------|
| "Find a product" | Search bar prominent, filters accessible | Feature tour modal on load |
| "Complete checkout" | Linear flow, minimal fields | Upsells interrupting every step |
| "Check status" | Status visible immediately | Requires 3 clicks to find |

### 3. Respect User Time

- **Minimize steps** — Combine related actions
- **Smart defaults** — Pre-fill what you can infer
- **Instant feedback** — Show loading states, confirm actions immediately
- **Remember state** — Don't make users repeat themselves

### 4. Error Prevention Over Error Messages

Design to prevent errors, not just handle them.

```
❌ "Invalid email format" after submit
✅ Real-time validation as user types

❌ Delete button with no confirmation
✅ Undo within 5 seconds, or confirmation for destructive actions
```

---

## UI Principles

### 1. Visual Hierarchy

Guide attention through deliberate contrast.

**Size**: Larger = more important
**Color**: Accent colors for actions, muted for secondary
**Space**: Whitespace groups related elements, separates sections
**Position**: Top-left starts the reading flow (LTR languages)

```
Primary action:   Bold, accent color, prominent size
Secondary action: Outlined or text-only, subdued
Tertiary action:  Text link, smallest
```

### 2. Limited Color Palette

95% of minimalist interfaces use a restricted palette.

**Core palette structure:**
- 1 neutral background (white, off-white, or dark mode equivalent)
- 1-2 accent colors (one primary, one optional secondary)
- Semantic colors: success (green), warning (amber), error (red), info (blue)
- Text: 2-3 shades (primary, secondary, disabled)

```css
/* Example minimal palette */
--bg-primary: #FFFFFF;
--bg-secondary: #F8F9FA;
--text-primary: #1A1A1A;
--text-secondary: #6B7280;
--accent: #2563EB;
--accent-hover: #1D4ED8;
```

### 3. Typography

One typeface, multiple weights. Never more than two font families.

**Hierarchy through weight and size, not different fonts:**
- Headings: Semibold or bold, larger size
- Body: Regular weight, comfortable reading size (16px minimum)
- Captions: Regular or medium, smaller size, secondary color

**Line height**: 1.4-1.6 for body text, 1.2-1.3 for headings

### 4. Spacing System

Use consistent spacing tokens—never arbitrary values.

```
4px  (xs)  — Tight spacing within components
8px  (sm)  — Related elements
16px (md)  — Standard spacing
24px (lg)  — Section separation
32px (xl)  — Major sections
48px (2xl) — Page-level spacing
```

**Rule**: Space between elements = relationship strength. Closer = more related.

### 5. Whitespace as Design Element

Empty space is not wasted space. It:
- Reduces cognitive load
- Creates visual breathing room
- Emphasizes important elements
- Communicates hierarchy

```
❌ Cramped form with no padding
✅ Generous padding, grouped fields with clear sections
```

---

## Avoiding AI Slop

AI slop = generic, over-engineered, feature-bloated design that looks "designed" but lacks soul.

### Red Flags (Don't Do This)

| Pattern | Problem | Fix |
|---------|---------|-----|
| Gradient everything | Looks like a template | Flat colors, subtle shadows only |
| Rounded corners on everything | Generic "modern" look | Consistent radius, not excessive |
| Unnecessary animations | Distracting, slow | Purposeful micro-interactions only |
| Stock illustrations everywhere | Feels corporate/hollow | Use sparingly or not at all |
| Feature overload | "Look how much it does!" | Ship less, ship focused |
| Symmetric grids always | Predictable, boring | Intentional asymmetry when appropriate |
| Drop shadows on everything | Cluttered, dated | Minimal elevation, purposeful depth |
| Glassmorphism/neumorphism by default | Trendy but often inaccessible | Use sparingly, ensure contrast |

### AI Slop Content Patterns to Avoid

- Generic hero sections with vague taglines ("Empower your workflow")
- Cookie-cutter landing page layouts
- Feature grids with icons that could apply to anything
- Excessive use of buzzwords in UI copy
- Decorative elements that add no meaning

### What Makes Design Feel Human

- **Personality in copywriting** — Real voice, not corporate speak
- **Intentional imperfection** — Hand-drawn elements, custom illustrations
- **Constraint** — Knowing what NOT to include
- **Context-awareness** — Design serves the specific use case, not generic patterns
- **Consistency over novelty** — Boring is often better

---

## Component Guidelines

### Buttons

```
Primary:   Solid background, high contrast text, clear CTA
Secondary: Outlined or ghost, lower visual weight
Tertiary:  Text-only, for less important actions
Disabled:  50% opacity, no pointer events

Sizing:
- Small: 32px height, compact UI
- Medium: 40px height, default
- Large: 48px height, touch targets, primary CTAs
```

### Forms

- Labels above inputs (not placeholder-as-label)
- Clear focus states (visible ring, color change)
- Inline validation, not just on submit
- Logical tab order
- Required field indicators (asterisk or text)
- Helpful hint text for complex fields

```
❌ Placeholder text as the only label
❌ Error messages appearing somewhere else on screen
❌ Submit button disabled with no explanation why

✅ Clear labels, visible focus, inline errors, enabled submit with validation
```

### Cards

- Consistent padding (16-24px)
- Clear content hierarchy
- Single primary action if any
- Subtle shadow or border, not both
- Avoid nesting cards within cards

### Navigation

- Primary nav: 5-7 items maximum
- Mobile: Hamburger menu or bottom nav (not both)
- Current state clearly indicated
- Breadcrumbs for deep hierarchies
- Search accessible on complex sites

### Tables

- Left-align text, right-align numbers
- Zebra striping OR grid lines, not both
- Sticky headers for long tables
- Sort indicators when sortable
- Row actions: hover reveal or action column
- Pagination or infinite scroll, clear indication of more data

### Modals/Dialogs

- Clear title describing the action
- Single primary action, cancel always available
- Focus trapped within modal
- Escape key closes (unless destructive action)
- Background overlay with click-to-close
- Don't nest modals

---

## Responsive Design

### Breakpoints (Standard)

```
sm:  640px  — Small tablets, large phones landscape
md:  768px  — Tablets portrait
lg:  1024px — Tablets landscape, small laptops
xl:  1280px — Desktops
2xl: 1536px — Large desktops
```

### Mobile-First Patterns

- Stack columns on mobile, expand on desktop
- Touch targets: minimum 44x44px
- Thumb-friendly zones for critical actions
- Simplify navigation for mobile
- Reduce content density, not just shrink

### What Changes on Mobile

| Desktop | Mobile |
|---------|--------|
| Horizontal nav | Hamburger or bottom nav |
| Multi-column grid | Single column or 2-col max |
| Hover states | Tap states (no hover on touch) |
| Side panels | Full-screen overlays |
| Data tables | Cards or simplified tables |

---

## Accessibility Checklist

Not optional. Design for everyone.

- [ ] Color contrast: 4.5:1 minimum for text
- [ ] Don't rely on color alone (add icons, text, patterns)
- [ ] Focus states visible and clear
- [ ] Keyboard navigation works
- [ ] Alt text on meaningful images
- [ ] Form labels associated with inputs
- [ ] Error messages clear and specific
- [ ] Touch targets 44x44px minimum
- [ ] Motion can be reduced (respects prefers-reduced-motion)
- [ ] Text resizable without breaking layout

---

## Implementation Checklist

Before shipping any interface:

### UX Verification
- [ ] Primary action is immediately obvious
- [ ] User can accomplish goal in minimum steps
- [ ] Error states are helpful, not just red
- [ ] Loading states exist for async operations
- [ ] Empty states guide users to next action

### UI Verification
- [ ] Spacing follows consistent scale
- [ ] Typography hierarchy is clear
- [ ] Color palette is limited and intentional
- [ ] Components are consistent throughout
- [ ] No orphan elements (everything aligned to grid)

### Anti-Slop Check
- [ ] Could remove any element without losing function?
- [ ] Does every gradient/shadow/animation serve a purpose?
- [ ] Would a non-designer find it cluttered?
- [ ] Is the copywriting specific to this product?
- [ ] Does it look distinct from a template?

---

## Quick Reference

### When Designing

1. **Start with user intent** — What are they trying to do?
2. **Minimize choices** — Each decision = cognitive load
3. **Establish hierarchy** — What's most important?
4. **Apply constraints** — Limited colors, spacing scale, one typeface
5. **Remove until it breaks** — Then add back only what's needed

### When Reviewing

Ask these questions:
- Can I remove anything?
- Is the primary action obvious?
- Does the spacing feel consistent?
- Is there unnecessary decoration?
- Would this work on mobile?

---

## References

For detailed design token specifications and component patterns, see:
- `references/design-tokens.md` — Spacing, color, typography scales
