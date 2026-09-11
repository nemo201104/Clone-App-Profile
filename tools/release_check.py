"""Validate the exact device-reviewed artifact before a manual publication."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
FILES = {'module.prop', 'customize.sh', 'service.sh', 'uninstall.sh', 'skip_mount',
         'bin/capctl', 'lib/core.jar', 'lib/proxy-template.apk', 'THIRD_PARTY_NOTICES.txt',
         'webroot/index.html', 'webroot/app.js', 'webroot/bridge.js', 'webroot/style.css', 'webroot/icon.png'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sha256', required=True)
    args = parser.parse_args()
    assert re.fullmatch('[a-f0-9]{64}', args.sha256), 'Expected a reviewed SHA-256'
    artifact = ROOT/'dist/Clone-App-Profile-v1.0.0.zip'
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    assert digest == args.sha256, 'Build differs from the device-reviewed artifact; do not publish'
    assert artifact.with_suffix('.zip.sha256').read_text().strip() == digest+'  '+artifact.name
    update = json.loads((ROOT/'dist/update.json').read_text())
    assert update == json.loads((ROOT/'update.json').read_text())
    assert update['version'] == 'v1.0.0' and type(update['versionCode']) is int and update['versionCode'] == 10000
    with zipfile.ZipFile(artifact) as z:
        assert len(z.namelist()) == len(FILES) and set(z.namelist()) == FILES, z.namelist()
        for name in FILES:
            data = z.read(name)
            assert data == (ROOT/'module'/name).read_bytes(), 'Stale packaged file: '+name
            for token in (b'BEGIN PRIVATE KEY', b'BEGIN RSA PRIVATE KEY', b'ghp_', b'github_pat_', b'127.0.0.1', b'localhost'):
                assert token not in data, 'Private/debug content: '+name
            assert not re.search(rb'[A-Z]:[\\/](?:Users|Code)[\\/]', data), 'Local workspace path: '+name
        props = dict(line.split('=', 1) for line in z.read('module.prop').decode().splitlines() if '=' in line)
        assert props['id'] == 'clone_app_profile' and props['version'] == 'v1.0.0' and props['versionCode'] == '10000'
        assert props['updateJson'] == 'https://github.com/nemo201104/Clone-App-Profile/releases/latest/download/update.json'
        assert props['webuiIcon'] == 'webroot/icon.png'
        assert re.fullmatch(r'https://raw\.githubusercontent\.com/nemo201104/Clone-App-Profile/(?:[a-f0-9]{40}|v1\.0\.0)/assets/banner\.png', props['banner'])
        assert z.read(props['webuiIcon']).startswith(b'\x89PNG\r\n\x1a\n')
        with zipfile.ZipFile(ROOT/'module/lib/proxy-template.apk') as apk:
            assert b'io.github.nemo.cap.fixture' not in apk.read('classes.dex')
    print('Reviewed release artifact validated:', artifact.stat().st_size, 'bytes;', digest)


if __name__ == '__main__':
    main()
