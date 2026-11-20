#!/usr/bin/env python3
import hashlib,json,re,sys,zipfile
from pathlib import Path
apk,pack=map(Path,sys.argv[1:])
with zipfile.ZipFile(apk) as z:
    names=z.namelist()
    assert not any('libmedialab_video_ultra.so' in n or n.endswith('video-ultra-arm64-v1.so') for n in names)
    meta=json.loads(z.read('assets/video-ultra.json'))
assert meta['api']==1 and meta['abi']=='arm64-v8a'
assert meta['bytes']==pack.stat().st_size
assert meta['sha256']==hashlib.sha256(pack.read_bytes()).hexdigest()
assert re.fullmatch(r'https://github\.com/INFEND/MEDIALAB/releases/download/v1\.3\.(?:[6-9][0-9]|[1-9][0-9]{2,})/video-ultra-arm64-v1\.so', meta['url']), 'Unexpected VIDEO ULTRA release URL'
print('Verified separate VIDEO ULTRA asset; APK contains metadata only')
