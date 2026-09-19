"""Record biome-route balance parity and real server appearance validation."""
import json
import shutil
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/simulation/biome-route-0.9.0'
BASE=ROOT/'docs/simulation/economy-0.9.0/validation/summary.json'
THEMED=ROOT/'target/biome-validation-20k/summary.json'
SMOKE=ROOT/'.runtime/biomes-smoke7'

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    baseline=json.loads(BASE.read_text())
    themed=json.loads(THEMED.read_text())
    smoke=json.loads((SMOKE/'biome-smoke.json').read_text())
    assert baseline['runs']==themed['runs']==20_000
    assert baseline['seedStart']==themed['seedStart']==9_300_000
    assert smoke=={'enemyTypes':49,'biomes':67,'ticks':220,'spawnMoveRemove':True,'dragonPartsProtected':True}
    for name,data in [('baseline',baseline),('themed',themed)]:
        (OUT/f'{name}-summary.json').write_text(json.dumps(data,indent=2)+'\n')
    (OUT/'server-smoke.json').write_text(json.dumps(smoke,indent=2)+'\n')
    shutil.copy2(ROOT/'target/waves-biome-route.json',OUT/'waves.json')
    def checkpoint(data,round):return next(x['rate'] for x in data['checkpoints'] if x['round']==round)
    lines=['# 0.9.0 바이옴·구조물 100라운드 경로','',
        '확정된 0.9.0 경제·체력 곡선을 유지하면서 적의 외형과 라운드 이름만 바이옴·구조물 경로에 맞게 바꿨습니다. 시뮬레이터 전투 규칙은 외형에 의존하지 않으므로 일반 라운드의 체력·속도·보상·출현 시점은 기준 편성과 같습니다.','',
        '| 지표 | 경제 기준 | 바이옴 경로 | 차이 |','|---|---:|---:|---:|']
    for label,key in [('클리어','clearRate'),('30R 생존',30),('50R 생존',50),('70R 생존',70),('90R 생존',90)]:
        old=baseline[key] if isinstance(key,str) else checkpoint(baseline,key)
        new=themed[key] if isinstance(key,str) else checkpoint(themed,key)
        lines.append(f'| {label} | {old:.3%} | {new:.3%} | {(new-old)*100:+.2f}%p |')
    lines += ['', '## 구성','',
        '- 1~55R: 오버월드 55개 고유 바이옴. 평야·숲·습지·건조 지대·산악·하천·해양·동굴 순서로 이동합니다.',
        '- 56~65R: 깊은 어둠과 고대 도시. 59·62·65R은 워든 1마리, 64R은 2마리를 시간차로 배치합니다. 워든 1마리는 일반 적 4마리의 체력·보상 예산을 합쳐 낮은 이동 속도로 등장합니다. 60R은 워든 보스입니다.',
        '- 66~80R: 네더 황무지·영혼 모래 골짜기·진홍/뒤틀린 숲·현무암 삼각주·보루·요새. 80R 위더 보스.',
        '- 81~90R: 공허·엔드 섬·불모지·중지대·고지대. 90R 엔더 드래곤 보스.',
        '- 91~100R: 외곽 섬·엔드 고지대·엔드 시티·엔드 함선. 100R 셜커 수호자 보스.',
        '- 설치된 Paper 26.3 레지스트리의 67개 바이옴 ID를 전부 정확히 한 번 이상 주 테마로 사용합니다. 적 외형은 총 49종입니다.',
        '', '## 검증','',
        '- 기준과 동일한 SHA-256 counter 난수, 20,000개 독립 시드(9,300,000~9,319,999), BALANCED 자동 배치로 비교했습니다.',
        '- 실제 Paper 26.3-mud localhost 서버에서 49종 전부를 생성·경로 이동·제거했습니다. 67개 바이옴 키 존재, 엔더 드래곤 파트 보호도 확인했습니다.',
        '- 셜커는 바닐라가 좌표를 블록 중앙으로 강제하므로 연속 경로 이동을 위한 Paper 26.3 전용 위치 어댑터를 사용합니다. 서버 버전이 바뀌면 이 어댑터의 계약을 재검증해야 합니다.',
        '- 실제 화면 렌더링, 다수 관전자 네트워크, 장시간 서버 부하는 이 검증 범위에 포함하지 않습니다.',
        '', '편성 원본은 [waves.json](waves.json)에 보관합니다.']
    (OUT/'REPORT.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')

if __name__=='__main__':main()
