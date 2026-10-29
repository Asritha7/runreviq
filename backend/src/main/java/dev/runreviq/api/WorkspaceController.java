package dev.runreviq.api;

import java.io.IOException;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Public application shell only. Identity and authority remain enforced by API controllers. */
@RestController
@ConditionalOnProperty(name="runreviq.web.enabled", havingValue="true")
public class WorkspaceController {
    private static final Map<String,String> TYPES=Map.of(
        "index.html","text/html;charset=UTF-8", "app.js","text/javascript;charset=UTF-8",
        "theme.js","text/javascript;charset=UTF-8", "app.css","text/css;charset=UTF-8");
    @GetMapping("/") public ResponseEntity<byte[]> index() throws IOException {return asset("index.html");}
    @GetMapping("/assets/{name}") public ResponseEntity<byte[]> asset(@PathVariable String name) throws IOException {
        if(!TYPES.containsKey(name))return ResponseEntity.notFound().build();
        return ResponseEntity.ok().header("Content-Type",TYPES.get(name))
            .header("X-Runreviq-Runtime","java").body(new ClassPathResource("workspace/"+name).getContentAsByteArray());
    }
}
