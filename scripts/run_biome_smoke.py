"""Run all biome/enemy appearances on an isolated localhost Paper fixture."""
import argparse
import json
import shutil
import subprocess
import time
from pathlib import Path

def main(a):
    run=a.output.resolve()
    if run.exists():raise SystemExit('Choose a fresh output directory')
    if 'eula=true' not in a.eula.read_text().lower():raise SystemExit('Accepted EULA required')
    (run/'plugins').mkdir(parents=True)
    shutil.copy2(a.eula,run/'eula.txt')
    shutil.copy2(a.plugin,run/'plugins/MCLuckDefense.jar')
    shutil.copy2(a.fixture,run/'plugins/BiomeSmoke.jar')
    (run/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25587\nonline-mode=false\nenforce-secure-profile=false\nview-distance=3\nsimulation-distance=3\npause-when-empty-seconds=-1\ngenerate-structures=false\nlevel-type=minecraft:flat\ngenerator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains"}\n')
    command=['java','-Xms1g','-Xmx3g','-jar',str(a.server.resolve()),'--nogui']
    (run/'invocation.json').write_text(json.dumps(command))
    with (run/'console.log').open('w',encoding='utf-8') as log:
        process=subprocess.Popen(command,cwd=run,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True)
        try:
            process.wait(timeout=180)
            print((run/'biome-smoke.json').read_text())
        finally:
            if process.poll() is None:
                process.stdin.write('stop\n');process.stdin.flush();process.wait(timeout=60)

if __name__=='__main__':
    p=argparse.ArgumentParser()
    for name in ['server','plugin','fixture','eula','output']:p.add_argument('--'+name,type=Path,required=True)
    main(p.parse_args())
