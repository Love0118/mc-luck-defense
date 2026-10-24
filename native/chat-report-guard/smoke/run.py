"""Build and test the guard on an isolated, loopback-only Paper 26.3 fixture."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--paperclip', type=Path, required=True)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    args = parser.parse_args()
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    plugins = work / 'plugins'
    plugins.mkdir(exist_ok=True)
    server = args.cache.resolve() / 'versions/26.3/paper-26.3.jar'
    libraries = args.cache.resolve() / 'libraries'
    guard = plugins / 'MomaChatGuard.jar'
    subprocess.run(['python', str(ROOT / 'build.py'), '--server-jar', str(server),
                    '--libraries', str(libraries), '--output', str(guard)], check=True)
    classes = work / 'smoke-classes'
    classes.mkdir(exist_ok=True)
    cp = os.pathsep.join(str(p) for p in [server, guard, *sorted(libraries.rglob('*.jar'))])
    arguments = ['-encoding', 'UTF-8', '--release', '25', '-cp', cp, '-d', str(classes),
                 str(ROOT / 'smoke/GuardSmoke.java')]
    argfile = work / 'smoke-javac.args'
    argfile.write_text('\n'.join(json.dumps(a) for a in arguments), encoding='utf-8')
    subprocess.run(['javac', '@' + str(argfile)], check=True)
    with zipfile.ZipFile(plugins / 'GuardSmoke.jar', 'w', zipfile.ZIP_DEFLATED) as jar:
        jar.writestr('plugin.yml', 'name: GuardSmoke\nversion: 1\nmain: dev.moma.chat.smoke.GuardSmoke\napi-version: \'26.3\'\ndepend: [MomaChatGuard]\n')
        jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\npaperweight-mappings-namespace: mojang\n\n')
        for file in classes.rglob('*.class'):
            jar.write(file, file.relative_to(classes).as_posix())
    for name in ('cache', 'libraries', 'versions'):
        target = work / name
        if not target.exists():
            # Junction/symlink to existing dependencies; no public server files are used.
            if os.name == 'nt':
                subprocess.run(['cmd', '/c', 'mklink', '/J', str(target), str(args.cache.resolve() / name)], check=True, stdout=subprocess.DEVNULL)
            else:
                target.symlink_to(args.cache.resolve() / name, target_is_directory=True)
    (work / 'eula.txt').write_text('eula=true\n')
    (work / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25589\nonline-mode=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\nlevel-type=minecraft:flat\ngenerate-structures=false\n')
    result = work / 'guard-smoke.txt'
    result.unlink(missing_ok=True)
    with (work / 'console.log').open('w', encoding='utf-8') as log:
        process = subprocess.Popen(['java', '-Xms512M', '-Xmx1G', '-Dpaper.disablePluginRemapping=true',
                                    '-jar', str(args.paperclip.resolve()), '--nogui'], cwd=work,
                                   stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT)
        try:
            process.wait(timeout=150)
        except subprocess.TimeoutExpired:
            process.communicate(b'stop\n', timeout=30)
            raise
    text = result.read_text() if result.exists() else 'FAIL: no smoke result; inspect console.log'
    print(text.strip())
    if not text.startswith('PASS:'):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
