"""Quote-only IBKR pilot; simulation runs without a broker connection or credentials."""
import argparse
import asyncio
from collections import deque
import datetime as dt
import fcntl
import json
import logging
import math
import os
from pathlib import Path
import signal
import tempfile
import uuid

UTC = dt.timezone.utc
HISTORY_LIMIT = 90
MAX_INSTRUMENTS = 100
DATA_TYPES = {1: 'real_time', 2: 'frozen', 3: 'delayed', 4: 'delayed_frozen'}
DEMO = (
    ('demo-aapl', 'Apple', 'AAPL', 'USD', 210.0),
    ('demo-msft', 'Microsoft', 'MSFT', 'USD', 430.0),
    ('demo-spy', 'S&P 500 ETF', 'SPY', 'USD', 570.0),
    ('demo-eurusd', 'EUR / USD', 'EUR.USD', 'USD', 1.08),
    ('demo-unavailable', 'Unavailable feed example', 'DEMO', 'USD', None),
)


def timestamp(value):
    return value.astimezone(UTC).isoformat().replace('+00:00', 'Z')


def positive(value):
    return float(value) if isinstance(value, (float, int)) and not isinstance(value, bool) and math.isfinite(value) and value > 0 else None


def write_snapshot(directory, frame):
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, name = tempfile.mkstemp(prefix='pending-', dir=directory)
    try:
        with os.fdopen(fd, 'w') as output:
            json.dump(frame, output, allow_nan=False)
        os.replace(name, directory / 'latest.json')
    finally:
        Path(name).unlink(missing_ok=True)


def demo_frame(now, session):
    quotes = []
    for index, (key, name, symbol, currency, base) in enumerate(DEMO):
        def price(instant):
            return round(base * (1 + .002 * math.sin(instant.timestamp() / 9 + index)), 6)
        history = [] if base is None else [
            {'time': timestamp(now - dt.timedelta(seconds=offset)), 'value': price(now - dt.timedelta(seconds=offset))}
            for offset in range(HISTORY_LIMIT - 1, -1, -1)]
        last = history[-1]['value'] if history else None
        quotes.append({'key': key, 'name': name, 'symbol': symbol, 'currency': currency, 'secType': 'CASH' if index == 3 else 'STK', 'conId': None,
                       'marketDataType': 'delayed' if index == 3 else 'real_time',
                       'status': 'unavailable' if base is None else 'active',
                       'receivedAt': None if last is None else timestamp(now), 'last': last,
                       'bid': None if last is None else round(last * .9999, 6),
                       'ask': None if last is None else round(last * 1.0001, 6), 'previousClose': base, 'dayHigh': None if base is None else round(base * 1.01, 6),
                       'dayLow': None if base is None else round(base * .99, 6), 'history': history})
    return {'schemaVersion': 1, 'mode': 'demo', 'session': session, 'generatedAt': timestamp(now),
            'connected': False, 'state': 'running', 'error': None, 'quotes': quotes}


def read_config(path):
    if path.stat().st_size > 65536:
        raise ValueError('Configuration is too large')
    config = json.loads(path.read_text())
    allowed = {'host', 'port', 'client_id', 'market_data_type', 'instruments'}
    if not isinstance(config, dict) or set(config) - allowed:
        raise ValueError('Unknown configuration fields; credentials belong in Gateway, not this collector')
    if not isinstance(config.get('host', '127.0.0.1'), str) or not config.get('host', '127.0.0.1'):
        raise ValueError('Gateway host is required')
    for field, default, lower, upper in [('port', 4002, 1, 65535), ('client_id', 43, 1, 999999), ('market_data_type', 3, 1, 4)]:
        value = config.get(field, default)
        if type(value) is not int or not lower <= value <= upper:
            raise ValueError('Invalid connection configuration')
        config[field] = value
    instruments = config.get('instruments')
    if not isinstance(instruments, list) or not 1 <= len(instruments) <= MAX_INSTRUMENTS:
        raise ValueError(f'Configure between one and {MAX_INSTRUMENTS} instruments')
    keys = set()
    for row in instruments:
        if not isinstance(row, dict) or set(row) - {'key', 'name', 'symbol', 'currency', 'exchange', 'primary_exchange', 'sec_type', 'con_id'}:
            raise ValueError('Invalid instrument fields')
        for field in ('key', 'name', 'symbol', 'currency', 'exchange'):
            if not isinstance(row.get(field), str) or not 1 <= len(row[field]) <= 100:
                raise ValueError('Instrument identity, currency and venue are required')
        if row['key'] in keys or len(row['currency']) != 3:
            raise ValueError('Duplicate instrument key or invalid currency')
        keys.add(row['key'])
        if row.get('sec_type', 'STK') not in ('STK', 'CASH'):
            raise ValueError('This pilot supports stocks/ETFs and FX only')
        if 'con_id' in row and (type(row['con_id']) is not int or row['con_id'] <= 0):
            raise ValueError('Contract ID must be positive')
    return config


