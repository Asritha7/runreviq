"""Local lexical passage retrieval; exact excerpts, never generated answers."""
import hashlib

from psycopg.types.json import Jsonb

from runreviq.database import connect
from runreviq.citations import validate_citations
from runreviq.selector import ROLES, select
from runreviq.ranking import rank, query_text, searchable_text, normalize_text, exact_terms, has_exact_terms

RETRIEVAL_METHOD = 'postgres_english_operational_v3'


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
        result.update(passages=[], retrieval_method=RETRIEVAL_METHOD)
        if result['status'] != 'selected':
            return result
        candidates = []
        for metadata, _, content in rows:
            if metadata['id'] in result['recommended_documents']:
                for passage in passages(content):
                    candidates.append(dict(passage, document_id=metadata['id'],
                                           revision=metadata['revision'], title=metadata['title']))
        # PostgreSQL provides the same English stemming for query and source.
        # Ranking uses only authorized applicable candidates in this snapshot.
        normalized = conn.execute("SELECT tsvector_to_array(to_tsvector('english', %s))",
                                  (normalize_text(query_text(question)),)).fetchone()[0]
        starts = {}
        for candidate in candidates:
            starts.setdefault(candidate['document_id'], set()).add(candidate['start_line'])
        headings = {}
        for metadata, _, content in rows:
            wanted = starts.get(metadata['id'], set())
            if not wanted:
                continue
            heading = ''
            for number, line in enumerate(content.splitlines(), 1):
                if line.lstrip().startswith('#'):
                    heading = line.lstrip('# ').strip()
                if number in wanted:
                    headings[(metadata['id'], number)] = heading
        texts = [dict(full=normalize_text(p['quote']), body=normalize_text(searchable_text(p['quote'])),
                      context=normalize_text(p['title'] + ' ' + headings[(p['document_id'],p['start_line'])]))
                 for p in candidates]
        terms = conn.execute("""SELECT tsvector_to_array(to_tsvector('english', value->>'body')),
                tsvector_to_array(to_tsvector('english', value->>'context')),
                tsvector_to_array(to_tsvector('english', value->>'full'))
            FROM jsonb_array_elements(%s::jsonb) WITH ORDINALITY t(value, position) ORDER BY position""",
            (Jsonb(texts),)).fetchall()
        required = exact_terms(question)
        exact_documents = {metadata['id'] for metadata, _, content in rows
                           if has_exact_terms(content, required)}
        eligible = [i for i,p in enumerate(candidates) if p['document_id'] in exact_documents]
        # A topic mentioned only in explicit scope exclusions is not evidence.
        # Compute this only over sources passing the same exact-term gate.
        supported = {term for i in eligible for term in terms[i][0] + terms[i][1]}
        mentioned = {term for i in eligible for term in terms[i][2]}
        excluded_only = set(normalized) & (mentioned - supported)
        hits = [] if excluded_only else rank([candidates[i] for i in eligible], normalized,
                                             [terms[i][0] for i in eligible], [terms[i][1] for i in eligible])
        result['passages'] = hits
        result['recommended_documents'] = list(dict.fromkeys(p['document_id'] for p in result['passages']))
        result['evidence'] = [e for e in result['evidence'] if e['document_id'] in result['recommended_documents']]
        if hits:
            result['message'] = 'Found matching source passages. These are excerpts, not an answer or proof of the cause.'
        else:
            result.update(status='insufficient_evidence', message='No sufficiently relevant passages in approved guidance accessible for this context.',
                          next_steps=['Try concrete terms from the incident, check the context, or request an approved source.'])
        return result
