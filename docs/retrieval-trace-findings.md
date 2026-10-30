# Isolated retrieval tracing — 30 October 2026

## Result

The seven externally supplied questions that missed already-applicable evidence are
reproduced with unchanged source passages, adapter assumptions, questions and gold labels.
Both reviewed-v5 and focused-v6 produce the original frozen responses exactly.
Traced and untraced execution also match, and repeated traces are identical.

**Dominant mechanism: document coverage rejects six questions (seven required passages).**
The remaining question (two required passages) is stopped by the global excluded-sentence
term gate. No required passage in these seven cases is first lost to exact-term matching,
HTTP substantive overlap, relative-score selection, top-K truncation or evidence bundling.
These counts describe this small synthetic pack, not general production accuracy.

## First decisive exclusion per case

Only case IDs and numeric findings are published here. Questions, source identifiers,
quotes, token lists, exact coordinates and complete raw traces remain in private local
storage outside the searchable corpus and repository.

| Case | Required passages | First decisive stage | Raw trace evidence (both modes) |
| --- | ---: | --- | --- |
| F02 | 1 | Document coverage | Coverage 0.2746042551 < 0.35; body coverage 0.2746042551 |
| F03 | 1 | Document coverage | Coverage 0.2618285756 < 0.35; body coverage 0.2618285756 |
| F04 | 2 | Global excluded-sentence terms | `exclusion_gate.excluded=true`; nonempty trigger terms; ranking not executed |
| F05 | 1 | Document coverage | Coverage 0.3354350434 < 0.35; body coverage 0.3354350434 |
| F06 | 1 | Document coverage | Coverage 0.1362425662 < 0.35; body coverage 0.1362425662 |
| F08 | 2 | Document coverage | Coverage 0.1790811912 and 0.2810363114 < 0.35; respective body coverage is identical |
| F12 | 1 | Document coverage | Coverage 0.3212043019 < 0.35; body coverage 0.2141362013 |

The coverage check precedes the body-coverage check in the algorithm. F06 also fails the
0.15 body floor, but document coverage is its **first** decisive exclusion. F12 passes
the exact-term/HTTP gate before failing coverage; this is not a missing literal HTTP-code
failure. Scores are not computed for candidates rejected before scoring, and the trace
does not invent counterfactual scores for them.

For each missing passage, the private report includes the exact event that caused the
first exclusion, its coordinates, query weights, matched body terms, document terms,
coverage and thresholds. Global exclusion records its trigger terms; lexical events
record both full-quote terms and terms after excluded sentences are removed. The trace
records source hashes and authorized snapshot identifiers, making the observations
checkable against the unchanged source corpus. The complete event sequence is retained,
not merely the derived classification.

## Why this happens

Document coverage is weighted overlap between query terms and a document's candidate
body/title/heading terms, divided by the weight of the entire normalized query. Terms
absent from the candidate corpus still contribute to the denominator (and receive higher
weights). Natural incident phrasing and paraphrases therefore reduce coverage even when
some relevant operational terms match. This observation does not establish that every
unmatched term is dispensable: some encode critical intent or constraints.

The global exclusion gate removes sentences matching explicit scope exclusions, then
finds query terms present only in those excluded sentences across candidates. A match
vetoes the entire result before ranking. In F04, that gate suppresses both the historical
and diagnostic evidence. Returning those excerpts would still not establish a current
root cause; their evidence-role and uncertainty distinctions must remain intact.

## Separate categories

- F01 and F09 stop for metadata/context review. Unsupported preconditions remain
  unverified under the frozen adapter; neither is counted as a ranking failure here.
- F07 retrieves both opposing passages but reports selected, not conflict. Prose-only
  contradiction detection remains unsupported and is not credited as task success.
- F10P has no required passage; semantic behavior remains ungraded. Its authorized
  snapshot excludes the security-only source. F10S and F11 retrieve required evidence.

## Smallest justified next experiment — not implemented here

First isolate the global scope-exclusion veto to the affected passage/sentence while
preserving explicit source limitations and historical/diagnostic role distinctions.
That is a smaller, directly motivated experiment than lowering all coverage floors:
it addresses one demonstrated query-wide suppression without relaxing applicability,
authorization or the coverage threshold. Evaluate negative scope questions and current-
cause questions alongside F04; no result should turn historical precedent into proof.

The dominant coverage failures need a separate candidate: consistent query normalization
or more deliberate treatment of incidental versus substantive terms. Do not simply drop
all unseen query terms or lower the floor until this pack passes; both can admit unrelated
material. F06 would additionally require addressing body coverage. Reserve fresh questions
before promotion, because these traced cases are now development evidence. No algorithm,
embedding, model, approval assumption or live default changes in this milestone.

## Reproduction and isolation

```sh
.venv/bin/python scripts/trace_external_pack.py \
  --sources /absolute/path/to/source-directory \
  --gold /absolute/path/to/questions_and_gold.json \
  --baseline /absolute/path/to/private-first-run-directory \
  --output /absolute/path/to/new-private-trace-directory
```

Requires the local PostgreSQL service and Java/Maven toolchain. The command creates and
removes a generated evaluation database. It uses the same import adapter and source
revision order as the original run. It validates input fingerprints and paired baseline
artifacts before replay. The original files are hashed before and after; output uses a
new owner-only directory and files and refuses overwrite. Failed attempts retain only a
private runner log, never an accepted diagnostic report.

The Java runner is a test class excluded from the executable application JAR. It receives
questions and frozen responses, not gold labels. It opens the existing restricted reader
connections and validates the same source checksums under the same RLS/read-only snapshot
contract. The Python evaluator handles gold comparisons outside the database/runtime.
Source revisions, ACLs, checksums and withdrawal state are compared before and after.

The observer has no global registry, property, endpoint, file writer or logging hook.
Normal API calls use the untraced overload. Trace events are defensive immutable copies;
collectors cannot mutate candidates through them. Tests reject trace/diagnostics request
fields, compare full HTTP responses with traced results, and exercise ACL revocation,
withdrawal and visible/hidden corruption. Collection is invoked only by isolated tests.

Raw traces are sensitive even when their contents were authorized for the test role.
Keep them private; do not upload them as CI artifacts or import them as source documents.
Fingerprints bind this replay to its files and compiled classes but are not a signature
or independent audit of externally supplied reports. Authorship independence remains
externally declared, not independently verified.

## Validation

- Database-enabled Python suite: 235 tests reported, 233 passed, two opt-in Java
  evaluation tests skipped in that run. The separate opt-in Java evaluation run passed
  all 14 tests, including both actual Java runtime evaluations.
- Maven verification passed; JDBC read/authoring/publication integration passed (98
  checks across the separate database test invocations).
- Search HTTP parity and boundary integration passed in each mode (114 checks per mode),
  including traced/untraced equality, deterministic traces and authorized snapshots.
- Four focused Java observer tests and six Python diagnosis tests passed.
- External replay covers all 13 cases in both modes, not only the seven misses. Every
  response matches the frozen result and repeated traces match exactly. Source and
  publication state, input hashes and independently read authorized source hashes agree.
- The executable JAR excludes the offline runner. No private pack or trace is committed.

These checks establish behavior preservation for the exercised cases; they are not a
claim that all possible retrieval queries or semantic judgments are correct.
