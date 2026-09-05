# Portfolio Performance CLI prototype

This is a deliberately narrow JLine prototype. It runs as the Equinox
application `name.abuchen.portfolio.cli.application`, loads files with the
production `ClientFactory` implementation, and only persists when `STORE` is
explicitly issued.

It supports the following interactive commands:

Interactive output uses terminal-aware colour: positive values are green,
negative values and errors are red, and report headings and prompts use cyan.
Generated report lines also have a subtle left rail to distinguish them from
commands. Colours are disabled for dumb terminals and when `NO_COLOR` is set.

Start a daily review with `SUMMARY`. With no period or date options, it uses the
most recent trading day. It shows total value,
TTWROR, performance in the base currency, net deposits, cash, earnings, costs,
the five largest positions, and the five strongest positive and negative security
contributors in portfolio currency. Use `DATA` for quote, FX, and calculation
diagnostics.

Contributor rows show the instrument's own absolute return, its signed
annualized IRR, portfolio-currency contribution, and its impact on the portfolio
in percentage points. Portfolio impact allocates the portfolio's TTWROR proportionally to each
signed currency contribution. This makes the complete contribution breakdown
reconcile to the reported portfolio return without allowing deposits or a small
opening balance to inflate the result. It is shown as `n/a` when total currency
performance is zero.

`SUMMARY` reports both TTWROR and annualized IRR. TTWROR removes the effect of
external cash-flow timing; IRR is money-weighted and therefore reflects when
and how much the investor deposited or withdrew.

Drill down with `SEC "Apple" YTD`: exact names and identifiers take precedence,
ambiguous searches list candidates, and the report shows shares, weight,
value, dated quotes, dividends, fees, taxes and period gains. Quote selection
excludes prices after the report end date.

Search the ledger with:

```
TXN YTD --security "Apple" --type BUY
TXN 1M --account "Brokerage"
TXN 2Y --type DIVIDENDS
```

Filters can be combined with `--from` and `--to`. Linked purchases/sales appear
once using the portfolio transaction. Unlinked cash transactions remain visible.
The ledger includes original/base amounts, fees, taxes, notes and a result count.
`1M`, `3M`, etc. now mean trailing calendar months for all period-aware commands.

```
OPEN <file>
SUMMARY [period]
RELOAD
QUPD
STORE
VAL [YYYY-MM-DD]
HOLD [YYYY-MM-DD]
PERF [period] [--from DATE] [--to DATE]
TPERF [period] [--from DATE] [--to DATE] [--limit N]
SEC <ticker|name> [period]
FX [period]
ALLOC [period]
INCOME [period]
TXN [period]
DATA [period]
CHK
HELP
EXIT
```

`OPEN` supports quoted paths and prompts securely for encrypted files. `QUPD`
fetches latest quotes into memory but never writes the client file. `VAL` and
`HOLD` uses `ClientSnapshot`. `PERF` reports the dashboard-style portfolio
performance breakdown. `TPERF` ranks current holdings by both cumulative
TTWROR and portfolio-currency performance using the core performance engine.
Purchases and other buy-ins are excluded from the currency-performance figure.
`RELOAD` discards in-memory quote updates. `STORE` saves the loaded file using
the same production `ClientFactory.save` writer used by the GUI, preserving its
existing format and encryption settings. It first creates or replaces a
sibling `.backup` file, matching the GUI's default Save protection. Each
ranking row shows both measures. `CHK` invokes registered core consistency
checks.

The percentage beside portfolio-currency performance is the matching absolute
performance percentage (`DeltaPercent`), not TTWROR. This keeps its sign and
basis consistent with the currency amount. Percentage-ranked sections continue
to show TTWROR.

`PERF` includes the dashboard-style breakdown: opening and ending value,
unrealized and realized capital gains, earnings, fees, taxes, currency gains,
net transfers, and total performance.

All period-aware commands default to the most recent trading day when no period,
`--from`, or `--to` is supplied. This uses the same configured trading calendar
as the GUI, including weekends and exchange holidays. Explicit periods continue
to use their documented calendar ranges.

Negative periods select completed calendar periods: `-1D` is yesterday, `-2W`
is the Monday–Sunday week two weeks ago, `-1M` is the previous calendar month,
and `-1Y` is the previous calendar year. With `--to DATE`, that date is used as
the reference point. Positive `nD`, `nW`, `nM`, and `nY` periods remain trailing
ranges—for example `2D`, `1W`, `7D`, `2Y`, or `5Y`.
`PERF YTD` reports from the end of the preceding calendar year through today.
`PERF MTD` reports from the end of the preceding calendar month through today.

## Build and launch

The bundle and its test fragment are registered in the Maven/Tycho reactor,
and JLine 3.30.0 is part of both target definitions. Build the prototype with:

```
mvn -f portfolio-app/pom.xml -Plocal-dev -DskipTests \
  -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,\
      :name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio,\
      :name.abuchen.portfolio.cli -am package
```

In Eclipse, import the CLI project and run the generated
`PortfolioPerformance_CLI` launch configuration. It starts the Equinox
application directly without the SWT workbench. A distributable native
launcher is intentionally left for the packaging milestone.

For a terminal-only development launch on a machine with Docker, run these
commands from the `portfolio-cli` worktree:

```
./portfolio-cli.sh
```

The script creates the Maven cache volume and builds the development runtime on
first use. After changing the CLI source, rebuild before launching with:

```
./portfolio-cli.sh --rebuild
```

Pass a client file to open it immediately. The script mounts the file's
directory into the container, so paths may be relative to the directory from
which you launch it. This also permits `STORE` to save in-memory updates:

```
./portfolio-cli.sh local-portfolios/PortOle.portfolio
./portfolio-cli.sh --rebuild /path/to/client.portfolio
```

The equivalent manual commands are:

```
docker volume create portfolio-cli-m2

docker run --rm \
  -v "$PWD:/workspace" -v portfolio-cli-m2:/root/.m2 \
  -w /workspace maven:3.9.11-eclipse-temurin-21 \
  mvn -q -f portfolio-app/pom.xml -Plocal-dev \
  -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,\
      :name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio,\
      :name.abuchen.portfolio.cli,:name.abuchen.portfolio.cli.tests \
  -am verify

docker run --rm -it \
  -v "$PWD:/workspace" -v portfolio-cli-m2:/root/.m2 \
  -w /workspace maven:3.9.11-eclipse-temurin-21 \
  java -Dosgi.clean=true \
  -jar /root/.m2/repository/p2/osgi/bundle/org.eclipse.equinox.launcher/1.7.100.v20251111-0406/org.eclipse.equinox.launcher-1.7.100.v20251111-0406.jar \
  -data /tmp/portfolio-cli-data \
  -configuration /workspace/name.abuchen.portfolio.cli.tests/target/work/configuration \
  -application name.abuchen.portfolio.cli.application -consoleLog
```

The first command builds and tests the prototype while assembling a temporary
Equinox development runtime. Subsequent launches only need the final `docker
run` command until the build output is cleaned.

## Prototype boundaries

The shell persists quote updates only when `STORE` is explicitly issued.
Transactions, `SAVE AS`, scripting, and a standalone packaged launcher are not
implemented yet. Valuation uses the exchange-rate data available to the
Equinox runtime workspace.
