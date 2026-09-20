"""Generate the draft list from the implemented stable achievement catalog."""
import re
from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=(root/'src/main/java/dev/moma/core/AchievementCatalog.java').read_text(encoding='utf-8')
text=['# 서버 도전과제 초안 · 50개','',
'기본 J키 도전과제 화면에 서버 전용 트리로 표시합니다. 일반 과제는 기본 토스트, 고난도 과제는 챌린지 프레임·토스트와 Minecraft 기본 완료음을 사용합니다. 보상 재화나 능력치는 없습니다.','',
'라운드는 입장한 최고 라운드, 소환은 **정확히 해당 등급**의 누적 성공 횟수입니다. 새 게임·재접속·정상 서버 재시작 후에도 유지합니다. 자동판매·동일 유닛 합성 결과도 성공한 뽑기 1회로 셉니다. 구매 실패와 관리자 개입 판의 이후 진행은 제외합니다.','',
'설치 이전 누적 소환 기록은 없으므로 소급 계산하지 않습니다. 기본 도전과제의 화면에 표시되는 진행을 초기화하고 신규 획득을 차단하며, 레시피 해금은 유지합니다. 서버 전용 도전과제는 플레이어별로 한 번씩만 완료됩니다.','',
'신화·태초 소환은 서버 전체 채팅 방송과 접속자 전원에게 소리를 전달합니다. 전설·에픽은 소환자의 기존 피드백만 유지합니다. 도전과제 최초 달성과 태초 소환이 동시에 발생하면 각각의 알림이 발생할 수 있습니다.','',
'| 번호 | 이름 | 조건 | 알림 |','|---:|---|---|---|']
index=0
for metric,thresholds,names,hard in re.findall(r'add\(entries, Metric\.(\w+), new long\[\]\{([^}]+)\},\s*new String\[\]\{([^}]+)\},(\d+)\)',source):
    for target,title in zip(map(int,thresholds.split(',')),re.findall(r'"([^"]+)"',names)):
        index+=1
        label={'EPIC':'에픽','MYTHIC':'신화','PRIMORDIAL':'태초'}.get(metric)
        condition=f'{target:,}라운드 도달' if metric=='ROUND' else f'{label} 누적 {target:,}회 소환'
        text.append(f'| {index} | {title} | {condition} | {"챌린지 + 완료음" if target>=int(hard) else "일반 토스트"} |')
assert index==50,index
text+=['','무한 라운드에 맞춰 2,000라운드까지 초안을 배치했습니다. 상위 과제의 달성 난이도는 무한 모드 실측 이후 조정할 대상이며, 검증된 달성률을 뜻하지 않습니다.']
(root/'docs/achievements-draft.md').write_text('\n'.join(text)+'\n',encoding='utf-8')
