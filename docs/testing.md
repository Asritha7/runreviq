# Tests, CI, and troubleshooting

## Run checks

After [Python setup](../SETUP.md):

```sh
./uv-local sync --frozen
.venv/bin/python -m unittest discover -s tests -v
```

The ordinary run exercises extraction, selection, catalog validation, and API tests; database integration tests are explicitly skipped. For the full suite, complete database bootstrap first, then:

```sh
docker compose up -d --wait db
RUNREVIQ_TEST_DATABASE=1 .venv/bin/python -m unittest discover -s tests -v
```

The suite currently contains 155 Python tests. Database tests create/drop a uniquely named `opslens_test_*` database and never truncate the development database. They use existing reader credentials without resetting their passwords. The admin account is needed to create/remove that temporary database. These are regression tests, not a held-out benchmark or a production security guarantee.

Coverage includes exact/multiline citation validation, stale fingerprints, invalid citation batches, legacy evidence, hidden quotations, version boundaries, permissions, conflicts, missing context, safe explanations, source provenance, ambiguous extraction, authority-field rejection, transactional ingestion, immutable revisions, raw database reader restrictions, bearer-key validation/revocation, authorized revision retrieval, historical access revocation, identical missing/denied responses, and API-to-database integration. File/database parity covers 72 role/version/environment/rotation combinations.

## Browser checks

Node.js 20 or later is needed only to run the JavaScript tests; there are no npm dependencies or frontend build step:

```sh
node --check runreviq/web/app.js
node --test tests/web.test.cjs
```

The eighteen tests execute the production script against a small mocked DOM and fetch implementation. They cover credential handling, disconnect and stale-response races, safe text rendering, historical revision labels, clearing failed document loads, and 401 resets. These are interaction-logic tests, not full browser automation.

Manual browser checks against the local database covered sign-in, missing-context follow-up, selected guidance, conflict quotes and highlighted source lines, metadata review, intern insufficient evidence, and reload clearing access. A 390px viewport had no horizontal page overflow. Manual checks supplement the automated suite.

## GitHub workflow

GitHub Actions runs the full Python suite, eighteen JavaScript interaction tests, and a pgvector smoke check on pull requests into `main` and pushes to `main`. It installs locked dependencies and uses a disposable PostgreSQL service with CI-only credentials. The required job is named `Selector and PostgreSQL tests`; keep that name aligned with branch protection.

Main requires pull requests, an up-to-date branch with passing required checks, and resolved conversations. Protection applies to admins; force pushes/deletion are blocked. The solo-developer configuration requires zero additional approving reviews. Use descriptive commits on focused branches and merge only with user authorization. Never bypass checks to merge.

## Common local messages

| Symptom | Explanation or next step |
|---|---|
| PostgreSQL initialization, CREATE DATABASE/EXTENSION | Normal first-run setup |
| Initial fast shutdown followed by startup | Normal container initialization sequence |
| Checkpoint starting/complete | Routine database disk persistence |
| Immutable-version / permission-denied errors during tests | Expected negative tests that verify prohibited writes and role switching are blocked; correlate with the test run |
| Database unavailable | Check Docker Desktop, `docker compose ps`, bootstrap, and local credentials |
| Reader password was edited in `.env` | The database password is not automatically rotated; restore the matching credential or deliberately rotate the account |
| API 401 | Credential missing, unknown, or revoked |
| API authentication 503 | Check that the key file exists, is valid, has unique records, and mode 600 |
| Existing keys on init | Expected refusal to overwrite; use the existing file |
| Port 8000 already in use | Inspect the existing process; do not terminate unrelated services |
| Extraction reports missing fields | The source must use the explicit Applicability format; prose is not inferred |

The container's local `trust` authentication rules concern sockets and loopback inside the container. The host application connects through the published port using password authentication. Access to Docker/container administration is trusted.

Do not delete database volumes, suppress error logging, or reset credentials merely to clear expected test logs. See [architecture](architecture.md) for boundaries and [metadata proposals](metadata-proposals.md) for extraction review.

## Google authentication checks