class GatewayFeed:
    def __init__(self, config, session):
        self.config = config
        self.session = session
        self.connected = False
        self.state = 'connecting'
        self.error = None
        self.rows = {}
        self.histories = {}
        self.venues = {}
        for item in config['instruments']:
            key = item['key']
            self.rows[key] = {'key': key, 'name': item['name'], 'symbol': item['symbol'], 'currency': item['currency'],
                              'secType': item.get('sec_type', 'STK'),
                              'conId': item.get('con_id'), 'marketDataType': 'unknown', 'status': 'waiting',
                              'receivedAt': None, 'last': None, 'bid': None, 'ask': None, 'previousClose': None, 'dayHigh': None, 'dayLow': None, 'history': []}
            self.histories[key] = deque(maxlen=HISTORY_LIMIT)
            self.venues[key] = item['exchange']

    def frame(self, now):
        return {'schemaVersion': 1, 'mode': 'ibkr', 'session': self.session, 'generatedAt': timestamp(now),
                'connected': self.connected, 'state': self.state, 'error': self.error, 'quotes': list(self.rows.values())}

    def update(self, key, ticker, now):
        row = self.rows[key]
        factor = 100 if row['currency'] == 'GBX' else 1
        def price(value):
            valid = positive(value)
            return positive(valid * factor) if valid is not None else None
        row.update(last=price(ticker.last), bid=price(ticker.bid), ask=price(ticker.ask),
                   previousClose=price(getattr(ticker, 'close', None)),
                   dayHigh=price(getattr(ticker, 'high', None)), dayLow=price(getattr(ticker, 'low', None)),
                   marketDataType=DATA_TYPES.get(ticker.marketDataType, 'unknown'))
        if any(row[field] is not None for field in ('last', 'bid', 'ask')):
            row['receivedAt'] = timestamp(now)
            row['status'] = 'active'
            if row['last'] is not None:
                samples = self.histories[key]
                # Publish at most one sample per received second, not every exchange tick.
                sample = {'time': timestamp(now.replace(microsecond=0)), 'value': row['last']}
                if samples and samples[-1]['time'] == sample['time']:
                    samples[-1] = sample
                else:
                    samples.append(sample)
                row['history'] = list(samples)

    async def connect(self, ib, contract_class):
        # Use the quote protocol directly: no high-level position, order, execution or account synchronization.
        await ib.client.connectAsync(self.config.get('host', '127.0.0.1'), self.config['port'],
                                     self.config['client_id'], timeout=8)
        ib.reqMarketDataType(self.config['market_data_type'])
        self.connected = True
        self.state = 'connected'
        self.error = None
        subscribed = {}
        for item in self.config['instruments']:
            key = item['key']
            self.rows[key]['status'] = 'waiting'
            broker_currency = 'GBP' if item['currency'] == 'GBX' else item['currency']
            contract = contract_class(secType=item.get('sec_type', 'STK'), symbol=item['symbol'],
                                      currency=broker_currency, exchange=item['exchange'],
                                      primaryExchange=item.get('primary_exchange', ''), conId=item.get('con_id', 0))
            try:
                qualified = await asyncio.wait_for(ib.qualifyContractsAsync(contract), timeout=12)
            except Exception:
                self.rows[key]['status'] = 'unresolved'
                continue
            if len(qualified) != 1 or qualified[0] is None:
                self.rows[key]['status'] = 'unresolved'
                continue
            actual = qualified[0]
            if (actual.currency != broker_currency or actual.secType != item.get('sec_type', 'STK')
                    or actual.exchange != item['exchange']
                    or actual.symbol != item['symbol'] or actual.conId <= 0
                    or item.get('con_id', actual.conId) != actual.conId
                    or item.get('primary_exchange') and actual.primaryExchange != item['primary_exchange']):
                self.rows[key]['status'] = 'unresolved'
                continue
            self.rows[key]['conId'] = actual.conId
            subscription = (actual.conId, actual.exchange)
            if subscription not in subscribed:
                subscribed[subscription] = ib.reqMktData(actual, '', False, False)
            ticker = subscribed[subscription]
            ticker.updateEvent += lambda current, row_key=key: self.update(row_key, current, dt.datetime.now(UTC))

    def handle_error(self, req_id, code, message, contract=None):
        # Do not copy broker error text or account identifiers into the web-facing file.
        if code in (354, 10186):
            for key, row in self.rows.items():
                if (contract is not None and row['conId'] == contract.conId
                        and getattr(contract, 'exchange', None) in (None, '', self.venues[key])):
                    row['status'] = 'not_subscribed'
        elif code in (1100, 1101, 1300):
            self.error = 'Broker connection interrupted; reconnecting.'
        elif code == 2103:
            self.error = 'A market-data connection is interrupted; received quote times remain visible.'


