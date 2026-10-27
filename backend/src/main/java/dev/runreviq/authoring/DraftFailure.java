package dev.runreviq.authoring;

/** Fixed public errors; never include source text, credentials or SQL diagnostics. */
public final class DraftFailure extends RuntimeException {
    private final int status;
    public DraftFailure(int status, String message) { super(message); this.status=status; }
    public int status() { return status; }
    public static DraftFailure invalid() { return new DraftFailure(422,"Invalid review fields"); }
    public static DraftFailure unavailable() { return new DraftFailure(503,"Draft store unavailable"); }
}
