package dev.runreviq.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }
    @GetMapping("/auth/config") public Map<String,String> config() { return auth.configuration(); }
    @GetMapping("/auth/google/login") public ResponseEntity<Void> login() { return auth.start(); }
    @GetMapping("/auth/google/callback") public ResponseEntity<Void> callback(HttpServletRequest request) { return auth.callback(request); }
    @GetMapping("/auth/session") public AuthService.Principal session(HttpServletRequest request) {
        if (!auth.mode().equals("google")) throw new AuthFailure(403,"Google login is disabled");
        return auth.principal(request,false);
    }
    @GetMapping("/v1/me") public AuthService.Principal me(HttpServletRequest request) { return auth.principal(request,true); }
    @PostMapping("/auth/logout") public ResponseEntity<Void> logout(HttpServletRequest request) { return auth.logout(request); }
    @ExceptionHandler(AuthFailure.class) public ResponseEntity<Map<String,String>> failed(AuthFailure failure) {
        var response = ResponseEntity.status(failure.status());
        if (failure.status() == 401 && auth.mode().equals("demo")) response.header("WWW-Authenticate","Bearer");
        return response.body(Map.of("detail",failure.getMessage()));
    }
}
