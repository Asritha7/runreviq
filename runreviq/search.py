"""Local lexical passage retrieval; exact excerpts, never generated answers."""
import hashlib
import re

from psycopg.types.json import Jsonb

from runreviq.database import connect
from runreviq.citations import validate_citations
from runreviq.evidence import validate_profile, validate_prohibitions, bundle, ROLE_NOTES
from runreviq.selector import ROLES, select
from runreviq.ranking import rank, query_text, searchable_text, normalize_text, exact_terms, has_exact_terms, http_terms, numeric_match

RETRIEVAL_METHOD = 'postgres_english_reviewed_v5'


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


def evidence_passages(content, metadata):
    profile = metadata.get('evidence_profile')
    if not profile or not profile['units']:
        for passage in passages(content):
            yield dict(passage, evidence_role='unclassified', role_reviewed=False)
        return
    # Explicit reviewed units define searchable excerpts, not inherited paragraph labels.
    lines = content.splitlines()
    digest = hashlib.sha256(content.encode()).hexdigest()
    for unit in profile['units']:
        yield dict(start_line=unit['start_line'], end_line=unit['end_line'],
                   quote='\n'.join(lines[unit['start_line']-1:unit['end_line']]),
                   source_sha256=digest, evidence_role=unit['role'], role_reviewed=True)


