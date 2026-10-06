# AndVibe — Visual Style Guide

The visual contract for the rebuild. The current app's look is a keeper ("classic GitHub vibe
but also pure Android… console like a trading platform"), so this documents what exists today
as named tokens and rules. `DESIGN.md` governs code structure; this file governs pixels.
Companion: `USER.md` principle 4 (blocked buttons + busy bar, nothing flashy).

## Design intent

- **GitHub Primer dark** is the base: near-black canvas, panel surfaces, 1dp hairline borders,
  blue interactive accent, green primary action.
- **Trading-terminal accents** for live data: monospace, bold, tight; green = positive/money,
  red = negative/cost, amber = quote/warning; near-black "tape" panels for streams.
- **Flat.** Elevation 0 everywhere. Depth comes from borders (`line`) and darker wells
  (`tape`), never shadows.
- **Dark only.** `values-night` is identical to `values`; `forceDarkAllowed=false`. Light mode
  is explicitly out of scope.

## 1. Color tokens

Single source of truth: `app/src/main/res/values/colors.xml`. Everything else (WebView HTML,
Kotlin, website) derives from these — see §6.

### Surfaces & text

| Token | Hex | Role |
|---|---|---|
| `bg` | `#0D1117` | Window background, status/nav bars, bottom nav |
| `panel` | `#161B22` | Cards, fields, chips — the default surface |
| `raised` | `#21262D` | Hover/active surface, table header rows (today only in Understand HTML — promote into `colors.xml`) |
| `tape` | `#010409` | Near-black wells: console, log streams, `onSecondary` |
| `line` | `#30363D` | 1dp hairline borders, dividers, badge fill, outlined-button stroke |
| `ink` | `#E6EDF3` | Primary text |
| `muted` | `#8B949E` | Secondary text, hints, inactive nav, plain file glyphs |
| `tape_ink` | `#C9D1D9` | Text on `tape` surfaces |

### Action & signal

| Token | Hex | Role |
|---|---|---|
| `accent` | `#58A6FF` | Interactive: links, focus stroke, checked/active states, selection, mention tags |
| `btn` | `#238636` | Primary filled button (GitHub green) |
| `bid` / `meter_green` | `#3FB950` | Positive: gains, success, token-meter green, trade-button fill, ripple on primary |
| `ask` / `meter_red` | `#F85149` | Negative: errors, cost, token-meter red |
| `quote` | `#D29922` | Warnings, amber data (license glyph, quote values) |

Derived: `accent` at 10% alpha (`#1A58A6FF`) is the mention-tag fill. Express alpha variants
as alpha-on-token, never as new hexes.

### Git status (VS Code decoration colors)

| Token | Hex | Meaning |
|---|---|---|
| `git_modified` | `#E2C08D` | Modified (M) |
| `git_added` | `#73C991` | Added/staged (A) |
| `git_deleted` | `#C74E39` | Deleted (D) |
| `git_conflict` | `#E4676B` | Conflict (!) |
| `git_commit` | `#1F6FEB` | Commit dots/refs |

### File-type glyphs

`FileIcons.kt` holds per-language brand colors (Kotlin `#A97BFF`, JS `#F1E05A`, TS `#3178C6`,
Android `#3DDC84`, folder `#DCB67A`, …). These are **brand colors, not theme tokens** — they
stay as the table in `FileIcons` and are the one sanctioned place for hex literals in Kotlin.
Fallback glyph color is `muted`.

## 2. Typography

- **UI text:** system sans (Roboto default). No custom font.
- **Data text:** `monospace` — console, logs, tape rows, diffs, paths, file glyphs, token/price
  numbers, trade buttons. Rule of thumb: *if it scrolls like a terminal or counts like money,
  it's monospace.*
- **Never** `textAllCaps`; letterSpacing 0 on buttons (0.04 only on nav labels).

Scale (observed, keep):

