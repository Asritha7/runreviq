package dev.runreviq.domain;

import java.util.Set;

/** Role is supplied by a trusted identity adapter, never an HTTP request field. */
public record SelectionContext(String role, String service, String softwareVersion,
        String environment, Boolean keyRotationEnabled, Integer httpStatus) {
    public SelectionContext {
        if (role == null || !Set.of("platform", "security", "intern").contains(role)) throw new IllegalArgumentException("Unknown role");
        SoftwareVersion.parse(softwareVersion);
        if (environment == null || !Set.of("production", "staging").contains(environment)) throw new IllegalArgumentException("Invalid environment");
        if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) throw new IllegalArgumentException("Invalid HTTP status");
    }
}
