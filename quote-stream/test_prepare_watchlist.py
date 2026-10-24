import unittest
from prepare_watchlist import draft


class WatchlistDraftTest(unittest.TestCase):
    def setUp(self):
        self.key = '00000000-0000-0000-0000-000000000001'
        self.report = {'holdings': [{'uuid': self.key, 'name': 'Synthetic stock', 'symbol': 'TEST',
                                     'quoteCurrency': 'USD', 'type': 'security'}]}

    def test_draft_preserves_explicit_portfolio_identity_and_requires_venue_review(self):
        result = draft(self.report, [self.key])
        row = result['instruments'][0]
        self.assertEqual(self.key, row['key'])
        self.assertEqual('USD', row['currency'])
        self.assertEqual('REVIEW', row['primary_exchange'])
        self.assertNotIn('username', result)
        self.assertNotIn('password', result)

    def test_unheld_duplicate_unknown_currency_and_oversized_selection_fail_closed(self):
        for selected in [[], [self.key, self.key], ['00000000-0000-0000-0000-000000000002'], [self.key] * 11]:
            with self.assertRaises(ValueError):
                draft(self.report, selected)
        self.report['holdings'][0]['quoteCurrency'] = None
        with self.assertRaises(ValueError):
            draft(self.report, [self.key])


if __name__ == '__main__':
    unittest.main()
