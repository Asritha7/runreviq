# Question-based passage search

Enter an optional question in the context form. For example, use “Compare failing versus healthy replicas”, identity-service, production, 2.4.0, and enabled key rotation. Leave the question blank to use the original applicability selector.

`POST /v1/search` accepts the same context fields as `/v1/select`, plus `question` (1–500 characters, at least one non-whitespace character). Authentication and server-assigned reader roles are unchanged; a caller cannot supply a role. Search uses a restricted PostgreSQL reader in one read-only repeatable-read transaction. Current ACLs, current revisions, withdrawals, approval and applicability are checked before ranking. Any applicable conflict or uncertain metadata blocks passage recommendations, even if the question appears unrelated to that conflict.

The result retains the selection fields and adds `retrieval_method: postgres_english_lexical_v1` and `passages`. Each passage carries `document_id`, `revision`, `title`, `start_line`, `end_line`, `quote`, and `source_sha256` (SHA-256 of the complete UTF-8 source). The viewer opens that exact revision and highlights matching lines. Search with no hits returns `insufficient_evidence`, no passages and no recommended documents. Context and conflict messages remain visible.

## Baseline algorithm

- Split exact stored content into blank-line paragraphs, excluding standalone Markdown headings, with at most 20 whole lines per passage. Long individual lines remain intact. Quotes join `splitlines()` with newline, matching existing citation validation.
- Normalize the question with PostgreSQL's English text-search configuration. Match any remaining lexeme and rank with `ts_rank_cd`, normalization 32. Return at most five passages; ties follow document ID and source line order.
- Passage vectors are computed per request. No persistent index, new migration, external service, embeddings, model download or paid fallback is added. Both ingested fixtures and explicitly published snapshots use the same current-version tables.
- Transactions already in flight may reflect an earlier ACL or withdrawal state. Fresh requests reflect committed changes; already displayed evidence is not remotely erased. Opening a source rechecks current reading permission and shows lifecycle status.

## What the scores do not establish

These are matching excerpts, not generated answers, diagnosis, calibrated confidence or proof that a passage answers the question. English stemming does not understand synonyms, negation, intent or causal relationships. OR matching can retrieve unrelated passages sharing generic words, including boilerplate. The implementation scans authorized source content and has not been load-tested for large corpora; SQL statements have a five-second timeout, but that is not a complete request deadline. No semantic conflict detection exists; the existing manually reviewed claim rules govern conflicts.

## Reproducible evaluation

Run `.venv/bin/python -m runreviq.evaluate_search` with the local database available. It creates, seeds and drops its own disposable database; it does not write to the development evidence corpus. The same 30-case evaluation runs inside the required integration-test suite in CI.

`datasets/evaluation/questions.json` labels expected status and exact source revision/line ranges. It contains 17 retrieval questions and 13 refusal/context/conflict cases. Reports include recall@5, mean reciprocal rank, refusal accuracy, citation integrity, per-case outcomes and local latency. Citation integrity checks source text and fingerprint, not semantic answer quality. A nonzero exit indicates at least one case failed.

Initial local run on 2026-10-25: 30/30 cases passed, recall@5 1.0, MRR 1.0, refusal accuracy 1.0, and citation integrity 1.0; median search latency approximately 6 ms. These are a small, deliberately straightforward fixture-based regression set, **not a held-out benchmark or production effectiveness claim**. Top-five results may still include irrelevant extra passages; precision is not measured by this initial suite.

Next: obtain sanitized operational runbooks and independently written incident questions, label expected evidence before tuning, add hard negatives and synonym-heavy questions, then measure precision and abstention alongside recall. Compare a local semantic/hybrid retriever against this fixed baseline before adding a grounded explanation model.
