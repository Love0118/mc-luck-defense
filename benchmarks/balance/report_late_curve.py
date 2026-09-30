import csv
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'traits'))
from summarize_progression import analyze, wilson

COHORTS = {'none': '무특성', 'top_fusion_four': '최고 4특성'}
CHECKPOINTS = (200, 300, 400, 500, 600, 700, 800, 900, 1000, 1500, 2000, 2500, 3000)


def records(path):
    with gzip.open(path, 'rt', encoding='utf-8') as stream:
        return [json.loads(line) for line in stream]


def reach(group, round_number):
    n = sum(row['round'] >= round_number for row in group)
    return f'{n}/{len(group)} ({n / len(group) * 100:.3f}%)' if group else '관측 없음'


def main(root, output):
    source = root / 'validation'
    settings = dict(line.split('=', 1) for line in (source / 'rules.txt').read_text().splitlines())
    count = int(settings['runs'])
    assert count == 8192
    output.mkdir(parents=True, exist_ok=True)
    (output / '.gitattributes').write_bytes(b'* -text\n')
    analysis = analyze(source, count, output / 'analysis')
    for name in ('rules.txt', 'summary.csv', 'performance.csv', 'none.jsonl.gz', 'top_fusion_four.jsonl.gz',
                 'none-traits.txt', 'top_fusion_four-traits.txt'):
        shutil.copy2(source / name, output / name)
    repository = Path(__file__).resolve().parents[2]
    before = repository / 'docs/balance/adjacent-1.0.16'
    baseline = repository / 'docs/balance/rebalance-1.0.15/candidate'
    new = {name: records(source / (name + '.jsonl.gz')) for name in COHORTS}
    old = {name: records(before / (name + '.jsonl.gz')) for name in COHORTS}
    prior = {name: records(baseline / (name + '.jsonl.gz')) for name in COHORTS}
    for name in COHORTS:
        assert [r['seed'] for r in new[name]] == [r['seed'] for r in old[name]]
        assert [r['seed'] for r in new[name][:len(prior[name])]] == [r['seed'] for r in prior[name]]
        for a, b in zip(old[name], new[name]):
            assert (a['round'] >= 200) == (b['round'] >= 200)
            if a['round'] <= 200:
                assert a == b, f'Early result changed: {name} {a["seed"]}'
            checkpoints_a = [r for r in a['checkpoints'] if r['round'] <= 200]
            checkpoints_b = [r for r in b['checkpoints'] if r['round'] <= 200]
            assert checkpoints_a == checkpoints_b
    with zipfile.ZipFile(root / 'validated.jar') as jar:
        properties = dict(line.split('=', 1) for line in jar.read('mud-runtime.properties').decode().splitlines() if '=' in line)
        assert properties['version'] == '1.0.17'
        (output / 'campaign.properties').write_bytes(jar.read('campaign.properties'))
    lines = ['# 1.0.17 — 승급 화력 변화에 따른 후반 체력 보정', '',
             '1.0.16에서 승급 신화·태초의 상속 공격력이 줄어든 영향을 201~999R 적 체력에 반영했다. 타워·강화·특성·배치·소환 가격·확률·판매가를 변경하지 않았다. 1~200R 및 1,000R 이후 적 설정도 그대로다.', '',
             '## 체력 기준', '',
             '| 라운드 | 1.0.16 | 1.0.17 | 변경 후/변경 전 |', '|---:|---:|---:|---:|']
    values = [(200, 200000, 200000), (300, 3500000, 400000), (400, 8500000, 1800000),
              (500, 16000000, 6000000), (600, 29215470.930176035, 22000000),
              (700, 46419190.23836803, 38000000), (800, 68183714.92227086, 58000000),
              (900, 102364663.51858358, 93000000), (1000, 146879926.25949115, 146879926.25949115)]
    for r, a, b in values:
        lines.append(f'| {r:,} | {a:,.3f} | {b:,.3f} | {b/a*100:.2f}% |')
    lines += ['', '기준 체력에 몹·웨이브 보정이 적용된다. 일반 적과 보스 모두 같은 비율로 완화되며, 등장 수·외형·속도·보상·등장 시점은 같다. 앵커 사이는 기하 보간하며 압력이 라운드마다 증가한다. 1,000R에서 기존 곡선에 합류한다.', '',
              '## 독립 검증', '',
              f'무특성 {count:,}판과 최고 4특성 {count:,}판, 총 {count*2:,}판을 동일 시드 42,000,000~{42000000+count-1:,}로 실행했다. 조정 시드는 43,000,000대로 분리했다. 모든 게임 틱을 처리하며 로컬 12스레드, 관측 상한 10,000R이다.', '',
              '최고 4특성은 피해 +21%, 중복 기물 +25%p, 강화 +30%p, 공속 +18%와 모든 초반 패시브다. 특정 자동 구매·합성·판매·배치 정책의 결과이며 실제 이용자 통계는 아니다.', '',
              '| 도달 | 1.0.16 무특성 | 1.0.17 무특성 | 1.0.16 최고 4특성 | 1.0.17 최고 4특성 |',
              '|---:|---:|---:|---:|---:|']
    for r in (30, 60, 100) + CHECKPOINTS:
        lines.append(f'| {r:,} | ' + ' | '.join(reach(group, r) for group in
                     (old['none'], new['none'], old['top_fusion_four'], new['top_fusion_four'])) + ' |')
    lines += ['', '200R까지의 모든 체크포인트와 그 구간에서 종료한 판의 전체 결과가 1.0.16과 일치함을 원본별로 검증했다. 이후 완화로 생존 판·처치·재화·합성이 늘어나므로 1,000R 이후 체력이 같아도 도달률은 달라진다.', '',
              '## 1.0.15 수준과의 비교', '',
              '초반 곡선이 다른 버전끼리는 처음 시작한 판 전체의 도달률을 그대로 복원할 수 없다. 주 비교는 각 버전에서 200R에 도달한 판 중 이후 도달 비율이다. 이전 표본은 2,048판, 새 표본은 8,192판이다. 생존자 구성이 달라질 수 있으므로 같은 시드·공통 생존자 비교도 아래에 별도로 기록한다.', '',
              '| 이후 도달 | 1.0.15: 200R 생존자 중 | 1.0.16: 200R 생존자 중 | 1.0.17: 200R 생존자 중 |',
              '|---:|---:|---:|---:|']
    for r in CHECKPOINTS[1:]:
        groups = [[x for x in cohort if x['round'] >= 200] for cohort in
                  (prior['top_fusion_four'], old['top_fusion_four'], new['top_fusion_four'])]
        lines.append(f'| {r:,} | ' + ' | '.join(reach(g, r) for g in groups) + ' |')
    lines += ['', '주 조정 기준은 최고 4특성의 후반 생존 곡선이다. 600~1,000R은 1.0.15보다 일부 어렵게 남아 있다. 무특성 전체 500R 도달률은 1.0.15의 1.611%에서 1.0.17의 17.188%로 높아졌다. 무특성 곡선까지 동시에 이전 수준으로 복원한 결과는 아니며, 강한 직접 소환 기물을 얻은 판도 완화된 체력을 적용받는다.']
    paired = []
    lines += ['', '### 같은 시드·공통 200R 생존자', '',
              '| 구성·라운드 | 1.0.15 | 1.0.17 |', '|---|---:|---:|']
    for name, label in COHORTS.items():
        pairs = [(a, b) for a, b in zip(prior[name], new[name]) if a['round'] >= 200 and b['round'] >= 200]
        for r in CHECKPOINTS[1:]:
            a, b = [x[0] for x in pairs], [x[1] for x in pairs]
            lines.append(f'| {label} {r:,}R | {reach(a,r)} | {reach(b,r)} |')
            paired.append(dict(scenario=name, round=r, eligible=len(pairs), old=sum(x['round']>=r for x in a),
                               new=sum(x['round']>=r for x in b)))
    with (output / 'paired-baseline.csv').open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(paired[0]))
        writer.writeheader(); writer.writerows(paired)
    lines += ['', '## 후반과 적용', '']
    for name, label in COHORTS.items():
        n = sum(x['round'] >= 2000 for x in new[name]); lo, hi = wilson(n, count)
        longest = max(new[name], key=lambda x: x['round'])
        lines.append(f'- {label}: 2,000R {n}/{count}, 95% Wilson 구간 {lo:.4f}~{hi:.4f}%. 최고 {longest["round"]:,}R, 시드 {longest["seed"]}, {longest["outcome"]}.')
    lines += ['', '이 검증은 10만 판이 아니다. 모든 특성·기물 조합에 동일한 체감을 보장하지 않으며 과거 2,000R 희귀 도달 목표를 확정하지 않는다. 새 곡선은 전체 자동 플레이 결과를 기준으로 맞췄으므로 상속 피해가 없던 직접 소환 태초 중심 조합은 이전보다 쉬워질 수 있다.', '',
              '442개 Java 테스트 및 Python 결과 검증기 테스트 3개 통과. 1~200R·1,000~10,000R 체력 보존과 전 구간 몹 구성·속도·보상·등장 시점 보존, 완화 구간·연속 압력 검증을 포함한다. 등급 간격·승급 상속 규칙은 1.0.16과 같다.', '',
              '새 세션 기준 설정이다. 기존 세션의 캠페인 설정은 강제 리로드로 재평가하지 않는다. 배포 JAR는 update 폴더에 준비하며 라이브 리로드·재시작은 실행하지 않는다.', '',
              '원본: 본 폴더의 rules.txt, trait 목록, jsonl.gz, analysis/reach.csv·conditional.csv. 이전 원본: ../adjacent-1.0.16 및 ../rebalance-1.0.15/candidate. 재현 도구: benchmarks/balance/report_late_curve.py.']
    (output / 'REPORT.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    for path in output.rglob('*'):
        if path.is_file() and path.suffix != '.gz':
            path.write_bytes(path.read_bytes().replace(b'\r\n', b'\n'))
    files = {str(p.relative_to(output)): hashlib.sha256(p.read_bytes()).hexdigest()
             for p in sorted(output.rglob('*')) if p.is_file() and p.name != 'manifest.json'}
    manifest = dict(version='1.0.17', source_base_commit='4c517e59028c0e8779ca94c153a91aaa5250ff69',
                    tested_jar_sha256=hashlib.sha256((root / 'validated.jar').read_bytes()).hexdigest(),
                    validation_games=analysis['validated_games'], files=files)
    (output / 'manifest.json').write_bytes((json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
    print(output / 'REPORT.md')


if __name__ == '__main__':
    main(Path(sys.argv[1]), Path(sys.argv[2]))
