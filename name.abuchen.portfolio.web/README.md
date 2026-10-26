# Portfolio Web — first slice

A native browser dashboard served by an independent headless Java/Equinox application. It uses the
same `ClientSnapshot` and `PerformanceIndex` calculations as Portfolio Performance. The desktop app
does not need to be running. The desktop REST API and its access policy are unchanged.

## Try it

With Docker installed, from the repository root:

```sh
./portfolio-web.sh --demo
```

The launcher builds and tests the required bundles on first use, using the existing CLI development
image and Maven cache. Open **http://localhost:8088**. Demo mode creates synthetic data in memory;
it never accesses your portfolio or downloads quotes. Stop the server with Ctrl-C.

```sh
./portfolio-web.sh --file /path/to/portfolio.portfolio
./portfolio-web.sh --rebuild --demo  # after changing source/assets
./portfolio-web.sh --build          # build and test without starting a server
./portfolio-web.sh --demo --port 8090
./portfolio-web.sh --detach --demo  # background container, survives shell exit
```

For an encrypted file, export `PP_WEB_PASSWORD` in the launching shell (for example using a hidden
shell prompt), then unset it when finished. Do not put the password in a command argument. The
launcher passes the environment variable through to Java. The password is retained in the process
environment for that launch; this prototype does not implement an interactive unlock flow.

The launcher publishes only on host loopback. This slice has no login system and is intended for
local use. Remote hosting and reverse proxy support require a separate authentication/deployment
step. Host and Origin checks reject access from unrelated websites; no CORS relaxation is needed.

Runtime state is stored in `${XDG_STATE_HOME:-$HOME/.local/state}/portfolio-web`. Override with
`PP_WEB_STATE_DIR`. The runtime container runs as the launching user's UID/GID, so it can read
user-owned files and FUSE mounts. Build output may still be root-owned. Existing runtime state from
older root-run versions must be made writable by the launching user before migration.
Runtime state contains the Equinox workspace, provider caches, and private recovery copies of
accepted portfolios. Keep it private and retain it while the service uses it. `PP_WEB_PORT` sets
the default port. No JavaScript build dependencies are required.

Detached mode names the container `portfolio-web` (override with `PP_WEB_CONTAINER_NAME`) and uses
`restart: unless-stopped`. Inspect it with `docker logs portfolio-web`, stop with
`docker stop portfolio-web`, and remove the stopped container before launching a replacement with
the same name. The public application still requires a separately managed access-controlled proxy.

## What works

- Total portfolio value, holdings and cash in the file's base currency.
- Cash-flow-adjusted investment gains and TTWROR over the previous trading day and trailing seven
  calendar days, relative to the selected closing date.
- Interactive daily return/value chart, keyboard-accessible date slider, preset and custom periods.
- Reconciliation: value change = net external flows + investment gain/loss.
- Received dividends, gross/net/taxes/fees and original payment currency.
- Portfolio revision, load time, quote dates and calculation/data-quality warnings.
- Automatic file checks, validated snapshot replacement, recovery after a failed sync, and freshness status.
- Benchmark selection and a return-chart overlay using existing security/index price history.
- Top and bottom security performance for a selected period, ranked by TTWROR or portfolio-currency gain/loss.
- An independent quote-stream pilot with an explicit simulation and a small read-only IBKR connector.

By default the closing valuation is today, using the server's current date. The previous-day
comparison uses the core's default trading calendar. The initial chart covers the preceding year. Select explicit dates to change
this; the day/week cards follow the selected closing date. A period runs **after the opening
valuation through the closing valuation**, matching core interval semantics. Monetary JSON fields
are integer hundredths of a currency unit, as in the core; return fields are decimal ratios.
The chart includes every daily core data point, without financial formulas in JavaScript.

## Embedded portfolio icons

