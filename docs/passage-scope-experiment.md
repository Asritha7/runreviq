# Passage-scoped exclusion experiment

## Decision: evaluator-only; no promotion

The narrow candidate replaces the query-wide excluded-sentence veto with a per-passage
veto. It uses reviewed-v5 scoring, normalization, thresholds, exact-term and HTTP gates,
source checks and assembly unchanged. There is no Spring configuration value or HTTP
request option for this candidate. Production continues to accept only reviewed-v5 and
focused-v6, with reviewed-v5 still the default.

The candidate is exposed only to the test-classpath offline runner. It does not introduce
an embedding, LLM, paid dependency, new approval rule or live configuration change.

## Rule being tested

For each candidate passage, compute terms present in the full quote but absent from its
searchable sentences and title/heading context. If the query intersects those terms,
exclude that passage. Other authorized, applicable passages continue through the existing
ranking checks. Exact-term and HTTP eligibility are still applied first.

Unlike the baseline's aggregate term sets, another passage cannot supply support that
overrides a passage's own scope exclusion. This is a lexical scope heuristic, not semantic
entailment or a general natural-language prohibition detector. The existing exclusion
patterns and source quotations remain unchanged. Source limitations are not rewritten.

## Frozen external-pack outcome

All 13 cases were replayed in reviewed-v5, focused-v6 and the evaluator-only candidate.
Both existing modes reproduce their original frozen responses. All three runs retain
traced/untraced equality, deterministic traces, reader authorization, source integrity
and unchanged publication/source state.

The candidate produces the **same statuses, passages and remaining response fields** as
reviewed-v5 across this pack, except for its explicit experimental retrieval-method label.
Required-evidence recall, strict returned precision and forbidden-passage results are
unchanged. No forbidden passage is returned. This is not evidence of a quality gain.

F04 explains why the narrower rule alone is insufficient:

| Evidence | Baseline first exclusion | Candidate first exclusion |
| --- | --- | --- |
| Historical passage | Global excluded-sentence veto | Its own passage exclusion |
| Diagnostic passage | Global excluded-sentence veto | Document coverage: 0.1029093105 < 0.35 |

The diagnostic passage now reaches ranking, revealing another rejection that the global
veto previously masked. It also falls below the 0.15 body-coverage floor, but document
coverage is checked first. The experiment deliberately does not adjust either threshold.
The other six eligible-evidence misses remain coverage failures. F01/F09 metadata review
and F07 prose contradiction handling are unchanged and remain separate limitations.

Original questions, gold, sources, adapter assumptions, first-run reports and PR #38
traces remain untouched. New results go into a separate owner-only directory. These
cases are development evidence after this inspection; they are not a fresh held-out set.

## Mechanism and security tests

Synthetic tests, separate from the external pack, demonstrate:

- A historical passage's explicit scope exclusion no longer vetoes a relevant diagnostic
  passage with adequate lexical support.
- A question supported only by excluded terms still abstains.
- Support in a sibling passage cannot override a passage's own explicit scope exclusion.
- With no scope exclusion, response content and historical uncertainty notes are preserved.

The search integration suite also exercises the candidate on the frozen development
requests, asserting unchanged applicability/context/conflict boundaries, authorized source
references and exact citation hashes/ranges. Revocation, withdrawal and corruption tests
exercise candidate traces. These boundary checks are not a general quality evaluation of
the candidate on all development questions; no broad improvement claim is made.

## Reproduce privately

```sh
.venv/bin/python scripts/trace_external_pack.py \
  --sources /absolute/path/to/source-directory \
  --gold /absolute/path/to/questions_and_gold.json \
  --baseline /absolute/path/to/private-first-run-directory \
  --output /absolute/path/to/new-private-experiment-directory \
  --passage-scope-experiment
```

Without the new flag, the command still runs the two baseline modes only. The candidate's
internal identifier is `passage-scope-v1`; it is not accepted by the running application.
The report retains raw events, per-case measurements and response comparisons. Gold labels
remain outside the Java runner and SQL. Invalid source references or forbidden returned
passages prevent creation of an accepted report. Raw traces remain private even when their
contents were authorized for an evaluator role.

## Next bounded work

Do not promote this candidate on the strength of synthetic mechanism tests. The next
useful step is a separately designed coverage/normalization experiment with irrelevant
and negative queries as controls. Preserve substantive incident constraints and current-
cause uncertainty; dropping every unmatched term or broadly lowering floors could admit
irrelevant evidence. Reserve additional independent questions before judging promotion.
