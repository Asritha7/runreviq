# Runtime acceptance and evaluation decisions — 29 October 2026

These checks extend PR #35's isolated rehearsal. The working Python application,
port 8000, private Google configuration, current source revisions and default ranking
remain unchanged. No model, paid service or live cutover is introduced.

## Browser workflow executed

A disposable Java server and synthetic demo identities exercised the existing UI:

1. Platform author uploaded fictional Markdown, supplied missing metadata, saved and
   submitted revision 1. The reviewer queue correctly denied the author's access.
2. A separate security reviewer inspected source/metadata and approved. The UI kept
   the document unpublished; the publisher queue denied this reviewer access.
3. The intern publisher verified a source-bound claim and published to platform readers.
   Ordinary search as that publisher returned insufficient evidence: publishing rights
   do not imply document-reading rights.
4. The platform reader searched and opened the correct immutable revision and citation.
5. The publisher withdrew the revision with a reason. History retained the source;
   a fresh platform-reader search returned insufficient evidence.
6. Disconnect cleared role-specific panels and evidence between identities.

This is a real-browser walkthrough using synthetic demo identities. It does not prove
live Google sign-in, expiration or revocation behavior. Existing automated Google tests
use an offline identity provider. Earlier smoke testing separately covered dark mode.

## Reproducible process-switch rollback

```sh
.venv/bin/python scripts/test_runtime_rollback.py
```

The drill creates a fresh database and private temporary configuration, starts Java,
and performs an HTTP upload/review/approval/publication/search workflow. It stops Java
and verifies that the port is no longer serving. A separate Python process then binds
that **same port and database**, using the same synthetic identities. It checks exact
revision/search response equality and reader exclusion, saves a new metadata revision,
and withdraws the publication. After stopping Python, Java restarts on a new random
port against the same database and verifies the Python edits, withdrawal, retained
history and reader exclusion. Finally both owned processes and the database are removed.

The Python process binds every data operation to the generated database inside a
rehearsal-only adapter. It never changes the product API's default configuration.
The tested Python fallback is the current retained implementation in this checkout,
not a separately built release artifact. Demo identities are reused; Google sessions,
traffic draining, concurrent in-flight requests, deployment networking and an observation
period remain outside this drill. Session transfer between runtimes is not promised.

## PR #26 disposition

PR #26 is closed without merging its Python-only diagnostic and assembly switches into
product search. Its complete code, corpus, questions and frozen results are preserved
at [archive/reviewed-evidence-validation-v1](https://github.com/Asritha7/runreviq/tree/archive/reviewed-evidence-validation-v1),
commit `5a025137c476bb766286e2c13fe1a3f91825aa36`.

The historical report's SHA-256 was verified against its original manifest:
`3afe5d9b6a02903c295ff5c9a077956c70bb5bc17c0a5281401e506d267fa5a6`.
No result or fixture was rewritten. The 94.44% precision figure is a Python development
ablation with different labels/corpus; it is not comparable to the Java realistic suite.
Its missing complementary evidence remains a useful historical finding. A future Java
ablation must explicitly port and verify its experimental controls before reusing it.

## Separate decisions

**Runtime cutover: hold.** Browser demo workflows and a same-port rollback drill now
have executed evidence. Real Google login/logout/expiration/revocation on the isolated
Java origin, operational failure checks and an explicit cutover review are still needed.

**Focused-v6 promotion: hold.** The fresh externally supplied pack did not show improvement
over reviewed-v5 in its first paired run. Both modes missed all required passages in
8 of 11 cases containing required evidence: one was blocked by an unsupported
precondition/unverified metadata, while seven returned insufficient evidence. Both retrieved both opposing passages in
the prose-only conflict case but returned selected rather than conflict. Three cases
retrieved every required passage; that includes the undetected conflict and is not a
semantic task-success count. Two other cases contain no required passages. No forbidden
passage was returned; structured source/citation/authorization checks passed.

The external pack is authored by ChatGPT according to its declaration, with no claimed
inspection of ranking code during creation. Exact text checks found no source/question
duplicates in repository datasets; this is not a semantic overlap test or an independent
authorship audit. Metadata approval is simulated for the disposable fixture, not a
production approval or independent human metadata review. See the evaluation adapter's
limits below. No ranking change was made after seeing these results.

## Local-only external evaluation

```sh
.venv/bin/python scripts/evaluate_external_pack.py \
  --sources /absolute/path/to/source-directory \
  --gold /absolute/path/to/questions_and_gold.json \
  --output /absolute/path/to/new-private-results-directory
```

The supplied archives and untouched answer key stay under ignored `.local/` storage;
neither is committed. SHA-256 receipts and raw paired outputs are retained there.
The evaluator copies only validated source files into the temporary ingestion tree;
questions and labels remain outside SQL and the Java process. It verifies source hashes
and exact gold quotations before starting the runtime, uses identical input/JAR for both
modes, and refuses to overwrite a result directory. Invalid structured output is redacted
rather than preserved as a potentially unauthorized disclosure.

Adaptation is explicit: supported version/environment/role/status/HTTP fields map to the
existing schema. Unsupported arbitrary preconditions remain unverified and are recorded;
they are not mapped to key rotation or silently generalized. Prohibition role proposals
without reviewed normalized claims remain unclassified source passages. No `must` or
`must_not` claims are invented, including for the prose-only conflict case. These limits
mean the run is a provisional capability/retrieval assessment, not full faithful support
for every supplied metadata field. A context/review response is not proof of the requested
multipart-specific clarification. Live-state and semantic-answer requirements are not
automatically graded by an excerpt-only API.

Strict returned precision counts non-required passages as extras. The supplied notes
allow some complementary evidence, so that strict number alone is not a usefulness score.
Raw status, required-evidence recall, forbidden passage IDs and unsupported capabilities
remain separate instead of being collapsed into a misleading overall pass percentage.

## Next bounded work

First review the failed cases and metadata adaptation without changing the frozen inputs.
An optional local hybrid experiment can then compare reviewed-v5, focused-v6 and a new
candidate on identical approved/authorized candidates. Record input/model fingerprints,
recall, strict and adjudicated precision, inappropriate abstention, false conflicts,
unauthorized references and latency. Preserve this first run; once used for tuning this
pack is development material and needs a separately reserved evaluation set.

Keep authorization/applicability deterministic before ranking and revalidate source state
before any future explanation delivery. Generated claims need semantic support checks,
explicit uncertainty and fail-closed handling; exact quotations alone do not establish
entailment. No generation implementation or live-default promotion is authorized by a
benchmark improvement alone.