| Size | Use |
|---|---|
| 22sp | Page titles, headline metrics |
| 15–16sp | Section titles |
| 14sp | Primary body, filled buttons |
| 13sp | Default body, outlined/trade buttons, tab labels — the workhorse |
| 12sp | Secondary rows, log text |
| 11sp | Nav labels, captions, badges |
| 9–10sp | Tiny meters/superscripts (sparingly) |

Bold is reserved for monospace data emphasis (trade buttons, tickers) and section titles.

## 3. Shape & spacing

- **Radii:** 6dp is the default (cards, fields, buttons, tape panels). 12dp chips, 8–9dp small
  pills (mention tag, count badge), **2dp trade buttons** (sharp = terminal). Nothing fully
  rounded.
- **Borders:** 1dp `line` on every surface that sits on `bg`. Fill + stroke, always both.
- **Spacing scale:** 2 / 4 / 6 / 8 / 10 / 12 / 16 dp, with **8dp as the default gap** (it's
  ~45% of all current spacing). 20dp+ only for page-level insets.
- **Elevation:** 0dp everywhere, including bottom nav.

## 4. Components

- **Filled button** — `btn` green fill, white 14sp text, 40dp min height, 6dp radius,
  `bid` ripple. One primary action per screen region.
- **Outlined button** — `panel`-transparent with 1dp `line` stroke, `ink` 13sp text, 36dp
  min height. The default for secondary actions (most git-tab actions).
- **Trade button** — monospace bold 13sp, `tape`-dark text on `bid` green fill, 2dp radius,
  36dp/64dp min. For money-adjacent or terminal-adjacent confirmations.
- **Text field** — outlined box, `panel` fill, 1dp stroke: `line` → `accent` when focused
  (`color/box_stroke` selector), 6dp radius, `muted` hint.
- **Card** (`bg_card`) — `panel` + `line` + 6dp. **Chip** (`bg_chip`) — same at 12dp.
  **Badge** (`bg_badge`) — `line` fill, 9dp. **Tape panel** (`bg_tape`) — `tape` + `line` +
  6dp for console/log wells. **Mention tag** (`bg_mention`) — 10% `accent` fill, 1dp `accent`
  stroke, 8dp.
- **Bottom nav** — `bg` background, 0 elevation, always-labeled 11sp, `color/nav_tint`
  selector (`accent` checked / `muted` unchecked). 8 items via `MaxBottomNav`.
- **Busy states** — the token/price meter sits just above the nav bar in sharp red/green
  monospace (`meter_red`/`meter_green`); the busy bar is the one animated element. Buttons
  **block without fading**: `BusyUi.setEnabled` keeps `alpha = 1` and `isEnabled = true`,
  only killing clickability — Material's disabled fade was rejected as too flashy. Preserve
  this exact behavior in the rebuild.

## 5. Theme wiring (keep as-is)

`Theme.AndVibe` (MaterialComponents.NoActionBar): `colorPrimary=accent`, `colorSecondary=bid`,
`colorSurface=panel`, `colorOnSurface=ink`, status + navigation bars `bg` with light icons,
`colorControlActivated=accent`. All widget defaults route through the `Widget.AndVibe.*`
styles so individual layouts don't restyle controls.

## 6. Rules for the rebuild

1. **No hex literals outside `colors.xml`** — exceptions: `FileIcons` brand glyphs and
   alpha-on-token derivations. Add `raised` (`#21262D`) to `colors.xml`.
2. **The Understand WebView must take its palette from the theme**, not hardcode it:
   `UnderstandDoc` currently duplicates ~10 hexes in generated HTML/CSS. In the rebuild,
   inject the token values (CSS variables) from resources so theme and WebView can't drift.
3. New UI states reuse signal tokens by meaning (success=`bid`, error=`ask`, warning=`quote`,
   interactive=`accent`) rather than minting new colors.
4. The website (andvibe.org) should map its shadcn theme to this palette for brand
   consistency, but the app's `colors.xml` is the canonical source.
5. Any future light theme is a *new* token sheet, not inline conditionals — but it's a
   non-goal (see Design intent).
