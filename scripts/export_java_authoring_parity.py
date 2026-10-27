"""Freeze proposal/review responses from Python; regression parity, not independent validation."""
import argparse
import json
from pathlib import Path
import sys
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from runreviq.extraction import extract
from runreviq.drafts import validate_review

SOURCE = '''# Example runbook

## Applicability
service: identity-service
environment: production
version_min: 2.4.0
version_max_exclusive: 2.5.0
key_rotation_enabled: true

## Procedure
Inspect JWKS errors before restart.
'''

def payload():
    sources = [SOURCE, SOURCE.replace('\n', '\r\n'), SOURCE.replace('\n', '\u0085'),
        SOURCE + '\n# Another title\n', '# Runbook\nMaybe production for 2.4?',
        SOURCE.replace('environment: production', 'environment: production\nenvironment: production'),
        SOURCE.replace('2.4.0', '2.6.0'), SOURCE.replace('key_rotation_enabled: true', ''),
        SOURCE.replace('true', 'none'), SOURCE.replace('true', 'false'),
        SOURCE.replace('## Procedure', 'allowed_roles: security\nstatus: approved\nmetadata_verified: true\n\n## Procedure'),
        SOURCE + '\n```\n# False title\n## Applicability\nenvironment: staging\n```',
        SOURCE.replace('# Example runbook', '# Café 🔑'), SOURCE.replace('service: identity-service','- service: identity-service')]
    sources += [SOURCE.replace(old,new) for old,new in [('2.4.0','2.4'),('production','unknown'),('identity-service','two services'),('true','maybe')]]
    metadata = dict(title='Example runbook', service='identity-service', environment='production', version_min='2.4.0',version_max_exclusive='2.5.0',key_rotation_enabled='true')
    reviews = [metadata,dict(metadata, key_rotation_enabled='none'),dict(metadata, version_min='2.6.0'),dict(metadata, title='\u0085 Café 🔑 \u00a0')]
    reviews += [dict(metadata, **{f:''}) for f in metadata]
    reviews += [dict(metadata, environment='invalid'), dict(metadata, evidence_profile={'schema_version':1,'http_scope':{'mode':'only','codes':[401]},'units':[{'role':'procedure','start_line':11,'end_line':11}]})]
    return dict(extraction=[dict(source=s,expected=extract(s)) for s in sources],reviews=[dict(source=SOURCE,metadata=m,expected=validate_review(m,SOURCE)) for m in reviews])

if __name__ == '__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args()
    path=ROOT/'backend/src/test/resources/authoring-parity.json'
    text=json.dumps(payload(),ensure_ascii=True,indent=2)+'\n'
    if args.check:
        if path.read_text()!=text: raise SystemExit('Authoring parity fixture is stale')
    else: path.write_text(text)
