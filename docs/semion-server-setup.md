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

### 2026-09-22 자동 업데이트 재시작 사고

15:18:55 KST Ubuntu의 `apt-daily-upgrade` / `unattended-upgrade` 실행 중 `needrestart`가 Paper 서비스를 재시작했다. 이어 Java 25 보안 업데이트 후 15:19:16에 Paper·Velocity 재시작이 다시 발생했고 Fabric도 연결된 서비스 동작으로 재시작되었다. `/var/log/unattended-upgrades/unattended-upgrades-dpkg.log`에 `systemctl restart ... mc-luck-defense.service` 및 `systemctl restart mc-luck-defense.service steve-td-proxy.service`가 명시되어 있다. 해당 시간대 커널 OOM·패닉 기록은 없고 호스트도 재부팅되지 않았다.

Fabric은 15:20:09, Paper는 15:20:18에 시작 완료 로그를 남겼다. 이 운영체제 재시작으로 update 폴더에 있던 ClickLink 1.0.1도 적용되었다. 진단 작업에서는 추가 서버 재시작을 실행하지 않았다.

재발 방지로 [needrestart 설정](../scripts/needrestart-minecraft.conf)을 `/etc/needrestart/conf.d/99-minecraft.conf`에 설치했다. `override_rc`에서 Paper·Fabric·Velocity 세 서비스의 자동 재시작만 보류한다. 보안 패키지 업데이트와 다른 서비스 정책은 유지하며, 향후 Java·공유 라이브러리 업데이트는 계획된 점검 시간에 게임 서버를 재시작하여 적용한다. 실제 전체 needrestart 설정을 파싱하고 세 서비스에만 정확히 보류 규칙이 매칭되는지 검증했다.

### 접속자 없는 시간의 자동 업데이트

이후 사용자 요청으로 수동 점검 대기 정책을 **Paper와 Fabric 모두 접속자 0명일 때만 자동 설치**하도록 확장했다. `apt-daily-upgrade.timer`는 15분 간격에 최대 2분의 지연을 더해 확인한다. 마지막 정상 unattended 업그레이드로부터 24시간이 지나야 설치를 시도한다. 패키지 목록 갱신·미리 다운로드하는 `apt-daily.service`는 그대로 유지한다.

- [관리 스크립트](../scripts/maintenance/minecraft_idle_upgrade.py): `/usr/local/sbin/minecraft-idle-upgrade`
- [서비스 설정](../scripts/maintenance/apt-daily-upgrade-service.conf): `/etc/systemd/system/apt-daily-upgrade.service.d/90-minecraft-idle.conf`
- [타이머 설정](../scripts/maintenance/apt-daily-upgrade-timer.conf): `/etc/systemd/system/apt-daily-upgrade.timer.d/90-minecraft-idle.conf`
- [재시작 정책](../scripts/needrestart-minecraft.conf): `/etc/needrestart/conf.d/99-minecraft.conf`

Paper `10.0.0.217:25566`, Fabric `127.0.0.1:25566`의 로컬 서버 상태 프로토콜로 인원수를 확인한다. 한 명이라도 있거나 응답 실패·잘못된 응답이면 설치를 보류한다. RCON 활성화나 비밀번호가 필요하지 않다.

0명 확인 후 별도 nftables 테이블 `inet minecraft_idle_upgrade`로 외부의 신규 게임 TCP 연결(25565·25566·25569)을 잠시 막는다. SSH와 기존 연결에는 해당 규칙을 적용하지 않는다. 5초 뒤 인원수를 다시 확인하고 로그인 진행 중 TCP 연결까지 없을 때만 APT 설치를 실행한다. 이 기간에만 root 전용 `/run/minecraft-idle-upgrade/allow-restarts` 파일을 통해 세 게임 서비스의 needrestart 재시작을 허용한다. 완료·실패 시 스크립트의 정리 처리와 systemd `ExecStopPost`가 허용 파일·임시 방화벽 테이블을 제거한다. 빈 서버를 확인할 수 없는 장애 상황에서도 업데이트를 강행하지 않는다. OS 자동 재부팅은 기존처럼 비활성 상태다.

검증: Windows와 실제 Semion에서 Python 검사 8개 통과. 서버 상태 패킷 분할 수신, 잘못된 인원 값, 24시간 경계, 접속자 유무, 검사 중 신규 접속, 성공·실패·예외 정리를 확인했다. nftables는 적용 없이 문법 검사했고 systemd 설정 검사도 통과했다. 실제 조회는 Paper 3명/Fabric 0명으로 보류됐으며, 당일 설치 완료 상태에서는 실행 경로가 APT를 호출하지 않는 것도 확인했다. 게임 서버 PID는 바뀌지 않았고, 검증 목적으로 실제 업그레이드나 임시 접속 차단을 실행하지 않았다.

수동 상태 확인: `sudo /usr/local/sbin/minecraft-idle-upgrade check`. 예약·로그는 `systemctl list-timers apt-daily-upgrade.timer`와 `journalctl -u apt-daily-upgrade.service`로 확인한다. 자동 설치 중인 APT를 강제 종료하지 말고 완료를 기다린다. 업데이트 패키지 설치 후 필요한 재시작이 일어나므로 유지보수 중에는 게임 접속이 잠시 제한된다.

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
