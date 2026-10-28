# Java submission, review and publication

Stage 4b completes the Java authoring API after [private drafts](java-authoring.md).
It is disabled by default and does not change the working Python app on port 8000.
Java still has no workspace frontend or passage search. No paid services are used.

## What becomes possible

1. An authenticated owner submits the latest complete saved metadata revision.
   Retrying the same submission returns its existing identifier.
2. A separately allowlisted reviewer sees submitted snapshots belonging to other
   owners and records one immutable approval or request for changes, with a comment.
3. A separately allowlisted publisher sees approved snapshots, confirms metadata,
   explicitly chooses reading roles and supplies source-backed claims. Publication
   atomically inserts the evidence revision and audit record and advances the catalog.
4. Publishers inspect current documents, immutable revision history and source content,
   or withdraw the current approved revision with a reason. Withdrawn content remains
   readable as authorized history but is excluded from current selection.

A reading role never grants review/publish permission. Operator allowlists reload on
every request. Missing lists grant nobody; malformed or unsafe files fail closed.
Google and demo identities remain separate. Google writes require the configured
Origin and `X-Runreviq-Request: 1`, including decisions, publication and withdrawal.

Independent review means the reviewer must differ from the draft owner. Reviewer
and publisher are separate permissions, not necessarily separate people: an operator
may assign both to one identity, matching the Python policy. A publisher may also be
the author if somebody else reviewed the submission. Publisher management can inspect
the catalog regardless of the publisher's normal reading role; normal evidence reads
continue to enforce current document reading permissions.

## API

| Method | Route | Required access |
| --- | --- | --- |
| GET | `/v1/reviewer-access`, `/v1/publisher-access` | Authenticated assigned identity |
| POST | `/v1/drafts/{uuid}/submit` | Draft owner; `{revision: integer}` |
| GET | `/v1/drafts/{uuid}/submissions` | Draft owner |
| GET | `/v1/submissions`, `/v1/submissions/{uuid}` | Independent reviewer |
| POST | `/v1/submissions/{uuid}/decision` | Independent reviewer; `decision`, `comment` |
| GET | `/v1/publications/queue`, `/v1/publications/{uuid}` | Publisher |
| POST | `/v1/publications/{uuid}` | Publisher; `allowed_roles`, `claims`, `metadata_verified` |
| GET | `/v1/publication-management/documents?after=ID` | Publisher; 50 items plus cursor |
| GET | `/v1/publication-management/documents/{id}` | Publisher; revision history |
| GET | `/v1/publication-management/documents/{id}/revisions/{revision}` | Publisher; exact source and checksum |
| POST | `/v1/publication-management/documents/{id}/withdraw` | Publisher; `expected_revision`, `reason` |

JSON is strictly typed, duplicate/unknown fields are rejected and bodies are capped at
20,000 bytes. Authentication/assignment checks happen before body parsing or database
access. Hidden/missing submissions share a 404. Stale revisions, repeated decisions,
changed publication retries and changed withdrawal retries return 409. Error responses
use fixed messages without submitted source text, credentials or SQL diagnostics.

Publication requires nonempty, distinct reading roles. Claims need distinct action
names, `must`/`must_not`, and exact source line ranges; Java constructs quotes and
source hashes rather than trusting caller-supplied citations. Reviewed evidence units
may be published without claims unless a prohibition needs a cited `must_not` claim
inside its range. These checks establish source integrity, not semantic proof that a
human-labeled action accurately represents the text. That responsibility remains with
the reviewer and publisher. No AI labels or answers acquire authority here.

## Database and consistency

Existing migrations remain under Python ownership. Java executes no schema changes.
Submission uses `opslens_draft_writer`; independent decisions use
`opslens_review_operator`; publication/history/withdrawal use
`opslens_publish_operator`. Each operation opens a fresh connection, sets transaction-local
identity, verifies restricted role/privilege and forced-RLS boundaries, then commits
or rolls back. Admin credentials are never used by these product adapters.

- Submission shares the existing per-draft advisory lock with metadata saves.
- Decisions share Python's negative submission lock and cannot overwrite a decision.
- Publication and withdrawal share the existing catalog advisory lock, also used by
  Python ingestion. All publication writes occur in one transaction.
- Write transactions use READ COMMITTED so a writer waiting on a lock sees the preceding
  commit. History uses a read-only repeatable-read transaction for a coherent snapshot.
- Exact publication retries compare Python-compatible metadata/content checksums and
  the original sorted reading roles. A retry of an old published revision never rolls
  back the current revision or its current permissions.
- An older, never-published submission cannot replace a newer publication.
- Withdrawal retries require the same actor, reason and current revision.

Java additionally validates source ranges when inspecting/approving submitted reviews,
and validates stored checksums/citations on publisher revision detail. These are stricter
than the corresponding Python reads. Existing documented ASCII-version and size-bound
limitations still apply. Authorization is checked at request entry; revocation affects
subsequent requests, not transactions already in flight.

## Optional local configuration

Do not change the working Python server or OAuth callback to try this stage. After the
Java identity and private draft configuration is explicitly enabled, optional helpers
create separate owner-only files without overwriting existing files:

```sh
.venv/bin/python scripts/init_java_workflows.py reviewer
.venv/bin/python scripts/init_java_workflows.py publisher
```

Add the following options to the Java development server, using real absolute paths:

```text
--runreviq.workflows.enabled=true
--runreviq.workflows.reviewer-config-path=/absolute/path/.local/java-reviewer-db.json
--runreviq.workflows.publisher-config-path=/absolute/path/.local/java-publisher-db.json
--runreviq.workflows.reviewers-path=/absolute/path/.local/reviewers.json
--runreviq.workflows.publishers-path=/absolute/path/.local/publishers.json
```

Each database file has exactly `database` and `password`; the Java adapter fixes the SQL
login itself. Existing Python allowlists use `{"google":[],"demo":[]}` with explicitly
assigned subject identifiers. The helper never adds identities to those lists. Omitting
an allowlist path grants nobody. All credentials/assignments remain private and untracked.
Live Google-to-Java sign-in is still a manual cutover gate; tests use an offline provider.

## Tests and next stage

Run the Maven unit suite and `RUNREVIQ_TEST_DATABASE=1 .venv/bin/python scripts/test_java_database.py`.
The harness uses a fresh disposable database and runs the existing read/draft tests
before workflow tests. It covers real HTTP authorization, database separation, RLS,
self-review denial, revoked allowlists, Google Origin/logout checks, concurrent decisions
and publication retries, rollback after an injected database failure, stale submissions,
claim/prohibition checks, supersession, permission changes, withdrawal and preserved history.
Python-created publications compare exactly in Java (including checksums and history),
and Python reads back a Java withdrawal and verifies all synthetic publication checksums/citations. This proves selected compatibility cases, not
independent retrieval effectiveness or production readiness.

Next is Java passage retrieval and complete evidence responses, followed by held-out
HTTP evaluation and the separately reviewed frontend/runtime cutover.
