# Portfolio Performance CLI feasibility

## Recommendation

Build the CLI on the JVM and reuse the existing model, serialization, quote
feeds, currency conversion, and snapshot/performance calculations. Start with a
small headless Equinox application to prove the commands, then move the
non-visual services that are currently coupled to Eclipse/SWT out of the UI
bundle. Do not independently implement writable `.portfolio` support in another
language.

The useful product is two interfaces over the same command/service layer:

1. a conventional non-interactive CLI for scripts, for example
   `portfolio --file savings.portfolio value --output json`; and
2. a keyboard-driven interactive shell with Bloomberg-style mnemonics, dense
   tables, color, completion, command history, and a persistent status line.

Read-only commands should come first. Quote updates and other mutations should
arrive only after safe-save and concurrent-edit protection exist.

## What is already reusable

This is not primarily a portfolio-file parsing project. Much of the command-line
application already exists as UI-independent business code:

- `ClientFactory` reads and writes seven exposed file variants: XML, XML with ID
  references, zipped XML, AES-128/AES-256 encrypted XML, zipped protobuf, and
  AES-256 encrypted protobuf.
- The binary payload has a checked-in proto3 schema (`model/client.proto`) and a
  handwritten Java mapping (`ProtobufWriter`). The encryption and compression
  envelope is also implemented in `ClientFactory`.
- File loading upgrades historical models through 70 format versions. Unknown
  protobuf `Any` extensions are retained so third-party extension data survives
  a load/save cycle.
- `snapshot/` already calculates total assets, holdings, account and portfolio
  values, performance, IRR, capital gains, dividends, trades, and taxonomy
  groupings.
- Core contains 23 registered quote-feed implementations plus exchange-rate
  providers and security-search providers.
- `ClientSnapshot.create(...).getMonetaryAssets()` is the existing definition of
  total portfolio value, so a CLI can agree exactly with the desktop
  application rather than inventing new accounting semantics.

Some scale indicators from the current tree:

| Area | Size |
| --- | ---: |
| Core Java | 477 files / about 135k lines |
| Snapshot calculations | about 12.5k lines |
| Online/quote support | about 10.9k lines |
| File schema + Java serialization mapping | about 3.7k lines |
| UI quote-update coordinator | about 1.2k lines |

## Headless gaps

Only 16 core source files import Eclipse packages, and six import SWT, but the
bundle manifest makes those dependencies mandatory for the whole bundle. The
couplings that matter to the CLI are:

- `ClientFactory` accepts Eclipse `IProgressMonitor`, uses `Platform` to detect
  macOS, and logs through the Eclipse runtime.
- Core messages use Eclipse NLS and core logging uses the Eclipse platform log.
- exchange-rate providers use `IProgressMonitor`; the ECB cache resolves its
  location through the OSGi runtime;
- a handful of image/color model helpers use SWT;
- OAuth token storage uses Equinox secure preferences;
- the quote feeds are in core, but orchestration, parallel execution, progress,
  authentication prompting, and mutation tracking are implemented by
  `ui.jobs.priceupdate`, including Eclipse Jobs and SWT `Display` calls;
- quote-provider API keys are currently copied from Eclipse preferences into
  service-loaded feed instances by a UI addon.

A proof of concept can retain Equinox (and resolve SWT without displaying a UI),
but that is a packaging shortcut, not the final architecture. A true headless
runtime should introduce small platform-neutral abstractions for progress,
logging, secrets/configuration, and cache locations, with Eclipse and CLI
adapters.

The quote updater should become a core `QuoteUpdateService` using a supplied
executor and progress listener. The current UI job can delegate to it, and the
CLI can run the same service synchronously or show terminal progress. This also
prevents a second implementation of rate limiting, provider grouping, update
policies, and error handling.

## Proposed modules and boundaries

```text
portfolio command definitions
    |-- batch renderer (table / JSON / CSV)
    `-- interactive terminal (history / completion / panels)
             |
             v
       application services
       |-- PortfolioFileService
       |-- ValuationService
       |-- PerformanceService
       |-- QuoteUpdateService
       `-- ValidationService
             |
             v
 existing model, snapshots, money, online feeds, and serializers
```

Create `name.abuchen.portfolio.cli` as an application bundle/module. Keep CLI
formatting and command parsing out of core. Move only reusable orchestration and
platform seams into core (or a small new headless application-services bundle).

