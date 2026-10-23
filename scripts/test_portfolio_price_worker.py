"""Regression checks for the quote bridge; never invokes SSH or a real updater."""
import datetime as dt
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid

spec = importlib.util.spec_from_file_location('price_worker', Path(__file__).with_name('portfolio-price-worker.py'))
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


class PriceWorkerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.finance = self.root / 'finance'
        self.finance.mkdir()
        self.receipts = self.root / 'receipts'
        self.mailbox = self.root / 'mailbox'
        self.config = self.root / 'config.json'
        worker.write(self.config, {'source': '/input/test.portfolio', 'schedule': 'Daily'})
        self.identifier = str(uuid.uuid4())

    def request(self, age=0, source='/input/test.portfolio'):
        worker.write(self.mailbox / 'request.json', {
            'id': self.identifier, 'source': source,
            'requestedAt': (worker.now() - dt.timedelta(minutes=age)).isoformat()})

    def test_duplicate_and_interrupted_receipts_never_rerun_writer(self):
        with patch.object(worker, 'execute_guarded', return_value='complete') as execute:
            first = worker.remote(self.finance, self.receipts, Path('/unused'), self.identifier)
            second = worker.remote(self.finance, self.receipts, Path('/unused'), self.identifier)
            self.assertEqual(first['receipt'], second['receipt'])
            self.assertEqual(1, execute.call_count)
            other = str(uuid.uuid4())
            worker.write(self.receipts / (other + '.json'), {'id': other, 'state': 'running'})
            self.assertEqual('running', worker.remote(self.finance, self.receipts, Path('/unused'), other)['receipt']['state'])
            self.assertEqual(1, execute.call_count)

    def test_pending_accounting_or_remote_cooldown_prevents_new_writer(self):
        with patch.object(worker, 'execute_guarded') as execute:
            worker.write(self.finance / 'pending.json', {'private': 'do not expose'})
            result = worker.remote(self.finance, self.receipts, Path('/unused'), self.identifier)
            self.assertEqual('busy', result['receipt']['state'])
            (self.finance / 'pending.json').unlink()
            worker.write(self.receipts / 'last-start.json', {'startedAt': worker.now().isoformat()})
            result = worker.remote(self.finance, self.receipts, Path('/unused'), str(uuid.uuid4()))
            self.assertEqual('busy', result['receipt']['state'])
            execute.assert_not_called()

    def test_status_does_not_forward_private_updater_fields(self):
        worker.write(self.finance / 'quote-last.json', {'status': 'partial', 'provider_failures': 4,
                     'verified_utc': worker.now().isoformat(), 'backup_file_id': 'PRIVATE', 'summary': 'PRIVATE'})
        result = worker.remote(self.finance, self.receipts, Path('/unused'))
        self.assertEqual(4, result['providerFailures'])
        self.assertIsNotNone(result['lastSuccessAt'])
        self.assertNotIn('PRIVATE', json.dumps(result))

    def test_local_poll_dispatches_once_then_only_inspects_uncertain_outcome(self):
        self.request()
        calls = []

        def invoke(config, requested=None, inspect=None, heartbeat=None):
            calls.append((requested, inspect))
            if heartbeat:
                heartbeat()
            return {'available': True, 'sourceBusy': False,
                    'receipt': {'id': self.identifier, 'state': 'unconfirmed'}} if requested or inspect else {'available': True}

        worker.poll(self.mailbox, self.config, invoke)
        worker.poll(self.mailbox, self.config, invoke)
        self.assertEqual(1, sum(requested is not None for requested, _ in calls))
        self.assertEqual(1, sum(inspect is not None for _, inspect in calls))
        self.assertEqual('unconfirmed', worker.read(self.mailbox / 'status.json')['state'])

    def test_expired_or_wrong_source_request_does_not_launch_writer(self):
        for age, source in [(16, '/input/test.portfolio'), (0, '/input/another.portfolio')]:
            with self.subTest(age=age, source=source):
                self.identifier = str(uuid.uuid4())
                self.request(age, source)
                def invoke(config, requested=None, inspect=None, heartbeat=None):
                    self.assertIsNone(requested)
                    return {'available': True}
                worker.poll(self.mailbox, self.config, invoke)
                self.assertIn(worker.read(self.mailbox / 'status.json')['state'], ('failed', 'unconfirmed'))

    def test_connection_failure_preserves_last_confirmed_result(self):
        worker.write(self.mailbox / 'status.json', {'lastSuccessAt': worker.now().isoformat(), 'providerFailures': 4})
        def unavailable(config):
            raise RuntimeError('private SSH diagnostic')
        worker.poll(self.mailbox, self.config, unavailable)
        result = worker.read(self.mailbox / 'status.json')
        self.assertFalse(result['available'])
        self.assertIsNotNone(result['lastSuccessAt'])
        self.assertEqual(4, result['providerFailures'])
        self.assertNotIn('private SSH', json.dumps(result))


if __name__ == '__main__':
    unittest.main()
