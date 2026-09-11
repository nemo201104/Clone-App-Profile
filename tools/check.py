import json
import os
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
    run(['node', '--test', ROOT / 'tests/bridge.test.cjs'])
    update = json.loads((ROOT / 'update.json').read_text())
    assert set(update) == {'version', 'versionCode', 'zipUrl', 'changelog'}
    assert type(update['versionCode']) is int and update['versionCode'] == 10000
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
    compile_java(javac, [ROOT / 'tests/HostTests.java'], tests, cp)
    artifact = BUILD / 'host-proxy-test.apk'
    run([java, '-cp', os.pathsep.join(map(str, [tests, *cp])), 'io.github.nemo.cap.HostTests', ROOT / 'module', ROOT / 'proxy/res/drawable/app_icon.png', artifact])
    result = subprocess.run([str(bt / ('aapt2' + suffix)), 'dump', 'badging', str(artifact)], check=True, capture_output=True, text=True, encoding='utf-8')
    assert "package: name='io.github.nemo.cap.entry.p" in result.stdout
    assert "application-label:'Zalo — Clone 1 · Unicode thử nghiệm'" in result.stdout
    assert "launchable-activity: name='io.github.nemo.cap.proxy.EntryActivity'" in result.stdout
    for p in (ROOT / 'core/src').rglob('*.java'):
        if p.name != 'ZteAdapter.java':
            assert not any(v in p.read_text(encoding='utf-8') for v in ['999', 'com.zte.', 'REDMAGIC', 'Nubia']), p
    with zipfile.ZipFile(ROOT / 'dist/Clone-App-Profile-v1.0.0.zip') as z:
        names = set(z.namelist())
        for required in ['module.prop', 'customize.sh', 'service.sh', 'uninstall.sh', 'bin/capctl', 'lib/core.jar', 'lib/proxy-template.apk', 'webroot/index.html']:
            assert required in names, required
        assert not any(n.startswith(('module/', '.git/', '.cache/')) for n in names)
    print('All host/static/ZIP checks passed.')

if __name__ == '__main__':
    main()
