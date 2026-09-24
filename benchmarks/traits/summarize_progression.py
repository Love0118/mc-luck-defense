"""Validate matched-seed simulation records and produce reproducible progression statistics."""
import argparse
from collections import Counter
import csv
import gzip
import hashlib
import json
import math
from pathlib import Path

SCENARIOS = ("none", "equipped", "top_growth", "top_combat", "top_fusion", "top_income_damage")
ROUNDS = (30, 60, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000, 1250, 1500, 1750, 2000, 2100, 2250, 2400,
          2500, 3000, 4000, 5000, 6000, 7500, 9000, 10000)
NAMES = dict(none="무특성", equipped="중간 티어 장착", top_growth="최고 성장", top_combat="피해·공속 비교", top_fusion="최고 합성·전투", top_income_damage="최고 수입·전투")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def wilson(successes, total):
    require(0 <= successes <= total and total > 0, "Invalid counts")
    z = 1.959963984540054
    p = successes / total
    d = 1 + z*z/total
    c = (p + z*z/(2*total))/d
    m = z*math.sqrt(p*(1-p)/total + z*z/(4*total*total))/d
    return [max(0, c-m)*100, min(1, c+m)*100]


def write_csv(path, rows):
    if not rows:
        return
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def analyze(folder, expected_runs, output):
    rules = dict(line.split("=", 1) for line in (folder/"rules.txt").read_text().splitlines())
    require(int(rules["runs"]) == expected_runs, "Wrong run count")
    cap = int(rules["roundCap"])
    require(1 <= cap <= 10000 and int(rules["batchTicks"]) == 500, "Wrong simulation limits")
    checkpoints = tuple(r for r in ROUNDS if r <= cap)
    first = int(rules["seed"])
    with (folder/"summary.csv").open(encoding="utf-8", newline="") as stream:
        summaries = list(csv.DictReader(stream))
    names = [x["scenario"] for x in summaries]
    require(names == rules["scenarios"].split(",") and len(set(names)) == len(names), "Missing/duplicate scenarios")
    require(set(names) <= set(SCENARIOS), "Unknown scenario")
    results, outcomes = {}, {}
    reach_rows, conditionals, histograms, cap_rows = [], [], [], []
    for summary in summaries:
        name = summary["scenario"]
        source = folder/(name+".jsonl.gz")
        hist, completed_hist, grades = Counter(), Counter(), Counter()
        ticks = purchases = sales = total_rounds = cap_completed = 0
        rounds = []
        with gzip.open(source, "rt", encoding="utf-8") as stream:
            for i, line in enumerate(stream):
                row = json.loads(line)
                require(row["seed"] == first+i, f"{name}: duplicate/missing/out-of-order seed")
                r, c, t = row["round"], row["completedRounds"], row["ticks"]
                require(1 <= r <= cap and c in (r-1, r), f"{name}: invalid round")
                require(300+(r-1)*600 < t <= 300+r*600, f"{name}: round/tick mismatch")
                require(row["outcome"] in ("PLAYING", "ENEMY_LIMIT"), "Unexpected outcome")
                capped = row["outcome"] == "PLAYING"
                require(not capped or (r == c == cap and t == 300+cap*600), "Incomplete capped game")
                snapshots = row["checkpoints"]
                cr = [x["round"] for x in snapshots]
                require(cr == sorted(set(cr)) and all(x <= r for x in cr), "Invalid checkpoints")
                for x in snapshots:
                    require(0 <= x["summons"] <= row["summons"] and 0 <= x["sales"] <= row["sales"]
                            and math.isfinite(x["coins"]) and x["coins"] >= 0
                            and math.isfinite(x["earned"]) and x["earned"] >= 0, "Invalid economy")
                hist[r] += 1
                completed_hist[c] += 1
                grades[row["maxGrade"]] += 1
                rounds.append(r)
                ticks += t
                total_rounds += r
                purchases += row["summons"]
                sales += row["sales"]
                cap_completed += capped
                if r == cap:
                    cap_rows.append(dict(scenario=name, seed=row["seed"], completed=c, outcome=row["outcome"]))
        n = len(rounds)
        require(n == expected_runs == int(summary["runs"]), f"{name}: incomplete cohort {n}")
        require(math.isclose(float(summary["mean_round"]), total_rounds/n, abs_tol=1e-9), "Mean mismatch")
        require(math.isclose(float(summary["simulated_seconds"]), ticks/20, abs_tol=1e-5), "Time mismatch")
        reached = {r:sum(v for k,v in hist.items() if k >= r) for r in checkpoints}
        for k, v in summary.items():
            if k.startswith("reach"):
                require(reached[int(k[5:])] == int(v), "Summary count mismatch")
        for r, count in reached.items():
            low, high = wilson(count,n)
            reach_rows.append(dict(scenario=name, round=r, reached=count, runs=n, percent=count/n*100,
                                   wilson95_low_percent=low, wilson95_high_percent=high,
                                   completed=sum(v for k,v in completed_hist.items() if k >= r)))
        for a,b in zip(checkpoints,checkpoints[1:]):
            conditionals.append(dict(scenario=name, from_round=a, to_round=b, reached_from=reached[a],
                                     reached_to=reached[b], conditional_percent=reached[b]/reached[a]*100 if reached[a] else ""))
        histograms.extend(dict(scenario=name, last_round=r, runs=n) for r,n in sorted(hist.items()))
        outcomes[name] = rounds
        results[name] = dict(runs=n, seed_start=first, seed_end=first+n-1, mean_round_capped=total_rounds/n,
                             max_round=max(hist), cap_completed=cap_completed, total_ticks=ticks,
                             total_summons=purchases, total_sales=sales, max_grade_counts=dict(grades), reached=reached,
                             sha256=hashlib.sha256(source.read_bytes()).hexdigest())
    paired = []
    if "none" in names:
        for name in names:
            if name == "none":
                continue
            for r in checkpoints:
                better=sum(a >= r > b for a,b in zip(outcomes[name],outcomes["none"]))
                worse=sum(b >= r > a for a,b in zip(outcomes[name],outcomes["none"]))
                paired.append(dict(scenario=name, reference="none", round=r, only_scenario_reached=better,
                                   only_reference_reached=worse, difference_pp=(better-worse)/expected_runs*100))
    output.mkdir(parents=True, exist_ok=True)
    for filename, rows in [("reach.csv",reach_rows),("conditional.csv",conditionals),
                           ("round-distribution.csv",histograms),("paired.csv",paired),("cap-seeds.csv",cap_rows)]:
        write_csv(output/filename,rows)
    analysis=dict(validated_games=expected_runs*len(names),round_cap=cap,batch_ticks=500,checkpoints=checkpoints,scenarios=results)
    (output/"analysis.json").write_text(json.dumps(analysis,indent=2)+"\n")
    print(json.dumps({k:dict(runs=v['runs'],reached=v['reached']) for k,v in results.items()}))
    return analysis


