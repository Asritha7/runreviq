# Reviewed evidence profiles

An optional `evidence_profile` belongs to a saved draft metadata revision. It describes exact source ranges as `procedure`, `prohibition`, `historical` or `monitoring`, and optionally supplies a document-wide HTTP scope. Authors propose these values; the independent reviewer approves the saved snapshot; the publisher verifies and publishes that exact snapshot. Extraction never grants authority.

```json
{"schema_version":1,"http_scope":{"mode":"only","codes":[401]},"units":[{"role":"procedure","start_line":3,"end_line":5}]}
```

`http_scope: null` means unclassified, not general applicability. `{"mode":"general","codes":[]}` explicitly declares general HTTP applicability; `only` requires 1–20 distinct codes from 100 to 599. The scope applies to the entire document, so split documents when procedures have incompatible scopes. Profiles contain at most 20 ordered, non-overlapping ranges, each at most 20 source lines. Range validation rejects headings-only and out-of-source ranges. Human reviewers must still verify meaning and scope against the source.

Profiles are optional. Missing profiles on old revisions remain missing; no roles or restrictions are invented for legacy data. JSONB metadata already supports this additive contract, so no schema migration is required. Published content and metadata remain immutable and checksummed. Revising a classification requires a new reviewed/published revision. Existing permissions continue to govern all historical reads. The revision API returns the stored profile or null.

The draft review API accepts the profile inside `metadata`. Review/submission responses include the saved values. Publication cannot override them in its request. A prohibition unit additionally needs a publisher-supplied, validated `must_not` claim cited within its range. Empty normative claims are permitted only when a profile has evidence units; this allows historical/monitoring evidence without inventing instructions. Legacy publication still requires claims.

Stage 1 establishes this backend contract. Retrieval and workspace controls are delivered in subsequent dependent PRs. Tests cover strict schema, unknown scope, invalid ranges, approval requirements, historical immutability, unauthorized reads and missing normative support for a prohibition. No live source revisions, permissions or private configuration are changed by installing this code.
