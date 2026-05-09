# Command palette — current color sources

## The blue you saw on screen

`#5ab0ff` — a **hardcoded fallback** baked into my CSS. It is *not* a
theme color, and it is *not* what `--t-accent-primary` resolves to in
your active theme. It only paints when `--t-accent-primary` is missing
or fails to resolve (which appears to be what's happening on your
current setup, since the user-visible Theme Manager doesn't expose an
accent picker).

The CSS pattern in `web/src/jsMain/.../main/AppShell.kt` is:

```css
border: 3px solid var(--t-accent-primary, #5ab0ff);
box-shadow:
    0 0 0 1px rgba(0, 0, 0, 0.65),
    0 0 0 6px color-mix(in srgb, var(--t-accent-primary, #5ab0ff) 22%, transparent),
    ...;
```

`var(--name, fallback)` returns `fallback` when `--name` is unset, so
both the 3 px border and the 6 px halo will collapse to `#5ab0ff` if
`--t-accent-primary` isn't defined on the document.

## Where each visible color comes from

| Surface | Source | Hardcoded fallback |
| --- | --- | --- |
| Panel background | `var(--t-terminal-bg)` | `#1e1e1e` |
| Panel text | `var(--t-terminal-fg)` | `#e6e6e6` |
| Panel border (3 px) | `var(--t-accent-primary)` | **`#5ab0ff`** ← the blue |
| Panel halo (6 px glow) | `var(--t-accent-primary)` mixed 22% with transparent | **`#5ab0ff`** ← the blue |
| Inner contour (1 px) | `rgba(0, 0, 0, 0.65)` | always literal |
| Drop shadow | `rgba(0, 0, 0, 0.65)` + `rgba(0, 0, 0, 0.45)` | always literal |
| Inset highlight | `rgba(255, 255, 255, 0.06)` | always literal |
| Input divider | `var(--t-border)` | `rgba(255, 255, 255, 0.10)` |
| Active row background | `var(--t-accent-primary)` mixed 22% | **`#5ab0ff`** ← the blue |
| Empty-state text | `var(--t-text-secondary)` | `rgba(255, 255, 255, 0.55)` |

## Notes about `#5ab0ff`

- It's a soft sky blue ≈ `rgb(90, 176, 255)`.
- I picked it because it reads as a friendly accent on dark
  backgrounds without competing with the editor caret.
- It also reuses the same numeric values that already appear sprinkled
  through `OutlinePaintLoop.kt` (`rgba(90, 176, 255, …)`) for the
  active-style highlight — so the palette and the editor's own accents
  agree visually when no theme accent is set.

## Does the Theme Manager change it?

The toolkit *does* set `--t-accent-primary` from
`ResolvedPalette.accent.primary` in
`darkness-toolkit/.../ThemeCssVars.kt`. But your Theme Manager UI only
shows section-based schemes (Main content, Sidebar, Tab strip, …) —
the accent is derived from one of those schemes, not a standalone
picker. If switching the scheme on, say, "Main content" or "Active
indicators" doesn't change the palette border, then on your build the
accent token isn't being populated and the `#5ab0ff` fallback wins.

## If you want a different default

Change both the border and the halo at the same time so they stay
consistent:

```css
border: 3px solid var(--t-accent-primary, <new-color>);
box-shadow:
    ...
    0 0 0 6px color-mix(in srgb, var(--t-accent-primary, <new-color>) 22%, transparent),
    ...;
```

Or point the palette at a different theme variable that *is* visibly
edited in the Theme Manager — `--t-border-focus`, `--t-sidebar-activeBg`,
or `--t-chrome-titlebar` are all populated per scheme and would track
your theme picks reliably.
