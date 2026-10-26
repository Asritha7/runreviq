# Measuring retrieval effectiveness

Runreviq now has four offline evaluation suites: the original 30-case `regression` suite and the larger 50-case `realistic` fictional development benchmark. The 12-question `validation` and eight-question `paraphrase` suites reuse the larger corpus. The larger corpus has 13 longer runbooks and deliberately difficult paraphrases, distractors and unsupported questions. None of these suites proves effectiveness on real incidents. The larger corpus, fixed labels and recorded results are in [datasets/benchmark](../datasets/benchmark/README.md).

## Commands and isolation

```sh
.venv/bin/python -m runreviq.evaluate_search
.venv/bin/python -m runreviq.evaluate_search --suite realistic --report-only
```

Both commands create a uniquely named disposable database, apply the normal migrations and restricted roles, ingest only the selected corpus and drop the database in a `finally` block. They use existing local credentials and never add benchmark sources to the development collection. No external model calls are made. Abrupt process termination can prevent cleanup; database integration runs are disposable, not production maintenance tools.

The realistic suite currently has expected quality failures. `--report-only` explicitly makes those nonfatal; it does not hide them from JSON output. Default mode exits nonzero if any case fails. Citation/current-source integrity failures remain fatal even in report-only mode. Invalid label boundaries, duplicate IDs/labels and inaccessible positive labels raise errors before scoring.

## Metric definitions

All rankings are limited to the five passages returned by the current search endpoint. A relevant hit must match the labeled document ID, revision and exact line range. Labels use a strict directly-useful-evidence rubric; they are manually authored and remain open to independent review.

- **Recall@5:** fraction of distinct labeled passages retrieved, averaged across answerable questions. For a two-source question, retrieving one source gives 0.5, not 1.0.
- **Hit rate@5:** fraction of answerable questions with at least one labeled passage returned. This is reported separately from recall.
- **Precision at returned five:** relevant distinct passages divided by the number actually returned, averaged across answerable questions. An empty result scores zero; returning one relevant passage alone scores 1.0 rather than 0.2.
- **Mean reciprocal rank:** average of `1 / first relevant rank` across answerable questions; no relevant result contributes zero.
- **Refusal accuracy:** fraction of expected withholding cases with the exact expected status, no returned passages, no recommended documents and valid integrity checks. This includes missing context, review and conflicts as well as insufficient evidence.
- **False-positive rate:** fraction of withholding cases that returned any passage. This captures irrelevant public results as well as other mistakes; it is not itself a confidentiality metric.
- **Citation accuracy:** valid exact quotations and full-source SHA-256 fingerprints divided by all returned passages. No returned passages means `null`, not a vacuous 100%. Integrity also checks source readability through the test reader role, current revision and absence of withdrawal.
- **Latency:** median and nearest-rank p95 elapsed time around the search call, including connection setup but excluding subsequent citation verification. Results are in milliseconds and depend on hardware and cache state.

Metrics with no eligible cases are `null`. Per-case output contains the actual status, missing labeled references and irrelevant returned passages, not just a success percentage. Reports include per-category aggregates, the retrieval method and a SHA-256 fingerprint over the corpus records and cases. The checked-in baseline additionally identifies the search source commit and recording date.

`passed` is a status/complete-recall/integrity check, not a claim of perfect relevance. Precision is deliberately separate so noisy result lists are visible even when the expected evidence appears somewhere in the top five. The old regression's `recall_at_5` now uses full label coverage; each old positive case has one label, so its value is unchanged.

## CI interpretation

The required suite tests metric arithmetic with hand-calculated examples, validates all 50 labels, runs the realistic evaluator against a disposable database and checks citation/current-source integrity and restricted-source isolation. CI does not require perfect relevance. With v3 ranking, it requires development recall ≥93.75%, precision ≥35% and all 18 withholding outcomes, plus 12/12 additional validation outcomes and probe recall ≥75% with all four withholding outcomes. These are development regression floors, not production acceptance criteria. The original 30 regression cases still must pass. A green CI run therefore means the evaluator and safety checks work, not that search quality is production-ready.

## Next experiment

Keep the corpus and labels fixed while improving query-term handling and relevance ranking. Start with the documented failures: generic-word matches, source exclusions presented as guidance, and missed verification paragraphs. Compare recall, precision and withholding together so a change cannot look better simply by returning nothing. Test unseen questions separately before claiming generalization. Consider local semantic/hybrid retrieval only after measuring the lexical changes; add generated answers after evidence selection and withholding are reliable.

For the current ranking and unchanged-corpus comparison, see [coverage-based search v2](search-ranking.md). The added validation questions were fixed before v2 experiments and are now regressions, not permanently unseen data.

See [current v3 comparison and known regressions](operational-query-matching.md). The additional probe questions are now regression data, not permanently unseen validation.

## Separately authored questions

Use `--questions PATH` to replace a suite's questions while retaining its frozen corpus. Use `--validate-only` for database-free schema, exact-label and individual positive-source applicability checks. Invalid input is rejected before database creation. External reports are explicitly marked `question_source: external` and `independence: not_verified`. See [independent evaluation workflow](independent-evaluation.md) for private storage, case format, review and freezing rules. Existing benchmark data and ranking remain unchanged.

The user-supplied ten-scenario set has distinct sources and therefore uses `.venv/bin/python -m runreviq.evaluate_external`, with one disposable database per scenario. See [observed results](external-validation-results.md) and [metadata adapter assumptions](../datasets/external-validation/README.md). Its zero exit checks integrity, not full behavioral success.
