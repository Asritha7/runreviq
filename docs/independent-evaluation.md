# Independent retrieval evaluation

The existing question suites were written by the same author as the implementation. They are development regressions, not independent evidence of operational usefulness. External question files can now be evaluated against a frozen built-in corpus without changing the application, published sources or ranking rules. External input is always reported as `independence: not_verified`: software cannot establish who authored a question or whether they saw tuning results.

## Collect and review questions

1. Ask a person who did not tune the retrieval rules to read the fictional source corpus and write realistic incident questions in their own words. For `--suite realistic`, use the 13 sources listed in `datasets/benchmark/catalog.json`, with source paths relative to `datasets/benchmark/`. Do not show existing benchmark questions or search results first.
2. Record service, exact software version, environment, reading role, and key-rotation state. Use JSON `null` for unknown rotation; do not guess. The current API requires an exact version, so missing-version API validation is a separate test, not a retrieval case.
3. Have a source reviewer label every directly useful passage before running retrieval. Include ambiguous context, conflicting rules, unsupported specifics and inaccessible evidence as withholding cases. Expected statuses are `selected`, `insufficient_evidence`, `conflict`, and `needs_context_or_review`.
4. Save questions, rationale, author/reviewer provenance and review date privately. Use pseudonyms if recording provenance. The case format permits `rationale`; keep other review notes in a separate local file. Resolve label disagreements before scoring, without consulting the retrieval output.
5. Freeze the question file and retain its reported dataset fingerprint. Once results influence changes, that set becomes development data. Collect a fresh independently written set for the next generalization check.

Independently written questions about this fictional corpus still do not establish performance on real company incidents. Real questions must be sanitized and have evidence in the chosen corpus; this command does not ingest a new corpus or connect to company services. No independent questions have been collected by this implementation.

## Local file format

Store cases in `.local/evaluation/questions.json` (the `.local/` directory is git-ignored). Do not commit incident details or reports containing sensitive material. Reports include question text and retrieved source excerpts.

The following **illustrative case reuses development evidence and is not independent validation**:

```json
[
  {
    "id": "EXAMPLE-1",
    "category": "verification",
    "question": "Which checks confirm that cache recovery is working?",
    "rationale": "Illustration only; replace with independently authored, reviewed cases.",
    "context": {
      "role": "platform",
      "service": "harbor",
      "software_version": "3.0.0",
      "environment": "production",
      "key_rotation_enabled": true
    },
    "expected_status": "selected",
    "expected_passages": [
      {"document_id": "CACHE-501", "revision": 1, "start_line": 19, "end_line": 19}
    ]
  }
]
```

Labels must identify complete passages produced by the current splitter: non-heading paragraphs separated by blank lines, capped at 20 whole lines. Count source lines starting at 1. Withholding cases use an empty `expected_passages` list. Cases require unique IDs and nonblank questions up to 500 characters. Unknown fields, coercible strings such as `"false"` in boolean fields, invalid versions, duplicate labels, nonexistent passages, inaccessible labels and individually inapplicable positive sources are rejected. Validation checks structure and source applicability; a human must still verify relevance, completeness of labels and expected withholding status.

## Validate and run

From the repository directory:

```sh
.venv/bin/python -m runreviq.evaluate_search --suite realistic --questions .local/evaluation/questions.json --validate-only
.venv/bin/python -m runreviq.evaluate_search --suite realistic --questions .local/evaluation/questions.json --report-only > .local/evaluation/report.json
```

`--suite` selects the frozen corpus; `--questions` replaces only its questions. The realistic, validation and paraphrase suites share the larger corpus; regression uses the original smaller corpus. Validation happens before database creation. `--validate-only` does not connect to PostgreSQL and prints the case count and corpus/question fingerprint.

The evaluation creates and drops a disposable database using the existing local setup. It uses the existing authorization, applicability, conflict and exact-citation checks. The working database is untouched. It makes no paid calls and needs no model download or API restart. As with the existing evaluator, forced process termination may prevent database cleanup.

External reports include `question_source: external`, `independence: not_verified`, and the corpus/question fingerprint. Compare precision, recall, withholding, exact-citation integrity and individual misses together. Omit `--report-only` for a nonzero exit on any failed expected outcome. Integrity failures remain fatal even with that option. A passing outcome requires complete expected recall and status/integrity checks, but can still include irrelevant passages: inspect precision separately.

Do not claim improved quality simply because this import workflow passes its tests. Ranking is unchanged. First collect independently reviewed questions, run the frozen baseline, and classify misses before proposing a retrieval change.
