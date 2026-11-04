"""Checks that the scraper selects the dated T Inc pence quote, not another fund class."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


spec = importlib.util.spec_from_file_location('fundsmith_nav', Path(__file__).with_name('fundsmith_nav.py'))
nav = importlib.util.module_from_spec(spec)
spec.loader.exec_module(nav)


class FundsmithNavTest(unittest.TestCase):
    def test_dated_t_inc_price(self):
        html = '''<h2>Price Data 4<sup>th</sup> November 2026</h2>
          <table><thead><tr><th>Share Class</th><th>OCF*</th><th>Minimum</th><th>Price p</th></tr></thead>
          <tbody><tr><th>T Class Acc</th><td>1.05%</td><td>£1,000</td><td>740.41</td></tr>
          <tr><th>T Class Inc</th><td>1.05%</td><td>£1,000</td><td>668.27</td></tr></tbody></table>'''
        self.assertEqual({'date': '2026-11-04', 'isin': nav.ISIN, 'currency': 'GBX',
                          'price': '668.27', 'source': nav.URL}, nav.parse_factsheet(html))

    def test_missing_or_ambiguous_price_is_rejected(self):
        html = '<h2>Price Data 4th November 2026</h2><table><tr><th>Share Class</th><th>OCF*</th><th>Minimum</th><th>Price p</th></tr><tr><th>T Class Inc</th><td>1%</td><td>£1,000</td><td>668.27</td></tr></table>'
        duplicate = html[html.index('<tr><th>T Class Inc'):html.index('</table>')]
        for changed in (html.replace('T Class Inc', 'T Class Acc'), html.replace('668.27', 'NaN'),
                        html.replace('</table>', duplicate + '</table>')):
            with self.subTest(changed=changed[-80:]):
                with self.assertRaises(ValueError):
                    nav.parse_factsheet(changed)

    def test_publish_retains_newer_price(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'fundsmith' / 'latest.json'
            first = {'date': '2026-11-04', 'isin': nav.ISIN, 'currency': 'GBX',
                     'price': '668.27', 'source': nav.URL}
            nav.publish(path, first)
            self.assertEqual('668.27', json.loads(path.read_text())['price'])
            with self.assertRaises(ValueError):
                nav.publish(path, {**first, 'date': '2026-11-03', 'price': '600'})
            self.assertEqual('668.27', json.loads(path.read_text())['price'])


if __name__ == '__main__':
    unittest.main()
