"""Freeze Python selector outputs for Java migration checks; never ingest this fixture."""
import argparse
import copy
import hashlib
import itertools
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from runreviq.selector import select


def render():
    source = ROOT / 'datasets/northstar/catalog.json'
    original = json.loads(source.read_text())
    scoped = copy.deepcopy(original)
    for index, doc in enumerate(scoped):
        doc['evidence_profile'] = {'schema_version': 1, 'http_scope':
            {'mode': 'only', 'codes': [401 if index % 2 else 503]}, 'units': []}
    catalogs = {'original': original, 'http_scoped': scoped}
    cases = []
    for catalog, role, version, environment, rotation, http in itertools.product(
            catalogs, ('platform', 'security', 'intern'),
            ('2.1.0', '2.4.0', '2.5.0'), ('production', 'staging'),
            (None, True, False), (None, 401, 503)):
        context = dict(role=role, service='identity-service', software_version=version,
                       environment=environment, key_rotation_enabled=rotation, http_status=http)
        cases.append(dict(catalog=catalog, context=context,
                          expected=select(catalogs[catalog], **context)))
    return json.dumps({'purpose': 'Migration parity, not independent correctness evidence',
                      'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
                      'catalogs': catalogs, 'cases': cases}, indent=2) + '\n'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    target = ROOT / 'backend/src/test/resources/parity/selector.json'
    result = render()
    if args.check:
        if not target.exists() or target.read_text() != result:
            raise SystemExit('Java parity fixture differs from Python; review before regenerating')
        print('Python parity fixture is current')
    else:
        target.write_text(result)
