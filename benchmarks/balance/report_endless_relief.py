import hashlib
import json
from pathlib import Path
import shutil
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'traits'))
from summarize_progression import analyze, wilson
from report_late_curve import records, reach

COHORTS = {'none': '무특성', 'top_fusion_four': '최고 4특성'}
ROUNDS = (200, 300, 500, 800, 900, 1000, 1250, 1500, 1750, 2000, 2250, 2500, 3000, 5000, 10000)


def main(root, output):
    source = root / 'validation'
    reuse = json.loads((root / 'prefix-reuse.json').read_text())
    assert reuse['source_version'] == '1.0.17' and reuse['boundary'] == 800
    count = reuse['runs_per_cohort']
    assert count == 8192 and reuse['seed'] == 42000000
    assert reuse['new_jar_sha256'] == hashlib.sha256((root / 'validated.jar').read_bytes()).hexdigest()
    output.mkdir(parents=True, exist_ok=True)
    (output / '.gitattributes').write_bytes(b'* -text\n')
    analysis = analyze(source, count, output / 'analysis')
    repository = Path(__file__).resolve().parents[2]
    before = repository / 'docs/balance/late-curve-1.0.17'
    baseline = repository / 'docs/balance/rebalance-1.0.15/candidate'
    old = {name: records(before / (name + '.jsonl.gz')) for name in COHORTS}
    new = {name: records(source / (name + '.jsonl.gz')) for name in COHORTS}
    prior = {name: records(baseline / (name + '.jsonl.gz')) for name in COHORTS}
    for name in COHORTS:
        assert reuse['source_records'][name+'.jsonl.gz'] == hashlib.sha256((before/(name+'.jsonl.gz')).read_bytes()).hexdigest()
        assert sum(r['outcome'] == 'ENEMY_LIMIT' and r['round'] <= 800 for r in old[name]) == reuse['reused'][name]
        assert [r['seed'] for r in new[name]] == [r['seed'] for r in old[name]]
        assert [r['seed'] for r in new[name][:len(prior[name])]] == [r['seed'] for r in prior[name]]
        for a, b in zip(old[name], new[name]):
            if a['round'] <= 800:
                assert a == b
            assert [r for r in a['checkpoints'] if r['round'] <= 800] == [r for r in b['checkpoints'] if r['round'] <= 800]
    for name in ('rules.txt', 'summary.csv', 'performance.csv', 'none.jsonl.gz', 'top_fusion_four.jsonl.gz',
                 'none-traits.txt', 'top_fusion_four-traits.txt'):
        shutil.copy2(source/name, output/name)
    shutil.copy2(root/'prefix-reuse.json', output/'prefix-reuse.json')
    for prefix, folder in [('rejected-65', repository/'.runtime/endless-relief-final/validation'),
                           ('pilot-85', repository/'.runtime/endless-relief-b')]:
        for name in ('rules.txt', 'summary.csv'):
            shutil.copy2(folder/name, output/(prefix+'-'+name))
    with zipfile.ZipFile(root/'validated.jar') as jar:
        props = dict(line.split('=', 1) for line in jar.read('mud-runtime.properties').decode().splitlines() if '=' in line)
        assert props['version'] == '1.0.18'
        (output/'campaign.properties').write_bytes(jar.read('campaign.properties'))
    reused, rerun = sum(reuse['reused'].values()), sum(reuse['rerun'].values())
    assert reused + rerun == count * 2
    lines = ['# 1.0.18 — 1,000R 이후에도 이어지는 태초 화력 보정', '',
             '1.0.17의 완화는 1,000R에서 기존 체력으로 합류했다. 승급 태초가 후반에도 공격에 참여하는 점을 반영해 1,000R 이후 모든 라운드의 적 체력을 1.0.17 대비 85%로 낮췄다. 일반 적과 보스에 모두 적용되며 성장 지수 3.1과 압력 보정 1.3은 같다.', '',
             '800R까지는 1.0.17과 같다. 900R 기준 체력은 9,300만→8,800만, 1,000R은 약 1억 4,688만→1억 2,485만이다. 801~1,000R은 기하 보간하며 1,001R 이후에도 감소 비율 85%가 유지된다. 몹 구성·속도·보상·등장 시점, 타워·강화·특성·구매·판매는 바꾸지 않았다.', '',
             '35% 감소안은 같은 8,192개 최고 4특성 시드에서 1,500R 도달률을 0.793%→16.919%로 높여 채택하지 않았다. 15% 감소안은 별도 1,024개 조정 시드에서 2,000R 7회(0.684%)를 관측한 뒤 아래 동일 시드 회귀 비교를 진행했다. 후보 설정·요약은 rejected-65-* 및 pilot-85-*에 보관했다.', '',
             '## 표본과 재실행', '',
             f'무특성·최고 4특성 각각 {count:,}개 시드, 총 {count*2:,}개의 검증 결과다. 이 중 {rerun:,}판은 영향을 받을 수 있어 처음부터 다시 시뮬레이션했다. {reused:,}판은 800R까지 패배하여 이후 변경 구간에 도달하지 않았으므로 1.0.17의 완료 결과를 재사용했다.', '',
             f'재사용 전 실제 테스트 JAR·원본 결과 해시, 게임 클래스 {reuse["identical_game_classes"]}개 전체 바이트 일치, 체력 앵커의 1~800R 일치와 나머지 캠페인 설정 일치를 검증했다. 경계 900R로 재사용을 시도하면 체력 변경이 검출되어 거절됨도 확인했다. 완료되지 않은 상태나 기존 게임 상태를 새 규칙과 섞어 이어 돌린 것은 아니다.', '',
             '재현: reuse_unchanged_prefix.py로 완료된 초기 패배 기록만 준비하고 ProgressionBenchmarkMain --resume을 실행한다. 재실행 시드는 기존과 동일한 42,000,000~42,008,191이다. 이 표본은 35% 감소안 평가에도 사용하여 엄격히 미사용된 독립 검증 표본은 아니다. 별도 조정 표본은 43,000,000대다. 관측 상한은 10,000R이고 게임에 종료 조건을 추가하지 않았다. 12스레드로 모든 게임 틱을 처리했다. performance.csv의 처리 속도는 새로 돌린 판만 포함하며 총 표본을 이번 실행 시간으로 나누지 않는다.', '',
             '최고 4특성은 피해 +21%, 중복 기물 +25%p, 강화 +30%p, 공속 +18%와 모든 초반 패시브다.', '',
             '| 도달 | 1.0.17 무특성 | 1.0.18 무특성 | 1.0.17 최고 4특성 | 1.0.18 최고 4특성 |',
             '|---:|---:|---:|---:|---:|']
    for r in ROUNDS:
        lines.append(f'| {r:,} | ' + ' | '.join(reach(g, r) for g in
                     (old['none'], new['none'], old['top_fusion_four'], new['top_fusion_four'])) + ' |')
    lines += ['', '## 패치 이전 1.0.15와의 비교', '',
              '초반 난이도가 달라 전체 시작 판 대비 도달률과 200R 생존자 중 도달률을 구분한다. 이전 표본은 2,048판, 현재는 8,192판이다. 아래 표는 각 버전의 최고 4특성 200R 생존자 기준이며 생존자 구성 차이가 있다.', '',
              '| 이후 도달 | 1.0.15 | 1.0.17 | 1.0.18 |', '|---:|---:|---:|---:|']
    for r in ROUNDS[1:]:
        groups = [[x for x in g if x['round'] >= 200] for g in
                  (prior['top_fusion_four'], old['top_fusion_four'], new['top_fusion_four'])]
        lines.append(f'| {r:,} | '+' | '.join(reach(g, r) for g in groups)+' |')
    lines += ['', '## 불확실성·적용', '']
    for name, label in COHORTS.items():
        for r in (2000, 2500):
            n = sum(x['round'] >= r for x in new[name]); lo, hi = wilson(n, count)
            lines.append(f'- {label} {r:,}R: {n}/{count}, 95% Wilson 구간 {lo:.4f}~{hi:.4f}%.')
        longest = max(new[name], key=lambda x: x['round'])
        lines.append(f'- {label} 최고 {longest["round"]:,}R, 시드 {longest["seed"]}, {longest["outcome"]}.')
    lines += ['', '표본은 10만 판이 아니다. 직접 소환 태초와 승급 태초, 강화 이력, 특성·공격 타입의 모든 조합에 동일한 체감을 보장하지 않는다. 특정 자동 플레이 정책의 결과이며 실제 이용자 전체의 도달률은 아니다. 이전과 동일한 희귀 도달률을 보장한 것은 아니다.', '',
              '442개 Java 테스트·Python 결과 검증기 테스트 3개 통과. 1~800R 체력 보존, 전 구간 몹 구성·속도·보상·등장 시점 보존, 1,000~10,000R 전수 85% 비율과 10,001·20,000·100,000R의 유지, 경계의 연속 상승을 검증했다.', '',
              '새 세션부터 새 곡선을 사용한다. 진행 중 세션의 저장된 캠페인 설정은 강제 리로드로 소급 변경하지 않는다. beta 배포 및 로컬·오라클 update 폴더에 준비하며 라이브 리로드·재시작은 실행하지 않는다.', '',
              '원본은 본 폴더의 rules.txt·trait 목록·jsonl.gz·analysis에 있다. prefix-reuse.json에 재사용 근거와 새로 실행한 판 수를 기록했다. 도구는 benchmarks/balance/reuse_unchanged_prefix.py 및 report_endless_relief.py다.']
    (output/'REPORT.md').write_text('\n'.join(lines)+'\n', encoding='utf-8')
    for path in output.rglob('*'):
        if path.is_file() and path.suffix != '.gz':
            path.write_bytes(path.read_bytes().replace(b'\r\n', b'\n'))
    files = {str(p.relative_to(output)): hashlib.sha256(p.read_bytes()).hexdigest()
             for p in sorted(output.rglob('*')) if p.is_file() and p.name != 'manifest.json'}
    manifest = dict(version='1.0.18', source_base_commit='b4c7ea9b1a3d652aead1f8c2b0da3bcf241bee9b',
                    tested_jar_sha256=hashlib.sha256((root/'validated.jar').read_bytes()).hexdigest(),
                    validation_games=analysis['validated_games'], reused_games=reused, rerun_games=rerun, files=files)
    (output/'manifest.json').write_bytes((json.dumps(manifest, ensure_ascii=False, indent=2)+'\n').encode('utf-8'))
    print(output/'REPORT.md')


if __name__ == '__main__':
    main(Path(sys.argv[1]), Path(sys.argv[2]))
