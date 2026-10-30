# Isolated retrieval-generalization experiment

## Decision and scope

This milestone compares reviewed-v5, focused-v6 and **relaxed-lexical-v1** in the isolated
Java evaluator. A local-embedding hybrid is an optional designed arm, **not executed**:
no vetted, pinned local model bundle has been selected. No model is downloaded, no cloud
provider is configured and no generated answers or autonomous actions are introduced.

The implementation is a recall/precision ablation, not a promoted search mode. Existing
runtime configuration still rejects experimental identifiers. The default remains
reviewed-v5. The known seven external misses are development/regression cases; improving
on them is not unseen generalization evidence. All thirteen cases are retained so that
metadata blocks, restricted access and prose disagreement do not disappear from reporting.

PRs #36–#39, their original packs, first-run outputs, diagnostic traces and the negative
passage-scope result remain preserved. The new command hashes existing files beneath the
baseline's parent before replay and verifies those files afterward, in addition to the
original source/gold fingerprint and database-state checks. It writes only into a new
owner-only result directory; it refuses overwrite. No private input or raw result enters Git.

## Eligibility before any retrieval arm

The path is: authenticate evaluator role → restricted reader connection and forced RLS →
validate source checksums/current revisions/withdrawals → deterministic applicability →
extract passages from recommended documents → candidate generation → scoring → assembly.
The SQL transaction is repeatable-read and read-only. No arm can move a rejected document
back into the eligible set. A conflict or context/review response short-circuits ranking.

| Check | Authority or heuristic? | Experimental treatment |
| --- | --- | --- |
| Reader role, RLS and source integrity | Authoritative security boundary | Identical; fail closed |
| Current revision, withdrawal and approved publication status | Authoritative lifecycle | Identical |
| Service, environment and software-version range | Authoritative reviewed applicability | Identical |
| Reviewed HTTP scope and explicit HTTP context | Authoritative applicability | Identical; missing required context blocks ranking |
| Reviewed key-rotation prerequisite | Authoritative precondition | Identical; unknown never becomes true/false |
| Unsupported manifest prerequisites | Unrepresented policy context | Existing adapter marks unverified; remains blocked |
| Reviewed normalized opposing claims | Authoritative conflict handling | Identical; prose-only disagreement remains unsupported |
| Literal identifiers/numbers in query matching source text | Relevance heuristic, not access authority | Retained in this arm |
| Legacy prose HTTP restrictions and missing-code overlap floor | Conservative relevance guards, not reviewed scope authority | Retained; reviewed HTTP metadata remains separately mandatory |
| Excluded-sentence/global term veto | Conservative lexical scope heuristic | Retained; PR #39's local-scope candidate remains a separate experiment |
| Document coverage and body coverage ratios | Relevance heuristics | Bypassed only in relaxed generation; require at least one shared body term |
| Title/heading support, weighting, relative-score cutoff, top-K and bundling | Ranking/assembly heuristics | Unchanged |

Retaining heuristic guards does not make them authoritative. In particular, access and
approval are never inferred from text, title similarity or a numeric match. Relaxing
another heuristic later requires a separate arm; there is no policy-eligibility relaxation
or conversion of unsupported metadata into approved metadata to manufacture recall.

## Arms and fixed parameters

- **reviewed-v5:** exact original response baseline, including 0.35/0.50 query coverage
  and 0.15 body coverage gates.
- **focused-v6:** existing optional precision candidate, unchanged and checked against
  its frozen outputs.
- **relaxed-lexical-v1:** reviewed-v5 preprocessing and ranking; replace only the two
  ratio-based admission gates with `matching_body_terms >= 1`. Keep exact-term, HTTP and
  global scope guards. Scores still use original query weights and coverage; relative
  score selection and limits remain unchanged. No per-case aliases or synonyms are added.
- **optional local hybrid:** not run; intended future comparison described below.

The relaxed arm broadens admission where terminology overlaps weakly. It cannot bridge
questions and passages with zero common normalized terms. A deliberate zero-overlap
control still abstains. A deliberately irrelevant procedure request sharing a generic
word produces a false positive: this negative finding is kept as a passing regression
assertion of the experiment's current behavior, not misrepresented as correct retrieval.

The experiment is package-private/test-driven, not selectable by HTTP or Spring search
configuration. The collector has no public API hook. Offline requests carry questions,
contexts and frozen baseline responses; gold labels stay in the Python evaluator and
never enter SQL or candidate generation.

## Metrics and interpretation

Every case retains full raw events and the first decisive exclusion stage of each missing
required passage. Positive retrieval is not treated as semantic task completion.

