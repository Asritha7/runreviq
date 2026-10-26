# Reviewed evidence profiles

An optional `evidence_profile` belongs to a saved draft metadata revision. It describes exact source ranges as `procedure`, `prohibition`, `historical` or `monitoring`, and optionally supplies a document-wide HTTP scope. Authors propose these values; the independent reviewer approves the saved snapshot; the publisher verifies and publishes that exact snapshot. Extraction never grants authority.

```json
{"schema_version":1,"http_scope":{"mode":"only","codes":[401]},"units":[{"role":"procedure","start_line":3,"end_line":5}]}
```

`http_scope: null` means unclassified, not general applicability. `{"mode":"general","codes":[]}` explicitly declares general HTTP applicability; `only` requires 1–20 distinct codes from 100 to 599. The scope applies to the entire document, so split documents when procedures have incompatible scopes. Profiles contain at most 20 ordered, non-overlapping ranges, each at most 20 source lines. Range validation rejects headings-only and out-of-source ranges. Human reviewers must still verify meaning and scope against the source.

Profiles are optional. Missing profiles on old revisions remain missing; no roles or restrictions are invented for legacy data. JSONB metadata already supports this additive contract, so no schema migration is required. Published content and metadata remain immutable and checksummed. Revising a classification requires a new reviewed/published revision. Existing permissions continue to govern all historical reads. The revision API returns the stored profile or null.

The draft review API accepts the profile inside `metadata`. Review/submission responses include the saved values. Publication cannot override them in its request. A prohibition unit additionally needs a publisher-supplied, validated `must_not` claim cited within its range. Empty normative claims are permitted only when a profile has evidence units; this allows historical/monitoring evidence without inventing instructions. Legacy publication still requires claims.

The contract, retrieval backend and workspace controls are implemented in three dependent PRs. Tests cover strict schema, unknown scope, invalid ranges, approval requirements, historical immutability, unauthorized reads and missing normative support for a prohibition. No live source revisions, permissions or private configuration are changed by installing this code.

## Retrieval and precision boundaries

`postgres_english_reviewed_v5` applies document HTTP scope before conflict classification and ranking. Requests may supply `http_status` (integer 100–599). Search also recognizes a single HTTP status in the question; explicit context that disagrees with the question returns 422. Unknown status is not treated as general: an otherwise applicable restricted document triggers a clarification. Only authorized documents contribute clarification options.

A nonempty reviewed unit list makes **only those exact ranges** searchable. Unmarked text remains in the authorized revision viewer but is not a search candidate. Empty units retain paragraph retrieval. Role labels do not bypass lexical relevance, permissions, publication status, version, environment or precondition checks. Explicit reviewed HTTP scope replaces legacy numeric text inference for that document; reviewers are responsible for its accuracy. Missing/null scope preserves legacy numeric guards.

The ranker considers up to 20 qualifying passages for a typed bundle and returns at most five. It collapses whitespace-identical passages of the same type (retaining the first citation), reserves a place for each available reviewed type, then fills by relevance. Different words, negation, numbers and punctuation are not merged. This does not establish semantic equivalence or guarantee exhaustive coverage. A prohibition still requires a source-bound `must_not` claim; conflicts still depend on reviewed normalized claims, not an AI judgment.

Each passage includes `evidence_role`, `role_reviewed`, `usage_note` and `applicability`, alongside its exact quote, source lines, revision and hash. Historical context does not confirm today's cause. Monitoring references do not contain live metrics. Unclassified legacy passages are labeled explicitly. Search returns `http_status_context`, `http_status_source` (`provided`, `question`, `unknown`), and an `evidence_summary` when ranked passages were considered.

## Validation evidence

The disposable-database regression `test_reviewed_units_http_scope_and_complementary_bundle` imports five synthetic reviewed documents: two identical procedures, one cited prohibition, one historical passage and one monitoring reference. It verifies a four-passage bundle spanning the four roles, no duplicate procedure, exact source boundaries, 401/503 isolation, unknown-status clarification, question-derived status, role exclusion and exclusion of an unmarked appendix. Additional unit tests cover incompatible HTTP scopes before conflict detection, hidden-scope non-disclosure, contradictory question/context rejection and deduplication without erasing negation.

These are implementation-authored regression cases, **not an independent benchmark or a measured improvement in general retrieval precision**. Frozen evaluation fixtures and prior baseline reports remain unchanged. Better precision for real documents requires independently reviewed source units and a fresh held-out evaluation. No generated answer, semantic entailment verifier or live telemetry connector is introduced.

## Workspace walkthrough

1. Upload/open a private Markdown draft. The source disclosure includes numbered lines.
2. Under **Your metadata edits**, enable **Propose evidence types and HTTP scope**. Choose unclassified, general, or specific HTTP statuses. Add ordered source ranges and select their types; each range previews its exact source quote. Unchecking the profile creates a future saved snapshot without that profile; it never changes a published revision.
3. Save progress, then submit that saved revision. The independent reviewer sees the scope, roles, exact quotes and original source. They must verify accuracy and completeness, including whether unmarked passages matter.
4. The publisher sees the same approved profile, verifies applicability and selects reading roles. Prohibition units require a forbidden claim cited within their source range. A profile with non-prohibition units allows the explicit **No normative claims** option when no instructions need claims; it does not authorize omitting real instructions. Conflicts depend on complete human-authored claims.
5. Search with the incident context. **Observed HTTP status** is optional. Unknown restricted scope produces buttons for known authorized status values; enter another observed status in the context form if appropriate. Results show type, usage limits, applicability and original citations. The revision viewer also shows the stored profile.

The controls use plain text for all source material and retain the existing light/dark theme. Session reset clears profile and quote displays. No new authentication route, role assignment, secret or billable dependency is introduced.

## Legacy corpus regression measurements (2026-10-26)

The existing unclassified corpora retain their frozen source revisions and labels. Rerunning v5 with `evaluate_search --suite realistic --report-only` returned 50/50 case-status/expected-evidence checks, recall@5 100%, and precision@returned-5 **38.28%**. The separate `--suite paraphrase --report-only` returned 7/8 checks, recall@5 75%, and precision **20.83%**. Refusal and citation integrity checks passed in both. These match the v4 values: optional profiles do not improve unclassified documents by themselves. Passing a case does not mean all extra retrieved passages are relevant. The unresolved paraphrase failure and low broader precision remain open effectiveness work.

A publication-to-search integration regression also checks that a historical profile stays unsearchable while draft/pending/approved-but-unpublished, becomes precisely cited after publication, remains hidden from an unauthorized role, and disappears from search after withdrawal. Browser logic checks cover proposal persistence, malformed input rejection, reviewer visibility, typed results, clarification, publication and logout clearing.
