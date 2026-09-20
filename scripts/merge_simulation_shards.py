"""Audit and assemble completed simulation shards, tolerating only a final interrupted line."""
import argparse,json
from pathlib import Path

def main(a):
    result={}
    for path in a.shards:
        lines=path.read_text(encoding='utf-8').splitlines()
        for index,line in enumerate(lines):
            try:row=json.loads(line)
            except json.JSONDecodeError:
                if index!=len(lines)-1:raise
                continue
            seed=row['seed']
            if seed in result:assert result[seed]==row,(seed,'Conflicting shard results')
            result[seed]=row
    rules=json.loads(a.rules.read_text(encoding='utf-8'))
    expected=set(range(rules['seedStart'],rules['seedStart']+rules['runs']))
    assert set(result)==expected,(len(result),'Incomplete cohort')
    a.output.mkdir(parents=True,exist_ok=True)
    (a.output/'runs.jsonl').write_text('\n'.join(json.dumps(result[s]) for s in sorted(result))+'\n',encoding='utf-8')
    (a.output/'rules.json').write_text(json.dumps(rules,indent=2),encoding='utf-8')
    (a.output/'survivor-seeds.txt').write_text('\n'.join(str(s) for s in sorted(result) if result[s]['round']>a.branch),encoding='utf-8')
    print('Audited',len(result),'unique games;',sum(r['round']>a.branch for r in result.values()),'survivors')

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--rules',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--branch',type=int,default=500);p.add_argument('shards',type=Path,nargs='+')
    main(p.parse_args())
