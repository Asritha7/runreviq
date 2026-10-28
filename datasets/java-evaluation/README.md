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
