#!/usr/bin/python3
"""Semion: run the daily unattended install only with an empty, fenced game stack."""
import argparse
import json
import os
from pathlib import Path
import signal
import socket
import struct
import subprocess
import time

SERVERS = (("Paper", "10.0.0.217", 25566), ("Fabric", "127.0.0.1", 25566))
STATE = Path("/run/minecraft-idle-upgrade")
MARKER = STATE / "allow-restarts"
STAMP = Path("/var/lib/apt/periodic/upgrade-stamp")
TABLE = "minecraft_idle_upgrade"
FENCE = '''table inet minecraft_idle_upgrade {
 chain admission {
  type filter hook input priority -10; policy accept;
  iifname != "lo" tcp dport { 25565, 25566, 25569 } ct state new reject with tcp reset
 }
}
'''


def varint(value):
    value &= 0xffffffff
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def read_exact(stream, length):
    result = bytearray()
    deadline = time.monotonic() + 8
    while len(result) < length:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("status response timed out")
        stream.settimeout(min(3, remaining))
        part = stream.recv(length - len(result))
        if not part:
            raise ValueError("truncated status response")
        result.extend(part)
    return bytes(result)


def read_varint(stream):
    value = 0
    for index in range(5):
        byte = read_exact(stream, 1)[0]
        value |= (byte & 127) << (7 * index)
        if byte < 128:
            return value
    raise ValueError("invalid status length")


def online(host, port):
    with socket.create_connection((host, port), timeout=3) as stream:
        address = host.encode()
        handshake = b"\x00" + varint(777) + varint(len(address)) + address + struct.pack(">H", port) + b"\x01"
        stream.sendall(varint(len(handshake)) + handshake + b"\x01\x00")
        length = read_varint(stream)
        if not 3 <= length <= 2 * 1024 * 1024:
            raise ValueError("invalid status packet size")
        payload = read_exact(stream, length)
    # Parse the bounded packet independently so a malformed JSON length cannot over-read.
    if payload[0] != 0:
        raise ValueError("unexpected status packet")
    size = 0
    for index in range(1, min(6, len(payload))):
        byte = payload[index]
        size |= (byte & 127) << (7 * (index - 1))
        if byte < 128:
            body = payload[index + 1:]
            if size != len(body):
                raise ValueError("invalid JSON length")
            count = json.loads(body)["players"]["online"]
            if type(count) is not int or count < 0:
                raise ValueError("invalid player count")
            return count
    raise ValueError("invalid status JSON")


def empty():
    try:
        counts = {name: online(host, port) for name, host, port in SERVERS}
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Update deferred: player status unavailable ({type(error).__name__}).", flush=True)
        return False
    print("Players: " + ", ".join(f"{name}={count}" for name, count in counts.items()), flush=True)
    return all(count == 0 for count in counts.values())


def connections_clear():
    result = subprocess.run(
        ["/usr/bin/ss", "-Htn", "state", "established", "( sport = :25565 or sport = :25566 or sport = :25569 )"],
        capture_output=True, text=True, check=True, timeout=10)
    return not result.stdout.strip()


def cleanup():
    MARKER.unlink(missing_ok=True)
    exists = subprocess.run(["/usr/sbin/nft", "list", "table", "inet", TABLE], capture_output=True, timeout=10)
    if exists.returncode == 0:
        subprocess.run(["/usr/sbin/nft", "delete", "table", "inet", TABLE], check=True, timeout=10)


def due():
    return not STAMP.exists() or time.time() - STAMP.stat().st_mtime >= 86400


def run():
    if not due():
        print("Daily unattended update is not due.", flush=True)
        return 0
    if not empty():
        return 0
    STATE.mkdir(mode=0o700, exist_ok=True)
    try:
        subprocess.run(["/usr/sbin/nft", "-f", "-"], input=FENCE, text=True, check=True, timeout=10)
        # Allow in-flight logins to settle, then reject even pre-login TCP sessions.
        time.sleep(5)
        if not empty() or not connections_clear():
            print("Update deferred: a player or connection arrived during the idle check.", flush=True)
            return 0
        MARKER.write_text("Both servers empty; admission fenced.\n")
        os.chmod(MARKER, 0o600)
        print("Empty-server maintenance started; game admission temporarily closed.", flush=True)
        result = subprocess.run(["/usr/lib/apt/apt.systemd.daily", "install"], check=False)
        return result.returncode
    finally:
        cleanup()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("check", "run", "cleanup"))
    args = parser.parse_args()
    if args.action == "check":
        return 0 if empty() else 1
    if os.geteuid() != 0:
        raise PermissionError("Maintenance requires root")
    if args.action == "cleanup":
        cleanup()
        return 0
    # Let an in-flight package transaction finish before reopening admission.
    signal.signal(signal.SIGTERM, lambda *_: print("Maintenance stop requested; waiting for the package transaction.", flush=True))
    return run()


if __name__ == "__main__":
    raise SystemExit(main())
