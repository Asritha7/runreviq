package dev.runreviq.api;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="runreviq.web.enabled=true")
class WorkspaceTest {
    @LocalServerPort int port;
    HttpResponse<byte[]> request(String path,String method)throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
            .method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    @Test void publicShellIsByteIdenticalToSharedSourceAndHasBrowserSecurityHeaders()throws Exception {
        for(var asset:Map.of("/","index.html","/assets/app.js","app.js","/assets/theme.js","theme.js","/assets/app.css","app.css").entrySet()){
            var response=request(asset.getKey(),"GET");assertEquals(200,response.statusCode());
            assertArrayEquals(Files.readAllBytes(Path.of("../runreviq/web",asset.getValue())),response.body());
            assertEquals("no-store",response.headers().firstValue("cache-control").orElseThrow());
            assertEquals("nosniff",response.headers().firstValue("x-content-type-options").orElseThrow());
            assertEquals("java",response.headers().firstValue("x-runreviq-runtime").orElseThrow());
            var policy=response.headers().firstValue("content-security-policy").orElseThrow();
            assertTrue(policy.contains("script-src 'self'"));assertTrue(policy.contains("frame-ancestors 'none'"));
            assertFalse(policy.contains("unsafe-inline"));assertTrue(response.headers().allValues("set-cookie").isEmpty());
            assertTrue(response.headers().allValues("access-control-allow-origin").isEmpty());
        }
    }
    @Test void enablingShellDoesNotExposeOtherResourcesOrEnableApis()throws Exception {
        for(String path:new String[]{"/assets/index.html","/assets/application.properties","/workspace/index.html","/assets/unknown.js","/actuator/env","/v1/drafts","/v1/search"})
            assertEquals(403,request(path,"GET").statusCode(),path);
        for(String path:new String[]{"/","/assets/app.js","/v1/search"})assertEquals(403,request(path,"POST").statusCode(),path);
    }
}
