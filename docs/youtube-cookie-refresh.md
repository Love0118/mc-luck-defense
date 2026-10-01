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

## 검증

쿠키 형식과 HttpOnly 보존, 도메인·만료·파티션 쿠키 필터, 잘못된 줄 삽입 거절, 파일 변경 시 보존, 직전 백업과 교체, 검증 실패·시간 초과 거절, 인증 값의 출력 방지를 검사한다.

```bash
python -m unittest discover -s scripts/maintenance -p test_youtube_cookie_refresh.py
```

브라우저의 지속 프로필과 쿠키 API는 [Playwright 공식 문서](https://playwright.dev/python/docs/api/class-browsertype#browser-type-launch-persistent-context)에 따른다. 외부 브라우저에서 쿠키가 회전하는 특성과 수동 재인증 방법은 [yt-dlp 공식 문서](https://github.com/yt-dlp/yt-dlp/wiki/Extractors#exporting-youtube-cookies)를 참고한다.
