# External synthetic scenario baseline — 2026-10-26

The user supplied ten proposed independently authored scenarios. We executed all ten against isolated source corpora using the unchanged v3 backend. This is a backend evidence/authorization assessment, not a browser login test or generated-answer evaluation. See [fixture assumptions and provenance](../datasets/external-validation/README.md) and [raw output](../datasets/external-validation/baseline-v3.json).

## Findings

| Scenario | Observed behavior | Assessment against the requested outcome |
| --- | --- | --- |
| 1: Version-specific guidance | Applicability selects RB-101 and excludes RB-102; question search returns insufficient evidence. | Partial: version filtering works, but the relevant passage is missed. The question requires literal `401`; RB-101 describes authentication failures without that number, so the exact-term gate rejects it. |
| 2: Genuine contradiction | Returns conflict, no recommendation, opposing structured rules and exact citations to RB-201/RB-202. | Meets the structured-conflict expectation. Claim normalization was supplied by the adapter; prose contradiction discovery was not tested. |
| 3: Unknown key rotation | Returns `needs_context_or_review`, asks whether rotation is enabled, and withholds recommendations. | Meets the clarification alternative. No value is silently assumed. |
| 4: Superseded revision | Applicability selects current RB-401; revision reader shows revision 1 superseded and revision 2 active. Search returns insufficient evidence. | Partial: revision lifecycle is correct, but the question does not retrieve revision 2 or provide the requested comparison. |
| 5: Compatible diagnostics | Selector accepts both sources with no conflict; search returns insufficient evidence. | Partial: no false conflict, but neither the requested evidence nor an explanation of compatibility is returned. |
| 6: Current health | Search returns insufficient evidence and no invented metrics. | Partial: it withholds, but does not cite RB-601 or specifically explain the lack of live telemetry. No live monitoring connector exists. |
| 7: Environment isolation | Excludes staging RB-701 and returns the exact production policy POL-702. | Correct evidence selection. It does not explicitly explain why the staging source was excluded. |
| 8: Signature verification prohibition | Applicability allows both sources; search returns insufficient evidence, missing SEC-802. | Unmet: no prohibited action is recommended, but failing to surface the explicit prohibition is an important usefulness gap. |
| 9: Historical cause versus current diagnosis | Returns INC-901's historical incident only; RB-902 is absent. The standard message says excerpts are not an answer or proof of cause. | Partial: no generated diagnosis, but the necessary verification guidance is missing. The exact `401` gate excludes RB-902, which does not contain that number. |
| 10: Access boundary | Platform search returns no restricted content; restricted revision read is denied. Security can read the revision directly but search misses it. After document ACL revocation, security search and revision reads disclose no restricted source. | Access enforcement works in these checks; authorized retrieval fails. Stored-answer invalidation/regeneration cannot be tested because there is no generated-answer store. Previously delivered text cannot be retracted. |

All returned passage/conflict quotes matched their stored authorized current sources. This does not prove semantic claim support or count absent quotes as successfully cited answers. S02 had two conflict quotes; S07 and S09 had one retrieved passage each. The other main searches returned no quotations.

## What this changes in the development plan

The foundation handles the tested access, context, revision and structured-conflict boundaries. Search misses too much relevant guidance to call it operationally effective. The highest-priority retrieval experiment is to revise document-wide numeric gating: requiring a question's HTTP code in every supporting source excludes legitimate diagnostic guidance. The same change must retain existing safeguards against recommending a 401-only procedure for a 503 incident; simply removing the gate would be an unsafe shortcut.

Then evaluate retrieval of explicit prohibitions and short sources under long questions, and decide how to represent procedure evidence separately from claims about live health or confirmed causes. Use this frozen set to compare both misses and irrelevant results, preserve existing negative regressions, and collect new independently reviewed questions before claiming generalization. No tuning was included in this baseline change.

The interface remains an excerpt workspace. It does not generate the nuanced explanations requested in several scenarios. Adding answer generation before fixing evidence selection would risk turning missing context or a historical precedent into a confident unsupported answer.
