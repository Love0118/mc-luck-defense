"""Verify and report the measured 0.9.0 economy datasets."""
import json
import statistics
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/'docs/simulation/economy-0.9.0'
TARGETS={30:.50,50:.30,70:.10,90:.05}
ROLES={'MELEE_SINGLE':'근접 단일','MELEE_CLEAVE':'근접 광역','RANGED_SINGLE':'원거리 단일','SMALL_AREA':'준광역','LARGE_AREA':'대광역','MULTI_TARGET':'다중'}

def dataset(name):
    summary=json.loads((DATA/name/'summary.json').read_text())
    rows=[json.loads(line) for line in (DATA/name/'runs.jsonl').read_text().splitlines()]
    assert len(rows)==summary['runs'] and len({r['seed'] for r in rows})==len(rows)
    assert sum(r['outcome']=='VICTORY' for r in rows)==summary['wins']
    for checkpoint in summary['checkpoints']:
        assert sum(r['completedRounds']>=checkpoint['round'] for r in rows)==checkpoint['survivors']
    return summary,rows

def main():
    summary,rows=dataset('validation')
    stress,_=dataset('one-primordial-stress')
    simple,_=dataset('auto-placement')
    example,example_rows=dataset('example-clear')
    calibration=json.loads((DATA/'calibration-summary.json').read_text())
    for other in [stress,simple,example,calibration]:
        for key in ['startingGold','summonCost','healthCurve','bossHealthScale','finalBossHealthMultiplier','blazeBaseDamage','wolfBaseInterval','regularRewardsByDecade','rarityDamageMultipliers','botTransactionIntervalTicks']:
            assert other[key]==summary[key],key
    properties=dict(line.split('=',1) for line in (ROOT/'src/main/resources/campaign.properties').read_text().splitlines() if line and not line.startswith('#'))
    assert int(properties['starting-coins'])==summary['startingGold']==30
    parse=lambda value:[(int(r),float(h)) for r,h in (part.split(':') for part in value.split(','))]
    assert parse(properties['health-curve'])==parse(summary['healthCurve'])
    assert stress['primordialCap']==1
    ex=example_rows[0]
    assert ex['outcome']=='VICTORY' and ex['primordial']==2 and ex['mythic']>=4
    roles=[json.loads(line) for line in (DATA/'roles.jsonl').read_text().splitlines()]
    lines=['# 0.9.0 · 30골드 시작 경제와 100라운드 밸런스','',
        '시작 골드를 100에서 **30골드**로 줄이고 초반 일반 적 보상을 **0.1골드**로 변경했습니다. 일반~서사 피해량을 높이고 적 체력 곡선을 다시 조정했습니다. 소환 비용 10골드, 등급 확률과 판매가는 동일합니다.','',
        '## 골드와 하위 포탑','',
        '| 라운드 | 일반 적 처치 | 보스 처치 |','|---|---:|---:|']
    for i,reward in enumerate(summary['regularRewardsByDecade']):lines.append(f'| {i*10+1}–{i*10+10} | {reward:g}골드 | {reward*25:g}골드 |')
    lines+=['','골드는 내부에서 0.1골드 단위 정수로 누적합니다. 0.1골드를 99회 받으면 9.9골드로 소환할 수 없고, 100회 받으면 정확히 10골드로 한 번 소환할 수 있습니다. 보상은 처치당 한 번 지급하며 소환 실패 시 차감하지 않습니다.','',
        '| 등급 | 이전 피해 배율 | 새 피해 배율 |','|---|---:|---:|']
    for label,old,new in zip(['일반','레어','고대','유물','서사'],[1,1.5,2.2,3.2,4],summary['rarityDamageMultipliers'][:5]):lines.append(f'| {label} | {old:g} | {new:g} |')
    lines+=['','블레이즈는 같은 대광역 타입에 비해 낮았던 기본 피해량을 10→12로 추가 보정했습니다. 늑대는 최상위 등급의 단독 클리어를 만든 공격 속도를 보정하여 기본 간격을 16→18틱으로 변경했습니다. 하위 등급 피해 상향을 포함하면 늑대의 일반 등급 기본 DPS도 이전보다 높습니다. 공격 타입·범위·등급 확률·판매 제한은 유지합니다.',
        '최종 구간의 태초 의존도를 유지하기 위해 태초 피해 배율을 2400→4800으로 높여 신화(120)와의 격차를 40배로 벌리고 R91~100 적 체력을 함께 조정했습니다. 일반~서사 상향이 마지막 라운드까지 상위 등급의 역할을 대체하지 않도록 검증합니다.','',
        '## 독립 검증','',
        f"조정 표본은 {calibration['seedStart']}부터 {calibration['runs']:,}회, 설정을 고정한 뒤 별도 시드 {summary['seedStart']}부터 {summary['runs']:,}회를 검증했습니다. 서버와 같은 전투·경제·웨이브 코어를 사용합니다.",'',
        '**자동 플레이 모델 기준**입니다. 1배 게임 시간에서 2틱마다 최대 한 번 거래하거나 이동하여 최대 초당 10회 조작합니다. 이는 1틱 간격 GUI 안에서 가능한 빠른 자동 플레이이며 실제 사람 전체의 상위 백분위가 아닙니다. 전장을 가득 채웠을 때 판매 후 재구매할 돈이 있어야 교체하고, 미래 뽑기나 시드를 미리 보지 않습니다. 배속에서 사람의 조작 시간은 별도 변수입니다.','',
        '| 기준 | 목표 | 관측 | 성공/전체 | Wilson 95% 구간 |','|---|---:|---:|---:|---:|']
    for c in summary['checkpoints']:
        target=f"{TARGETS[c['round']]:.0%}" if c['round'] in TARGETS else '초반 확인'
        lines.append(f"| R{c['round']} 종료 생존 | {target} | {c['rate']:.3%} | {c['survivors']:,}/{summary['runs']:,} | {c['ci95Low']:.2%}–{c['ci95High']:.2%} |")
    lines += [f"| 100라운드 클리어 | 약 1% | **{summary['clearRate']:.3%}** | {summary['wins']:,}/{summary['runs']:,} | {summary['ci95Low']:.3%}–{summary['ci95High']:.3%} |",'',
        '분모는 처음 시작한 모든 판입니다. 라운드 생존은 마지막 틱까지 생존한 경우이며, 최종 클리어는 제한 시간 안에 모든 적을 처치한 경우입니다. 승패를 확률로 강제하거나 태초 개수를 검사하지 않습니다. 최종 클리어 관측값은 목표 1%보다 어려운 편이며, 아래의 드문 태초 1개 예외가 있어 태초 2개 최소 조건을 보장하는 규칙은 아닙니다.',
        f"태초 2개 미만 클리어: {summary['winsBelowTwoPrimordials']}회. 별도 태초 1개 제한 실험: {stress['runs']:,}회 중 {stress['wins']}회 클리어. 태초 2개+신화 4개 이상 표본: {summary['twoPrimordialManyMythicRuns']}회 중 {summary['twoPrimordialManyMythicWins']}회 클리어.",
        f"수동 재배치 없는 자동 배치 비교: {simple['runs']:,}회 중 {simple['wins']}회 ({simple['clearRate']:.3%}). 낮은 조작 빈도나 다른 전략의 결과는 이 수치와 다를 수 있습니다.",'',
        f"[클리어 리플레이](example-clear/replay.html): 시드 {ex['seed']}, 태초 {ex['primordial']}개·신화 {ex['mythic']}개. 이전 경제의 리플레이와 통계는 과거 기록이며 새 밸런스 근거로 합산하지 않았습니다.",'',
        '## 적 체력','', '| 라운드 | 기본 체력 |','|---:|---:|']
    for round_,health in parse(summary['healthCurve']):lines.append(f'| {round_} | {health:g} |')
    lines += ['',f"기준점 사이를 기하 보간하고 종별 계수를 적용합니다. 보스는 기본 체력 × (15+라운드/5) × {summary['bossHealthScale']}; 100라운드 최종 보스는 추가 {summary['finalBossHealthMultiplier']:g}배입니다. 정확한 편성·보상은 [waves.json](waves.json)에 있습니다.",'',
        '## 낮은 등급 타입 비교','',
        '동일 등급, 가장 좋은 고정 배치에서 보스 1마리와 군집 48마리를 각각 비교한 일반 등급 평균입니다. 오버킬을 제외한 피해이며 감속·혼합 편성 시너지는 포함하지 않습니다.','',
        '| 타입 | 보스 DPS | 군집 DPS |','|---|---:|---:|']
    for role,label in ROLES.items():
        group=[r for r in roles if r['role']==role and r['rarity']=='COMMON']
        lines.append(f"| {label} | {statistics.mean(r['bossDps'] for r in group):.1f} | {statistics.mean(r['crowdDps'] for r in group):.1f} |")
    lines += ['', '모든 24종×9등급의 [측정값](roles.jsonl)을 함께 보관합니다. 보스 DPS와 군집 DPS를 각각 같은 타입 중앙값으로 나눈 평균이 0.75 미만인 하위 등급 종:']
    flagged=[]
    for rarity in ['COMMON','RARE','ANCIENT','RELIC','NARRATIVE']:
        for role in ROLES:
            group=[r for r in roles if r['role']==role and r['rarity']==rarity]
            boss=statistics.median(r['bossDps'] for r in group);crowd=statistics.median(r['crowdDps'] for r in group)
            for r in group:
                score=(r['bossDps']/boss+r['crowdDps']/crowd)/2
                if score<.75:flagged.append(f"- {rarity} {r['unit']}: {score:.2f}")
    lines += ['']+(flagged or ['해당 없음. 이 비교만으로 실제 플레이의 모든 조합이 동등하다는 뜻은 아닙니다.'])
    (DATA/'REPORT.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')

if __name__=='__main__':main()
