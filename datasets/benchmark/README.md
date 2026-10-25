# Harbor fictional operational benchmark v1

This development benchmark contains 13 original fictional runbooks and 50 questions. It contains no customer documents, real incidents, credentials or real-system instructions. Topics include signing keys, clock skew, database pools and locks, deployments, rollback, worker queues, cache memory, DNS, security audit records, conflicting policies and incomplete metadata.

The corpus and labels were authored before the first retrieval run on 2026-10-25. Search ranking was not changed to improve these scores. The same author created the documents and questions; this is **not independent human validation or a held-out benchmark**. The question list and source content should remain fixed for comparisons. Corrections require an explained dataset revision and a new fingerprint; do not change labels just because search misses them.

The nested `datasets/northstar/` folder lets the existing ingestion validator run with this folder as its explicit root. It does not add these sources to the normal Northstar catalog. The evaluator loads this corpus only in a new disposable test database. `catalog.json` is manually authored test authority; prose does not assign roles or approval.

## Labels

There are 32 answerable questions and 18 expected withholding cases. Each question records its category, explicit reader/context, rationale, expected status and exact relevant revision/line ranges. Positive labels identify directly useful paragraphs, not every paragraph that shares the topic. Multi-source question H32 requires both labeled sources. Other returned paragraphs count as irrelevant under this strict rubric; a future independent review may find legitimate alternative evidence.

The 18 withholding cases include nine unsupported questions sharing source terminology, two access cases, three context cases, a conflict, unverified metadata, stopwords and an unrelated query. A source saying “certificate renewal is outside this procedure” is not sufficient to answer “How do I renew the TLS certificate?” The expected product behavior is to withhold a guidance recommendation. These tests deliberately expose the difference between a text match and answerable evidence; the current endpoint is only a lexical retriever.

H42 asks for a security-only marker as a platform reader. PostgreSQL tokenizes the marker into words including “Harbor”, which also appears in public sources. Returning those public paragraphs is a relevance failure, not a disclosure of the restricted marker. The integration test separately asserts that unauthorized results contain neither the security source nor its marker.

## Recorded baseline

`baseline.json` records the unchanged `postgres_english_lexical_v1` search against these labels, including source/case fingerprint, per-category scores, missed labels and irrelevant returned references. Full quotes can be regenerated using the command below.

| Measure | Initial result |
| --- | ---: |
| Cases meeting status, complete recall and integrity expectations | 36 / 50 |
| Mean recall@5 on answerable questions | 87.5% |
| Mean precision among returned top-five passages | 18.54% |
| Mean reciprocal rank of first relevant passage | 0.586 |
| Correct withholding | 8 / 18 (44.44%) |
| False-positive retrieval on withholding cases | 10 / 18 (55.56%) |
| Exact quote and fingerprint integrity | 100% |
| Median / p95 search time on this local run | 7.285 / 9.03 ms |

The 36 passing cases do **not** mean 36 clean result lists: a positive case passes when all expected passages are found and status/integrity checks hold, even if additional irrelevant passages appear. Precision measures that separate weakness. Timing includes database connection and search, excludes benchmark quote verification, and is not a load test or performance guarantee.

The original 30-case regression remains a separate suite. This harder corpus exposes missed recovery paragraphs (H21, H24, H27), a paraphrased readiness question (H29), and every unsupported-overlap question (H33–H41). Future work should address irrelevant matches and withholding before adding generated explanations.

## Run

From the repository root, with the existing local PostgreSQL setup available:

```sh
.venv/bin/python -m runreviq.evaluate_search --suite realistic --report-only > realistic-report.json
```

`--report-only` permits known relevance failures so a baseline report can be collected. Broken labels, runtime failures and returned source integrity failures still fail. Without that option, the command exits 1 when any case misses its expected outcome. The recorded v1 baseline had 14 failures; current v2 ranking has six. The default command without `--suite` continues to run the original 30-case regression. Do not commit arbitrary report output; the checked-in baseline is an intentional fixture.

See [metric definitions](../../docs/search-evaluation.md). No paid services, downloaded models, new database migrations or live API changes are required.

## v2 comparison

The original corpus, labels and `baseline.json` remain unchanged. `comparison-v2.json` records the coverage-based ranking comparison: 44/50 outcomes, unchanged 87.5% recall, 35.47% precision and 16/18 correct withholding. `validation-questions.json` adds 12 same-author questions fixed before v2 experiments; the final ranking met 12/12 expected outcomes. This is additional development evidence, not independent human validation. See [ranking changes and remaining failures](../../docs/search-ranking.md).
