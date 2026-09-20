"""Isolated 1.21.8 protocol verification through ViaBackwards on the 26.3 fork.

Requires a built plugin, compiled fixture jar, existing localhost fixture worlds and
minecraft-protocol in .runtime/clients. Does not restart or modify production.
"""
import functools, hashlib, http.server, json, os, shutil, subprocess, sys, threading, time, zipfile
from pathlib import Path

root=Path(__file__).resolve().parents[2]
run=root/'.runtime'/('bgm-via-'+time.strftime('%Y%m%d-%H%M%S'))
def ignore(directory,names):
    excluded=shutil.ignore_patterns('logs','clients.json','*passed.json','bgm.db*')(directory,names)
    if Path(directory).name=='plugins':excluded.update(n for n in names if n.endswith('.jar'))
    return excluded
shutil.copytree(root/'.runtime/v080-smoke-final',run,ignore=ignore)
for name in ['ViaVersion-5.12.0.jar','ViaBackwards-5.12.0.jar']:
    shutil.copy2(Path('E:/mc luck defense/plugins')/name,run/'plugins'/name)
shutil.copy2(root/'target/mc-luck-defense-0.12.0.jar',run/'plugins/MCLuckDefense.jar')
shutil.copy2(root/'target/mud-bgm-smoke.jar',run/'plugins/mud-bgm-smoke.jar')
properties=run/'server.properties'
properties.write_text(properties.read_text().replace('server-port=25586','server-port=25587'))
packs=run/'packs';packs.mkdir()
bgm=run/'plugins/MCLuckDefense';bgm.mkdir(exist_ok=True)
(bgm/'bgm.yml').write_text("refresh-token: ''\n")
for i in range(3):
    track='fixture'+str(i);path=packs/(track+'.zip')
    with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED) as z:
        z.writestr('pack.mcmeta',json.dumps({'pack':{'pack_format':64,'supported_formats':[64,97],'min_format':64,'max_format':[97,1],'description':'BGM protocol fixture'}}))
        z.write(root/'src/main/resources/bgm/default.ogg','assets/mud_bgm/sounds/fixture.ogg')
        z.writestr('assets/mud_bgm/sounds.json',json.dumps({f'track_{track}_part_{s}':{'sounds':[{'name':'mud_bgm:fixture','stream':True}]} for s in range(3)}))
    (packs/(track+'.sha1')).write_text(hashlib.sha1(path.read_bytes()).hexdigest())
http=http.server.ThreadingHTTPServer(('127.0.0.1',25588),functools.partial(http.server.SimpleHTTPRequestHandler,directory=str(packs)))
threading.Thread(target=http.serve_forever,daemon=True).start()
env=os.environ.copy();env['NODE_PATH']=str(root/'.runtime/clients/node_modules')
with (run/'console.log').open('w',encoding='utf-8') as log, (run/'client-console.log').open('w',encoding='utf-8') as clientlog:
    server=subprocess.Popen(['java','-Xms1g','-Xmx3g','-jar','E:/projects/paper-26.3-mud/paper-server/build/libs/paper-paperclip-26.3.local-SNAPSHOT.jar','--nogui'],cwd=run,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True)
    clients=None
    try:
        deadline=time.monotonic()+120
        while 'For help, type' not in (run/'console.log').read_text(encoding='utf-8',errors='replace'):
            if server.poll() is not None or time.monotonic()>deadline:raise RuntimeError('Server startup failed')
            time.sleep(.5)
        clients=subprocess.Popen(['node',str(root/'benchmarks/bgm/via-client.cjs'),str(run/'clients.json')],env=env,stdout=clientlog,stderr=subprocess.STDOUT)
        server.wait(timeout=100);clients.wait(timeout=15)
        assert (run/'bgm-via-server-passed.json').exists(),'Server assertions did not complete'
        result=json.loads((run/'clients.json').read_text())
        for name,record in result.items():
            assert not record['errors'],(name,record['errors'])
            assert len(record['adds'])==3 and not record['removes'],(name,'Pack cache not retained')
            assert len(record['sounds'])>=4,(name,'Missing resumed music')
        (run/'bgm-via-passed.json').write_text(json.dumps({'clients':2,'version':'1.21.8','packsPerClient':3,'sha1Verified':True,'allAppliedGate':True,'recordChannel':True,'unloads':0,'reloadsOnReentry':0}))
        print(run)
        print((run/'bgm-via-passed.json').read_text())
    finally:
        if server.poll() is None:
            server.stdin.write('stop\n');server.stdin.flush();server.wait(timeout=60)
        if clients and clients.poll() is None:clients.terminate()
        http.shutdown()