The full suite includes offline OIDC tests with generated RSA keys and a mocked exchange. Browser logic tests also cover cookie-based session restoration, unassigned users, logout, and clearing evidence after role denial. No real Google credentials or network requests are needed in CI. A live Google sign-in, initially unassigned account, local role assignment, session restoration, and authorized evidence query were verified on 2026-10-22; see [the verification record](google-login.md#recorded-local-verification--2026-10-22). Each installation still needs its own Google setup.

Demo API and database tests explicitly disable Google mode, so adding real local OAuth configuration cannot change their behavior. Expired login flows and sessions are checked through HTTP endpoints using a controlled clock.

## Draft checks

Draft tests cover file validation and byte limits, authenticated owner derivation, same-role account isolation, uniform missing/denied responses, per-owner quota, saved proposal persistence, RLS insert checks, and the draft writer’s inability to read or change published evidence. Integration writes stay in disposable databases. Browser interaction tests cover raw Markdown upload, safe preview text, saved draft loading, and disconnect races. A manual browser check uploaded and reopened the fictional template against a disposable database and inspected the extracted fields and source lines.

Review coverage includes incomplete/invalid fields, forbidden authority fields, owner-scoped persistence, original-source preservation, stale revision conflicts, append-only grants, RLS, UI error retention, and logout races.

Submission tests cover reviewer allowlist validation/revocation, server-derived identities, valid/latest revision requirements, immutable snapshots, retry deduplication, private unsubmitted content, self-review rejection, owner feedback, database privileges, and concurrent decision conflicts. Browser tests cover explicit reviewer access checks, decision payloads, safe source rendering, logout races, and owner submission feedback.

## Controlled publication

Publication tests cover separate access/revocation, server-derived actors, source citations, approval requirements, transactional validation, idempotent retries, concurrent requests, stale revision rejection, immutable history, current reading permissions, and restricted database grants. Browser tests cover explicit publication inputs, denied capability, failed requests, and late responses after logout. All integration writes use disposable databases.

Manual publication QA used a disposable database and fictional author, reviewer, and publisher identities. The browser published an approved source with an explicit intern reading role and line-bound claim, then found it through selection and opened the stored revision. The test server and database were removed afterward.

## Publication management

Tests cover publisher authentication and revocation, withdrawal validation, stale revisions, idempotent retries, concurrent attempts, append-only grants, reader isolation, preserved source checksums, same-revision reingestion, newer approved replacements, superseded history, and paginated listings. Browser tests cover withdrawal input, clearing stale results, conflict feedback, logout races, and source warnings. Integration tests use disposable databases only.

Manual lifecycle QA used two published revisions of a fictional upload in a disposable database. The browser showed revision 2 as active and revision 1 as superseded; after withdrawal with a reason, the same query returned insufficient evidence, while the withdrawn source and both revisions remained inspectable. The temporary server and database were removed. Migration 006 was checked to preserve the development database's existing source checksums and reading permissions without creating any withdrawals.

## Search evaluation

The database-enabled suite includes the 30-case labeled question evaluation, exact passage boundaries, authentication/input handling, revision changes, withdrawal persistence, ACL revocation and restricted-canary isolation. Browser logic covers question submission, context follow-up and source opening. Run `.venv/bin/python -m runreviq.evaluate_search` for a JSON report from a disposable database. See [metric definitions and limitations](search.md).

## Larger relevance benchmark

The realistic suite contains 50 questions over 13 separate fictional documents. Integration tests run it in a disposable database, verify exact-source integrity and restricted-source isolation, and validate labels. Unit tests verify precision, partial recall, reciprocal rank, empty metrics and percentile arithmetic. Known relevance failures are reported, not required to pass CI. The original 30-case suite remains a required regression. See [metric definitions](search-evaluation.md) and [the frozen baseline](../datasets/benchmark/README.md).

## v2 ranking regression checks

Tests cover title-only rejection, missing concepts, scope-disclaimer scoring, preserved safety text, deterministic ties, context blocking before ranking and the result cap. CI checks the unchanged Harbor corpus against recall ≥ 87.5%, precision ≥ 30% and withholding ≥ 80%, alongside exact citations and role isolation. Twelve additional same-author questions now form a regression suite. The original 30-case regression remains required. See [v2 comparison](search-ranking.md); these thresholds are not production effectiveness guarantees.

## v3 query-matching checks

Tests cover exact code/identifier boundaries (including trailing punctuation), operational vocabulary and preserved negation, scope-only withholding, numeric-code mismatch and the eight-question probe. CI floors are development recall ≥93.75%, precision ≥35%, all 18 withholding outcomes, and probe recall ≥75% with all four withholding outcomes. Original 30/30 and additional 12/12 regressions remain required. See [measured trade-offs](operational-query-matching.md).

External evaluation coverage checks strict case/context types, inaccessible or inapplicable positive labels, preflight rejection before database access, database-free validation, stable/change-sensitive fingerprints, and external-report integrity without an independence claim. Run the full Python suite with `RUNREVIQ_TEST_DATABASE=1` to include the disposable PostgreSQL path. See [independent evaluation](independent-evaluation.md).

## v4 regression checks

The full suite now checks the external evidence targets alongside existing context/conflict/access invariants. New tests cover code-free diagnostic evidence, incompatible HTTP-only restrictions even when another code is mentioned, strict identifiers, matching context versions, restricted source exclusion, query-clause body requirements and relevance-gated prohibition priority. Comparison tests check the frozen baseline denominators and refuse corpus changes. These are tuned development regressions; see [measured limits](context-sensitive-retrieval.md).

## Java migration checks

Run `./backend/mvnw -f backend/pom.xml verify` and
`.venv/bin/python scripts/export_java_parity.py --check` from the repository root.
The Java suite compares complete selector responses against 324 frozen Python
outputs and checks denied HTTP routes plus domain validation. These are regression
checks, not independent evidence of retrieval quality. The fixture is test-only and
must never be ingested as product evidence. Regenerate with the exporter only after
reviewing intentional behavior changes. Existing Python/SQL and browser tests remain required.

Java authentication tests run without Google accounts, private local files or paid
services. HTTP tests use temporary owner-only credentials and a fake provider; token
tests separately verify real RSA signatures, strict claim types, audience/issuer/
nonce/time validation, missing claims, duplicate claims and wrong signatures. A local
HTTP token endpoint verifies bounded responses, redirect rejection and code exchange
parameters. Session tests cover browser binding, replay, expiry, role refresh/revocation,
client rotation, logout, origin/Host checks, configuration failure and demo-mode isolation.
A live Google browser sign-in is still required before production-workflow cutover.

## Java JDBC integration

Run `RUNREVIQ_TEST_DATABASE=1 .venv/bin/python scripts/test_java_database.py`. This
creates and removes its own database, then runs HTTP parity and security checks for
both demo keys and Google sessions, RLS isolation, historical/current policy, withdrawal,
checksum serialization, corrupted sources, rejected writes and snapshot consistency.
The Java unit job skips these classes without the harness; the PostgreSQL CI job
executes them explicitly. No test writes target the live application database.

## Java private draft checks

The Maven unit suite includes frozen Python extraction/review parity; check freshness with
`.venv/bin/python scripts/export_java_authoring_parity.py --check`. The disposable Java
database harness also runs draft HTTP/SQL tests, concurrent quota/review races and a
Python → Java → Python draft/review round trip. Google integration uses an offline
identity-provider substitute. See [Java authoring](java-authoring.md) for scope and limits.

## Java workflow checks

The disposable Java harness also runs submission, review, publication and withdrawal
HTTP/JDBC tests after the existing read/draft suite. Tests compare Python-created
publication responses and checksums, then verify Python can read a Java withdrawal.
Concurrency tests exercise single-use decisions and idempotent publication; an injected
SQL failure proves publication rollback. Operator allowlist revocation, self-review,
source-backed claims, supersession, current permissions and Google Origin/logout are
covered. These tests use a fresh database and offline identity provider, not live Google
or the product database. See [Java workflows](java-workflows.md).

## Java passage search checks

Run `RUNREVIQ_TEST_DATABASE=1 .venv/bin/python scripts/test_java_search.py`. This separate
harness imports only synthetic sources into a new disposable database, checks 108 frozen
Python responses for freshness, and compares the full Java HTTP responses and citations.
It additionally tests reader isolation, current revision/ACL/withdrawal behavior, snapshot
consistency, visible/hidden corruption, missing RLS, strict input before SQL, and Google
Origin, assignment revocation and logout. Offline lexical tests cover source boundaries,
Unicode line breaks, scope exclusions, exact numeric restrictions, relevance gates, ties,
policy priority and bundles. These are parity/security regressions, not an independent
retrieval quality score. See [Java search](java-search.md) for the fixture update procedure.
