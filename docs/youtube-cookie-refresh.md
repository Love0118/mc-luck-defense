# Oracle YouTube 쿠키 자동 갱신

Oracle의 전용 Chromium 프로필에 YouTube 로그인 상태를 보관하고 4시간마다 접속하여 쿠키를 내보낸다. 갱신한 후보 파일로 yt-dlp의 영상 정보 조회가 성공했을 때만 서버의 `secrets/youtube-cookies.txt`를 교체한다. 개인 PC가 꺼져 있어도 동작한다. 브라우저는 작업 동안만 실행하고 종료한다.

현재 세션으로 로그인 유지, 인증 타임스탬프 쿠키(`__Secure-1PSIDTS`, `__Secure-3PSIDTS`) 값 변경, 브라우저를 닫았다가 다시 연 뒤 로그인 유지, 내보낸 쿠키의 yt-dlp 검증을 Oracle에서 확인했다. 쿠키 값이 실제로 바뀌는 시점은 YouTube가 결정한다. 매 실행마다 반드시 다른 값이 생성되는 것은 아니다.

유효한 로그인 세션을 유지하는 기능이다. Google이 세션을 완전히 폐기하거나 추가 인증을 요구하면 사람이 다시 로그인해야 한다. 이때는 `LOGIN_REQUIRED` 또는 검증 실패 상태를 기록하고 기존 다운로드용 쿠키 파일을 유지한다. 로그인 비밀번호를 저장하거나 CAPTCHA·추가 인증을 자동 처리하지 않는다.

## 구성

- `scripts/maintenance/youtube_cookie_refresh.py`: 프로필 유지·쿠키 내보내기·검증·교체.
- `scripts/maintenance/youtube-cookie-browser-requirements.txt`: 브라우저 제어 라이브러리 버전.
- `mc-luck-defense-youtube-cookies.service` / `.timer`: Oracle 작업과 4시간 주기.
- `secrets/youtube-browser-profile`: 서버 전용 로그인 프로필. 디렉터리 권한 700.
- `secrets/youtube-cookies.previous.txt`: 교체 직전 파일의 백업. 파일 권한 600.
- `secrets/youtube-cookie-refresh-state.json`: 최근 점검·성공 시각과 결과. 쿠키 값은 기록하지 않는다.

브라우저 프로필과 쿠키 파일은 Git에 넣지 않는다. 내보내기는 youtube.com과 그 하위 도메인의 쿠키로 제한하고, 만료되었거나 다른 도메인에 속한 쿠키는 제외한다. 갱신 중 다운로드용 파일이 다른 작업에 의해 변경됐으면 그 파일을 덮어쓰지 않는다.

## 최초 구성 또는 다른 서버 설치

Python 가상 환경과 Chromium Headless Shell을 설치한다. 기존 yt-dlp 가상 환경은 따로 유지한다.

```bash
python3 -m venv /home/ubuntu/mc-luck-defense/tools/cookie-browser-venv
/home/ubuntu/mc-luck-defense/tools/cookie-browser-venv/bin/python -m pip install -r scripts/maintenance/youtube-cookie-browser-requirements.txt
PLAYWRIGHT_BROWSERS_PATH=/home/ubuntu/mc-luck-defense/tools/cookie-browser-bin /home/ubuntu/mc-luck-defense/tools/cookie-browser-venv/bin/python -m playwright install --only-shell chromium
install -m 755 scripts/maintenance/youtube_cookie_refresh.py /home/ubuntu/mc-luck-defense/tools/youtube_cookie_refresh.py
```

서버에 유효한 쿠키 파일을 넣은 뒤 `--seed`를 한 번 실행하여 전용 프로필을 초기화한다. 이후 예약 실행에서는 `--seed`를 사용하지 않는다. 프로필의 갱신된 상태를 계속 이어 사용한다.

기존 설치에서 새 Netscape 쿠키 파일을 받았을 때도 파일만 바꾸지 말고 같은 전용 프로필에 `--seed`를 한 번 실행한다. 파일은 비공개 디렉터리에서 검증한 뒤 기존 파일을 백업하고 원자적으로 교체하며 권한 600을 유지한다. 예약 작업과 수동 설치가 겹치지 않도록 기존 `youtube-cookie-refresh.lock`을 공유한다. 새 파일을 프로필에 반영하지 않으면 다음 예약 실행이 이전 프로필의 쿠키를 다시 내보낼 수 있다. 정상 갱신 후에는 기존 4시간 타이머를 그대로 사용한다.

```bash
PLAYWRIGHT_BROWSERS_PATH=/home/ubuntu/mc-luck-defense/tools/cookie-browser-bin /home/ubuntu/mc-luck-defense/tools/cookie-browser-venv/bin/python /home/ubuntu/mc-luck-defense/tools/youtube_cookie_refresh.py --server-root /home/ubuntu/mc-luck-defense --probe-url 'https://www.youtube.com/watch?v=40WtnlJwTM8' --seed
sudo install -m 644 scripts/maintenance/mc-luck-defense-youtube-cookies.service /etc/systemd/system/mc-luck-defense-youtube-cookies.service
sudo install -m 644 scripts/maintenance/mc-luck-defense-youtube-cookies.timer /etc/systemd/system/mc-luck-defense-youtube-cookies.timer
sudo systemd-analyze verify /etc/systemd/system/mc-luck-defense-youtube-cookies.service /etc/systemd/system/mc-luck-defense-youtube-cookies.timer
sudo systemctl daemon-reload
sudo systemctl start mc-luck-defense-youtube-cookies.service
sudo systemctl enable --now mc-luck-defense-youtube-cookies.timer
```

