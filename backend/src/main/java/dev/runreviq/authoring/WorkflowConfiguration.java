package dev.runreviq.authoring;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name="runreviq.workflows.enabled",havingValue="true")
public class WorkflowConfiguration {
    private static Path required(String p){if(p.isBlank())throw new IllegalArgumentException("Explicit private workflow configuration paths are required");return Path.of(p);}
    @Bean WorkflowAccess workflowAccess(@Value("${runreviq.workflows.reviewers-path:}") String reviewers,@Value("${runreviq.workflows.publishers-path:}") String publishers){return new WorkflowAccess(reviewers.isBlank()?null:Path.of(reviewers),publishers.isBlank()?null:Path.of(publishers));}
    @Bean SubmissionRepository submissions(@Value("${runreviq.drafts.enabled:false}") boolean draftsEnabled,
            @Value("${runreviq.drafts.config-path:}") String drafts,@Value("${runreviq.workflows.reviewer-config-path:}") String reviewers){
        if(!draftsEnabled)throw new IllegalArgumentException("Workflows require explicit draft enablement");
        return new SubmissionRepository(new DraftConnections(required(drafts)),new WorkflowConnections(required(reviewers),WorkflowConnections.Kind.REVIEWER));
    }
    @Bean PublicationRepository publications(@Value("${runreviq.workflows.publisher-config-path:}") String publishers){return new PublicationRepository(new WorkflowConnections(required(publishers),WorkflowConnections.Kind.PUBLISHER));}
    @Bean LifecycleRepository lifecycle(PublicationRepository publications){return new LifecycleRepository(publications.publishers);}
}
