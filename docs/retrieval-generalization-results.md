# Retrieval generalization: development experiment

## Outcome

Relaxing lexical coverage admission recovers six of the seven known eligible-evidence
misses. It also lowers strict returned precision and admits a wrong procedure in a
synthetic control. **Do not promote this arm.** It is evidence that ratio gates suppress
useful candidates, not proof that one shared term establishes relevance.

The original 13-case external pack is now development/regression data. Questions, gold
labels, source passages and adapter assumptions are unchanged. The source/gold fingerprint
remains `33a3d6b3a1a55162a74551c018f7c8394510bc08783d24679709a816de04efb2`.
Private raw reports and earlier PR #36–#39 artifacts remain outside Git. The optional
embedding hybrid was **not run**; no model, vectors or cloud dependencies were introduced.

## Measured evidence delivery

| Metric | reviewed-v5 | focused-v6 | relaxed-lexical-v1 |
| --- | ---: | ---: | ---: |
| Required passages retrieved | 4 / 14 | 4 / 14 | 11 / 14 |
| Evidence recall | 28.57% | 28.57% | 78.57% |
| Strict returned precision | 4 / 4 (100%) | 4 / 4 (100%) | 11 / 12 (91.67%) |
| Inappropriate evidence abstention | 7 / 10 (70%) | 7 / 10 (70%) | 1 / 10 (10%) |
| Supplied forbidden-source hits | 0 | 0 | 0 |
| Unadjudicated extra passages | 0 | 0 | 1 |
| Full semantic wrong-procedure rate | Not adjudicated | Not adjudicated | Not adjudicated |

Recall includes all 14 required labels, including one blocked by metadata. The abstention
denominator includes only ten cases with required, applicable evidence and no policy block.
These are passage-delivery metrics, not answer accuracy. F07 still lacks prose contradiction
detection, and F06 does not establish live operational state.

The additional F10P passage is authorized general guidance, not the requested restricted
finding. It requires independent relevance adjudication. Zero forbidden-source hits is
only a lower-bound proxy for wrong-procedure risk; it does not prove semantic safety.

## Per-case results and first exclusion stages

Both frozen baseline arms have the same outcomes below. The raw private report retains
each missing passage's actual decisive trace event rather than inferring uncomputed scores.

| Case | Baseline boundary/outcome | Relaxed arm |
| --- | --- | --- |
| F01 | Metadata/context review | Same block; not a ranking failure |
| F02 | Document coverage | Required passage retrieved |
| F03 | Document coverage | Required prohibition retrieved |
| F04 | Global excluded-sentence term veto, two passages | Same veto, two misses |
| F05 | Document coverage | Required passage retrieved |
| F06 | Document coverage | Monitoring procedure retrieved; no live values |
| F07 | Both passages retrieved, prose conflict unrecognized | Same unsupported semantic capability |
| F08 | Document coverage, two passages | Both required passages retrieved |
| F09 | Metadata/context review | Same block; not a ranking failure |
| F10P | No required passages, no restricted disclosure | One authorized extra; relevance unadjudicated |
| F10S | Required authorized evidence retrieved | Unchanged |
| F11 | Required current evidence retrieved | Unchanged |
| F12 | Document coverage | Required passage retrieved |

The dominant mechanism was document-coverage admission: six questions and seven required
passages. F04 is a different lexical scope failure. PR #39 already showed that bypassing
its global veto alone exposes a subsequent coverage failure. This experiment deliberately
does not combine those two changes or reinterpret unsupported metadata to inflate recall.

## Negative controls and latency

Four public synthetic mechanism tests retain the experiment's limitations: verbose
low-overlap retrieval succeeds, zero-overlap retrieval still abstains, an unmatched exact
identifier still blocks retrieval, and a generic shared word retrieves an unrelated
procedure. That last result is an explicit false positive, separate from the external
pack's metrics. These implementation-authored controls are not independent validation.

The initial development replay measured repository p50/p95 of 9.17/14.34 ms for reviewed-v5,
7.77/8.99 ms for focused-v6 and 7.93/11.54 ms for relaxed admission. Each arm has 65 timed
samples (five per case after a warmup). These pilot measurements came from an uncommitted
development revision; the final replay records its own exact code revision and samples.
Connection setup, RLS and snapshot loading are included; HTTP/authentication and tracing
are excluded. Fixed arm order, small samples and local host load preclude a speedup or
production SLA claim. Full host/toolchain provenance is in the final private report.

## Validation and next decision

Validation covers the full Python suite (239 discovered, 237 passed and two opt-in Java
tests skipped there), both opt-in Java evaluation integrations in a separate 14-test run,
Maven verification and database-backed Java search tests in both existing modes. The
39-case/arm external replay compares both baseline responses exactly with frozen outputs,
checks traced/untraced and timed-response parity, verifies authorized citations and
deterministic traces, and verifies unchanged source/publication state and historical hashes.
The experimental mode is rejected by live configuration. Neither runtime data nor the
default search mode changes.

The smallest justified next experiment is to retain broad admission but test an explicit
relevance/abstention decision against independently adjudicated hard negatives, including
the authorized F10P extra. Do not choose thresholds from these seven questions and call
that generalization. Freeze a separate unseen partition before further tuning. A pinned
local hybrid is a possible later arm for zero-overlap terminology, subject to the model,
eligibility and reproducibility requirements in the [technical design](retrieval-generalization-design.md).
