package dev.runreviq.auth;

import static org.junit.jupiter.api.Assertions.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import tools.jackson.databind.json.JsonMapper;

class GoogleTokenTest {
    static final Instant NOW=Instant.parse("2026-01-01T00:00:00Z");
    static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    static final JsonMapper JSON=JsonMapper.builder().build();
    static KeyPair key;
    @BeforeAll static void keys() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); key=generator.generateKeyPair();
    }
    GoogleIdentityProvider provider() { return new GoogleIdentityProvider(NimbusJwtDecoder.withPublicKey((RSAPublicKey)key.getPublic()).build(),CLOCK); }
    Map<String,Object> claims() { return new HashMap<>(Map.of("iss","https://accounts.google.com","sub","user-1","aud","test-client",
            "nonce","nonce-1","iat",NOW.getEpochSecond(),"exp",NOW.plusSeconds(60).getEpochSecond())); }
    String sign(Map<String,Object> claims) throws Exception { return signRaw(JSON.writeValueAsString(claims),key); }
    String signRaw(String json, KeyPair pair) throws Exception {
        var jwt=new JWSObject(new JWSHeader(JWSAlgorithm.RS256),new Payload(json));
        jwt.sign(new RSASSASigner((RSAPrivateKey)pair.getPrivate())); return jwt.serialize();
    }
    @Test void acceptsSignedGoogleIdentityAndAuthorizedParty() throws Exception {
        assertEquals("user-1",provider().verify(sign(claims()),"nonce-1","test-client").subject());
        var c=claims(); c.put("iss","accounts.google.com"); c.put("aud",List.of("test-client","other")); c.put("azp","test-client");
        assertEquals("user-1",provider().verify(sign(c),"nonce-1","test-client").subject());
    }
    @TestFactory Stream<DynamicTest> invalidClaimsAreRejected() {
        var mutations=List.<Map<String,Object>>of(Map.of("iss","https://evil.example"), Map.of("sub",""), Map.of("sub",12),
                Map.of("aud","other"), Map.of("aud",List.of("test-client","other")), Map.of("aud",List.of(12,"test-client")),
                Map.of("azp","other"), Map.of("nonce","other"), Map.of("nonce",12), Map.of("exp",NOW.getEpochSecond()),
                Map.of("exp","9999999999"),Map.of("exp",true),Map.of("exp",1.5),Map.of("iat",NOW.plusSeconds(1).getEpochSecond()),
                Map.of("iat","1"),Map.of("nbf",NOW.plusSeconds(1).getEpochSecond()),Map.of("nbf","1"));
        return mutations.stream().map(change->DynamicTest.dynamicTest(change.keySet().toString()+change.values(),()->{
            var c=claims(); c.putAll(change); String jwt=sign(c);
            assertThrows(RuntimeException.class,()->provider().verify(jwt,"nonce-1","test-client"));
        }));
    }
    @TestFactory Stream<DynamicTest> missingClaimsAreRejected() {
        return Stream.of("iss","sub","aud","iat","exp","nonce").map(field->DynamicTest.dynamicTest(field,()->{
            var c=claims(); c.remove(field); String jwt=sign(c);
            assertThrows(RuntimeException.class,()->provider().verify(jwt,"nonce-1","test-client"));
        }));
    }
    @Test void badSignatureUnsignedAndDuplicateClaimsAreRejected() throws Exception {
        var g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);
        String wrong=signRaw(JSON.writeValueAsString(claims()),g.generateKeyPair());
        assertThrows(RuntimeException.class,()->provider().verify(wrong,"nonce-1","test-client"));
        String unsigned=Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))+"."
                +Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(claims()))+".";
        assertThrows(RuntimeException.class,()->provider().verify(unsigned,"nonce-1","test-client"));
        String duplicate=signRaw(JSON.writeValueAsString(claims()).replace("{","{\"sub\":\"other\","),key);
        assertThrows(RuntimeException.class,()->provider().verify(duplicate,"nonce-1","test-client"));
        assertThrows(RuntimeException.class,()->provider().verify("x".repeat(32769),"nonce-1","test-client"));
    }
    @Test void codeExchangeUsesBoundParametersAndRejectsRedirectsOrOversizeBodies() throws Exception {
        String jwt=sign(claims()); var received=new ArrayList<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        int[] status={200}; String[] body={JSON.writeValueAsString(Map.of("id_token",jwt))};
        server.createContext("/token",exchange->{
            received.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            if (status[0]==302) exchange.getResponseHeaders().add("Location","/unexpected");
            byte[] bytes=body[0].getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status[0],bytes.length);
            try(var output=exchange.getResponseBody()){output.write(bytes);}
        }); server.start();
        try {
            var p=new GoogleIdentityProvider(NimbusJwtDecoder.withPublicKey((RSAPublicKey)key.getPublic()).build(),CLOCK,
                    URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/token"));
            var config=new PrivateConfiguration.Google("test-client","offline-secret",Map.of());
            assertEquals("user-1",p.exchange("code", "verifier", "nonce-1",config,"http://127.0.0.1:8081/auth/google/callback").subject());
            assertTrue(received.getFirst().contains("code_verifier=verifier"));
            assertTrue(received.getFirst().contains("grant_type=authorization_code"));
            status[0]=302; assertThrows(Exception.class,()->p.exchange("code","verifier","nonce-1",config,"callback"));
            status[0]=200; body[0]="x".repeat(131073);
            assertThrows(Exception.class,()->p.exchange("code","verifier","nonce-1",config,"callback"));
            assertEquals(3,received.size());
        } finally {server.stop(0);}
    }
}
