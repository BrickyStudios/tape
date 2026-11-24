# Tape design

The tokens live in `app/src/main/java/io/github/brickystudios/tape/Ui.kt`. This file says what they are for, so new screens use them the same way.

## Direction

- **Product:** prices and the news that moves them, for the stocks you follow, without ads.
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
| `Amber` | `#F2A93B` | fresh clocks, new-item counts, a market that trades right now ("open until 17:30"), the primary action ("Add stock", "Search") |
| `Up` | `#7CC45A` | price up and "rather positive" only (OKLCH hue 137, away from teal) |
| `Down` | `#E5484D` | price down, "rather negative" and source errors only |

Colour never carries direction alone: changes always show ▲ or ▼.

## Type

Roboto (system), monospace for symbols and clocks. Sizes: 28 sp page title and detail price, 16 sp body and prices, 14 sp section labels and buttons, 13 sp meta. Weights 400 and 500. Prices and percentages use tabular figures (`Tnum`); percent uses a narrow no-break space ("+31.5 %").

## Spacing and shape

4 dp grid. Page margin 20 dp. Rows: quotes 72 dp, settings 56 dp, touch targets at least 48 dp. Sections: 32 dp above the label, 8 dp below. Radii: 6 dp change tag, 8 dp range and filter buttons, 10 dp inputs, 12 dp the assessment block. No shadows, no gradients, no glow.

## Components

- `QuoteRow`: name over company | today's line | price over `ChangeTag`. Used for stocks and market rows alike. A US stock outside the session adds `ExtLine` ("pre-market ▲ +0.3 %") under the whole row, so the day lines stay in one column.
- `RegionHeader`: section label with the session in 13 sp ("Europe · open until 17:30"). Regions sort by what trades now.
- `Sparkline` / chart: line in `Up` or `Down` by direction against yesterday's close (`Text2` when the move rounds to 0.0 %), the close dashed in `Text3`.
- `HeadlineRow`: clock column, headline, meta line `SYMBOL · Event · ▲ rather positive · Source`.
- `Title`: page title with the market status and last check in monospace below.