Holdings, performers, instrument headers, waterfall bars and allocation tiles reuse the native **Logo** image
attribute from securities and cash accounts. Missing or invalid images show initials in lists and
headers. Chart logos share a white rounded box. Allocation tiles prioritize the logo as space
shrinks: hide the name, then the percentage, then shrink the logo box from 40px to a minimum of
24px (16px image), then omit it. Larger tiles also show the absolute value. Full figures and names
remain available on hover or keyboard focus, even on the smallest tiles. Logos follow the accepted portfolio
snapshot and update through the normal automatic reload. Content fingerprints in icon URLs prevent
the browser from reusing an outdated logo after a change.

Only embedded PNGs are accepted, with bounded file size and dimensions. The server decodes and
re-encodes them in memory, without fetching external URLs or reading image paths. The source
portfolio is never modified. Dashboard JSON contains same-origin icon URLs rather than image data.
`GET /api/icon?id=UUID` (with an optional `v=SHA256` cache version) returns a validated PNG or 404, with `Cache-Control: no-store`. Malformed
queries return 400; other methods are rejected. Keep this exact route behind the same authentication
as the dashboard and GET-only in any reverse-proxy allowlist. No CSP or CORS changes are required.
Synthetic logo/browser regressions are `name.abuchen.portfolio.web.tests/browser/icons.test.cjs`
and `chart-logos.test.cjs`.

## Portfolio scope and allocation playback

Open **Portfolio filters** and check groups to exclude. The options reuse saved native account/portfolio
groups, including pensions where configured, and expose individual securities and cash accounts.
Exclusions apply to native transactions before calculating values, returns, dividends, instrument
activity, rankings and waterfalls. Securities held outside an excluded account remain in scope.
Weighted ownership groups retain the complementary share; overlapping exclusions use the largest
excluded weight once. Benchmarks and FX retain the original portfolio context. Choices travel in
the URL and survive navigation/reload; **Include all groups** restores the entire portfolio.

Allocation playback defaults to daily steps at ten per second, with four and twenty steps per second
available. It buffers 31 daily native position snapshots per request instead of recalculating the
full performance report for each date. Tile geometry transitions locally between dated snapshots;
values and tooltips always refer to the displayed native date. Reduced-motion preferences disable
the transitions. Pause, manual dates, group changes, navigation and hidden tabs stop playback.
The frame cache is bounded and keyed by source revision, FX context and group exclusions.
The existing protected dashboard endpoint accepts `frames=true`, ISO `from`/`to`, and optional
`exclude` IDs for at most 93 daily snapshots. Invalid IDs and out-of-history dates are rejected.

Holdings also shows explicitly mapped IBKR quotes and indicative position values in instrument
currencies. See [quote-stream/README.md](../quote-stream/README.md) for private watchlist preparation
and the remaining authenticated-Gateway setup. Synthetic integration checks are in
`name.abuchen.portfolio.web.tests/browser/live-scope-playback.test.cjs`.

## Security performers

The Performers page calculates per-security results only when opened. It uses the core's security
performance snapshot for each eligible security with portfolio activity or a position at either
period boundary. Percentage return is the security's time-weighted return. Currency gain/loss is
the core delta over the selected opening and closing valuations, accounting for trades, net
dividends, taxes, fees, and refunds in the portfolio's reporting currency. This includes sold
securities with activity during the period.
Each row shows both measures; choose which one orders the positive and negative lists. Neutral
results are omitted from both lists. Missing prices and calculation failures are unavailable, not
zero losses. The 1D preset uses the dashboard's prior trading day opening date; MTD opens on the
last day of the previous month. These are opening valuations, so activity on the opening date is
excluded, consistent with the core period semantics.

Missing or stale prices can produce misleading valuations. Closing holdings show quote dates and
quotes older than seven calendar days generate warnings. This is an initial diagnostic, not a full
historical quote audit. Missing FX in the calculation window is flagged because the core may use
fallback conversion. By default the app loads cached exchange-rate providers from its own workspace.
A fresh runtime may lack FX data present in the desktop workspace; review
the warnings before comparing multi-currency results. Security quotes come from the loaded file.

## Benchmark comparison

Use **Compare with** above the chart to select a security or index with stored prices. The Return
view overlays its price-series return in blue against the portfolio's TTWROR in green. The legend
shows both period returns; the date slider/pointer shows both daily values. Value view preserves the
selection but hides the return overlay. Clearing the selection removes the comparison.