async def wait_or_stop(stop, seconds):
    try:
        await asyncio.wait_for(stop.wait(), timeout=seconds)
    except asyncio.TimeoutError:
        pass


async def run_demo(directory, stop, session):
    while not stop.is_set():
        write_snapshot(directory, demo_frame(dt.datetime.now(UTC), session))
        await wait_or_stop(stop, 1)
    frame = demo_frame(dt.datetime.now(UTC), session)
    frame['state'] = 'stopped'
    write_snapshot(directory, frame)


async def run_gateway(directory, config, stop, session):
    from ib_async import Contract
    # Avoid third-party logs containing broker account/session details.
    logging.getLogger('ib_async').setLevel(logging.CRITICAL)
    feed = GatewayFeed(config, session)

    async def publish():
        try:
            while not stop.is_set():
                write_snapshot(directory, feed.frame(dt.datetime.now(UTC)))
                await wait_or_stop(stop, 1)
        except OSError:
            stop.set()
            raise

    publisher = asyncio.create_task(publish())
    backoff = 1
    try:
        while not stop.is_set():
            ib = quote_client()
            def error(req_id, code, message, contract=None):
                feed.handle_error(req_id, code, message, contract)
                if code in (1100, 1101, 1300):
                    ib.disconnect()
            ib.errorEvent += error
            try:
                feed.state = 'connecting'
                await feed.connect(ib, Contract)
                backoff = 1
                while ib.isConnected() and not stop.is_set():
                    await wait_or_stop(stop, 1)
            except Exception:
                feed.error = 'Gateway unavailable or contract setup failed; retrying.'
            finally:
                ib.disconnect()
                feed.connected = False
                feed.state = 'disconnected'
            await wait_or_stop(stop, backoff)
            backoff = min(backoff * 2, 30)
    finally:
        stop.set()
        await publisher
        feed.state = 'stopped'
        feed.connected = False
        write_snapshot(directory, feed.frame(dt.datetime.now(UTC)))


def quote_client():
    from ib_async import IB
    ib = IB()
    # ib_async 2.1.0's default recovery callback requests account summaries on error 1102.
    # This collector only needs contract definitions and market data, including after recovery.
    ib.errorEvent -= ib._onError
    return ib


async def main_async(args):
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)
    session = str(uuid.uuid4())
    if args.demo:
        await run_demo(args.directory, stop, session)
    else:
        await run_gateway(args.directory, read_config(args.config), stop, session)


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--demo', action='store_true')
    mode.add_argument('--config', type=Path, help='Private, quote-only Gateway configuration (no credentials)')
    parser.add_argument('--directory', type=Path, required=True, help='Private directory for latest.json')
    args = parser.parse_args()
    args.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(args.directory, 0o700)
    with (args.directory / 'collector.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        asyncio.run(main_async(args))


if __name__ == '__main__':
    main()
