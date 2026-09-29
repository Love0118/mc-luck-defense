import csv
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import sys
import zipfile

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'traits'))
from summarize_progression import analyze, wilson

LABELS=dict(COMMON='일반',RARE='레어',ANCIENT='고대',RELIC='유물',NARRATIVE='서사',LEGENDARY='전설',
            EPIC='에픽',MYTHIC='신화',PRIMORDIAL='태초',TRUE_PRIMORDIAL='진 태초',MIRACLE='미라클')


def rows(path):
    with gzip.open(path,'rt',encoding='utf-8') as f:return [json.loads(line) for line in f]


def main(root,output):
    output.mkdir(parents=True,exist_ok=True)
    (output/'.gitattributes').write_bytes(b'* -text\n')
    validation=root/'validation'
    settings=dict(line.split('=',1) for line in (validation/'rules.txt').read_text().splitlines())
    count=int(settings['runs']);assert count==8192
    analysis=analyze(validation,count,output/'analysis')
    for name in ['rules.txt','summary.csv','performance.csv','none.jsonl.gz','top_fusion_four.jsonl.gz','none-traits.txt','top_fusion_four-traits.txt']:
        shutil.copy2(validation/name,output/name)
    duel=list(csv.DictReader((root/'adjacent-combat.csv').open(encoding='utf-8')))
    assert len(duel)==1440 and all(float(x['ratio'])>1 for x in duel)
    assert len({(r['unit'],r['lower19'],r['bonus'],r['scenario']) for r in duel})==1440
    shutil.copy2(root/'adjacent-combat.csv',output/'adjacent-combat.csv')
    profiles=list(csv.DictReader((root/'profiles.csv').open(encoding='utf-8')))
    assert len(profiles)==10560
    with gzip.open(output/'profiles.csv.gz','wb') as f:f.write((root/'profiles.csv').read_bytes())
    with zipfile.ZipFile(root/'validated.jar') as jar:
        properties=dict(line.split('=',1) for line in jar.read('mud-runtime.properties').decode().splitlines() if '=' in line)
        assert properties['version']=='1.0.16'
        (output/'campaign.properties').write_bytes(jar.read('campaign.properties'))
    baseline=Path(__file__).resolve().parents[2]/'docs/balance/rebalance-1.0.15/candidate'
    old={name:rows(baseline/(name+'.jsonl.gz')) for name in ['none','top_fusion_four']}
    new={name:rows(output/(name+'.jsonl.gz')) for name in old}
    for name in old:
        assert [r['seed'] for r in old[name]]==[r['seed'] for r in new[name][:len(old[name])]]
    lines=['# 1.0.16 인접 등급 보장·초중반 재조정','',
        '같은 몹 종류에서 모든 인접 등급의 상위 +0가 하위 +19보다 강하도록 다시 설정했다. 최대 강화 특성 +30%p를 포함한다. 서로 다른 타입·사거리·공격 패턴의 모든 상황을 하나의 공격력 순위로 묶는 기준은 아니다.','',
        '## 타워 기준','',
        '일반→태초는 매 단계 기본 공격력 32배, 태초→진 태초→미라클은 기존 100배 간격이다. 하위 +19는 최대 강화 특성에서 27.2배, +20 직전은 29배이므로 다음 등급 기본 공격력이 둘 다 넘는다. 강화 공식은 수정하지 않았다. 새 규칙으로 생성·합성한 기물은 승급 시 숨은 상속 공격력이 생기지 않는다. 이전 버전에서 저장한 기물의 상속값을 강제로 제거하지는 않는다.','',
        '| 등급 | 기본 공격력 배율 |','|---|---:|']
    for i,(grade,label) in enumerate(LABELS.items()):
        value=4800/(32**(8-i)) if i<=8 else [480000,48000000][i-9]
        lines.append(f'| {label} | {value:.14g} |')
    lines+=['','태초·진 태초·미라클의 공격력·공속·사거리·특수효과는 1.0.15와 같다. 하위 절대 수치가 작은 것은 이 상위 수치를 고정하고 역산한 결과다. 선택 GUI에서 작은 피해가 0.0으로 보이지 않도록 4자리 유효숫자로 표시한다. 자동 배치는 실제 최고 점수를 기준으로 정규화하여 작은 피해량이 위치 선호 보정에 묻히지 않게 했다. 등급 우선 배치 정책은 같다.','',
        '## 실제 전투 비교','',
        '24종 × 인접 등급 10쌍 × 강화 특성 없음/최대 × 일반 적 1마리·보스 1마리·일반 적 48마리 = 1,440조건이다. 하위 +19와 상위 +0를 실제 CombatEngine으로 각각 실행했다. 6×6, 이동속도 초당 2블록, 사거리 노출이 가장 큰 고정 칸, 42초 예열 후 336초 측정이다. 적 체력은 충분히 크게 설정하여 과잉 피해를 제외했다.','',
        '| 인접 등급 | 무특성 상위/하위 DPS | 최대 강화 특성 상위/하위 DPS |','|---|---:|---:|']
    for grade in list(LABELS)[:-1]:
        group=[r for r in duel if r['lower19']==grade]
        values=[]
        for bonus in ['0','30']:
            ratio=[float(r['ratio']) for r in group if r['bonus']==bonus]
            values.append(f'{min(ratio):.3f}~{max(ratio):.3f}배')
        high=group[0]['higher0'];lines.append(f'| {LABELS[grade]} +19 → {LABELS[high]} +0 | '+' | '.join(values)+' |')
    lines+=['','전체 조건에서 역전 0건, 가장 작은 상위 우위는 1.17647배였다. 일반 +0를 1로 보면 일반 +19는 무특성 21.5, 최대 강화 특성 27.2, 레어 +0는 32다. 수식·실제 프로필·전투 계산을 각각 확인했다. 모든 종의 연속 승급도 새 등급 직접 소환 프로필과 일치했다.','',
        '## 독립 라운드 검증','',
        f'조정 시드는 43,000,000대, 검증 시드는 42,000,000~{42000000+count-1:,}다. 무특성 {count:,}판과 최고 합성·피해·공속 4특성 {count:,}판, 총 {count*2:,}판을 로컬 12스레드로 실행했다. 관측 상한은 10,000R이며 게임에 종료 조건을 추가한 것이 아니다.','',
        '4특성은 피해 +21%, 중복 기물 확률 +25%p, 강화 +30%p, 공속 +18%와 모든 초반 패시브다. 실제 플레이의 최적 조합 전체를 증명한 것은 아니다.','',
        '| 도달 라운드 | 무특성 | 최고 4특성 |','|---:|---:|---:|']
    checkpoints=[30,60,100,200,300,400,500,700,1000,1250,1500,2000,2500,3000,10000]
    for round_number in checkpoints:
        lines.append(f'| {round_number:,} | '+' | '.join(f"{sum(r['round']>=round_number for r in new[n])}/{count} ({sum(r['round']>=round_number for r in new[n])/count*100:.3f}%)" for n in new)+' |')
    lines+=['','## 같은 시드로 비교한 1.0.15 → 1.0.16','',
        '1.0.15에서 이미 완료한 2,048개 시드와 새 결과의 동일 시드만 비교했다. 원본은 ../rebalance-1.0.15/candidate/에 보관되어 있다.','',
        '| 도달 라운드 | 이전 무특성 | 변경 무특성 | 이전 4특성 | 변경 4특성 |','|---:|---:|---:|---:|---:|']
    for round_number in checkpoints[:-1]:
        groups=[old['none'],new['none'][:2048],old['top_fusion_four'],new['top_fusion_four'][:2048]]
        lines.append(f'| {round_number:,} | '+' | '.join(f"{sum(r['round']>=round_number for r in group)/len(group)*100:.3f}%" for group in groups)+' |')
    lines+=['','## 후반과 한계','']
    for name,label in [('none','무특성'),('top_fusion_four','최고 4특성')]:
        cohort=new[name];n=sum(r['round']>=2000 for r in cohort);lo,hi=wilson(n,count);longest=max(cohort,key=lambda x:x['round'])
        lines.append(f"- {label}: 2,000R {n}/{count}, 95% Wilson 구간 {lo:.4f}~{hi:.4f}%. 최고 {longest['round']:,}R(시드 {longest['seed']}, {longest['outcome']}).")
        assert all(r['outcome']!='PLAYING' or r['round']==10000 for r in cohort)
    lines+=['','이 검증은 10만 판이 아니다. 드문 2,000R 도달률의 과거 목표 0.01~0.02%를 확정했다고 주장하지 않는다. 시드 누락·중복·정렬, 판 수, 라운드/틱 관계, 재화, 결과 요약 합계를 검증했다. 95% 구간과 완료 라운드 수는 analysis/reach.csv에 있다.','',
        '400R 이후 적 체력·보상은 1.0.15와 같고, 태초~미라클 전투 수치도 같다. 초반 통과자와 보유 기물이 달라지므로 도달률까지 같아지는 것은 아니다. 판매가·뽑기 가격·확률·강화·공격 배속은 변경하지 않았다.','',
        '## 코드 검증·적용','',
        '442개 Java 테스트와 Python 결과 검증기 테스트 3개 통과. 인접 등급 24종 전수 비교, 모든 단계 연속 승급의 기본 프로필 일치, 태초~미라클 보호, 작은 피해량의 실제 처치·보상 지급, 배치 계산의 수치 단위 불변성, 작은 공격력 표시, 400~10,000R 기존 체력 곡선 보존을 포함한다.','',
        '밸런스 설정은 새 세션 기준이다. 기존 세션의 저장된 능력치·상속 공격력·판매가를 강제로 재평가하지 않는다. 진행 중 판에 force 업데이트를 하면 기존 값과 새 소환/합성 규칙이 혼재할 수 있으므로 이번 시뮬레이션과 동일한 판이 아니다. beta 배포만 수행하고 운영 서버에 강제 적용하지 않는다.','',
        '재현: benchmarks/balance/AdjacentGradeAudit.java와 report_adjacent.py, 본 폴더의 rules.txt·특성 목록·JAR SHA-256을 사용한다. 인접 전투 원본은 adjacent-combat.csv, 전체 강화 프로필 10,560개는 profiles.csv.gz에 있다.']
    (output/'REPORT.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    for path in output.rglob('*'):
        if path.is_file() and path.suffix!='.gz':path.write_bytes(path.read_bytes().replace(b'\r\n',b'\n'))
    files={str(p.relative_to(output)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(output.rglob('*')) if p.is_file() and p.name!='manifest.json'}
    manifest=dict(version=properties['version'],source_base_commit='2b3605a37de3f36e61f5bf3d153e672a3a6e013d',
                  tested_jar_sha256=hashlib.sha256((root/'validated.jar').read_bytes()).hexdigest(),
                  profiles=len(profiles),duel_cases=len(duel),validation_games=analysis['validated_games'],files=files)
    (output/'manifest.json').write_bytes((json.dumps(manifest,ensure_ascii=False,indent=2)+'\n').encode('utf-8'))
    print(output/'REPORT.md')


if __name__=='__main__':
    main(Path(sys.argv[1]),Path(sys.argv[2]))
