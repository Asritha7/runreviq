# Operational query matching v3

`postgres_english_operational_v3` extends v2's local lexical ranking with a small explicit vocabulary and conservative gates for specific codes and topics mentioned only in scope exclusions. It does not generate answers, change approvals or infer user permissions. The source splitter, immutable revisions, full quotations and existing authorization/applicability/conflict/withdrawal checks are preserved. There are no new dependencies, paid calls, model downloads or migrations.

## Explicit vocabulary

The same transformations apply to query and scoring text before PostgreSQL English stemming. They never rewrite stored or displayed sources:

| Input wording | Scoring form |
| --- | --- |
| recovery | recover |
| verification, validation, validate | verify |
| instance / instances | replica / replicas |
| job / jobs | task / tasks |
| requeue, requeued, requeuing | retry |
| database slot / slots | database connection |
| eligible to receive traffic | ready |

The question-scaffolding list additionally removes “metrics”. This is a deliberately small, hand-authored vocabulary motivated by development failures, not semantic understanding or independent language validation. Some mappings are ambiguous outside these operational scenarios: a VM instance need not be a replica, and a database slot might be a replication slot. These aliases can produce wrong matches or suppress useful distinctions. Negation and safety instructions remain in the source quotations; lexical retrieval still does not understand their meaning.

## Specific identifiers

Numbers (including dotted versions) and uppercase hyphen/underscore identifiers in the question must occur as complete tokens in a candidate's stored document, case-insensitively. Thus a question about HTTP 503 cannot qualify a document only mentioning 401, and BUILD-9 cannot match BUILD-99. Sentence-final punctuation is allowed. All requested exact terms must be in the same document; they need not all be in the returned paragraph.

This conservative check can miss legitimate cross-document comparisons or a version present only in metadata. It does not parse every identifier format or every lowercase/mixed-case code. It is a retrieval check, not a claim that the exact identifier makes the passage relevant. Existing coverage and ranking gates still apply afterward.

## Scope-only terms

After the exact-token gate, collect normalized terms from full paragraphs and from their scoring text/title/heading context. If a query term occurs in the full paragraphs but nowhere in scoring text/context, it was only found in a recognized scope-exclusion sentence. In that case return insufficient evidence rather than recommending a related paragraph. The rule considers only authorized, applicable, current sources that passed the exact-token check; restricted sources cannot contribute support or exclusions.

For example, “database durability” appears only in a sentence stating that the procedure does not authorize changing durability settings. Previously a different passage sharing “database” and “settings” could appear as guidance. This rule withholds the recommendation. It is intentionally conservative and can reject a question about the limitation itself. Other exclusion phrasings may be missed, and a repeated term in unrelated scoring text can defeat the gate. It is not general answerability detection.

## Measured results and limits

Original source documents and the 50 development labels remain byte-for-byte unchanged. The eight new probe questions were saved before experiments and run after selecting the vocabulary/gates; their remaining failure was not used for further tuning. All datasets have the same author and fictional context, so none provides independent production validation.

| Measure on the fixed 50 questions | v2 | v3 |
| --- | ---: | ---: |
| Expected outcomes | 44/50 | 48/50 |
| Mean recall@5 | 87.5% | 93.75% |
| Mean precision among returned passages | 35.47% | 37.24% |
| Correct withholding | 16/18 | 18/18 |
| Exact quotation integrity | 100% | 100% |

H28 (“free database slot”) and H29 (“eligible to receive traffic”) still miss their expected evidence despite vocabulary support. Alias matching alone is not enough for these broad paraphrases. The original 30-case suite remains 30/30. The previous 12 additional questions remain 12/12, but their precision **drops from 38.54% to 36.46%** because extra unrelated passages enter the lists.

The new eight-question probe improves from 5/8 expected outcomes to 7/8. Its answerable-question recall rises from 25% to 75%, precision from 6.25% to 22.92%, and withholding remains 4/4. P02 (“Which checks confirm that cache recovery is working?”) still misses the labeled verification paragraph. These questions now become regressions and cannot be called unseen tests for future tuning.

See [recorded comparison](../datasets/benchmark/comparison-v3.json). Coverage percentages do not establish safe action or diagnosis; most returned development passages are still outside the strict relevance labels. More independently labeled scenarios and a separately evaluated semantic/hybrid approach may be needed instead of indefinitely extending a hand-authored vocabulary.

## Run

```sh
.venv/bin/python -m runreviq.evaluate_search
.venv/bin/python -m runreviq.evaluate_search --suite realistic --report-only
.venv/bin/python -m runreviq.evaluate_search --suite validation --report-only
.venv/bin/python -m runreviq.evaluate_search --suite paraphrase --report-only
```

All suites use disposable databases. CI requires development recall ≥93.75%, precision ≥35% and all 18 withholding outcomes; it also retains the original and 12-case regressions. The new probe requires ≥75% recall and all four withholding outcomes while preserving its known failure. These are development regression checks, not universal quality guarantees. Restart the local API to load v3; no other setup changes are necessary.
