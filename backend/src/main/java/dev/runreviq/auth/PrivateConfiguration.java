package dev.runreviq.auth;

import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reloads owner-only local configuration; never falls back to another auth mode. */
public final class PrivateConfiguration {
    private static final Set<String> ROLES = Set.of("platform", "security", "intern");
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    public record Google(String clientId, String clientSecret, Map<String,String> allowedSubjects) {
        public Google { allowedSubjects = Map.copyOf(allowedSubjects); }
        @Override public String toString() { return "Google[redacted]"; }
    }
    public record DemoKey(String subject, String role, String token) {
        @Override public String toString() { return "DemoKey[redacted]"; }
    }
    public static JsonNode readOwnerOnly(Path path) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException();
            var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            if (permissions.stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")))
                throw new IllegalArgumentException();
            try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(131073);
                if (bytes.length > 131072) throw new IllegalArgumentException();
                // A concurrent permission change also fails closed.
                if (!permissions.equals(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)))
                    throw new IllegalArgumentException();
                return JSON.readTree(bytes);
            }
        } catch (Exception ignored) { throw new AuthFailure(503, "Authentication configuration unavailable"); }
    }
    private static void fields(JsonNode n, Set<String> expected) {
        if (n == null || !n.isObject() || !Set.copyOf(n.propertyNames()).equals(expected)) throw new IllegalArgumentException();
    }
    private static String string(JsonNode n) {
        if (n == null || !n.isString() || n.asString().isBlank()) throw new IllegalArgumentException();
        return n.asString();
    }
    public static Google google(Path path) {
        try {
            var n = readOwnerOnly(path);
            fields(n, Set.of("client_id", "client_secret", "allowed_subjects"));
            var assignments = n.get("allowed_subjects");
            if (!assignments.isObject()) throw new IllegalArgumentException();
            Map<String,String> roles = new HashMap<>();
            for (String subject : assignments.propertyNames()) {
                String role = string(assignments.get(subject));
                if (subject.isBlank() || subject.length() > 255 || !ROLES.contains(role)) throw new IllegalArgumentException();
                roles.put(subject, role);
            }
            return new Google(string(n.get("client_id")), string(n.get("client_secret")), roles);
        } catch (Exception ignored) { throw new AuthFailure(503, "Google login configuration unavailable"); }
    }
    public static List<DemoKey> demo(Path path) {
        try {
            var n = readOwnerOnly(path);
            if (n == null || !n.isArray() || n.isEmpty()) throw new IllegalArgumentException();
            var records = new ArrayList<DemoKey>();
            Set<String> subjects = new HashSet<>(), tokens = new HashSet<>();
            for (var record : n) {
                fields(record, Set.of("subject", "role", "token"));
                String subject = string(record.get("subject")), role = string(record.get("role")), token = string(record.get("token"));
                if (!ROLES.contains(role) || token.length() < 32 || token.length() > 256
                        || !subjects.add(subject) || !tokens.add(token)) throw new IllegalArgumentException();
                records.add(new DemoKey(subject, role, token));
            }
            return List.copyOf(records);
        } catch (Exception ignored) { throw new AuthFailure(503, "Authentication configuration unavailable"); }
    }
}
