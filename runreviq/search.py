"""Local lexical passage retrieval; exact excerpts, never generated answers."""
import hashlib

from psycopg.types.json import Jsonb

from runreviq.database import connect
from runreviq.citations import validate_citations
from runreviq.selector import ROLES, select


def passages(content):
    """Blank-line paragraphs, capped at 20 whole lines; preserve exact line text.

    Headings stay in the source viewer but are not standalone search results.
    Long single lines remain intact rather than inventing partial-line citations.
    """
    lines = content.splitlines()
    fingerprint = hashlib.sha256(content.encode()).hexdigest()
    start = None
    for index in range(len(lines) + 1):
        boundary = index == len(lines) or not lines[index].strip() or lines[index].lstrip().startswith('#')
        if start is not None and (boundary or index - start == 20):
            yield {'start_line': start + 1, 'end_line': index,
                   'quote': '\n'.join(lines[start:index]),
                   'source_sha256': fingerprint}
            start = None
        if not boundary and start is None:
            start = index


def search(*, role, question, dbname='opslens', **context):
    if role not in ROLES or not isinstance(question, str) or not 1 <= len(question.strip()) <= 500:
        raise ValueError('Invalid search input')
    # One snapshot binds authorization, current revision, withdrawal and ranking.
    # No separately maintained index can outlive a withdrawal or ACL change.
    with connect(role=role, dbname=dbname) as conn:
        conn.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY')
        conn.execute("SET LOCAL statement_timeout = '5s'")
        rows = conn.execute('''SELECT v.metadata, d.allowed_roles, v.content
            FROM opslens.documents d JOIN opslens.document_versions v
              ON v.document_id=d.id AND v.revision=d.current_revision
            WHERE NOT EXISTS (SELECT 1 FROM opslens.document_withdrawals w
              WHERE w.document_id=d.id AND w.revision=d.current_revision)
            ORDER BY d.id''').fetchall()
        documents = []
        for metadata, roles, content in rows:
            validate_citations(metadata['claims'], content)
            documents.append(dict(metadata, allowed_roles=roles))
        result = select(documents, role=role, **context)
        result.update(passages=[], retrieval_method='postgres_english_lexical_v1')
        if result['status'] != 'selected':
            return result
        candidates = []
        for metadata, _, content in rows:
            if metadata['id'] in result['recommended_documents']:
                for passage in passages(content):
                    candidates.append(dict(passage, document_id=metadata['id'],
                                           revision=metadata['revision'], title=metadata['title']))
        # OR of normalized English lexemes accepts natural questions without
        # requiring every question word. Parameterized text, never SQL syntax.
        hits = conn.execute('''WITH query AS (
            SELECT to_tsquery('english', string_agg(quote_literal(term), ' | ')) AS q
            FROM unnest(tsvector_to_array(to_tsvector('english', %s))) term
        ), candidates AS (
            SELECT p.*, to_tsvector('english', p.value->>'quote') AS vector
            FROM jsonb_array_elements(%s::jsonb) WITH ORDINALITY p(value, position)
        ) SELECT value FROM candidates, query WHERE vector @@ q
          ORDER BY ts_rank_cd(vector, q, 32) DESC, position LIMIT 5''',
            (question, Jsonb(candidates))).fetchall()
        result['passages'] = [row[0] for row in hits]
        result['recommended_documents'] = list(dict.fromkeys(p['document_id'] for p in result['passages']))
        result['evidence'] = [e for e in result['evidence'] if e['document_id'] in result['recommended_documents']]
        if hits:
            result['message'] = 'Found matching source passages. These are excerpts, not an answer or proof of the cause.'
        else:
            result.update(status='insufficient_evidence', message='No matching passages in approved guidance accessible for this context.',
                          next_steps=['Try concrete terms from the incident, check the context, or request an approved source.'])
        return result
