# Performance Waterfall Chart Plan

## Goal

Add a standalone **Waterfall** view under **Performance** that explains portfolio
performance over one selected reporting period. The view supports two related
breakdowns:

1. Reconciliation of the performance calculation from initial portfolio value
   to final portfolio value.
2. Absolute performance contribution by investment instrument.

The first version uses additive monetary amounts in the portfolio base currency.
TTWROR and IRR percentages are not additive across instruments and are therefore
outside the initial scope.

## Product decisions

- Add the waterfall as a sibling of Calculation, Chart, and Return / Volatility
  in the Performance navigation section.
- Do not add it to `PerformanceView`'s internal tab folder. That folder contains
  calculation tables and transaction details, while the waterfall needs its own
  chart controls and lifecycle.
- Interpret "over a period" as decomposing one selected reporting interval.
  Monthly or daily component waterfalls require additional domain calculations
  and are deferred.
- Use the existing SWTChart dependency. Implement the missing floating-bar
  primitive with a custom paint listener instead of introducing another chart
  engine.
- Keep `ClientPerformanceSnapshot` as the accounting source of truth.

## Mode 1: Performance calculation

Render the existing accounting identity as a bridge:

```text
Initial value
+ Unrealized capital gains
+ Realized capital gains
+ Earnings
- Fees
- Taxes
+ Currency gains
+ Transfers
= Final value
```

Initial and final values are total bars. The other entries are floating change
bars. Transfers are included because this mode reconciles portfolio values; they
must not be described as investment performance.

The identity is already asserted by
`ClientPerformanceSnapshotTest.assertThatCalculationWorksOut`:

```text
final = initial
      + capital gains
      + realized capital gains
      + earnings
      - fees
      - taxes
      + currency gains
      + transfers
```

## Mode 2: Contribution by instrument

Start at zero, add each instrument's absolute contribution, and finish with the
portfolio's total absolute performance:

```text
instrument contribution =
    unrealized capital gains
  + realized capital gains
  + earnings
  - fees
  - taxes
```

Aggregate `ClientPerformanceSnapshot.Category#getPositions()` by `Security`.
Add synthetic buckets for:

- Earnings and charges without an assigned security.
- Cash/currency effects from the `CURRENCY_GAINS` category.
- Contributions outside the configured top N, grouped as "Other".

Do not add `Position#getForexGain()` to the contribution. It is an explanatory
subcomponent of the reporting-currency capital gain, not an additional gain.

Transfers are excluded from this mode because they are not performance. The sum
of all displayed contributions must equal
`ClientPerformanceSnapshot#getAbsoluteDelta()`.

## Chart engine decision

SWTChart 1.1.0 already provides category and numeric axes, coordinate conversion,
custom painting, zooming, theming hooks, context menus, and image export. Its bar
series do not support a different arbitrary baseline for every bar. A stacked
transparent-baseline workaround is also unreliable for negative changes and
cumulative values that cross zero.

Implement `WaterfallChart` on top of `PlainChart`:

- Configure a category X axis and monetary Y axis.
- Store each entry's cumulative start and end values.
- Draw floating rectangles through `ICustomPaintListener` using
  `IAxis#getPixelCoordinate`.
- Draw connectors, a zero line, and optional value labels.
- Use distinct positive, negative, and total colors.
- Calculate the Y range from all start and end values because custom-painted
  bars are invisible to `IAxisSet#adjustRange`.
- Retain painted rectangles for tooltip hit-testing and selection.
- Reuse `ChartContextMenu` and `ChartUtil.save` for interaction and image export.

Before implementing the production renderer, build a small synthetic spike with
positive and negative steps, totals, a zero crossing, long labels, and a negative
cumulative baseline. Verify resizing, high-DPI rendering, light/dark themes,
zoom/pan, hit-testing, clipping, and PNG export.

A new chart engine should be considered only if this spike cannot support correct
painting, theme changes, hit-testing, or export. Adding another engine would also
add OSGi target-platform, packaging, styling, accessibility, and maintenance
costs.

## Implementation phases

### 1. Add the domain breakdown model

Add a presentation-independent model in the core snapshot package, tentatively:

- `PerformanceBreakdown`
- `PerformanceBreakdown.Entry`
- `PerformanceBreakdown.EntryKind`: `START`, `CHANGE`, `SUBTOTAL`, `TOTAL`

Each entry contains:

- A stable semantic type.
- A display label.
- A signed `Money` amount.
- An optional `Security`.
- An optional source category or position for explanations and selection.

Provide factories for category reconciliation and contribution by instrument.
The adapter must assign signs explicitly rather than parsing
`Category#getSign()`. In particular, fees and taxes are negated. Negative fee or
tax values represent refunds and consequently become positive contributions.

Preserve these existing semantics:

- Dividends and interest are gross earnings; their taxes and fees are separate.
- Interest charges are negative earnings.
- Transfer positions cannot be summed directly because deposits and removals are
  both stored as positive position values. Use the category's net valuation.
- Security capital gains use `TaxesAndFees.NOT_INCLUDED` because fees and taxes
  have their own waterfall entries.
