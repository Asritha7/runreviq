# Optional Java focused retrieval candidate

Java now offers an explicit `focused-v6` search mode alongside the unchanged default
`reviewed-v5`. This is a development candidate for reducing unnecessary passages.
Python's working product, ranking implementation, frozen migration fixture and recorded
v5 reports remain unchanged. The mode is server configuration, not a request field.

## What changes

The candidate retains the same restricted SQL reader, consistent source snapshot,
current permissions/withdrawals, integrity validation, applicability selector, conflict
handling, reviewed source ranges, numeric restrictions and exact quotations.
Only lexical query processing, scoring and result selection change:

- Confirmation wording maps to “verify”; generic “checks” and “is/are working” wording
  is removed from scoring. “Working set” and other uses of “working” remain intact.
- The existing document/body coverage gates still apply. Passage-body coverage adjusts
  the score so a title match is less able to dominate weak passage evidence.
- Query terms present in candidate title/heading context provide topic anchors, excluding
  generic operational terms such as recovery, approval and failure. Anchor coverage
  changes score, not eligibility or permissions.
- Explicit diagnostic or verification/resumption wording can favor corresponding
  source headings. This is a small lexical intent heuristic, not semantic understanding.
  Source headings remain untrusted text; they never assign reviewed roles, permission,
  approval, or operational authority. A title/heading alone cannot qualify a passage.
- Results ordinarily need 65% of the leading score, or 45% while adding an uncovered
  query concept, instead of the baseline's 40%/30%. One already-relevant passage per
  document matching explicit intent and all topic anchors may survive at 30%, preserving
  complementary diagnostics/verification. Existing coverage requirements apply first.
- Relevant reviewed prohibitions retain their existing action-term priority. Typed bundles
  still reserve space only among qualified evidence and return at most five passages.

These rules contain no benchmark document IDs, question IDs or expected passage ranges.
They were developed using the existing development cases, so those cases are not held out.

## Evaluate without changing the working app

```sh
.venv/bin/python -m runreviq.evaluate_java --suite realistic \
  --search-mode focused-v6 --report-only > .local/focused-realistic.json
.venv/bin/python -m runreviq.compare_java \
  datasets/java-evaluation/realistic-v5.json .local/focused-realistic.json
```

Create `.local/` first if it does not exist. The evaluator builds and copies Java into
its own temporary directory, creates a disposable source-only database, and starts an
isolated loopback process. It removes its process/database afterward. Labels never enter
retrieval. External questions remain supported via `--questions`; authorship and held-out
status are not automatically verified.

Comparison refuses changed corpus/question fingerprints, case IDs, questions, evidence
targets, provenance or inconsistent aggregates. It lists lost outcomes/recall/integrity,
per-case precision regressions, and exact evidence sets. A comparison is not promotion;
no command changes the live runtime or makes v6 the default.

To select v6 for a separately configured Java engineering instance, add
`--runreviq.search.mode=focused-v6` to the existing Java startup command. The reader
adapter and authentication still require explicit configuration. Unknown modes are
rejected when initializing the reader adapter. Clients cannot switch modes through
`/v1/search`; extra request fields receive 422. The response identifies the selected
method as `postgres_english_focused_v6` or `postgres_english_reviewed_v5`.

## Recorded comparison

All measurements below use the identical frozen inputs for each v5/v6 pair:

| Suite | Outcome matches, v5 → v6 | Returned precision, v5 → v6 | Exact evidence sets, v5 → v6 |
| --- | ---: | ---: | ---: |
| Original regression | 30/30 → 30/30 | 100% → 100% | 30 → 30 |
| Realistic Harbor | 50/50 → 50/50 | 38.28% → 70.57% | 20 → 33 |
| Validation questions | 12/12 → 12/12 | 38.54% → 60.42% | 4 → 6 |
| Paraphrase probes | 7/8 → 8/8 | 20.83% → 54.17% | 4 → 5 |

The cache-recovery probe P02 now retrieves its labeled passage. Recorded recall reaches
100% on all four sets, with no recorded status/recall/integrity regressions or per-case
precision losses. All structured citation/access checks pass. Full reports and paired
comparisons are in [the evaluation directory](../datasets/java-evaluation/README.md).

The candidate **still fails the strict exact-evidence gate** on the realistic,
validation and paraphrase sets. Extra results remain; unlabeled passages count as extras
under these labels even when potentially useful. These are tuned development results,
not new independent validation, a semantic contradiction detector or production readiness.
More selective thresholds can miss useful evidence on new questions. Keep the baseline
as default until new independently reviewed questions and deployment gates justify a change.

## Tests and next steps

- The original 108 full-response Python/Java parity cases continue to test v5 unchanged.
- In v6 mode the same cases check unchanged applicability/context/conflict fields and
  exact accessible source citations; reviewed procedure/prohibition/history/monitoring
  coverage is checked on the typed-evidence examples.
- Both modes run the 114-case HTTP/database suite covering ACL changes, withdrawals,
  snapshot consistency, corrupt sources, missing RLS, strict inputs and Google guards.
- Unit tests prevent heading-only qualification, missing-concept bypass, discarded
  complementary evidence, and silent fallback for unknown configuration.
- CI evaluates v6 through real HTTP on all four frozen development suites and rejects
  lost labeled targets or lower aggregate precision against the recorded v5 baselines.

Next, record a first result on genuinely new reviewed questions before tuning on them.
Isolated UI rehearsal and real Google smoke testing can then establish runtime migration
readiness; the live Python-to-Java cutover still needs a separate review.
