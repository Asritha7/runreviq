"""Private, mode-blinded review of frozen returned evidence; no runtime or database access."""
import argparse
import hashlib
import json
import os
from pathlib import Path

MODES = ('reviewed-v5', 'focused-v6', 'relaxed-lexical-v1')
LABELS = {'relevant', 'irrelevant', 'wrong_procedure', 'uncertain'}
FIELDS = ('document_id', 'revision', 'start_line', 'end_line', 'source_sha256', 'quote', 'evidence_role')


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    raw = path.read_bytes()
    return json.loads(raw), digest(raw)


def private_write(path, value):
    # Exclusive create: never overwrite labels, frozen inputs, or symlinks.
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as stream:
        stream.write(canonical(value) + b'\n')


def build(report, gold, report_hash, gold_hash, role):
    """Only returned passages for this role enter the packet; no raw trace projection."""
    require(gold_hash in report['frozen_file_hashes'].values(), 'Gold does not match frozen replay')
    require(report['dataset_use'] == 'development_regression_not_unseen_validation', 'Unsupported dataset use')
    require(not report['git_dirty'], 'A clean committed replay is required')
    required_guarantees = {'reader_snapshot_only', 'source_publication_state_unchanged', 'frozen_inputs_unchanged'}
    require(required_guarantees <= set(report['guarantees']), 'Missing replay guarantees')
    cases = {case['id']: case for case in gold['cases']}
    require(len(cases) == len(gold['cases']), 'Duplicate case')
    require(role in {c['context']['role'] for c in cases.values()}, 'Unknown role')
    rows = {(row['mode'], row['id']): row for row in report['traces']}
    require(len(rows) == len(report['traces']), 'Duplicate replay row')
    require(set(rows) == {(m, c) for m in MODES for c in cases}, 'Incomplete or unsupported replay arms')
    items, memberships = {}, {m: [] for m in MODES}
    selected_cases = sorted(c for c in cases if cases[c]['context']['role'] == role)
    for mode in MODES:
        for case_id in selected_cases:
            row = rows[mode, case_id]
            snapshots = [e for e in row['events'] if e['stage'] == 'authorized_snapshot']
            require(len(snapshots) == 1, 'Missing authorized snapshot')
            authorized = {(d['document_id'], d['revision'], d['source_sha256']) for d in snapshots[0]['documents']}
            seen = set()
            for passage in row['response']['passages']:
                require((passage['document_id'], passage['revision'], passage['source_sha256']) in authorized,
                        'Returned passage absent from authorized snapshot')
                require(passage['document_id'] in row['response']['applicable_documents'], 'Inapplicable returned passage')
                evidence = {k: passage[k] for k in FIELDS}
                content = dict(case_id=case_id, question=cases[case_id]['question'],
                               context=cases[case_id]['context'], passage=evidence)
                item_id = digest(canonical(content))
                require(item_id not in seen, 'Duplicate returned passage')
                seen.add(item_id)
                items[item_id] = dict(item_id=item_id, **content)
                memberships[mode].append(item_id)
    packet = dict(schema_version=1, role=role, dataset_use='development_regression_not_unseen_validation',
                  instructions='Judge whether each passage addresses the question in its supplied context. A relevant prohibition or monitoring procedure is useful evidence, not proof of live state or root cause. Use wrong_procedure for an operational procedure mismatched to the requested task, irrelevant for other nonresponsive evidence, uncertain when context is inadequate. Do not infer the search mode. Do not edit evidence.',
                  items=[items[k] for k in sorted(items)])
    # Counts, arm membership, baseline outputs and gold labels stay outside the blinded packet.
    manifest = dict(schema_version=1, report_sha256=report_hash, gold_sha256=gold_hash,
                    packet_sha256=digest(canonical(packet)), role=role, memberships=memberships,
                    case_count=len(selected_cases), code_revision=report['code_revision'])
    labels = dict(schema_version=1, packet_sha256=manifest['packet_sha256'], reviewer='',
                  independence_attestation='', judgments=[dict(item_id=k, label='pending', rationale='') for k in sorted(items)])
    return packet, manifest, labels


