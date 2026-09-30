import csv
import gzip
import json
import math
from pathlib import Path
import statistics
import sys
from collections import defaultdict

sys.path.insert(0, str(Path(__file__).resolve().parents[2]/'.runtime/analysis-deps'))
import numpy as np
from scipy.optimize import least_squares


def read(path):
    stream = path.open(encoding='utf-8', newline='') if path.exists() else gzip.open(str(path)+'.gz', 'rt', encoding='utf-8', newline='')
    with stream:
        return list(csv.DictReader(stream))


def write(path, rows):
    with path.open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader(); writer.writerows(rows)


def main(root):
    summaries = []
    for version, files in [('old', ['old-early', 'old']), ('current', ['current'])]:
        groups = defaultdict(lambda: defaultdict(float))
        folder = 'rebalance-1.0.15/candidate' if version == 'old' else 'endless-relief-1.0.18'
        with gzip.open(Path(__file__).resolve().parents[2]/'docs/balance'/folder/'top_fusion_four.jsonl.gz', 'rt') as stream:
            baseline = {r['seed']: r for r in map(json.loads, stream)}
        for filename in files:
            rows = read(root/(filename+'.csv'))
            for row in rows:
                seed, start = int(row['seed']), int(row['start'])
                if filename == 'old-early' and start >= 700:
                    continue
                if filename != 'old-early':
                    expected = baseline[seed]
                    for a, b in [('lastRound', 'round'), ('ticks', 'ticks'), ('summons', 'summons'), ('sales', 'sales')]:
                        assert int(row[a]) == expected[b], (version, seed, a)
                    assert row['outcome'] == expected['outcome']
                if start < 1500 and seed >= 42000512:
                    continue
                g = groups[(seed, start)]
                for name in ('damage', 'lostDamage', 'rawDamage', 'lostRawDamage'):
                    g[name] += float(row[name])
                g[row['grade']] += float(row['damage'])
        for start in sorted({w for seed, w in groups}):
            games = [g for (seed, w), g in groups.items() if w == start and g['damage'] > 0]
            result = dict(version=version, start=start, end=start+99, games=len(games),
                          seed_population=512 if start<1500 else (2048 if version=='old' else 8192))
            for grade in ('MYTHIC', 'PRIMORDIAL', 'TRUE_PRIMORDIAL', 'MIRACLE'):
                result[grade] = statistics.mean(g[grade]/g['damage'] for g in games)
            losses = [g['lostDamage']/g['damage'] for g in games]
            result.update(loss=statistics.mean(losses), median_loss=statistics.median(losses),
                          p90_loss=float(np.quantile(losses,.9)),
                          raw_loss=statistics.mean(g['lostRawDamage']/g['rawDamage'] for g in games))
            summaries.append(result)
    write(root/'damage-shares.csv', summaries)
    data = [r for r in summaries if r['version']=='old' and r['games']>=10 and r['start']<=2400]
    x = np.array([r['start']+49.5 for r in data]); y = np.array([r['loss'] for r in data])
    weights = np.sqrt(np.minimum([r['games'] for r in data], 50)/50)
    def predicted(p):
        a, scale, power = p
        return a*np.exp(-(x/scale)**power)
    fit = least_squares(lambda p: weights*(np.log(predicted(p)+.001)-np.log(y+.001)),
                        [.99, 500, 1.3], bounds=([.01,100,.5],[1,2000,4]), xtol=1e-12, ftol=1e-12, gtol=1e-12)
    a, scale, power = map(float,fit.x)
    loss = lambda r: a*math.exp(-(r/scale)**power)
    rows = [dict(round=r, measured_loss=float(v), fitted_loss=loss(r)) for r,v in zip(x,y)]
    write(root/'loss-fit.csv',rows)
    base_rounds=np.array([200,300,400,500,600,700,800,900,1000,1250,1500,2000],dtype=float)
    reference={200:2000000,300:5000000,400:8500000,500:16000000,600:29215470.930176035,
               700:46419190.23836803,800:68183714.92227086,900:102364663.51858358,1000:146879926.25949115}
    def base(r):
        return 146879926.25949115*(r/1000)**3.1*((1+(r/1000)**2)/2)**1.3
    target=np.array([reference.get(r,base(r)) for r in base_rounds])
    def smooth(p,r):
        strength,sharpness=p;u=r/1000
        return base(r)*np.exp(-strength*np.log(u)/(1+u**sharpness))
    baseline_fit=least_squares(lambda p: np.log(smooth(p,base_rounds)/target),[.9,8],bounds=([0,2],[2,20]))
    params=dict(loss_amplitude=a,loss_scale=scale,loss_power=power,
                baseline_strength=float(baseline_fit.x[0]),baseline_sharpness=float(baseline_fit.x[1]))
    (root/'fitted-parameters.json').write_text(json.dumps(params,indent=2)+'\n')
    print(json.dumps(params))
    for r in data:
        print(r['start'],r['games'],f"Prim {r['PRIMORDIAL']*100:.3f}% loss {r['loss']*100:.3f}% fit {loss(r['start']+49.5)*100:.3f}%")


if __name__ == '__main__':
    main(Path(sys.argv[1]))
