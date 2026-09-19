"""Archive measured runs and generate the Korean report. JFR stays in local run folders."""
import hashlib
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/performance"
RUNS = ["final-stock-r1", "final-candidate-r1", "final-candidate-noframe", "final-campaign"]
NATIVE_RUNS = ["native-java-control", "native-java-batch", "native-jni-only", "native-both-r1", "native-campaign"]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    summaries = {}
    for name in RUNS + NATIVE_RUNS:
        source = ROOT / ".runtime" / name
        dest = OUT / name
        dest.mkdir(exist_ok=True)
        data = json.loads((source / "plugins/MudBenchmark/result.json").read_text())
        workload = json.loads((source / "plugins/MudBenchmark/workload.json").read_text())
        assert data["measuredTicks"] == len(data["mspt"])
        assert data["sessions"] == data["viewers"] == 20
        if workload["mode"] == "campaign":
            assert len(workload["outcomes"]) == 20
            assert all(o["outcome"] == "VICTORY" and o["round"] == 100 for o in workload["outcomes"])
        invocation = json.loads((source / "invocation.json").read_text())
        data["artifactSha256"] = {
            "server": digest(Path(invocation[invocation.index("-jar") + 1])),
            "plugin": digest(source / "plugins/MomaDefense.jar"),
            "benchmarkPlugin": digest(source / "plugins/MudBenchmark.jar"),
        }
        data["jfrSha256"] = digest(source / "plugins/MudBenchmark/profile.jfr")
        clients = json.loads((source / "clients.json").read_text())
        start = json.loads((source / "clients-measure-start.json").read_text())
        assert len(clients["clients"]) == 20 and all(v["ready"] and not v["errors"] for v in clients["clients"].values())
        data["clientPacketsDuringApproximateCapture"] = sum(v["packets"] - start["clients"][k]["packets"] for k, v in clients["clients"].items())
        data["clientBytesDuringApproximateCapture"] = sum(v["bytes"] - start["clients"][k]["bytes"] for k, v in clients["clients"].items())
        data["clientCaptureSeconds"] = clients["seconds"] - start["seconds"]
        data["workload"] = workload
        (dest / "result.json").write_text(json.dumps(data, indent=2) + "\n")
        for file in ["clients.json", "clients-measure-start.json", "invocation.json"]:
            shutil.copy2(source / file, dest / file)
        for prefix, file in [("-Dmud.native.entityBatch=true", "native.json"), ("-Dmud.native.library=", "native-combat.json")]:
            if any(arg.startswith(prefix) for arg in invocation):
                assert (source / "plugins/MudBenchmark" / file).is_file(), f"Missing native execution proof: {name}/{file}"
        for file in ["native.json", "native-combat.json"]:
            if (source / "plugins/MudBenchmark" / file).exists():
                counters = json.loads((source / "plugins/MudBenchmark" / file).read_text())
                assert all(value > 0 for value in counters.values())
                shutil.copy2(source / "plugins/MudBenchmark" / file, dest / file)
        summaries[name] = {k:v for k,v in data.items() if k not in ["mspt", "tpsWindows"]}
    (OUT / "summary.json").write_text(json.dumps(summaries, indent=2) + "\n")
    labels = {"final-stock-r1":"공식 Paper + 기존 플러그인", "final-candidate-r1":"26.3-mud + 최적화 플러그인 + framing", "final-candidate-noframe":"동일 후보 · framing 꺼짐", "final-campaign":"100라운드 실제 캠페인 20세션"}
    lines = ["# Paper 26.3 · MUD 20세션 성능 검증", "",
        "**320 TPS 지속 유지는 달성하지 못했습니다.** 실제 20개 TCP 클라이언트가 연결된 서버에서 검증했습니다. 최적화 후보는 100라운드 전체 실행에서는 목표에 근접하지만, 적 한도 근처의 밀집 부하에서는 목표보다 느립니다.", "",
        "## 최종 측정", "", "| 실행 | 실제 TPS | 평균 MSPT | p95 | p99 | 3.125ms 초과 틱 |", "|---|---:|---:|---:|---:|---:|"]
    for name in RUNS:
        r = summaries[name]
        lines.append(f"| {labels[name]} | **{r['actualTps']:.2f}** | {r['meanMspt']:.3f} | {r['p95Mspt']:.3f} | {r['p99Mspt']:.3f} | {r['ticksOverBudget']:,}/{r['measuredTicks']:,} ({r['ticksOverBudget']/r['measuredTicks']:.2%}) |")
    base, candidate = summaries[RUNS[0]], summaries[RUNS[1]]
    noframe, campaign = summaries[RUNS[2]], summaries[RUNS[3]]
    lines += ["", f"최종 밀집 비교에서 평균 틱 시간이 {base['meanMspt']:.3f}→{candidate['meanMspt']:.3f}ms로 **{1-candidate['meanMspt']/base['meanMspt']:.2%} 감소**했습니다. framing on/off 한 쌍에서는 {noframe['meanMspt']:.3f}→{candidate['meanMspt']:.3f}ms로 {1-candidate['meanMspt']/noframe['meanMspt']:.2%} 감소했습니다. 각 최종 설정을 여러 번 번갈아 반복한 통계는 아니므로 보편적인 성능 향상률로 해석하지 않습니다.", "",
        f"최종 캠페인은 {campaign['measuredTicks']:,}틱을 {campaign['seconds']:.2f}초에 실행했습니다. 모든 20세션이 100라운드를 클리어했고 최대 아군 720마리·적 700마리였습니다. 부하가 도중에 소멸하지 않도록 검증된 성공 시드 101476을 모든 세션에 사용했습니다. 이는 클리어 확률 실험이 아닙니다. 준비·정리 시간까지 포함한 같은 코어의 소환·판매·재배치·처치 수입을 실행했습니다.", "",
        "## 부하와 측정 방법", "",
        "- Ryzen 5 9600X(6코어/12스레드), 32GB RAM, Windows 11, Microsoft OpenJDK 25.0.3. 서버 힙 4GB 고정. 별도의 기존 Paper 26.2 프로세스가 실행 중이었으며 종료하지 않았습니다.",
        "- 공식 Paper 26.3 build 19(b4c7d1686d)와 같은 기반의 26.3-mud 소스를 비교합니다. 빌드19 SHA-256: f623c073913db7f21c6338eef22a00b19a8d87c1ef3115c2060d2382b90f4650.",
        "- 밀집 부하는 아군 36×20=720, 적 90×20=1800을 계속 유지합니다. 공격·감속·이동·추적은 실제로 실행하고 적 체력만 1e15로 고정해 측정 중 사망에 의한 부하 감소를 막습니다. 자연 웨이브 곡선을 재현하는 실험과 구분합니다.",
        "- 20개 실제 localhost TCP 연결, 프로토콜 777, 압축 threshold 256, view/simulation distance 3. 클라이언트는 모든 프레임을 수신·압축 해제하고 keepalive·teleport·chunk batch에 응답합니다. 화면 렌더링, WAN 지연, 온라인 인증·암호화는 포함하지 않습니다.",
        "- tick rate 320으로 설정한 뒤 밀집은 3200틱 준비·6400틱 측정, 캠페인은 3200틱 준비·61520틱 측정합니다. JFR은 측정 320틱 전에 시작했습니다. 매 틱 NMS tickTimesNanos에서 직전 틱 소요시간을 읽고 실제 TPS는 틱 수/벽시계 시간으로 계산합니다. 320틱 구간 TPS와 모든 원본 MSPT도 저장합니다.",
        "- 기준은 틱당 3.125ms입니다. 평균이 낮더라도 지연 틱이 있으므로 ‘항상 320 TPS 유지’로 표현하지 않습니다. 서버 OSHI 시스템 정보 조회 오류가 모든 변형의 시작 시 나타났으나 실제 캠페인/패킷 처리 오류는 없었습니다.", "",
        "## 패킷 검증", "",
        "| 실행 | 수신 패킷 | 수신 bytes | 대략적인 수신 MB/s |", "|---|---:|---:|---:|"]
    for name in RUNS:
        r = summaries[name]
        lines.append(f"| {labels[name]} | {r['clientPacketsDuringApproximateCapture']:,} | {r['clientBytesDuringApproximateCapture']:,} | {r['clientBytesDuringApproximateCapture']/r['clientCaptureSeconds']/1e6:.3f} |")
    lines += ["", "수신 카운트는 1초 스냅샷과 프로세스 종료 시점 기준으로 틱 측정 구간과 약간 다릅니다. TCP 패킷 수가 아닌 Minecraft 프레임 수입니다. 후보가 더 빨리 틱을 처리하므로 초당 트래픽이 늘어날 수 있으며 이것을 최적화 실패로 판정하지 않습니다. framing은 복사를 줄이며 프로토콜 payload 크기나 codec 호출을 제거하지 않습니다.", "",
        "retained slice·in-place prefix는 26.3 build19 바이트코드에 없는 것을 확인한 뒤 26.2 포크에서 선택 이식했습니다. 압축 on/off 왕복, VarInt 경계, fragmented/coalesced 입력, 공유 버퍼 fallback을 테스트했습니다. 별도 paranoid Netty leak detection 20클라이언트 canary에서 누출 보고가 없었으며 그 실행의 시간은 성능 비교에 사용하지 않았습니다.", "",
        "## 적용한 변경과 이식 판단", "",
        "플러그인: 정지 포탑의 불필요한 teleport 생략(실제 위치를 확인하므로 밀려나면 복구), noPhysics, 전용 월드 자연 스폰 중지, 타격 효과를 적당 1회/틱으로 합치기, 매틱 엔티티 목록 복사 제거, 선택 가능한 서버 전용 연속 이동 경로. 피해·감속·쿨다운·뽑기·재화 규칙은 유지합니다.", "",
        "서버: MUD 태그 + AI/물리/중력 꺼짐 + 무적·생존·탑승 없음 조건에서만 바닐라 게임플레이 틱을 생략합니다. commonTick과 엔티티 추적은 유지합니다. 전용 이동은 같은 월드의 로드된 청크에서만 허용하며 일반 teleport 이벤트를 호출하지 않는 별도 계약입니다. 수동 재배치는 기존 teleport입니다.", "",
        "| 기존 포크 요소 | 26.3/MUD 판단 |", "|---|---|",
        "| Pathetic 공유 경로·역방향 필드 | MUD는 고정 경로를 자체 계산, AI 꺼짐 → 적용 불필요 |",
        "| AI 간격·managed Bukkit damage | MUD는 AI·Bukkit damage 경로를 사용하지 않음 → 제외 |",
        "| retained frames·in-place prefix | build19에 없음 → 선택 이식·개별 설정 |",
        "| Velocity 압축·flush consolidation·Moonrise 추적 | Paper 26.3에 이미 있음 → 중복 이식하지 않음 |",
        "| 추가 PLAY 배칭·viewer LOD | 기존 기능과 완전히 같지는 않지만 이번 프로파일의 주 병목이 아님 → 보류 |",
        "| 대량 블록/폭발 최적화 | MUD 실행 부하에 없음 → 제외 |", "",
        "프로파일의 주요 비용은 바닐라 틱·월드 조회·추적이었습니다. 먼저 불필요한 작업을 제거하는 Java 변경을 적용하고, 이후 요청에 따라 아래 Rust FFM/JNI 실험을 추가했습니다. 다음 후보는 세션별 가시성/추적 비용과 월드 조회, 연속 이동 빈도·시각 보간이며 행동 및 화면 품질 검증이 필요합니다.", "",
        "## 실행과 결과 파일", "",
        "서버 브랜치: https://github.com/Love0118/paper-pathetic-mobs-fork/tree/26.3-mud. Java 최적화 커밋은 60d633037d, JNI·Java 배치 대조군을 포함한 최종 커밋은 c4813f8c27입니다. 기존 26.2 beta는 변경하지 않았습니다. 서버 설정은 해당 브랜치 MUD_26_3.md를 참고합니다. 세 옵션은 기본 꺼짐입니다.", "",
        "~~~powershell", "mvn -B -ntp verify",
        ".\\scripts\\build_server_benchmark.ps1 -PluginJar target/mc-luck-defense-0.5.1.jar -PaperLibraries .runtime/server-discovery/libraries",
        "python scripts/run_server_benchmark.py --server <26.3-mud.jar> --plugin target/mc-luck-defense-0.5.1.jar --eula <기존에동의한-eula.txt> --output .runtime/new-run --template .runtime/bench-pilot --warmup 3200 --ticks 61520 --mud-tick --framing --campaign", "~~~", "",
        "템플릿을 생략하면 전장을 새로 생성합니다. 테스트 서버는 127.0.0.1:25585에서만 열고 기존 EULA 동의 파일을 요구합니다. benchmarks/server는 별도 테스트 플러그인이며 제품 JAR에 포함되지 않습니다. .runtime의 각 실행 폴더에 전체 로그·JFR이 남고, docs/performance에는 틱 원본·수신 카운트·JFR 해시와 실행별 서버·플러그인·벤치마크 JAR 해시를 보존합니다.", "",
        "이전 pilot은 JFR 시작 직후 스파이크와 짧은 warmup 영향이 있어 최종 표에 합산하지 않았습니다. pilot 범위: stock 78 TPS, 플러그인 84→99 TPS, 서버 틱 174 TPS, 이동 185 TPS, 긴 combined pilot 222 TPS. 호스트 변동을 보여주는 참고치입니다.", "",
        "## Rust FFM / 실제 Java 객체 JNI 비교", "",
        "Rust 모듈은 두 부분으로 구현했습니다. FFM 전투 모듈은 세션별 수치 배열로 타깃·거리·부채꼴·광역·순차 피해를 계산합니다. JNI 엔티티 모듈은 **실제 NMS Entity 객체**를 받아 캐시한 jmethodID로 setPos와 회전을 호출합니다. 객체 raw 메모리를 덮어쓰지 않으며 JVM의 GC 참조·청크 인덱스·바운딩 박스 갱신을 유지합니다. 이동 배치와 JNI 경계 자체를 구분하기 위해 동일 구조의 Java 배치 대조군도 추가했습니다.", "",
        "| 경로 | 실제 TPS | 평균 MSPT | p95 | p99 |", "|---|---:|---:|---:|---:|"]
    native_labels = {"native-java-control":"Java 개별 이동·Java 전투", "native-java-batch":"Java 배치 이동·Java 전투", "native-jni-only":"Rust JNI 객체 이동·Java 전투", "native-both-r1":"Rust JNI 객체 이동 + Rust FFM 전투", "native-campaign":"Rust 두 모듈 · 100라운드 20세션"}
    for name in NATIVE_RUNS:
        r = summaries[name]
        lines.append(f"| {native_labels[name]} | **{r['actualTps']:.2f}** | {r['meanMspt']:.3f} | {r['p95Mspt']:.3f} | {r['p99Mspt']:.3f} |")
    native = summaries["native-campaign"]
    lines += ["", "**현재 구현에서는 Rust를 기본으로 켜지 않는 것을 권장합니다.** JNI만 사용해도 Java 배치보다 빠르지 않았고 두 Rust 모듈을 함께 쓰면 밀집 성능이 악화됐습니다. 수치 배열 구성·결과 검증·JNI→Java 콜백 비용을 포함한 실제 결과입니다. 언어만 바꾸면 바닐라 추적·월드 객체 처리가 사라지지 않습니다. 모든 native 옵션은 기본 꺼짐으로 제공합니다.", "",
        f"Rust 전체 캠페인은 {native['seconds']:.2f}초, {native['actualTps']:.2f} TPS이며 {native['ticksOverBudget']:,}/{native['measuredTicks']:,}틱이 3.125ms를 초과했습니다. 결과는 {sum(o['outcome']=='VICTORY' for o in native['workload']['outcomes'])}/20세션 클리어입니다. 사용 시드와 전투 규칙은 Java 캠페인과 같습니다.", "",
        "검증: Rust 3개 단위 테스트·Clippy, native 활성화한 Java 120개 테스트, 30개 편성×400틱의 명중 순서·체력·진행·감속·쿨다운 일치, 전체 50시드 결과 JSONL byte 일치. JNI는 10,000배치 객체 변경·배열 경계·NaN 거절·예외 전파·GC 참조 수명 테스트를 통과했습니다. 실제 서버 -Xcheck:jni canary에서 MUD native 함수의 경고는 없었습니다. JNA/OSHI 초기화 경고는 라이브러리 로드 이전에 발생하여 별도로 기록했습니다.", "",
        "모든 native 서버 실행은 실제 배치 카운터를 확인하며 core 위치와 NMS 위치도 320틱마다 비교합니다. 로드 실패나 미사용을 정상적인 native 성공으로 집계하지 않습니다. raw 힙 오프셋·GC 중 객체 고정·서버 월드의 비동기 수정은 사용하지 않습니다. 향후 더 큰 개선은 native가 상태를 장기간 소유하고 Java 접촉을 줄이는 구조나 패킷 전용 외형/추적 분리처럼 책임 경계를 바꾸어야 합니다.", "",
        "실행 방법: native/mud-combat/README.md 및 서버 저장소 native/mud-entities/README.md. 서버의 native 코드는 26.3-mud 브랜치 c4813f8c27에 푸시했습니다. Linux JNI CI도 성공했습니다. native.json/native-combat.json에 실제 실행 횟수를 보존합니다."]
    (OUT / "REPORT.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
