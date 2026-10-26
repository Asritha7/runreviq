# Coverage-based lexical search v2

This describes the v2 foundation and historical comparison. Current search adds [v3 operational vocabulary and support checks](operational-query-matching.md); the weighting and coverage formula below is retained.

The first lexical search matched any query word and often returned irrelevant passages. `postgres_english_coverage_v2` adds explicit coverage gates and topic context. It remains local keyword retrieval, not a semantic model or an answerability guarantee. No paid API, new model, dependency, migration or stored index is introduced.

## Algorithm and authority boundaries

Authorization, withdrawal, approval, version/environment checks and conflict/context blocking still run first in the same restricted-reader snapshot. Only the resulting applicable sources contribute terms or ranking statistics. The original passage splitter and exact returned quotations are unchanged.

1. Remove a fixed set of question-scaffolding words such as “please”, “should”, “inspect” and “compare” before query stemming. Apply three explicit morphological aliases to query and scoring text: recovery → recover, verification → verify, validation → validate.
2. Exclude sentences with recognized scope disclaimers from scoring text, including “does not cover”, “does not specify”, “does not authorize”, “does not establish”, “outside this/the procedure/scope” and “not instructions for”. **The returned quotation remains complete**, including safety instructions and disclaimers. This simple sentence heuristic can miss other phrasings or suppress a useful matching sentence; it does not understand negation generally.
3. Use PostgreSQL's English stemmer to obtain distinct terms for query, passage scoring text and title/nearest-heading context. Repeated words do not gain extra weight. Corpus statistics use only authorized applicable passages. Missing query terms retain weight instead of being discarded.
4. Weight query terms by inverse passage frequency: `log(1 + (N + 1)/(frequency + 1))`. A candidate's document must cover at least 50% of weighted query terms, and its own scoring text at least 15%. A title or another paragraph cannot qualify it alone.
5. Rank qualifying passages by length-normalized weighted term overlap, add a 0.60-weighted title/heading match bonus, then multiply by document coverage. Length is distinct-term count. Return up to five passages whose score is at least 40% of the best qualifying score, breaking ties by existing source order.

These thresholds are tuned development heuristics, not probabilities or confidence percentages. A weak question can still return misleading results; a useful paraphrase can still be missed. A zero-result response says that no sufficiently relevant passages were found and invites concrete incident terms or an approved source. Existing conflict and missing-context messages take precedence.

## Fixed-corpus comparison

The 50-question Harbor corpus and labels were not changed. Its fingerprint matches the original checked-in baseline. Earlier experimental settings traded away too much recall; the final setting was chosen using this development set. The results below therefore measure development improvement, not unseen-data performance.

| Measure | v1 | v2 |
| --- | ---: | ---: |
| Cases meeting expected status/complete recall/integrity | 36/50 | 44/50 |
| Mean recall@5 | 87.5% | 87.5% |
| Mean precision among returned results | 18.54% | 35.47% |
| Correct withholding | 8/18 | 16/18 |
| Exact quote integrity | 100% | 100% |

Remaining failed cases: H24 (cache recovery metrics), H28–H30 (paraphrases), H37 (unsupported durability procedure), H41 (unsupported universal HTTP error cause). Equal aggregate recall does not mean every old success is preserved: H24 remains a miss; H28 and H30 regress, while other questions improve. Per-case details must be checked alongside averages.

Twelve additional questions were authored and saved before ranking experiments, then evaluated once after choosing the final settings. They reuse the fictional corpus and the same author; they are not independent or permanently held-out. v1 met 7/12 expected outcomes and v2 met 12/12. Their mean precision increased from 17.5% to 38.54%, which still means noisy result lists. They now serve as regressions, so future changes cannot call them unseen tests.

The original 30-case regression still passes 30/30. See [comparison data](../datasets/benchmark/comparison-v2.json) and [metric definitions](search-evaluation.md). Accurate quotations do not prove the source answers the question or that a procedure is safe to execute.

## Run and maintain

```sh
.venv/bin/python -m runreviq.evaluate_search
.venv/bin/python -m runreviq.evaluate_search --suite realistic --report-only
.venv/bin/python -m runreviq.evaluate_search --suite validation --report-only
```

The suites still use disposable databases. CI now requires development recall ≥ 87.5%, precision ≥ 30% and withholding accuracy ≥ 80%, plus source integrity and the 12 additional expected outcomes. These floors prevent losing the measured gains; they are not production acceptance criteria. The original baseline remains unchanged for comparison.

Next: independently review labels and test genuinely new scenarios; improve the documented remaining misses and unsupported-question handling without sacrificing retrieval of useful evidence. Avoid adding generated explanations while relevance remains this noisy.
