package dev.runreviq.authoring;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name="runreviq.drafts.enabled",havingValue="true")
public class AuthoringConfiguration {
    @Bean DraftRepository drafts(@Value("${runreviq.drafts.config-path:}") String path) {
        if(path.isBlank())throw new IllegalArgumentException("An explicit private draft config path is required");
        return new DraftRepository(new DraftConnections(Path.of(path)));
    }
}
