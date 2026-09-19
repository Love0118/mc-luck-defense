"""Archive the 0.8.0 measurements without overwriting earlier performance evidence."""
import hashlib
import json
import shutil
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/performance/0.8.0'

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    summaries={}
    for name in ['v080-dense-final','v080-campaign-final']:
        source=ROOT/'.runtime'/name
        result=json.loads((source/'plugins/MudBenchmark/result.json').read_text())
        workload=json.loads((source/'plugins/MudBenchmark/workload.json').read_text())
        clients=json.loads((source/'clients.json').read_text())
        assert result['sessions']==result['viewers']==20
        assert result['measuredTicks']==len(result['mspt'])
        assert len(clients['clients'])==20 and all(c['ready'] and not c['errors'] for c in clients['clients'].values())
        if workload['mode']=='campaign':
            assert len(workload['outcomes'])==20 and all(c['outcome']=='VICTORY' and c['round']==100 for c in workload['outcomes'])
        result['workload']=workload
        invocation=json.loads((source/'invocation.json').read_text())
        result['serverSha256']=sha(Path(invocation[invocation.index('-jar')+1]))
        result['pluginSha256']=sha(source/'plugins/MCLuckDefense.jar')
        assert result['pluginSha256']==sha(ROOT/'target/mc-luck-defense-0.8.0.jar')
        result['benchmarkSha256']=sha(source/'plugins/MudBenchmark.jar')
        result['jfrSha256']=sha(source/'plugins/MudBenchmark/profile.jfr')
        target=OUT/name;target.mkdir(exist_ok=True)
        (target/'result.json').write_text(json.dumps(result,indent=2)+chr(10))
        for file in ['clients.json','clients-measure-start.json','invocation.json']:shutil.copy2(source/file,target/file)
        summaries[name]={k:v for k,v in result.items() if k not in ['mspt','tpsWindows']}
    (OUT/'summary.json').write_text(json.dumps(summaries,indent=2)+chr(10))
    lines=['# 0.8.0 · 20세션 / 16배속 TPS 재측정','',
        '0.8.0 전투·표시 기능을 켠 실제 Paper 서버에 프로토콜 클라이언트 20개를 연결해 목표 tick rate 320으로 측정했습니다. 게임 세션 수를 20으로 제한하는 설정이 아니라 부하 시험의 동시 세션 수입니다.','',
        '| 부하 | 실제 TPS | 평균 MSPT | p95 | p99 | 3.125ms 초과 틱 |',
        '|---|---:|---:|---:|---:|---:|']
    for name,label in [('v080-dense-final','아군 720·적 1,800 지속'),('v080-campaign-final','100라운드 전체 · 20세션')]:
        r=summaries[name]
        lines.append(f"| {label} | {r['actualTps']:.2f} | {r['meanMspt']:.3f} | {r['p95Mspt']:.3f} | {r['p99Mspt']:.3f} | {r['ticksOverBudget']:,}/{r['measuredTicks']:,} ({r['ticksOverBudget']/r['measuredTicks']:.2%}) |")
    lines += ['',
        '## 조건', '',
        '- Windows 11 / Ryzen 5 9600X / Java 25 / 힙 4GB. 배포 서버 프로세스가 동시에 실행 중인 공유 호스트입니다.',
        '- 서버: Paper 26.3 build19 기반 26.3-mud c4813f8c27. MUD 틱·패킷 버퍼 최적화 활성화, Java 배치 이동, Rust 비활성화.',
        '- 플러그인: 0.8.0. scale=2.0, 아군 공격 방향·적 진행 방향 회전, 실제 판정 부채꼴/원/연결선 더스트, 타입별 공격음, 탭 TPS, 개인 선택 발광 핸들러, 시스템 채팅 처리 활성화. 채팅 부하는 이 TPS 실행에 주입하지 않았습니다.',
        '- 밀집 부하는 선택 발광 포탑을 각 세션에 하나씩 두고, 적 HP를 1e15로 고정해 부하가 사라지지 않게 했습니다. 3,200틱 준비 후 6,400틱 측정.',
        '- 캠페인은 실제 소환·판매·재배치·경제·100라운드 및 종료 정리를 실행했습니다. 3,200틱 준비 후 61,520틱 측정. 부하 유지용 동일 성공 시드 101476을 사용하여 모두 클리어하며, 클리어 확률 표본이 아닙니다.',
        '- 20개 localhost TCP 클라이언트, 압축 256, view/simulation distance 3. 로비를 비활성화한 전장 중심 fixture입니다. 배포 설정의 view-distance는 4입니다. 서버 틱 소요시간과 벽시계 TPS, 패킷 수신을 측정했으며 실제 화면 렌더링·오디오 재생·외부 네트워크 지연·로비 대기자·다수 관전자 부하는 포함하지 않습니다.',
        '- 비교적 짧은 단일 실행이므로 과거 수치와의 차이를 기능 자체의 향상률로 해석하지 않습니다. 과거 26.2 배경 서버 대신 현재 배포 서버가 실행 중인 환경입니다.',
        '- 버전 업그레이드·메타데이터 선택 재추적·공격음/시스템 채팅의 정확성은 별도 3클라이언트 smoke 및 Java/Rust parity 테스트로 확인했습니다. [검증 데이터](../../presentation-0.8.0-validation.json)',
        '', '틱 원본, 클라이언트 카운트, 실행 인수와 아티팩트 해시를 함께 보관합니다. 큰 JFR 파일은 해당 .runtime 실행 폴더에 남깁니다.']
    if any(r['ticksOverBudget']>0 for r in summaries.values()):lines[2:2]=['**320 TPS 지속 유지 기준은 미달입니다.** 평균 TPS와 개별 틱 예산 초과를 함께 확인해야 합니다.','']
    (OUT/'REPORT.md').write_text(chr(10).join(lines)+chr(10),encoding='utf-8')

if __name__=='__main__':main()