def search(*, role, question, dbname='opslens', **context):
    if role not in ROLES or not isinstance(question, str) or not 1 <= len(question.strip()) <= 500:
        raise ValueError('Invalid search input')
    requested_codes = http_terms(question)
    explicit_status = context.get('http_status')
    if explicit_status is not None and requested_codes and requested_codes != {str(explicit_status)}:
        raise ValueError('Question and HTTP status disagree')
    if explicit_status is None and len(requested_codes) == 1:
        context['http_status'] = int(next(iter(requested_codes)))
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
            if 'evidence_profile' in metadata:
                profile = validate_profile(metadata['evidence_profile'], content)
                validate_prohibitions(profile, metadata['claims'])
            documents.append(dict(metadata, allowed_roles=roles))
        result = select(documents, role=role, **context)
        result.update(passages=[], retrieval_method=RETRIEVAL_METHOD,
                      http_status_context=context.get('http_status'),
                      http_status_source='provided' if explicit_status is not None else 'question' if context.get('http_status') is not None else 'unknown')
        if result['status'] != 'selected':
            return result
        candidates = []
        for metadata, _, content in rows:
            if metadata['id'] in result['recommended_documents']:
                for passage in evidence_passages(content, metadata):
                    candidates.append(dict(passage, document_id=metadata['id'],
                                           revision=metadata['revision'], title=metadata['title']))
        # PostgreSQL provides the same English stemming for query and source.
        # Ranking uses only authorized applicable candidates in this snapshot.
        # These exact context values already passed applicability checks.
        # Remove their repetition from relevance scoring, not from stored text.
        scoring_question = re.sub(r'(?<![\w.-])' + re.escape(context['service']) + r'(?![\w.-])', ' ', question, flags=re.I)
        scoring_question = re.sub(r'(?<![\w.-])v?' + re.escape(context['software_version']) + r'(?![\w.-])', ' ', scoring_question, flags=re.I)
        normalized = conn.execute("SELECT tsvector_to_array(to_tsvector('english', %s))",
                                  (normalize_text(query_text(scoring_question)),)).fetchone()[0]
        clauses = re.split(r'\b(?:while|whereas|as well as|and also)\b', scoring_question, flags=re.I)
        query_groups = []
        if len(clauses) > 1:
            query_groups = [row[0] for row in conn.execute(
                "SELECT tsvector_to_array(to_tsvector('english', value)) FROM jsonb_array_elements_text(%s)",
                (Jsonb([normalize_text(query_text(clause)) for clause in clauses]),)).fetchall()]
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
        policy_actions = {}
        for metadata, _, _ in rows:
            for claim in metadata['claims']:
                if metadata['metadata_verified'] and claim['rule'] == 'must_not' and 'citation' in claim:
                    policy_actions.setdefault(metadata['id'], []).append(claim)
        texts = [dict(policy=normalize_text(' '.join(c['action'].replace('_', ' ')
                         for c in policy_actions.get(p['document_id'], [])
                         if p['start_line'] <= c['citation']['start_line'] and c['citation']['end_line'] <= p['end_line'])), full=normalize_text(p['quote']), body=normalize_text(searchable_text(p['quote'])),
                      context=normalize_text(p['title'] + ' ' + headings[(p['document_id'],p['start_line'])]))
                 for p in candidates]
        terms = conn.execute("""SELECT tsvector_to_array(to_tsvector('english', value->>'body')),
                tsvector_to_array(to_tsvector('english', value->>'context')),
                tsvector_to_array(to_tsvector('english', value->>'full')),
                tsvector_to_array(to_tsvector('english', value->>'policy'))
            FROM jsonb_array_elements(%s::jsonb) WITH ORDINALITY t(value, position) ORDER BY position""",
            (Jsonb(texts),)).fetchall()
        required = [term for term in exact_terms(question) if term != context['software_version']]
        codes = requested_codes or ({str(context['http_status'])} if context.get('http_status') is not None else set())
        reviewed_scope_ids = {metadata['id'] for metadata, _, _ in rows
                              if metadata['metadata_verified'] and metadata.get('evidence_profile', {}).get('http_scope')}
        matching_documents = {metadata['id'] for metadata, _, content in rows
                              if (has_exact_terms(content, [t for t in required if t not in codes])
                                  if metadata['id'] in reviewed_scope_ids else numeric_match(content, required, codes))}
        rankable = [i for i,p in enumerate(candidates) if p['document_id'] in matching_documents]
        # A topic mentioned only in explicit scope exclusions is not evidence.
        # Compute this only over sources passing the same exact-term gate.
        supported = {term for i in rankable for term in terms[i][0] + terms[i][1]}
        mentioned = {term for i in rankable for term in terms[i][2]}
        excluded_only = set(normalized) & (mentioned - supported)
        # Code-free sources need at least two query terms beyond status/generic error words.
        code_free = {metadata['id'] for metadata, _, content in rows
                     if codes and not has_exact_terms(content, codes)}
        generic = codes | {'http', 'status', 'respons', 'error', 'failur'}
        rankable = [i for i in rankable if candidates[i]['document_id'] not in code_free
                    or len((set(normalized) - generic) & set(terms[i][0])) >= 2]
        # Status gates already checked specifics; do not penalize general evidence for
        # lacking a status token when the question supplies other diagnostic concepts.
        ranking_query = [term for term in normalized if term not in codes | {'http'}]
        if not codes or len(set(ranking_query) - generic) < 2:
            ranking_query = normalized
        # Executable/universal requests retain the stricter missing-concept gate.
        coverage_floor = .50 if re.search(r'\b(?:commands?|sql|every|universal|exactly)\b', question, re.I) else .35
        hits = [] if excluded_only else rank([candidates[i] for i in rankable], ranking_query,
                                             [terms[i][0] for i in rankable], [terms[i][1] for i in rankable],
                                             policy_terms=[terms[i][3] for i in rankable], coverage_floor=coverage_floor, query_groups=query_groups,
                                             limit=20 if any(p['role_reviewed'] for p in candidates) else 5)
        if any(p['role_reviewed'] for p in candidates):
            hits = bundle(hits)
        metadata_by_id = {d['id']: d for d in documents}
        for hit in hits:
            doc = metadata_by_id[hit['document_id']]
            hit['usage_note'] = ROLE_NOTES[hit['evidence_role']]
            hit['applicability'] = {k: doc[k] for k in ('service', 'environment', 'version_min', 'version_max_exclusive', 'key_rotation_enabled')}
            hit['applicability']['http_scope'] = doc.get('evidence_profile', {}).get('http_scope')
        result['evidence_summary'] = {role: sum(p['evidence_role'] == role for p in hits) for role in ROLE_NOTES}
        result['passages'] = hits
        result['recommended_documents'] = list(dict.fromkeys(p['document_id'] for p in result['passages']))
        result['evidence'] = [e for e in result['evidence'] if e['document_id'] in result['recommended_documents']]
        if hits:
            result['message'] = 'Found matching source passages. These are excerpts, not an answer or proof of the cause.'
        else:
            result.update(status='insufficient_evidence', message='No sufficiently relevant passages in approved guidance accessible for this context.',
                          next_steps=['Try concrete terms from the incident, check the context, or request an approved source.'])
        return result
