"""Create a private Java draft-writer config from existing local setup, without overwriting."""
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from runreviq.database import credentials


def main():
    path = ROOT / '.local/java-drafts.json'
    values = credentials()
    password = values.get('DRAFTS_PASSWORD')
    if not password:
        raise SystemExit('Draft configuration unavailable; complete SETUP.md first.')
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        raise SystemExit('Java draft configuration already exists; left unchanged.')
    with os.fdopen(fd, 'w') as stream:
        json.dump({'database': 'opslens', 'draft_password': password}, stream, indent=2)
        stream.write('\n')
    print('Created private Java draft configuration. Only the restricted draft writer credential was copied.')


if __name__ == '__main__':
    main()
