# Design Tokens Reference

Standardized design values for consistent, scalable interfaces.

## Spacing Scale

Use multiples of 4px for all spacing.

| Token | Value | Use Case |
|-------|-------|----------|
| `--space-0` | 0 | Reset spacing |
| `--space-1` | 4px | Tight: icon-text gap, inline elements |
| `--space-2` | 8px | Compact: related elements within a component |
| `--space-3` | 12px | Default: form field gaps, list item padding |
| `--space-4` | 16px | Standard: card padding, section gaps |
| `--space-5` | 20px | Comfortable: grouped content spacing |
| `--space-6` | 24px | Relaxed: between sections |
| `--space-8` | 32px | Large: major section separation |
| `--space-10` | 40px | Generous: page section margins |
| `--space-12` | 48px | Extra: hero sections, major landmarks |
| `--space-16` | 64px | Maximum: page-level vertical rhythm |

### CSS Variables

```css
:root {
  --space-1: 0.25rem;  /* 4px */
  --space-2: 0.5rem;   /* 8px */
  --space-3: 0.75rem;  /* 12px */
  --space-4: 1rem;     /* 16px */
  --space-5: 1.25rem;  /* 20px */
  --space-6: 1.5rem;   /* 24px */
  --space-8: 2rem;     /* 32px */
  --space-10: 2.5rem;  /* 40px */
  --space-12: 3rem;    /* 48px */
  --space-16: 4rem;    /* 64px */
}
```

---

## Typography Scale

Based on a 1.25 ratio (major third) from 16px base.

| Token | Size | Line Height | Use Case |
|-------|------|-------------|----------|
| `--text-xs` | 12px | 1.5 | Labels, captions, fine print |
| `--text-sm` | 14px | 1.5 | Secondary text, table cells |
| `--text-base` | 16px | 1.5 | Body text, inputs |
| `--text-lg` | 18px | 1.5 | Lead paragraphs |
| `--text-xl` | 20px | 1.4 | H4, card titles |
| `--text-2xl` | 24px | 1.3 | H3 |
| `--text-3xl` | 30px | 1.3 | H2 |
| `--text-4xl` | 36px | 1.2 | H1, page titles |
| `--text-5xl` | 48px | 1.1 | Hero headings |

### Font Weights

| Token | Weight | Use Case |
|-------|--------|----------|
| `--font-normal` | 400 | Body text |
| `--font-medium` | 500 | Emphasis, labels |
| `--font-semibold` | 600 | Subheadings, buttons |
| `--font-bold` | 700 | Headings, strong emphasis |

### CSS Variables

```css
:root {
  /* Sizes */
  --text-xs: 0.75rem;
  --text-sm: 0.875rem;
  --text-base: 1rem;
  --text-lg: 1.125rem;
  --text-xl: 1.25rem;
  --text-2xl: 1.5rem;
  --text-3xl: 1.875rem;
  --text-4xl: 2.25rem;
  --text-5xl: 3rem;

  /* Line heights */
  --leading-tight: 1.2;
  --leading-snug: 1.3;
  --leading-normal: 1.5;
  --leading-relaxed: 1.625;

  /* Weights */
  --font-normal: 400;
  --font-medium: 500;
  --font-semibold: 600;
  --font-bold: 700;
}
```

---

## Color Tokens

### Neutral Colors (Light Mode)

| Token | Value | Use Case |
|-------|-------|----------|
| `--neutral-50` | #FAFAFA | Page background |
| `--neutral-100` | #F5F5F5 | Card backgrounds, subtle fills |
| `--neutral-200` | #E5E5E5 | Borders, dividers |
| `--neutral-300` | #D4D4D4 | Disabled borders |
| `--neutral-400` | #A3A3A3 | Placeholder text |
| `--neutral-500` | #737373 | Secondary text |
| `--neutral-600` | #525252 | Body text (secondary) |
| `--neutral-700` | #404040 | Body text |
| `--neutral-800` | #262626 | Headings |
| `--neutral-900` | #171717 | High contrast text |

### Semantic Colors

```css
:root {
  /* Primary */
  --primary-50: #EFF6FF;
  --primary-100: #DBEAFE;
  --primary-500: #3B82F6;
  --primary-600: #2563EB;
  --primary-700: #1D4ED8;

  /* Success */
  --success-50: #F0FDF4;
  --success-500: #22C55E;
  --success-700: #15803D;

  /* Warning */
  --warning-50: #FFFBEB;
  --warning-500: #F59E0B;
  --warning-700: #B45309;

  /* Error */
  --error-50: #FEF2F2;
  --error-500: #EF4444;
  --error-700: #B91C1C;

  /* Info */
  --info-50: #EFF6FF;
  --info-500: #3B82F6;
  --info-700: #1D4ED8;
}
```

### Dark Mode Mapping

| Light Token | Dark Equivalent |
|-------------|-----------------|
| `--neutral-50` (bg) | `--neutral-900` |
| `--neutral-100` (surface) | `--neutral-800` |
| `--neutral-200` (border) | `--neutral-700` |
| `--neutral-700` (text) | `--neutral-200` |
| `--neutral-900` (heading) | `--neutral-50` |

---

## Border Radius

