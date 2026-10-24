"""Build the separately licensed protocol-777 module against a matching Paper server."""
from pathlib import Path
import argparse,hashlib,json,os,subprocess,tempfile,zipfile

ROOT=Path(__file__).resolve().parent
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--server-jar',type=Path,required=True);parser.add_argument('--libraries',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();provenance=json.loads((ROOT/'upstream.json').read_text(encoding='utf-8'))
    for name,expected in provenance['sourceSha256'].items():
        assert hashlib.sha256((ROOT/'src/main/java'/name).read_bytes().replace(b'\r\n',b'\n')).hexdigest()==expected,name
    with zipfile.ZipFile(args.server_jar) as server:assert 'net/minecraft/network/protocol/game/ClientboundPlayerChatPacket.class' in server.namelist()
    build=args.output.resolve().parent/'guard-build';build.mkdir(parents=True,exist_ok=True)
    temporary=tempfile.TemporaryDirectory(prefix='classes-',dir=build);classes=Path(temporary.name)
    classpath=os.pathsep.join(str(p.resolve()) for p in [args.server_jar,*sorted(args.libraries.rglob('*.jar'))])
    sources=sorted((ROOT/'src/main/java').rglob('*.java'))
    arguments=['-encoding','UTF-8','--release','25','-cp',classpath,'-d',str(classes),*[str(p) for p in sources]]
    argfile=build/'javac.args';argfile.write_text('\n'.join(json.dumps(arg) for arg in arguments),encoding='utf-8')
    subprocess.run(['javac','@'+str(argfile)],check=True)
    files={p.relative_to(classes).as_posix():p.read_bytes() for p in classes.rglob('*.class')}
    temporary.cleanup()
    files.update({p.relative_to(ROOT/'src/main/resources').as_posix():p.read_bytes() for p in (ROOT/'src/main/resources').rglob('*') if p.is_file()})
    files['META-INF/MANIFEST.MF']=b'Manifest-Version: 1.0\npaperweight-mappings-namespace: mojang\n\n'
    files['LICENSE']=(ROOT/'LICENSE').read_bytes();files['upstream.json']=(ROOT/'upstream.json').read_bytes()
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(args.output,'w',compression=zipfile.ZIP_DEFLATED) as jar:
        for name,data in sorted(files.items()):
            entry=zipfile.ZipInfo(name,(2026,10,25,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED;entry.external_attr=0o644<<16;jar.writestr(entry,data)
    print('MomaChatGuard SHA-256:',hashlib.sha256(args.output.read_bytes()).hexdigest())
if __name__=='__main__':main()
