import argparse
import gzip
import hashlib
import json
from pathlib import Path
import zipfile


def properties(data):
    return dict(line.split('=', 1) for line in data.decode().splitlines()
                if '=' in line and not line.startswith('#'))


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def seed_completed_prefix(source, old_jar, new_jar, output, boundary):
    with zipfile.ZipFile(old_jar) as old, zipfile.ZipFile(new_jar) as new:
        old_classes = {n for n in old.namelist() if n.startswith('dev/moma/') and n.endswith('.class')}
        new_classes = {n for n in new.namelist() if n.startswith('dev/moma/') and n.endswith('.class')}
        if old_classes != new_classes or any(old.read(n) != new.read(n) for n in old_classes):
            raise ValueError('Game classes changed; prefix results cannot be reused')
        before, after = properties(old.read('campaign.properties')), properties(new.read('campaign.properties'))
        old_curve, new_curve = before.pop('health-curve'), after.pop('health-curve')
        if before != after:
            raise ValueError('Other campaign rules changed')
        def anchors(curve):
            return [(int(r), float(h)) for r, h in (point.split(':') for point in curve.split(','))]
        a, b = anchors(old_curve), anchors(new_curve)
        if boundary not in [r for r, h in a] or boundary not in [r for r, h in b]:
            raise ValueError('Boundary must be an exact anchor in both curves')
        if [p for p in a if p[0] <= boundary] != [p for p in b if p[0] <= boundary]:
            raise ValueError('Health changed within the reused prefix')
    settings = properties((source / 'rules.txt').read_bytes())
    count, first = int(settings['runs']), int(settings['seed'])
    if settings['roundCap'] != '10000' or settings['scenarios'] != 'none,top_fusion_four':
        raise ValueError('Unexpected baseline scenarios or cap')
    if output.exists() and any(output.iterdir()):
        raise ValueError('Output must be empty')
    manifest = json.loads((source / 'manifest.json').read_text(encoding='utf-8'))
    if sha256(old_jar) != manifest['tested_jar_sha256']:
        raise ValueError('Baseline JAR is not the exact tested artifact')
    if properties((source / 'campaign.properties').read_bytes())['health-curve'] != old_curve:
        raise ValueError('Baseline records and baseline JAR use different curves')
    output.mkdir(parents=True, exist_ok=True)
    reused, sources = {}, {}
    for name in settings['scenarios'].split(','):
        path = source / (name + '.jsonl.gz')
        actual_hash = sha256(path)
        if actual_hash != manifest['files'][path.name]:
            raise ValueError('Baseline record checksum mismatch')
        sources[path.name] = actual_hash
        selected, total = [], 0
        with gzip.open(path, 'rt', encoding='utf-8') as stream:
            for i, line in enumerate(stream):
                row = json.loads(line)
                if row['seed'] != first + i:
                    raise ValueError('Missing or reordered baseline seed')
                total += 1
                if row['outcome'] == 'ENEMY_LIMIT' and row['round'] <= boundary:
                    selected.append(line.rstrip('\r\n'))
        if total != count:
            raise ValueError('Incomplete baseline cohort')
        (output / (name + '.partial.jsonl')).write_bytes(('\n'.join(selected) + '\n').encode('utf-8'))
        reused[name] = len(selected)
    evidence = dict(source_version=manifest['version'], boundary=boundary, runs_per_cohort=count,
                    seed=first, old_jar_sha256=sha256(old_jar), new_jar_sha256=sha256(new_jar),
                    identical_game_classes=len(old_classes), source_records=sources, reused=reused,
                    rerun={name: count - n for name, n in reused.items()})
    (output.parent / 'prefix-reuse.json').write_bytes((json.dumps(evidence, indent=2) + '\n').encode())
    return evidence


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=Path)
    parser.add_argument('old_jar', type=Path)
    parser.add_argument('new_jar', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--boundary', type=int, required=True)
    args = parser.parse_args()
    print(json.dumps(seed_completed_prefix(args.source, args.old_jar, args.new_jar, args.output, args.boundary)))