검사용 영상은 공개 상태로 유지되는 정상 영상이어야 한다. 영상이 삭제되거나 비공개로 바뀌면 서비스의 `--probe-url`을 다른 정상 영상으로 바꾸고 서비스 설정을 다시 읽힌다. 확인 방법:

```bash
systemctl list-timers mc-luck-defense-youtube-cookies.timer
systemctl show mc-luck-defense-youtube-cookies.service -p Result -p ExecMainStatus -p MemoryPeak
journalctl -u mc-luck-defense-youtube-cookies.service -n 5 --no-pager
```

서비스는 Ubuntu 사용자로 동작하며 메모리 상한 512 MiB, CPU 상한 1코어, 낮은 실행 우선순위를 적용한다. 파일 교체는 다음 다운로드부터 반영되어 게임 서버 재시작이 필요하지 않다. 이 보조 기능은 Oracle에 따로 설치하며 플러그인 JAR만 교체해 설치되는 기능은 아니다.

2026-10-23 KST 운영 점검에서 제공된 파일의 비공개 설치·백업·권한 600 및 기존 프로필 재초기화를 운영자가 확인했다. UTC 17:51:15 갱신 결과는 `authenticated=true`, 쿠키 15개였고 인증 타임스탬프 쿠키 두 종류의 회전도 확인했다. 쿠키 값은 출력하지 않았다. 기존 4시간 타이머는 같은 프로필을 이어 사용한다. 이는 당시 확인 결과이며, 향후 로그인 유지나 만료된 인증의 재생성을 보장하지 않는다.

이후 **2026-10-23 19:39 KST** 재점검에서는 다운로드 인증이 유효하지 않았다. 마지막 성공은 같은 날 **02:51:15 KST**였고, 타이머는 **05:02:03 KST**부터 `LOGIN_REQUIRED`를 기록했다. 최근 실패는 **17:00:49 KST**였다. 설치된 스크립트는 저장소 버전과 동일했으며, 인증 쿠키의 저장된 만료 시각은 아직 미래였다. 파일에 적힌 만료 시각만으로 서버 측 인증 유효성을 판단할 수 없다.

기존 다운로드용 jar의 임시 복사본으로 유지보수 검사용 영상의 yt-dlp 정보 조회를 실행했으며, 종료 코드 1과 계정 쿠키 무효 경고 및 로그인·봇 확인 요구를 확인했다. 영상 삭제·비공개 또는 JavaScript 실행 실패를 나타내는 진단은 검출되지 않았다. 별도의 신규 곡 변환 작업도 저장된 결과가 `SOURCE_BLOCKED`였다. 따라서 이번 실패는 브라우저의 `LOGGED_IN` 검사 하나만 잘못된 것으로 설명되지 않으며, 현재 jar에 대한 외부 인증 거절이 확인된 상태다. 회전과 세션 폐기 중 어느 원인이었는지는 이 증거만으로 확정할 수 없다.

이 점검에서는 현재 jar가 그대로 보존되었음을 확인했고 프로필·타이머·실행 스크립트를 교체하지 않았다. 성공으로 처리하도록 로그인 검사를 우회하거나, 같은 거절된 파일을 반복 seed하지 않는다. 사람이 다시 인증하여 얻은 새 후보를 서버에서 검증한 뒤에만 설치·일회성 seed하고, 실제 신규 다운로드까지 성공해야 복구 완료로 본다. 기존에 캐시된 음원의 재생 성공은 신규 다운로드 인증의 성공을 의미하지 않는다.

Noqui 다운로드 래퍼는 읽기 전용 원본을 호출마다 임시 권한 600 파일로 복사하므로 yt-dlp가 그 임시 파일에 기록한 변경은 삭제 시 사라진다. 영구 갱신은 위 전용 프로필과 검증된 후보 파일의 교체가 담당한다. yt-dlp의 `--cookies`가 파일을 읽고 다시 기록하는 동작은 [공식 README](https://github.com/yt-dlp/yt-dlp#filesystem-options)에 명시되어 있다. 원본은 다음 호출부터 다시 읽으며 진행 중인 작업이나 이미 READY인 음원 캐시는 바꾸지 않는다.

[yt-dlp 공식 YouTube 안내](https://github.com/yt-dlp/yt-dlp/wiki/Extractors#exporting-youtube-cookies)는 열린 YouTube 탭에서 계정 쿠키가 자주 회전한다고 설명하며, 수동 내보내기에는 개인 창에서 로그인·내보내기 후 창을 닫는 절차를 제시한다. 이 서버의 전용 프로필 갱신은 기존 세션을 사용할 수 있을 때의 제한된 유지 절차다. 제공된 텍스트 파일만으로 이미 폐기된 로그인 자격을 새로 만들 수는 없다. 재인증이 필요하면 새 파일을 비공개로 설치하고 다시 한 번 `--seed`한다.

## 검증

쿠키 형식과 HttpOnly 보존, 도메인·만료·파티션 쿠키 필터, 잘못된 줄 삽입 거절, 파일 변경 시 보존, 직전 백업과 교체, 검증 실패·시간 초과 거절, 인증 값의 출력 방지, 새 파일의 일회성 seed, 예약 실행의 프로필 재사용과 검증 중 쿠키 변경 보존을 검사한다.

```bash
python -m unittest discover -s scripts/maintenance -p test_youtube_cookie_refresh.py
```

브라우저의 지속 프로필과 쿠키 API는 [Playwright 공식 문서](https://playwright.dev/python/docs/api/class-browsertype#browser-type-launch-persistent-context)에 따른다. 외부 브라우저에서 쿠키가 회전하는 특성과 수동 재인증 방법은 [yt-dlp 공식 문서](https://github.com/yt-dlp/yt-dlp/wiki/Extractors#exporting-youtube-cookies)를 참고한다.
