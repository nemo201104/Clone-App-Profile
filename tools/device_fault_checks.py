"""Additional negative cases using only a fresh module-owned disposable profile.
Requires installed module and the separate permission-free test fixture APK.
"""
import argparse
import json
import time
from device_test import Device, FIXTURE, ROOT

MODULE='/data/adb/modules/clone_app_profile'

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--run',action='store_true')
    args=parser.parse_args()
    if not args.run:raise SystemExit('Review disposable-resource mutations, then pass --run.')
    d=Device(args.serial,MODULE);owned=None;installed=False
    before=d.core('status')['details'];baseline={p['serialNumber'] for p in before['profiles']}
    assert before['used']<before['max'] and not before['pending']
    assert not d.shell('pm','list','packages','-u',FIXTURE),'Do not reuse an existing fixture installation.'
    def reject(code,*command):
        result=d.core(*command,success=False)
        assert not result['success'] and result['errorCode']==code,result
    try:
        for p in before['profiles']:
            if not p['moduleManaged']:reject('PROFILE_NOT_MANAGED','profile-delete',p['userId'])
        reject('TARGET_NOT_CLONE','clone',FIXTURE,before['currentUser'])
        missing=next(i for i in range(100,1000) if all(p['userId']!=i for p in before['profiles']))
        reject('PROFILE_NOT_FOUND','clone',FIXTURE,missing)
        reject('INVALID_ARGUMENT','clone',FIXTURE+';id',missing)
        profile=d.core('profile-create')['details'];owned=profile['userId']
        reject('PACKAGE_NOT_FOUND','clone','io.github.nemo.cap.nonexistent',owned)
        reject('PACKAGE_NOT_MANAGED','clone-remove',FIXTURE,owned)
        d.adb('push',str(ROOT/'build/cap-fixture.apk'),'/data/local/tmp/cap-fault-fixture.apk')
        assert 'Success' in d.shell('pm','install','--user',before['currentUser'],'/data/local/tmp/cap-fault-fixture.apk');installed=True
        # Explicit test-created untracked package: core must refuse to adopt/remove it.
        d.shell('pm','install-existing','--user',owned,FIXTURE)
        reject('PACKAGE_ALREADY_PRESENT','clone',FIXTURE,owned)
        reject('PACKAGE_NOT_MANAGED','clone-remove',FIXTURE,owned)
        assert not any(c['targetUserId']==owned for c in d.core('clones')['details'])
        assert FIXTURE in d.shell('pm','list','packages','--user',owned,FIXTURE)
        # Simulate external deletion only for our just-created disposable profile.
        actual=next(p for p in d.core('profiles')['details'] if p['userId']==owned)
        assert actual['moduleManaged'] and actual['serialNumber']==profile['serialNumber']
        d.shell('pm','remove-user',owned)
        for _ in range(30):
            if all(p['userId']!=owned for p in d.core('profiles')['details']):break
            time.sleep(.5)
        else:raise AssertionError('Disposable profile removal did not finish')
        d.core('profile-delete',owned);owned=None
        after=d.core('status')['details']
        assert {p['serialNumber'] for p in after['profiles']}==baseline
        assert after['cloned']==before['cloned']
        print('Negative device checks passed; no untracked ownership was acquired.',flush=True)
    finally:
        if owned is not None:d.core('profile-delete',owned)
        if installed:d.shell('pm','uninstall',FIXTURE)
        (ROOT/'build/device-fault-results.json').write_text(json.dumps(d.events,indent=2),encoding='utf-8')

if __name__=='__main__':main()