- **Evidence recall:** exact document/revision/line-range matches divided by required
  labeled passages. Overall recall includes metadata-blocked required evidence; those
  failures are separately classified rather than blamed on ranking.
- **Strict returned precision:** required matches / all returned passages. Optional useful
  evidence is not automatically credited. Extras are recorded for adjudication.
- **Wrong-procedure false positives:** report passages and cases matching supplied
  forbidden-source labels as a lower-bound proxy. Report unadjudicated extras separately.
  The full semantic wrong-procedure rate is `null` until independent adjudication exists;
  zero forbidden sources must not be described as zero wrong procedures.
- **Inappropriate evidence abstention:** no passages despite labeled required evidence
  in applicable documents, excluding conflict/context-review blocks. This measures source
  delivery, not answer abstention. A request for live state still cannot be answered from
  documentation, even if its monitoring procedure is retrieved.
- **Latency:** five untraced repository calls per case/mode after one untimed warmup.
  Includes reader connection, RLS checks, source snapshot and search; excludes HTTP/OIDC,
  compilation and trace collection. Retain samples and report p50/p95. Fixed arm order,
  small sample size and host load prevent a production latency or speedup claim.
- **Failure stages:** applicability/context, exact-term/HTTP/scope guards, body overlap,
  coverage (baselines), relative score, top-K and assembly. Prose-only conflict remains
  a separate capability failure, even when both passages are retrieved.

Gold labels have incomplete negative coverage. Before promotion, freeze extra independent
questions with positive, unrelated-but-lexically-similar, prohibited-action, unknown-context,
wrong-environment/version, withdrawn and restricted-role cases. Adjudicate optional evidence
without changing labels to fit candidate results. Any question used for tuning is development
material; reserve a separate unseen partition and report that partition separately.

## Optional embedding arm: design, not implementation

Do not insert an all-corpus vector search ahead of eligibility and filter its top-K later.
The first implementation should encode/score **only the eligible snapshot** for the current
role/context. An in-memory exact similarity search is sufficient for this small corpus;
no shared ANN index or production ingestion changes are justified yet. Merge lexical and
semantic ranks with a fixed reciprocal-rank rule; freeze its constant, candidate limits
and abstention rule before evaluating. Embeddings measure similarity, not operational safety
or claim support. Exact numbers, scope limitations and source-role distinctions still matter.

A runnable hybrid arm requires a local manifest with:

- Model identity, immutable upstream revision/version, license and intended language/domain.
- SHA-256 of every weight, configuration and tokenizer file, plus a bundle manifest hash.
- Runtime/library versions, tokenizer revision, dimensions, pooling, normalization, maximum
  tokens, truncation/chunking, precision/quantization, thread count and random seed.
- Verified offline loading, no network fallback, and a pinned model held outside Git.
- Measured CPU/architecture, RAM and free-disk requirements, batch size, indexing time,
  warm/cold latency, peak memory and whether acceleration is required. No numeric budget
  or model performance is claimed before a model is chosen and measured.

Cache keys must bind document/revision/content hash, evidence-unit boundaries and the full
model/tokenizer configuration. ACL, approval, withdrawal and revision changes require fresh
eligibility checks on every request even when an embedding is cached. Never expose vectors,
unfiltered index counts, IDs, scores, titles or snippets to an unauthorized caller. Keep
local caches owner-only and logs free of source-derived data. Retain the existing request
snapshot contract and ensure the next request observes revocation. No persistent generated
answer store is introduced.

If model availability, licensing, resource requirements or a checksum cannot be established,
record the hybrid arm as not run. Never silently substitute a cloud model or a different
local model. This PR follows that rule and reports the absent arm explicitly.

## Reproduction

```sh
.venv/bin/python scripts/trace_external_pack.py \
  --sources /absolute/path/to/source-directory \
  --gold /absolute/path/to/questions_and_gold.json \
  --baseline /absolute/path/to/private-first-run-directory \
  --output /absolute/path/to/new-private-generalization-directory \
  --generalization-experiment
```

The generalization and passage-scope flags are mutually exclusive to prevent accidentally
combining changes. The report records source/gold/history hashes, compiled-class checksum,
Git revision/dirty state, Python/Java/PostgreSQL versions, Maven wrapper/POM hashes and host
architecture/CPU count. Each arm sees the same generated database and source snapshot rules.
Database lifecycle cleanup is owned by the evaluator; the working app and demo database
are never targeted. Protected-response integrity failures abort accepted reporting. In this
arm, authorized but labeled-wrong sources are retained privately as negative findings rather
than aborting and hiding a precision regression. Legacy replay behavior is unchanged.

See [measured development findings](retrieval-generalization-results.md). Passing tests is
not a promotion decision. No automatic merge, runtime cutover or default-mode change follows.
