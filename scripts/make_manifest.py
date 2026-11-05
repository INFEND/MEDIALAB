#!/usr/bin/env python3
"""Derive OTA manifest from a signed APK, never from a manually typed version."""
import hashlib
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
apk=ROOT/'dist/MediaLAB.apk'
if not apk.is_file():raise SystemExit('Signed APK not found')
info=subprocess.check_output([str(Path(__import__('os').environ['ANDROID_SDK_ROOT'])/'build-tools/35.0.0/aapt'), 'dump', 'badging', str(apk)], text=True)
match=re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'",info,re.MULTILINE)
if not match:raise SystemExit('aapt failed to parse APK version')
package,code,version=match.groups()
if package!='com.xarkon.medialab':raise SystemExit('Unexpected APK applicationId '+package)
if not re.fullmatch('[A-Za-z0-9._-]{1,40}',version):raise SystemExit('Invalid version name')
notes_path=ROOT/'release_notes'/('v'+version+'.txt')
if not notes_path.is_file():raise SystemExit('Release notes missing: '+str(notes_path))
notes=notes_path.read_text(encoding='utf-8').strip()
if not notes or len(notes)>6000:raise SystemExit('Release notes invalid')
# Publish a complete cumulative changelog, ordered by versionCode.
index=json.loads((ROOT/'release_notes/version_codes.json').read_text(encoding='utf-8'))
history=[]
for row in index:
    ver=row['versionName'];num=int(row['versionCode'])
    source=ROOT/'release_notes'/('v'+ver+'.txt')
    if not source.is_file():raise SystemExit('Missing historical release notes: '+str(source))
    message=source.read_text(encoding='utf-8').strip()
    if not message or len(message)>6000:raise SystemExit('Bad historical release notes '+ver)
    history.append({'versionCode':num,'versionName':ver,'notes':message})
if not history or history[-1]['versionCode']!=int(code) or history[-1]['versionName']!=version:
    raise SystemExit('Latest release missing from historical index')
for before,after in zip(history,history[1:]):
    if after['versionCode']!=before['versionCode']+1:
        raise SystemExit('Non-contiguous OTA version codes in history')
metadata={
 'schema':1, 'packageName':package, 'versionCode':int(code), 'versionName':version,
 'history':history,
 'apkUrl':'https://github.com/INFEND/MEDIALAB/releases/download/v'+version+'/MediaLAB.apk',
 'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(), 'sizeBytes':apk.stat().st_size,
 'publishedAt':datetime.now(timezone.utc).isoformat(timespec='seconds'), 'notes':notes,
}
(ROOT/'dist/update.json').write_text(json.dumps(metadata,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
if __import__('os').environ.get('GITHUB_OUTPUT'):
 with open(__import__('os').environ['GITHUB_OUTPUT'],'a',encoding='utf-8') as o:
  o.write('tag=v'+version+'\nversion='+version+'\n')
print('Generated verified manifest:',package,version,'code',code,'bytes',apk.stat().st_size)