The benchmark starts at zero on the same opening valuation date as the portfolio. Each day uses
the most recent stored price on or before that date, including the latest quote if applicable;
future quotes are never used. A quote must exist on or before the opening date. Missing opening
history or zero/negative prices make the benchmark unavailable rather than silently shortening
the comparison period. Old carried-forward quotes are flagged, and the last quote date is shown.

When a source currency is specified, the core currency converter converts prices to the portfolio
currency using the valuation date's FX rate. Missing FX makes the benchmark unavailable; the core's
fallback rate is not used for a comparison. A currencyless index is displayed in native index units
with an explicit warning that it is not FX-adjusted.

This is growth in the stored price/index series, not a separately reconstructed total return.
Dividends/distributions are not added. A total-return or adjusted-price series may already include
them; the app does not infer that from the instrument's name. Choose the appropriate stored series
for the intended comparison. No new data feed, download, or portfolio edit is performed.

The selector and date filters are preserved during automatic reload. If a selected series is removed
from the source, the portfolio still loads and the selection is marked unavailable until changed.

## Instrument details

Select a security name in Holdings & cash to open its detail panel. The optional
`instrument=SECURITY_UUID` parameter on `GET /api/dashboard` returns that instrument's selected-date
quantity/value, core performance-period delta, gross dividends, TTWROR, and IRR. TTWROR and IRR
are separate native metrics; IRR is annualized by the core. The panel states the selected period.
When no closing quote is available, position valuation and performance metrics are unavailable,
while held quantity and received dividends remain visible. The quote chart uses stored prices in the instrument currency and
excludes dates after the selected closing date; it describes price history, not investment return.
Instruments with no position at the selected date can still show historical activity and dividends.
An unknown or removed UUID is reported in the detail payload without failing the dashboard.

Instrument details also list booked dividends and security transactions for the selected period.
Payments retain original-currency gross, tax, fees and net; the final column converts net paid to
the reporting currency at the payment date. Transactions show their original recorded amount,
quantity, taxes and fees, plus the reporting-currency equivalent. Transfers and deliveries are
recorded valuations, not cash payments. The native security transaction lookup avoids duplicating
the cash side of a buy/sell entry. The lists include closing-date activity and exclude opening-date
activity, future records and other securities. Each table is paged in groups of 25; refreshing
preserves the current page when the instrument and period are unchanged. Missing FX leaves the
original amount readable and the converted amount unavailable, rather than using a fallback rate.

## Quote stream pilot

Open **Live quotes** for an independent watchlist. Simulation uses generated sample prices and
never connects to IBKR. Its prices do not change saved portfolio valuations, rankings or history.
Both daily and intraday charts use the same chart component. Hover/keyboard selection pauses an
intraday chart for inspection; **Follow latest** resumes. The watchlist continues updating.

The separate collector uses pinned `ib_async`, validates contract identity, and publishes a private
snapshot. The web app reads it using `PP_WEB_LIVE_QUOTES=1` and the existing status endpoint.
Missing heartbeat, delayed data, unresolved contracts and stale individual quotes are distinguished.
A real feed needs an authenticated Gateway/TWS session, read-only API and the logged-in user's
market-data permissions. Setup, isolation, testing and retirement are documented in
[quote-stream/README.md](../quote-stream/README.md).

## File ownership and Google Drive

`PortfolioSource` isolates loading from calculations and HTTP hosting. File mode checks SHA-256
every 30 seconds (`PP_WEB_POLL_SECONDS`, range 1–3600). Unchanged content is not deserialized again.
A changed revision must remain identical over checks at least five seconds apart. The replacement
is loaded and its default report calculated before it can replace the active client. A request
captures one complete context, so it cannot mix revisions while an update is being loaded.

