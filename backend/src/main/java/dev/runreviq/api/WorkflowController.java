package dev.runreviq.api;

import dev.runreviq.auth.*;
import dev.runreviq.authoring.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@ConditionalOnProperty(name="runreviq.workflows.enabled",havingValue="true")
public class WorkflowController {
    private final AuthService auth;private final WorkflowAccess access;private final SubmissionRepository submissions;private final PublicationRepository publications;private final LifecycleRepository lifecycle;
    public WorkflowController(AuthService auth,WorkflowAccess access,SubmissionRepository submissions,PublicationRepository publications,LifecycleRepository lifecycle){this.auth=auth;this.access=access;this.submissions=submissions;this.publications=publications;this.lifecycle=lifecycle;}
    private String owner(HttpServletRequest request){var p=auth.principal(request,true);return DraftRepository.owner(auth.mode(),p.subject());}
    private String operator(HttpServletRequest request,boolean publisher){var p=auth.principal(request,true);if(!access.allowed(publisher,auth.mode(),p.subject()))throw new AuthFailure(403,publisher?"Publisher permission must be assigned separately by the operator":"Reviewer permission must be assigned separately by the operator");return DraftRepository.owner(auth.mode(),p.subject());}
    private static UUID uuid(String id){try{if(!id.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))throw new IllegalArgumentException();return UUID.fromString(id);}catch(Exception e){throw new AuthFailure(422,"Invalid request fields or values");}}
    private static <T>T found(T item){if(item==null)throw new DraftFailure(404,"Submission or draft not found");return item;}
    private static <T>T document(T item){if(item==null)throw new DraftFailure(404,"Published document or revision not found");return item;}
    private static int integer(JsonNode n,int min,int max){if(n==null||!n.isIntegralNumber()||!n.canConvertToInt()||n.asInt()<min||n.asInt()>max)throw new AuthFailure(422,"Invalid request fields or values");return n.asInt();}
    private static String string(JsonNode n){if(n==null||!n.isString())throw new AuthFailure(422,"Invalid request fields or values");return n.asString();}
    private static JsonNode body(HttpServletRequest request,String... fields){try{var bytes=request.getInputStream().readNBytes(20_001);if(bytes.length>20_000)throw new AuthFailure(422,"Invalid request fields or values");var n=DraftRepository.JSON.readTree(bytes);if(n==null||!n.isObject()||!Set.copyOf(n.propertyNames()).equals(Set.of(fields)))throw new AuthFailure(422,"Invalid request fields or values");return n;}catch(Exception e){throw new AuthFailure(422,"Invalid request fields or values");}}
    @GetMapping("/v1/reviewer-access") public Map<String,Boolean> reviewerAccess(HttpServletRequest r){var p=auth.principal(r,true);return Map.of("can_review",access.allowed(false,auth.mode(),p.subject()));}
    @GetMapping("/v1/publisher-access") public Map<String,Boolean> publisherAccess(HttpServletRequest r){var p=auth.principal(r,true);return Map.of("can_publish",access.allowed(true,auth.mode(),p.subject()));}
    @PostMapping("/v1/drafts/{id}/submit") public Map<String,Object> submit(@PathVariable String id,HttpServletRequest r){String owner=owner(r);var n=body(r,"revision");return found(submissions.submit(owner,uuid(id),integer(n.get("revision"),1,100)));}
    @GetMapping("/v1/drafts/{id}/submissions") public Map<String,Object> own(@PathVariable String id,HttpServletRequest r){String owner=owner(r);return Map.of("submissions",found(submissions.own(owner,uuid(id))));}
    @GetMapping("/v1/submissions") public Map<String,Object> queue(HttpServletRequest r){return Map.of("submissions",submissions.queue(operator(r,false)));}
    @GetMapping("/v1/submissions/{id}") public Map<String,Object> detail(@PathVariable String id,HttpServletRequest r){String actor=operator(r,false);return found(submissions.detail(actor,uuid(id)));}
    @PostMapping("/v1/submissions/{id}/decision") public Map<String,Object> decide(@PathVariable String id,HttpServletRequest r){String actor=operator(r,false);var n=body(r,"decision","comment");return found(submissions.decide(actor,uuid(id),string(n.get("decision")),string(n.get("comment"))));}
    @GetMapping("/v1/publications/queue") public Map<String,Object> publishQueue(HttpServletRequest r){return Map.of("submissions",publications.queue(operator(r,true)));}
    @GetMapping("/v1/publications/{id}") public Map<String,Object> publishDetail(@PathVariable String id,HttpServletRequest r){String actor=operator(r,true);return found(publications.detail(actor,uuid(id)));}
    @PostMapping("/v1/publications/{id}") public Map<String,Object> publish(@PathVariable String id,HttpServletRequest r){String actor=operator(r,true);var n=body(r,"allowed_roles","claims","metadata_verified");if(!n.get("metadata_verified").isBoolean())throw new AuthFailure(422,"Invalid request fields or values");return found(publications.publish(actor,uuid(id),n.get("allowed_roles"),n.get("claims"),n.get("metadata_verified").asBoolean()));}
    @GetMapping("/v1/publication-management/documents") public Map<String,Object> documents(HttpServletRequest r){String actor=operator(r,true);String after=r.getParameter("after");if(after!=null&&r.getParameterValues("after").length!=1)throw new AuthFailure(422,"Invalid request fields or values");return lifecycle.documents(actor,after==null?"":after);}
    @GetMapping("/v1/publication-management/documents/{id}") public Map<String,Object> history(@PathVariable String id,HttpServletRequest r){String actor=operator(r,true);return document(lifecycle.history(actor,id));}
    @GetMapping("/v1/publication-management/documents/{id}/revisions/{revision}") public Map<String,Object> revision(@PathVariable String id,@PathVariable String revision,HttpServletRequest r){String actor=operator(r,true);int number;try{if(!revision.matches("[0-9]{1,10}"))throw new IllegalArgumentException();number=Integer.parseInt(revision);}catch(Exception e){throw new AuthFailure(422,"Invalid request fields or values");}return document(lifecycle.revision(actor,id,number));}
    @PostMapping("/v1/publication-management/documents/{id}/withdraw") public Map<String,Object> withdraw(@PathVariable String id,HttpServletRequest r){String actor=operator(r,true);var n=body(r,"expected_revision","reason");return document(lifecycle.withdraw(actor,id,integer(n.get("expected_revision"),1,Integer.MAX_VALUE),string(n.get("reason"))));}
    @ExceptionHandler(DraftFailure.class) ResponseEntity<Map<String,String>> failure(DraftFailure e,HttpServletRequest request){
        boolean management=request.getRequestURI().startsWith("/v1/publication-management/");String detail=e.getMessage();
        if(management){detail=switch(e.status()){case 409->"Revision or withdrawal changed; reload history";case 422->"Use a valid document, current approved revision, and nonempty reason";case 503->"Publication management unavailable";default->detail;};}
        else if(e.status()==503&&(detail.equals("Workflow store unavailable")||detail.equals("Draft store unavailable")))detail="Review service unavailable";
        else if(e.status()==422)detail="Complete valid metadata or supply a nonempty decision comment";
        return ResponseEntity.status(e.status()).body(Map.of("detail",detail));
    }
    @ExceptionHandler(AuthFailure.class) ResponseEntity<Map<String,String>> authFailure(AuthFailure e){var response=ResponseEntity.status(e.status());if(e.status()==401&&auth.mode().equals("demo"))response.header("WWW-Authenticate","Bearer");return response.body(Map.of("detail",e.getMessage()));}
}
