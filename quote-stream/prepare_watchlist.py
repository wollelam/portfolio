"""Create a private, review-required IBKR watchlist draft from the local dashboard."""
import argparse
import json
import os
from pathlib import Path
import uuid
import urllib.parse
import urllib.request


def draft(report, securities):
    if not 1 <= len(securities) <= 100 or len(set(securities)) != len(securities):
        raise ValueError('Choose between one and 100 distinct portfolio security UUIDs')
    holdings = {item['uuid']: item for item in report['holdings'] if item.get('uuid') and item.get('type') == 'security'}
    instruments = []
    for key in securities:
        uuid.UUID(key)
        row = holdings.get(key)
        if row is None:
            raise ValueError('A selected security is not held in the chosen portfolio view')
        currency = row.get('quoteCurrency')
        if not isinstance(currency, str) or len(currency) != 3 or not currency.isalpha() or not currency.isupper():
            raise ValueError('A selected security has no usable quote currency')
        instruments.append({'key': key, 'name': row['name'], 'symbol': row.get('symbol') or 'REVIEW',
                            'currency': currency, 'exchange': 'SMART', 'primary_exchange': 'REVIEW', 'sec_type': 'STK'})
    return {'host': '127.0.0.1', 'port': 4002, 'client_id': 43, 'market_data_type': 3, 'instruments': instruments}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--security', action='append', required=True, help='Portfolio security UUID; repeat up to 100 times')
    parser.add_argument('--port', type=int, default=8088, help='Local web dashboard port')
    parser.add_argument('--exclude', default='', help='Optional native group exclusion IDs from the dashboard URL')
    parser.add_argument('--output', type=Path, required=True, help='New private config path outside the checkout')
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error('Invalid local port')
    output = args.output.expanduser().resolve()
    checkout = Path(__file__).resolve().parents[1]
    if output == checkout or checkout in output.parents:
        parser.error('Write private watchlists outside the source checkout')
    params = urllib.parse.urlencode({'exclude': args.exclude}) if args.exclude else ''
    with urllib.request.urlopen(f'http://127.0.0.1:{args.port}/api/dashboard' + ('?' + params if params else ''), timeout=30) as response:
        config = draft(json.load(response), args.security)
    output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    # Exclusive creation prevents replacing an existing operational configuration.
    fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump(config, stream, indent=2)
        stream.write('\n')
    print('Private draft created. Review each symbol, currency, venue and contract ID before use; verify the Gateway port.')
    print('REVIEW primary exchanges deliberately prevent unreviewed contracts from passing qualification. No broker connection was made.')


if __name__ == '__main__':
    main()