Missing files, partial downloads, decryption failures and calculation errors leave the previous
working snapshot available. Successful acceptance atomically replaces a private recovery file
under the runtime state's `snapshots/` directory. Recovery filenames are bound to the configured
source path, so changing sources cannot silently recover a different portfolio. If startup cannot
load the current source, it can serve that source's last accepted copy and report the failure.
The server never saves or alters the original source. Encrypted recovery copies retain the source
file's encryption and use the same password when loaded. Password arrays are cleared after each load.

The browser checks for updates every 30 seconds while visible and when returning to the tab. It
keeps the selected date range, shows the last portfolio update/check times, and reports refresh
failures while retaining existing figures. Checks with unchanged data preserve chart exploration.
With the default polling interval, acceptance typically takes two checks plus the next browser check.
Demo mode does not watch a file. The refresh button reloads the latest accepted server snapshot;
it does not force acceptance of a file that is still being synchronized.

Supply a locally synchronized Google Drive path with `--file`. For a user-owned FUSE submount,
`PP_WEB_FILE_ROOT` can point at a readable parent directory containing it. The root is mounted
read-only; the selected file must remain beneath it. For example:

```sh
PP_WEB_FILE_ROOT=/path/to/sync-root ./portfolio-web.sh --file /path/to/sync-root/mounted-folder/portfolio.portfolio
```

Atomic file replacements are visible because the directory is mounted, rather than a single file.
Direct Google Drive API access remains outside the web process.
Automatic reload reflects what the existing sync and desktop processes save to the source file.

## Price refresh and freshness

The Market data panel distinguishes the last confirmed updater run from stored quote dates.
Coverage counts securities held at the selected valuation date, excluding cash. A successful check
does not guarantee a new quote for every instrument. Partial failures retain earlier prices.

Enable optional controls with `PP_WEB_PRICE_UPDATES=1` in file mode. Demo mode never enables them.
An independent ECB cache under runtime `price-updates/fx/` is refreshed every six hours, with
15-minute failure retries. Downloads are staged and validated before publication; a report captures
one complete rate context. Failed downloads preserve working rates. FX never alters the source file.

**Refresh prices** queues the existing guarded quote-only updater and requests an ECB check.
The web process has no SSH credentials and never writes the portfolio. Install
`scripts/portfolio-price-worker.py` outside the web container on both web and updater hosts.
Create a mode-0600 administrative config outside the mounted runtime state, for example
`~/.config/portfolio-web/price-worker.json`:

```json
{
  "host": "updater-host",
  "remote_script": "/path/to/installed/portfolio-price-worker.py",
  "finance_state": "/path/to/existing-updater-state",
  "receipts": "/path/to/private-bridge-receipts",
  "runner": "/path/to/existing-guarded-quotes-runner.py",
  "source": "/portfolio-input/example.portfolio",
  "schedule": "Existing daily schedule"
}
```

`source` must exactly match the normalized file path inside the container. The runner must accept
`--quotes-only --apply`, share its existing writer lock, back up and validate changes, and emit JSON
with `applied: true` and status `current`, `updated`, or `partial` only after confirmed completion.
No broker posting, mail, or portfolio editing is implemented in this bridge. Keep the existing
automatic quote schedule; do not add another portfolio writer.

Run this once a minute using user cron and explicit paths:

```sh
/usr/bin/python3 /path/to/installed/portfolio-price-worker.py poll \
  --directory /path/to/web-runtime/price-updates \
  --config /path/to/private/price-worker.json
```

Status reads use SSH without running the quote updater. Requests have durable UUID receipts,
a shared writer lock, and a 15-minute cooldown. Interrupted requests are never blindly replayed.
Check uncertain outcomes against the existing updater and its journals. Keep bridge receipts while
the service is active; retire them only after confirming no outstanding updates. A stale worker
heartbeat disables the button. The visible page checks status every ten seconds. Saved quotes
appear after Drive synchronization and the usual validated portfolio reload.

`GET /api/price-updates` only reads status. `POST /api/price-updates` accepts no body or query and
requires the accepted same-origin `Origin` and `X-Portfolio-Action: refresh-prices`. For remote
access, authenticate the entire hostname, explicitly allow this route, and validate the public
HTTPS Origin on POST before rewriting upstream Host/Origin. Keep the other routes GET-only.
To disable controls, remove the worker's own cron entry and relaunch without the environment flag.
Do not remove native updater journals or uncertain bridge receipts.

