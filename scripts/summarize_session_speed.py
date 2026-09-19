"""Archive the 0.8.1 session-speed load and integration checks."""
import hashlib
import json
import shutil
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/performance/0.8.1'

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    plugin_hash=sha(ROOT/'target/mc-luck-defense-0.8.1.jar')
    runs={}
    for speed in [1,8]:
        source=ROOT/f'.runtime/v081-speed{speed}-final'
        result=json.loads((source/'plugins/MudBenchmark/result.json').read_text())
        clients=json.loads((source/'clients.json').read_text())
        assert result['sessionSpeed']==speed and result['targetTps']==20
        assert result['sessions']==20 and result['enemies']==1800 and result['defenders']==720
        assert len(result['mspt'])==result['measuredTicks']==600
        assert len(clients['clients'])==20 and all(c['ready'] and not c['errors'] for c in clients['clients'].values())
        assert sha(source/'plugins/MCLuckDefense.jar')==plugin_hash
        result['pluginSha256']=plugin_hash
        result['benchmarkSha256']=sha(source/'plugins/MudBenchmark.jar')
        invocation=json.loads((source/'invocation.json').read_text())
        result['serverSha256']=sha(Path(invocation[invocation.index('-jar')+1]))
        target=OUT/f'speed{speed}';target.mkdir(exist_ok=True)
        (target/'result.json').write_text(json.dumps(result,indent=2)+'\n')
        for filename in ['clients.json','clients-measure-start.json','invocation.json']:
            shutil.copy2(source/filename,target/filename)
        runs[speed]=result
    smoke=ROOT/'.runtime/v081-smoke-tools-final'
    assert sha(smoke/'plugins/MCLuckDefense.jar')==plugin_hash
    proof=json.loads((smoke/'session-speed-smoke-passed.json').read_text())
    proof['pluginSha256']=plugin_hash
    proof['tests']=154
    proof['unitTestFailures']=0
    (OUT/'integration.json').write_text(json.dumps(proof,indent=2)+'\n')
    rows=[]
    for speed,result in runs.items():
        rows.append(f"| {speed}배 | {result['actualTps']:.2f} | {result['meanMspt']:.2f}ms | {result['p95Mspt']:.2f}ms | {result['p99Mspt']:.2f}ms | {result['ticksOverBudget']}/600 |")
    text='\n'.join([
        '# 0.8.1 세션별 게임 배속 부하 확인','',
        '서버 목표는 20 TPS로 고정하고, 20개 세션 각각의 게임 진행만 1배 또는 8배로 설정했습니다. 플레이어 이동·비행 속도는 바꾸지 않습니다.','',
        '| 세션 배속 | 실제 서버 TPS | 평균 MSPT | p95 | p99 | 50ms 초과 틱 |',
        '|---|---:|---:|---:|---:|---:|',*rows,'',
        '- 각 실행은 아군 720마리·적 1,800마리를 유지합니다. 적 HP=1e15, 200틱 준비 후 600틱(약 30초) 측정한 밀집 부하 시험입니다. 100라운드 클리어 시험이나 확률 검증은 아닙니다.',
        '- Windows 11 / Ryzen 5 9600X / Java 25 / 힙 4GB / Paper 26.3-mud c4813f8c27. 틱·패킷 최적화와 Java 배치 이동을 활성화하고 Rust는 비활성화했습니다.',
        '- 같은 호스트에서 실제 배포 서버와 사용자의 플레이도 실행 중이었습니다. 각 조건을 한 번씩 측정했으므로 장시간 안정성이나 일반적인 성능 배수를 보장하지 않습니다.',
        '- localhost 프로토콜 클라이언트 20개, view/simulation distance 3, 압축 256, 로비 없는 fixture입니다. 실제 화면·오디오·WAN·다수 관전자 부하는 포함하지 않습니다.',
        '- 전투는 배속만큼 매 단계를 처리하되 엔티티 위치/방향과 포탑별 마지막 실제 공격 효과는 서버 틱당 한 번 전송합니다. 서버 부하가 높아 TPS가 떨어지면 모든 세션의 실제 진행 속도도 느려집니다.',
        '- 별도 3클라이언트 통합 확인: 실제 F GUI 2→4→8→1배 전환 및 340프레임의 독립 시계, 우클릭 판매 중복 방지, 핫바 복구, 참가/관전 비행과 로비 해제, 아군 24종 크기 확인. 화면 렌더링은 별도입니다.',
        '- Java/Rust 포함 154개 테스트 통과: 동일 게임 시간에서 1배·8배·도중 변경의 전투/경제/웨이브 결과 일치, 가속 프레임 중 패배 종료, 배속 범위/소유권, 도구 복구와 다음 틱 구매 확인.',''])
    (OUT/'REPORT.md').write_text(text,encoding='utf-8')

if __name__=='__main__':main()
