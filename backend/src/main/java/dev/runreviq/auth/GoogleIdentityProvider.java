package dev.runreviq.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/** Google endpoints are fixed; tokens are verified server-side and never returned to clients. */
public final class GoogleIdentityProvider implements IdentityProvider {
    public static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private final NimbusJwtDecoder decoder;
    private final Clock clock;
    private final URI tokenEndpoint;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    public GoogleIdentityProvider(Clock clock) {
        this(remoteDecoder(), clock);
    }
    // Package-private injection for real signature tests with an offline public key.
    GoogleIdentityProvider(NimbusJwtDecoder decoder, Clock clock) {
        this(decoder, clock, URI.create(TOKEN_URL));
    }
    GoogleIdentityProvider(NimbusJwtDecoder decoder, Clock clock, URI tokenEndpoint) {
        this.decoder = decoder; this.clock = clock; this.tokenEndpoint = tokenEndpoint;
        // Strict time/issuer/audience/nonce checks below use the same injected clock.
        decoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
    }
    private static NimbusJwtDecoder remoteDecoder() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000); factory.setReadTimeout(10000);
        return NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .jwsAlgorithm(SignatureAlgorithm.RS256).restOperations(new RestTemplate(factory)).build();
    }
    public static String form(Map<String,String> values) {
        return values.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }
    @Override public VerifiedIdentity exchange(String code, String verifier, String nonce,
            PrivateConfiguration.Google config, String callback) throws Exception {
        String body = form(Map.of("code", code, "code_verifier", verifier, "client_id", config.clientId(),
                "client_secret", config.clientSecret(), "redirect_uri", callback, "grant_type", "authorization_code"));
        var connection = (HttpURLConnection) tokenEndpoint.toURL().openConnection();
        connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(false); connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        connection.setDoOutput(true);
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(data.length);
        try {
            try (var output = connection.getOutputStream()) { output.write(data); }
            if (connection.getResponseCode() != 200) throw new IllegalArgumentException();
            try (var stream = connection.getInputStream()) {
                byte[] bytes = stream.readNBytes(131073);
                if (bytes.length > 131072) throw new IllegalArgumentException();
                var token = json.readTree(bytes).get("id_token");
                if (token == null || !token.isString()) throw new IllegalArgumentException();
                return verify(token.asString(), nonce, config.clientId());
            }
        } finally { connection.disconnect(); }
    }

    VerifiedIdentity verify(String token, String nonce, String clientId) {
        if (token == null || token.length() > 32768) throw new IllegalArgumentException();
        String[] segments = token.split("\\.", -1);
        if (segments.length != 3) throw new IllegalArgumentException();
        var raw = json.readTree(Base64.getUrlDecoder().decode(segments[1]));
        var header = json.readTree(Base64.getUrlDecoder().decode(segments[0]));
        if (!"RS256".equals(header.path("alg").asString(""))) throw new IllegalArgumentException();
        // Inspect raw types before Nimbus/Spring can coerce dates or audience strings.
        for (String field : List.of("iss", "sub", "nonce"))
            if (!raw.has(field) || !raw.get(field).isString()) throw new IllegalArgumentException();
        for (String field : List.of("exp", "iat"))
            if (!raw.has(field) || !raw.get(field).isIntegralNumber() || !raw.get(field).canConvertToLong()) throw new IllegalArgumentException();
        long now = clock.instant().getEpochSecond(), exp = raw.get("exp").asLong(), iat = raw.get("iat").asLong();
        String issuer = raw.get("iss").asString(), subject = raw.get("sub").asString();
        if ((!issuer.equals("https://accounts.google.com") && !issuer.equals("accounts.google.com"))
                || subject.isBlank() || subject.length() > 255 || exp <= now || iat > now || iat > exp
                || !AuthService.equal(raw.get("nonce").asString(), nonce)) throw new IllegalArgumentException();
        var aud = raw.get("aud");
        List<String> audiences = new ArrayList<>();
        if (aud != null && aud.isString()) audiences.add(aud.asString());
        else if (aud != null && aud.isArray()) {
            for (var value : aud) {
                if (!value.isString()) throw new IllegalArgumentException();
                audiences.add(value.asString());
            }
        } else throw new IllegalArgumentException();
        if (!audiences.contains(clientId) || (audiences.size() > 1 && !raw.has("azp"))
                || (raw.has("azp") && (!raw.get("azp").isString() || !clientId.equals(raw.get("azp").asString()))))
            throw new IllegalArgumentException();
        if (raw.has("nbf") && (!raw.get("nbf").isIntegralNumber() || !raw.get("nbf").canConvertToLong()
                || raw.get("nbf").asLong() > now)) throw new IllegalArgumentException();
        decoder.decode(token); // Signature checked against Google's cached, rotating keys; RS256 only.
        return new VerifiedIdentity(subject, Instant.ofEpochSecond(exp));
    }
}
