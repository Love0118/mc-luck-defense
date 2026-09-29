import gzip
import hashlib
import json
from pathlib import Path
import shutil
import sys

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'traits'))
from summarize_progression import analyze, wilson
from rebalance_economy import calculate


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def records(path):
    with gzip.open(path,'rt',encoding='utf-8') as f:
        return [json.loads(line) for line in f]


def main(baseline, candidate, audit, output):
    output.mkdir(parents=True,exist_ok=True)
    (output/'.gitattributes').write_text('* -text\n',encoding='utf-8')
    counts=[]
    for name,folder in [('baseline',baseline),('candidate',candidate)]:
        rules=dict(line.split('=',1) for line in (folder/'rules.txt').read_text().splitlines())
        count=int(rules['runs']);counts.append(count)
        analyze(folder,count,output/name/'analysis')
        for file in ['rules.txt','summary.csv','performance.csv','none.jsonl.gz','top_fusion_four.jsonl.gz','none-traits.txt','top_fusion_four-traits.txt']:
            shutil.copy2(folder/file,output/name/file)
    old={n:records(baseline/(n+'.jsonl.gz')) for n in ['none','top_fusion_four']}
    new={n:records(candidate/(n+'.jsonl.gz')) for n in old}
    for name in old:
        assert [x['seed'] for x in old[name]]==[x['seed'] for x in new[name][:counts[0]]]
    stats=load(audit/'stats.json');combat=load(audit/'combat.json')
    idx={(r['unit'],r['rarity'],r['enhancement'],r['bonus']):r for r in stats}
    units=list(dict.fromkeys(r['unit'] for r in stats))
    ci={(r['unit'],r['rarity'],r['enhancement'],r['scenario']):r for r in combat}
    comparisons=[]
    for u in units:
        comparisons.append(dict(unit=u,name=idx[u,'COMMON',0,0]['name'],
            legendary0_over_common9=idx[u,'LEGENDARY',0,0]['rawDps']/idx[u,'COMMON',9,0]['rawDps'],
            legendary0_over_common19_max_trait=idx[u,'LEGENDARY',0,30]['rawDps']/idx[u,'COMMON',19,30]['rawDps'],
            primordial0_over_mythic19_max_trait=idx[u,'PRIMORDIAL',0,30]['rawDps']/idx[u,'MYTHIC',19,30]['rawDps'],
            moving_normal=ci[u,'LEGENDARY',0,'single_normal']['dps']/ci[u,'COMMON',9,'single_normal']['dps'],
            moving_boss=ci[u,'LEGENDARY',0,'single_boss']['dps']/ci[u,'COMMON',9,'single_boss']['dps'],
            moving_crowd48=ci[u,'LEGENDARY',0,'crowd_48']['dps']/ci[u,'COMMON',9,'crowd_48']['dps']))
    assert all(x[k]>1 for x in comparisons for k in ['legendary0_over_common9','legendary0_over_common19_max_trait','primordial0_over_mythic19_max_trait'])
    (output/'unit-comparisons.json').write_text(json.dumps(comparisons,ensure_ascii=False,indent=2),encoding='utf-8')
    for name in ['stats.json','combat.json','promotions.json','metadata.json']:
        with gzip.open(output/(name+'.gz'),'wb') as f:f.write((audit/name).read_bytes())
    economy=calculate();(output/'economy.json').write_text(json.dumps(economy,ensure_ascii=False,indent=2),encoding='utf-8')
    lines=['# 1.0.15 등급·경제 재조정 검증','',
        '강화·승급·자동 배치 코드는 변경하지 않았다. 일반~신화 공격력 간격과 소환 경제를 조정하고, 초중반 적 체력을 재조정했다. 태초·진 태초·미라클의 공격력·공속·사거리·특수효과 및 1,000라운드 이후 적 체력·보상은 기존과 같다.','',
        '## 등급 공격력 배율','',
        '| 등급 | 이전 | 변경 |','|---|---:|---:|']
    for name,a,b in zip(['일반','레어','고대','유물','서사','전설','에픽','신화','태초','진 태초','미라클'],
                        [1.75,2.5,3.5,5,7,8,24,120,4800,480000,48000000],
                        [1,2,4,8,16,32,80,180,4800,480000,48000000]):
        lines.append(f'| {name} | {a:g} | {b:g} |')
    lines+=['','일반·레어를 함께 조정해 위 등급까지 무조건 상향만 누적되지 않도록 했다. 같은 종 기준 전설 +0는 일반 +9의 기본 DPS보다 3.81~4.27배 강하다. 최대 강화 특성을 적용한 일반 +19보다도 1.47~1.65배 강하다. 24종 모두 검증했다. 사거리·특수효과를 포함한 864개 엔진 실험에서도 일반 +9 역전은 해소됐다.','',
            '**승급 상속은 유지했다.** 같은 표시 등급·강화라도 합성 이력에 따른 공격력 차이는 남는다. 모든 하위 +19가 바로 다음 등급 +0보다 약한 설계도 아니다. 이번 범위는 일반 +9가 전설 +0를 넘던 과도한 등급 압축 해소다.','',
            '## 판매·소환 경제','',
            '등급별 판매 단가는 모든 라운드에 공통 적용한다. 에픽 400, 신화 1,000, 태초 1,500, 진 태초 30,000, 미라클 630,000골드이며 일반~전설은 기존 단가다. 이전 구매의 기록값은 유지하고 새 재료값만 더한다. [확률·판매가·계산 가정](../../summon-economy.md).','',
            '500R 소환 2,000→1,400골드, 1,000R 소환 5,000→3,300골드. 진 태초/미라클의 1회 확률 자체는 기존과 같다. 판매 환급을 반영한 목표 획득 순지출은 이전 대비 각각 -2.60%, -0.53%, -0.32%다. 판매 없이 골드 총액만 비교하면 획득이 더 쉬워지므로 두 기준을 혼동하지 않는다.','',
            '## 독립 시드 검증','',
            f'후보 조정용 시드 41,000,000대와 별도로, 42,000,000부터 연속 {counts[1]:,}개 시드를 무특성·최고 합성/피해/공속 4특성에 각각 실행했다. 새 밸런스 총 {2*counts[1]:,}판, 관측 상한 10,000R다. 최고 4특성은 전체 피해 +21%, 중복 확률 +25%p, 강화 +30%p, 공속 +18%와 모든 초반 패시브다. 가능한 모든 조합의 최적해를 증명한 것은 아니다.','',
            '| 라운드 | 무특성 | 최고 4특성 |','|---:|---:|---:|']
    rounds=[30,60,100,200,300,400,500,700,1000,1250,1500,2000,2500,3000,10000]
    for r in rounds:
        lines.append(f'| {r:,} | '+' | '.join(f"{sum(x['round']>=r for x in new[n])}/{len(new[n])} ({sum(x['round']>=r for x in new[n])/len(new[n])*100:.3f}%)" for n in new)+' |')
    lines+=['','## 동일 시드 이전 버전 비교','',f'아래는 두 버전 모두 실행한 처음 {counts[0]}개 시드만 비교한다. 전체 새 검증 표본과 분모가 다르다. 이전 전투 규칙은 공개 1.0.13 JAR(1.0.14와 관련 전투·경제 수치 동일), 실행기에는 동일한 4특성 시나리오를 제공했다.','',
            '| 라운드 | 이전 무특성 | 변경 무특성 | 이전 4특성 | 변경 4특성 |','|---:|---:|---:|---:|---:|']
    for r in rounds[:-1]:
        groups=[old['none'],new['none'][:counts[0]],old['top_fusion_four'],new['top_fusion_four'][:counts[0]]]
        lines.append(f'| {r:,} | '+' | '.join(f"{sum(x['round']>=r for x in group)/len(group)*100:.3f}%" for group in groups)+' |')
    lines+=['','## 후반 해석과 검증 범위','']
    for name,label in [('none','무특성'),('top_fusion_four','최고 4특성')]:
        rows=new[name];count=sum(x['round']>=2000 for x in rows);low,high=wilson(count,len(rows));longest=max(rows,key=lambda x:x['round']);capped=sum(x['outcome']=='PLAYING' for x in rows)
        lines.append(f"- {label}: 2,000R {count}/{len(rows)}, 95% Wilson 구간 {low:.4f}~{high:.4f}%. 최고 {longest['round']:,}R, 시드 {longest['seed']}, 종료 {longest['outcome']}. 관측 상한 종료 {capped}판.")
    lines+=['','**이 표본은 10만 판이 아니다. 2,000R 0.01~0.02%라는 과거 목표를 달성했다고 주장하지 않는다.** 4특성과 최신 판매/확률 조건에서 측정한 결과를 그대로 기록했다. 장비 보유·판매 정책에 따라 실제 수익률과 도달률은 달라진다. 태초~미라클의 수치가 같아도 진입 시 보유 기물과 합성량이 달라지므로 후반 도달률까지 자동으로 동일해지지는 않는다.','',
            '검증기는 모든 시드의 중복·누락·정렬, 종료 라운드·틱, 음수 재화, 상한 종료, 요약표 합계를 검사했다. 실제 CombatEngine으로 공격·소환·합성·배치·판매를 계산하고 틱을 건너뛰지 않았다. 대규모 게임은 로컬에서 실행했으며 CI에서는 기본 테스트만 실행한다.','',
            '414개 Java 테스트와 Python 결과 검증 테스트 3개를 통과했다. 전 등급·종 프로필 11,232개, 전투 조건 864개, 특수효과 792개 검증을 다시 실행했다. 판매가의 소급 변경 방지, GUI 가격·확률 일치, 1배/32배 상태 일치, 1,000~10,000R의 기존 적 체력·보상 일치를 포함한다.','',
            '## 적용','',
            '이번 버전은 밸런스 변경이다. 진행 중 세션의 저장된 능력치·적·재화·판매가를 임의로 재평가하지 않는다. 새 게임이 이 표의 전체 설정을 사용한다. 1.0.14부터는 /mud update force로 세션 유지 교체를 선택할 수 있지만 기존 판이 새 판의 시뮬레이션과 동일해지는 것은 아니다. 1.0.11~1.0.13은 이전 명령 해제 결함 때문에 최초 정상 재시작이 필요하다. 이번 작업은 beta 배포만 수행하며 운영 서버에 강제 적용하지 않는다.','',
            '원본: baseline/ 및 candidate/의 압축 게임별 결과·설정·특성 목록. analysis/에 도달률과 95% 구간이 있다. 프로필·전투 원본은 stats.json.gz, combat.json.gz, promotions.json.gz, 24종 핵심 비교는 unit-comparisons.json이다.']
    (output/'REPORT.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    for path in output.rglob('*'):
        if path.is_file() and path.suffix!='.gz':
            path.write_bytes(path.read_bytes().replace(b'\r\n',b'\n'))
    files={str(p.relative_to(output)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(output.rglob('*')) if p.is_file() and p.name!='manifest.json'}
    baseline_jar=Path(__file__).resolve().parents[2]/'.runtime/release-1.0.13/MCLuckDefense.jar'
    manifest=dict(audit=load(audit/'metadata.json'),baseline_jar_sha256=hashlib.sha256(baseline_jar.read_bytes()).hexdigest(),
                  baseline_runs=counts[0],candidate_runs=counts[1],sha256=files)
    (output/'manifest.json').write_bytes((json.dumps(manifest,ensure_ascii=False,indent=2)+'\n').encode('utf-8'))
    print(output/'REPORT.md')


if __name__=='__main__':
    main(*(Path(x) for x in sys.argv[1:]))
