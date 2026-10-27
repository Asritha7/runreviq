package dev.runreviq.auth;

/** Only fixed, safe messages may cross the HTTP boundary. */
public final class AuthFailure extends RuntimeException {
    private final int status;
    public AuthFailure(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
}
