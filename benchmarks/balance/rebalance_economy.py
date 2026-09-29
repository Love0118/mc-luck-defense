import json
from pathlib import Path

PRICES = [1, 3, 5, 24, 35, 60, 400, 1000, 1500, 30000, 630000]
GRADES = ["일반", "레어", "고대", "유물", "서사", "전설", "에픽", "신화", "태초", "진 태초", "미라클"]
TIERS = [
    (1, 10, [50001, 33100, 10200, 5100, 800, 500, 200, 80, 19, 0, 0]),
    (101, 100, [0, 0, 0, 32010, 35000, 30000, 2000, 800, 190, 0, 0]),
    (500, 1400, [0, 0, 0, 0, 0, 38829, 38829, 18050, 4287, 5, 0]),
    (1000, 3300, [0, 0, 0, 0, 0, 0, 43132, 45942, 10911, 12, 3]),
]
REFERENCE = [(500, 9, 2000, 998.365, .00005, 20000),
             (1000, 9, 5000, 2503.730, .00012, 20000),
             (1000, 10, 5000, 2503.730, .00003, 420000)]


def calculate():
    tiers = []
    for round_number, cost, weights in TIERS:
        assert sum(weights) == 100000
        refund = sum(w * s for w, s in zip(weights, PRICES)) / 100000
        assert 0 < refund < cost
        tiers.append(dict(round=round_number, cost=cost, weights=weights, mean_refund=refund,
                          recovery_percent=refund/cost*100, mean_net_spend=cost-refund))
    comparisons = []
    for round_number, grade, old_cost, old_refund, old_chance, old_sale in REFERENCE:
        tier = next(t for t in tiers if t['round'] == round_number)
        chance = tier['weights'][grade] / 100000
        old = (old_cost-old_refund)/old_chance+old_sale
        new = tier['mean_net_spend']/chance+PRICES[grade]
        assert abs(new/old-1) < .03
        comparisons.append(dict(round=round_number, grade=GRADES[grade], old_net_gold_until_target=old,
                                new_net_gold_until_target=new, relative_change_percent=(new/old-1)*100,
                                old_gross_gold_until_target=old_cost/old_chance,
                                new_gross_gold_until_target=tier['cost']/chance))
    return dict(prices=dict(zip(GRADES,PRICES)), tiers=tiers, comparisons=comparisons,
                assumption="Keep the target, sell every other result, no traits/fusion; (cost - mean refund) / probability + target sale value.")


if __name__ == '__main__':
    import sys
    output=Path(sys.argv[1]);output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(calculate(),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(output)
