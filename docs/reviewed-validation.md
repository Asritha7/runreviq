# Reviewed evidence retrieval validation v1

This is an implementation-authored **development ablation**, not independent human validation, a production-readiness score, or a comparison with a commercial RAG system. It introduces no model or paid service. The current API and demo corpus are not reconfigured or re-ingested by the evaluator.

## Frozen inputs and provenance

`datasets/reviewed-validation/` contains 26 Markdown sources and 60 question/context cases. `freeze.json` fingerprints source text, catalog, questions and simulated review records before comparative execution. An initial fixture audit corrected two invoice prohibition action identifiers before the committed report; the initial draft freeze and change note are retained. Earlier executions were development tests. Old benchmark files and reports are untouched. Gold spans remain in a separate question file, never indexed as source documents. Changing inputs requires a deliberate new fixture version and renewed review; do not regenerate hashes to conceal edits.

The synthetic catalog includes procedure duplicates, cited prohibitions, monitoring references, plausible historical causes and distractors for HTTP status, environment, software version, restricted access and superseded status. Two separate operational services and a conflict service exercise isolation. Repeated contexts/templates limit diversity; 60 cases do not mean 60 independent incidents. This is a small engineering benchmark.

`reviews.json` records author/reviewer identifiers, exact approved fixture profiles and source hashes. These are **simulated approvals**, not claims that a human independently inspected these documents. The evaluator uses trusted fixture ingestion in disposable databases, as existing benchmarks do. Actual production author/reviewer/publisher enforcement is covered separately by the publication lifecycle integration tests. It does not assign real reviewers or publish to the working corpus.

## Three paired configurations

All arms use identical Markdown, questions, permissions, status, normalized claims and software/environment context:

1. `legacy_paragraphs`: current v5 engine, profiles removed; paragraph extraction and legacy numeric guards. This is a controlled pre-profile configuration, **not execution of the historical v4 binary**.
2. `reviewed_ranges`: profiles retained, including HTTP applicability and marked source ranges; top five qualified results without complementary bundling.
3. `reviewed_bundle`: profiles plus current complementary selection and deduplication.

The second arm combines HTTP scope and range selection; their separate effects are not identified by this experiment. It is therefore incorrect to attribute all improvement to ranking. Unknown HTTP scope may legitimately yield different statuses in the baseline. All arms are judged against the same frozen intended applicability. Existing frozen legacy reports remain separate regressions, not directly comparable scores on this new corpus.

## Gold relevance and denominators

A result supports a gold span only when document ID and revision agree and the returned range **fully contains** the labeled span. Partial overlap earns no credit. This accommodates paragraph/unit boundary differences without changing labels per configuration. Longer excerpts may still contain noise; containment is not semantic entailment.

Whitespace-identical gold quotes form explicit duplicate-alternative groups. Retrieval of either labeled document satisfies that group. Repeated retrieval of the same group earns no additional precision credit. Different wording, negation or numbers are not merged. Gold relevance remains a human labeling judgment; unlisted passages are treated as irrelevant and labels may be incomplete.

- Recall@5: supported distinct gold groups / all gold groups, macro-averaged over expected-selected cases.
- Precision@returned-5: useful nonduplicate hits / returned hits, macro-averaged over expected-selected cases; no hits scores zero.
- Fixed Precision@5: useful nonduplicate hits / 5, averaged over expected-selected cases. Returning fewer than five relevant excerpts may be desirable despite a lower fixed score.
- False-positive rate: expected-withholding cases returning any passage / all expected-withholding cases.
- False-conflict rate: cases incorrectly returning `conflict` / cases whose expected status is not `conflict`.
- Appropriate abstention: expected-withholding cases returning the correct withholding status and no passages / all expected-withholding cases.
- Incorrect abstention: expected-selected cases returning a non-selected status / all expected-selected cases.
- Access-isolation failures: cases exposing a restricted fixture identifier or its unique body text anywhere in the serialized result / all cases. This is a fixture disclosure check, not a general information-flow proof; shared titles cannot be attributed uniquely.
- Inapplicable-passage failures: cases with a returned passage failing frozen reviewed applicability / all cases.
- Citation failures: cases with an incorrect source quote, revision or hash / all cases. No-quotation cases pass the integrity invariant vacuously; this rate is not positive citation-support coverage.

