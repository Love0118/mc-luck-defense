"""Isolated two-client regression: server NoAI stays on; managed dragon client NoAI turns off."""
import argparse
import hashlib
import json
import shutil
import subprocess
import time
from pathlib import Path


def verify(run,plugin,baseline):
    authority=json.loads((run/"dragon-server-pass.json").read_text(encoding="utf-8"))
    ids=json.loads((run/"dragon-ids.json").read_text(encoding="utf-8"))
    packet=json.loads((run/"packets.json").read_text(encoding="utf-8"))
    checks={}
    for name,state in packet["players"].items():
        assert state["position"] and not state["errors"],state["errors"]
        flags=lambda entity,key:[m["value"] for p in state["metadata"] if p["entityId"]==ids[entity] for m in p["metadata"] if m["key"]==key]
        dragon=flags("dragon",15)
        assert dragon and all((v&1)==(1 if baseline else 0) for v in dragon),dragon
        assert any(v&2 for v in dragon),"Other mob bits must survive"
        assert flags("zombie",15) and all(v&1 for v in flags("zombie",15))
        assert flags("control",15) and all(v&1 for v in flags("control",15))
        assert sum(s["id"]==ids["dragon"] for s in state["spawns"])>=2,"Retracking not tested"
        moves=[p for p in state["moves"] if p["entityId"]==ids["dragon"]]
        assert len(moves)>20,"Missing dragon movement packets"
        glow=any(v&64 for v in flags("wolf",0))
        assert glow==(name=="DragonOwner"),"Private glow regression"
        checks[name]=dict(dragonFlags=dragon,movementPackets=len(moves),privateGlow=glow)
    result=dict(baseline=baseline,server=authority,clients=checks,sha256=hashlib.sha256(plugin.read_bytes()).hexdigest())
    (run/"verification.json").write_text(json.dumps(result,indent=2)+"\n")
    print(json.dumps(result))


def main(a):
    run=a.output.resolve()
    if run.exists():raise SystemExit("Choose a fresh output directory")
    if "eula=true" not in a.eula.read_text(encoding="utf-8").lower():raise SystemExit("Accepted EULA required")
    (run/"plugins").mkdir(parents=True)
    shutil.copy2(a.eula,run/"eula.txt")
    for path in (a.plugin,a.fixture,*a.via):shutil.copy2(path,run/"plugins"/path.name)
    (run/"server.properties").write_text("server-ip=127.0.0.1\nserver-port=25586\nonline-mode=false\nenforce-secure-profile=false\nview-distance=5\nsimulation-distance=3\npause-when-empty-seconds=-1\n")
    with (run/"server.log").open("w",encoding="utf-8") as log:
        server=subprocess.Popen(["java","-Xms1g","-Xmx3g","-jar",str(a.server.resolve()),"--nogui"],
            cwd=run,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True)
        client=None
        try:
            deadline=time.monotonic()+120
            while "For help, type" not in (run/"server.log").read_text(encoding="utf-8",errors="replace"):
                if server.poll() is not None or time.monotonic()>deadline:raise RuntimeError("Startup failed")
                time.sleep(.5)
            server.stdin.write("whitelist off\n");server.stdin.flush()
            with (run/"client.log").open("w") as clientlog:
                client=subprocess.Popen(["node",str(a.client.resolve()),str(run/"packets.json")],stdout=clientlog,stderr=subprocess.STDOUT)
                server.wait(timeout=90);client.wait(timeout=15)
            verify(run,a.plugin,a.baseline)
        finally:
            if server.poll() is None:
                server.stdin.write("stop\n");server.stdin.flush();server.wait(timeout=60)
            if client and client.poll() is None:client.terminate()


if __name__=="__main__":
    p=argparse.ArgumentParser()
    for name in ("server","plugin","fixture","eula","output","client"):p.add_argument("--"+name,type=Path,required=True)
    p.add_argument("--via",type=Path,nargs="+",required=True)
    p.add_argument("--baseline",action="store_true")
    main(p.parse_args())
