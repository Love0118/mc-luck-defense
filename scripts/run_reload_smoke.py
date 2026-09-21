"""Isolated live-session code update, compatibility rejection and automatic/explicit rollback."""
import argparse
import hashlib
import json
import shutil
import subprocess
import time
from pathlib import Path


def main(a):
    run=a.output.resolve()
    if run.exists():raise SystemExit("Choose a fresh output directory")
    if "eula=true" not in a.eula.read_text(encoding="utf-8").lower():raise SystemExit("Accepted EULA required")
    (run/"plugins").mkdir(parents=True);shutil.copy2(a.eula,run/"eula.txt")
    shutil.copy2(a.plugin,run/"plugins/MCLuckDefense.jar")
    for path in (a.fixture,*a.via):shutil.copy2(path,run/"plugins"/path.name)
    shutil.copytree(a.candidates,run/"candidates")
    (run/"server.properties").write_text("server-ip=127.0.0.1\nserver-port=25585\nonline-mode=false\nenforce-secure-profile=false\nview-distance=4\nsimulation-distance=3\npause-when-empty-seconds=-1\n")
    with (run/"server.log").open("w",encoding="utf-8") as log:
        server=subprocess.Popen(["java","-Xms1g","-Xmx3g","-jar",str(a.server.resolve()),"--nogui"],cwd=run,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True)
        client=None
        try:
            deadline=time.monotonic()+120
            while "For help, type" not in (run/"server.log").read_text(encoding="utf-8",errors="replace"):
                if server.poll() is not None or time.monotonic()>deadline:raise RuntimeError("Startup failed")
                time.sleep(.5)
            server.stdin.write("whitelist off\n");server.stdin.flush()
            with (run/"client.log").open("w") as clientlog:
                client=subprocess.Popen(["node",str(a.client.resolve()),str(run/"clients.json")],stdout=clientlog,stderr=subprocess.STDOUT)
                server.wait(timeout=150);client.wait(timeout=15)
            proof=json.loads((run/"reload-pass.json").read_text(encoding="utf-8"))
            clients=json.loads((run/"clients.json").read_text(encoding="utf-8"))
            for state in clients["players"].values():assert state["position"] and state["completed"] and not state["errors"],state
            proof["clients"]=clients;proof["sha256"]=hashlib.sha256(a.plugin.read_bytes()).hexdigest()
            proof["protocolClientParticleWarnings"]=(run/"client.log").read_text(encoding="utf-8",errors="replace").count("PartialReadError: Read error")
            assert proof["protocolClientParticleWarnings"]==0,"Protocol client could not decode particles"
            (run/"verification.json").write_text(json.dumps(proof,indent=2)+"\n")
            print(json.dumps(proof))
        finally:
            if server.poll() is None:
                server.stdin.write("stop\n");server.stdin.flush();server.wait(timeout=60)
            if client and client.poll() is None:client.terminate()


if __name__=="__main__":
    p=argparse.ArgumentParser()
    for name in ("server","plugin","fixture","candidates","eula","output","client"):p.add_argument("--"+name,type=Path,required=True)
    p.add_argument("--via",type=Path,nargs="+",required=True)
    main(p.parse_args())
