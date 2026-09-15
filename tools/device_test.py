"""Rooted device acceptance runner. Mutates only its new fixture/profiles/proxies.
No reboot, launcher force-stop or cache clearing is performed implicitly.
"""
import argparse
import json
from pathlib import Path
import shlex
import subprocess
import time

ROOT=Path(__file__).resolve().parents[1]
STAGE='/data/local/tmp/cap-acceptance'
FIXTURE='io.github.nemo.cap.fixture'

class Device:
    def __init__(self,serial,module=STAGE):self.serial=serial;self.module=module;self.events=[]
    def adb(self,*args,check=True,timeout=150):
        r=subprocess.run(['adb','-s',self.serial,*map(str,args)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout)
        if check and r.returncode:raise RuntimeError(r.stdout+'\n'+r.stderr)
        return r.stdout.strip()
    def shell(self,*args,check=True):return self.adb('shell','su','-c',shlex.quote(' '.join(shlex.quote(str(a)) for a in args)),check=check)
    def script(self,script):return self.adb('shell','su','-c',shlex.quote(script))
    def core(self,*args,success=True):
        stdout=self.shell(self.module+'/bin/capctl',*args,check=False)
        result=json.loads(stdout.splitlines()[-1]);self.events.append({'command':list(args),'result':result})
        print(args[0],result['errorCode'],flush=True)
        if success and not result['success']:raise RuntimeError(json.dumps(result,ensure_ascii=False))
        return result
    def evidence(self,data_dir):
        for _ in range(30):
            raw=self.shell('cat',data_dir+'/files/launch-evidence.json',check=False)
            try:return json.loads(raw)
            except json.JSONDecodeError:time.sleep(.3)
        raise AssertionError('Fixture never wrote launch evidence: '+data_dir)
    def launch(self,package,parent):
        self.shell('am','start','--user',parent,'-W','-n',package+'/io.github.nemo.cap.proxy.EntryActivity','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--run',action='store_true',help='Authorize disposable fixture installation and new profile creation/deletion')
    parser.add_argument('--keep-on-failure',action='store_true',help='Retain failed fixture/profiles for debugging (default cleans owned profiles)')
    parser.add_argument('--keep-for-visual-check',action='store_true',help='Leave two working clones for explicit visual/reboot checks; print cleanup commands')
    args=parser.parse_args();d=Device(args.serial)
    if not args.run:raise SystemExit('No changes made. Pass --run after reviewing this test script.')
    # Never reuse somebody else's installed module or take ownership of existing fixtures.
    assert d.script('if [ -e /data/adb/modules/clone_app_profile/module.prop ]; then echo EXISTS; fi')!='EXISTS','Use an isolated device or uninstall the module first.'
    assert not d.shell('pm','list','packages','-u',FIXTURE),'Fixture already known to PackageManager; inspect manually.'
    d.adb('push',str(ROOT/'dist/Clone-App-Profile-v1.0.2.zip'),STAGE+'.zip')
    d.shell('mkdir','-p',STAGE)
    d.shell('/data/adb/ksu/bin/busybox','unzip','-o',STAGE+'.zip','-d',STAGE)
    d.shell('rm','-f',STAGE+'/disable')
    d.shell('chmod','0755',STAGE+'/bin/capctl')
    pre=d.core('doctor')['details']
    if pre.get('registryIntegrity')=='NOT_INITIALIZED':
        users=pre['users'];maximum=pre['maxUsers'];parent=pre['currentUser']
    else:
        assert pre.get('registryIntegrity')=='VALID' and not pre['pending'],'An empty valid test registry is required.'
        assert not d.core('clones')['details'] and not any(p['moduleManaged'] for p in pre['status']['profiles']),'Existing module resources are not disposable test fixtures.'
        users=pre['status']['profiles'];maximum=pre['status']['max'];parent=pre['status']['currentUser']
    baseline={u['serialNumber'] for u in users}
    assert maximum-len(baseline)>=2,'Need capacity for two additional clone profiles.'
    owned=[];clones=[];failed=False
    d.core('init')
    d.script('/system/bin/sh '+STAGE+'/service.sh >'+STAGE+'/supervisor.log 2>&1 </dev/null &')
    d.adb('push',str(ROOT/'build/cap-fixture.apk'),STAGE+'/fixture.apk')
    install=d.shell('pm','install','--user',parent,STAGE+'/fixture.apk');assert 'Success' in install,install
    try:
        for _ in range(20):
            doctor=d.core('doctor')['details']
            if doctor.get('broker')=='READY':break
            time.sleep(.5)
        else:raise AssertionError('Broker did not start')
        for index in (1,2):
            profile=d.core('profile-create')['details'];owned.append(profile['userId'])
            clone=d.core('clone',FIXTURE,profile['userId'])['details']['clone'];clones.append(clone)
            d.launch(clone['proxyPackage'],parent)
            evidence=d.evidence(clone['packageVerification']['targetDataDir'])
            assert evidence['uid']==clone['packageVerification']['targetUid']
            d.events.append({'test':f'clone-{index}-exact-uid','evidence':evidence})
        assert clones[0]['id']!=clones[1]['id'] and clones[0]['proxyPackage']!=clones[1]['proxyPackage']
        full=d.core('profile-create',success=False);assert full['errorCode']=='MAX_USERS_REACHED'
        owner_delete=d.core('profile-delete','0',success=False);assert owner_delete['errorCode']=='PROFILE_NOT_MANAGED'
        # Owner is launched explicitly and must have a third independent marker and UID.
        d.shell('am','start','--user',parent,'-W','-n',FIXTURE+'/io.github.nemo.cap.fixture.MainActivity')
        owner=d.evidence(clones[0]['packageVerification']['sourceDataDir'])
        proofs=[d.evidence(c['packageVerification']['targetDataDir']) for c in clones]+[owner]
        assert len({e['uid'] for e in proofs})==3 and len({e['marker'] for e in proofs})==3
        d.events.append({'test':'three-independent-sandboxes','evidence':proofs})
        d.core('reconcile')
        assert {c['id'] for c in d.core('clones')['details']}=={c['id'] for c in clones}
        if args.keep_for_visual_check:
            d.shell('am','start','--user',parent,'-W','-a','android.intent.action.MAIN','-c','android.intent.category.HOME')
            print('Retained for visual check; cleanup only these owned profiles:',owned,flush=True)
            return
        d.core('clone-remove',FIXTURE,owned[0])
        assert not d.shell('pm','list','packages','--user',parent,clones[0]['proxyPackage'])
        assert FIXTURE in d.shell('pm','list','packages','--user',owned[1],FIXTURE)
        assert FIXTURE in d.shell('pm','list','packages','--user',parent,FIXTURE)
        d.events.append({'test':'remove-only-one-instance','passed':True})
        d.core('profile-delete',owned[1]);owned.pop()
        assert not d.shell('pm','list','packages','--user',parent,clones[1]['proxyPackage'])
        d.events.append({'test':'delete-profile-cleanup','passed':True})
        d.core('profile-delete',owned[0]);owned.clear()
        after=d.core('profiles')['details'];assert {u['serialNumber'] for u in after}==baseline
        print('A–E API/routing checks passed. Visual launcher taps, reboot and launcher restart remain separate checks.',flush=True)
    except Exception:
        failed=True;raise
    finally:
        if not(failed and args.keep_on_failure) and not args.keep_for_visual_check:
            for uid in reversed(owned):d.core('profile-delete',uid,success=False)
            d.shell('pm','uninstall',FIXTURE,check=False)
            d.shell('touch',STAGE+'/disable')
        (ROOT/'build/device-results.json').write_text(json.dumps(d.events,ensure_ascii=False,indent=2),encoding='utf-8')
        print('Evidence saved to build/device-results.json',flush=True)

if __name__=='__main__':main()
