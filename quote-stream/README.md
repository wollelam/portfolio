# Quote stream pilot

The **Live quotes** page is a watchlist experiment, independent of saved portfolio valuations.
It uses the same chart component as portfolio and instrument charts. Simulation is clearly
labelled and makes no broker connection. A configured Gateway collector supports read-only
contract qualification and streaming prices for up to ten stock/ETF or FX entries.

The web process reads a private `latest.json` snapshot; it has no broker connection or credentials.
One collector owns subscriptions, shared across every browser tab. It publishes once a second,
retains at most 90 observed last-price samples per instrument, and never writes a portfolio.
The browser polls the existing protected `/api/price-updates` route once a second while viewing
the page. Other portfolio pages retain their usual refresh behavior.

## Simulation

Build the small standalone collector image from this directory:

```sh
docker build -t portfolio-web-quotes:pilot quote-stream
```

Create a private `live-quotes/` directory in the web runtime state, owned by the app's user, then run:

```sh
docker run --detach --restart unless-stopped --name portfolio-web-quotes \
  --init --read-only --cap-drop ALL --security-opt no-new-privileges \
  --network none --user "$(id -u):$(id -g)" --memory 128m \
  -v /path/to/web-state/live-quotes:/state \
  portfolio-web-quotes:pilot --demo --directory /state
```

Enable `PP_WEB_LIVE_QUOTES=1` when launching `portfolio-web.sh`, retaining the instance's existing
source, file-root, price-update and state settings. Open `#/live-quotes`. Demo instruments, prices,
and feed-permission examples are fabricated. **No IBKR subscription or login is used.**

## Gateway mode

Install/run IB Gateway or TWS separately, log in, enable the socket API and keep **Read-Only API**
enabled in Gateway. Give this collector a unique nonzero client ID. The pinned `ib_async==2.1.0`
client connects through its quote protocol without initial account, position, execution or order
synchronization. Its automatic account-summary recovery callback is disabled and covered by a
compatibility test. Gateway's read-only setting provides the additional server-side order guard.
No order endpoints or order construction are implemented in the collector or web app.

Copy `example-config.json` to a private, mode-0600 administrative file outside the web-writable
state. Configure the Gateway host/port and a small watchlist. The example uses the usual paper
Gateway port; verify the actual instance's port. Authentication stays entirely in Gateway; the
config rejects username/password fields. Do not put passwords in a command or commit a personal
watchlist. Run the image with `--config /config/gateway.json --directory /state`, mounting that
private config read-only and choosing a private network that can reach Gateway. A Gateway on host
loopback can be reached using host networking; the collector itself opens no listening ports.
Restrict Gateway API access to the collector, and keep the web container off that API network.

Contracts are qualified before subscription and checked against the configured symbol, currency,
security type and primary exchange. Ambiguous/mismatched contracts stay unresolved. An optional
positive `con_id` can pin a previously verified contract. Duplicate qualified contracts on the same route share one
subscription. Market data type 3 requests delayed data where available; IBKR may supply real-time
data when entitled. Mode 1 requests real-time. Neither mode purchases subscriptions. Regulatory
snapshots are disabled. Availability and market-data-line limits depend on the logged-in username.

Market-data type and received time are shown separately. Receipt time is when the collector saw
an update, not the exchange's trade timestamp. Older individual quotes and missing collector
heartbeats are marked stale. Green/red tick pulses and arrows indicate change since the previous received price; flat prices
return to neutral and reduced-motion preferences disable the pulse. A failed/corrupt update preserves last accepted prices with an
unavailable indication. Gateway disconnections use reconnect backoff and rebuild subscriptions;
login/2FA and server-side permissions remain operational prerequisites. Raw broker error strings
and account identifiers are not included in the web-facing snapshot.

This pilot does **not** apply quotes to portfolio performance, infer holdings from IBKR, or persist
quotes to the Google Drive portfolio. The next step after validating real Gateway data is reviewed
portfolio-security mapping, then an explicitly labelled live valuation overlay using the native
calculation engine and known position/FX timestamps.

## Gateway container option

A verified community packaging project is
[gnzsnz/ib-gateway-docker](https://github.com/gnzsnz/ib-gateway-docker), documenting
`ghcr.io/gnzsnz/ib-gateway:stable`. Pin a reviewed release/digest for deployment. Its current
container relay ports are 4003 (live) and 4004 (paper); its sample maps these to host loopback
4001 and 4002. On a private Docker network, the collector can reach `ib-gateway:4003` without
publishing an API port on the host. VNC is optional, disabled without a password, and should be
bound only to loopback if published. Password-file/Compose-secret settings are supported.
Set `READ_ONLY_API=yes`; authentication and 2FA are still required. This pilot does not start
or configure a Gateway container or store broker credentials.

## Verification and retirement

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s quote-stream
# Run inside the image for the pinned-library recovery compatibility test too:
docker run --rm -v "$PWD/quote-stream:/work:ro" -w /work \
  --entrypoint python portfolio-web-quotes:pilot -B -m unittest -v test_collector
```

Stop/remove the collector container and relaunch the web app without `PP_WEB_LIVE_QUOTES=1` to
retire the pilot. Remove its cache only after the collector stops. Keep any real Gateway config
private while in use; it is separate from the cache and never mounted into the web app.

References: [ib_async](https://github.com/ib-api-reloaded/ib_async),
[IBKR streaming data](https://www.interactivebrokers.com/docs/tws-api/doc/md/streaming-data),
[IBKR read-only API setup](https://interactivebrokers.github.io/tws-api/initial_setup.html).