- Currency gains and cross-currency transfers retain the existing snapshot's
  exchange-rate and rounding behavior.

### 2. Add the UI dataset and renderer

Add the following under `name.abuchen.portfolio.ui/.../ui/util/chart`:

- `WaterfallDataset.java`
- `WaterfallChart.java`
- `WaterfallChartToolTip.java`
- `WaterfallChartCSVExporter.java`

`WaterfallDataset` converts signed breakdown entries into cumulative start/end
values and is unit-testable without SWT painting.

`WaterfallChartCSVExporter` exports at least:

- Label
- Entry kind
- Start value
- Signed change
- End value
- Currency

Update `ChartContextMenu` so "Original size" invokes the waterfall-specific range
calculation.

### 3. Add the standalone view

Add `PerformanceWaterfallView extends AbstractHistoricView` under the UI views
package and register it in `Navigation#createPerformanceSection`.

Model its lifecycle after `PerformanceChartView`:

- Restore view preferences in `@PostConstruct`.
- Construct a filtered `ClientPerformanceSnapshot` for the selected interval.
- Rebuild the breakdown and chart from `reportingPeriodUpdated()` and
  `notifyModelUpdated()`.
- Forward instrument selection to `SelectionService` if the information pane is
  enabled.

Add labels to `Messages.java` and `messages.properties`. Reuse
`Images.VIEW_BARCHART` initially.

### 4. Add controls and preferences

Toolbar controls:

- Reporting period inherited from `AbstractHistoricView`.
- Client filter.
- Calculation / Instruments mode.
- FIFO / moving-average capital-gains method.
- Pre-tax toggle.
- Top-N selector for instrument mode.
- CSV and image export.

Suggested preference keys:

- `PerformanceWaterfallView-mode`
- `PerformanceWaterfallView-capital-gain-method`
- `PerformanceWaterfallView-pre-tax`
- `PerformanceWaterfallView-top-n`

Do not persist the reporting period separately; `AbstractHistoricView` and
`PortfolioPart` already own it.

Filter the client before constructing `ClientPerformanceSnapshot`. Do not copy
the filtered start/end snapshot FIXME currently documented in `PerformanceView`.

### 5. Tests

Add core tests for:

- Exact category reconciliation to final value.
- Instrument contributions summing to absolute performance.
- Realized and unrealized gains after partial sales.
- Gross dividends with separate fees and taxes.
- Fee and tax refunds.
- Interest charges and earnings without a security.
- Deposits, removals, and delivery transfers.
- Foreign-currency cash and transfer rounding.
- Foreign-security capital-gain decomposition without double-counting FX.
- FIFO versus moving average.
- Filtered clients and cross-portfolio transfers.
- Empty, zero, all-negative, and zero-crossing datasets.
- Top-N grouping without changing the total.

Add UI-side tests for cumulative start/end calculation, bar kinds, sign-based
styles, range calculation, and hit rectangles. Perform manual visual checks on
supported operating systems, themes, scaling levels, and large portfolios.

The existing snapshot has short-sale cases that do not reconcile. If the
breakdown detects a mismatch, show a warning instead of drawing a misleading
final bridge.

## Relevant existing files

- `name.abuchen.portfolio/src/name/abuchen/portfolio/snapshot/ClientPerformanceSnapshot.java`
- `name.abuchen.portfolio.tests/src/name/abuchen/portfolio/snapshot/ClientPerformanceSnapshotTest.java`
- `name.abuchen.portfolio.ui/src/name/abuchen/portfolio/ui/views/PerformanceView.java`
- `name.abuchen.portfolio.ui/src/name/abuchen/portfolio/ui/views/PerformanceChartView.java`
- `name.abuchen.portfolio.ui/src/name/abuchen/portfolio/ui/editor/Navigation.java`
- `name.abuchen.portfolio.ui/src/name/abuchen/portfolio/ui/util/chart/PlainChart.java`
- `name.abuchen.portfolio.ui/src/name/abuchen/portfolio/ui/util/chart/BarChart.java`
- `name.abuchen.portfolio.ui/src/name/abuchen/portfolio/ui/util/chart/ChartContextMenu.java`
- `name.abuchen.portfolio.ui/src/name/abuchen/portfolio/ui/util/chart/AbstractChartToolTip.java`

## Completion criteria

- Waterfall appears as a separate Performance navigation item.
- Both modes update with reporting period, filter, pre-tax setting, and cost
  method.
- Calculation mode reconciles exactly to final value.
- Instrument mode reconciles exactly to absolute performance.
- Positive and negative floating bars, totals, refunds, and zero crossings render
  correctly.
- Large portfolios remain readable through top-N grouping.
- Tooltip, selection, CSV export, and image export work.
- Relevant focused core and UI tests pass.
- No new chart dependency or target-platform change is introduced.

## Plan lifecycle

Keep this file on the feature branch while the implementation is in progress.
Update it if accounting or UI decisions change. Delete it in the final
implementation commit unless the document has become useful as permanent
developer documentation. The deleted plan remains available in Git history.
