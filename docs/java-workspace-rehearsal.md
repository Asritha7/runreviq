# Isolated Java workspace rehearsal

Java can optionally serve the existing browser workspace with
`--runreviq.web.enabled=true`. The default remains disabled. Maven packages exactly
four shared files from `runreviq/web`; there is no second frontend to maintain.
The controller exposes only `/` and `/assets/app.js`, `/assets/theme.js`,
`/assets/app.css`. API authorization stays in its existing controllers; enabling
web assets does not enable database, draft, workflow or authentication adapters.

The workspace retains same-origin fetches, memory-only demo keys, Google session
handling, dark mode and text-only evidence rendering. Java supplies no-store,
nosniff, no-referrer, frame denial and a self-only script/style/connect policy.
No inline script allowance, cross-origin grants or arbitrary filesystem serving
is added. Loopback Host checks still apply.

## Try without touching the working app

With the existing local PostgreSQL service and Java toolchain available:

```sh
.venv/bin/python scripts/rehearse_java_workspace.py
```

The launcher builds Java, copies the JAR into a private temporary directory,
creates a uniquely named disposable database, and imports fictional source fixtures.
It generates temporary demo identities and restricted reader/author/reviewer/publisher
configuration. Java receives an allowlisted runtime environment, excluding shell
credentials, Spring overrides and JVM option injection variables; no database
administrator credential is passed to Java. It launches
on a randomly assigned loopback port and prints that URL and the path to its private
`auth.json`. Open that file locally to copy a temporary token into the workspace.
Do not share or commit these credentials.

Use the platform identity to author/read, security to review, and intern to publish.
Publishing authority does not grant unrestricted reading access. Use Disconnect to
switch identities, or open separate tabs. These are synthetic demo identities, not
Google accounts. Only use fictional rehearsal material.

The launcher has no option for an existing database or private configuration.
It keeps reviewed-v5 search enabled. Port 8000 and its configuration are untouched.
After 15 minutes, or Ctrl-C, the owned process stops and temporary data/configuration
are deleted. `--minutes 1..60` changes the duration. Forced termination of the launcher
or machine failure can prevent cleanup; inspect leftover `opslens_java_eval_*`
databases before manually removing only a verified abandoned rehearsal database.

For a noninteractive packaged-server smoke test:

```sh
.venv/bin/python scripts/rehearse_java_workspace.py --verify-only
```

This verifies exact packaged assets and all three demo identities from outside the
source checkout, then cleans up. It is not browser end-to-end coverage.

## Workflow and compatibility checks

```sh
./backend/mvnw -f backend/pom.xml -B -ntp verify
RUNREVIQ_TEST_DATABASE=1 .venv/bin/python scripts/test_java_database.py
node --test tests/web.test.cjs
```

The workflow HTTP test serves the shell and then uploads Markdown, saves metadata,
submits, independently approves, publishes, searches as permitted/denied roles, reads
an exact revision and withdraws it. It checks that withdrawal removes search passages
while preserving readable history. Other tests exercise session guards and revoked
privileges with an offline identity provider. Python reads Java-written drafts and
publication records back with matching checksums and citations.

This is API workflow and storage compatibility evidence. It is not a completed
process-switch rollback drill or a live Google sign-in test. Existing Node tests use
a simulated DOM/fetch; they do not prove real browser layout or workflow behavior.

## Executed browser smoke check

On 29 October 2026, the in-app browser loaded the disposable Java workspace,
connected as the synthetic platform identity, searched for intermittent 401 errors,
opened the cited RB-042 revision, selected dark mode and disconnected. Evidence and
the source viewer cleared on disconnect. This is a narrow smoke check, not the full
browser author/reviewer/publisher lifecycle or a real Google login.

## Follow-up acceptance

The full synthetic browser author/reviewer/publisher workflow and a same-port
Java → Python → Java process drill were subsequently executed. See
[runtime acceptance](runtime-acceptance.md) for exact coverage, limits and results.
The earlier checks above remain their original, narrower evidence.

## Remaining cutover gates

1. Repeat the accepted synthetic browser workflow for the release artifact, including
   failed requests and session handling with the intended identity provider.
2. Use a separate isolated Google configuration and register the exact rehearsal
   callback. Preserve the port-8000 callback. Test real login, logout, expiration and
   revoked assignment; never copy production role assignments into a public fixture.
3. Extend the executed disposable process-switch drill to the retained release artifact,
   operational failures and in-flight request handling.
4. Review independent retrieval results and the explicit cutover decision before
   changing the working application's startup or port. Keep the last working Python
   release and private configuration available for rollback.

This change is an opt-in rehearsal facility, not a live cutover, deployment,
semantic retrieval improvement or production-readiness claim.
