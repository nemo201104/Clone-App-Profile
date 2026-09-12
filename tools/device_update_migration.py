"""Pre-publication device comparison using controlled candidate metadata.

Loads the real installed UpdateMetadata/Core classes with a separate test DEX.
This does not replace the post-publication HTTPS gate in device_update_gate.py.
The immutable legacy updater is expected to reject the migrated repository.
"""
import argparse
import hashlib
import json
import re
import time
from pathlib import Path
from build import ROOT, BUILD, toolchain, compile_java, archive_classes, run
from device_test import Device

MODULE = '/data/adb/modules/clone_app_profile'
STAGE = '/data/local/tmp/cap-update-maintenance'
ENDPOINT = 'https://github.com/nemoforge/Clone-App-Profile/releases/latest/download/update.json'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--legacy', action='store_true', help='Read-only rejection proof before manual migration')
    parser.add_argument('--run', action='store_true', help='Authorize controlled installed versionCode test and restoration')
    args = parser.parse_args()
    if not (args.legacy or args.run) or (args.legacy and args.run):
        raise SystemExit('Select exactly one of --legacy or --run.')
    java, javac, bt, android, _ = toolchain()
    classes = BUILD/'update-migration-classes'
    compile_java(javac, [ROOT/'tests/UpdateMigrationProbe.java'], classes, [BUILD/'core-classes', android])
    jar = BUILD/'update-migration-classes.jar'
    archive_classes(classes, jar)
    dex = BUILD/'update-migration-dex'
    dex.mkdir(exist_ok=True)
    run([java, '-cp', bt/'lib/d8.jar', 'com.android.tools.r8.D8', '--release', '--min-api', '31', '--lib', android, '--output', dex, jar])
    import zipfile
    probe = BUILD/'update-migration-probe.jar'
    with zipfile.ZipFile(probe, 'w') as z:
        z.write(dex/'classes.dex', 'classes.dex')
    expected = json.loads((ROOT/'update.json').read_text())
    assert expected['version'] == 'v1.0.1' and expected['versionCode'] == 10001
    d = Device(args.serial, MODULE)
    d.shell('mkdir', '-p', STAGE)
    d.shell('chown', '2000:2000', STAGE)
    d.shell('chmod', '0700', STAGE)
    d.adb('push', probe, STAGE+'/probe.jar')
    d.adb('push', ROOT/'update.json', STAGE+'/metadata.json')

    def evaluate():
        raw = d.shell('/system/bin/env', 'CLASSPATH='+MODULE+'/lib/core.jar:'+STAGE+'/probe.jar',
                      '/system/bin/app_process', '/system/bin', 'io.github.nemo.cap.UpdateMigrationProbe', MODULE, STAGE+'/metadata.json')
        return json.loads(raw.splitlines()[-1])

    if args.legacy:
        result = evaluate()
        assert result['installedVersionCode'] == 10000
        assert not result['success'] and result['errorCode'] == 'INVALID_UPDATE_METADATA'
        (BUILD/'maintenance-legacy-device.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        print('Legacy migration limitation confirmed on the actual installed backend; manual installation is required.')
        return

    assert not d.script('for flag in disable remove; do if [ -e '+MODULE+'/$flag ]; then echo $flag; fi; done'), 'Module must be enabled'
    original = d.shell('cat', MODULE+'/module.prop')+'\n'
    assert hashlib.sha256(original.encode()).hexdigest() == d.shell('sha256sum', MODULE+'/module.prop').split()[0]
    assert re.search(r'^versionCode=10001$', original, re.M)
    lower, count = re.subn(r'^versionCode=10001$', 'versionCode=10000', original, flags=re.M)
    assert count == 1

    def write_props(text):
        local = BUILD/'maintenance-update-module.prop'
        local.write_bytes(text.encode())
        d.adb('push', local, STAGE+'/module.prop')
        d.shell('cp', STAGE+'/module.prop', MODULE+'/module.prop.next')
        d.shell('chmod', '0644', MODULE+'/module.prop.next')
        d.shell('mv', MODULE+'/module.prop.next', MODULE+'/module.prop')
        assert hashlib.sha256(text.encode()).hexdigest() == d.shell('sha256sum', MODULE+'/module.prop').split()[0]

    d.shell('touch', MODULE+'/disable')
    try:
        for _ in range(30):
            if not any('io.github.nemo.cap.Core '+MODULE+' service' in row for row in d.shell('ps', '-A', '-o', 'PID,ARGS').splitlines()):
                break
            time.sleep(1)
        else:
            raise RuntimeError('Service did not stop; no version was changed')
        d.shell('rm', '-f', MODULE+'/disable')
        try:
            current = evaluate()
            write_props(lower)
            previous = evaluate()
        finally:
            write_props(original)
        restored = evaluate()
        for result, code, state in [(current,10001,'UP_TO_DATE'), (previous,10000,'UPDATE_AVAILABLE'), (restored,10001,'UP_TO_DATE')]:
            assert result['success'] and result['endpoint'] == ENDPOINT
            assert result['details']['installedVersionCode'] == code and result['details']['state'] == state
            assert result['details']['metadata'] == expected
        report = dict(passed=True, mode='CONTROLLED_METADATA_NO_NETWORK', current=current, previousVersion=previous, restored=restored,
                      limitation='One-time manual migration is required; public HTTPS verification remains a post-publication gate.')
        (BUILD/'maintenance-update-device.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
        print('Controlled device metadata checks passed: 10000 UPDATE_AVAILABLE; 10001/restored UP_TO_DATE. No public release was created.')
    finally:
        d.shell('rm', '-f', MODULE+'/disable')
        d.script('/system/bin/sh '+MODULE+'/service.sh >> /data/adb/clone_app_profile/maintenance-supervisor.log 2>&1 </dev/null &')


if __name__ == '__main__':
    main()
