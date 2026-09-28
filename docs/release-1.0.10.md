# 1.0.10 — 관리자 `/reboot`

`/reboot` 명령을 관리자 권한 `moma.admin` 및 콘솔에 제공한다. 인수를 받지 않으며, 접속자에게 재시작을 알리고 다음 서버 틱에 정상 종료한다. 종료가 중복 예약되지 않도록 한다. 예약 실패 시 명령을 다시 시도할 수 있다.

정상 종료한 프로세스를 **실제로 다시 시작하는 주체는 서버 실행 관리자**다. 기본 `Restart=on-failure`인 systemd 서비스는 정상 종료를 재시작하지 않는다. Oracle Paper의 `mc-luck-defense.service`에는 [`mc-luck-defense-reboot.conf`](../scripts/maintenance/mc-luck-defense-reboot.conf)를 `/etc/systemd/system/mc-luck-defense.service.d/95-reboot.conf`로 설치하고 `sudo systemctl daemon-reload`를 실행해야 한다. 이 설정은 `Restart=always`와 5초 지연을 지정한다. 설치된 서버 JAR가 1.0.10인지 확인한 다음 `/reboot`를 사용한다. `systemctl stop`으로 관리자가 명시적으로 서비스를 중지할 때는 systemd가 재시작하지 않는다.

다른 실행 환경에서도 실행 관리자가 정상 종료 후 자동 시작하도록 설정해야 한다. Paper의 [기본 `/restart` 방식](https://docs.papermc.io/paper/reference/commands/#restart)은 별도의 `restart-script`를 사용한다. 이 명령은 그 스크립트에 의존하지 않고 정상 종료를 요청한다.

명령은 플러그인 영구 로더에 등록된다. 따라서 기존 버전에서 최초 설치 시 JAR를 교체하고 Paper를 정상 재시작해야 한다. 운영 중인 오라클 서버에는 이 변경을 적용하지 않았다.

검증: 권한이 없는 사용자·잘못된 인수는 서버를 종료하지 않는다. 관리자의 첫 요청만 종료 작업을 예약하고 명령이 반환되기 전에는 종료하지 않는다. 작업 예약이 실패하면 재시도를 허용한다.

로컬 `mvn clean verify`에서 **360개 Java 테스트**, 결과 검증기 Python 테스트 **3개**가 통과했다.
