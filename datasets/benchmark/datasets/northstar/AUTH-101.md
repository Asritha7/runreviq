# Signing key propagation

Fictional benchmark only. No real infrastructure or customer data.

## Situation

In the fictional Harbor service, intermittent HTTP 401 responses began after a signing key rotation. Capture the affected key identifier, deployment version and first failure time. A matching old incident is only a hypothesis; this procedure does not establish the current cause.

## Investigation

Compare JWKS refresh failures and signing key identifiers on failing and healthy replicas. Check whether the issuer publishes the new public key before the verifier refreshes its cache. Preserve one redacted failure sample from each replica without copying bearer tokens.

## Controlled response

If the new public key is missing on a failing replica, request incident-lead approval for a targeted verifier cache refresh. Recheck both old and new sessions afterward. Do not restart every replica or disable signature verification to make the error disappear.

## Verification

Escalate when both replicas already have the same public key or the issuer cannot be reached. Record which observation contradicted the propagation hypothesis. This procedure does not cover account lockouts, password resets or identity-provider outages.
