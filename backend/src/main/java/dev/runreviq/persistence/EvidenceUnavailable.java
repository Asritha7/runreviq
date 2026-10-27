package dev.runreviq.persistence;

/** Never retain source rows, SQL parameters or credentials in client-facing exceptions. */
public final class EvidenceUnavailable extends RuntimeException {
    public EvidenceUnavailable() { super("Evidence store unavailable"); }
}