def prepare(report_path, gold_path, role, output):
    report, report_hash = read_json(report_path)
    gold, gold_hash = read_json(gold_path)
    packet, manifest, labels = build(report, gold, report_hash, gold_hash, role)
    output.mkdir(mode=0o700, parents=False, exist_ok=False)
    for name, value in [('packet.json', packet), ('operator-manifest.json', manifest), ('labels-template.json', labels)]:
        private_write(output / name, value)
    return len(packet['items'])


def score(report_path, gold_path, directory, labels_path, output):
    report, report_hash = read_json(report_path)
    gold, gold_hash = read_json(gold_path)
    manifest, _ = read_json(directory / 'operator-manifest.json')
    packet, _ = read_json(directory / 'packet.json')
    expected, bound, _ = build(report, gold, report_hash, gold_hash, manifest['role'])
    require(packet == expected and manifest == bound, 'Packet, manifest, or frozen inputs changed')
    labels, labels_hash = read_json(labels_path)
    require(labels['schema_version'] == 1 and labels['packet_sha256'] == bound['packet_sha256'], 'Labels target another packet')
    for field in ('reviewer', 'independence_attestation'):
        require(isinstance(labels[field], str) and bool(labels[field].strip()), 'Reviewer identity and attestation required')
    judgments = {j['item_id']: j for j in labels['judgments']}
    require(len(judgments) == len(labels['judgments']), 'Duplicate judgment')
    require(set(judgments) == {i['item_id'] for i in packet['items']}, 'Missing or unknown judgment')
    for j in judgments.values():
        require(j['label'] in LABELS, 'Pending or invalid label; review is incomplete')
        require(isinstance(j['rationale'], str) and bool(j['rationale'].strip()), 'Each judgment needs a rationale')
    metrics = {}
    for mode, ids in bound['memberships'].items():
        counts = {label: sum(judgments[k]['label'] == label for k in ids) for label in sorted(LABELS)}
        total = len(ids)
        decisive = total - counts['uncertain']
        metrics[mode] = dict(returned_passages=total, counts=counts,
                            decisive_precision=counts['relevant']/decisive if decisive else None,
                            relevance_lower_bound=counts['relevant']/total if total else None,
                            relevance_upper_bound=(counts['relevant']+counts['uncertain'])/total if total else None,
                            observed_wrong_procedure_rate=counts['wrong_procedure']/total if total else None)
    result = dict(schema_version=1, **{k: bound[k] for k in ('report_sha256','gold_sha256','packet_sha256','role','code_revision')},
                  labels_sha256=labels_hash, reviewer=labels['reviewer'], independence_attestation=labels['independence_attestation'],
                  independence_status='reviewer_attested_not_verified', dataset_use=packet['dataset_use'], metrics=metrics,
                  limitation='Returned-evidence review only. Unknown judgments remain visible; recall, missed evidence, answer correctness and live authorization are not established. No policy is tuned or promoted.')
    private_write(output, result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    for name in ('prepare', 'score'):
        p = sub.add_parser(name)
        p.add_argument('--report', type=Path, required=True)
        p.add_argument('--gold', type=Path, required=True)
        p.add_argument('--output', type=Path, required=True)
        if name == 'prepare':
            p.add_argument('--role', required=True)
        else:
            p.add_argument('--packet-directory', type=Path, required=True)
            p.add_argument('--labels', type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == 'prepare':
            count = prepare(args.report, args.gold, args.role, args.output)
            print(f'Prepared {count} pending review items; no judgments assigned.')
        else:
            score(args.report, args.gold, args.packet_directory, args.labels, args.output)
            print('Private adjudication report created; no runtime changes.')
    except (ValueError, KeyError, TypeError, OSError):
        parser.exit(1, 'Review rejected: invalid/incomplete inputs or output already exists. No accepted score produced.\n')


if __name__ == '__main__':
    main()