## Development

The new bundles are `name.abuchen.portfolio.web` and `name.abuchen.portfolio.web.tests`. With Java 21
and Maven, the focused verification command is:

```sh
mvn -f portfolio-app/pom.xml verify -Plocal-dev \
  -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio,:name.abuchen.portfolio.web,:name.abuchen.portfolio.web.tests \
  -am
```

The frontend is small, dependency-free HTML/CSS/JavaScript, packaged as bundle resources. It uses
linkable same-origin hash pages (`#/overview`, `#/performance`, `#/waterfall`, `#/returns`, `#/drawdown`,
`#/allocation`, `#/holdings`, `#/performers`, `#/dividends`, `#/live-quotes`, and
`#/instrument/SECURITY_UUID`) and retains date, benchmark, chart-mode, scroll, and chart-selection
state in browser history. Instrument links are normal anchors, so direct links and opening a detail
in a new tab work without server routes. The Back control returns to Holdings in a direct-open tab.

Both graphs use the same internal `TimeSeriesChart` component for time-based axes, crosshair,
pointer/touch selection, and keyboard navigation. It is bundled locally with no third-party graph
library or CDN dependency. Portfolio benchmark lines retain gaps and use the same chart selection.
A future framework interface can consume `GET /api/dashboard?from=YYYY-MM-DD&to=YYYY-MM-DD&benchmark=SECURITY_UUID`.
The optional `benchmark` parameter selects one series; responses include the available `benchmarks`
and the selected `benchmark` result (or `null`). Benchmark failures are local to that result.
All response fields belong to this prototype; this is separate from the desktop's published `/v1`
contract. Calculations are serialized per immutable loaded client; a background worker constructs
and validates replacements independently. `refresh` metadata is included in the dashboard response.
Price-refresh control is a separate, opt-in endpoint from automatic file reload.

The Waterfall page reuses the core `PerformanceBreakdown` from the desktop waterfall feature.
`GET /api/dashboard?waterfall=calculation` returns the opening-to-closing value bridge, including
external transfers. `waterfall=contributions` attributes absolute performance to securities, shows
the largest 20 by absolute contribution, and retains the rest as Other. Both use FIFO costs and
include tax and fees. Every breakdown must reconcile before it is displayed. Without this optional
parameter the expensive breakdown is not calculated. The chart exposes exact values in a pointer-adjacent tooltip on hover and
keyboard focus (Escape dismisses it), security bars open the instrument detail, and an accessible table lists all amounts.

Table headers sort raw numbers and dates, keep missing values last, and expose `aria-sort`.
Original-currency amounts in instrument activity are grouped by currency before comparing amounts;
reporting-currency columns provide a common basis. Activity sorting happens before pagination.
Binary choices for ranking basis and waterfall breakdown use toggle buttons with an explicit
pressed state rather than dropdowns. Performer entries default to ten per side, with 25, 50 and All options. Rank basis selects the top
and bottom subsets; header sorting changes their display order. Holdings and performers support
name filtering. Sorts, name filters, entry count and waterfall mode travel in the hash URL so that
Back and direct links retain them. Live watchlist sorting uses the current received values.

The Allocation page partitions the existing closing holdings into a binary treemap using absolute
reporting-currency values, including cash. It shows the largest 20 positions and groups the rest by
sign and asset type, preserving both net value and gross exposure. Negative values are striped;
net weights keep their native denominator and are unavailable when net value is zero. Concentration
uses gross exposure, includes cash, and is not a performance measure. Tiles expose exact amounts
on hover/focus and link securities to their detail page. No new data source or API route is used.

The Returns calendar derives monthly and selected-year returns by compounding the endpoints of
the native cumulative TTWROR series. It marks partial periods explicitly, keeps missing returns
unavailable, and guards a zero denominator after a total loss. Month and year cells link to their
exact performance interval. The Drawdown page uses `PerformanceIndex.getDrawdown()` directly,
including its dated series, maximum drawdown interval and longest interval between new highs.
It reuses `TimeSeriesChart`, retains inspected dates in browser history, and considers only peaks
inside the selected range. Durations use calendar days. No volatility annualization is inferred.

