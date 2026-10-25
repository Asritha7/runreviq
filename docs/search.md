# Question-based passage search

Enter an optional question in the context form. For example, use “Compare failing versus healthy replicas”, identity-service, production, 2.4.0, and enabled key rotation. Leave the question blank to use the original applicability selector.

`POST /v1/search` accepts the same context fields as `/v1/select`, plus `question` (1–500 characters, at least one non-whitespace character). Authentication and server-assigned reader roles are unchanged; a caller cannot supply a role. Search uses a restricted PostgreSQL reader in one read-only repeatable-read transaction. Current ACLs, current revisions, withdrawals, approval and applicability are checked before ranking. Any applicable conflict or uncertain metadata blocks passage recommendations, even if the question appears unrelated to that conflict.

The result retains the selection fields and adds `retrieval_method: postgres_english_operational_v3` and `passages`. Each passage carries `document_id`, `revision`, `title`, `start_line`, `end_line`, `quote`, and `source_sha256` (SHA-256 of the complete UTF-8 source). The viewer opens that exact revision and highlights matching lines. Search with no hits returns `insufficient_evidence`, no passages and no recommended documents. Context and conflict messages remain visible.

## Current ranking

The source splitter remains blank-line paragraphs capped at 20 whole lines, with exact revision/line citations. PostgreSQL English stemming now feeds weighted query coverage gates and length-normalized ranking. Title/heading context supports topic matching, and recognized scope-disclaimer sentences do not contribute scoring terms. Returned quotes remain unchanged. Up to five qualifying passages are returned; weak matches can yield insufficient evidence. The v3 vocabulary and exact-code/scope-only gates run before ranking; see [current rules and limitations](operational-query-matching.md) and [the underlying v2 ranking](search-ranking.md).

There is no persistent index or external service. Both ingested fixtures and published snapshots use the same current-version tables. In-flight transactions may reflect an earlier ACL or withdrawal state; opening a revision rechecks current read permission and lifecycle status.

## What the scores do not establish

These are matching excerpts, not generated answers, diagnosis, calibrated confidence or proof that a passage answers the question. English stemming does not understand synonyms, negation, intent or causal relationships. Lexical overlap and the heuristic coverage thresholds can still retrieve unrelated passages or miss useful paraphrases. The implementation scans authorized source content and has not been load-tested for large corpora; SQL statements have a five-second timeout, but that is not a complete request deadline. No semantic conflict detection exists; the existing manually reviewed claim rules govern conflicts.

## Reproducible evaluation

Run `.venv/bin/python -m runreviq.evaluate_search` with the local database available. It creates, seeds and drops its own disposable database; it does not write to the development evidence corpus. The same 30-case evaluation runs inside the required integration-test suite in CI.

`datasets/evaluation/questions.json` labels expected status and exact source revision/line ranges. It contains 17 retrieval questions and 13 refusal/context/conflict cases. Reports include recall@5, mean reciprocal rank, refusal accuracy, citation integrity, per-case outcomes and local latency. Citation integrity checks source text and fingerprint, not semantic answer quality. A nonzero exit indicates at least one case failed.

Initial local run on 2026-10-25: 30/30 cases passed, recall@5 1.0, MRR 1.0, refusal accuracy 1.0, and citation integrity 1.0; median search latency approximately 6 ms. These are a small, deliberately straightforward fixture-based regression set, **not a held-out benchmark or production effectiveness claim**. Top-five results may still include irrelevant extra passages. The expanded evaluator now reports precision separately.

A separate 13-document, 50-question fictional benchmark now exposes noisy matches and incorrect withholding in this lexical baseline. See [evaluation definitions and results](search-evaluation.md). Current v3 matching improves measured recall, precision and withholding; remaining relevance failures are reported openly. Independent human labeling and sanitized real-incident validation remain future work.
