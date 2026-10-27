package dev.runreviq.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Python json.dumps(sort_keys=True, ensure_ascii=True) for the integer-only metadata schema. */
public final class PythonJson {
    private PythonJson() {}
    public static String encode(JsonNode node) {
        if (node.isNull()) return "null";
        if (node.isBoolean()) return node.asBoolean() ? "true" : "false";
        if (node.isIntegralNumber()) return node.bigIntegerValue().toString();
        if (node.isString()) return quote(node.asString());
        if (node.isArray()) {
            var values = new ArrayList<String>(); node.forEach(n -> values.add(encode(n)));
            return "[" + String.join(", ", values) + "]";
        }
        if (node.isObject()) {
            var keys = new ArrayList<>(node.propertyNames());
            // Python sorts Unicode code points, not UTF-16 surrogate units.
            keys.sort((a,b) -> Arrays.compare(a.codePoints().toArray(), b.codePoints().toArray()));
            var values = new ArrayList<String>();
            for (var key : keys) values.add(quote(key) + ": " + encode(node.get(key)));
            return "{" + String.join(", ", values) + "}";
        }
        throw new IllegalArgumentException("Unsupported metadata value");
    }
    private static String quote(String value) {
        var out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b"); case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> { if (c < 32 || c >= 127) out.append(String.format(Locale.ROOT,"\\u%04x",(int)c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }
    public static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