The browser regression harness is `name.abuchen.portfolio.web.tests/browser/navigation-chart.test.cjs`.
Run it against a synthetic demo server with Playwright Core available through `NODE_PATH`, for example:

```sh
BASE_URL=http://127.0.0.1:8097 NODE_PATH=/path/to/node_modules node \
  name.abuchen.portfolio.web.tests/browser/navigation-chart.test.cjs
```

The activity browser harness, `name.abuchen.portfolio.web.tests/browser/security-activity.test.cjs`,
uses the same setup and checks original payments, period filtering, trade de-duplication, pagination,
missing FX, empty lists and mobile layout.

Additional synthetic/demo browser harnesses are `waterfall-sorting.test.cjs`,
`returns-calendar.test.cjs`, `drawdown.test.cjs`, and `allocation.test.cjs` in the same directory. They cover bridge
inspection and security navigation, numeric/null sorting, filtering, sorting before pagination,
65 performers per side, compounded/partial/missing calendar returns, zero denominators,
drawdown chart selection, proportional allocation areas, signed/cash exposure, grouped totals,
zero-net-value cases, Back/reload and mobile overflow.

The navigation harness augments the demo response in-browser with synthetic long-list rows and quote shapes. It never
uses a real portfolio or sends a price-refresh POST.

Transaction editing and SaaS
tenancy are outside this slice. The launcher currently uses the Tycho test runtime; a dedicated
distributable server product is a later packaging task.

Preset ranges include 2Y, 5Y, 10Y and MAX. Multi-year periods clamp leap-day boundaries to the
last valid day of the destination month. MAX opens the day before the first booked account or
portfolio transaction, so the initial cash flow is included; future-dated transactions are ignored.
An empty portfolio uses the previous day. The API retains a ten-year bound for empty history but
allows older ranges back to the actual portfolio inception. Presets and toggle states survive Back
and reload through the existing hash URL.

The Value chart overlays `PerformanceIndex.calculateInvestedCapital()` as a dashed staircase:
opening portfolio value plus cumulative external inflows minus outflows. Opening-date flows are
already included in the baseline. The engine includes external security deliveries at their booked
value and excludes internal trades, transfers and dividends from new contributions. Hover/keyboard
inspection displays the baseline and investment gain/loss; the closing gap matches the selected
period's gain. Return mode keeps its existing benchmark comparison. Charts use direct pointer,
touch and keyboard inspection without a date slider.

Allocation uses a single As of date, selected by a daily timeline slider, date picker or Today button.
The timeline spans the opening date before first booked activity through the server's current date.
Dragging is debounced; each historical snapshot uses a one-day native calculation window with the
requested closing date. Pending dates are labelled separately from the currently displayed chart.
The asof hash parameter survives reload and Back. Scrubbing keeps the performance date range intact;
security and holdings links open at the displayed snapshot date. Other graphs retain direct chart
inspection without sliders.

Reviewed non-cash spin-off distributions use `PP-NONCASH-SPINOFF:<acquisition UUID>` notes. The web
app classifies one as non-cash only when the matching purchase has the same account, amount,
currency, timestamp and acquisition marker. These distributions remain in native performance and
are explicitly labelled in instrument transactions, while paid-dividend lists/totals and instrument
paid-dividend gross exclude them. An unmatched or unbalanced marker remains ordinary cash income.

Allocation playback uses an explicit Play/Pause toggle and daily/monthly/yearly advances. Play at
the end restarts from inception; reaching today stops. Each frame waits for its native snapshot
before scheduling the next, and Pause stays available during loading. Manual date changes, leaving
the page, hidden tabs and failed frames stop playback. Back never starts playback automatically.

