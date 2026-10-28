package dev.runreviq.api;

import dev.runreviq.auth.*;
import dev.runreviq.domain.*;
import dev.runreviq.persistence.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
@ConditionalOnProperty(name="runreviq.database.enabled",havingValue="true")
public class EvidenceController {
    private final AuthService auth;
    private final EvidenceRepository repository;
    private final JsonMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public EvidenceController(AuthService auth,EvidenceRepository repository) {this.auth=auth;this.repository=repository;}
    @PostMapping("/v1/select") public Map<String,Object> select(HttpServletRequest request) {return query(request,false);}
    @PostMapping("/v1/search") public Map<String,Object> search(HttpServletRequest request) {return query(request,true);}
    private Map<String,Object> query(HttpServletRequest request,boolean searching) {
        var identity=auth.principal(request,true); // Never parse a caller-supplied role or read evidence before authentication.
        SelectionContext context;String question=null;
        try {
            byte[] bytes=request.getInputStream().readNBytes(4097);
            if(bytes.length>4096) throw new IllegalArgumentException();
            var n=json.readTree(bytes);
            var required=new HashSet<>(Set.of("service","software_version","environment"));if(searching)required.add("question");
            var allowed=new HashSet<>(Set.of("service","software_version","environment","key_rotation_enabled","http_status"));if(searching)allowed.add("question");
            if(n==null || !n.isObject() || !n.propertyNames().containsAll(required) || !allowed.containsAll(n.propertyNames())) throw new IllegalArgumentException();
            for(String key:required) if(!n.get(key).isString()) throw new IllegalArgumentException();
            String service=n.get("service").asString(),version=n.get("software_version").asString();
            if(!service.matches("[a-zA-Z0-9_-]{1,100}") || version.length()>32) throw new IllegalArgumentException();
            Boolean rotation=null;Integer http=null;
            if(n.has("key_rotation_enabled") && !n.get("key_rotation_enabled").isNull()) {
                if(!n.get("key_rotation_enabled").isBoolean()) throw new IllegalArgumentException();
                rotation=n.get("key_rotation_enabled").asBoolean();
            }
            if(n.has("http_status") && !n.get("http_status").isNull()) {
                if(!n.get("http_status").isIntegralNumber() || !n.get("http_status").canConvertToInt()) throw new IllegalArgumentException();
                http=n.get("http_status").asInt();
            }
            if(searching) {
                question=n.get("question").asString();
                if(question.codePointCount(0,question.length())>500)throw new IllegalArgumentException();
            }
            context=new SelectionContext(identity.role(),service,version,n.get("environment").asString(),rotation,http);
        } catch(Exception ignored) {throw new AuthFailure(422,"Invalid request fields or values");}
        if(searching){try{return repository.search(question,context);}catch(IllegalArgumentException e){throw new AuthFailure(422,"Invalid request fields or values");}}
        return new ApplicabilitySelector().select(repository.current(identity.role()),context);
    }
    @GetMapping("/v1/documents/{id}/revisions/{revision}") public Map<String,Object> revision(
            @PathVariable String id,@PathVariable String revision,HttpServletRequest request) {
        var identity=auth.principal(request,true);
        int number;
        try {
            if(!id.matches("[A-Z]+-[0-9]+") || !revision.matches("[0-9]{1,10}")) throw new IllegalArgumentException();
            number=Integer.parseInt(revision);if(number<1) throw new IllegalArgumentException();
        } catch(Exception ignored) {throw new AuthFailure(422,"Invalid request fields or values");}
        var result=repository.revision(identity.role(),id,number);
        if(result==null) throw new AuthFailure(404,"Document revision not found");
        return result;
    }
    @ExceptionHandler(AuthFailure.class) ResponseEntity<Map<String,String>> authFailure(AuthFailure failure) {
        var response=ResponseEntity.status(failure.status());
        if(failure.status()==401 && auth.mode().equals("demo")) response.header("WWW-Authenticate","Bearer");
        return response.body(Map.of("detail",failure.getMessage()));
    }
    @ExceptionHandler(EvidenceUnavailable.class) ResponseEntity<Map<String,String>> unavailable() {
        return ResponseEntity.status(503).body(Map.of("detail","Evidence store unavailable"));
    }
}
