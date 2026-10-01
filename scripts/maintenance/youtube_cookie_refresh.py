#!/usr/bin/env python3
"""Refresh a dedicated Oracle YouTube browser profile and publish validated cookies."""
import argparse
import datetime as dt
import http.cookiejar
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time

AUTH_NAMES = {'SID', 'HSID', 'SSID', 'APISID', 'SAPISID', '__Secure-1PSID', '__Secure-3PSID',
              '__Secure-1PSIDTS', '__Secure-3PSIDTS'}


class RefreshFailure(Exception):
    pass


def youtube_domain(domain):
    domain = domain.lstrip('.').lower()
    return domain == 'youtube.com' or domain.endswith('.youtube.com')


def import_cookies(path, now):
    jar = http.cookiejar.MozillaCookieJar(str(path))
    jar.load(ignore_discard=True, ignore_expires=True)
    result = []
    for cookie in jar:
        if not youtube_domain(cookie.domain) or (cookie.expires and cookie.expires <= now):
            continue
        value = dict(name=cookie.name, value=cookie.value, domain=cookie.domain, path=cookie.path,
                     secure=cookie.secure, httpOnly=cookie.has_nonstandard_attr('HTTPOnly'))
        if cookie.expires:
            value['expires'] = cookie.expires
        result.append(value)
    if not any(cookie['name'] in AUTH_NAMES for cookie in result):
        raise RefreshFailure('SEED_HAS_NO_LOGIN_COOKIES')
    return result


def export_cookies(cookies, now):
    lines = ['# Netscape HTTP Cookie File', '# Dedicated YouTube browser export']
    count = 0
    for cookie in cookies:
        if not youtube_domain(cookie['domain']) or cookie.get('partitionKey'):
            continue
        expires = cookie.get('expires', -1)
        if expires > 0 and expires <= now:
            continue
        domain = cookie['domain']
        prefix = '#HttpOnly_' if cookie.get('httpOnly') else ''
        fields = [prefix + domain, 'TRUE' if domain.startswith('.') else 'FALSE', cookie['path'],
                  'TRUE' if cookie['secure'] else 'FALSE', str(int(expires) if expires > 0 else 0),
                  cookie['name'], cookie['value']]
        if any('\t' in field or '\n' in field or '\r' in field for field in fields):
            raise RefreshFailure('INVALID_COOKIE_FORMAT')
        lines.append('\t'.join(fields))
        count += 1
    if count == 0:
        raise RefreshFailure('NO_YOUTUBE_COOKIES')
    return ('\n'.join(lines) + '\n').encode('utf-8'), count


def private_write(path, data):
    descriptor, name = tempfile.mkstemp(prefix='.' + path.name + '-', dir=path.parent)
    temp = Path(name)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temp, 0o600)
        temp.replace(path)
    finally:
        temp.unlink(missing_ok=True)


def publish(candidate, destination, original):
    if destination.read_bytes() != original:
        raise RefreshFailure('COOKIE_FILE_CHANGED_DURING_REFRESH')
    private_write(destination.with_name('youtube-cookies.previous.txt'), original)
    private_write(destination, candidate.read_bytes())


def validate(downloader, candidate, url):
    command = [str(downloader), '--ignore-config', '--cookies', str(candidate), '--js-runtimes', 'node',
               '--no-playlist', '--skip-download', '--dump-single-json', '--', url]
    try:
        result = subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                                text=True, timeout=120)
    except subprocess.TimeoutExpired:
        raise RefreshFailure('VALIDATION_TIMEOUT') from None
    invalid = 'cookies are no longer valid' in result.stderr.lower()
    if result.returncode != 0 or invalid:
        raise RefreshFailure('VALIDATION_FAILED')


def refresh(root, probe_url, seed):
    from playwright.sync_api import sync_playwright
    secrets = root / 'secrets'
    target = secrets / 'youtube-cookies.txt'
    original = target.read_bytes()
    profile = secrets / 'youtube-browser-profile'
    profile.mkdir(mode=0o700, exist_ok=True)
    os.chmod(profile, 0o700)
    with sync_playwright() as playwright:
        context = playwright.chromium.launch_persistent_context(str(profile), headless=True,
                                                                viewport={'width': 800, 'height': 600})
        try:
            if seed:
                context.clear_cookies()
                context.add_cookies(import_cookies(target, time.time()))
            before = {c['name']: c['value'] for c in context.cookies('https://www.youtube.com/') if c['name'] in AUTH_NAMES}
            page = context.pages[0] if context.pages else context.new_page()
            page.route('**/*', lambda route: route.abort() if route.request.resource_type in {'image', 'media', 'font'} else route.continue_())
            page.goto('https://www.youtube.com/', wait_until='domcontentloaded', timeout=45000)
            page.wait_for_function("window.ytcfg && typeof window.ytcfg.get('LOGGED_IN') === 'boolean'", timeout=20000)
            if not page.evaluate("!!window.ytcfg.get('LOGGED_IN')"):
                raise RefreshFailure('LOGIN_REQUIRED')
            page.wait_for_timeout(5000)
            cookies = context.cookies('https://www.youtube.com/')
            changed = sorted({c['name'] for c in cookies if c['name'] in AUTH_NAMES and c['value'] != before.get(c['name'])})
            data, count = export_cookies(cookies, time.time())
        finally:
            context.close()
    descriptor, name = tempfile.mkstemp(prefix='.youtube-candidate-', dir=secrets)
    os.close(descriptor)
    candidate = Path(name)
    try:
        private_write(candidate, data)
        validate(root / 'tools/media-venv/bin/yt-dlp', candidate, probe_url)
        publish(candidate, target, original)
    finally:
        candidate.unlink(missing_ok=True)
    return dict(status='ok', authenticated=True, exported_cookies=count, changed_auth_cookie_names=changed)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server-root', type=Path, required=True)
    parser.add_argument('--probe-url', required=True)
    parser.add_argument('--seed', action='store_true')
    args = parser.parse_args()
    import fcntl
    root = args.server_root.resolve()
    state = root / 'secrets/youtube-cookie-refresh-state.json'
    with (root / 'secrets/youtube-cookie-refresh.lock').open('a') as lock:
        try:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print(json.dumps({'status': 'busy'}))
            return 0
        try:
            report = refresh(root, args.probe_url, args.seed)
            code = 0
        except RefreshFailure as error:
            report = dict(status='failed', reason=str(error))
            code = 1
        except Exception as error:
            report = dict(status='failed', reason=type(error).__name__)
            code = 1
        report['checked_utc'] = dt.datetime.now(dt.timezone.utc).isoformat()
        if state.exists():
            report['last_success_utc'] = json.loads(state.read_text()).get('last_success_utc')
        if report['status'] == 'ok':
            report['last_success_utc'] = report['checked_utc']
        private_write(state, (json.dumps(report, indent=2) + '\n').encode())
        print(json.dumps(report), flush=True)
        return code


if __name__ == '__main__':
    raise SystemExit(main())
