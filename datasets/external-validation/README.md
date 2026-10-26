# User-supplied synthetic scenarios — first baseline

These ten scenarios were supplied by the user on 2026-10-26, with an assertion that they were authored independently of the implementation. They are synthetic and sanitized. Authorship independence has not been independently verified. Questions, quoted source passages and expected outcomes were transcribed before executing the baseline; no search rules were changed to improve these results.

`scenarios.json` contains questions, contexts and expectations. Each scenario directory contains only source Markdown and catalog ingestion batches. The evaluator imports only those catalog batches and their referenced Markdown, never the question/expectation file or this report. Each scenario runs in its own disposable database: importing all scenarios together would make the intentional S02 conflict block unrelated production scenarios.

## Explicit adapter assumptions

The supplied passages are preserved verbatim, with an ID heading and blank line added; each passage is line 3. Titles are document IDs because no titles were provided. Unless the user specified otherwise, the adapter uses revision 1, service `identity-service`, approved status, verified metadata, version range `>=2.4.0,<2.5.0`, the scenario's environment, rotation enabled and reading access for platform/security. These fill omitted metadata for this test only; they are not inferred authority or production approvals.

- S01's historical source uses its supplied `>=2.1.0,<2.2.0` range.
- S03's sources require true and false rotation respectively; the query remains unknown.
- S04 ingests revision 1 as superseded, then revision 2 as approved/current. Document revision and requested software version remain different fields.
- S07 uses the supplied staging/production distinction.
- S10 maps `security-team` to `security` and `platform-team` to `platform`, without cross-granting access. Other scenarios use platform queries; S10 additionally queries as security and then revokes the restricted document's access.
- S02's two passages are manually encoded as `restart_before_diagnostics: must_not/must` with exact citations. S08's prohibition is encoded as `disable_signature_verification: must_not`, also cited. This tests structured conflict processing, **not automatic contradiction extraction**. No other normalized claims are manufactured from expected answers.

These assumptions and claim mappings need human review before treating the fixture as accepted independent evidence. Source labels remain separate from expected search outcomes.

## Reproduce

```sh
.venv/bin/python -m runreviq.evaluate_external > .local/external-validation-report.json
```

The `.local` directory must exist. The runner creates and drops ten uniquely named disposable databases, one per scenario. It never imports into the working collection or changes runtime authentication. It requires existing local PostgreSQL credentials, uses no paid services and downloads no models. Forced termination can prevent cleanup. Its exit code checks quotation/access integrity only; a zero exit **does not mean ten successful scenarios**. `quotation_count: 0` means no quotations were tested, even when the integrity flag is true. There is no aggregate production-readiness score.

The committed `baseline-v3.json` is the actual first-baseline output, recorded using `postgres_english_operational_v3` with adapter revision from this PR. It includes the source/question fingerprint, raw selection/search responses, history inspection and access-revocation checks. See [assessment](../../docs/external-validation-results.md). The baseline contains only the supplied fictional material; do not use this public report format for private incident data.

This execution exposes the questions to the implementation author. Any subsequent tuning using them makes them development data; future generalization claims need a fresh independently reviewed set. Existing historical benchmarks and their labels remain unchanged.
