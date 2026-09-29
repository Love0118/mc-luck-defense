# 1.0.14 — 안전 리로드의 명령 해제 실패 수정

2026-09-29 Oracle 서버 로그에서 22:41:27 KST(서버 UTC 13:41:27)에 `/mud update`가 기존 게임 1.0.11을 중단하는 단계에서 실패했다. `RebootCommand.remove()`의 `getKnownCommands().entrySet().removeIf(...)`가 `UnsupportedOperationException: remove`를 발생시켰다. Paper의 `BukkitBrigForwardingMap.EntrySet`은 스트림 iterator를 반환하여 iterator 제거를 지원하지 않는다.

이후 이전 런타임 재활성화도 `/reboot 명령어가 이미 등록되어 있습니다`로 실패했다. JVM은 살아 있었지만 런타임 중단 절차가 중간에 끊겨 게임 기능이 정상 동작하지 못했다. 이후 실행된 전체 Bukkit 리로드에서는 별도로 PlugManX 활성화 실패, ViaVersion 중복 로드 경고, 비동기 명령 트리 구성 중 ConcurrentModificationException이 기록됐다. 최초 원인은 전체 리로드가 아니라 우리 `/reboot` 해제 코드다.

수정: 소유한 명령의 이름들을 먼저 수집하고 Paper가 지원하는 `Map.remove(key)`로 제거한다. 등록 실패 정리 경로도 같은 방식으로 수정했다. 다른 플러그인의 명령은 제거하지 않는다.

기존 테스트는 HashMap 기반 SimpleCommandMap을 사용해 운영 서버의 iterator 제한을 놓쳤다. 회귀 테스트는 entry iterator 변경이 불가능하지만 key 제거는 가능한 map으로 수정했다. 실제 Paper 서버에서는 새 `/reboot`와 네임스페이스 별칭이 활성 런타임에 속하는지도 확인한다.

검증:

- Maven 전체 테스트 363개 통과.
- 실제 Paper 26.3 + Via 1.21.8 클라이언트 2개에서 새 코드 교체, 호환성 거절, 설정 오류 거절, 활성화 실패 자동 롤백, 명시적 롤백, 동일 버전 리로드 통과.
- 세션 fingerprint, 엔티티 UUID, 플레이어·관전 상태 보존 및 단일 게임 루프 확인.
- 클라이언트 오류 및 파티클 파싱 경고 0개.

현재 운영 중인 1.0.11의 해제 코드는 새 JAR를 적용하기 전에 실행된다. 따라서 이 버전에서 1.0.14로 처음 넘어갈 때는 정상 종료 후 수정 JAR로 재시작해야 한다. 이번 작업은 운영 서버 로그만 조회했으며 재시작·리로드·파일 교체를 실행하지 않았다. 리로드 실패로 이미 유실된 메모리 상태를 복원한다고 보장하지 않는다.
