# Separate hard-negative and reserved-question evaluation protocol

## Decision and current state

The 7 November provisional review is completed diagnostic work: 11 relevant judgments
and one task mismatch, authored by AI with prior involvement. It is not a release gate.
F10P's wrong-procedure versus irrelevant/fallback interpretation remains a possible human
label dispute. Preserve both the rationale and any later disagreement as separate artifacts.
Issue #42 stays open until authorized independent human review is completed.

This milestone adds a private protocol freeze/verify tool and a planning template. It
creates no new incident questions, human judgments, semantic results or independent
validation claims. No new externally authored set has been supplied. The existing three
methods, working Python runtime, default configuration and Java cutover decision stay fixed.

## Collection and separation

An external author should write questions without seeing earlier questions, provisional
labels or retrieval outputs. A separate authorized source reviewer should check expected
evidence, complete negative labels and metadata before retrieval. Record author/reviewer
identities or stable pseudonyms, dates, prior involvement, exposure and disagreements in
private provenance records. The protocol declaration references those records; it is not
proof of independence. Do not send security evidence to a platform-only reviewer.

Keep an author-held reserved partition away from developers and AI assistants before its
first run. An operator may validate it using the offline tool without printing its contents
into this chat. After developers inspect it or use its output for tuning, mark its provenance
accordingly in a **new** protocol; retain the original freeze as historical evidence. The
script cannot detect unreported exposure or prove semantic novelty. A stored reserved claim
does not remain genuinely unseen merely because no file changed.

Use a new private directory, separate from the October corpus and provisional review. Keep
Markdown plus proposed metadata in `sources/`; keep questions, gold and provenance outside
that directory. Use the existing external-pack schema accepted by
`scripts/evaluate_external_pack.py`; only its source adapter may feed the disposable database.
Do not ingest acceptance criteria, expected responses, questions, labels or reviewer notes.

## Required coverage design

| Category | Author's task and evidence expectation |
| --- | --- |
| `wrong_procedure` | Ask for one operational task while an otherwise eligible source describes another. Include a positive or explicitly justified abstention counterpart. |
| `shared_keyword_distractor` | Place unrelated instructions alongside useful guidance sharing salient words. Do not make every negative removable by an environment mismatch. |
| `prose_conflict` | Include opposing instructions under the same conditions; retrieve both, but withhold an unsupported winner. Include complementary diagnostics as a non-conflict control. |
| `incomplete_context` | Omit a meaningful prerequisite. Preserve unknowns; require clarification or conditional handling. |
| `useful_prohibition` | Ask whether an action is allowed; required evidence explicitly prohibits it. A relevant prohibition must survive any future mismatch filter. |
| `monitoring_vs_live_state` | Ask for current health/metrics with documentation-only evidence. A monitoring reference may be useful, but must not become a fabricated live reading. |
| `authorization` | Pair role-specific queries and restricted sources. Check excluded identifiers, text, scores and derived findings, plus separate revocation regressions. |
| `applicability` | Include wrong environment/version/HTTP scope, superseded or withdrawn sources and valid alternatives. Preserve deterministic eligibility. |

Each case has one or more coverage tags in the protocol. The tool requires every case to
be tagged and every category to occur. This is a structural minimum, not proof that the
content actually covers the category or that the sample size is statistically sufficient.
An author must inspect the coverage and include multiple independent examples and controls;
one case tagged with every category is not a credible study despite passing structure checks.

## Metrics and criteria fixed before running

Copy [the planning template](evaluation-protocol.example.json) privately. Replace all IDs
and declarations truthfully. The proposed strict engineering gates are recall 1.0, adjudicated
precision lower bound 1.0, task mismatch 0 and inappropriate evidence abstention 0. These
are diagnostic targets, not calibrated production thresholds; current methods may fail.
The operator can choose different rate targets **before** freezing and before examining
new outputs, recording a rationale. Do not lower targets after seeing failures. Safety
violations and unresolved judgments must remain zero for an accepted decision.

Report the following for each method, each category, each role and each case. Do not use an
overall average to hide an unsafe subgroup or suppress failed/unsupported cases.

- Required-evidence recall: exact required coordinates retrieved / all required coordinates.
  Also report eligible-only misses separately; metadata blocks stay visible.
- Strict returned precision: required coordinates / returned coordinates, alongside human
  adjudicated relevance bounds. Strict labels can omit useful optional evidence.
- Task mismatch: human wrong-procedure count / returned passages. Report irrelevant and
  uncertain counts separately. Uncertain labels are not correct labels; unresolved judgment
  means no accepted conclusion. Preserve F10P-style disagreement rather than relabel to pass.
