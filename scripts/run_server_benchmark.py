"""Run an isolated localhost Paper benchmark; requires an existing accepted EULA file."""
import argparse
import json
import shutil
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main(a):
    if a.campaign and a.session_speed != 1:
        raise SystemExit("The campaign auto-player currently supports only session speed 1")
    run = a.output.resolve()
    if run.exists():
        raise SystemExit("Choose a fresh output directory")
    if "eula=true" not in a.eula.read_text().lower():
        raise SystemExit("An existing accepted EULA file is required")
    run.mkdir(parents=True)
    (run / "plugins").mkdir()
    shutil.copy2(a.eula, run / "eula.txt")
    shutil.copy2(a.plugin, run / "plugins/MCLuckDefense.jar")
    shutil.copy2(ROOT / ".runtime/mud-benchmark.jar", run / "plugins/MudBenchmark.jar")
    if a.template:
        for name in ["world", "moma_arenas", "config"]:
            if (a.template / name).exists():
                shutil.copytree(a.template / name, run / name)
        source = a.template / "plugins/MCLuckDefense"
        if not source.exists():
            source = a.template / "plugins/MomaDefense"  # Existing pre-rename templates.
        if source.exists():
            shutil.copytree(source, run / "plugins/MCLuckDefense")
    if a.mud_tick or a.framing:
        config = run / "config/paper-global.yml"
        config.parent.mkdir(exist_ok=True)
        with config.open("a") as out:
            out.write("\nmud-optimizations:\n")
            out.write(f"  presentation-mob-tick: {str(a.mud_tick).lower()}\n")
            out.write(f"  retained-frames: {str(a.framing).lower()}\n")
            out.write(f"  in-place-frame-prefix: {str(a.framing).lower()}\n")
    properties = """server-ip=127.0.0.1
server-port=25585
online-mode=false
enforce-secure-profile=false
white-list=false
enforce-whitelist=false
spawn-protection=0
view-distance=3
simulation-distance=3
max-players=30
allow-flight=true
pause-when-empty-seconds=-1
network-compression-threshold=256
generate-structures=false
level-type=minecraft:flat
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains"}
"""
    (run / "server.properties").write_text(properties)
    cmd = ["java", "-Xms4g", "-Xmx4g", "-Dio.netty.leakDetection.level=paranoid" if a.leaks else "-Dio.netty.leakDetection.level=simple",
           f"-Dmudbench.warmup={a.warmup}", f"-Dmudbench.measure={a.ticks}", f"-Dmudbench.campaign={str(a.campaign).lower()}", "-jar", str(a.server.resolve()), "--nogui"]
    if a.native_combat:
        cmd[1:1] = ["--enable-native-access=ALL-UNNAMED", f"-Dmud.native.library={a.native_combat.resolve()}"]
    cmd[1:1] = [f"-Dmudbench.targetTps={a.target_tps}", f"-Dmudbench.sessionSpeed={a.session_speed}"]
    if a.native_entities:
        cmd[1:1] = ["--enable-native-access=ALL-UNNAMED", "-Dmud.native.entityBatch=true", f"-Dmud.native.entities={a.native_entities.resolve()}"]
    if a.check_jni:
        cmd.insert(1, "-Xcheck:jni")
    if a.java_batch:
        cmd[1:1] = ["-Dmud.native.entityBatch=true", "-Dmud.native.entities.javaControl=true"]
    (run / "invocation.json").write_text(json.dumps(cmd))
    log = (run / "console.log").open("w", encoding="utf-8")
    server = subprocess.Popen(cmd, cwd=run, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True)
    clients = None
    try:
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if server.poll() is not None:
                raise RuntimeError("Server exited during startup")
            if "For help, type" in (run / "console.log").read_text(encoding="utf-8", errors="replace"):
                break
            time.sleep(.5)
        else:
            raise TimeoutError("Server startup")
        clients_log = (run / "clients.log").open("w")
        clients = subprocess.Popen(["python", str(ROOT / "scripts/benchmark_clients.py"), "--count", "20", "--output", str(run / "clients.json")], stdout=clients_log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 900
        measured = False
        while server.poll() is None:
            if time.monotonic() > deadline:
                raise TimeoutError("Benchmark exceeded 15 minutes")
            if not measured and "BENCH_MEASURE" in (run / "console.log").read_text(encoding="utf-8", errors="replace"):
                if (run / "clients.json").exists():
                    shutil.copy2(run / "clients.json", run / "clients-measure-start.json")
                    measured = True
            time.sleep(.25)
        clients.wait(timeout=15)
        result = run / "plugins/MudBenchmark/result.json"
        if not result.exists():
            raise RuntimeError("No benchmark result; inspect console.log")
        data = json.loads(result.read_text())
        print(json.dumps({k:v for k,v in data.items() if k not in ["mspt","tpsWindows"]}), flush=True)
    finally:
        if server.poll() is None:
            server.stdin.write("stop\n"); server.stdin.flush()
            try:
                server.wait(timeout=30)
            except subprocess.TimeoutExpired:
                server.terminate()
        if clients and clients.poll() is None:
            clients.terminate()
        log.close()


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--server", type=Path, required=True)
    p.add_argument("--plugin", type=Path, required=True)
    p.add_argument("--eula", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--template", type=Path)
    p.add_argument("--warmup", type=int, default=6400)
    p.add_argument("--ticks", type=int, default=19200)
    p.add_argument("--leaks", action="store_true")
    p.add_argument("--mud-tick", action="store_true")
    p.add_argument("--framing", action="store_true")
    p.add_argument("--campaign", action="store_true")
    p.add_argument("--target-tps", type=int, choices=[20, 320], default=320)
    p.add_argument("--session-speed", type=int, choices=[1, 2, 4, 8], default=1)
    p.add_argument("--native-combat", type=Path)
    p.add_argument("--native-entities", type=Path)
    p.add_argument("--check-jni", action="store_true")
    p.add_argument("--java-batch", action="store_true")
    main(p.parse_args())
