package dev.runreviq.auth;

import java.time.Instant;

public interface IdentityProvider {
    record VerifiedIdentity(String subject, Instant expiresAt) {}
    VerifiedIdentity exchange(String code, String verifier, String nonce,
                              PrivateConfiguration.Google config, String callback) throws Exception;
}
