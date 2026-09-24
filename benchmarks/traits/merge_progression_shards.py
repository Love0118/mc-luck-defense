"""Merge complete CI shards only after validating seed ranges, rules, build and jobs."""
import argparse
import csv
from datetime import datetime
import gzip
import hashlib
import json
from pathlib import Path
import re
from summarize_progression import require, write_csv

SCENARIOS = ("none", "equipped", "top_growth", "top_fusion")


def merge(source, jobs_file, output, seed_start):
    jobs=json.loads(jobs_file.read_text(encoding="utf-8-sig"))["jobs"]
    require(any(j['name']=='build' and j['conclusion']=='success' for j in jobs), 'Build did not pass')
    timings={}
    for job in jobs:
        if job['name']=='build':
            continue
        match=re.fullmatch(r"simulate \((none|equipped|top_growth|top_fusion), (\d+)\)",job['name'])
        require(match is not None and job['conclusion']=='success',f"Incomplete CI job: {job['name']}")
        step=next(s for s in job['steps'] if s['name']=='Run 5000 games')
        require(step['conclusion']=='success','Simulation did not finish')
        key=(match[1],int(match[2]))
        require(key not in timings,'Duplicate job')
        timings[key]=(datetime.fromisoformat(step['startedAt']),datetime.fromisoformat(step['completedAt']))
    require(set(timings)=={(n,s) for n in SCENARIOS for s in range(20)},'Expected 80 completed shards')
    require(len(list(source.glob('progression-*/summary.csv')))==80,'Expected 80 artifacts')
    combined={}; summaries=[]; proofs=[]; reference=None; binary=None
    for name in SCENARIOS:
        parts=[]; totals=None; traits=None; wall=0
        for shard in range(20):
            folder=source/f'progression-{name}-{shard}'
            rules=dict(line.split('=',1) for line in (folder/'rules.txt').read_text().splitlines())
            seed=seed_start+shard*5000
            require(int(rules['runs'])==5000 and int(rules['seed'])==seed,'Incorrect seed range')
            require(rules['scenarios']==name,'Incorrect scenario')
            invariant={k:v for k,v in rules.items() if k not in ('runs','seed','scenarios')}
            require(reference is None or reference==invariant,'Rules/build drift across shards')
            reference=invariant
            provenance=((folder/'jar.sha256').read_text().strip(),(folder/'source-commit.txt').read_text().strip())
            require(binary is None or binary==provenance,'Different executable across shards')
            binary=provenance
            current=(folder/(name+'-traits.txt')).read_text()
            require(traits is None or traits==current,'Trait drift')
            traits=current
            with (folder/'summary.csv').open(newline='',encoding='utf-8') as f:
                rows=list(csv.DictReader(f))
            require(len(rows)==1 and rows[0]['scenario']==name and int(rows[0]['runs'])==5000,'Invalid summary')
            row=rows[0]; zipped=(folder/(name+'.jsonl.gz')).read_bytes()
            games=[json.loads(s) for s in gzip.decompress(zipped).splitlines()]
            require(len(games)==5000 and all(g['seed']==seed+i for i,g in enumerate(games)),'Missing/overlapping seeds')
            if totals is None:
                totals={k:0 for k in row if k.startswith('reach')}
                totals.update(runs=0,simulated_seconds=0,total_rounds=0)
            totals['runs']+=5000
            totals['simulated_seconds']+=float(row['simulated_seconds'])
            totals['total_rounds']+=sum(g['round'] for g in games)
            for key in totals:
                if key.startswith('reach'):
                    totals[key]+=int(row[key])
            wall+=float(row['wall_seconds']); parts.append(zipped)
            proofs.append(dict(scenario=name,shard=shard,seed_start=seed,runs=5000,
                               gzip_sha256=hashlib.sha256(zipped).hexdigest(),
                               java=(folder/'java-version.txt').read_text().strip(),
                               processors=int((folder/'processors.txt').read_text()),wall_seconds=float(row['wall_seconds'])))
        starts,ends=zip(*(timings[name,s] for s in range(20)))
        elapsed=(max(ends)-min(starts)).total_seconds()
        require(elapsed>0,'Invalid elapsed time')
        summary=dict(scenario=name,runs=totals.pop('runs'),wall_seconds=elapsed,
                     simulated_seconds=totals.pop('simulated_seconds'),mean_round=totals.pop('total_rounds')/100000)
        summary['effective_speed']=summary['simulated_seconds']/elapsed
        summary.update(totals); summary['summed_shard_wall_seconds']=wall
        summaries.append(summary); combined[name]=(parts,traits)
    output.mkdir(exist_ok=True,parents=True)
    for name,(parts,traits) in combined.items():
        (output/(name+'.jsonl.gz')).write_bytes(b''.join(parts))
        (output/(name+'-traits.txt')).write_text(traits)
    reference.update(runs='100000',seed=str(seed_start),scenarios=','.join(SCENARIOS))
    (output/'rules.txt').write_text(''.join(f'{k}={v}\n' for k,v in reference.items()))
    (output/'jar.sha256').write_text(binary[0]+'\n')
    (output/'source-commit.txt').write_text(binary[1]+'\n')
    write_csv(output/'summary.csv',summaries)
    (output/'shards.json').write_text(json.dumps(proofs,indent=2)+'\n')
    print('Validated and merged 80 shards: 100000 games per configuration')


if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('source',type=Path);p.add_argument('jobs',type=Path);p.add_argument('output',type=Path)
    p.add_argument('--seed-start',type=int,default=34000000)
    a=p.parse_args();merge(a.source,a.jobs,a.output,a.seed_start)
