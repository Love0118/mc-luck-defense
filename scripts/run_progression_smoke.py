"""Validate progression mechanics on a localhost Paper server."""
import argparse, json, shutil, subprocess, time
from pathlib import Path

def main(a):
    run=a.output.resolve()
    if run.exists(): raise SystemExit("Choose a fresh output directory")
    if "eula=true" not in a.eula.read_text().lower(): raise SystemExit("Accepted EULA required")
    (run/"plugins").mkdir(parents=True)
    shutil.copy2(a.eula,run/"eula.txt")
    for path in [a.plugin,a.fixture,*a.via]: shutil.copy2(path,run/"plugins"/path.name)
    settings = dict([('server-ip','127.0.0.1'),('server-port','25589'),('online-mode','false'),('white-list','false'),('enforce-whitelist','false'),('enforce-secure-profile','false'),('view-distance','3'),('simulation-distance','3'),('pause-when-empty-seconds','-1')])
    (run/"server.properties").write_text(chr(10).join(k+'='+v for k,v in settings.items()),encoding="utf-8")
    for attempt in [1]:
        with (run/f"server-{attempt}.log").open("w",encoding="utf-8") as log:
            server=subprocess.Popen(["java","-Xms1g","-Xmx3g","-jar",str(a.server.resolve()),"--nogui"],cwd=run,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True)
            client=None
            try:
                end=time.monotonic()+120
                while "For help, type" not in (run/f"server-{attempt}.log").read_text(encoding="utf-8",errors="replace"):
                    if server.poll() is not None or time.monotonic()>end: raise RuntimeError("Startup failed")
                    time.sleep(.5)
                server.stdin.write("whitelist off"+chr(10));server.stdin.flush()
                with (run/f"client-{attempt}.log").open("w") as out:
                    client=subprocess.Popen(["node",str(a.client.resolve()),str(run/f"packets-{attempt}.json")],stdout=out,stderr=subprocess.STDOUT)
                    server.wait(timeout=90);client.wait(timeout=15)
                required=run/"progression-smoke.json"
                print(required.read_text(encoding="utf-8"))
                data=json.loads((run/f"packets-{attempt}.json").read_text(encoding="utf-8"))
                assert data["position"] and not data["errors"],data
            finally:
                if server.poll() is None:
                    server.stdin.write("stop\n");server.stdin.flush();server.wait(timeout=60)
                if client and client.poll() is None:client.terminate()

if __name__=="__main__":
    parser=argparse.ArgumentParser()
    for name in ["server","plugin","fixture","eula","output","client"]:parser.add_argument("--"+name,type=Path,required=True)
    parser.add_argument("--via",type=Path,nargs="+",required=True)
    main(parser.parse_args())
