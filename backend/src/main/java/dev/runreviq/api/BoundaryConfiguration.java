package dev.runreviq.api;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Identity endpoints plus explicitly enabled, authenticated evidence reads. */
@Configuration
public class BoundaryConfiguration {
    @Bean
    SecurityFilterChain security(HttpSecurity http, @Value("${runreviq.database.enabled:false}") boolean databaseEnabled,
            @Value("${runreviq.drafts.enabled:false}") boolean draftsEnabled) throws Exception {
        var csrfExceptions=new java.util.ArrayList<String>();csrfExceptions.add("/auth/logout");
        if(databaseEnabled)csrfExceptions.add("/v1/select");
        if(draftsEnabled){csrfExceptions.add("/v1/drafts");csrfExceptions.add("/v1/drafts/*/review");}
        return http.authorizeHttpRequests(a -> {
                if (draftsEnabled) {
                    a.requestMatchers(HttpMethod.GET, "/v1/drafts", "/v1/drafts/*").permitAll();
                    a.requestMatchers(HttpMethod.POST, "/v1/drafts", "/v1/drafts/*/review").permitAll();
                }
                if (databaseEnabled) {
                    a.requestMatchers(HttpMethod.POST, "/v1/select").permitAll();
                    a.requestMatchers(HttpMethod.GET, "/v1/documents/*/revisions/*").permitAll();
                }
                a
                .requestMatchers(HttpMethod.GET, "/health", "/auth/config", "/auth/google/login", "/auth/google/callback", "/auth/session", "/v1/me").permitAll()
                .requestMatchers(HttpMethod.POST, "/auth/logout").permitAll().anyRequest().denyAll(); })
                // Logout and all enabled POST controllers enforce the strict Origin/header guard for Google sessions.
                // Spring's CSRF checks remain enabled everywhere else, with no CORS grants.
                .csrf(c -> c.ignoringRequestMatchers(csrfExceptions.toArray(String[]::new)))
                .requestCache(AbstractHttpConfigurer::disable)
                .addFilterBefore(new LoopbackBoundary(), AuthorizationFilter.class)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .headers(h -> h.referrerPolicy(r -> r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)).contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .build();
    }

    static class LoopbackBoundary extends OncePerRequestFilter {
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            response.setHeader("Cache-Control", "no-store");
            // Trust neither forwarded headers nor a DNS-rebinding Host, even on loopback.
            if (!"127.0.0.1".equals(request.getServerName())) {
                response.setStatus(400); response.setContentType("application/json");
                response.getWriter().write("{\"detail\":\"Invalid host\"}"); return;
            }
            chain.doFilter(request,response);
        }
    }

    @Bean
    UserDetailsService noUsers() {
        return name -> { throw new UsernameNotFoundException("Password login is disabled"); };
    }

    @RestController
    static class HealthController {
        @GetMapping("/health")
        Map<String, String> health(@Value("${runreviq.database.enabled:false}") boolean enabled) {
            return Map.of("status", "ok", "runtime", "java", "stage", "persistence-migration",
                    "operational_api", enabled ? "evidence-reads" : "disabled");
        }
    }
}
