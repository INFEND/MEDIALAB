#!/usr/bin/env python3
"""Sign the release APK with the permanent identity stored in GitHub Actions secrets."""
import base64
import io
import json
import os
import subprocess
import tempfile
import zipfile
from hashlib import sha256
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SECRET = (os.getenv('MEDIALAB_SIGNING_BUNDLE') or os.getenv('MEDIA_LAB_SIGNING_BUNDLE') or '').strip()
if not SECRET:
    raise SystemExit('Missing secret MEDIALAB_SIGNING_BUNDLE (Base64 ZIP with signing.json and a .p12 keystore).')
try:
    raw = base64.b64decode(SECRET, validate=True)
    if len(raw) > 1024 * 1024:
        raise ValueError('Signing bundle unexpectedly large')
    with zipfile.ZipFile(io.BytesIO(raw)) as z:
        info = json.loads(z.read('signing.json').decode('utf-8'))
        keys = [p for p in z.namelist() if p.lower().endswith(('.p12', '.pfx'))]
        if len(keys) != 1:
            raise ValueError('Expected exactly one PKCS12 keystore')
        key_bytes = z.read(keys[0])
except Exception as exc:
    raise SystemExit('Bad signing bundle. Need Base64 ZIP containing signing.json + exactly one .p12 file.') from exc

for k in ('alias', 'keystore_password', 'key_password', 'certificate_sha256'):
    if not info.get(k):
        raise SystemExit('Signing metadata missing field: ' + k)

sdk = Path(os.environ['ANDROID_SDK_ROOT']) / 'build-tools' / '35.0.0'
src = ROOT / 'app/build/outputs/apk/release'
outputs = list(src.glob('*.apk'))
if len(outputs) != 1:
    raise SystemExit('Expected one release APK in: ' + str(src))

out = ROOT / 'dist/MediaLAB.apk'
out.parent.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='medialab-sign-') as tmp:
    tmp = Path(tmp)
    key = tmp / 'release.p12'
    key.write_bytes(key_bytes)
    key.chmod(0o600)
    cert = tmp / 'release.cer'
    env = os.environ.copy()
    env['MEDIALAB_STORE_PASS'] = info['keystore_password']
    env['MEDIALAB_KEY_PASS'] = info['key_password']
    subprocess.run([
        'keytool', '-exportcert', '-alias', info['alias'], '-keystore', str(key),
        '-storetype', 'PKCS12', '-storepass:env', 'MEDIALAB_STORE_PASS', '-file', str(cert)
    ], check=True, env=env, capture_output=True)
    fingerprint = sha256(cert.read_bytes()).hexdigest().upper()
    expected = info['certificate_sha256'].replace(':', '').upper()
    if expected != fingerprint:
        raise SystemExit('Keystore certificate SHA-256 does not match signing.json')
    aligned = tmp / 'aligned.apk'
    subprocess.run([str(sdk / 'zipalign'), '-f', '-p', '4', str(outputs[0]), str(aligned)], check=True)
    subprocess.run([
        str(sdk / 'apksigner'), 'sign', '--ks', str(key), '--ks-type', 'PKCS12',
        '--ks-key-alias', info['alias'], '--ks-pass', 'env:MEDIALAB_STORE_PASS',
        '--key-pass', 'env:MEDIALAB_KEY_PASS', '--out', str(out), str(aligned)
    ], check=True, env=env)
    subprocess.run([str(sdk / 'apksigner'), 'verify', '--verbose', '--print-certs', str(out)], check=True)
    subprocess.run([str(sdk / 'zipalign'), '-c', '-p', '4', str(out)], check=True)
print('Release signed with certificate SHA-256:', fingerprint)
