package dev.runreviq.persistence;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name="runreviq.database.enabled",havingValue="true")
public class PersistenceConfiguration {
    @Bean ReaderConnections readers(@Value("${runreviq.database.config-path:}") String path) {
        if(path.isBlank()) throw new IllegalArgumentException("An explicit private reader config path is required");
        return new ReaderConnections(Path.of(path));
    }
    @Bean EvidenceRepository evidenceRepository(ReaderConnections readers,@Value("${runreviq.search.mode:reviewed-v5}") String mode) {return new EvidenceRepository(readers,mode);}
}
