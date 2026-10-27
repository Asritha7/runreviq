package dev.runreviq.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Deterministic selection only. No database, authentication, ranking or generation. */
public final class ApplicabilitySelector {
    private static final Map<String, String> MESSAGES = Map.of(
        "selected", "Found approved guidance applicable to the supplied context.",
        "conflict", "Applicable documents disagree. No recommendation is available until the conflict is reviewed.",
        "needs_context_or_review", "More context or metadata review is required before guidance can be recommended.",
        "insufficient_evidence", "Could not find enough approved guidance you can access for the supplied context.");

    public Map<String, Object> select(List<EvidenceDocument> documents, SelectionContext context) {
        SoftwareVersion requested = SoftwareVersion.parse(context.softwareVersion());
        List<EvidenceDocument> matches = new ArrayList<>();
        List<String> uncertain = new ArrayList<>();
        List<Map<String, Object>> questions = new ArrayList<>(), reviews = new ArrayList<>();
        TreeSet<Integer> unknownHttpCodes = new TreeSet<>();
        Map<String, Object> httpQuestion = null;
        for (EvidenceDocument doc : documents) {
            // Authority is checked before any document-derived explanation.
            if (!doc.allowedRoles().contains(context.role())) continue;
            if (!"approved".equals(doc.status()) || !Objects.equals(doc.service(), context.service())) continue;
            if (doc.environment() != null && !doc.environment().equals(context.environment())) continue;
            if (doc.versionMin() != null && requested.compareTo(SoftwareVersion.parse(doc.versionMin())) < 0) continue;
            if (doc.versionMaxExclusive() != null && requested.compareTo(SoftwareVersion.parse(doc.versionMaxExclusive())) >= 0) continue;
            EvidenceProfile.HttpScope scope = doc.profile() == null ? null : doc.profile().httpScope();
            boolean restricted = scope != null && scope.mode().equals("only");
            if (restricted && context.httpStatus() != null && !scope.codes().contains(context.httpStatus())) continue;
            boolean missingHttp = restricted && context.httpStatus() == null;
            Boolean rotation = doc.keyRotationEnabled();
            if (rotation != null && context.keyRotationEnabled() != null && !rotation.equals(context.keyRotationEnabled())) continue;
            if (missingHttp || !doc.metadataVerified() || doc.environment() == null || doc.versionMin() == null
                    || doc.versionMaxExclusive() == null || rotation != null && context.keyRotationEnabled() == null) {
                uncertain.add(doc.id());
                List<String> reasons = new ArrayList<>();
                if (!doc.metadataVerified()) reasons.add("metadata_unverified");
                if (doc.environment() == null) reasons.add("missing_environment");
                if (doc.versionMin() == null) reasons.add("missing_version_min");
                if (doc.versionMaxExclusive() == null) reasons.add("missing_version_max_exclusive");
                if (!reasons.isEmpty()) { var ref = reference(doc, true); ref.put("reasons", reasons); reviews.add(ref); }
                if (missingHttp) {
                    if (httpQuestion == null) {
                        httpQuestion = map("field", "http_status", "question", "Which HTTP status occurred in the incident?", "allowed_answers", List.of());
                        questions.add(httpQuestion);
                    }
                    unknownHttpCodes.addAll(scope.codes());
                    httpQuestion.put("allowed_answers", List.copyOf(unknownHttpCodes));
                }
                if (rotation != null && context.keyRotationEnabled() == null
                        && questions.stream().noneMatch(q -> q.get("field").equals("key_rotation_enabled"))) {
                    questions.add(map("field", "key_rotation_enabled", "question", "Is key rotation enabled in the affected service?", "allowed_answers", List.of(true, false)));
                }
                continue;
            }
            matches.add(doc);
        }
        List<List<String>> conflicts = new ArrayList<>();
        List<Map<String, Object>> details = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            var left = matches.get(i);
            for (var right : matches.subList(i + 1, matches.size())) {
                TreeSet<String> opposing = new TreeSet<>();
                for (var a : left.claims()) for (var b : right.claims()) {
                    if (a.action().equals(b.action()) && (a.rule().equals("must") && b.rule().equals("must_not")
                            || a.rule().equals("must_not") && b.rule().equals("must"))) opposing.add(a.action());
                }
                if (!opposing.isEmpty()) conflicts.add(List.of(left.id(), right.id()));
                for (String action : opposing) {
                    List<Map<String, Object>> refs = new ArrayList<>();
                    for (var doc : List.of(left, right)) {
                        var ref = reference(doc, true);
                        TreeSet<String> rules = new TreeSet<>();
                        List<Map<String, Object>> citations = new ArrayList<>();
                        boolean complete = true;
                        for (var claim : doc.claims()) if (claim.action().equals(action)) {
                            rules.add(claim.rule());
                            if (claim.citation() == null) complete = false;
                            else {
                                var cite = new LinkedHashMap<>(claim.citation());
                                cite.putAll(map("rule", claim.rule(), "document_id", doc.id(), "revision", doc.revision()));
                                citations.add(cite);
                            }
                        }
                        ref.putAll(map("rules", List.copyOf(rules), "citations", citations, "citation_status", complete ? "available" : "incomplete"));
                        refs.add(ref);
                    }
                    details.add(map("action", action, "basis", "catalog_rules", "documents", refs,
                            "explanation", "Applicable documents require and forbid the same action: " + action + "."));
                }
            }
        }
        String status = !conflicts.isEmpty() ? "conflict" : !uncertain.isEmpty() ? "needs_context_or_review"
                : !matches.isEmpty() ? "selected" : "insufficient_evidence";
        List<String> steps = new ArrayList<>();
        if (!conflicts.isEmpty()) steps.add("Ask an authorized document owner to resolve the opposing instructions.");
        if (!questions.isEmpty()) steps.add("Answer the follow-up question and submit the request again.");
        if (!reviews.isEmpty()) steps.add("Ask an authorized reviewer to verify the listed document metadata.");
        if (status.equals("selected")) steps.add("Read the supporting documents and follow their approval requirements before acting.");
        if (status.equals("insufficient_evidence")) steps.add("Check the service, version, environment and prerequisites, or request an approved source.");
        var ids = matches.stream().map(EvidenceDocument::id).toList();
        return map("status", status, "message", MESSAGES.get(status), "next_steps", steps, "questions", questions,
                "review_items", reviews, "conflict_details", details, "applicable_documents", ids,
                "uncertain_documents", uncertain, "conflicts", conflicts,
                "recommended_documents", status.equals("selected") ? ids : List.of(),
                "evidence", matches.stream().map(d -> reference(d, false)).toList());
    }

    private static Map<String, Object> reference(EvidenceDocument doc, boolean title) {
        var ref = map("document_id", doc.id(), "revision", doc.revision(), "path", doc.path());
        if (title) ref.put("title", doc.title());
        return ref;
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
}
