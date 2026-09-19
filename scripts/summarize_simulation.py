"""Build the Korean balance report from simulator output; Python standard library only."""
import json
import statistics
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "docs" / "simulation"
ROLES = {"MELEE_SINGLE": "근거리 단일", "MELEE_CLEAVE": "근거리 광역", "RANGED_SINGLE": "원거리 단일",
         "SMALL_AREA": "준광역", "LARGE_AREA": "대광역", "MULTI_TARGET": "다중"}


def read_rows(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def main():
    summary = json.loads((DATA / "validation" / "summary.json").read_text())
    runs = read_rows(DATA / "validation" / "runs.jsonl")
    wins = [r for r in runs if r["outcome"] == "VICTORY"]
    assert len(runs) == summary["runs"] and len(wins) == summary["wins"]
    assert sum(r["primordial"] < 2 for r in wins) == summary["winsBelowTwoPrimordials"]
    assert len({r["seed"] for r in runs}) == len(runs)
    target = [r for r in runs if r["primordial"] == 2 and r["mythic"] >= 4]
    target_wins = [r for r in target if r["outcome"] == "VICTORY"]
    lines = [
        "# 100라운드 밸런스 검증", "",
        "뽑기 확률을 유지하고 웨이브·처치 수입·유닛 성능을 조정했습니다. 서버와 시뮬레이터는 동일한 Java 코어와 campaign.properties를 사용합니다.", "",
        "## 별도 시드 검증 결과", "",
        f"- {summary['runs']:,}회 중 **{summary['wins']}회 클리어 · {summary['clearRate']:.3%}**.",
        f"- Wilson 95% 신뢰구간: **{summary['ci95Low']:.3%}–{summary['ci95High']:.3%}**.",
        f"- 시드: {summary['seedStart']}–{summary['seedStart'] + summary['runs'] - 1}. 조정용 시드 100000–104999와 분리했습니다.",
        f"- 태초 2개 미만 클리어: **{summary['winsBelowTwoPrimordials']}회**.",
        f"- 평균 도달 라운드 {summary['meanRound']:.2f}, 평균 소환 {summary['meanSummons']:.1f}회.",
        f"- 정확히 태초 2개 + 신화 4개 이상을 획득한 시도 {len(target)}회 중 {len(target_wins)}회 성공 ({len(target_wins)/len(target):.2%}).",
        "- ‘신화를 많이’는 보고서에서 4개 이상으로 분류했습니다. 이는 승리 조건에 들어가는 숫자가 아닙니다.", "",
        "태초·신화 획득 수는 소환 로그 기준이며 전설 이상은 판매 불가라서 유지됩니다. 이번 수정은 태초 2개 미만으로도 유리한 조합이 클리어하던 문제를 스펙 격차와 후반 웨이브로 해결하는 데 초점을 맞췄습니다. 승패 코드는 등급 수를 검사하지 않으며 적을 실제로 모두 처치해야 승리합니다. 유한한 표본에서 실패만 관측한 것과 모든 전략에서 수학적으로 불가능함은 다릅니다.", "",
        "| 클리어한 시도의 태초 수 | 시도 수 |", "|---:|---:|",
    ]
    for count, n in sorted(Counter(r["primordial"] for r in wins).items()):
        lines.append(f"| {count} | {n} |")
    lines += ["", "예시 성공 시드:", "", "| 시드 | 태초 | 신화 | 소환 횟수 |", "|---:|---:|---:|---:|"]
    for r in target_wins[:5]:
        lines.append(f"| {r['seed']} | {r['primordial']} | {r['mythic']} | {r['summons']} |")
    stress_path = DATA / "one-primordial-stress" / "summary.json"
    if stress_path.exists():
        stress = json.loads(stress_path.read_text())
        assert stress["healthScale"] == summary["healthScale"] and stress["lateHealthScale"] == summary["lateHealthScale"]
        assert stress["primordialCap"] == 1
        stress_rows = read_rows(DATA / "one-primordial-stress" / "runs.jsonl")
        assert len(stress_rows) == stress["runs"] and all(r["primordial"] <= 1 for r in stress_rows)
        assert sum(r["outcome"] == "VICTORY" for r in stress_rows) == stress["wins"]
        lines += ["", "## 태초 1개 제한 검증", "",
                  f"- 별도 시드 {stress['seedStart']}부터 {stress['runs']:,}회 중 **{stress['wins']}회 클리어**.",
                  f"- Wilson 95% 신뢰구간: {stress['ci95Low']:.3%}–{stress['ci95High']:.3%}.",
                  "- 시뮬레이터에만 있는 반사실 실험입니다. 첫 태초는 그대로 두고 이후 태초 뽑기는 같은 종의 신화로 낮춥니다. 소환 비용과 소비한 난수는 그대로이고 추가 신화가 보조 전력으로 남습니다.",
                  "- 실제 서버의 확률·소환·승패 규칙에는 이 제한이 없습니다.",
                  "- 회귀 시드 100124: 태초 2개·신화 7개는 클리어, 두 번째 태초를 신화로 낮춘 태초 1개·신화 8개는 실패. 두 실행의 소환 수는 동일합니다."]
    lines += ["", "## 등급 스펙과 후반 성장", "",
              "| 등급 | 기존 피해 배율 | 새 피해 배율 | 이전 등급 대비 피해 |", "|---|---:|---:|---:|",
              "| 전설 | 6 | 8 | 서사 대비 2배 |", "| 에픽 | 9 | 24 | 전설 대비 3배 |",
              "| 신화 | 40 | 120 | 에픽 대비 5배 |", "| 태초 | 80 | 2400 | 신화 대비 20배 |", "",
              "사거리·공격속도·타입은 기존 값을 유지합니다. 동일 종의 기본 DPS 격차는 이 피해 배율 이상이며, 확률과 판매표는 변경하지 않았습니다. 근접 단일의 연속 공격 배율은 1 + 0.12 × 단계 × (횟수−1)에서 1 + 0.06 × 단계 × (횟수−1)로 조정했습니다. 태초 5연타 배율은 2.92→1.96이며, 기본 보정·등급·연타가 과도하게 곱해지던 단독 캐리 사례를 줄입니다.",
              f"61라운드부터 적 체력에 1 + ({summary['lateHealthScale']:g} − 1) × (max(0, (라운드 − 60)/40))²를 추가 적용합니다. 1~60라운드 체력은 이전과 같고 100라운드에서는 기존 체력의 {summary['lateHealthScale']:g}배입니다. 보스에도 동일하게 적용합니다."]
    if (DATA / "auto-placement" / "summary.json").exists():
        simple = json.loads((DATA / "auto-placement" / "summary.json").read_text())
        assert simple["healthScale"] == summary["healthScale"] and simple["lateHealthScale"] == summary["lateHealthScale"]
        lines += ["", f"자동 배치만 사용한 비교 정책은 {simple['runs']:,}회 중 {simple['wins']}회 성공 ({simple['clearRate']:.2%}). 판매 기준과 소환 규칙은 같고 재배치만 하지 않습니다. 배치 전략이 다른 실제 플레이어의 성공률은 달라집니다."]
    lines += ["", "## 타입 가치 비교", "",
        "각 종을 레어 등급으로 고정하고, 빈 전장의 가장 넓은 경로를 커버하는 고정 칸에서 180초간 측정했습니다. 보스 1마리 및 최대 48마리 군집 두 상황을 비교하며 적 체력을 충분히 크게 해 오버킬을 배제합니다. 아래는 타입 내 4종 평균입니다. 감속의 아군 지원 가치와 실제 혼합 편성 시너지는 이 표에 포함되지 않습니다.", "",
        "| 타입 | 기존 보스 DPS | 변경 보스 DPS | 기존 군집 DPS | 변경 군집 DPS |", "|---|---:|---:|---:|---:|"]
    before, after = read_rows(DATA / "roles-before.jsonl"), read_rows(DATA / "roles-after.jsonl")
    for role, label in ROLES.items():
        old = [r for r in before if r["role"] == role and r["rarity"] == "RARE"]
        new = [r for r in after if r["role"] == role and r["rarity"] == "RARE"]
        values = [statistics.mean(row[key] for row in group) for group, key in [(old, "bossDps"), (new, "bossDps"), (old, "crowdDps"), (new, "crowdDps")]]
        lines.append("| " + label + " | " + " | ".join(f"{v:.1f}" for v in values) + " |")
    lines += ["",
        "- 근거리 단일은 짧은 사거리로 공격 기회가 적어 기본 피해를 2.8배로 보정했습니다. 가장자리 배치가 여전히 중요합니다.",
        "- 다중 공격은 제한된 대상 수에 비해 화력이 낮아 기본 피해를 1.8배로 보정했습니다. 단일 대상 중복 공격은 여전히 없습니다.",
        "- 준광역은 대광역보다 보스 중심 타격에 유리하고, 대광역은 밀집 처리에 유리합니다. 근거리 광역은 감속·군집 처리, 원거리 단일은 보스 처리가 역할입니다.",
        "- 상위 등급 피해 격차는 위 표대로 확대했습니다. 역할·사거리·등장 확률·판매 제한은 그대로입니다.",
        "- 종별 보정: 보그드 10→11.5, 라마 7→10.5, 눈골렘 5→6.5, 위더 스켈레톤 7→11.2, 알레이 4→4.8의 기본 피해를 적용했습니다. 다중 타입 계수는 이 값에 추가 적용됩니다.", "",
        "### 종별 약점 점검", "",
        "같은 타입 내 보스와 군집 DPS를 각각 중앙값으로 나눈 뒤 평균이 0.75 미만이면 검토 대상으로 표시합니다. 두 가지 고정 상황에 대한 신호이며 종의 종합 승률 판정이 아닙니다.", "",
        "| 등급 | 종 | 타입 | 타입 중앙값 대비 점수 |", "|---|---|---|---:|"]
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
    lines += ["", "## 자동 플레이 정책과 해석 범위", "",
        "- 0.5초마다 합법적인 소환·판매·선택 후 이동 중 한 동작을 수행합니다. 이동 평가는 2초마다 실행합니다.",
        "- 빈 칸이 있으면 소환하고, 전장이 차면 판매 가능한 유닛 중 사거리 커버리지·DPS·타격 수를 기준으로 가장 약한 유닛을 판매합니다.",
        "- 빈 칸으로 이동했을 때 경로 커버리지가 개선되면 이동합니다. 미래 뽑기·시드·적 체력 배율을 보고 의사결정을 바꾸지 않습니다.",
        "- 전설 이상 25칸이 차면 판매 불가 규칙에 따라 더 뽑지 못합니다. 이 상태도 시뮬레이션에 포함됩니다.",
        "- 20 TPS 고정, 네트워크 지연·화면 조작 시간·사람의 전략 변화는 모델에 없습니다. 1%는 이 정책에 대한 시도당 추정치이며 실제 사람 상위 1%라는 보장은 아닙니다.",
        "- 최종 설정의 조정용 시드 100000–104999는 0.80% 클리어이며 승리자의 태초는 모두 2개 이상이었습니다. 설정을 고정한 뒤 새 시드 3100000부터 정식 검증하고 별도 3500000부터 태초 1개 제한 검증을 수행했습니다. 중간 설정에서는 2110951 시드가 태초 1개로 클리어하여 설정을 폐기했고, 최종 설정에서 해당 시드의 실패를 회귀 테스트로 확인합니다. 이전 버전의 1.11% 결과는 현재 밸런스 결과가 아닙니다.", "",
        "## 재현", "", "~~~powershell", "mvn -B -ntp compile",
        "java -Xmx2g -cp target/classes dev.moma.sim.SimulatorMain 20000 3100000 3.05 target/validation BALANCED 20",
        "java -Xmx2g -cp target/classes dev.moma.sim.SimulatorMain 10000 3500000 3.05 target/stress BALANCED 20 1",
        "java -Xmx2g -cp target/classes dev.moma.sim.SimulatorMain 5000 3100000 3.05 target/auto-placement AUTO_PLACE 20",
        "java -cp target/classes dev.moma.sim.RoleBenchmarkMain target/roles.jsonl", "~~~", "",
        "원본 결과는 validation/runs.jsonl과 summary.json에 있습니다. 리플레이는 validation/replay.html 및 example-clear/replay.html을 브라우저에서 열면 됩니다. 1초 간격의 실제 코어 상태를 기록한 리플레이입니다."]
    (DATA / "REPORT.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
