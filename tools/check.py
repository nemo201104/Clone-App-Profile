import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import zipfile

sys.path.insert(0, str(Path(__file__).parent))
from build import ROOT, BUILD, toolchain, compile_java, run

def main():
    java, javac, bt, android, suffix = toolchain()
    bash = shutil.which('bash') or ('C:/Program Files/Git/bin/bash.exe' if os.name == 'nt' else None)
    if not bash:
        raise SystemExit('bash is required for POSIX shell syntax checks')
    for p in list((ROOT / 'module').glob('*.sh')) + [ROOT / 'module/bin/capctl']:
        run([bash, '-n', p])
        assert b'\r\n' not in p.read_bytes(), p
    for p in (ROOT / 'module/webroot').glob('*.js'):
        run(['node', '--check', p])
    # Runtime-owned copy only: app/profile names, historical docs, Unicode test
    # fixtures and third-party notices are deliberately outside this scan.
    runtime = list((ROOT/'module/webroot').glob('*.html')) + list((ROOT/'module/webroot').glob('*.js')) + list((ROOT/'module/webroot').glob('*.css'))
    runtime += list((ROOT/'module').glob('*.sh')) + [ROOT/'module/bin/capctl', ROOT/'module/module.prop']
    runtime += list((ROOT/'core/src').rglob('*.java')) + list((ROOT/'proxy/src').rglob('*.java'))
    non_english = re.compile(r'[\u00c0-\u00ff\u0102\u0103\u0110\u0111\u01a0\u01a1\u01af\u01b0\u1ea0-\u1ef9\u4e00-\u9fff]')
    for path in runtime:
        text = path.read_text(encoding='utf-8')
        assert not non_english.search(text), 'Non-English runtime copy: '+str(path)
        assert not re.search(r'nemo2011|nemo\.github\.io', text, re.I), 'Old runtime identity: '+str(path)
    html = (ROOT/'module/webroot/index.html').read_text(encoding='utf-8')
    assert '<html lang="en">' in html and 'src="icon.png"' in html
    assert 'Author: nemoforge' in html and '<span class="tag">v1.0.2</span>' in html
    assert 'href="https://nemoforge.github.io"' in html
    assert 'banner.png' not in html, 'Manager banner must not add a WebUI network request'
    css = (ROOT/'module/webroot/style.css').read_text(encoding='utf-8')
    assert css.count('#0E60E2') == 1 and '--color-primary: #0E60E2;' in css
    run(['node', '--test', ROOT / 'tests/bridge.test.cjs'])
    update = json.loads((ROOT / 'update.json').read_text())
    assert set(update) == {'version', 'versionCode', 'zipUrl', 'changelog'}
    assert update['version'] == 'v1.0.2' and type(update['versionCode']) is int and update['versionCode'] == 10002
    assert update['zipUrl'] == 'https://github.com/nemoforge/Clone-App-Profile/releases/download/v1.0.2/Clone-App-Profile-v1.0.2.zip'
    assert update['changelog'] == 'https://raw.githubusercontent.com/nemoforge/Clone-App-Profile/v1.0.2/CHANGELOG.md'
    json_jar = ROOT / '.cache/json-20250517.jar'
    if not json_jar.exists():
        json_jar.parent.mkdir(exist_ok=True)
        url = 'https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar'
        urllib.request.urlretrieve(url, json_jar)
        import hashlib
        expected = urllib.request.urlopen(url + '.sha1').read().decode().strip()
        assert hashlib.sha1(json_jar.read_bytes()).hexdigest() == expected
    import hashlib
    assert hashlib.sha256(json_jar.read_bytes()).hexdigest() == '3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796'
    tests = BUILD / 'test-classes'
    cp = [BUILD / 'core-classes', BUILD / 'apksig.jar', json_jar, android]
    compile_java(javac, [ROOT / 'tests/HostTests.java', ROOT / 'tests/DeviceReadOnlyChecks.java'], tests, cp)
    artifact = BUILD / 'host-proxy-test.apk'
    run([java, '-cp', os.pathsep.join(map(str, [tests, *cp])), 'io.github.nemo.cap.HostTests', ROOT / 'module', ROOT / 'proxy/res/drawable/app_icon.png', artifact])
    result = subprocess.run([str(bt / ('aapt2' + suffix)), 'dump', 'badging', str(artifact)], check=True, capture_output=True, text=True, encoding='utf-8')
    assert "package: name='io.github.nemo.cap.entry.p" in result.stdout
    assert "application-label:'Zalo — Clone 1 · Unicode thử nghiệm'" in result.stdout
    assert "launchable-activity: name='io.github.nemo.cap.proxy.EntryActivity'" in result.stdout
    for p in (ROOT / 'core/src').rglob('*.java'):
        if p.name != 'ZteAdapter.java':
            assert not any(v in p.read_text(encoding='utf-8') for v in ['999', 'com.zte.', 'REDMAGIC', 'Nubia']), p
    with zipfile.ZipFile(ROOT / 'dist/Clone-App-Profile-v1.0.2.zip') as z:
        names = set(z.namelist())
        # A missing policy recreates the real WildKSU proxy failure after boot.
        # Bound the grant: never replace it with domain/appdomain or wildcard access.
        policy = (ROOT/'module/sepolicy.rule').read_bytes()
        assert z.read('sepolicy.rule') == policy
        assert b'\r' not in policy
        rules = [line.strip() for line in policy.decode().splitlines() if line.strip() and not line.lstrip().startswith('#')]
        assert rules == ['allow untrusted_app su unix_stream_socket connectto']
        for required in ['module.prop', 'customize.sh', 'service.sh', 'uninstall.sh', 'bin/capctl', 'lib/core.jar', 'lib/proxy-template.apk', 'webroot/index.html', 'webroot/icon.png']:
            assert required in names, required
        assert not any(n.startswith(('module/', '.git/', '.cache/', '.local/', 'assets/', 'build/', 'dist/', 'tests/')) for n in names)
        assert 'webroot/banner.png' in names
        assert z.read('webroot/banner.png') == (ROOT/'assets/banner.png').read_bytes()
        assert z.read('webroot/banner.png') == (ROOT/'module/webroot/banner.png').read_bytes()
        assert z.read('webroot/banner.png').startswith(b'\x89PNG\r\n\x1a\n')
        assert z.read('webroot/icon.png') == (ROOT/'module/webroot/icon.png').read_bytes()
        assert z.read('webroot/icon.png').startswith(b'\x89PNG\r\n\x1a\n')
        props = dict(line.split('=', 1) for line in z.read('module.prop').decode().splitlines() if '=' in line)
        assert props['name'] == 'Clone App Profile' and props['author'] == 'nemoforge'
        assert props['version'] == 'v1.0.2' and props['versionCode'] == '10002'
        assert props['updateJson'] == 'https://github.com/nemoforge/Clone-App-Profile/releases/latest/download/update.json'
        assert props['webuiIcon'] == 'webroot/icon.png' and 'actionIcon' not in props
        assert props['banner'] == 'webroot/banner.png'
    print('All host/static/ZIP checks passed.')

if __name__ == '__main__':
    main()