For command parsing, picocli fits batch subcommands, aliases, validation,
password prompting, help, exit codes, and shell completion. For the interactive
terminal, JLine supplies line editing, history, completion, key bindings, ANSI
styling, signal handling, and terminal-size awareness. Both have OSGi support or
modular artifacts. A full-screen terminal toolkit is not needed for the first
release: a rich REPL with dense tables will feel terminal-native and still work
well over SSH and in narrow terminals.

Package a JVM application first (platform launchers plus a Java 21 runtime, or
`jpackage`). Defer GraalVM native-image work: XStream reflection, `ServiceLoader`,
resources, crypto, and the HTTP stack all need reachability testing and metadata,
which adds risk without validating the product.

## Command design

The batch and interactive forms should execute identical command objects. Long
names make scripts discoverable; uppercase aliases give the interactive shell
the desired Bloomberg flavor.

| Long command | Interactive alias | Purpose |
| --- | --- | --- |
| `open <file>` | `OPEN` | Open/reload a portfolio; prompt securely if encrypted |
| `value [--date]` | `VAL` | Total value in base or selected currency |
| `holdings [--date]` | `HOLD` | Positions, shares, quote, value, allocation, gain |
| `performance --from ... --to ...` | `PERF` | Absolute/relative result, IRR, fees, taxes, earnings |
| `accounts` / `portfolios` | `ACCT` / `PORT` | Balances and portfolio totals |
| `transactions [filters]` | `TXN` | Search ledger entries by date/type/security/account |
| `security <id>` | `SEC` | Instrument identifiers, feeds, latest quote, history |
| `taxonomy <name>` | `TAXO` | Actual versus target allocation |
| `quotes status` | `QSTAT` | Stale/missing/error quote overview |
| `quotes update` | `QUPD` | Preview and fetch latest and/or historical prices |
| `fx update` | `FXUPD` | Refresh currency conversion data |
| `check` | `CHK` | Run the existing model consistency checks |
| `save` / `reload` | `SAVE` / `RELOAD` | Explicit mutation lifecycle |
| `export` | `EXPORT` | Stable JSON/CSV output for automation |

Useful global behavior:

- `--output table|json|csv`, `--no-color`, predictable exit codes, and no
  progress animation when stdout is not a TTY;
- relative dates and presets such as `YTD`, `1Y`, `MAX`, plus explicit ISO dates;
- selectors by UUID, ISIN, ticker, and exact name, with an ambiguity error rather
  than guessing;
- command completion populated from the currently open client's accounts,
  portfolios, taxonomies, and securities;
- a status line showing file, dirty/read-only state, valuation date, base
  currency, quote freshness, and last command duration;
- F-key or Ctrl-key bindings for the most common views while retaining typed
  commands for portability.

An initial screen can show total value, daily change, stale-quote count, cash,
largest positions, and recent transactions. Selecting a position can drill down
without creating a completely separate navigation model.

## Safe write requirements

The desktop save implementation writes directly to the target file. A CLI makes
simultaneous desktop/CLI access more likely, so writable support needs stronger
guards before it is enabled:

1. hash/stat the source at load time and reject save if it changed;
2. hold a clearly documented sidecar/advisory lock for the editing session;
3. write and fsync a temporary sibling file, re-read and validate it, then use an
   atomic replace where supported;
4. keep a recoverable backup and preserve the source file's original format and
   encryption settings;
5. preserve unknown protobuf extensions and verify round trips with fixtures;
6. never accept a password as a normal command-line argument; use a masked
   terminal prompt, stdin/file descriptor, or a documented secret provider;
7. make `quotes update` show a summary/diff and require an explicit `save` in the
   interactive shell; offer `--write` explicitly for batch automation.

Transaction entry is a later phase than quote updates. Buy/sell and transfers
create linked entries and have validation/accounting rules that deserve their
own command design and regression suite.

## Delivery slices and estimates

Assumptions: one experienced Java developer working full time, familiar with the
repository; estimates include focused automated tests and documentation but not
project governance/review latency. Ranges include roughly 25% uncertainty.

