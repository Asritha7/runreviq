package dev.runreviq.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;

/** Single-process loopback sessions. Identity is re-authorized from local configuration per request. */
public final class AuthService {
    public static final String SESSION_COOKIE = "runreviq_java_session", FLOW_COOKIE = "runreviq_java_login";
    public record Principal(String subject, String role) {}
    private record Flow(String nonce, String verifier, String bindingHash, String clientId, Instant expires) {}
    private record Session(String subject, String clientId, Instant expires) {}
    private final String mode, origin;
    private final Path configPath;
    private final Clock clock;
    private final IdentityProvider provider;
    private final SecureRandom random = new SecureRandom();
    private final Map<String,Flow> pending = new HashMap<>();
    private final Map<String,Session> sessions = new HashMap<>();
    private final Object lock = new Object();

    public AuthService(String mode, String origin, Path configPath, Clock clock, IdentityProvider provider) {
        if (!Set.of("disabled", "google", "demo").contains(mode)) throw new IllegalArgumentException("Invalid auth mode");
        var uri = URI.create(origin);
        if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort() < 1
                || uri.getPort() > 65535 || uri.getUserInfo() != null || !uri.getRawPath().isEmpty()
                || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException("Use an explicit loopback origin");
        this.mode = mode; this.origin = origin; this.configPath = configPath; this.clock = clock; this.provider = provider;
        if (!mode.equals("disabled")) configuration();
    }
    public Map<String,String> configuration() {
        if (mode.equals("google")) PrivateConfiguration.google(configPath);
        if (mode.equals("demo")) PrivateConfiguration.demo(configPath);
        return Map.of("mode", mode);
    }
    public String mode() { return mode; }
    public String origin() { return origin; }
    private void requireGoogle() { if (!mode.equals("google")) throw new AuthFailure(403,"Google login is disabled"); }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static boolean equal(String a, String b) {
        return MessageDigest.isEqual(digest(a).getBytes(StandardCharsets.US_ASCII), digest(b).getBytes(StandardCharsets.US_ASCII));
    }
    private String token() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private void prune() {
        Instant now = clock.instant();
        pending.values().removeIf(v -> !v.expires().isAfter(now));
        sessions.values().removeIf(v -> !v.expires().isAfter(now));
    }
    private static String cookie(HttpServletRequest request, String name) {
        String result = "";
        boolean seen = false;
        if (request.getCookies() != null) for (Cookie cookie : request.getCookies()) if (name.equals(cookie.getName())) {
            if (seen || cookie.getValue().length() > 256) throw new AuthFailure(400,"Invalid request");
            seen = true; result = cookie.getValue();
        }
        return result;
    }
    private static String cookieHeader(String name, String value, long seconds, String path) {
        return ResponseCookie.from(name, value).httpOnly(true).sameSite("Lax").path(path).maxAge(seconds).build().toString();
    }
    public ResponseEntity<Void> start() {
        requireGoogle();
        var config = PrivateConfiguration.google(configPath);
        String state = token(), nonce = token(), verifier = token(), binding = token();
        synchronized (lock) {
            prune();
            if (pending.size() >= 1000) throw new AuthFailure(503,"Too many pending logins");
            pending.put(digest(state), new Flow(nonce, verifier, digest(binding), config.clientId(), clock.instant().plusSeconds(600)));
        }
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(HexFormat.of().parseHex(digest(verifier)));
        String target = GoogleIdentityProvider.AUTH_URL + "?" + GoogleIdentityProvider.form(Map.of(
                "client_id",config.clientId(), "redirect_uri",origin + "/auth/google/callback", "response_type","code",
                "scope","openid", "state",state, "nonce",nonce, "code_challenge",challenge,
                "code_challenge_method","S256", "prompt","select_account"));
        return ResponseEntity.status(303).header("Location",target)
                .header("Set-Cookie",cookieHeader(FLOW_COOKIE,binding,600,"/auth/google")).build();
    }
    public ResponseEntity<Void> callback(HttpServletRequest request) {
        requireGoogle();
        String state = request.getParameter("state"), code = request.getParameter("code");
        Flow flow;
        synchronized (lock) { prune(); flow = pending.remove(digest(state == null ? "" : state)); }
        var response = ResponseEntity.status(303).header("Set-Cookie",cookieHeader(FLOW_COOKIE,"",0,"/auth/google"));
        String binding = cookie(request,FLOW_COOKIE);
        if (flow == null || binding.isEmpty() || !equal(flow.bindingHash(),digest(binding)) || request.getParameter("error") != null
                || code == null || code.isEmpty() || code.length() > 4096 || state == null || state.length() > 256
                || request.getParameterValues("state").length != 1 || request.getParameterValues("code").length != 1)
            return response.header("Location","/?login=failed").build();
        var config = PrivateConfiguration.google(configPath);
        if (!config.clientId().equals(flow.clientId())) return response.header("Location","/?login=failed").build();
        IdentityProvider.VerifiedIdentity identity;
        try {
            identity = provider.exchange(code,flow.verifier(),flow.nonce(),config,origin + "/auth/google/callback");
            if (identity.subject() == null || identity.subject().isBlank() || identity.subject().length() > 255
                    || !identity.expiresAt().isAfter(clock.instant())) throw new IllegalArgumentException();
        } catch (Exception ignored) { return response.header("Location","/?login=failed").build(); }
        String newCookie = token();
        synchronized (lock) {
            prune();
            if (sessions.size() >= 1000) throw new AuthFailure(503,"Too many active sessions");
            sessions.remove(digest(cookie(request,SESSION_COOKIE)));
            Instant expires = clock.instant().plusSeconds(3600);
            if (identity.expiresAt().isBefore(expires)) expires = identity.expiresAt();
            sessions.put(digest(newCookie),new Session(identity.subject(),config.clientId(),expires));
        }
        return response.header("Location","/").header("Set-Cookie",cookieHeader(SESSION_COOKIE,newCookie,3600,"/")).build();
    }
    public Principal principal(HttpServletRequest request, boolean requireRole) {
        if (mode.equals("disabled")) throw new AuthFailure(403,"Authentication is disabled");
        if (mode.equals("demo")) return demo(request);
        var config = PrivateConfiguration.google(configPath);
        Session session;
        synchronized (lock) { prune(); session = sessions.get(digest(cookie(request,SESSION_COOKIE))); }
        if (session == null || !session.clientId().equals(config.clientId())) throw new AuthFailure(401,"Sign in with Google to continue");
        String role = config.allowedSubjects().get(session.subject());
        if (requireRole && role == null) throw new AuthFailure(403,"This Google account has no assigned Runreviq role");
        if (!Set.of("GET","HEAD").contains(request.getMethod())) checkOrigin(request);
        return new Principal(session.subject(),role);
    }
    private Principal demo(HttpServletRequest request) {
        var headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.size() != 1 || !headers.getFirst().regionMatches(true,0,"Bearer ",0,7))
            throw new AuthFailure(401,"Valid bearer credentials required");
        String supplied = headers.getFirst().substring(7);
        if (supplied.isEmpty() || supplied.length() > 256) throw new AuthFailure(401,"Valid bearer credentials required");
        for (var record : PrivateConfiguration.demo(configPath)) if (equal(supplied,record.token()))
            return new Principal(record.subject(),record.role());
        throw new AuthFailure(401,"Valid bearer credentials required");
    }
    public void checkOrigin(HttpServletRequest request) {
        if (!origin.equals(request.getHeader("Origin")) || !"1".equals(request.getHeader("X-Runreviq-Request"))
                || Collections.list(request.getHeaders("Origin")).size() != 1
                || Collections.list(request.getHeaders("X-Runreviq-Request")).size() != 1)
            throw new AuthFailure(403,"Same-origin request required");
    }
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        requireGoogle(); checkOrigin(request);
        synchronized (lock) { sessions.remove(digest(cookie(request,SESSION_COOKIE))); }
        return ResponseEntity.noContent().header("Set-Cookie",cookieHeader(SESSION_COOKIE,"",0,"/")).build();
    }
}
