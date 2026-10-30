"""Conservative evidence metrics; unknown semantic judgments are never counted as correct."""
import math
import statistics


def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values)*fraction)-1)] if values else None


def summarize(rows):
    required=sum(r['required_count'] for r in rows)
    returned=sum(r['returned_count'] for r in rows)
    matched=sum(r['matched_count'] for r in rows)
    eligible=[r for r in rows if r['eligible_required_evidence']]
    samples=[n for r in rows for n in r['repository_latency_ms']]
    return dict(cases=len(rows),required_passages=required,returned_passages=returned,matched_passages=matched,
        evidence_recall=matched/required if required else None,
        strict_returned_precision=matched/returned if returned else None,
        labeled_wrong_source_passages=sum(r['labeled_wrong_source_count'] for r in rows),
        labeled_wrong_source_case_rate=sum(r['labeled_wrong_source_count']>0 for r in rows)/len(rows) if rows else None,
        unadjudicated_extra_passages=sum(r['unadjudicated_extra_count'] for r in rows),
        eligible_required_cases=len(eligible),
        inappropriate_evidence_abstentions=sum(r['inappropriate_evidence_abstention'] for r in eligible),
        inappropriate_evidence_abstention_rate=sum(r['inappropriate_evidence_abstention'] for r in eligible)/len(eligible) if eligible else None,
        repository_latency_ms=dict(p50=statistics.median(samples) if samples else None,p95=percentile(samples,.95),samples=len(samples)),
        semantic_wrong_procedure_rate=None,
        limitation='Forbidden-source labels are a lower bound for wrong-procedure risk; unlabeled extras need adjudication. No answer correctness is graded.')
