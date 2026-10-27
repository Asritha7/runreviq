"""Create a private Java reader-only config from existing local setup, without overwriting."""
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from runreviq.database import credentials


def main():
    path = ROOT / '.local/java-readers.json'
    values = credentials()
    passwords = {r: values.get(f'READER_{r.upper()}_PASSWORD') for r in ('platform','security','intern')}
    if any(not value for value in passwords.values()):
        raise SystemExit('Reader configuration unavailable; complete SETUP.md first.')
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        raise SystemExit('Java reader configuration already exists; left unchanged.')
    with os.fdopen(fd, 'w') as stream:
        json.dump({'database': 'opslens', 'reader_passwords': passwords}, stream, indent=2)
        stream.write('\n')
    print('Created private Java reader configuration. No administrator or writer credentials copied.')


if __name__ == '__main__':
    main()
