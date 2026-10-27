package dev.runreviq.auth;

import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuthConfiguration {
    @Bean @ConditionalOnMissingBean Clock authClock() { return Clock.systemUTC(); }
    @Bean @ConditionalOnMissingBean IdentityProvider identityProvider(Clock clock) { return new GoogleIdentityProvider(clock); }
    @Bean AuthService authService(@Value("${runreviq.auth.mode:disabled}") String mode,
            @Value("${runreviq.auth.origin:http://127.0.0.1:8081}") String origin,
            @Value("${runreviq.auth.config-path:}") String path, Clock clock, IdentityProvider provider) {
        if (!mode.equals("disabled") && path.isBlank()) throw new IllegalArgumentException("An explicit private auth config path is required");
        return new AuthService(mode, origin, Path.of(path), clock, provider);
    }
}
