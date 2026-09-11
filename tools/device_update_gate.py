"""Mandatory post-publication update test using the actual installed backend.

Reads the fixed public release endpoint on-device, temporarily sets ONLY the
installed module.prop versionCode to 9999, and restores the original bytes in
finally. Does not build/upload a lower-version release or mock the network.
"""
import argparse
import hashlib
import json
import re
import time
from device_test import Device, ROOT

MODULE = '/data/adb/modules/clone_app_profile'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--run', action='store_true')
    args = parser.parse_args()
    if not args.run:
        raise SystemExit('Run only after the reviewed public release exists; pass --run.')
    d = Device(args.serial, MODULE)
    remote = MODULE+'/module.prop'
    # module.prop is public module metadata, never registry or signing material.
    original = d.shell('cat', remote) + '\n'
    assert hashlib.sha256(original.encode()).hexdigest() == d.shell('sha256sum', remote).split()[0], 'Metadata must round-trip exactly before testing'
    assert re.search(r'^versionCode=10000$', original, re.M)
    lower, count = re.subn(r'^versionCode=10000$', 'versionCode=9999', original, flags=re.M)
    assert count == 1
    d.shell('touch', MODULE+'/disable')
    try:
        for _ in range(30):
            rows = d.shell('ps', '-A', '-o', 'PID,ARGS').splitlines()
            if not any('io.github.nemo.cap.Core '+MODULE+' service' in r for r in rows):
                break
            time.sleep(1)
        else:
            raise RuntimeError('Module service did not stop; no metadata was changed')
        d.shell('rm', '-f', MODULE+'/disable')

        def write_props(text):
            local = ROOT/'build/update-gate-module.prop'
            local.write_bytes(text.encode('utf-8'))
            stage = '/data/local/tmp/cap-update-gate-module.prop'
            d.adb('push', local, stage)
            d.shell('cp', stage, remote+'.next')
            d.shell('chmod', '0644', remote+'.next')
            d.shell('mv', remote+'.next', remote)
            assert hashlib.sha256(text.encode()).hexdigest() in d.shell('sha256sum', remote)

        try:
            current = d.core('update-check')['details']
            assert current['state'] == 'UP_TO_DATE' and current['installedVersionCode'] == 10000
            write_props(lower)
            available = d.core('update-check')['details']
            assert available['state'] == 'UPDATE_AVAILABLE' and available['installedVersionCode'] == 9999
            expected = json.loads((ROOT/'update.json').read_text())
            assert current['metadata'] == available['metadata'] == expected
            assert expected['versionCode'] == 10000 and expected['version'] == 'v1.0.0'
        finally:
            write_props(original)
        restored = d.core('update-check')['details']
        assert restored['state'] == 'UP_TO_DATE' and restored['installedVersionCode'] == 10000
        result = dict(passed=True, current=current, lowerInstalledVersion=available, restored=restored,
                      endpoint='https://github.com/nemo201104/Clone-App-Profile/releases/latest/download/update.json',
                      source='Actual installed capctl on-device HTTPS requests; no mock')
        (ROOT/'build/release-update-device.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
        print('Published update flow passed: 10000 UP_TO_DATE; 9999 UPDATE_AVAILABLE; restored 10000 UP_TO_DATE.')
    finally:
        d.shell('rm', '-f', MODULE+'/disable')
        d.script('/system/bin/sh '+MODULE+'/service.sh >> /data/adb/clone_app_profile/release-supervisor.log 2>&1 </dev/null &')


if __name__ == '__main__':
    main()
