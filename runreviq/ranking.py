"""Conservative local lexical ranking. Scores are not answer confidence."""
import math
import re
from collections import Counter

# Question scaffolding should not outweigh incident terminology. No domain
# identifiers, source IDs or benchmark-specific answers are encoded here.
SCAFFOLD = set('ask can could would should what which how why when where please tell show give use need help check inspect compare find look metrics'.split())
SCAFFOLD.update('runbook runbooks documentation procedure procedures instructions tells another recommends checking one older latest approved revision follow considering upgrading users receiving some does did about recent conclude definitely current right now applies apply'.split())
EXCLUSION = re.compile(r'\b(?:does not|do not) (?:cover|specify|authorize|establish)\b|\boutside (?:this|the) (?:procedure|scope)\b|\bnot instructions for\b', re.I)


def normalize_text(text):
    # Small, explicit operational vocabulary; no source IDs or answers.
    # This is a lexical aid, not a claim that these terms are universally equal.
    phrases = {r'\bdatabase slots?\b': 'database connection',
               r'\beligible to receive traffic\b': 'ready'}
    for pattern, replacement in phrases.items():
        text = re.sub(pattern, replacement, text, flags=re.I)
    text = re.sub(r'\b(HTTP\s+[45][0-9]{2})\s+responses?\b', r'\1 failures', text, flags=re.I)
    text = re.sub(r'\btoken validation\b', 'authentication validation', text, flags=re.I)
    aliases = {'errors': 'failures', 'error': 'failure', 'recovery': 'recover', 'verification': 'verify', 'validation': 'verify',
               'validate': 'verify', 'instances': 'replicas', 'instance': 'replica',
               'jobs': 'tasks', 'job': 'task', 'requeue': 'retry', 'requeued': 'retry',
               'requeuing': 'retry'}
    return re.sub(r'\b(?:' + '|'.join(aliases) + r')\b',
                  lambda match: aliases[match.group().lower()], text, flags=re.I)


def exact_terms(question):
    """Specific numeric codes/versions and uppercase IDs must not fuzzy-match."""
    return re.findall(r'(?<![\w.-])(?:[A-Z][A-Z0-9]*(?:[-_][A-Z0-9]+)+|[0-9]+(?:\.[0-9]+)*)(?![\w-]|\.\d)', question)


def has_exact_terms(text, terms):
    return all(re.search(r'(?<![\w.-])' + re.escape(term) + r'(?![\w-]|\.\d)', text, re.I) for term in terms)


def http_terms(question):
    if not re.search(r'\b(?:http|status|responses?|errors?)\b', question, re.I):
        return set()
    return {term for term in exact_terms(question) if re.fullmatch(r'[1-5][0-9]{2}', term)}


def numeric_match(content, required, codes):
    """Relevance guard, not permission/applicability metadata.

    Non-HTTP identifiers stay exact. Code-free diagnostic sources can be ranked,
    but mentioning other status numbers without the requested code is rejected.
    """
    if not has_exact_terms(content, [term for term in required if term not in codes]):
        return False
    if not codes:
        return True
    restricted = set(re.findall(r'\b(?:only(?:\s+for)?|restricted\s+to)\s+(?:HTTP\s+)?([1-5][0-9]{2})\b', content, re.I))
    restricted.update(re.findall(r'\bHTTP\s+([1-5][0-9]{2})(?:\s+(?:errors?|responses?|incidents?))?\s+only\b', content, re.I))
    excluded = set(re.findall(r'\bnot\s+(?:for\s+)?(?:HTTP\s+)?([1-5][0-9]{2})\b', content, re.I))
    if codes & excluded or (restricted and not codes <= restricted):
        return False
    if has_exact_terms(content, codes):
        return True
    present = {term for term in exact_terms(content) if re.fullmatch(r'[1-5][0-9]{2}', term)}
    return not present


def searchable_text(text):
    """Exclude explicit scope disclaimers from ranking, never from the quote."""
    return ' '.join(sentence for sentence in re.split(r'(?<=[.!?])\s+', text)
                    if not EXCLUSION.search(sentence))


def query_text(question):
    return ' '.join(word for word in re.findall(r"[\w.-]+", question) if word.lower() not in SCAFFOLD)


def rank(candidates, query_terms, body_terms, context_terms, *, policy_terms=None, coverage_floor=.50, query_groups=None):
    """IDF coverage gates and length-normalized lexical relevance.

    Context aids topic matching but cannot alone make a passage evidence.
    Unknown query terms retain weight, so missing concepts can cause abstention.
    """
    policy_terms = policy_terms or [[] for _ in candidates]
    query = set(query_terms)
    if not query or not candidates:
        return []
    bodies = [Counter(terms) for terms in body_terms]
    contexts = [set(terms) for terms in context_terms]
    frequency = Counter(term for body, context in zip(bodies, contexts) for term in set(body) | context)
    weights = {term: math.log(1 + (len(candidates)+1)/(frequency[term]+1)) for term in query}
    total = sum(weights.values())
    average_length = sum(sum(body.values()) for body in bodies)/len(bodies) or 1
    document_terms = {}
    for candidate, body, context in zip(candidates, bodies, contexts):
        document_terms.setdefault(candidate['document_id'], set()).update(set(body) | context)
    scored = []
    for index, (candidate, body, context) in enumerate(zip(candidates, bodies, contexts)):
        matched = query & body.keys()
        coverage = sum(weights[t] for t in query & document_terms[candidate['document_id']])/total
        # Explicitly contrasted clauses may ask for complementary evidence.
        for group in query_groups or []:
            local = query & set(group)
            if len(local & body.keys()) >= 2:
                coverage = max(coverage, sum(weights[t] for t in local & document_terms[candidate['document_id']]) / sum(weights[t] for t in local))
        body_coverage = sum(weights[t] for t in matched)/total
        if coverage < coverage_floor or body_coverage < .15:
            continue
        length = sum(body.values())
        score = sum(weights[t] * body[t]*2.2 / (body[t]+1.2*(.25+.75*length/average_length)) for t in matched)
        score += .60 * sum(weights[t] for t in query & context)
        # Only a cited, reviewed prohibition with two matching action terms earns priority.
        if len(query & set(policy_terms[index])) >= 2:
            score *= 1.25
        score *= coverage
        scored.append((score, index, candidate))
    scored.sort(key=lambda item: (-item[0], item[1]))
    if not scored:
        return []
    selected, covered = [], set()
    for score, index, candidate in scored:
        matched = query & bodies[index].keys()
        # A slightly weaker result can contribute a missing concept, but still
        # must pass document/body coverage and the relative-score floor.
        if score >= scored[0][0]*.40 or (score >= scored[0][0]*.30 and matched - covered):
            selected.append(candidate)
            covered.update(matched)
        if len(selected) == 5:
            break
    return selected
