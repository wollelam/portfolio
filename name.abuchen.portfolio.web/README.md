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

By default the closing valuation is today, using the server's current date. The previous-day
comparison uses the core's default trading calendar. The initial chart covers the preceding year. Select explicit dates to change
this; the day/week cards follow the selected closing date. A period runs **after the opening
valuation through the closing valuation**, matching core interval semantics. Monetary JSON fields
are integer hundredths of a currency unit, as in the core; return fields are decimal ratios.
The chart includes every daily core data point, without financial formulas in JavaScript.

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
linkable same-origin hash pages (`#/overview`, `#/performance`, `#/holdings`, `#/dividends`, and
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

The browser regression harness is `name.abuchen.portfolio.web.tests/browser/navigation-chart.test.cjs`.
Run it against a synthetic demo server with Playwright Core available through `NODE_PATH`, for example:

```sh
BASE_URL=http://127.0.0.1:8097 NODE_PATH=/path/to/node_modules node \
  name.abuchen.portfolio.web.tests/browser/navigation-chart.test.cjs
```

The activity browser harness, `name.abuchen.portfolio.web.tests/browser/security-activity.test.cjs`,
uses the same setup and checks original payments, period filtering, trade de-duplication, pagination,
missing FX, empty lists and mobile layout.

The navigation harness augments the demo response in-browser with synthetic long-list rows and quote shapes. It never
uses a real portfolio or sends a price-refresh POST.

Transaction editing and SaaS
tenancy are outside this slice. The launcher currently uses the Tycho test runtime; a dedicated
distributable server product is a later packaging task.
