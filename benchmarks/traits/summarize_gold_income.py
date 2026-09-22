"""Merge completed paired runs and report uncertainty without counting duplicate seeds twice."""
import argparse
import csv
import gzip
import json
import math
from pathlib import Path
import shutil
import statistics


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("inputs", nargs="+", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    scenarios = {}
    for folder in args.inputs:
        rules = dict(line.split("=", 1) for line in (folder / "rules.txt").read_text().splitlines())
        count = int(rules["runs"])
        # A cancelled sweep may leave an incomplete gzip file; only published summary rows count.
        with (folder / "summary.csv").open(newline="") as stream:
            for summary in csv.DictReader(stream):
                key = summary["context"] + "-" + summary["scenario"]
                source = folder / (key + ".jsonl.gz")
                with gzip.open(source, "rt", encoding="utf-8") as stream:
                    rows = [json.loads(line) for line in stream]
                assert len(rows) == count
                by_seed = {row["seed"]: row for row in rows}
                assert len(by_seed) == count
                assert set(by_seed) == set(range(int(rules["seed"]), int(rules["seed"]) + count))
                if key in scenarios:
                    assert scenarios[key] == by_seed, key
                    continue
                scenarios[key] = by_seed
                shutil.copyfile(source, args.output / source.name)
        shutil.copyfile(folder / "rules.txt", args.output / (folder.name + "-rules.txt"))
    checkpoints = [30, 60, 100, 200, 300, 500, 700]
    results = {}
    for key, rows in scenarios.items():
        results[key] = {
            "runs": len(rows),
            "mean_round_capped": statistics.mean(row["round"] for row in rows.values()),
            "reaches": {str(r): sum(row["round"] >= r for row in rows.values()) for r in checkpoints},
        }
    comparisons = {}
    for key, rows in scenarios.items():
        context, scenario = key.split("-", 1)
        if not scenario.startswith("gold_"):
            continue
        for reference in ["none", "damage_4", "damage_8", "damage_12", "speed_2", "speed_5", "speed_8"]:
            other = scenarios.get(context + "-" + reference)
            if other is None:
                continue
            assert rows.keys() == other.keys()
            values = {}
            for r in checkpoints:
                delta = [int(row["round"] >= r) - int(other[seed]["round"] >= r) for seed, row in rows.items()]
                mean = statistics.mean(delta) * 100
                error = 1.96 * statistics.stdev(delta) / math.sqrt(len(delta)) * 100
                values[str(r)] = {"difference_pp": mean, "paired_95_ci": [mean - error, mean + error]}
            comparisons[key + " vs " + reference] = values
    report = {"unique_games": sum(len(rows) for rows in scenarios.values()), "scenarios": results, "paired_comparisons": comparisons}
    (args.output / "analysis.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("unique games:", report["unique_games"])
    for key, row in results.items():
        print(key, "mean", round(row["mean_round_capped"], 2), ", ".join(f"R{r}: {100 * row['reaches'][str(r)] / row['runs']:.2f}%" for r in [100, 300, 500, 700]))


if __name__ == "__main__":
    main()
