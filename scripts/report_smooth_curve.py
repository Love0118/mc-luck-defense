"""Archive tuning and untouched validation cohorts, with actual inter-round attrition."""
import json
import shutil
from pathlib import Path
from summarize_reach_curve import summarize

root = Path("docs/simulation/smooth-200-700")
root.mkdir(parents=True, exist_ok=True)
for source, name in [("f", "tuning"), ("validation", "independent-check"), ("refined", "validation")]:
    directory = Path("target/smooth-curve") / source
    summarize(directory)
    output = root / name
    output.mkdir(exist_ok=True)
    for filename in ["rules.json", "economy.json", "summary.json", "runs.jsonl.gz"]:
        shutil.copy2(directory / filename, output / filename)
summary = json.loads((root / "validation/summary.json").read_text())
properties = Path("src/main/resources/campaign.properties").read_text()
curve = next(line.split("=", 1)[1] for line in properties.splitlines() if line.startswith("health-curve="))
def prefix(spec):
    return [(int(r), float(h)) for r, h in (item.split(":") for item in spec.split(",")) if int(r) <= 700]
assert prefix(curve) == prefix(summary["rules"]["healthCurve"])
assert prefix(curve)[-1][0] == summary["rules"]["cap"] == 700
assert (root / "validation/economy.json").read_text() == (root / "tuning/economy.json").read_text()
targets = {200: "약 22%", 300: "8~9%", 400: "5~6%", 500: "2~3%", 600: "1~2%", 700: "1% 미만"}
lines = ["# 200~700라운드 도달 곡선 조정", "",
         "4,096개 보정 시드(19000000~19004095)로 후보를 선택한 뒤, 별도의 10,000개 시드(20000000~20009999)로 검사했습니다. 그 결과를 확인하고 300·400 체력을 조금 낮춘 뒤 동일한 10,000개 시드를 최종 재실행했습니다. 최종 수치는 독립된 미사용 표본이 아닌 추가 보정 표본입니다. 모든 실행은 같은 Java 전투 코어와 유물 이상 뽑기·1/3/5 하위 판매가를 사용합니다.", "",
         "| 라운드 | 목표 | 도달률 | 도달 판수 | 95% Wilson 구간 |", "|---:|---|---:|---:|---:|"]
for p in summary["checkpoints"]:
    lines.append(f"| {p['round']} | {targets.get(p['round'], '관찰')} | {p['rate']:.2%} | {p['reached']}/10000 | {p['ci95'][0]:.2%}–{p['ci95'][1]:.2%} |")
lines += ["", f"300 도달자 중 400 도달률: {summary['conditional400Given300']:.2%}.", "",
          "## 곡선이 어긋났던 이유", "",
          "- 이전 체력 기준점은 100→200에서 16,000→2,500,000(156.25배), 200→300에서 2,500,000→3,000,000(1.2배)로 증가율이 불균형했습니다.",
          "- 필드·적 마릿수는 100라운드마다 반복합니다. 각 주기 시작에는 적 수가 줄어들어 정체 구간이 생기고, 후반에는 적 수와 체력이 함께 증가합니다.",
          "- +20 승급과 확률적 상위 유닛 획득 때문에 플레이어 화력은 일정 비율로 증가하지 않습니다. 체력 보간이 부드럽다고 도달률도 부드러워지는 것은 아닙니다.", "",
          "## 변경과 한계", "",
          "100까지의 체력·재화·확률·공격·웨이브 구성은 유지했습니다. 150과 200 체력을 조정하고, 250~700에 50라운드 간격 기준점을 배치했습니다. 승패를 확률로 강제하지 않습니다.",
          "주요 100라운드 단위 도달률을 목표로 맞춘 결과입니다. 반복 웨이브 특성상 각 블록 전반부의 낮은 탈락률은 남아 있으며, 매 라운드 일정하게 탈락하는 곡선은 아닙니다. summary.json의 deathsByDecade와 50라운드 체크포인트에서 확인할 수 있습니다.",
          "검증은 700라운드에서 종료하며 도달은 진입 기준입니다. 라운드 완료 수는 completed에 별도 저장합니다. 자동 플레이어의 수치로 사람의 조작·자동판매·배속 편차를 포함하지 않습니다.",
          "최종 실행은 최종 설정과 같은 곡선으로 700까지 검증했습니다. 앞선 후보들의 1000 이후 기준점은 최종 설정과 다릅니다. 최종 1000/1500/2000 기준점은 변경 전 값으로 유지했으며 700 이후 도달률이나 2000 희귀 성공률을 재검증한 결과는 아닙니다.", "",
          "## 최종 설정", "", curve, "",
          "## 재현", "", "java -Xmx3g -cp target/classes dev.moma.sim.EndlessSimulatorMain 10000 700 20000000 target/recheck-smooth", "",
          "python scripts/summarize_reach_curve.py target/recheck-smooth", "",
          "각 실행의 설정, 경제표, 시드별 도달/완료 기록은 함께 보관한 rules.json, economy.json, runs.jsonl.gz, summary.json을 참고하세요."]
(root / "REPORT.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
