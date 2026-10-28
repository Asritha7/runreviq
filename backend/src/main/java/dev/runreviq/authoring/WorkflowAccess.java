package dev.runreviq.authoring;

import dev.runreviq.auth.PrivateConfiguration;
import java.nio.file.*;
import java.util.*;

/** Independent operator assignments, reloaded for every request; reading roles grant no authority. */
public final class WorkflowAccess {
    private final Path reviewers,publishers;
    public WorkflowAccess(Path reviewers,Path publishers){this.reviewers=reviewers;this.publishers=publishers;}
    public boolean allowed(boolean publisher,String mode,String subject) {
        Path path=publisher?publishers:reviewers;
        if(path==null)return false;
        try {
            if(Files.notExists(path,LinkOption.NOFOLLOW_LINKS))return false;
            var n=PrivateConfiguration.readOwnerOnly(path);
            if(!n.isObject()||!Set.copyOf(n.propertyNames()).equals(Set.of("google","demo")))throw new IllegalArgumentException();
            for(String key:List.of("google","demo")) {
                if(!n.get(key).isArray())throw new IllegalArgumentException();
                for(var s:n.get(key))if(!s.isString()||MetadataProposal.strip(s.asString()).isEmpty()||s.asString().codePointCount(0,s.asString().length())>255)throw new IllegalArgumentException();
            }
            if(!Set.of("google","demo").contains(mode))return false;
            for(var s:n.get(mode))if(s.asString().equals(subject))return true;
            return false;
        }catch(Exception e){throw new DraftFailure(503,publisher?"Publisher configuration unavailable":"Reviewer configuration unavailable");}
    }
}
