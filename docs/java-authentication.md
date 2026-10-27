# Java identity migration

This is an opt-in identity implementation on loopback port 8081. The working Python
workspace remains on port 8000. Java does not yet serve the UI. Optional JDBC evidence reads are described in [Java persistence](java-persistence.md).

## Methods and trust boundary

- **Google mode:** authorization-code OIDC with PKCE S256, random state and nonce,
  and a separate HttpOnly browser-binding cookie. Flows expire after 10 minutes and
  are consumed once, including rejected callbacks.
- **Token validation:** Spring Security's Nimbus decoder checks RS256 signatures
  against Google's rotating JWK set. Strict raw claims are checked for issuer,
  subject, audience, authorized party, nonce, expiry, issue time and optional not-before.
  Only Google issuer variants are accepted. Integral numeric dates are required;
  unlike the Python helper, fractional timestamps are rejected.
- **Application sessions:** random 256-bit cookies; only their SHA-256 digests are
  kept server-side. Sessions expire at the earlier of one hour or ID-token expiry.
  Signing in again invalidates the previous session presented by that browser.
  Provider tokens are never application bearer credentials or browser storage.
- **Authorization:** Google `sub` maps to `platform`, `security`, or `intern` in the
  private operator file. No email/domain/text/client role grants access. Roles are
  reloaded for each identity request. An unassigned user may inspect their own subject
  at `/auth/session`, but `/v1/me` returns 403. Removing a role takes effect on the
  next request; changing the client ID invalidates existing sessions.
- **Demo mode:** owner-only local bearer credentials, hashed for constant-time
  comparison and reloaded per request. Google cookies are not demo credentials;
  bearer keys do not bypass Google mode. There is no automatic fallback.

Spring Security permits the listed identity routes and explicitly enabled evidence reads; the controller
then requires `AuthService.principal` for identity reads. Unimplemented data routes
are denied even after login. Enabled evidence reads use this same identity boundary
before a role-specific JDBC connection; their separate RLS tests are described in
the persistence guide. Review and publisher permissions remain separate
future ports of the existing Python policy.

## HTTP contract

| Method and route | Behavior |
| --- | --- |
| GET `/health` | Public liveness; no secrets or database checks |
| GET `/auth/config` | `mode`: `disabled`, `google`, or `demo`; configured-file failures return 503 |
| GET `/auth/google/login` | Google-only 303 redirect, state/PKCE/nonce and browser-binding cookie |
| GET `/auth/google/callback` | Google-only, one-use flow; success redirects to `/`, rejection to `/?login=failed` |
| GET `/auth/session` | Google-only current subject and nullable role; no session returns 401 |
| GET `/v1/me` | Current subject and assigned role; missing identity 401, unassigned Google user 403 |
| POST `/auth/logout` | Google-only 204; invalidates session and clears its cookie |

The redirect target `/` deliberately has no Java workspace yet and returns 403.
After a manual login smoke test, inspect `/auth/session` or `/v1/me` directly.
A future UI migration will make the landing page usable.

Logout is protected by the existing frontend contract: exact configured `Origin`
and `X-Runreviq-Request: 1`. Logout and explicitly enabled `/v1/select` are excluded from Spring's token-based
CSRF check; Google-mode service calls enforce both headers instead. Other unsafe routes
remain denied. No cross-origin grants are installed, Host must be `127.0.0.1`,
forwarded headers are ignored, responses are non-cacheable and referrers suppressed.
Do not relax these controls to solve a callback/port mismatch.

## Explicit configuration

Build from the repository root:

```sh
./backend/mvnw -f backend/pom.xml verify
```

Default mode is `disabled`, which does not read any existing private file. For an
optional Java Google smoke test, first register this **additional** callback on the
Google Web client: `http://127.0.0.1:8081/auth/google/callback`. Retain the existing
`http://127.0.0.1:8000/auth/google/callback`. No cloud setting is changed by this PR.

Run from the repository root using the already configured private operator file:

```sh
java -jar backend/target/runreviq-backend-0.1.0-SNAPSHOT.jar \
  --runreviq.auth.mode=google \
  --runreviq.auth.origin=http://127.0.0.1:8081 \
  --runreviq.auth.config-path="$PWD/.local/google-oidc.json"
```

Only a file path belongs in the command; never put the client secret or bearer key in
arguments, repository files or screenshots. Java reads the same JSON shape as Python:
`client_id`, `client_secret`, `allowed_subjects`. It never edits the file. The file
must be regular, non-symlinked, owner-only (for example, 0600); configurations over 128 KiB,
invalid roles, duplicate JSON fields, missing files or unsafe permissions fail closed.
Google/demo mode validates configuration at startup and again on identity requests.
Use an absolute path so the working directory does not change credential selection.

Demo uses the same command with `--runreviq.auth.mode=demo` and the absolute path to
`.local/api-keys.json`, retaining the existing list of `{subject, role, token}` records.
Do not enable demo mode for a workspace intended to require Google sign-in.

## Migration limits

- Separate cookies (`runreviq_java_session`, `runreviq_java_login`, and a separate
  framework cookie name) avoid overwriting Python cookies on the same host.
- Cookies intentionally lack `Secure` for loopback HTTP. This implementation is not
  suitable for public HTTP deployment. TLS, deployment configuration and distributed
  session storage require a separate reviewed change.
- Session and pending-flow stores each have a 1,000-entry limit with expiry pruning.
  Restarting Java signs users out; one process is supported.
- Offline tests cover token checks and HTTP behavior. A live Google account roundtrip
  has not been executed on Java. Existing Python Google login remains the working app.
- No local credentials, subjects, Google settings, existing sessions or product data
  were changed. Python's offline benchmarks remain in place.

References: [Google OIDC protocol](https://developers.google.com/identity/openid-connect/openid-connect)
and [Spring Security JWT validation](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
