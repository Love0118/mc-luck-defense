"""Archive and validate the 1.0.19 holdout; regenerate its comparison tables and figures."""
import csv
import gzip
import hashlib
import json
import math
from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / '.runtime/analysis-deps'))
sys.path.insert(0, str(ROOT / 'benchmarks/traits'))
from summarize_progression import analyze, wilson, write_csv
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt


def rows(path):
    with gzip.open(path, 'rt', encoding='utf-8') as stream:
        return [json.loads(line) for line in stream]


def main():
    source = ROOT / '.runtime/smooth-curve-final/validation'
    probe = ROOT / '.runtime/damage-share-1.0.19'
    output = ROOT / 'docs/balance/smooth-growth-1.0.19'
    output.mkdir(parents=True, exist_ok=True)
    for name in ('rules.txt', 'summary.csv', 'performance.csv', 'none.jsonl.gz',
                 'top_fusion_four.jsonl.gz', 'none-traits.txt', 'top_fusion_four-traits.txt'):
        shutil.copy2(source / name, output / name)
    analyze(output, 8192, output)
    shutil.copy2(ROOT / 'src/main/resources/campaign.properties', output / 'campaign.properties')
    for name in ('damage-shares.csv', 'loss-fit.csv', 'fitted-parameters.json', 'state-compatibility.json'):
        shutil.copy2(probe / name, output / name)
    shutil.copy2(ROOT / '.runtime/smooth-curve-final/parity.json', output / 'parity.json')
    for name in ('old.csv', 'old-early.csv', 'current.csv'):
        (output / (name + '.gz')).write_bytes(gzip.compress((probe / name).read_bytes(), mtime=0))
    for candidate in ('a', 'd', 'e'):
        shutil.copy2(ROOT / f'.runtime/smooth-curve-{candidate}/summary.csv', output / f'pilot-{candidate}.csv')
        config = probe / f'candidate-{candidate}.properties'
        if config.exists():
            shutil.copy2(config, output / f'pilot-{candidate}.properties')
    checkpoints = (300, 400, 500, 600, 700, 800, 900, 1000, 1250, 1500, 1750, 2000, 2500)
    comparison, cohorts = [], {}
    for scenario in ('none', 'top_fusion_four'):
        for version, folder in [('1.0.15', ROOT / 'docs/balance/rebalance-1.0.15/candidate'),
                                ('1.0.18', ROOT / 'docs/balance/endless-relief-1.0.18'),
                                ('1.0.19', output)]:
            games = rows(folder / (scenario + '.jsonl.gz'))
            survivors = [g for g in games if g['completedRounds'] >= 200]
            cohorts[(scenario, version)] = (games, survivors)
            for round_number in checkpoints:
                count = sum(g['round'] >= round_number for g in survivors)
                lo, hi = wilson(count, len(survivors))
                comparison.append(dict(scenario=scenario, version=version, round=round_number,
                                       starts=len(games), completed200=len(survivors), reached=count,
                                       absolute_percent=100*count/len(games),
                                       conditional_percent=100*count/len(survivors), ci_low=lo, ci_high=hi))
    write_csv(output / 'conditional-comparison.csv', comparison)
    fig, axes = plt.subplots(1, 2, figsize=(12, 4.5))
    for version in ('1.0.15', '1.0.18', '1.0.19'):
        values = [r for r in comparison if r['scenario'] == 'top_fusion_four' and r['version'] == version]
        for ax in axes:
            ax.plot([r['round'] for r in values], [r['conditional_percent'] for r in values], marker='.', label=version)
    axes[0].set(xlim=(200, 1500), ylim=(0, 103), title='Survival after completing 200R')
    axes[1].set(xlim=(1250, 2500), ylim=(0.1, 50), yscale='log', title='Late survival (log scale)')
    for ax in axes:
        ax.set(xlabel='Round reached', ylabel='% of 200R survivors'); ax.grid(alpha=.25); ax.legend()
    fig.tight_layout(); fig.savefig(output / 'conditional-reach.png', dpi=160); plt.close(fig)
    with (output / 'damage-shares.csv').open() as stream:
        damage = [r for r in csv.DictReader(stream) if r['version'] == 'old']
    fig, axes = plt.subplots(1, 2, figsize=(12, 4.5))
    x = [int(r['start']) + 49.5 for r in damage]
    for grade in ('MYTHIC', 'PRIMORDIAL', 'TRUE_PRIMORDIAL', 'MIRACLE'):
        axes[0].plot(x, [100*float(r[grade]) for r in damage], label=grade)
    axes[0].set(title='Effective damage share before enhancement change', ylabel='% of effective damage')
    params = json.loads((output / 'fitted-parameters.json').read_text())
    axes[1].plot(x, [100*float(r['loss']) for r in damage], 'o', label='Measured same-hit loss')
    axes[1].plot(x, [100*params['loss_amplitude']*math.exp(-(r/params['loss_scale'])**params['loss_power']) for r in x], label='Fitted loss')
    axes[1].set(title='Compensation fades with measured damage loss', ylabel='% loss')
    for ax in axes:
        ax.set(xlabel='100-round window midpoint'); ax.grid(alpha=.25); ax.legend(fontsize=8)
    fig.tight_layout(); fig.savefig(output / 'damage-contribution.png', dpi=160); plt.close(fig)
    lines = ['# 1.0.19 — 200R 생존자 기준 후반 곡선 복원', '',
             '1~200R은 1.0.18을 유지하고, 이후 도달률을 강화 변경 전 1.0.15의 **200R 완료자** 기준에 맞췄다. 도달은 해당 라운드 시작을 뜻한다. 전체 시작 판의 도달률을 1.0.15와 같게 만드는 조정은 아니다.', '',
             '## 독립 검증', '',
             '조정에 사용하지 않은 시드 44,000,000~44,008,191로 무특성·최고 4특성 각각 8,192판, 총 16,384판을 처음부터 실행했다. 결과 재사용은 없다. 관측 상한은 10,000R이며 게임의 라운드 제한은 아니다. 기준 1.0.15는 기존 2,048판 자료다. 같은 시드의 직접 비교가 아니므로 표본 변동을 포함한다.', '',
             '| 라운드 | 1.0.15 | 1.0.19 | 차이(%p) | 1.0.19 95% 구간 |',
             '|---:|---:|---:|---:|---:|']
    for r in checkpoints:
        old = next(v for v in comparison if v['scenario']=='top_fusion_four' and v['version']=='1.0.15' and v['round']==r)
        new = next(v for v in comparison if v['scenario']=='top_fusion_four' and v['version']=='1.0.19' and v['round']==r)
        lines.append(f"| {r} | {old['conditional_percent']:.3f}% | {new['conditional_percent']:.3f}% | {new['conditional_percent']-old['conditional_percent']:+.3f} | {new['ci_low']:.3f}~{new['ci_high']:.3f}% |")
    lines += ['', '최고 4특성 기준이며 희귀 후반 도달은 오차가 크다. 구간은 Wilson 95% 신뢰구간으로, 이전 표본에도 불확실성이 있다. 신뢰구간의 중첩 자체가 동등성의 증명은 아니다. 300~400R은 이전보다 약 3~4%p 낮아 완전히 복원되지 않았다. 600~1250R의 차이는 약 1%p 이내다. 특성 이용자의 후반을 우선한 조정이며 무특성의 조건부 곡선까지 동일하게 맞춘 결과는 아니다.', '',
              '![조건부 도달률](conditional-reach.png)', '',
              '| 조건 | 버전 | 전체 판 | 200R 완료 | 1000R/전체 | 2000R/전체 | 최장 도달 |', '|---|---|---:|---:|---:|---:|---:|']
    for (scenario, version), (games, survivors) in cohorts.items():
        lines.append(f"| {'무특성' if scenario=='none' else '최고 4특성'} | {version} | {len(games)} | {len(survivors)} | {100*sum(g['round']>=1000 for g in games)/len(games):.3f}% | {100*sum(g['round']>=2000 for g in games)/len(games):.3f}% | {max(g['round'] for g in games)} |")
    lines += ['', '## 피해 측정과 곡선', '',
              '이전 전투를 타격 단위로 재실행하고, 실제 적용된 피해와 현재 등급·강화 공식으로 같은 타격을 계산한 피해를 비교했다. 초과 피해는 남은 체력으로 잘랐다. 재구성된 실제 피해가 전투 엔진 값과 같고, 계측한 게임의 최종 라운드·틱·구매·판매가 원본과 같은지 검사했다. 이는 같은 타격의 화력 손실 추정이며, 표적 변경·처치 시점까지 다시 계산한 가상 전투는 아니다. 최종 도달률은 별도의 실제 전투 시뮬레이션으로 확인했다.', '',
              '200~1499R 피해 지분은 첫 512개 시드의 해당 구간 생존자별 비율을 평균했다. 1500R 이상은 이전 2048판·1.0.18 8192판의 생존자를 계측했다. 후반에 표본 구성이 달라지며, 늦은 구간은 표본이 적다. 원시 타격 집계는 old.csv.gz, old-early.csv.gz, current.csv.gz에 보관한다.', '',
              '![피해 기여도와 손실](damage-contribution.png)', '',
              '200R 이후는 하나의 식으로 계산한다. 기준 성장식 B(r)에 이전 곡선을 근사하는 완만한 보정, 실측 화력 손실 L(r), 초반을 고정했을 때 생기는 조합 성장 지연 G(r)을 함께 반영한다. 500·1000·1500R별 체력값을 직접 입력하지 않는다.', '',
              '```text',
              'x = r / 1000',
              'B(r) = 146879926.25949115 × x^3.1 × ((1+x²)/2)^1.3',
              'L(r) = 0.7110088784 × exp(-(r/619.4530037)^1.593123948)',
              'A(r) = B(r) × exp(-0.9100461108 × ln(x)/(1+x^19.99999472)) × (1-L(r))',
              'G(r) = exp(|ln(200/277.1763647)/0.5348965926|³ - |ln(r/277.1763647)/0.5348965926|³)',
              'H(r) = A(r) × exp(-ln(A(200)/200000) × G(r))',
              '```', '',
              'L은 이전 타격 손실 자료를 가중 로그 최소제곱으로 근사했다. 기준식 보정은 1.0.15 체력 곡선에 적합했다. 성장 지연의 폭·중심·형태는 43,000,000부터 1,024개 시드로 A/D/E 후보를 비교하여 D를 선택했다. 공통 도달 구간 600~1250R의 오차를 우선했고 희귀한 2000R 성공 횟수만 맞추지 않았다. 후보 요약과 설정을 함께 보관한다. 전체 곡선의 정확한 계수는 campaign.properties에 있다.', '',
              '보정은 후반으로 갈수록 사라져 기존의 장기 성장식으로 수렴한다. 1.0.18처럼 무한 구간 전체를 15% 깎지 않는다. 저장 호환성을 위해 계산한 정수 라운드 값을 기존 HealthCurve 형식에 저장한다. 이 값들은 수식의 샘플이며 개별 수동 조정값이 아니다. 10,000R 이후에도 수렴한 같은 성장식이 이어진다.', '',
              '## 검증 범위와 적용', '',
              '1~200R 체력과 몹 구성·보상·속도·출현 시점 보존, 증가 곡선 연속성, 이전 보간의 비트 단위 동일성, 설정 재정의, 저장 형식 왕복을 자동 검사한다. 기존/신규 JAR 사이에서 캠페인 직렬화와 HP 해시가 양방향 일치했다. 라이브 안전 리로드를 수행했다는 뜻은 아니다.', '',
              '타워·강화·뽑기·판매·특성은 이번에 바꾸지 않았다. 진행 중 세션은 저장된 캠페인 설정을 유지하며 새 곡선은 새 세션부터 적용된다. beta 배포 후 로컬·오라클 update 폴더에 준비하고 서버 리로드·재시작은 하지 않는다.', '',
              '전체 조합이나 실제 이용자의 플레이를 대표하지 않으며 10만 판 검증도 아니다. 무특성 결과와 최고 4특성 결과를 분리해 공개한다. 재현: ProgressionBenchmarkMain 8192 44000000 OUTPUT none,top_fusion_four --threads 12 --cap 10000. 피해 재분석은 benchmarks/balance/analyze_damage_share.py, 이 보고서는 report_smooth_growth.py로 생성한다.', '']
    (output / 'REPORT.md').write_text('\n'.join(lines), encoding='utf-8')
    manifest = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(output.iterdir()) if p.is_file() and p.name != 'manifest.json'}
    manifest['simulationJar'] = hashlib.sha256((ROOT / '.runtime/smooth-curve-final/validated.jar').read_bytes()).hexdigest()
    manifest['deliveryJar'] = hashlib.sha256((ROOT / '.runtime/smooth-curve-final/delivery.jar').read_bytes()).hexdigest()
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
    print('Report and manifest written:', output)


if __name__ == '__main__':
    main()
