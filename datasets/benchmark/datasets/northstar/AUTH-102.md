# Token clock skew

Fictional benchmark only. No real infrastructure or customer data.

## Situation

In the fictional Harbor service, requests fail immediately after hosts resume from maintenance. Token validation reports that a token is not yet valid or has expired. Record timestamps in UTC and compare one healthy host with one failing host.

## Investigation

Compare the verifier clock with the trusted time source and inspect the not-before and expiry timestamps from a redacted token record. If the offset exceeds the configured tolerance, investigate time synchronization. Never log the raw token or its signature.

## Controlled response

Ask the host operator to restore time synchronization using the approved maintenance procedure. Verify the offset before retrying the same request. Do not increase token lifetime or bypass expiration checks as an incident workaround.

## Verification

If clocks agree, stop this procedure and investigate signing key propagation or issuer availability. Keep the original observations so another responder can reproduce the distinction between an expired session and an incorrect host clock.
