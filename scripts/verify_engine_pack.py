#!/usr/bin/env python3
import hashlib,json,sys,zipfile
from pathlib import Path
apk,pack=map(Path,sys.argv[1:])
with zipfile.ZipFile(apk) as z:
    assert not any(n.startswith('lib/') and n.endswith('.so') for n in z.namelist()), 'Native payload in APK'
    manifest=json.loads(z.read('assets/ffmpeg-pack.json'))
assert manifest['abi']=='arm64-v8a' and manifest['version']=='8.1.9'
files={f['name']:f for f in manifest['files']}
assert {'libffmpegkit.so','libffmpegkit_abidetect.so','libavcodec.so'} <= files.keys()
with zipfile.ZipFile(pack) as z:
    assert len(z.namelist())==len(files) and set(z.namelist())==set(files), 'Archive inventory mismatch'
    for name,f in files.items():
        data=z.read(name)
        assert len(data)==f['bytes'] and hashlib.sha256(data).hexdigest()==f['sha256'], name
        assert data[:4]==b'\x7fELF' and int.from_bytes(data[18:20],'little')==183, name
        assert set(f['dependencies'])<=files.keys()
def visit(n,active,done):
    if n in done:return
    assert n not in active,'Cyclic ELF dependency '+n
    active.add(n)
    for dep in files[n]['dependencies']:visit(dep,active,done)
    active.remove(n);done.add(n)
done=set()
for n in files:visit(n,set(),done)
print('Verified light APK:',apk.stat().st_size,'bytes; external ARM64 runtime:',pack.stat().st_size,'bytes;',len(files),'libraries')
