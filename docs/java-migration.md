# Java backend migration

The agreed destination is a Java product backend with Python retained for offline
benchmarks initially. The browser remains HTML/CSS/JavaScript and PostgreSQL remains
the database. The running Python application is unchanged by these migration stages.

Python supported a compact prototype and rapid evaluation. Java is a reasonable
choice for developing this product alongside Java learning and work. Changing the
language does not itself improve retrieval accuracy or make evidence trustworthy.
Both stacks can run locally without paid APIs.

## Stage 1 — merged in PR #27

- Spring Boot 4.1.2, Java 25 language target, Maven 3.9.12 checksum-pinned wrapper.
- Framework-independent typed versions, operational context, evidence profiles,
  document references and applicability selector.
- Role filtering before selection/explanations; approved status, service,
  environment, version ranges, tri-state rotation and HTTP scope checks.
- Same rule-based conflicts, follow-up questions, metadata review explanations,
  abstention and evidence references as the Python selector.
- 324 full-response comparison cases across the original synthetic catalog and a
  separate HTTP-scoped derivative. Existing source documents remain unchanged.
- A server bound to `127.0.0.1:8081`, initially health-only. Stage 2 adds identity
  endpoints; data routes remain denied and password login remains disabled.

The selector accepts trusted domain objects, not arbitrary HTTP input. Role filtering
is not authentication or database authorization. Evidence-profile constructors check
structure; source quote, line-bound, citation hash and publication checks still belong
to the existing Python implementation until explicitly ported. Java exact version
inputs currently use ASCII digits; Python's internal helper also accepts Unicode
numeric digits. Neither supports prereleases. Java now exposes the opt-in read endpoints described in stage 3.

## Stage 2 — identity implementation

Java now supports explicit `google`, `demo`, or default `disabled` authentication.
Google code exchange uses PKCE and browser-bound state/nonce. RS256 signatures and
required identity claims are checked before an opaque, expiring session is created.
Private role mappings and demo keys are reloaded on every identity request. Logout
requires the exact configured Origin and `X-Runreviq-Request: 1` and revokes the
server-side session. Data routes were blocked at stage 2; stage 3 enables only selection/revision reads. See [identity configuration and tests](java-authentication.md).

Offline token, HTTP and revocation tests are implemented. Live Google sign-in on Java
has not been performed; a registered Java callback is a manual smoke-test prerequisite.
No existing Google settings, credentials or Python sessions are changed by this stage.

## Stage 3 — JDBC evidence reads

Implemented: authenticated selection and revision reads through separate restricted
PostgreSQL accounts, read-only snapshot transactions, current RLS enforcement and
content/metadata/citation integrity checks. Python/Java HTTP parity and isolation tests
run against a fresh disposable database. Database access defaults to disabled; existing
runtime and migrations are unchanged. See [Java persistence](java-persistence.md).

Stage 4a adds private draft writes and owner metadata reviews; independent review and publication remain in stage 4b; no Java ingestion or schema migration
owner is introduced. Live Google-to-Java sign-in remains a separate manual smoke-test gate.

## Stage 4a — private drafts and metadata review

Implemented as a separate opt-in boundary: owner-private Markdown uploads, explicit
metadata proposals, source-aware profile validation and append-only review saves with
stale-revision rejection. Real database/HTTP tests cover privacy, concurrency and
Python/Java interoperability. See [Java authoring](java-authoring.md).
Submission, independent decisions, publication and withdrawal remain unported.

## Remaining stages and acceptance gates

| Stage | Work | Required proof before use |
| --- | --- | --- |
| 4b: controlled publication | Submission, independent review, publication and withdrawal | Owner privacy, reviewer/publisher separation, stale-review rejection, history and idempotency parity |
| 5: retrieval | Authorized passage retrieval, reviewed profiles, typed bundles and evidence responses | Same applicability/abstention semantics, citation support and held-out evaluation through a Java HTTP adapter |
| 6: cutover | Existing frontend served by Java; operational tooling, documentation and rollback | Full end-to-end parity, live sign-in smoke test, no unauthorized disclosures, reproducible evaluation and explicit cutover review |

Keep PostgreSQL migrations under the existing Python migration owner initially.
Do not enable JPA auto-DDL or make a second migration tool rewrite the schema.
Stored content/metadata hashes require byte-identical canonical serialization tests;
Java JSON defaults must not silently alter revision fingerprints.

Python offline benchmarks remain separate from retrievable source content. The
migration fixture is generated by `scripts/export_java_parity.py`; it demonstrates
agreement with the current Python selector, not independent correctness. Keep the
user-authored evaluation questions and expected outcomes outside ingestion.

## Runtime and rollback

Port 8000 continues to serve the working Python product. Port 8081 is deliberately
a separate engineering server with no UI. Identity and restricted database reads can be
enabled explicitly; Google requires an additional registered callback for that port.
Keep the existing port-8000 OAuth callback throughout migration. Do not switch users to Java until
the operational stages pass. Preserve the Python entry point and existing database
through cutover so application rollback does not require destructive data migration.

The evaluation-only PR #26 is separate from this migration branch and is not merged
by this change. No local secrets, database data or downloaded toolchains are committed.
