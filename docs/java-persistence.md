# Java PostgreSQL evidence reads

The third migration stage adds JDBC repositories and opt-in authenticated selection
and revision endpoints. Later stages add private drafts/workflows and [passage search](java-search.md). Ingestion and schema ownership remain with Python. The existing Python application remains on 8000;
Java remains on loopback 8081, without the workspace frontend.

## Database boundary

Java connects directly as `opslens_reader_platform`, `opslens_reader_security`, or
`opslens_reader_intern`, selected from the authenticated identity. It accepts no admin
username, arbitrary connection URL or caller-supplied role. There is no fallback.
PostgreSQL row-level policies, including current access to historical revisions,
remain the primary authorization boundary. SQL queries use parameters for IDs/revisions.

Each operation opens a fresh connection and read-only, repeatable-read transaction.
Connections are not pooled or reused across roles. Checks reject privileged readers,
role memberships, ownership of evidence tables, table write privileges, or missing/
disabled/unenforced RLS. A failure rolls back and closes the connection. Statement,
connection and socket timeouts bound stalled database work. Existing SQL migrations
remain owned by the Python bootstrap command; Java runs no schema changes or auto-DDL.

The transaction keeps content, lifecycle and permissions internally consistent.
Revocation committed during an in-flight read takes effect on the next request;
it does not retroactively cancel a response based on an already established snapshot.

## Evidence integrity and behavior

- Current selection excludes withdrawn revisions before metadata inspection.
- Revision reads preserve historical content and distinguish active, superseded,
  withdrawn and other source statuses. Missing and inaccessible revisions return the
  same 404 response, with no title, source passage or existence clue.
- After RLS filtering, Java checks the content-and-metadata checksum against Python's
  canonical JSON serialization, including Unicode escaping, key ordering, separators,
  booleans, nulls and large integers. Permissions remain current policy outside the hash.
- Claim quotations must match exact source lines and the content SHA-256. Evidence
  units must be valid ordered ranges containing source text; prohibition units need
  a cited `must_not` claim inside the range. Legacy uncited claims remain supported.
- Integrity/configuration/database failures return a fixed 503 message; no SQL rows,
  passwords or exception details reach the client. Unlike the current Python revision
  reader, Java actively checks checksums on reads. This is a deliberate stricter check.

Authenticated `POST /v1/select`, `POST /v1/search` and `GET /v1/documents/{id}/revisions/{revision}` use the
existing Python JSON contract. Selection fields are strictly typed; extra fields,
including a claimed role, are rejected. Authentication precedes body parsing and SQL.
Google sessions retain exact-Origin/custom-header checks for POST; demo mode requires
a bearer credential. Private drafts have a separate opt-in [authoring adapter](java-authoring.md); workflow APIs have their own [operator adapters](java-workflows.md); search uses this reader adapter. Both authentication and database
reads must be explicitly enabled; default startup does not open PostgreSQL.

## Optional local setup

The existing Python setup must already have initialized PostgreSQL and its restricted
readers. This helper creates a new owner-only file using those reader credentials;
it never overwrites a file or copies the administrator/writer passwords:

```sh
.venv/bin/python scripts/init_java_readers.py
./backend/mvnw -f backend/pom.xml verify
java -jar backend/target/runreviq-backend-0.1.0-SNAPSHOT.jar \
  --runreviq.auth.mode=google \
  --runreviq.auth.origin=http://127.0.0.1:8081 \
  --runreviq.auth.config-path="$PWD/.local/google-oidc.json" \
  --runreviq.database.enabled=true \
  --runreviq.database.config-path="$PWD/.local/java-readers.json"
```

Run from the repository root. Google still needs the additional 8081 callback described
in [Java authentication](java-authentication.md); retain the Python 8000 callback.
Do not put passwords or bearer tokens in command arguments. The private reader file
has exactly `database` and `reader_passwords` (platform/security/intern). Host and port
are fixed to the existing local PostgreSQL instance, `127.0.0.1:55432`. The PostgreSQL
schema/database retain their internal `opslens` names to preserve existing data.

The reader configuration is reloaded per operation, allowing password changes without
restart. Missing files, unsafe permissions and invalid values fail closed. These
instructions are optional; this change does not create a live configuration, change
Google settings, restart the Python app or modify product data.

## Validation

```sh
RUNREVIQ_TEST_DATABASE=1 .venv/bin/python scripts/test_java_database.py
```

The harness creates a random `opslens_java_test_*` database, applies the unchanged
Python migrations and imports synthetic source documents. It compares Python and Java
responses over real HTTP, tests RLS, session authorization, role revocation, historical
revisions, withdrawal timestamps, corruption, transaction consistency and rejected
writes/role switching. It drops only that freshly created database in `finally`.

Admin credentials exist only in a temporary owner-only test file, and the Java test
helper refuses any database other than the exact generated test name. The production
Java reader configuration never receives those credentials. Existing roles are reused
without altering their passwords or memberships. No integration writes target `opslens`.

CI runs the harness in the existing PostgreSQL test job. Normal Maven verification
runs unit/security/parity tests and skips integration classes when the harness's
temporary configuration is absent. A skipped integration suite is not a database pass.
Python and Java parity establishes compatibility, not independent retrieval quality.

Stage 4a now provides [private drafts and metadata review](java-authoring.md) with a
separate restricted writer. Stage 4b adds [independent decisions/publication](java-workflows.md).
