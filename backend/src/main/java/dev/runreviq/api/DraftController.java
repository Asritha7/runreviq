package dev.runreviq.api;

import dev.runreviq.auth.*;
import dev.runreviq.authoring.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="runreviq.drafts.enabled",havingValue="true")
public class DraftController {
    private final AuthService auth;private final DraftRepository drafts;
    public DraftController(AuthService auth,DraftRepository drafts){this.auth=auth;this.drafts=drafts;}
    private String owner(HttpServletRequest r){var p=auth.principal(r,true);return DraftRepository.owner(auth.mode(),p.subject());}
    private static UUID id(String raw){try{if(!raw.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))throw new IllegalArgumentException();return UUID.fromString(raw);}catch(Exception e){throw DraftFailure.invalid();}}
    private static Map<String,Object> found(Map<String,Object> value){if(value==null)throw new DraftFailure(404,"Draft not found");return value;}
    @PostMapping("/v1/drafts") public ResponseEntity<Map<String,Object>> create(HttpServletRequest request) {
        String owner=owner(request);
        if(request.getContentType()==null||!request.getContentType().split(";",2)[0].trim().equalsIgnoreCase("text/markdown"))throw new DraftFailure(415,"Upload UTF-8 Markdown using text/markdown");
        byte[] bytes;
        try{bytes=request.getInputStream().readNBytes(1_000_001);}catch(Exception e){throw DraftFailure.invalid();}
        if(bytes.length>1_000_000)throw new DraftFailure(413,"Markdown file exceeds 1 MB");
        String content;
        try{content=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException e){throw new DraftFailure(422,"Expected nonempty UTF-8 Markdown with a simple .md filename");}
        if(request.getParameterValues("filename")==null||request.getParameterValues("filename").length!=1)throw DraftFailure.invalid();
        return ResponseEntity.status(201).body(drafts.create(owner,request.getParameter("filename"),content));
    }
    @GetMapping("/v1/drafts") public Map<String,Object> list(HttpServletRequest request){return Map.of("drafts",drafts.list(owner(request)));}
    @GetMapping("/v1/drafts/{identifier}") public Map<String,Object> get(@PathVariable String identifier,HttpServletRequest request){String owner=owner(request);return found(drafts.get(owner,id(identifier)));}
    @PostMapping("/v1/drafts/{identifier}/review") public Map<String,Object> save(@PathVariable String identifier,HttpServletRequest request) {
        String owner=owner(request);UUID id=id(identifier);tools.jackson.databind.JsonNode n;
        try{
            byte[] bytes=request.getInputStream().readNBytes(20_001);if(bytes.length>20_000)throw DraftFailure.invalid();n=DraftRepository.JSON.readTree(bytes);
            if(n==null||!n.isObject()||!Set.copyOf(n.propertyNames()).equals(Set.of("expected_revision","metadata"))
                    ||!n.get("expected_revision").isIntegralNumber()||!n.get("expected_revision").canConvertToInt())throw DraftFailure.invalid();
        }catch(Exception e){throw DraftFailure.invalid();}
        return found(drafts.save(owner,id,n.get("expected_revision").asInt(),n.get("metadata")));
    }
    @ExceptionHandler(DraftFailure.class) ResponseEntity<Map<String,String>> failure(DraftFailure e){return ResponseEntity.status(e.status()).body(Map.of("detail",e.getMessage()));}
    @ExceptionHandler(AuthFailure.class) ResponseEntity<Map<String,String>> authFailure(AuthFailure e){var response=ResponseEntity.status(e.status());if(e.status()==401&&auth.mode().equals("demo"))response.header("WWW-Authenticate","Bearer");return response.body(Map.of("detail",e.getMessage()));}
}
