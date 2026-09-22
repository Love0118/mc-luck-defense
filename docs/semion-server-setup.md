# semion 서버 구성 — 2026-09-22

기존 Fabric 서버와 별도로 Paper 26.3 MC Luck Defense 0.16.0 서버를 설치했다.

| 항목 | 설정 |
|---|---|
| 외부 접속 주소 | `168.110.98.171:25566` — OCI 포트 개방 후 사용 |
| 실제 바인딩 | `10.0.0.217:25566` |
| 서버 경로 | `/home/ubuntu/mc-luck-defense` |
| 서비스 | `mc-luck-defense.service` |
| Java / 메모리 | OpenJDK 25 ARM64, 초기 2 GiB / 최대 4 GiB |
| CPU | 새 서버 `2`, 기존 Fabric·Velocity `0-1` |
| 자동 시작 | systemd 부팅 시 시작, 비정상 종료 시 재시작 |
| 인증 | `online-mode=true`, RCON·Query 비활성화 |
| 클라이언트 호환 | ViaVersion / ViaBackwards 5.12.0 설치 |

기존 Fabric의 내부 주소 `127.0.0.1:25566`과 새 서버의 네트워크 주소를 분리하여 포트 충돌을 피한다. 기존 외부 `25565` 및 프록시 구성은 유지한다.

## 후속 변경 적용

사용자 요청으로 Fabric과 Paper의 다음 시작 옵션을 모두 **`-Xms4G -Xmx6G`**로 변경했다. Fabric은 `steve-td.service.d/memory.conf`, Paper는 기존 서비스 파일에 저장했다. Velocity는 `-Xms256M -Xmx1G`를 유지한다. 변경 후 최대 JVM 힙 합계는 13 GiB다. 약 17 GiB 물리 메모리 중 나머지는 JVM 네이티브 메모리·OS·다른 서비스가 사용하므로 힙 제한만으로 OOM 방지를 보장하지는 않는다.

사용자의 명시적인 재시작 승인 후 2026-09-22 02:06 KST에 Fabric·Paper를 재시작했다. 새 JVM 실행 인자에서 두 서버 모두 4–6 GiB를 확인했다. Fabric의 서비스 연결 관계에 따라 Velocity도 함께 재시작했다. 기존 CPU 분리는 유지한다.

정상 재시작에서 MCLuckDefense 0.17.0과 ServerChatMirror 1.1.0이 활성화되었다. 개인 `/chatmirror on|off` 선택 및 접속 안내가 포함된다. 별도 수집기나 Fabric에는 챗 미러 코드 변경을 적용하지 않았다. 로컬 Windows 서버는 재시작하지 않고 두 JAR를 update 폴더에 준비했다.

두 서버의 시작 완료 로그를 확인했다. 당시 사용 가능 메모리는 약 9.9 GiB였다. swap과 서비스별 전체 메모리 상한은 아직 없으며 `vm.panic_on_oom=0`이다. Fabric의 리소스팩 셰이더 변환 오류는 9월 9일·12일 이전 로그에도 있는 별도 기존 문제로, 이번 메모리/플러그인 작업에서는 수정하지 않았다.

기존 두 서비스에 `systemctl set-property ... AllowedCPUs=0-1`을 적용했다. 실행 중인 프로세스와 이후 재시작에도 적용되며, Fabric·Velocity는 재시작하지 않았다. 새 서비스는 `AllowedCPUs=2`, `CPUAffinity=2`, JVM `ActiveProcessorCount=1`을 사용한다. 두 Minecraft 서버의 사용 가능 CPU 집합이 겹치지 않지만 OS 및 다른 서비스까지 코어 2에서 제외한 것은 아니다.

로비는 `starhill120.schem`을 변환해 두었던 새 월드에서 가져왔다. 전장은 세션 생성 시 새로 만든다. 기존 서버의 진행 중 세션·플레이어 진행도·순위 기록은 복제하지 않았다. BGM 설정과 곡 목록은 복사하고, 미디어 도구 경로는 Linux의 `yt-dlp`, `ffmpeg`로 변경했다. 채팅 미러 플러그인은 설치하지 않았다.

## 네트워크

UFW에 `enp0s6` 인터페이스의 `10.0.0.217:25566/TCP` 허용 규칙을 추가했다. SSH 및 기존 규칙은 유지한다.

서버 로그에서 `MCLuckDefense v0.16.0` 활성화와 `Done (59.196s)!` 시작 완료를 확인했다. Fabric·Velocity PID는 배포 전후 동일하다. 이는 시작 로그 및 서비스 설정 확인이며 접속 테스트는 아니다.

**OCI 보안 목록/NSG의 외부 TCP 25566 개방은 사용자가 별도로 진행한다.** 인스턴스 인증으로 OCI 네트워크 API를 조회할 권한이 없었으며, 사용자가 UFW만 설정하도록 범위를 확정했다. 외부 접속·Minecraft 로그인·포트 연결 테스트는 실행하지 않는다.

## 운영

일반적인 플러그인 업데이트는 관리자 **`/mud update` 한 번**으로 진행한다. GitHub의 테스트를 통과한 beta JAR 다운로드 → 해시·버전·커밋 검사 → 세션 호환성 검사 → 안전 리로드가 포함되며, 수동 파일 복사가 필요하지 않다. 명령은 설치된 0.17.0 로더에 이미 포함되어 있다.

2026-09-22 확인 시 semion에 수동으로 준비했던 0.17.2 중복 JAR는 `/home/ubuntu/mc-luck-defense-deploy/manual-staging-backup`으로 보관 이동했다. 이후 새 beta 배포와 수동 예정 파일의 충돌을 피하기 위한 조치다. 서버 재시작이나 라이브 리로드는 실행하지 않았다. 로컬 Windows의 최초 설치용 update 파일은 유지한다.

```sh
sudo systemctl status mc-luck-defense.service
sudo journalctl -u mc-luck-defense.service -f
```

업데이트 파일의 고정 이름은 `plugins/update/MCLuckDefense.jar`이다. 파일명에 버전을 붙이지 않으며 실제 버전은 JAR 내부와 `/mud version`으로 확인한다. 호환 업데이트는 `/mud reload check` 후 `/mud reload`로 적용할 수 있다. 자세한 제한은 [0.16.0 릴리스 안내](release-0.16.0.md)를 따른다.

배포한 MCLuckDefense JAR SHA-256:

```text
cc2d74d1b945dcd8e50ccf3eadca8f761f35dafa19aa9b15ded97b5b4ac399da
```
