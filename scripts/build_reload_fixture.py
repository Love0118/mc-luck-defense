"""Compile isolated hot-update fixtures and deliberately different runtime candidates."""
import argparse
import os
import shutil
import subprocess
import zipfile
from pathlib import Path


def main(a):
    root=Path(__file__).resolve().parents[1]
    a.output.mkdir(parents=True,exist_ok=True)
    libraries=[str(p.resolve()) for p in a.libraries.rglob("*.jar")]
    classpath=os.pathsep.join([str(a.plugin.resolve()),*libraries])
    classes=a.output/"fixture-classes";classes.mkdir(exist_ok=True)
    subprocess.run(["javac","-cp",classpath,"-d",str(classes),str(root/"benchmarks/reload/ReloadSmokePlugin.java")],check=True)
    shutil.copy2(root/"benchmarks/reload/plugin.yml",classes/"plugin.yml")
    subprocess.run(["jar","--create","--file",str(a.output/"ReloadSmoke.jar"),"-C",str(classes),"."],check=True)
    candidates=a.output/"candidates";candidates.mkdir(exist_ok=True)
    shutil.copy2(a.plugin,candidates/"base.jar")
    original=(root/"src/main/java/dev/moma/paper/GameRuntime.java").read_text(encoding="utf-8")
    for name,version,fail in [("compatible","0.16.1-test",False),("activation-failure","0.16.2-test",True)]:
        code=original.rsplit("\n}",1)[0]+'\n    public String validationMarker(){return "candidate-one";}\n}\n'
        if fail:code=code.replace("        active=true;","        if(!initialBoot)throw new IllegalStateException(\"fixture activation failure\");\n        active=true;")
        folder=a.output/name;folder.mkdir(exist_ok=True);source=folder/"GameRuntime.java";source.write_text(code,encoding="utf-8")
        subprocess.run(["javac","-encoding","UTF-8","-cp",classpath,"-d",str(folder),str(source)],check=True)
        patch={"dev/moma/paper/GameRuntime.class":(folder/"dev/moma/paper/GameRuntime.class").read_bytes(),
               "mud-runtime.properties":f"host-api=1\nstate-schema=1\nversion={version}\n".encode()}
        rewrite(a.plugin,candidates/(name+".jar"),patch,version)
    rewrite(a.plugin,candidates/"incompatible.jar",{"mud-runtime.properties":b"host-api=1\nstate-schema=2\nversion=0.16.3-test\n"},"0.16.3-test")


def rewrite(source,target,patch,version):
    with zipfile.ZipFile(source) as incoming,zipfile.ZipFile(target,"w",zipfile.ZIP_DEFLATED) as output:
        for item in incoming.infolist():
            data=patch.get(item.filename,incoming.read(item.filename))
            if item.filename=="plugin.yml":
                import re
                data=re.sub(r"(?m)^version:.*$",f"version: '{version}'",data.decode("utf-8")).encode("utf-8")
            output.writestr(item.filename,data)


if __name__=="__main__":
    p=argparse.ArgumentParser()
    for name in ("plugin","libraries","output"):p.add_argument("--"+name,type=Path,required=True)
    main(p.parse_args())
