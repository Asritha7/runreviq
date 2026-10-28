"""Create one private restricted Java operator DB config; never assign permissions."""
import argparse
import json
import os
from pathlib import Path
import sys
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from runreviq.database import credentials


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operator', choices=['reviewer', 'publisher'])
    args = parser.parse_args()
    key = 'REVIEW_PASSWORD' if args.operator == 'reviewer' else 'PUBLISH_PASSWORD'
    password = credentials().get(key)
    if not password:
        raise SystemExit('Operator database configuration unavailable; complete SETUP.md first.')
    path = ROOT / '.local' / f'java-{args.operator}-db.json'
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        raise SystemExit('Java operator database configuration already exists; left unchanged.')
    with os.fdopen(fd, 'w') as stream:
        json.dump({'database': 'opslens', 'password': password}, stream, indent=2)
        stream.write('\n')
    print('Created private restricted operator database configuration. No permissions assigned.')


if __name__ == '__main__':
    main()
