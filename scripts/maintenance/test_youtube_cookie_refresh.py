import os
from pathlib import Path
import subprocess
import tempfile
import types
import unittest
from unittest.mock import MagicMock, patch
import youtube_cookie_refresh as refresh


class CookieRefreshTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def cookie(self, **overrides):
        value = dict(name='SID', value='private-value', domain='.youtube.com', path='/',
                     secure=True, httpOnly=True, expires=2000000000)
        value.update(overrides)
        return value

    def test_cookie_roundtrip_preserves_login_and_http_only(self):
        data, count = refresh.export_cookies([self.cookie()], 1900000000)
        path = self.root / 'cookies.txt'
        path.write_bytes(data)
        imported = refresh.import_cookies(path, 1900000000)
        self.assertEqual(1, count)
        self.assertEqual([self.cookie()], imported)

    def test_export_filters_other_domains_expired_and_partitioned_cookies(self):
        cookies = [self.cookie(), self.cookie(domain='.google.com'), self.cookie(domain='youtube.com.evil.test'),
                   self.cookie(domain='.notyoutube.com'), self.cookie(expires=1800000000),
                   self.cookie(partitionKey='https://example.test')]
        data, count = refresh.export_cookies(cookies, 1900000000)
        self.assertEqual(1, count)
        self.assertNotIn(b'google.com', data)
        self.assertNotIn(b'evil', data)

    def test_session_cookies_keep_session_expiry(self):
        data, _ = refresh.export_cookies([self.cookie(expires=-1)], 1900000000)
        path = self.root / 'cookies.txt'
        path.write_bytes(data)
        imported = refresh.import_cookies(path, 1900000000)
        self.assertNotIn('expires', imported[0])

    def test_empty_or_line_injecting_export_fails(self):
        with self.assertRaises(refresh.RefreshFailure):
            refresh.export_cookies([], 1900000000)
        with self.assertRaises(refresh.RefreshFailure):
            refresh.export_cookies([self.cookie(value='secret\nmalformed')], 1900000000)

    def test_cookie_file_changed_during_refresh_is_preserved(self):
        target = self.root / 'youtube-cookies.txt'
        candidate = self.root / 'candidate'
        target.write_bytes(b'updated by downloader')
        candidate.write_bytes(b'browser export')
        with self.assertRaises(refresh.RefreshFailure):
            refresh.publish(candidate, target, b'old cookies')
        self.assertEqual(b'updated by downloader', target.read_bytes())
        self.assertFalse((self.root / 'youtube-cookies.previous.txt').exists())

    def test_publish_backs_up_previous_export_and_replaces_atomically(self):
        target = self.root / 'youtube-cookies.txt'
        candidate = self.root / 'candidate'
        target.write_bytes(b'old cookies')
        candidate.write_bytes(b'new cookies')
        refresh.publish(candidate, target, b'old cookies')
        self.assertEqual(b'new cookies', target.read_bytes())
        self.assertEqual(b'old cookies', (self.root / 'youtube-cookies.previous.txt').read_bytes())
        self.assertEqual([], list(self.root.glob('.*youtube-cookies*')))
        if os.name == 'posix':
            self.assertEqual(0o600, target.stat().st_mode & 0o777)

    def test_validation_uses_cookie_path_and_hides_download_response(self):
        candidate = self.root / 'candidate'
        candidate.write_text('private-cookie')
        with patch.object(refresh.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, stderr='')) as run:
            refresh.validate(Path('yt-dlp'), candidate, 'https://www.youtube.com/watch?v=40WtnlJwTM8')
        args = run.call_args.args[0]
        self.assertIn(str(candidate), args)
        self.assertNotIn('private-cookie', str(args))
        self.assertEqual(subprocess.DEVNULL, run.call_args.kwargs['stdout'])

    def test_failed_or_invalid_cookie_validation_never_passes(self):
        for code, message in [(1, 'private-cookie'), (0, 'WARNING: cookies are no longer valid')]:
            with patch.object(refresh.subprocess, 'run', return_value=subprocess.CompletedProcess([], code, stderr=message)):
                with self.assertRaises(refresh.RefreshFailure) as caught:
                    refresh.validate(Path('yt-dlp'), self.root / 'candidate', 'https://www.youtube.com/')
                self.assertEqual('VALIDATION_FAILED', str(caught.exception))

    def test_validation_timeout_is_a_safe_status(self):
        with patch.object(refresh.subprocess, 'run', side_effect=subprocess.TimeoutExpired(['private-cookie'], 120)):
            with self.assertRaises(refresh.RefreshFailure) as caught:
                refresh.validate(Path('yt-dlp'), self.root / 'candidate', 'https://www.youtube.com/')
            self.assertEqual('VALIDATION_TIMEOUT', str(caught.exception))

    def browser_fixture(self, authenticated):
        context = MagicMock()
        context.pages = [MagicMock()]
        context.pages[0].evaluate.return_value = authenticated
        context.cookies.return_value = [self.cookie()]
        driver = MagicMock()
        driver.__enter__.return_value.chromium.launch_persistent_context.return_value = context
        module = types.SimpleNamespace(sync_playwright=lambda: driver)
        secrets = self.root / 'secrets'
        secrets.mkdir()
        target = secrets / 'youtube-cookies.txt'
        target.write_bytes(b'old private cookies')
        return context, module, target

    def test_revoked_browser_login_keeps_downloader_file_and_closes_browser(self):
        context, module, target = self.browser_fixture(False)
        with patch.dict('sys.modules', playwright=types.ModuleType('playwright'), **{'playwright.sync_api': module}):
            with self.assertRaises(refresh.RefreshFailure) as caught:
                refresh.refresh(self.root, 'https://www.youtube.com/', False)
        self.assertEqual('LOGIN_REQUIRED', str(caught.exception))
        self.assertEqual(b'old private cookies', target.read_bytes())
        context.close.assert_called_once()

    def test_failed_export_validation_preserves_existing_cookie_file(self):
        context, module, target = self.browser_fixture(True)
        with patch.dict('sys.modules', playwright=types.ModuleType('playwright'), **{'playwright.sync_api': module}):
            with patch.object(refresh, 'validate', side_effect=refresh.RefreshFailure('VALIDATION_FAILED')):
                with self.assertRaises(refresh.RefreshFailure):
                    refresh.refresh(self.root, 'https://www.youtube.com/', False)
        self.assertEqual(b'old private cookies', target.read_bytes())
        self.assertFalse(target.with_name('youtube-cookies.previous.txt').exists())
        self.assertEqual([], list(target.parent.glob('.youtube-candidate-*')))
        context.close.assert_called_once()


if __name__ == '__main__':
    unittest.main()