The Overview total reprices today's explicitly mapped holdings once per second using fresh IBKR
last prices and fresh IBKR FX midpoints, falling back to the valuation date's saved rates.
Positions without a usable stock quote retain stored native prices; foreign holdings and cash
can still receive FX-only adjustments. Unconvertible positions retain stored values. The card labels
quote coverage and delayed/frozen data. Historical dates, longer-period performance metrics and charts continue
to use saved portfolio prices. This indicative overlay never writes prices to the portfolio.

The Live quotes table includes Day %, calculated as `(last / previousClose - 1) × 100`
using IBKR's previous close. It updates with quotes and shows unavailable when either price is
missing. Feed labels still indicate delayed/frozen/stale quotes. GBX close prices use the same
verified price units as last prices.

Day % and the Overview total pulse green/red for 1.3 seconds when their values rise/fall.
A change in portfolio date, currency or selected groups resets the total's comparison baseline.
Reduced-motion preferences disable pulse animations.

Today's Overview total uses fresh IBKR CASH bid/ask midpoints to convert foreign positions and
cash. Direct/inverse pairs and two-leg USD crosses are supported; GBX uses GBP rates divided by
100. Stored stock values also receive FX-only adjustments using their saved conversion rate.
Missing, stale, frozen, crossed, duplicate or mismatched FX quotes fall back to saved conversion
rates. The card reports FX coverage and delayed data; saved portfolio prices and historical
performance are unchanged. FX pairs also appear in the Live quotes watchlist.

The Live quotes table shows sortable Day high and Day low from IBKR's daily high/low
fields. These use broker session data, rather than the collector's short observed history;
missing values remain unavailable. GBX highs/lows use the same verified price units as last prices.

Today's performance follows the same intraday total: the change from the saved closing valuation
is added to the core daily gain. Its time-weighted return uses the native index's prior-day
accumulated factor and closing-day opening capital plus inflows; deposits and withdrawals keep
their native timing treatment. The card is labelled as an intraday estimate. Missing quotes fall
back with the total; historical views, charts and longer-period performance retain saved values.
When the native return denominator is zero, an adjusted return is unavailable rather than guessed.

Waterfall hover/focus uses one custom tooltip with the bar's amount. The cumulative connector
value is not included in the tooltip. Accessible labels are preserved without SVG title elements
that would trigger a second browser-native tooltip.

The daily performance card expands a panel with the five largest security gains and five largest
losses, ranked by monetary gain in the reporting currency. The native daily breakdown is fetched
only while expanded (`rankings=day`); live stock/FX valuation deltas are applied from the same
calculation as the overview total. Closed positions retain native realised results, and missing
base metrics remain unavailable. Scope filters and historical dates use their corresponding
native daily interval. Securities link to their details, and expanding/collapsing is keyboard accessible.

For a RAM-backed live quote cache, set `PP_WEB_LIVE_QUOTES_VOLUME` to an existing shared
tmpfs Docker volume alongside `PP_WEB_LIVE_QUOTES=1`. The web process mounts that cache read-only;
the collector mounts the same volume read/write. See the quote-stream README for setup.

The eye button beside the Overview total enables Discreet mode, masking that amount with bullets
and remembering the browser preference. Live updates continue underneath the mask; copied text
and the amount's accessible label also stay masked. Other performance figures remain visible.
If browser storage is disabled the toggle still works for the current page session.

Displayed live quote prices (including bid/ask, session ranges and chart labels) use at most two
decimal places. Tick pulses compare displayed prices to avoid flashes caused by invisible feed
precision changes; calculations, FX conversion, daily percentage change and quote history retain
the original data precision.

The daily performance card shows the latest processed quote-snapshot time and flags delayed data
or stored-price fallback. Its gain and percentage pulse on displayed changes, making intraday
updates visible even when Discreet mode hides the total. Percentages still use two decimal places,
so small valuation moves may change the monetary gain while the displayed percentage stays flat.

The Overview always requests the server's current valuation date. Historical closing dates from
other pages, saved links or browser history cannot disable live repricing there. Opening dates
and portfolio-group filters are preserved, and the URL is updated to the actual current date.
Historical analysis remains available on the other pages and survives Back/Forward navigation.
Overview background refresh also follows the server date across midnight.