Case pass requires expected status, full gold-group recall for positive cases (no returned passages otherwise), citation integrity, no restricted disclosure and no inapplicable passages. It does **not** require perfect precision. Rate summaries include their numerators/denominators and use null when a denominator is empty.

## Offline failure diagnostics

Search accepts Python-only `_diagnostics` and `_assemble` controls. Strict API input rejects them; diagnostics are never attached to public search results. The trace contains authorized selection state, effective context, candidate ranges, lexical survivors, relevance-qualified passages and the pre-bundle pool. Diagnostic mode collects beyond the usual cap; regression tests compare its returned result with ordinary execution.

Missing gold groups receive the earliest observed exclusion: authorization, applicability, metadata/context incompleteness, global conflict/review withholding, source-range omission, lexical gate, relevance ranking, candidate cap or final result/bundle selection. These are **pipeline observations**, not proof of root cause. Incorrect classifications need independent label review; the evaluator cannot infer that metadata is wrong from a miss alone. Expected-negative cases retain full expected/actual statuses and traces for inspection.

## Reproduction

```sh
.venv/bin/python -m runreviq.evaluate_reviewed --validate-only
.venv/bin/python -m runreviq.evaluate_reviewed --report-only > .local/reviewed-validation-v1.json
RUNREVIQ_TEST_DATABASE=1 .venv/bin/python -m unittest discover -s tests -v
node --test tests/web.test.cjs
```

Each configuration creates, seeds and drops a uniquely named `opslens_test_reviewed_*` database. No database name or external fixture path can be supplied through this CLI. Reports retain complete per-case results, traces, configuration hashes, frozen fixture hash, engine identifier, code revision/dirty state and Python implementation fingerprint. `--report-only` tolerates relevance/status failures but never suppresses detected disclosure or citation-integrity failures.

Future genuinely unseen cases require separate authorship, freezing and explicit provenance. These questions are now development data; do not describe subsequent tuning on them as held-out validation.

## Recorded results

[Complete raw results and traces](../reports/reviewed-validation-v1/results.json) were captured at clean code revision `fffdc25681abf9b1994b6e200f6c891abfab8b18`; the [report manifest](../reports/reviewed-validation-v1/manifest.json) records its checksum and input/implementation fingerprints.

| Measure | Legacy paragraphs | Reviewed ranges/scope | Reviewed bundle |
| --- | ---: | ---: | ---: |
| Case checks passed | 51/60 | 58/60 | 58/60 |
| Recall@5, 36 positive cases | 97.22% | 97.22% | 97.22% |
| Precision@returned-5 | 84.26% | 84.26% | 94.44% |
| Fixed Precision@5 | 21.11% | 21.11% | 21.11% |
| False-positive withholding failures | 4/24 | 0/24 | 0/24 |
| False conflicts | 3/59 | 0/59 | 0/59 |
| Appropriate abstention | 17/24 | 24/24 | 24/24 |
| Incorrect abstention | 0/36 | 0/36 | 0/36 |
| Restricted disclosure cases | 0/60 | 0/60 | 0/60 |
| Inapplicable passage cases | 4/60 | 0/60 | 0/60 |
| Citation integrity failure cases | 0/60 | 0/60 | 0/60 |

**Interpretation:** HTTP scope improves intended-context handling. Complementary bundling improves precision primarily by removing duplicate procedures; it does not find additional gold evidence in this corpus. Unchanged recall and fixed Precision@5 make that limitation visible. The result is not evidence of a general semantic ranking improvement. These figures are not comparable to the existing legacy corpus's 38.28% precision because both corpus and gold relevance definitions differ.

**Unresolved failures:** Q-009 and Q-037 ask for signing-key propagation diagnostics together with dashboard error rates. Both still miss the procedure alternative group at the relevance-ranking stage, before the candidate/result cap. Reserving a role slot cannot rescue a passage rejected by ranking. Neither labels nor thresholds were tuned after this report. These two repeated-family failures are development evidence for a future fix, followed by a fresh evaluation set.

**Limits:** only two templated operational families plus a small conflict family; no independently reviewed classifications; no live telemetry or generated answers; no general semantic entailment verifier; no claim of production readiness. No golden-span omission or truncation failure occurred in this particular report, although diagnostic unit tests exercise those categories. Existing authorization/session/revocation and publication/withdrawal suites remain necessary: this benchmark does not replace them. Full local checks passed: 206 Python tests and 33 browser logic tests, including all three paired arms and diagnostic-versus-normal output equality.
