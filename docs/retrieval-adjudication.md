# Private returned-evidence adjudication

PR #40 improved development recall but left one extra authorized passage unadjudicated
and demonstrated a wrong-procedure false positive in a synthetic control. Before testing
another relevance threshold, this milestone prepares reviewable labels without changing
retrieval. Independent review is still pending; no new semantic accuracy claim is made.

## Scope and trust boundary

`scripts/adjudicate_retrieval.py` is an offline operator tool. It reads a clean frozen
three-arm generalization report and its hash-matching private gold file. It never accesses
SQL, starts the application, calls a model, or writes to a search corpus. It preserves
all original benchmark files and creates only new outputs with exclusive creation.

This is a trusted local artifact workflow, **not an authentication service**. The operator
must control the inputs and approve the reviewer for the selected role. A `--role` argument
does not grant permissions. The report's authorized snapshot describes the replay time;
it does not establish current access after revocation. Before sharing any packet, recheck
reviewer authorization and retire old packet copies when that authorization changes.
Do not expose this command through an HTTP endpoint or upload its files to the public repo.

The tool selects only cases for one role and only passages returned for those cases.
Each passage must match its replay's authorized document/revision/checksum snapshot and
applicable document list. It projects an explicit allowlist of evidence fields, never raw
trace events, scores, other roles' cases, or inaccessible candidates. The hash binding
prevents accidental stale or edited packet/label combinations; it is not a signature and
cannot authenticate a maliciously rewritten report and manifest.

## Review packet

For each distinct case/passage across the three modes, the reviewer sees:

- The original incident question and operational context.
- Exact quoted evidence, document/revision/line coordinates, evidence role and source hash.
- An opaque item identifier, ordered by its hash rather than retrieval rank.

Search-mode names, memberships, gold outcomes and prior scores are hidden. Identical items
across arms are reviewed once. This reduces method-label bias; it does not guarantee that
a reviewer cannot recognize a previously seen question. All cases remain development data.

Prepare each role separately, using a new directory beneath an existing private parent:

```sh
.venv/bin/python scripts/adjudicate_retrieval.py prepare \
  --report /private/generalization-final/trace-report.json \
  --gold /private/questions_and_gold.json \
  --role platform \
  --output /private/review-platform
```

Files are owner-only (0600) and the created directory is 0700. Share **only** `packet.json`
and `labels-template.json` with an authorized reviewer. Keep `operator-manifest.json`,
the raw report and gold file with the operator. The manifest binds the original bytes,
packet content and hidden mode membership. Output directories/files cannot be overwritten.

The reviewer saves a separate `labels-completed.json`, fills `reviewer` and
`independence_attestation` with truthful text, and supplies a label and rationale for every
item. Do not edit the packet, item identifiers or packet hash. Label meanings:

| Label | Meaning |
| --- | --- |
| `relevant` | Supports the requested operational task, including a pertinent prohibition, diagnostic prerequisite or monitoring procedure |
| `irrelevant` | Does not address the question; not necessarily an operational procedure |
| `wrong_procedure` | Presents an operational procedure mismatched to the requested task |
| `uncertain` | Insufficient context or disputed interpretation prevents a defensible decision |

A historical hypothesis is not proof of current cause. A monitoring instruction is not
live telemetry. These labels judge source relevance, not generated-answer correctness.
Do not infer an action is authorized merely because a diagnostic passage mentions it.

## Scoring and limitations

```sh
.venv/bin/python scripts/adjudicate_retrieval.py score \
  --report /private/generalization-final/trace-report.json \
  --gold /private/questions_and_gold.json \
  --packet-directory /private/review-platform \
  --labels /private/review-platform/labels-completed.json \
  --output /private/adjudication-platform.json
```

Scoring reconstructs the packet from frozen inputs and verifies its manifest. Missing,
duplicate, unknown or pending judgments, missing rationales, changed inputs and stale
packet bindings reject the report. Reviewer identity and an independence attestation are
required, but independence is explicitly recorded as **attested, not verified**.

The private result reports each arm's returned-passage counts by label, precision among
decisive judgments, and relevance bounds with uncertain items counted as either nonrelevant
or relevant. The observed wrong-procedure rate is a lower bound while uncertainty remains.
No-return arms have null rate values. No uncertain item is silently marked correct.
The report includes hashes of the original report, gold, packet and completed labels.

This workflow only reviews returned evidence. It cannot measure missing evidence recall,
justify abstention on unanswered cases, establish current authorization, or prove
production readiness. Existing PR #40 recall and first-failure reports remain separate and
unchanged. Do not combine role-specific reports as independent duplicate questions.

## Executed checks and next milestone

Synthetic tests cover role isolation, mode blinding, cross-arm deduplication, restrictive
permissions, snapshot/applicability checks, incomplete arms, changed packet/report/manifest,
pending and malformed judgments, uncertainty, wrong-procedure accounting, and exclusive
writes including symlink targets. Synthetic reviewers are test fixtures, not external evidence.

Private packets can now be prepared while awaiting a reviewer. Once real reviews exist,
preserve disputed labels and rationales, then freeze a separate set of hard negatives and
unseen questions before choosing a relevance/abstention rule. No new threshold, embedding,
LLM, answer generation, runtime mode or automatic promotion is introduced by this milestone.
