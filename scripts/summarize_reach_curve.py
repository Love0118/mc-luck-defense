"""Summarize measured reach rates, including conditional 300-to-400 survival."""
import argparse
import gzip
import json
import math
from pathlib import Path

ROUNDS = [30, 60, 90, 100, 150, 200, 300, 350, 400, 450, 500, 750, 1000, 1500, 2000]

def summarize(directory):
    rules = json.loads((directory / "rules.json").read_text(encoding="utf-8"))
    with (directory / "runs.jsonl").open(encoding="utf-8") as stream:
        rows = [json.loads(line) for line in stream]
    count = len(rows)
    assert count == rules["runs"]
    assert {row["seed"] for row in rows} == set(range(rules["seedStart"], rules["seedStart"] + count))
    points = []
    for round_number in ROUNDS:
        if round_number > rules["cap"]:
            continue
        reached = sum(row["round"] >= round_number for row in rows)
        completed = sum(row["completedRounds"] >= round_number for row in rows)
        p = reached / count
        z2 = 1.96 ** 2
        denominator = 1 + z2 / count
        center = (p + z2 / (2 * count)) / denominator
        margin = 1.96 * math.sqrt(p * (1 - p) / count + z2 / (4 * count ** 2)) / denominator
        points.append(dict(round=round_number, reached=reached, completed=completed, rate=p,
                           ci95=[max(0, center - margin), min(1, center + margin)]))
    counts = {point["round"]: point["reached"] for point in points}
    result = dict(rules=rules, runs=count, checkpoints=points,
                  conditional400Given300=counts.get(400, 0) / counts[300] if counts.get(300) and 400 in counts else None)
    (directory / "summary.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    # Keep raw evidence compact without rewriting or deleting the input.
    with (directory / "runs.jsonl").open("rb") as source, gzip.open(directory / "runs.jsonl.gz", "wb") as output:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            output.write(block)
    print(json.dumps({"directory": str(directory), "rates": {p["round"]: round(p["rate"] * 100, 3) for p in points},
                      "conditional400Given300": result["conditional400Given300"]}))

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("directories", nargs="+", type=Path)
    for directory in parser.parse_args().directories:
        summarize(directory)
