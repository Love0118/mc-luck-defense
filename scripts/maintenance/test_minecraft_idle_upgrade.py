import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch, MagicMock

spec = importlib.util.spec_from_file_location("maintenance", Path(__file__).with_name("minecraft_idle_upgrade.py"))
maintenance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(maintenance)


class MaintenanceTest(unittest.TestCase):
    def test_daily_stamp_boundary(self):
        with tempfile.TemporaryDirectory() as directory:
            stamp = Path(directory) / "stamp"
            with patch.object(maintenance, "STAMP", stamp):
                self.assertTrue(maintenance.due())
                stamp.touch()
                modified = stamp.stat().st_mtime
                with patch.object(maintenance.time, "time", return_value=modified + 86399):
                    self.assertFalse(maintenance.due())
                with patch.object(maintenance.time, "time", return_value=modified + 86400):
                    self.assertTrue(maintenance.due())

    def test_cleanup_revokes_restart_permission_before_removing_firewall(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "allow-restarts"
            marker.touch()
            def execute(command, **kwargs):
                self.assertFalse(marker.exists())
                return subprocess.CompletedProcess(command, 0)
            with patch.object(maintenance, "MARKER", marker), patch.object(maintenance.subprocess, "run", side_effect=execute) as process:
                maintenance.cleanup()
                self.assertEqual(["/usr/sbin/nft", "delete", "table", "inet", maintenance.TABLE], process.call_args.args[0])

    def test_status_parses_fragmented_response_without_player_names(self):
        body = json.dumps({"players": {"online": 3}}).encode()
        payload = b"\0" + maintenance.varint(len(body)) + body
        wire = bytearray(maintenance.varint(len(payload)) + payload)
        stream = MagicMock()
        stream.__enter__.return_value = stream
        def receive(_):
            return bytes([wire.pop(0)]) if wire else b""
        stream.recv.side_effect = receive
        with patch.object(maintenance.socket, "create_connection", return_value=stream):
            self.assertEqual(3, maintenance.online("127.0.0.1", 25566))

    def test_malformed_counts_and_packets_fail_closed(self):
        for count in (True, -1, "0", None):
            body = json.dumps({"players": {"online": count}}).encode()
            payload = b"\0" + maintenance.varint(len(body)) + body
            stream = MagicMock()
            stream.__enter__.return_value = stream
            stream.recv.side_effect = [maintenance.varint(len(payload)), payload]
            with patch.object(maintenance.socket, "create_connection", return_value=stream):
                with self.assertRaises(ValueError):
                    maintenance.online("127.0.0.1", 25566)
        stream.recv.side_effect = [b"\x01"]
        with patch.object(maintenance.socket, "create_connection", return_value=stream):
            with self.assertRaises(ValueError):
                maintenance.online("127.0.0.1", 25566)

    def test_either_occupied_server_or_unknown_status_defers(self):
        for counts in ((0, 1), (2, 0), (0, 0)):
            with patch.object(maintenance, "online", side_effect=counts):
                self.assertEqual(counts == (0, 0), maintenance.empty())
        with patch.object(maintenance, "online", side_effect=TimeoutError):
            self.assertFalse(maintenance.empty())

    def test_daily_interval_or_busy_server_never_changes_firewall(self):
        for due, empty in ((False, True), (True, False)):
            with patch.object(maintenance, "due", return_value=due), patch.object(maintenance, "empty", return_value=empty), patch.object(maintenance.subprocess, "run") as process:
                self.assertEqual(0, maintenance.run())
                process.assert_not_called()

    def guarded_run(self, counts, connection_clear=True, update_exit=0, update_error=False):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            marker = state / "allow-restarts"
            calls = []
            def execute(command, **kwargs):
                calls.append(command)
                if command[0] == "/usr/lib/apt/apt.systemd.daily":
                    self.assertTrue(marker.exists())
                    if update_error:
                        raise OSError("process failed")
                    return subprocess.CompletedProcess(command, update_exit)
                return subprocess.CompletedProcess(command, 0)
            def cleanup():
                marker.unlink(missing_ok=True)
            with patch.object(maintenance, "STATE", state), patch.object(maintenance, "MARKER", marker), patch.object(maintenance, "due", return_value=True), patch.object(maintenance, "empty", side_effect=counts), patch.object(maintenance, "connections_clear", return_value=connection_clear), patch.object(maintenance.time, "sleep"), patch.object(maintenance.subprocess, "run", side_effect=execute), patch.object(maintenance, "cleanup", side_effect=cleanup) as clean:
                if update_error:
                    with self.assertRaises(OSError):
                        maintenance.run()
                else:
                    result = maintenance.run()
                    self.assertEqual(update_exit if all(counts) and connection_clear else 0, result)
                clean.assert_called_once()
                self.assertFalse(marker.exists())
            return calls

    def test_player_or_prelogin_connection_arriving_after_fence_defers(self):
        for counts, clear in (((True, False), True), ((True, True), False)):
            calls = self.guarded_run(counts, clear)
            self.assertFalse(any(c[0] == "/usr/lib/apt/apt.systemd.daily" for c in calls))

    def test_empty_servers_run_update_and_cleanup_on_success_failure_or_exception(self):
        for exit_code in (0, 1):
            calls = self.guarded_run((True, True), update_exit=exit_code)
            self.assertEqual("/usr/sbin/nft", calls[0][0])
            self.assertEqual("/usr/lib/apt/apt.systemd.daily", calls[1][0])
        self.guarded_run((True, True), update_error=True)


if __name__ == "__main__":
    unittest.main()
