import asyncio
import datetime as dt
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, patch

import collector


class Event:
    def __init__(self):
        self.handlers = []

    def __iadd__(self, handler):
        self.handlers.append(handler)
        return self


class CollectorTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.now = dt.datetime(2026, 10, 23, 12, 0, tzinfo=collector.UTC)
        self.config = {'host': '127.0.0.1', 'port': 4002, 'client_id': 43, 'market_data_type': 3,
                       'instruments': [{'key': 'apple', 'name': 'Apple', 'symbol': 'AAPL', 'currency': 'USD',
                                        'exchange': 'SMART', 'primary_exchange': 'NASDAQ'}]}

    def test_demo_is_explicitly_simulated_and_prices_move_without_a_broker(self):
        first = collector.demo_frame(self.now, 'test')
        second = collector.demo_frame(self.now + dt.timedelta(seconds=1), 'test')
        self.assertEqual('demo', first['mode'])
        self.assertFalse(first['connected'])
        self.assertEqual(5, len(first['quotes']))
        self.assertNotEqual(first['quotes'][0]['last'], second['quotes'][0]['last'])
        self.assertEqual(90, len(first['quotes'][0]['history']))
        self.assertIsNone(first['quotes'][-1]['last'])
        json.dumps(first, allow_nan=False)

    def test_atomic_snapshot_is_private_and_has_no_pending_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            collector.write_snapshot(directory, collector.demo_frame(self.now, 'test'))
            self.assertEqual(0o600, (directory / 'latest.json').stat().st_mode & 0o777)
            self.assertEqual('demo', json.loads((directory / 'latest.json').read_text())['mode'])
            self.assertEqual(['latest.json'], [p.name for p in directory.iterdir()])

    def test_config_rejects_credentials_and_unbounded_subscriptions(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'config.json'
            path.write_text(json.dumps(self.config))
            self.assertEqual(4002, collector.read_config(path)['port'])
            for bad in [dict(self.config, password='never-in-the-web-app'),
                        dict(self.config, client_id=0),
                        dict(self.config, instruments=self.config['instruments'] * 11)]:
                path.write_text(json.dumps(bad))
                with self.assertRaises(ValueError):
                    collector.read_config(path)

    async def test_connect_only_qualifies_contracts_and_requests_shared_non_snapshot_quotes(self):
        self.config['instruments'].append(dict(self.config['instruments'][0], key='same-contract'))
        calls = []
        ticker = SimpleNamespace(updateEvent=Event())
        async def qualify(contract):
            contract.conId = 123
            return [contract]
        fake = SimpleNamespace(client=SimpleNamespace(connectAsync=AsyncMock()),
                               reqMarketDataType=lambda kind: calls.append(('type', kind)),
                               qualifyContractsAsync=qualify,
                               reqMktData=lambda *args: (calls.append(('subscribe', args)) or ticker))
        feed = collector.GatewayFeed(self.config, 'test')
        await feed.connect(fake, lambda **fields: SimpleNamespace(**fields))
        fake.client.connectAsync.assert_awaited_once_with('127.0.0.1', 4002, 43, timeout=8)
        subscriptions = [entry for entry in calls if entry[0] == 'subscribe']
        self.assertEqual(1, len(subscriptions))
        self.assertEqual(('', False, False), subscriptions[0][1][1:])
        self.assertEqual(2, len(ticker.updateEvent.handlers))
        self.assertTrue(feed.connected)
        # Fake has no order, position or account request methods: using any would fail this test.

    async def test_ambiguous_or_wrong_currency_contract_never_subscribes(self):
        for result in ([], [SimpleNamespace(currency='EUR', secType='STK', symbol='AAPL', conId=123)],
                       [SimpleNamespace(currency='USD', secType='STK', symbol='AAPL', conId=123, exchange='NYSE')]):
            fake = SimpleNamespace(client=SimpleNamespace(connectAsync=AsyncMock()), reqMarketDataType=lambda _: None,
                                   qualifyContractsAsync=AsyncMock(return_value=result))
            feed = collector.GatewayFeed(self.config, 'test')
            await feed.connect(fake, lambda **fields: SimpleNamespace(**fields))
            self.assertEqual('unresolved', feed.rows['apple']['status'])

    def test_invalid_prices_are_removed_and_history_is_bounded(self):
        feed = collector.GatewayFeed(self.config, 'test')
        bad = SimpleNamespace(last=float('nan'), bid=-1, ask=float('inf'), marketDataType=3)
        feed.update('apple', bad, self.now)
        self.assertIsNone(feed.rows['apple']['last'])
        self.assertIsNone(feed.rows['apple']['receivedAt'])
        for index in range(100):
            ticker = SimpleNamespace(last=100 + index, bid=99, ask=101, marketDataType=3)
            feed.update('apple', ticker, self.now + dt.timedelta(seconds=index))
        self.assertEqual(90, len(feed.rows['apple']['history']))
        self.assertEqual('delayed', feed.rows['apple']['marketDataType'])
        feed.update('apple', ticker, self.now + dt.timedelta(seconds=99, microseconds=100))
        self.assertEqual(90, len(feed.rows['apple']['history']))

    def test_raw_broker_errors_are_never_forwarded_to_the_web(self):
        feed = collector.GatewayFeed(self.config, 'test')
        feed.rows['apple']['conId'] = 123
        feed.handle_error(1, 354, 'private account identifier', SimpleNamespace(conId=123))
        self.assertEqual('not_subscribed', feed.rows['apple']['status'])
        feed.handle_error(1, 1100, 'private account identifier')
        self.assertNotIn('private account', json.dumps(feed.frame(self.now)))

    @unittest.skipUnless(importlib.util.find_spec('ib_async'), 'Run inside the collector image to check the pinned library')
    async def test_pinned_client_does_not_fetch_account_summary_on_recovery(self):
        with patch('ib_async.IB.reqAccountSummaryAsync', new_callable=AsyncMock) as account:
            ib = collector.quote_client()
            ib.errorEvent.emit(-1, 1102, 'connectivity restored', None)
            await asyncio.sleep(0)
            account.assert_not_called()
            self.assertFalse(ib.isConnected())


if __name__ == '__main__':
    unittest.main()
