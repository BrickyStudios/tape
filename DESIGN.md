# Tape design

The tokens live in `app/src/main/java/io/github/brickystudios/tape/Ui.kt`. This file says what they are for, so new screens use them the same way.

## Direction

- **Product:** prices and the news that moves them, for the stocks you follow, without ads.
- **Principle:** as much as possible at one glance, as plain statements: "beat · 9.45 vs 6.59", "Overbought, RSI 74", "39 % under its high". A number always comes with what it means; a row says one thing, the stock's page says all of it.
- **Character:** calm, exact, a little nocturnal.
- **World:** a trading desk at night: graphite surfaces, one amber lamp.
- **Moment:** the price rows, each with today's line and a coloured change tag.
- **Signature:** an amber clock on headlines younger than 10 minutes.

## Colour

| Token | Value | Use |
|---|---|---|
| `Ink` | `#141415` | page background |
| `Raised` | `#232325` | selected range or filter, the assessment block, inputs |
| `Text1` | `#EDEDED` | prices, headlines, labels |
| `Text2` | `#A1A1A6` | company names, meta lines |
| `Text3` | `#85858B` | old clocks, hints, dashed previous-close line (4.9:1 on Ink) |
| `Hairline` | `Text1` at 8 % | row dividers |
| `Glass` | `Raised` at 80 % | tint of the floating glass; nearly solid on Android 11 and older, which cannot blur |
| `Amber` | `#F2A93B` | fresh clocks, new-item counts, a market that trades right now ("open until 17:30"), the primary action ("Find a stock"), a chosen Watch / I own this button, a buying chance that holds |
| `Up` | `#7CC45A` | price up, "rather positive" and a beaten estimate (OKLCH hue 137, away from teal) |
| `Down` | `#E5484D` | price down, "rather negative", a sell signal, a missed estimate and source errors |

Colour never carries direction alone: changes always show ▲ or ▼.

## Type

Roboto (system), monospace for symbols and clocks. Sizes: 28 sp page title and detail price, 16 sp body and prices, 14 sp section labels and buttons, 13 sp meta. Weights 400 and 500. Prices and percentages use tabular figures (`Tnum`); percent uses a narrow no-break space ("+31.5 %").

## Spacing and shape

4 dp grid. Page margin 20 dp. Pages end with 104 dp of room (`BAR_ROOM`) so the last line scrolls above the floating tab bar. Rows: quotes 72 dp, settings 56 dp, touch targets at least 48 dp. Sections: 32 dp above the label, 8 dp below. Radii: 6 dp change tag, 8 dp range and filter buttons, 10 dp inputs, 12 dp the assessment block. No shadows, no glow. The one gradient is the fade from transparent to `Ink` under the tab bar, so text never sits half under the glass.

## Glass and motion

Glass only for chrome that floats over content, as in Freehub (Kyant's Liquid Glass, blur 20 dp, refraction at the rim): the tab bar capsule, the search circle beside it, the back capsule on a stock's page and the update card. Never glass on glass, never on content. Shapes are continuous corners (`Pill`, `Card`).

Motion explains where you are: the pill under the tabs follows the pages 1:1 while you swipe, a deeper page (search, a stock) slides in a quarter width from the right in 320 ms with an iOS-like ease-out (0.32, 0.72, 0, 1) and fades out the way it came, buttons sink to 96 % while pressed (100 ms down, 160 ms back), and Watch / I own this give a haptic tick. "Remove animations" in Android's settings stops all of it.

## Components

- `QuoteRow`: name over company | today's line | price over `ChangeTag`. Used for stocks and market rows alike. A US stock outside the session adds `ExtLine` ("pre-market ▲ +0.3 %") under the whole row, so the day lines stay in one column.
- Rows that carry no price stay on the same column grid: `ReleaseRow` and `EarningRow` (Dates tab) use the headline clock column; `VixRow` replaces the change tag with a word (calm, normal, nervous in neutral steps, panic in `Down`), because a rising VIX is fear, not a gain. `PositioningBlock` writes the CFTC numbers as two sentences, the group in weight 500. Emoji in Reddit titles are stripped on the way in.
- `RegionHeader`: section label with the session in 13 sp ("Europe · open until 17:30"). Regions sort by what trades now.
- `Sparkline` / chart: line in `Up` or `Down` by direction against yesterday's close (`Text2` when the move rounds to 0.0 %), the close dashed in `Text3`.
- `HeadlineRow`: clock column, headline, meta line `SYMBOL · Event · ▲ rather positive · Source`.
- `BottomBar`: the four tabs in one glass capsule, labels 13 sp, a `Text1`-at-10 % pill under the open tab, counts in amber mono 11 sp; the search circle (56 dp) to its right.
- `Search`: field on `Raised` with the hint "Name, symbol or ISIN", results while typing (300 ms pause), rows on the symbol column grid; a stock already on the list says "Watching" or "In my portfolio" in `Text3`.
- `FollowButton`: two pills under the price, `Raised` when off, `Amber` at 16 % with amber text and a ✓ when on.
- `ChanceBlock`: section "Signals", two lines "Buying chance: 2 of 4" (amber when any holds) and "Sell signal: 1 of 3" (`Down` when any holds), a 2 × 3 grid (200-day line, RSI, 52-week low, from the high, P/E, earnings per share) with the level as value and the distance below it, a dot before a rule that holds (amber to buy, `Down` to sell), the rules in `Text3` underneath. The leading rule goes under the stock's row in its colour, the sell signal first for an owned stock.
- `EarningRow` after a report: the verdict word in colour (beat `Up`, missed `Down`, in line `Text1`) and "9.45 vs 6.59" in the meta line, the move since as a `ChangeTag` on the right with "since" under it. Yesterday's reports stay on the Dates tab, because today's prices answer them.
- Videos and posts (YouTube, X) use `HeadlineRow` with "· YouTube" or "· X" after the name.
- `Title`: page title with the market status and last check in monospace below.
