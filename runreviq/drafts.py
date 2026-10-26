"""Private, unpublished Markdown drafts; never a source for evidence selection."""
import hashlib
import re
import uuid

import psycopg
from psycopg.types.json import Jsonb

from runreviq.database import credentials
from runreviq.extraction import extract, parse_value, FIELDS
from runreviq.selector import version
from runreviq.evidence import validate_profile

MAX_BYTES = 1_000_000
MAX_DRAFTS = 20


class DraftInputError(ValueError):
    pass


class DraftLimit(Exception):
    pass


def prepare_draft(filename, content):
    # Filename is a display label, never a path. Uploaded bytes must be UTF-8.
    if not isinstance(filename, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9 ._-]{0,116}\.md', filename, re.IGNORECASE):
        raise DraftInputError('Expected a simple Markdown filename')
    if not isinstance(content, str) or not content.strip() or '\x00' in content:
        raise DraftInputError('Expected nonempty Markdown text')
    encoded = content.encode('utf-8')
    if len(encoded) > MAX_BYTES:
        raise DraftInputError('Document exceeds 1 MB')
    return extract(content)


def owner_key(mode, subject):
    # Keep demo and Google identities separate without storing raw subjects in drafts.
    return hashlib.sha256((mode + ':' + subject).encode()).hexdigest()


def connect_drafts(dbname='opslens'):
    password = credentials().get('DRAFTS_PASSWORD')
    if not password:
        raise ValueError('Draft storage is not configured')
    return psycopg.connect(host='127.0.0.1', port=55432, dbname=dbname,
                          user='opslens_draft_writer', password=password, connect_timeout=5)


def scope(conn, owner):
    conn.execute("SELECT set_config('opslens.draft_owner', %s, true)", (owner,))


def create_draft(owner, filename, content, *, dbname='opslens'):
    proposal = prepare_draft(filename, content)
    identifier = uuid.uuid4()
    with connect_drafts(dbname) as conn:
        scope(conn, owner)
        # Serializes per-owner quota checks without granting reads of other owners.
        conn.execute('SELECT pg_advisory_xact_lock(%s)', (int(owner[:15], 16),))
        count = conn.execute('SELECT count(*) FROM opslens.upload_drafts').fetchone()[0]
        if count >= MAX_DRAFTS:
            raise DraftLimit()
        created = conn.execute('INSERT INTO opslens.upload_drafts(id, owner, filename, content, proposal) VALUES (%s,%s,%s,%s,%s) RETURNING created_at',
                               (identifier, owner, filename, content, Jsonb(proposal))).fetchone()[0]
    return {'id': str(identifier), 'filename': filename, 'created_at': created.isoformat(),
            'status': 'draft', 'content': content, 'proposal': proposal, 'review': None}


def list_drafts(owner, *, dbname='opslens'):
    with connect_drafts(dbname) as conn:
        scope(conn, owner)
        rows = conn.execute('SELECT id, filename, created_at FROM opslens.upload_drafts ORDER BY created_at DESC, id LIMIT %s', (MAX_DRAFTS,)).fetchall()
    return [{'id': str(id), 'filename': name, 'created_at': created.isoformat(), 'status': 'draft'} for id, name, created in rows]


def get_draft(owner, identifier, *, dbname='opslens'):
    with connect_drafts(dbname) as conn:
        scope(conn, owner)
        row = conn.execute('SELECT id, filename, created_at, content, proposal FROM opslens.upload_drafts WHERE id=%s', (identifier,)).fetchone()
        review = latest_review(conn, identifier) if row else None
    if row is None:
        return None
    id, filename, created, content, proposal = row
    return {'id': str(id), 'filename': filename, 'created_at': created.isoformat(),
            'status': 'draft', 'content': content, 'proposal': proposal, 'review': review}


class ReviewConflict(Exception):
    pass


def validate_review(metadata, content=None):
    """Allow incomplete work, but never treat invalid values as usable metadata."""
    base = {'title', *FIELDS}
    if (not isinstance(metadata, dict) or not base <= set(metadata) or set(metadata) - base - {'evidence_profile'}
            or any(not isinstance(metadata[k], str) or len(metadata[k]) > 200 or '\x00' in metadata[k] for k in base)):
        raise DraftInputError('Expected editable string fields and an optional evidence profile')
    values, issues = {}, []
    if 'evidence_profile' in metadata:
        try:
            values['evidence_profile'] = validate_profile(metadata['evidence_profile'], content)
        except ValueError as exc:
            raise DraftInputError('Invalid evidence profile') from exc
    for field in metadata:
        if field == 'evidence_profile':
            continue
        raw = metadata[field]
        raw = raw.strip()
        if not raw:
            issues.append({'field': field, 'reason': 'missing'})
            continue
        try:
            values[field] = raw if field == 'title' else parse_value(field, raw)
        except ValueError:
            issues.append({'field': field, 'reason': 'invalid_value'})
    if ('version_min' in values and 'version_max_exclusive' in values
            and version(values['version_min']) >= version(values['version_max_exclusive'])):
        issues.append({'field': 'version_range', 'reason': 'empty_or_inverted'})
        del values['version_min'], values['version_max_exclusive']
    return {'metadata': metadata, 'validated_metadata': values, 'issues': issues,
            'review_required': True, 'status': 'in_progress'}


def latest_review(conn, identifier):
    row = conn.execute('SELECT revision, metadata, created_at FROM opslens.draft_reviews WHERE draft_id=%s ORDER BY revision DESC LIMIT 1', (identifier,)).fetchone()
    if row is None:
        return None
    revision, metadata, created = row
    return {**validate_review(metadata), 'revision': revision, 'created_at': created.isoformat()}


def save_review(owner, identifier, expected_revision, metadata, *, dbname='opslens'):
    result = validate_review(metadata)
    if type(expected_revision) is not int or not 0 <= expected_revision < 100:
        raise DraftInputError('Invalid review revision')
    with connect_drafts(dbname) as conn:
        scope(conn, owner)
        # Every review writer for a draft shares this lock, preventing lost updates.
        lock = int(uuid.UUID(str(identifier)).hex[:15], 16)
        conn.execute('SELECT pg_advisory_xact_lock(%s)', (lock,))
        source = conn.execute('SELECT content FROM opslens.upload_drafts WHERE id=%s', (identifier,)).fetchone()
        if not source:
            return None
        result = validate_review(metadata, source[0])
        previous = latest_review(conn, identifier)
        current = previous['revision'] if previous else 0
        if current != expected_revision:
            raise ReviewConflict()
        created = conn.execute('INSERT INTO opslens.draft_reviews(draft_id, revision, metadata) VALUES (%s,%s,%s) RETURNING created_at',
                               (identifier, current + 1, Jsonb(metadata))).fetchone()[0]
    return {**result, 'revision': current + 1, 'created_at': created.isoformat()}
