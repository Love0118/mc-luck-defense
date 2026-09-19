"""Generate the Korean 6x6 balance report from measured results (standard library only)."""
import json
import statistics
from collections import Counter
from pathlib import Path

DATA = Path(__file__).resolve().parents[1] / "docs/simulation"
ROLES = {"MELEE_SINGLE": "근거리 단일", "MELEE_CLEAVE": "근거리 광역", "RANGED_SINGLE": "원거리 단일",
         "SMALL_AREA": "준광역", "LARGE_AREA": "대광역", "MULTI_TARGET": "다중"}


def read_rows(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def dataset(name):
    summary = json.loads((DATA / name / "summary.json").read_text(encoding="utf-8"))
    rows = read_rows(DATA / name / "runs.jsonl")
    assert len(rows) == summary["runs"]
    assert len({r["seed"] for r in rows}) == len(rows)
    assert sum(r["outcome"] == "VICTORY" for r in rows) == summary["wins"]
    for c in summary["checkpoints"]:
        assert sum(r["completedRounds"] >= c["round"] for r in rows) == c["survivors"]
    return summary, rows


def main():
    summary, runs = dataset("validation")
    stress, stress_rows = dataset("one-primordial-stress")
    simple, _ = dataset("auto-placement")
    example, examples = dataset("example-clear")
    calibration = json.loads((DATA / "calibration-summary.json").read_text(encoding="utf-8"))
    for other in [stress, simple, example, calibration]:
        for key in ["gridSize", "healthScale", "healthCurve", "bossHealthScale", "randomAlgorithm"]:
            assert other[key] == summary[key], key
    assert summary["gridSize"] == 6
    assert summary["randomAlgorithm"] == "sha256-counter-v1"
    properties = dict(line.split("=", 1) for line in (DATA.parents[1] / "src/main/resources/campaign.properties").read_text().splitlines() if line and not line.startswith("#"))
    assert int(properties["grid-size"]) == summary["gridSize"]
    assert float(properties["boss-health-scale"]) == summary["bossHealthScale"]
    assert float(properties["health-scale"]) == summary["healthScale"]
    parse_curve = lambda s: [(int(r), float(h)) for r, h in (p.split(":") for p in s.split(","))]
    assert parse_curve(properties["health-curve"]) == parse_curve(summary["healthCurve"])
    assert stress["primordialCap"] == 1 and all(r["primordial"] <= 1 for r in stress_rows)
    wins = [r for r in runs if r["outcome"] == "VICTORY"]
    assert sum(r["primordial"] < 2 for r in wins) == summary["winsBelowTwoPrimordials"]
    target = [r for r in runs if r["primordial"] == 2 and r["mythic"] >= 4]
    target_wins = [r for r in target if r["outcome"] == "VICTORY"]
    ex = examples[0]
    audit = json.loads((DATA / "random-audit.json").read_text())
    assert audit["randomAlgorithm"] == summary["randomAlgorithm"]
    assert sum(audit["speciesCounts"]) == audit["draws"]
    assert sum(r["count"] for r in audit["rarities"]) == audit["draws"]
    rarity_chi = sum((r["count"] - audit["draws"] * r["expectedRate"]) ** 2 / (audit["draws"] * r["expectedRate"]) for r in audit["rarities"])
    assert abs(rarity_chi - audit["rarityChiSquareDf8"]) < 1e-5
    assert ex["outcome"] == "VICTORY" and ex["primordial"] == 2 and ex["mythic"] >= 4
    lines = ["# 6×6 · 100라운드 밸런스 검증", "",
        "6×6 배치 칸 36개와 근접 가장자리·원거리 안쪽 우선 배치를 적용한 결과입니다. 서버와 시뮬레이터는 동일한 Java 전투·경제·웨이브 코어와 campaign.properties를 사용합니다. 기존 5×5 결과는 이번 검증에 적용하지 않습니다.", "",
        "## 라운드 생존률과 클리어", "",
        f"설정을 고정한 뒤 별도 시드 {summary['seedStart']}–{summary['seedStart'] + summary['runs'] - 1}에서 {summary['runs']:,}회를 실행했습니다. 조정용 시드는 {calibration['seedStart']}–{calibration['seedStart'] + calibration['runs'] - 1}이며 조정 표본의 클리어율은 {calibration['clearRate']:.2%}입니다.", "",
        "6×6 곡선을 조정한 뒤 난수를 SHA-256 기반으로 교체하고 별도 시드에서 다시 검증했습니다. 기존 java.util.Random 결과와 중간 체력 설정은 calibration-history.json에 과거 기록으로만 남기며 최종 표본에 합산하지 않습니다.", "",
        "생존률의 분모는 **처음 시작한 모든 판**입니다. 해당 라운드 마지막 틱의 전투까지 살아 있으면 집계합니다. 남은 적은 다음 라운드로 이어지므로 라운드 생존은 해당 웨이브 전멸과 다릅니다. 최종 클리어는 100라운드의 마지막 출현 이후 제한 시간 안에 모든 적을 처치한 경우입니다.", "",
        "| 기준 | 목표 | 관측 | 생존/전체 | Wilson 95% 신뢰구간 |", "|---|---:|---:|---:|---:|"]
    targets = {30: .5, 50: .3, 70: .1, 90: .05}
    for c in summary["checkpoints"]:
        lines.append(f"| {c['round']}라운드 종료 | {targets[c['round']]:.0%} | **{c['rate']:.3%}** | {c['survivors']:,}/{summary['runs']:,} | {c['ci95Low']:.2%}–{c['ci95High']:.2%} |")
    lines += [f"| 최종 클리어 | 약 1% | **{summary['clearRate']:.3%}** | {summary['wins']:,}/{summary['runs']:,} | {summary['ci95Low']:.3%}–{summary['ci95High']:.3%} |", "",
        f"평균 도달 라운드 {summary['meanRound']:.2f}, 평균 소환 {summary['meanSummons']:.1f}회. 위 비율은 뽑기와 자동 플레이 정책의 결과이며 백분위 승패 추첨이나 인위적인 탈락 처리는 없습니다.", "",
        "## 전장과 자동 배치", "",
        "- 25칸에서 36칸으로 확대했습니다. 가장자리 20칸, 안쪽 16칸이며 칸 간격은 3블록입니다. 순환 경로는 한 변 21블록·총 84블록입니다.",
        "- 근접은 가장자리부터 안쪽 순서, 원거리는 안쪽 16칸부터 가장자리 순서입니다. 각 줄은 북서쪽 시작·시계 방향입니다. 선호 구역이 차면 다른 빈 칸을 사용합니다.",
        "- 기존 유닛을 자동 재정렬하지 않습니다. 좌클릭 수동 이동과 판매 후 빈 칸 재사용을 지원하며 이동으로 공격 대기시간·연타 상태를 초기화하지 않습니다.",
        "- 전설 이상이 36칸을 채우면 판매 불가 규칙에 따라 추가 소환을 할 수 없습니다.",
        f"- 재배치 없는 자동 배치 비교: {simple['runs']:,}회 중 {simple['wins']}회 클리어 ({simple['clearRate']:.3%}, 95% 구간 {simple['ci95Low']:.3%}–{simple['ci95High']:.3%}). 소환·판매 정책은 같습니다.", "",
        "## 적 체력 곡선", "",
        "아래 기준점 사이를 기하 보간합니다. H(r)=H(a)×(H(b)/H(a))^((r−a)/(b−a)). 종별 계수와 편성은 waves.json에 기록합니다. 적 수가 증가하고 기존 적이 누적되므로 후반 기본 체력이 일정해도 압박은 유지됩니다.", "",
        "| 라운드 | 기본 체력 |", "|---:|---:|"]
    for pair in summary["healthCurve"].split(","):
        round_, health = pair.split(":")
        lines.append(f"| {round_} | {float(health) * summary['healthScale']:,.1f} |")
    lines += ["", f"보스 체력은 기본 체력 × (15 + 라운드/5) × **{summary['bossHealthScale']:g}**입니다. 일반 적 누적으로 중간 생존률을 조정하고 보스 계수로 최종 단일 화력 요구량을 조정했습니다. 서버와 시뮬레이터 모두 같은 공식을 사용합니다. 최종 정리 시간은 기존 60초입니다.", "",
        "## 상위 등급과 태초 조건", "",
        "등급 확률·소환 10원·판매표는 그대로입니다. 전설/에픽/신화/태초 피해 배율은 8/24/120/2400이며 태초는 같은 종 신화의 기본 피해 20배입니다. 근접 단일 연속 타격 계수는 0.06입니다. 이번 변경은 유닛 스펙을 추가 변경하지 않았습니다.", "",
        f"- 태초 2개 미만 클리어: **{summary['winsBelowTwoPrimordials']}회**.",
        f"- 태초 정확히 2개 + 신화 4개 이상: {len(target)}회 중 {len(target_wins)}회 성공 ({len(target_wins)/len(target):.2%}). 보고서의 ‘신화를 많이’는 4개 이상으로 분류했습니다.",
        f"- 태초 1개 제한 실험: 시드 {stress['seedStart']}부터 {stress['runs']:,}회 중 **{stress['wins']}회 클리어**, 95% 구간 {stress['ci95Low']:.3%}–{stress['ci95High']:.3%}.",
        "- 제한 실험은 첫 태초를 유지하고 이후 태초를 같은 종 신화로 바꿉니다. 비용·난수 사용은 동일하지만 전투 결과에 따라 이후 수입·소환 횟수는 달라질 수 있습니다. 실제 서버에는 이 제한이 없습니다.",
        f"- 예시 리플레이 시드 **{ex['seed']}**: 태초 **2개 + 신화 {ex['mythic']}개**, {ex['summons']:,}회 소환 후 클리어. 같은 시드에서 태초 1개 제한 시 패배하는 것을 회귀 테스트로 검증합니다.", "",
        "승패 코드는 태초·신화 수를 검사하지 않습니다. 실제 전투로 판정하며, 유한한 표본에서 태초 1개 승리가 관측되지 않았다는 결과는 모든 인간 전략에서 불가능하다는 증명은 아닙니다.", "",
        "| 클리어한 시도의 태초 수 | 횟수 |", "|---:|---:|"]
    for count, n in sorted(Counter(r["primordial"] for r in wins).items()):
        lines.append(f"| {count} | {n} |")
    after = read_rows(DATA / "roles-after.jsonl")
    lines += ["", "## 6×6 타입 가치 점검", "",
        "6×6에서 각 종을 같은 등급으로 고정하고 경로 커버리지가 가장 큰 고정 칸에서 180초간 측정합니다. 보스 1마리와 최대 48마리 군집을 비교하며 적 체력을 높여 오버킬을 제외합니다. 아래는 레어 등급 타입별 4종 평균입니다. 감속 지원·혼합 편성 시너지·소환 시점은 포함하지 않습니다. roles-before.jsonl은 과거 5×5 자료로 이번 비교에 사용하지 않습니다.", "",
        "| 타입 | 보스 DPS | 군집 DPS |", "|---|---:|---:|"]
    for role, label in ROLES.items():
        group = [r for r in after if r["role"] == role and r["rarity"] == "RARE"]
        lines.append(f"| {label} | {statistics.mean(r['bossDps'] for r in group):.1f} | {statistics.mean(r['crowdDps'] for r in group):.1f} |")
    lines += ["", "각 종의 보스·군집 DPS를 같은 타입 중앙값으로 나눈 평균이 0.75 미만이면 관찰 대상으로 표시합니다. 한 타입의 종합 가치를 판정하는 지표는 아닙니다.", "",
        "| 등급 | 종 | 타입 | 중앙값 대비 점수 |", "|---|---|---|---:|"]
    flagged = []
    for rarity in ["RARE", "MYTHIC", "PRIMORDIAL"]:
        for role in ROLES:
            group = [r for r in after if r["role"] == role and r["rarity"] == rarity]
            boss = statistics.median(r["bossDps"] for r in group)
            crowd = statistics.median(r["crowdDps"] for r in group)
            for r in group:
                score = (r["bossDps"] / boss + r["crowdDps"] / crowd) / 2
                if score < .75:
                    flagged.append(r["unit"])
                    lines.append(f"| {rarity} | {r['unit']} | {ROLES[role]} | {score:.2f} |")
    if not flagged:
        lines.append("| — | 해당 없음 | — | — |")
    lines += ["", "원거리 단일은 보스, 광역·다중은 군집 처리에서 역할을 유지합니다. 근접 단일은 짧은 공격 기회를 피해량 보정·연타로 보완하고 근접 광역은 전설부터 감속을 제공합니다. 고정 벤치마크만으로 하위 타입을 단정하지 않으며 관찰 대상의 실전 기여는 후속 플레이에서 확인해야 합니다.", "",
        "## 해시 기반 소환 난수", "",
        "알고리즘 식별자는 sha256-counter-v1입니다. SHA-256(고정 도메인 바이트 + 32바이트 시드 + 8바이트 카운터)를 계산하고 결과를 빅엔디언 32비트 값 8개로 나누어 순서대로 사용합니다. 카운터는 0부터 시작합니다. 서버는 게임마다 SecureRandom으로 256비트 시드를 만들고, 시뮬레이터는 64비트 숫자 시드 뒤에 24개의 0바이트를 붙입니다. 이후 난수 생성·범위 변환은 같은 코드를 사용합니다.", "",
        "범위 N의 추첨은 L=2^32−(2^32 mod N)으로 두고 unsigned 값 x≥L을 버린 후 x mod N을 사용합니다. 따라서 불완전한 마지막 구간에 의한 나머지 연산 편향을 제거합니다. 등급 [0,100000)과 종 [0,24)은 별도 값을 소비합니다. 공개 확률표는 유지하며 hash 교체 전과 같은 숫자 시드라도 결과는 달라집니다.", "",
        "java.util.Random.nextInt(bound)도 단순 나머지 편향은 피합니다. 이번 변경은 기존 추첨 편향이 입증되었다는 뜻이 아니라 48비트 상태 LCG를 명시적으로 버전이 정해진 해시 스트림으로 바꾼 것입니다. 독립 Python 해시 벡터, 블록 경계, 상위 시드 비트, 거부 구간 경계, 스트림 재현성을 자동 검증합니다. 유한한 플레이 표본만으로 난수의 완전한 독립성을 증명하지는 않습니다.", "",
        f"별도 시드 {audit['seedStart']}부터 {audit['streams']:,}개 스트림 × {audit['drawsPerStream']:,}회 = **{audit['draws']:,}회** 추첨을 점검했습니다. 종별 횟수는 {min(audit['speciesCounts']):,}–{max(audit['speciesCounts']):,}회(각 기대값 {audit['draws']/24:,.0f}회), 태초는 {audit['rarities'][-1]['count']}회(기대값 {audit['draws']*.00019:,.0f}회)입니다. Pearson 통계량은 종 {audit['speciesChiSquareDf23']:.3f}(자유도 23), 등급 {audit['rarityChiSquareDf8']:.3f}(자유도 8), 등급×종 독립성 {audit['independenceChiSquareDf184']:.3f}(자유도 184)입니다. 원본은 random-audit.json에 있습니다. 표본 점검과 구간 변환의 수학적 편향 제거는 별개입니다.", "",
        "## 정책과 검증 범위", "",
        "자동 플레이어는 0.5초마다 소환·판매·선택 후 이동 중 하나를 실행합니다. 전장이 차면 판매 가능한 약한 유닛을 판매하고 빈 칸이 있으면 소환합니다. 2초마다 빈 칸 이동의 경로 커버리지 개선을 평가합니다. 미래 뽑기와 시드를 보고 전략을 바꾸지 않습니다.", "",
        "20 TPS의 실제 코어를 끝까지 실행합니다. 평균 DPS로 승패를 근사하지 않습니다. 사람의 조작 지연·네트워크·서버 렉은 모델에 없으므로 ‘상위 1%’는 이 자동 정책의 시도당 추정치입니다. 실제 Paper 서버·접속 클라이언트의 플레이 검증은 별도로 남아 있습니다.", "",
        "## 재현", "", "~~~powershell", "mvn -B -ntp compile",
        f"java -Xmx2g -cp target/classes dev.moma.sim.SimulatorMain {summary['runs']} {summary['seedStart']} 1 target/validation BALANCED",
        f"java -Xmx2g -cp target/classes dev.moma.sim.SimulatorMain {stress['runs']} {stress['seedStart']} 1 target/stress BALANCED 1",
        f"java -Xmx2g -cp target/classes dev.moma.sim.SimulatorMain {simple['runs']} {simple['seedStart']} 1 target/auto-placement AUTO_PLACE",
        f"java -cp target/classes dev.moma.sim.SimulatorMain 1 {ex['seed']} 1 target/example BALANCED",
        "java -cp target/classes dev.moma.sim.RoleBenchmarkMain target/roles.jsonl",
        "java -cp target/classes dev.moma.sim.WaveExportMain target/waves.json",
        "java -cp target/classes dev.moma.sim.RandomAuditMain target/random-audit.json", "~~~", "",
        "CLI 인수: 실행 수, 시작 시드, 전체 체력 배율, 출력 폴더, 정책, 선택적 태초 제한, 선택적 체력 기준점 문자열, 선택적 보스 배율. 생략 시 campaign.properties의 규칙을 사용합니다.", "",
        "원본은 validation/runs.jsonl·summary.json, 비교 정책은 auto-placement/, 태초 제한은 one-primordial-stress/, 조정 결과는 calibration-summary.json에 있습니다. example-clear/replay.html은 1초 간격 상태를 담은 독립 HTML입니다. scripts/summarize_simulation.py로 보고서를 재생성합니다."]
    (DATA / "REPORT.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
