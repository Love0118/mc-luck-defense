"""Verify real-server update results and archive the exact tested artifact identities."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile


def verify(source, destination):
    server = json.loads((source / 'notification-reload-pass.json').read_text(encoding='utf-8'))
    clients = json.loads((source / 'clients.json').read_text(encoding='utf-8'))
    assert server['base'] == '1.0.20' and server['candidate'] == '1.0.22'
    assert server['activeSession'] and server['spectator'] and server['fullStateMatched']
    for name, state in clients['players'].items():
        assert state['position'] and state['completed'] and not state['errors'], (name, state)
        assert state['settingsWindows'] >= 1
        off = state['phases']['OWN_OFF_OTHER_OFF']
        on = state['phases']['OWN_ON_OTHER_ON']
        expected = 1 if name == 'ReloadLobby' else 0
        assert off['summonChat'] == off['sounds'] == expected, (name, off)
        assert on['summonChat'] == on['sounds'] == 1, (name, on)
        assert state['phases']['ROUND_OFF']['roundChat'] == 0
        assert state['phases']['ROUND_ON']['roundChat'] == (1 if name == 'ReloadOwner' else 0)
    assert set(clients['players']) == {'ReloadOwner', 'ReloadViewer', 'ReloadLobby'}
    log = (source / 'server.log').read_text(encoding='utf-8', errors='replace')
    assert 'NOTIFICATION_RELOAD_PASSED' in log and 'NOTIFICATION_RELOAD_FAILED' not in log
    artifacts = {}
    for name, path in [('server', source / 'server.jar'), ('base', source / 'plugins/MCLuckDefense.jar'),
                       ('candidate', source / 'candidate.jar'), ('fixture', source / 'plugins/NotificationReloadSmoke.jar')]:
        artifacts[name] = dict(sha256=hashlib.sha256(path.read_bytes()).hexdigest(), bytes=path.stat().st_size)
        if name in ('base', 'candidate'):
            with zipfile.ZipFile(path) as archive:
                artifacts[name]['runtime'] = dict(line.split('=', 1) for line in archive.read('mud-runtime.properties').decode().splitlines() if '=' in line)
    assert artifacts['base']['runtime']['version'] == '1.0.20'
    assert artifacts['candidate']['runtime']['version'] == '1.0.22'
    result = dict(server=server, clients=clients, artifacts=artifacts,
                  execution='Paper 26.3 with three Minecraft 1.21.8 protocol clients through ViaVersion/ViaBackwards',
                  actions='Normal installDownloaded(path), no force; GUI interactions dispatched through Bukkit events',
                  live_server_modified=False)
    destination.mkdir(parents=True, exist_ok=True)
    (destination / 'verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (destination / 'clients.json').write_text(json.dumps(clients, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (destination / 'server-result.json').write_text(json.dumps(server, indent=2) + '\n', encoding='utf-8')
    print('PASS: active-session 1.0.20 -> 1.0.22 update, GUI and client notification packets')


if __name__ == '__main__':
    verify(Path(sys.argv[1]), Path(sys.argv[2]))
