# Java passage retrieval

Stage 5 implements `POST /v1/search` in the optional Java backend. It uses the same
`postgres_english_reviewed_v5` rules as Python and returns exact source excerpts,
not generated answers. The live Python workspace remains on port 8000. Java stays
on loopback 8081 without a frontend; no runtime cutover or paid service is enabled.

## Request and authentication

Enable authentication and the JDBC reader adapter using [Java persistence setup](java-persistence.md).
Search uses those existing settings and restricted reader credentials; it needs no
additional writer credential, schema migration, index or model. Default startup denies
the route. An enabled adapter still authenticates before parsing a body or reading SQL.

The JSON request requires `question`, `service`, `software_version` and `environment`.
Optional `key_rotation_enabled` and `http_status` preserve unknown values as null.
The question must contain non-whitespace text and at most 500 Unicode code points; the whole request
is capped at 4096 bytes. Duplicate, extra, mistyped and trailing JSON values are rejected.
A caller cannot supply a role. Google sessions require the configured exact Origin and
`X-Runreviq-Request: 1`; demo mode requires its bearer key. Identity assignments reload
per request. Failures use 401/403 for identity/access, 422 for input, and a fixed 503
`Evidence store unavailable` for storage/configuration/integrity failures.

```json
{
  "question": "HTTP 401 authentication diagnostics and JWKS propagation",
  "service": "identity-service",
  "software_version": "2.4.0",
  "environment": "production",
  "key_rotation_enabled": true,
  "http_status": 401
}
```

A single recognizable HTTP code in the question supplies missing HTTP context. An
explicit status that disagrees with question codes is rejected before database access.
This inference does not infer key rotation, current service health or the incident cause.

## Evidence pipeline

1. Connect as the authenticated role's restricted PostgreSQL reader. Verify its grants,
   non-privileged identity and forced row-level security.
2. In one read-only repeatable-read transaction, load authorized current revisions,
   excluding withdrawals, and validate canonical metadata/content checksums, cited
   source ranges, exact quotations, profiles and prohibition claims.
3. Apply the existing selector. Conflicts or missing context/review return their
   explanations and empty search passages; ranking cannot override those decisions.
4. Extract reviewed source ranges, or legacy blank-line paragraphs capped at 20 lines.
   Keep exact source text and content hashes. Reviewed units exclude unclassified appendices.
5. Use PostgreSQL English stemming on that same connection, then ported lexical
   coverage, numeric restrictions, exclusion and relevance gates. Rank only applicable
   authorized candidates. No cross-role cache or persistent search index is involved.
6. Return at most five passages. Relevant reviewed roles can reserve bundle slots;
   a label alone cannot qualify an irrelevant passage. Deduplication collapses whitespace
   within the same evidence role without discarding negation or numbers.

Each passage includes its document ID, immutable revision, title, start/end line,
exact quote, source SHA-256, reviewed evidence role, usage note and applicability.
The response retains selector explanations and current evidence references, adds HTTP
context provenance and role counts, and reports `insufficient_evidence` if nothing
passes relevance gates. Historical and monitoring notes explicitly avoid claiming a
confirmed current cause or live telemetry. See [the shared search contract](search.md).

Current policy is checked at request entry and source loading uses a consistent snapshot.
A revocation committed during an in-flight search takes effect on subsequent requests;
it does not cancel an established snapshot or retract text already delivered. There is
no generated-answer store. Future generated or cached answers require additional
pre-delivery/current-evidence revalidation, described in [the migration plan](java-migration.md).

## Validation and limits

```sh
./backend/mvnw -f backend/pom.xml -B -ntp verify
RUNREVIQ_TEST_DATABASE=1 .venv/bin/python scripts/test_java_search.py
```

The search harness creates and drops only its new random disposable database. It imports
synthetic source documents, then compares 108 complete Java HTTP responses with the
frozen Python fixture in `backend/src/test/resources/search-parity.json`. The cases cover
existing regression, realistic, validation and paraphrase questions plus reviewed HTTP
scope/bundles. Questions and expected responses stay outside the source tables. The
fixture records the current corpus fingerprint; Python response changes fail the check.
Use `--update-fixture` only after reviewing an intentional behavior change.

Integration checks also verify exact returned quotes against revision reads, current
revision/withdrawal handling, ACL revocation, snapshot consistency, restricted canary
isolation, malformed input before SQL, corrupt visible/hidden sources, missing RLS,
safe database failures and Google Origin/role-revocation/logout behavior. Google uses
an offline verified-identity substitute here; this is not a live Google login test.

Parity proves compatibility on these cases, not independent quality or semantic claim
support. Existing ranking misses and low precision remain; this migration does not tune
against expected answers or merge the separate evaluation PR #26. Exact citations prove
where text came from, not that every implied answer is supported. This remains lexical,
small-corpus retrieval with no embeddings, LLM, general semantic contradiction detector,
live monitoring, or operational action execution. Query and output sizes are bounded;
source scanning and ranking still scale with the authorized corpus. Production load and
retrieval quality need separate evaluation before cutover.

A separate [Java HTTP quality evaluator](java-quality-evaluation.md) now scores labeled
evidence targets in an isolated runtime. Its realistic development report still fails
the strict relevance gate despite matching all expected outcomes; migration parity
remains distinct from effectiveness.

An optional server-configured [focused-v6 candidate](focused-retrieval.md) changes only
lexical scoring/selection. The default remains reviewed-v5, with its original frozen
full-response parity checks. Neither candidate selection nor benchmark expectations
can be supplied by an HTTP caller.
