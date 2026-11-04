#!/usr/bin/env python3
"""Read the dated Fundsmith Equity Fund T Inc NAV from its public factsheet."""

import argparse
from datetime import date, datetime, timezone
from decimal import Decimal, InvalidOperation
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError


URL = 'https://www.fundsmith.co.uk/factsheet/'
ISIN = 'GB00B4M93C53'


class FactsheetParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.heading = None
        self.price_heading = None
        self.in_price_table = False
        self.cell = None
        self.row = None
        self.rows = []

    def handle_starttag(self, tag, attrs):
        if tag == 'h2':
            self.heading = []
        elif tag == 'table' and self.price_heading and not self.rows:
            self.in_price_table = True
        elif self.in_price_table and tag == 'tr':
            self.row = []
        elif self.row is not None and tag in ('th', 'td'):
            self.cell = []

    def handle_data(self, data):
        if self.cell is not None:
            self.cell.append(data)
        elif self.heading is not None:
            self.heading.append(data)

    def handle_endtag(self, tag):
        if tag == 'h2' and self.heading is not None:
            text = ' '.join(''.join(self.heading).split())
            if text.startswith('Price Data '):
                if self.price_heading is not None:
                    raise ValueError('Multiple price data sections')
                self.price_heading = text
            self.heading = None
        elif tag in ('th', 'td') and self.cell is not None:
            self.row.append(' '.join(''.join(self.cell).split()))
            self.cell = None
        elif tag == 'tr' and self.row is not None:
            self.rows.append(self.row)
            self.row = None
        elif tag == 'table' and self.in_price_table:
            self.in_price_table = False


def parse_factsheet(html):
    parser = FactsheetParser()
    parser.feed(html)
    match = re.fullmatch(r'Price Data (\d{1,2})(?:st|nd|rd|th) ([A-Za-z]+) (\d{4})', parser.price_heading or '')
    if not match:
        raise ValueError('Dated price data section missing')
    priced_on = datetime.strptime(f'{match[1]} {match[2]} {match[3]}', '%d %B %Y').date()
    if not parser.rows or parser.rows[0] != ['Share Class', 'OCF*', 'Minimum', 'Price p']:
        raise ValueError('Unexpected Fundsmith price table')
    matches = [row for row in parser.rows[1:] if row and row[0] == 'T Class Inc']
    if len(matches) != 1 or len(matches[0]) != 4:
        raise ValueError('Fundsmith T Class Inc price missing or ambiguous')
    try:
        price = Decimal(matches[0][3])
    except InvalidOperation as error:
        raise ValueError('Invalid Fundsmith price') from error
    if not price.is_finite() or not 0 < price < 100000:
        raise ValueError('Invalid Fundsmith price')
    return {'date': priced_on.isoformat(), 'isin': ISIN, 'currency': 'GBX', 'price': str(price), 'source': URL}


def fetch_nav():
    request = Request(URL, headers={'User-Agent': 'PortfolioWeb-FundsmithNAV/1.0', 'Accept': 'text/html'})
    with urlopen(request, timeout=15) as response:
        if response.headers.get_content_type() != 'text/html':
            raise ValueError('Fundsmith did not return HTML')
        body = response.read(2_000_001)
        if len(body) > 2_000_000:
            raise ValueError('Oversized Fundsmith response')
        nav = parse_factsheet(body.decode('utf-8'))
    if date.fromisoformat(nav['date']) > date.today():
        raise ValueError('Fundsmith price date is in the future')
    return nav


def publish(path, nav):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    previous = json.loads(path.read_text()) if path.exists() else None
    if previous and previous.get('date', '') > nav['date']:
        raise ValueError('Fundsmith returned an older price than the retained snapshot')
    record = {**nav, 'fetchedAt': datetime.now(timezone.utc).isoformat()}
    fd, name = tempfile.mkstemp(prefix='fundsmith-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as output:
            json.dump(record, output, sort_keys=True)
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--html', help='Read a saved factsheet HTML file instead of fetching the page')
    parser.add_argument('--output', type=Path, help='Atomically retain the latest successful NAV as JSON')
    args = parser.parse_args()
    try:
        nav = parse_factsheet(Path(args.html).read_text(encoding='utf-8')) if args.html else fetch_nav()
        if args.output:
            nav = publish(args.output, nav)
    except (OSError, HTTPError, URLError, ValueError, UnicodeError) as error:
        raise SystemExit(f'Fundsmith NAV unavailable: {error}') from None
    print(json.dumps(nav, sort_keys=True))


if __name__ == '__main__':
    main()
