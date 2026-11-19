#!/usr/bin/env python3
import hashlib,json,sys,zipfile
from pathlib import Path
apk,pack=map(Path,sys.argv[1:])
with zipfile.ZipFile(apk) as z:
    names=z.namelist()
    assert not any('libmedialab_photo_ultra.so' in n or n.endswith('photo-ultra-arm64-v1.so') for n in names)
    meta=json.loads(z.read('assets/photo-ultra.json'))
assert meta['api']==1 and meta['abi']=='arm64-v8a'
assert meta['bytes']==pack.stat().st_size
assert meta['sha256']==hashlib.sha256(pack.read_bytes()).hexdigest()
assert meta['url']=='https://github.com/INFEND/MEDIALAB/releases/download/v1.3.66/photo-ultra-arm64-v1.so'
print('Verified separate PHOTO ULTRA asset; APK contains metadata only')
