package dev.runreviq.api;

import java.util.Map;
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

/** Migration scaffold: no operational API is exposed before identity parity. */
@Configuration
public class BoundaryConfiguration {
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(a -> a.requestMatchers("/health").permitAll().anyRequest().denyAll())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .build();
    }

    @Bean
    UserDetailsService noUsers() {
        return name -> { throw new UsernameNotFoundException("Identity migration is not enabled"); };
    }

    @RestController
    static class HealthController {
        @GetMapping("/health")
        Map<String, String> health() {
            return Map.of("status", "ok", "runtime", "java", "stage", "domain-migration",
                    "operational_api", "disabled");
        }
    }
}