- Inappropriate **evidence** abstention: no passages despite required applicable evidence,
  excluding policy/context blocks. Documentation-only live-state requests still require
  answer abstention even if a monitoring passage is returned.
- Authorization, applicability and citation integrity: zero violations. Missing evidence is
  not a leak; unauthorized identifiers or text are a failure regardless of relevance.
- Expected behavior: inspect contradiction, clarification and live-state outcomes separately.
  The current excerpt API does not implement prose contradiction recognition or live metrics.
  Record unsupported capability and unmet expectation; do not turn successful retrieval into
  a semantic pass. No generated-answer support metric is claimed.
- Latency: use the existing five-sample repository replay protocol, with warmup, host/toolchain
  provenance and per-case samples; no production SLA or speedup claim from fixed-order runs.

All rate denominators and zero-return cases must be reported. Undefined values are unknown,
not passes. Human relevance review, reviewed metadata, current authorization verification and
per-case semantic assessment are necessary for an acceptance decision. The freeze tool never
issues one. Even a passing study does not authorize method promotion or Java cutover.

## Freeze and verify

The new command rejects role-inaccessible, stale, forbidden or out-of-range required labels.
It reuses the existing source adapter's validation without changing its
assumptions. Unsupported prerequisites remain unverified; prose contradictions are not
converted into normalized claims; proposed permissions/publication remain simulated fixture
metadata. A source reviewer must acknowledge these limits before executing the study.

```sh
.venv/bin/python scripts/freeze_evaluation.py freeze \
  --sources /private/new-study/sources \
  --gold /private/new-study/questions_and_gold.json \
  --protocol /private/new-study/protocol.json \
  --known-gold /private/previous-study/questions_and_gold.json \
  --output /private/new-study/freeze.json

.venv/bin/python scripts/freeze_evaluation.py verify \
  --receipt /private/new-study/freeze.json
```

Repeat `--known-gold` for **all** earlier private sets. Built-in repository questions are
also checked by the existing adapter. The receipt pins the built-in JSON/Markdown inventory,
so adding, removing or changing a file used in that overlap check requires a new freeze. Comparison normalizes case and whitespace only;
paraphrase/semantic duplicates require human checking. AI/mixed/unknown authorship, known
exposure or exact overlap yield `development_only`. Human authorship with no declared
exposure and no detected overlap yields only `reserved_claim_unverified`. Independence
and semantic novelty are never certified.

The owner-only, exclusive-created receipt records input and source hashes, criteria,
coverage, UTC creation time, a receipt-content checksum, declared provenance, exact-overlap
hashes, adapter limitations and fingerprints
of runtime/evaluator code, Java test driver, fixtures and build configuration. Its output
must be outside the source directory. It refuses overwrites, input symlinks and changed
bindings. `verify` checks inputs and implementation before and after execution. Keep the
receipt privately with a separately recorded SHA-256; hashes detect accidental change, not
malicious rewriting of both a receipt and its inputs. The command is an operator tool, not
an access-control service. Historical packets do not certify current reviewer access.

## Execution using existing tools, unchanged

1. Commit the evaluator version, confirm provenance and metadata reviews, and freeze the
   private inputs and criteria. Verify the receipt. Do not inspect reserved questions in
   implementation chats before the planned first run.
2. Run `scripts/evaluate_external_pack.py --sources ... --gold ... --output NEW_BASELINE`
   on the new sources/gold. This creates paired baseline outputs in a disposable database.
3. Run `scripts/trace_external_pack.py --sources ... --gold ... --baseline NEW_BASELINE
   --output NEW_TRACE --generalization-experiment`. This compares both exact baseline
   responses and relaxed-lexical-v1 with the same eligibility boundaries. Do not use the old
   October baseline with a new pack. Preserve all negative and unsupported findings.
4. Verify the frozen receipt again. Retain raw traces, reports and timing samples privately.
   If code or input hashes changed, reject the comparison; do not rewrite the old receipt.
5. Use the existing role-specific adjudication workflow on the new trace. Share only approved
   packet/template files, never hidden modes, gold or provisional labels. Have independent
   humans adjudicate without seeing method scores, preserving uncertain/disputed labels.
6. Score each role separately. Join case IDs to the frozen coverage tags for the per-category
   review. Record a private acceptance decision against the predeclared criteria, including
   unavailable metrics and unsupported semantics. This is a documented manual assessment,
   not an implemented automatic acceptance or category-aggregation runner.

No new pack was executed in this milestone. Synthetic regression fixtures verify tooling
only. External authorship, independent human judgments, adequate coverage/sample size,
semantic novelty, current permissions and production usefulness remain unverified.
