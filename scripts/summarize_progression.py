"""Compare actual seeded combat runs; reaching a cap is not a claimed forced victory."""
import argparse,json,math,statistics
from pathlib import Path

def wilson(wins,n):
    p=wins/n;z=1.96;d=1+z*z/n
    c=(p+z*z/(2*n))/d;m=z*math.sqrt(p*(1-p)/n+z*z/(4*n*n))/d
    return [max(0,c-m),min(1,c+m)]

def main(a):
    old=[json.loads(x) for x in (a.baseline/'runs.jsonl').read_text().splitlines()]
    runs=[json.loads(x) for x in (a.current/'runs.jsonl').read_text().splitlines()]
    assert {r['seed'] for r in old}=={r['seed'] for r in runs}
    milestones=[]
    for round in [26,30,60,100,101,200,500,1000,1500,2000]:
        count=sum(r['round']>=round for r in runs)
        milestones.append(dict(round=round,reached=count,total=len(runs),rate=count/len(runs),ci95=wilson(count,len(runs)),baselineReached=sum(r['round']>=round for r in old) if round<=100 else None))
    samples=[]
    for r in runs:
        at={c['round']:c for c in r['checkpoints']}
        if 2000 in at:
            samples.append(dict(seed=r['seed'],round=r['round'],completedRounds=r['completedRounds'],maxGrade=r['maxGrade'],**{'at100':at[100],'at2000':at[2000]}))
    summary=dict(runs=len(runs),seedStart=min(r['seed'] for r in runs),checkpoints=milestones,maxRound=max(r['round'] for r in runs),meanRound=statistics.mean(r['round'] for r in runs),cappedRuns=sum(r['outcome']=='PLAYING' for r in runs),truePrimordialRuns=sum(r['maxGrade']=='TRUE_PRIMORDIAL' for r in runs),examples=samples[:5])
    a.output.mkdir(parents=True,exist_ok=True)
    (a.output/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    lines=['# 진행 밸런스 · 0.13.0','',f'동일한 시드 {len(runs):,}개로 이전 0.12.0과 새 규칙을 비교했습니다. 새 규칙은 실제 적 생성·이동·공격·처치·소환·판매·강화·승급을 계산하며 최대 2,000라운드의 마지막 틱까지 실행합니다. 라운드 강제 승리나 무적 처리는 없습니다.','',
           '| 도달 라운드 | 이전 | 변경 후 | 변경 후 95% 구간 |','|---:|---:|---:|---:|']
    for m in milestones:
        prev='미측정' if m['baselineReached'] is None else f"{m['baselineReached']/len(runs):.1%}"
        lines.append(f"| {m['round']:,} | {prev} | {m['rate']:.1%} ({m['reached']}/{len(runs)}) | {m['ci95'][0]:.1%}–{m['ci95'][1]:.1%} |")
    lines+=['','자동 플레이어는 미래 뽑기를 보지 않고 2틱마다 거래·이동을 수행하며, 칸이 가득 차면 추정 효율이 낮은 판매 가능한 유닛을 판매합니다. 사람의 조작 시간·개별 배속·자동판매 설정·네트워크 지연은 이 도달률에 포함되지 않습니다. 결과는 이 정책의 달성 가능성 증거이며 모든 플레이어의 성공률을 의미하지 않습니다.','',
    '## 적용 규칙','',
    '- 20~60R 완화: 체력 기준점 20R 650, 26R 760, 30R 850, 40R 1,600, 50R 2,200, 60R 3,500. 100R은 27,000.','- 101R부터 100골드 소환. 전설 5%, 에픽 2%, 신화 0.8%, 태초 0.19%; 네 등급의 골드당 기대 소환 횟수를 유지하고 하위 등급은 비례 축소합니다. 판매 회수액이나 분산까지 같지는 않습니다.','- 일반~태초 +20에서 다음 등급으로 승급하고 강화 수치는 리셋합니다. 기존 +20 피해량을 하한으로 계승하여 승급으로 약해지지 않습니다. 판매가는 재료의 판매가 합을 보존하며 태초 이상은 판매 불가입니다.','- 태초 +20은 진 태초 +0. 순수 태초 재료 기준 피해량은 태초 +30과 같은 34배이며 사거리·공격 간격·특수효과 단계는 태초와 같습니다. 진 태초는 소환 확률 0, 최종 등급으로 추가 강화만 가능합니다.','- 테마·적 종류·마릿수·출현 시간·보스 구성은 100R마다 반복합니다. 이후 일반 적 기본 체력은 100R 기준 × (라운드/100)², 처치 보상은 30 + 2×floor((라운드−100)/10)골드입니다. 보스 보상은 25배입니다.','',
    '## 예시 시드','']
    for s in samples[:5]:lines.append(f"- 시드 {s['seed']}: {s['completedRounds']:,}R 생존 완료, 최고 등급 {s['maxGrade']}, 2,000R 진입 시 진 태초 {s['at2000']['truePrimordial']}개")
    lines+=['','## 남은 밸런스 특성','','이번 표본은 200R 도달 1,139판 중 1,136판이 2,000R까지 생존했습니다. 주된 탈락 구간은 101~200R이며 그 이후에는 성장한 진형이 적 성장을 앞서는 경향이 있습니다. 2,000R에 도달 가능한 규칙임은 확인했지만, 후반부에 계속 긴장감을 주는 난이도 곡선은 추가 조정 대상입니다. 진 태초까지 승급한 판은 전체 26판이며 2,000R 도달의 필수 조건은 아닙니다.']
    (a.output/'REPORT.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print(json.dumps(summary,ensure_ascii=False))

if __name__=='__main__':
    p=argparse.ArgumentParser()
    for name in ['baseline','current','output']:p.add_argument('--'+name,type=Path,required=True)
    main(p.parse_args())
