# Java HTTP retrieval reports

These reports were recorded on 28 October 2026 using the existing fictional public
corpora and question labels. They measure the Java reviewed-v5 implementation through
HTTP, not response equality with Python. They are **same-author development results**;
no new independent or held-out validation is claimed.

- [Original regression](regression-v5.json): 30/30 strict evidence sets match.
- [Realistic Harbor](realistic-v5.json): 50/50 outcome matches, but only 20/50 exact
  evidence sets and 38.28% returned-result precision. The strict gate fails.
- [Paraphrase probes](paraphrase-v5.json): 7/8 outcome matches, 4/8 exact evidence sets
  and 20.83% returned-result precision. The strict gate fails.

Each report includes complete retrieved passages, missing/extra targets, category
metrics, the source/question fingerprint, and the built Java jar SHA-256. Latencies
are local request samples, not a reproducible performance baseline. Jar hashes may
change on rebuild; source/question fingerprints and semantic results identify the
benchmark inputs and behavior.

Reproduce with `.venv/bin/python -m runreviq.evaluate_java --suite <suite> --report-only`.
This creates a new isolated Java process and disposable database. It imports source
material only; questions and target labels remain in the evaluator. Keep these recorded
reports unchanged; record a new named report for an intentional algorithm/data revision.

See [metric definitions and limitations](../../docs/java-quality-evaluation.md).

## Focused-v6 candidate

The original v5 reports remain unchanged. `validation-v5.json` adds the previously
unrecorded validation-suite baseline. Each `*-focused-v6.json` report records the Java
candidate on the identical suite, and each `*-v5-v6-comparison.json` records its paired
metric deltas and per-case regressions. All inputs are existing development material.

The candidate improves realistic precision to 70.57%, validation precision to 60.42%,
and paraphrase precision to 54.17%, with no lost labeled evidence in these recorded
comparisons. It still fails their strict exact-set gates and remains opt-in. Reproduce
with `--search-mode focused-v6`; compare using `python -m runreviq.compare_java`.
See [algorithm, measured trade-offs and tests](../../docs/focused-retrieval.md).