| Slice | Scope | Estimate |
| --- | --- | ---: |
| Technical spike | Headless Equinox launcher; load all current formats; `value` and `holdings`; verify ServiceLoader feeds | 3-5 days |
| Headless seams | progress/log/config/cache abstractions; extract quote orchestration; adapters keep desktop behavior unchanged | 2-4 weeks |
| Read-only CLI MVP | open/value/holdings/accounts/transactions/performance/check; table + JSON/CSV; encrypted prompt | 2-3 weeks |
| Safe writes and quotes | conflict detection, atomic save/backup, latest/history updates, provider config, FX cache, dry-run and tests | 2-4 weeks |
| Bloomberg-style shell | JLine REPL, aliases, completion, history, status line, resize/signal behavior, compact dashboard | 2-3 weeks |
| Distribution hardening | platform packaging, non-TTY behavior, launchers, end-to-end fixtures, docs and release integration | 2-3 weeks |

Some work overlaps, so the likely totals are:

- throwaway but convincing read-only prototype: **1 week**;
- scriptable read-only MVP retaining the Equinox runtime: **3-5 weeks**;
- production-quality Java CLI with quote updates, safe writes, and interactive
  shell: **10-14 person-weeks**;
- polished v1 with transaction entry, richer drill-down views, broad provider
  configuration, and installers on every supported platform: **16-24
  person-weeks**.

Two developers could shorten calendar time, but core-boundary and file-safety
work is mostly serial. UI commands/rendering, packaging, and fixture tests can be
parallelized after the service contracts stabilize.

## Alternative: another language

The protobuf schema makes a narrow binary reader in Go or Rust possible. The
custom wrapper (signatures, ZIP, AES-CBC, PBKDF2), XML/XStream variants, linked
transactions, format migrations, decimal scaling, extension preservation, quote
providers, FX history, and 12.5k lines of calculation logic make a compatible
writable implementation much larger.

Reasonable estimates for a separate implementation are:

- inspect/export raw entities without matching valuations: **3-6 weeks**;
- trustworthy read-only value/holdings/performance across current files:
  **12-20 weeks**;
- safe writes, historical migrations, encryption variants, quote/FX updates,
  and differential compatibility tests: **6-10 months**;
- continued maintenance every time the Java model/schema or calculation rules
  change.

The payoff would be fast startup and a potentially small native binary, but it
would establish two accounting engines and two writable format implementations.
That is a poor trade unless the new client is deliberately a read-only external
tool. Kotlin is viable for CLI code because it can reuse the Java core, but it
does not materially improve the architecture and adds another language/build
tool to this repository.

## Validation plan

- Golden load/save tests for every `ClientFileType`, including wrong passwords
  and historical fixtures.
- Round-trip checks that unknown protobuf extensions and save flags survive.
- Differential snapshot/performance tests comparing CLI output to direct core
  API results for multi-currency, transfers, taxes/fees, stock splits, and stale
  quotes.
- Quote-update tests with deterministic fake feeds covering grouping, throttling,
  partial failures, latest/history merge policies, and cancellation.
- Concurrent-edit, interrupted-write, disk-full, and failed-validation tests.
- Black-box tests for stdout/stderr, JSON schema, exit codes, color/TTY behavior,
  Ctrl+C, resize, and encrypted password input.
- Cross-platform smoke tests on the same OS/architecture matrix used by the RCP
  product.

## Suggested first milestone

Time-box a five-day spike and require these exit criteria:

1. a headless launcher opens XML, zipped protobuf, and encrypted protobuf
   fixtures;
2. `value`, `holdings`, and `check` produce stable table and JSON output;
3. one public and one API-key quote feed can be discovered and invoked without
   starting SWT;
4. results match existing core tests for a multi-currency scenario;
5. the spike documents installed size, cold startup, and the exact dependencies
   blocking a plain JVM launch.

If this passes, keep the command model and tests, begin the headless refactor,
and discard any launcher shortcuts that would leak UI dependencies into the
long-term distribution.

## Library references

- [picocli manual](https://picocli.info/) — nested subcommands, aliases, help,
  completion, interactive password options, exit codes, ANSI, OSGi, and
  packaging.
- [JLine architecture](https://jline.org/docs/architecture/) and [line reader
  guide](https://jline.org/docs/line-reader/) — terminal abstraction, history,
  completion, styling, signals, key bindings, and widgets.
- [GraalVM reachability metadata](https://www.graalvm.org/latest/reference-manual/native-image/metadata/)
  — why reflection, resources, serialization, and service discovery need a
  dedicated later packaging effort.
