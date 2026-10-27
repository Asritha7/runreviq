package dev.runreviq.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Trusted storage model, never populated from a caller's claimed authority. */
public record EvidenceDocument(String id, String title, int revision, String path,
        Set<String> allowedRoles, String status, String service, String environment,
        String versionMin, String versionMaxExclusive, Boolean keyRotationEnabled,
        boolean metadataVerified, List<Claim> claims, EvidenceProfile profile) {
    public EvidenceDocument {
        allowedRoles = Set.copyOf(allowedRoles);
        claims = List.copyOf(claims);
    }
    public record Claim(String action, String rule, Map<String, Object> citation) {
        public Claim { if (citation != null) citation = Map.copyOf(citation); }
    }
}
