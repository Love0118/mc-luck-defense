"""Merge common-prefix and suffix simulations with an explicit coverage audit."""
import argparse,json,math
from pathlib import Path

def rows(path):
    return {r['seed']:r for r in map(json.loads,path.read_text(encoding='utf-8').splitlines())}

def main(a):
    base=rows(a.prefix/'runs.jsonl');tail=rows(a.suffix/'runs.jsonl')
    eligible={s for s,r in base.items() if r['round']>a.branch}
    assert set(tail)==eligible,(len(tail),len(eligible),set(tail)^eligible)
    # Identical health/price/AI up to the branch is necessary for this decomposition.
    before=json.loads((a.prefix/'rules.json').read_text());after=json.loads((a.suffix/'rules.json').read_text())
    def anchors(config):return [(int(x.split(':')[0]),float(x.split(':')[1])) for x in config['healthCurve'].split(',')]
    assert [(r,h) for r,h in anchors(before) if r<=a.branch]==[(r,h) for r,h in anchors(after) if r<=a.branch]
    assert any(r==a.branch for r,h in anchors(before)) and any(r==a.branch for r,h in anchors(after))
    assert before['healthScale']==after['healthScale'] and before['bossScale']==after['bossScale']
    assert before['policy']==after['policy']
    expected=set(range(before['seedStart'],before['seedStart']+before['runs']))
    assert set(base)==expected,"Missing or duplicated seed coverage"
    for seed in eligible:
        old={c['round']:c for c in base[seed]['checkpoints'] if c['round']<=a.branch}
        new={c['round']:c for c in tail[seed]['checkpoints'] if c['round']<=a.branch}
        assert old==new,(seed,"Shared prefix drift")
    merged=base|tail;n=len(merged)
    checks=[]
    for round in [30,60,100,200,300,500,750,1000,1500,2000]:
        count=sum(r['round']>=round for r in merged.values());p=count/n;z=1.96;d=1+z*z/n
        c=(p+z*z/(2*n))/d;m=z*math.sqrt(p*(1-p)/n+z*z/(4*n*n))/d
        checks.append(dict(round=round,count=count,rate=p,ci95=[max(0,c-m),min(1,c+m)]))
    summary=dict(runs=n,seedStart=min(merged),branchRound=a.branch,rerunSurvivors=len(tail),exactPrefixAudit=True,checkpoints=checks,
        survivors=[r for r in merged.values() if r['round']>=2000],healthCurve=after['healthCurve'])
    a.output.mkdir(parents=True,exist_ok=True)
    (a.output/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    (a.output/'runs.jsonl').write_text('\n'.join(json.dumps(merged[s],ensure_ascii=False) for s in sorted(merged))+'\n',encoding='utf-8')
    lines=['# 0.14.0 장기 생존 난이도 재조정','',
        f"실제 전투를 계산한 {n:,}개 시드 ({min(merged)}~{max(merged)})의 결과입니다. 30R 90%, 60R 70%, 100R 이전 수준(0.12 비교값 13.75%), 2,000R 10만 회 중 1~2회를 목표로 조정했습니다.",'',
        '| 도달 라운드 | 도달 횟수 | 도달률 | Wilson 95% 구간 |','|---:|---:|---:|---:|']
    for c in checks:lines.append(f"| {c['round']:,} | {c['count']:,}/{n:,} | {c['rate']*100:.4f}% | {c['ci95'][0]*100:.4f}–{c['ci95'][1]*100:.4f}% |")
    lines+=['','## 검증 방법과 해석','',
        '적 생성·누적 경로 이동·공격·재화·뽑기·판매·합성·승급을 실제 서버와 같은 Java 코어로 틱마다 계산했습니다. 라운드 강제 통과나 확률로 승패를 직접 결정하는 처리는 없습니다.',
        f'500R까지 규칙이 같은 공통 실행에서 전체 {n:,}판을 계산했습니다. 500R을 넘긴 {len(tail):,}판은 최종 후반 곡선으로 처음부터 다시 실행했습니다. 모든 시드 포함 여부와 500R 이전 골드·소환·판매·진 태초 기록이 일치하는지 자동 감사했습니다. 500R 이전에 탈락한 판은 최종 곡선에서도 같은 결과이므로 그대로 합쳤습니다.',
        '자동 플레이어는 미래 뽑기를 보지 않으며 2틱마다 최대 한 번 거래·이동합니다. 슬롯이 가득 차면 효율이 낮은 판매 가능 유닛부터 판매합니다. 사람의 조작 수준·배속별 입력 여유·자동판매/자동배치 설정에 따른 실제 서버 전체 성공률과 같다고 보장하지 않습니다.',
        '이 표본은 난이도 조정에 사용한 보정 표본입니다. 별도의 미사용 10만 시드 검증을 완료한 것으로 해석하면 안 됩니다. 1~2건의 희귀 성공만으로 실제 확률을 정확히 확정할 수 없으며 위 신뢰구간에도 보정 과정의 영향은 포함되지 않습니다.',
        '기존 0.13.0의 2,000R 56.8% 결과는 과거 규칙의 기록이며 현재 결과가 아닙니다.','',
        '## 유지한 규칙','',
        '- 100라운드마다 필드·적 종류·마릿수·보스 구성이 반복됩니다.',
        '- 101R부터 100골드, 상위 4등급 확률 10배, +20 승급과 진 태초 능력치는 유지합니다.',
        '- 골드 보상·특수효과·소환 확률은 이번 난이도 조정으로 변경하지 않습니다.',
        '- 체력만 명시적 기준점으로 조정하고 사이 라운드는 기하 보간합니다. 2,000 이후는 마지막 기준점에서 제곱 비율로 연장합니다.','',
        '## 체력 기준점','',after['healthCurve'],'','## 2,000라운드 도달 시드','']
    for r in summary['survivors']:lines.append(f"- {r['seed']}: 도달 {r['round']}R / 생존 완료 {r['completedRounds']}R / 최고 등급 {r['maxGrade']}")
    (a.output/'REPORT.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print(json.dumps(summary,ensure_ascii=False))

if __name__=='__main__':
    p=argparse.ArgumentParser()
    for name in ['prefix','suffix','output']:p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--branch',type=int,default=500)
    main(p.parse_args())
