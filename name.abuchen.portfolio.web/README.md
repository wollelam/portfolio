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
`PP_WEB_STATE_DIR`. The container runs as root, so generated state and build output may be root-owned.
Runtime state contains the Equinox workspace and any provider caches, not a copy of the portfolio.
It can be removed after stopping the server if those caches are no longer needed. `PP_WEB_PORT`
sets the default port. No JavaScript build dependencies are required.

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

By default the closing valuation is today, using the server's current date. The previous-day
comparison uses the core's default trading calendar. The initial chart covers the preceding year. Select explicit dates to change
this; the day/week cards follow the selected closing date. A period runs **after the opening
valuation through the closing valuation**, matching core interval semantics. Monetary JSON fields
are integer hundredths of a currency unit, as in the core; return fields are decimal ratios.
The chart includes every daily core data point, without financial formulas in JavaScript.

Missing or stale prices can produce misleading valuations. Closing holdings show quote dates and
quotes older than seven calendar days generate warnings. This is an initial diagnostic, not a full
historical quote audit. Missing FX in the calculation window is flagged because the core may use
fallback conversion. The app loads cached exchange-rate providers from its own workspace but does
not refresh them online. A fresh runtime may lack FX data present in the desktop workspace; review
the warnings before comparing multi-currency results. Security quotes come from the loaded file.

## File ownership and Google Drive

`PortfolioSource` isolates loading from calculations and HTTP hosting. A file is loaded once, checked
for concurrent changes using SHA-256 before/after load, and exposed as one read-only revision.
The server never saves or alters the source. Refreshing the dashboard recalculates that snapshot;
**restart the server to read an updated file**.

A locally synchronized Google Drive path can be supplied using `--file` today. Direct Drive API
access and automatic reload are deferred. The next step is polling/watching a stable file, loading a
replacement completely, then atomically swapping the snapshot while retaining the last good revision
if a download/save is incomplete. No desktop/server concurrent writes or live sync protocol is needed
for that model. The loader accepts encrypted files through the core `ClientFactory` implementation.

## Development

The new bundles are `name.abuchen.portfolio.web` and `name.abuchen.portfolio.web.tests`. With Java 21
and Maven, the focused verification command is:

```sh
mvn -f portfolio-app/pom.xml verify -Plocal-dev \
  -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio,:name.abuchen.portfolio.web,:name.abuchen.portfolio.web.tests \
  -am
```

The frontend is small, dependency-free HTML/CSS/JavaScript, packaged as bundle resources. A future
React interface can consume the same `GET /api/dashboard?from=YYYY-MM-DD&to=YYYY-MM-DD` contract.
All response fields belong to this prototype; this is separate from the desktop's published `/v1`
contract. Calculations are serialized against one immutable loaded client. The source boundary is
ready for reload support; there is no snapshot replacement operation yet.

Follow-up slices: benchmark overlays with explicit return/currency semantics, automated snapshot
reload, managed FX/quote refresh, then authenticated remote access. Transaction editing and SaaS
tenancy are outside this slice. The launcher currently uses the Tycho test runtime; a dedicated
distributable server product is a later packaging task.
