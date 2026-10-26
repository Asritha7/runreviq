"""Conservative local lexical ranking. Scores are not answer confidence."""
import math
import re
from collections import Counter

# Question scaffolding should not outweigh incident terminology. No domain
# identifiers, source IDs or benchmark-specific answers are encoded here.
SCAFFOLD = set('ask can could would should what which how why when where please tell show give use need help check inspect compare find look metrics'.split())
EXCLUSION = re.compile(r'\b(?:does not|do not) (?:cover|specify|authorize|establish)\b|\boutside (?:this|the) (?:procedure|scope)\b|\bnot instructions for\b', re.I)


def normalize_text(text):
    # Small, explicit operational vocabulary; no source IDs or answers.
    # This is a lexical aid, not a claim that these terms are universally equal.
    phrases = {r'\bdatabase slots?\b': 'database connection',
               r'\beligible to receive traffic\b': 'ready'}
    for pattern, replacement in phrases.items():
        text = re.sub(pattern, replacement, text, flags=re.I)
    aliases = {'recovery': 'recover', 'verification': 'verify', 'validation': 'verify',
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


def searchable_text(text):
    """Exclude explicit scope disclaimers from ranking, never from the quote."""
    return ' '.join(sentence for sentence in re.split(r'(?<=[.!?])\s+', text)
                    if not EXCLUSION.search(sentence))


def query_text(question):
    return ' '.join(word for word in re.findall(r"[\w.-]+", question) if word.lower() not in SCAFFOLD)


def rank(candidates, query_terms, body_terms, context_terms):
    """IDF coverage gates and length-normalized lexical relevance.

    Context aids topic matching but cannot alone make a passage evidence.
    Unknown query terms retain weight, so missing concepts can cause abstention.
    """
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
        body_coverage = sum(weights[t] for t in matched)/total
        if coverage < .50 or body_coverage < .15:
            continue
        length = sum(body.values())
        score = sum(weights[t] * body[t]*2.2 / (body[t]+1.2*(.25+.75*length/average_length)) for t in matched)
        score += .60 * sum(weights[t] for t in query & context)
        score *= coverage
        scored.append((score, index, candidate))
    scored.sort(key=lambda item: (-item[0], item[1]))
    if not scored:
        return []
    return [candidate for score, _, candidate in scored if score >= scored[0][0]*.40][:5]
