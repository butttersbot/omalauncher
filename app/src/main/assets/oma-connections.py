#!/usr/bin/env python3
"""Read-only Connections diagnostics. This tool cannot request, approve or perform a restart."""
import argparse
import json
import os
from pathlib import Path
import re
import sys

IDENTIFIER = re.compile(r'[0-9]{1,16}-[0-9a-f]{16}')

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['incident', 'list', 'owner-key'])
    parser.add_argument('id', nargs='?')
    args = parser.parse_args(argv)
    root = Path.home() / '.local/share/soma/connections'
    if args.command == 'owner-key':
        if args.id:
            parser.error('owner-key takes no id')
        target = root / 'owner-public-key.json'
    elif args.command == 'incident':
        identifier = args.id or 'newest'
        if identifier != 'newest' and not IDENTIFIER.fullmatch(identifier):
            parser.error('invalid incident id')
        target = root / 'incidents' / (identifier + '.json')
    else:
        if args.id:
            parser.error('list takes no id')
        directory = root / 'incidents'
        rows = []
        for path in directory.glob('*.json'):
            if not IDENTIFIER.fullmatch(path.stem) or path.is_symlink() or path.stat().st_size > 524288:
                continue
            try:
                record = json.loads(path.read_text())
                rows.append({k: record.get(k) for k in ('id', 'last_seen', 'host', 'service', 'stage', 'summary')})
            except (OSError, ValueError):
                continue
        print(json.dumps(sorted(rows, key=lambda r: r.get('last_seen') or 0, reverse=True)[:256], indent=2))
        return 0
    if target.is_symlink() or not target.is_file() or target.stat().st_size > 524288:
        print('No Connections record at ' + str(target), file=sys.stderr)
        return 1
    try:
        print(json.dumps(json.loads(target.read_text()), indent=2, ensure_ascii=False))
        return 0
    except (OSError, ValueError) as error:
        print('Cannot read Connections record: ' + type(error).__name__, file=sys.stderr)
        return 1

if __name__ == '__main__':
    raise SystemExit(main())
