"""Local two-client Via smoke for private leaderboard entity packets and real click routing."""
import argparse,json,os,shutil,subprocess,time
from pathlib import Path

p=argparse.ArgumentParser()
for name in ['server','plugin','fixture','template','output','node-modules','via','backwards']:
    p.add_argument('--'+name,type=Path,required=True)
a=p.parse_args()
run=a.output.resolve()
if run.exists():raise SystemExit('Fresh output directory required')
def ignore(directory,names):
    excluded=shutil.ignore_patterns('logs','*passed.json','clients.json')(directory,names)
    if Path(directory).name=='plugins':excluded.update(n for n in names if n.endswith('.jar'))
    return excluded
shutil.copytree(a.template,run,ignore=ignore)
for source in [a.plugin,a.fixture,a.via,a.backwards]:shutil.copy2(source,run/'plugins'/source.name)
props=run/'server.properties'
props.write_text(props.read_text().replace('server-port=25586','server-port=25592'))
data=run/'plugins/MCLuckDefense';data.mkdir(exist_ok=True)
(data/'round-records.properties').write_text('\n'.join(
    f'00000000-0000-0000-0000-{i:012d}.name=Ranker{i}\n00000000-0000-0000-0000-{i:012d}.round={100-i}' for i in range(25)))
env=os.environ.copy();env['NODE_PATH']=str(a.node_modules.resolve())
with (run/'console.log').open('w',encoding='utf-8') as log,(run/'client.log').open('w',encoding='utf-8') as clientlog:
    server=subprocess.Popen(['java','-Xms1g','-Xmx3g','-jar',str(a.server.resolve()),'--nogui'],cwd=run,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True)
    client=None
    try:
        deadline=time.monotonic()+120
        while 'For help, type' not in (run/'console.log').read_text(encoding='utf-8',errors='replace'):
            if server.poll() is not None or time.monotonic()>deadline:raise RuntimeError('Startup failed')
            time.sleep(.5)
        server.stdin.write('whitelist off\n');server.stdin.flush()
        client=subprocess.Popen(['node',str(Path(__file__).with_name('client.cjs').resolve()),str(run)],env=env,stdout=clientlog,stderr=subprocess.STDOUT)
        server.wait(timeout=90);client.wait(timeout=15)
        assert (run/'leaderboard-passed.json').exists()
        ids=json.loads((run/'leaderboard-ids.json').read_text(encoding='utf-8'));clients=json.loads((run/'clients.json').read_text(encoding='utf-8'))
        for name,own,other in [('RankFirst','first','second'),('RankSecond','second','first')]:
            record=clients[name]
            assert not record['errors'],record['errors']
            assert ids[own] in record['spawns'] and ids[other] not in record['spawns'],name
            assert all(m['entityId']!=ids[other] for m in record['metadata']),name
        print((run/'leaderboard-passed.json').read_text());print('Private spawn and metadata packets verified for both clients')
    finally:
        if server.poll() is None:
            server.stdin.write('stop\n');server.stdin.flush();server.wait(timeout=60)
        if client and client.poll() is None:client.terminate()
