"""Publish same-seed results, separating the exploratory tail from the final curve."""
import gzip
import json
import shutil
from pathlib import Path
from summarize_reach_curve import summarize

root = Path("docs/simulation/relic-floor-curve")
datasets, rows = {}, {}
for source, name in [("comparison-baseline", "baseline"), ("refined-validation", "validation"), ("final-validation", "exploratory-tail")]:
    directory = Path("target/curve-tuning") / source
    summarize(directory)
    destination = root / name
    destination.mkdir(parents=True, exist_ok=True)
    for file in ["summary.json", "rules.json", "economy.json", "runs.jsonl.gz"]:
        shutil.copy2(directory / file, destination / file)
    datasets[name] = json.loads((directory / "summary.json").read_text())
    with gzip.open(destination / "runs.jsonl.gz", "rt", encoding="utf-8") as stream:
        rows[name] = {row["seed"]: row for row in map(json.loads, stream)}
assert rows["baseline"].keys() == rows["validation"].keys() == rows["exploratory-tail"].keys()
assert len({(root / name / "economy.json").read_text() for name in datasets}) == 1
for seed, row in rows["validation"].items():
    assert [c for c in row["checkpoints"] if c["round"] <= 100] == [c for c in rows["exploratory-tail"][seed]["checkpoints"] if c["round"] <= 100]
baseline, final, tail = [datasets[name] for name in ["baseline", "validation", "exploratory-tail"]]
old = {p["round"]: p for p in baseline["checkpoints"]}
lines = ["# 유물 이상 후반 뽑기 · 라운드 도달 곡선", "",
         "최종 판매가·확률로 같은 5,000개 시드(18000000~18004999)의 실제 전투를 비교했습니다. 100라운드 약 32%, 200라운드 약 18% 도달이 목표입니다.", "",
         "| 도달 라운드 | 체력 변경 전 | 최종 체력 | 최종 95% 구간 |", "|---:|---:|---:|---:|"]
for p in final["checkpoints"]:
    lines.append(f"| {p['round']} | {old[p['round']]['rate']:.2%} | {p['rate']:.2%} ({p['reached']}/5000) | {p['ci95'][0]:.2%}–{p['ci95'][1]:.2%} |")
lines += ["", f"300라운드 도달자 중 400라운드 도달률: 변경 전 {baseline['conditional400Given300']:.2%}, 최종 {final['conditional400Given300']:.2%}.", "",
          "## 검증 범위", "",
          "- 도달은 해당 라운드 진입입니다. 생존 완료 횟수는 summary.json의 completed에 별도 기록합니다.",
          "- 16000000대 시드로 초반 튜닝 후 18000000대 표본에서 200라운드 체력을 200만에서 250만으로 한 번 조정했습니다. 최종 표본은 완전히 독립된 검증이 아닙니다.",
          "- 실제 서버와 같은 Java 코어로 틱별 적 생성·이동·전투·재화·소환·판매·승급을 계산합니다. 도달률로 승패를 강제하지 않습니다.",
          "- 자동 플레이어는 미래 뽑기를 보지 않고 2틱마다 최대 한 번 거래·이동합니다. 인간의 자동판매 설정·배속·입력 편차까지 재현하지 않습니다.",
          "- 최종 곡선은 500라운드까지 5,000판 검증했습니다. 300~400의 낮은 탈락률과 2000라운드 희귀 도달 목표는 후반 추가 조정 대상으로 남습니다.", "",
          "## 2000라운드 탐색 실행 — 최종 곡선과 구분", "",
          "같은 경제·시드로 200라운드 체력이 200만인 후보를 실행했습니다. 최종 250만 설정과 다르므로 최종 2000라운드 도달률이 아닙니다. 100라운드까지 시드별 재화·소환·판매 기록은 최종 실행과 일치함을 검사했습니다.", ""]
for p in tail["checkpoints"]:
    if p["round"] in [100, 200, 300, 400, 500, 1000, 1500, 2000]:
        lines.append(f"- {p['round']}라운드: {p['rate']:.2%} ({p['reached']}/5000)")
lines += ["", "## 최종 체력 기준점", "", final["rules"]["healthCurve"], "",
          "각 실행의 rules.json, economy.json, runs.jsonl.gz, summary.json에 당시 설정과 결과를 보관합니다. 중단한 이전 경제 후보들은 포함하지 않습니다."]
(root / "REPORT.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
