# Java HTTP retrieval quality evaluation

The Java search migration has passed frozen Python-response comparisons. This stage
adds a separate evaluator that scores actual Java HTTP results against labeled source
passages. It does not change ranking, tune against answers, connect the live UI to Java,
or claim that a new language improves retrieval quality.

## Run it locally

PostgreSQL and the existing restricted reader accounts must already be initialized;
Java 25 or later and the repository's Maven wrapper are required. No paid API is used.

```sh
# Validate labels and fingerprint without starting Java or contacting PostgreSQL.
.venv/bin/python -m runreviq.evaluate_java --suite realistic --validate-only

# Build a fresh Java jar, run in isolation, and print the complete quality report.
.venv/bin/python -m runreviq.evaluate_java --suite realistic --report-only

# Strict evaluation: nonzero exit if any expected status/evidence set differs.
.venv/bin/python -m runreviq.evaluate_java --suite regression

# New external questions against the selected frozen corpus.
.venv/bin/python -m runreviq.evaluate_java --suite realistic \
  --questions .local/evaluation/questions.json --validate-only
.venv/bin/python -m runreviq.evaluate_java --suite realistic \
  --questions .local/evaluation/questions.json --report-only
```

External files use the [existing question/label schema](independent-evaluation.md).
They may reference exact passages in the selected `regression`, `realistic`, `validation`
or `paraphrase` corpus. Arbitrary new corpora and the separate scenario-batch evaluator
are not added in this stage. Labels must identify accessible, applicable passages;
invalid schemas, duplicate labels and conflicting HTTP context fail before any build
or database creation. External provenance is recorded as `not_verified`: a file path
alone cannot establish independent authorship or that questions were held out.

Reports contain questions and returned source passages. Keep reports for private
question sets in `.local/`; the committed reports below contain only existing fictional
public benchmark material. Expected labels never enter the retrieval request or source tables.

## Isolation and cleanup

The command builds the current Java source, creates a uniquely named
`opslens_java_eval_<uuid>` database, and imports only the chosen source corpus. It
verifies the ingested corpus against the preflight fingerprint and copies the built
jar into its private temporary directory before starting its own Java process on an
automatically allocated loopback port. It never connects to an existing app URL or
reserves ports 8000/8081.

Java gets temporary owner-only demo tokens and restricted reader configuration. It
receives no administrator credentials, question file, expected labels or evaluator
report. Writer/workflow adapters are explicitly disabled. HTTP calls stay on
`127.0.0.1`, do not use environment proxies, and never follow redirects. Startup and
request waits are bounded. The HTTP response cap is 4 MB; exceeding it is an evaluation
failure, not a quality score.

On success or failure, the runner stops only the process it started and drops only
its generated database. Temporary configuration/logs are removed. Existing product
data, runtime, Google client settings and reader passwords remain unchanged. A forced
OS termination can bypass process cleanup; ordinary exceptions and interrupts run the
cleanup handlers.

## Metrics and the strict gate

- `passed`: expected status and all labeled passages found. This compatibility metric
  permits extra passages and must not be read as clean retrieval.
- `exact_evidence_set_passed`: expected status, all labeled passages, and no extra
  passages. For withholding cases, the matching status and no recommendations are required.
- `precision_at_returned_5`: mean labeled hits divided by actual returned passages,
  across positive cases; empty results score zero.
- `precision_fixed_at_5`: mean distinct labeled hits divided by five, including empty
  slots. A question with one expected passage can score at most 20% on this measure.
- Recall, reciprocal rank, correct withholding, and per-category breakdowns retain the
  existing evaluator definitions. HTTP latency excludes JVM startup/build and evaluator
  integrity reads; these local samples are not load-test results.
- The strict `quality_gate` passes only when **every exact evidence set and expected
  status matches, with no integrity failure**. The default exit status follows this gate.
  `--report-only` permits quality misses but never integrity or transport failures.

Unlabeled passages count as extra under these labels. Some may be useful but unlabeled;
label review is separate from implementation tuning. A strict-gate pass is a benchmark
result, not proof of production readiness or semantic entailment.

Before scoring, the evaluator reads current sources through the same restricted role.
It checks structured references, current revisions, applicability, result bounds,
duplicate passages, recommendation consistency, exact quote ranges and source hashes,
including quoted conflict evidence. Invalid responses are withheld from the report
and reported with fixed error categories. This does not detect every possible leak in
arbitrary explanatory prose or prove that a passage supports a generated claim. Separate
security tests remain necessary; there are no generated answers to evaluate yet.

## Recorded development results

Measured against the current reviewed-v5 Java implementation on 28 October 2026:

| Suite | Outcome matches | Exact evidence sets | Precision of returned passages | Fixed P@5 | Strict gate |
| --- | ---: | ---: | ---: | ---: | --- |
| Original regression | 30/30 | 30/30 | 100% | 20% | Pass |
| Realistic Harbor | 50/50 | 20/50 | 38.28% | 20.63% | Fail |
| Paraphrase probes | 7/8 | 4/8 | 20.83% | 15% | Fail |

All recorded structured integrity checks passed. The realistic set contains 18
withholding cases and 32 positive cases; only **2 of 32 positive cases** returned the
exact labeled evidence set. The paraphrase set still misses P02: “Which checks confirm
that cache recovery is working?” None of its four positive cases returned the exact set.

The full reports, source/question fingerprints and built-jar hashes are in
[`datasets/java-evaluation/`](../datasets/java-evaluation/README.md). These are existing
same-author development cases, not new held-out evidence. No quality improvement is
claimed. The separate evaluation PR #26 remains separate and its reports are not rewritten.

## Next gates

Use newly authored, independently reviewed questions against a frozen corpus to test
broader usefulness. Keep those labels outside ingestion and avoid tuning on the set
before recording the initial report. Improve relevance on a separate development split,
then remeasure the frozen evaluation split. The Java frontend rehearsal, real Google
sign-in, and explicit runtime/rollback review remain pending; this command does not
perform a cutover.

## Optional focused candidate

`--search-mode focused-v6` explicitly configures the isolated Java server for the
precision candidate; the default is `reviewed-v5`. The evaluator checks the selected
method in every response. Use `python -m runreviq.compare_java BASELINE CANDIDATE` to
compare reports only on identical frozen sources/questions/labels. See
[focused retrieval](focused-retrieval.md) for measured gains and limits; the original
v5 reports above remain historical baselines.