| Token | Value | Use Case |
|-------|-------|----------|
| `--radius-none` | 0 | No rounding |
| `--radius-sm` | 4px | Subtle rounding (inputs, buttons) |
| `--radius-md` | 6px | Default (cards, modals) |
| `--radius-lg` | 8px | Prominent elements |
| `--radius-xl` | 12px | Larger containers |
| `--radius-2xl` | 16px | Pills, avatars |
| `--radius-full` | 9999px | Circles, full pills |

### Consistency Rule

Pick ONE radius for each component category and stick to it:
- Buttons: `--radius-sm` or `--radius-md`
- Cards: `--radius-md` or `--radius-lg`
- Inputs: Same as buttons
- Avatars: `--radius-full`

---

## Shadows

| Token | Use Case |
|-------|----------|
| `--shadow-sm` | Subtle elevation (dropdowns, popovers) |
| `--shadow-md` | Default cards |
| `--shadow-lg` | Modals, dialogs |
| `--shadow-xl` | High emphasis elements |

```css
:root {
  --shadow-sm: 0 1px 2px 0 rgb(0 0 0 / 0.05);
  --shadow-md: 0 4px 6px -1px rgb(0 0 0 / 0.1);
  --shadow-lg: 0 10px 15px -3px rgb(0 0 0 / 0.1);
  --shadow-xl: 0 20px 25px -5px rgb(0 0 0 / 0.1);
}
```

### Shadow Guidelines

- Use sparingly—most elements need no shadow
- Shadows indicate elevation/layering
- Cards on white backgrounds often need only subtle border OR shadow, not both
- Dark mode: reduce shadow opacity or use lighter shadows

---

## Z-Index Scale

| Token | Value | Use Case |
|-------|-------|----------|
| `--z-dropdown` | 50 | Dropdowns, popovers |
| `--z-sticky` | 100 | Sticky headers |
| `--z-modal` | 200 | Modal dialogs |
| `--z-toast` | 300 | Toast notifications |
| `--z-tooltip` | 400 | Tooltips |

---

## Transitions

| Token | Duration | Easing | Use Case |
|-------|----------|--------|----------|
| `--duration-fast` | 100ms | ease-out | Hover states |
| `--duration-normal` | 200ms | ease-in-out | Default transitions |
| `--duration-slow` | 300ms | ease-in-out | Modals, overlays |

```css
:root {
  --duration-fast: 100ms;
  --duration-normal: 200ms;
  --duration-slow: 300ms;
  --ease-default: cubic-bezier(0.4, 0, 0.2, 1);
  --ease-in: cubic-bezier(0.4, 0, 1, 1);
  --ease-out: cubic-bezier(0, 0, 0.2, 1);
}
```

### Motion Guidelines

- Respect `prefers-reduced-motion`
- Keep transitions under 300ms
- Use `transform` and `opacity` for performance
- Avoid animating `width`, `height`, `top`, `left`

---

## Breakpoints

| Token | Value | Description |
|-------|-------|-------------|
| `--screen-sm` | 640px | Small tablets |
| `--screen-md` | 768px | Tablets |
| `--screen-lg` | 1024px | Small laptops |
| `--screen-xl` | 1280px | Desktops |
| `--screen-2xl` | 1536px | Large screens |

### Media Query Pattern

```css
/* Mobile first */
.component { /* mobile styles */ }

@media (min-width: 768px) {
  .component { /* tablet+ styles */ }
}

@media (min-width: 1024px) {
  .component { /* desktop styles */ }
}
```

---

## Component Size Tokens

### Button Sizes

| Size | Height | Padding X | Font Size |
|------|--------|-----------|-----------|
| `sm` | 32px | 12px | 14px |
| `md` | 40px | 16px | 14px |
| `lg` | 48px | 24px | 16px |

### Input Sizes

| Size | Height | Padding X | Font Size |
|------|--------|-----------|-----------|
| `sm` | 32px | 12px | 14px |
| `md` | 40px | 14px | 16px |
| `lg` | 48px | 16px | 16px |

### Icon Sizes

| Token | Size | Use Case |
|-------|------|----------|
| `--icon-xs` | 12px | Inline with small text |
| `--icon-sm` | 16px | Inline with body text |
| `--icon-md` | 20px | Buttons, inputs |
| `--icon-lg` | 24px | Standalone icons |
| `--icon-xl` | 32px | Feature icons |

---

## Using Tokens in Practice

### Example: Card Component

```css
.card {
  background: var(--neutral-50);
  border: 1px solid var(--neutral-200);
  border-radius: var(--radius-lg);
  padding: var(--space-6);
  box-shadow: var(--shadow-sm);
}

.card-title {
  font-size: var(--text-xl);
  font-weight: var(--font-semibold);
  color: var(--neutral-900);
  margin-bottom: var(--space-2);
}

.card-body {
  font-size: var(--text-base);
  color: var(--neutral-700);
  line-height: var(--leading-relaxed);
}
```

### Example: Button Component

```css
.btn {
  height: 40px;
  padding: 0 var(--space-4);
  font-size: var(--text-sm);
  font-weight: var(--font-semibold);
  border-radius: var(--radius-md);
  transition: all var(--duration-fast) var(--ease-default);
}

.btn-primary {
  background: var(--primary-600);
  color: white;
}

.btn-primary:hover {
  background: var(--primary-700);
}

.btn-secondary {
  background: transparent;
  border: 1px solid var(--neutral-300);
  color: var(--neutral-700);
}
```
