# Java private drafts and metadata review

Stage 4 is split at privilege boundaries. Stage 4a implements private uploads, listing,
reading and append-only owner metadata review. [Stage 4b](java-workflows.md) separately enables independent reviewer decisions,
submission, publication, history and withdrawal. Saving a metadata review does not approve a document or make it searchable.

## Routes and behavior

Separately enable `runreviq.drafts.enabled=true`; it defaults to false even when evidence
reads are enabled. The existing authenticated identity supplies the owner. No request
can supply an owner or reading role. Google writes require the configured exact Origin
and `X-Runreviq-Request: 1`; demo mode uses bearer keys. Role removal takes effect on the
next request, and logout revokes the Google session.

| Route | Behavior |
| --- | --- |
| `POST /v1/drafts?filename=name.md` | UTF-8 `text/markdown`, at most 1,000,000 bytes; creates a private draft, returns 201 |
| `GET /v1/drafts` | Lists only this identity's drafts |
| `GET /v1/drafts/{uuid}` | Original source, extraction proposal and latest saved review; hidden/missing share 404 |
| `POST /v1/drafts/{uuid}/review` | JSON `expected_revision` and editable `metadata`; appends a review or returns 409 on stale save |

Editable metadata contains title, service, environment, version_min,
version_max_exclusive and key_rotation_enabled as strings; evidence_profile is optional.
Incomplete or invalid field values are retained as work in progress with explicit issues.
Unknown fields, malformed profiles, heading-only/out-of-bounds source ranges and attempts
to assign approval or permissions are rejected. Review snapshots remain capped at 100.

The extraction algorithm recognizes explicit Applicability fields outside fenced code,
preserves exact line provenance and reports missing/ambiguous values. It never infers
permissions, verification, approval or evidence roles from text. A human must review the
proposals. Java versions use ASCII digits, as in earlier migration stages.

## Database boundary and concurrency

A fresh `opslens_draft_writer` connection handles each operation. The credential file is
reloaded per operation and contains no administrator, reader, reviewer or publisher
password. Ownership is the Python-compatible SHA-256 of `mode:subject`; raw identity
subjects are not stored in drafts. Owner scope is set with transaction-local
`opslens.draft_owner`, and existing forced row-level policies enforce privacy.

The adapter verifies its restricted login, lack of role memberships, forced RLS and
append-only grants. Unexpected privileges or missing protection fail with a fixed 503.
No Java schema migrations or changes to SQL policies are introduced.

READ COMMITTED transactions deliberately acquire the existing Python advisory locks
before quota/revision checks. The statement after a blocked lock sees the winning
writer's commit. Per-owner locks enforce 20 drafts even under concurrent upload;
per-draft locks prevent lost review updates. Any failed transaction rolls back.

Stored profiles are also checked against the original content when loading a review.
This is stricter than Python's metadata-only latest-review read. Java bounds canonical
review JSON at 10,000 bytes before insertion and the HTTP review body at 20,000 bytes;
very large Unicode metadata can be rejected earlier than Python's PostgreSQL check.
No complete authoring parity claim is made for these deliberately stricter cases.

## Optional development configuration

The running Python app and its settings remain unchanged. These commands are optional,
not a cutover or a request to modify Google settings:

```sh
.venv/bin/python scripts/init_java_drafts.py
```

This creates `.local/java-drafts.json` with mode 0600 from the existing restricted
`DRAFTS_PASSWORD`, and refuses to overwrite a file. It is never committed. Add these
options to the separately configured Java development server:

```text
--runreviq.drafts.enabled=true
--runreviq.drafts.config-path=/absolute/path/to/.local/java-drafts.json
```

Java still runs at 127.0.0.1:8081 with no workspace UI. Keep Python at port 8000 until
cutover gates pass. Review [authentication](java-authentication.md) for Google/demo
configuration. Live Google-to-Java sign-in remains unverified.

## Verification

```sh
.venv/bin/python scripts/export_java_authoring_parity.py --check
./backend/mvnw -f backend/pom.xml -B -ntp verify
RUNREVIQ_TEST_DATABASE=1 .venv/bin/python scripts/test_java_database.py
```

The frozen fixture compares 18 extraction and 12 review responses against Python;
it is implementation-authored regression evidence, not an independent evaluation.
Database tests use only a fresh disposable database. They exercise real HTTP and JDBC,
owner privacy, two-writer quota/review races, rollback, privileges, disabled RLS,
source bounds, malformed UTF-8/JSON, Google Origin checks, access revocation and logout.
Python creates a draft which Java reads exactly and reviews; Python then reads the new
review back. Existing evidence-read tests run alongside these checks. Integration tests
are skipped without the disposable harness and are not counted as passing unit tests.