def report_markdown(analysis):
    cases=analysis["scenarios"]
    checkpoints=analysis["checkpoints"]
    lines=["# 라운드 도달률 검증", "", f"총 {analysis['validated_games']:,}판을 검증했다. 아래 수치는 라운드 진입 기준이며 완료 수는 `reach.csv`에 별도로 기록했다.",
           "", "| 라운드 | "+" | ".join(NAMES[x] for x in cases)+" |", "|---:"+"|---:"*len(cases)+"|"]
    for r in checkpoints:
        lines.append(f"| {r:,} | "+" | ".join(f"{x['reached'][r]/x['runs']*100:.3f}%" for x in cases.values())+" |")
    intervals=[(a,b) for a,b in [(1000,1500),(1500,2000),(2000,2250),(2250,2500)] if b<=analysis["round_cap"]]
    if intervals:
        lines += ["", "| 구성 | "+" | ".join(f"{a:,}→{b:,}" for a,b in intervals)+" |", "|---|"+"---:|"*len(intervals)]
        for name,case in cases.items():
            v=case['reached']
            lines.append(f"| {NAMES[name]} | "+" | ".join(f"{v[b]}/{v[a]} ({v[b]/v[a]*100:.2f}%)" if v[a] else "관측 없음" for a,b in intervals)+" |")
    lines += ["", "같은 시드를 각 구성에 적용했다. 시드 누락·중복·순서, 판 수, 라운드·처리 틱, 보상 값과 실행기 요약을 원본 전체에서 다시 검증했다.",
              "", "95% Wilson 구간은 `reach.csv`에 있다. 0회 관측은 불가능의 증명이 아니며, 매우 적은 생존자에 기반한 조건부 도달률은 불확실성이 크다.",
              "", "이는 고정된 자동 구매·판매·배치·합성 정책의 결과다. 실제 이용자 전체의 통계나 가능한 모든 특성 조합의 최적해가 아니다. 관측 상한은 게임 내 강제 승리 조건이 아니다."]
    return "\n".join(lines)+"\n"


if __name__ == "__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("input",type=Path)
    parser.add_argument("output",type=Path)
    parser.add_argument("--expected-runs",type=int,default=100000)
    parser.add_argument("--report",type=Path)
    args=parser.parse_args()
    result=analyze(args.input,args.expected_runs,args.output)
    if args.report:
        args.report.write_text(report_markdown(result),encoding="utf-8")
