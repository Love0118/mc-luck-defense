# 등급·강화 밸런스 점검

[전체 보고서](../../docs/balance/enhancement-audit-1.0.13/report.md)와 [유닛별 수치 조회](../../docs/balance/enhancement-audit-1.0.13/report.html)는 실제 1.0.13 공개 JAR로 계산했다. 운영 플러그인의 전투 코드를 복사하거나 수정하지 않고 같은 클래스의 프로필·합성·승급·배치·전투를 호출한다.

저장소 루트에서 실행한다. JDK 25와 Python 3 표준 라이브러리를 사용한다. 다운로드 파일이 이미 있다면 첫 명령은 생략한다.

```powershell
gh release download beta-build-d9e1790e700d5c667103e500ae7629a46ffe844d --repo Love0118/mc-luck-defense --pattern MCLuckDefense.jar --dir .runtime/release-1.0.13
javac -encoding UTF-8 -cp '.runtime/release-1.0.13/MCLuckDefense.jar' -d target/enhancement-audit benchmarks/balance/EnhancementAudit.java
java -Xmx2G -cp 'target/enhancement-audit;.runtime/release-1.0.13/MCLuckDefense.jar' dev.moma.core.EnhancementAudit docs/balance/enhancement-audit-1.0.13 d9e1790e700d5c667103e500ae7629a46ffe844d
python -X utf8 benchmarks/balance/report_enhancement_audit.py docs/balance/enhancement-audit-1.0.13
```

범위는 직접 소환 출신의 24종·11등급·강화별 프로필과 무특성/최대 강화 특성이다. 미라클 강화는 상한이 없어 대표 추가 단계를 포함한다. 중간 강화 특성은 계수표에 모두 포함하며, 승급으로 물려받는 피해는 별도 표에서 다룬다.

전투 검증은 사거리 안의 특수효과·타깃 수 792건과 실제 경로 864조건이다. 경로 실험은 사거리 노출이 큰 고정 칸, 초당 2블록 이동, 42초 예열·336초 측정, 죽지 않는 적을 사용한다. 동일 종 일반 +9와 전설 +0의 비교에 쓰며, 실제 웨이브 도달률이나 처치 시간을 의미하지 않는다. 자세한 가정과 비교 제한은 보고서에 명시했다.

HTML 조회는 외부 서버 요청 없이 동작한다. 이 작업에서는 브라우저의 로컬 파일 정책으로 화면 미리보기를 열지 못해 JavaScript 문법과 데이터 구조를 검사했고, 브라우저 시각 검증은 하지 않았다.
