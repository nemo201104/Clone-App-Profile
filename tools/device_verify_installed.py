"""Complete visual A–G for the module-owned disposable fixture on an unlocked device.
Requires the reviewed module already installed and an actual reboot performed.
Clears only HOME's cache (when the explicit API is advertised), restarts HOME,
removes/reconciles a managed proxy, and cleans only the recorded test resources.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from device_test import Device, FIXTURE, ROOT

MODULE='/data/adb/modules/clone_app_profile'
REMOTE='/data/local/tmp/cap-acceptance'

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--run',action='store_true')
    parser.add_argument('--snapshot',default='build/pre-reboot-final.json')
    args=parser.parse_args()
    if not args.run:raise SystemExit('Review the script, unlock the device, then pass --run.')
    d=Device(args.serial,MODULE);results=[]
    snapshot=json.loads((ROOT/args.snapshot).read_text(encoding='utf-8'))
    status=d.core('doctor')['details'];assert status['broker']=='READY'
    boot_id=d.shell('cat','/proc/sys/kernel/random/boot_id')
    assert boot_id!=snapshot['bootId'],'An actual reboot after the recorded snapshot is required.'
    assert 'showing=true' not in d.shell('dumpsys','window','policy'),'Unlock the screen; no lockscreen bypass is attempted.'
    rows=d.core('clones')['details'];assert len(rows)==2 and all(c['packageName']==FIXTURE for c in rows)
    assert all(c['launcherEntryState']=='READY_PROXY' for c in rows),[(c['targetUserId'],c.get('launcherError')) for c in rows]
    assert {c['id'] for c in rows}=={c['id'] for c in snapshot['registry']['clones']}
    for c in rows:
        actual=d.evidence(c['packageVerification']['targetDataDir'])
        before=next(e for e in snapshot['sandboxes'] if e['uid']==actual['uid'])
        assert actual['marker']==before['marker']
    expected=hashlib.sha256((ROOT/'module/lib/core.jar').read_bytes()).hexdigest()
    assert d.shell('sha256sum',MODULE+'/lib/core.jar').split()[0]==expected,'Installed backend differs from the tested build.'
    parent=rows[0]['sourceUserId'];home=rows[0]['launcherCapabilities']['home']['packageName']
    record={'parent':parent,'home':home,'clones':[{'id':c['id'],'targetUserId':c['targetUserId'],'serial':c['targetSerial'],'proxy':c['proxyPackage']} for c in rows]}
    results.append({'test':'F-reboot-registry-and-sandbox','passed':True,'bootIdChanged':True,'zipSha256':snapshot['zipSha256'],**record})

    def tree():
        d.shell('uiautomator','dump',REMOTE+'/verify-window.xml')
        return ET.fromstring(d.shell('cat',REMOTE+'/verify-window.xml'))
    def find(label,root):
        return next((n for n in root.iter('node') if n.attrib.get('package')==home and (n.attrib.get('text')==label or n.attrib.get('content-desc')==label) and n.attrib.get('clickable')=='true'),None)
    def catalog(labels):
        d.shell('am','start','--user',parent,'-W','-a','android.intent.action.MAIN','-c','android.intent.category.HOME')
        time.sleep(.7)
        for attempt in range(3):
            root=tree()
            if all(find(label,root) is not None for label in labels):return root
            bounds=re.findall(r'\d+',root.find('node').attrib['bounds']);width,height=int(bounds[2]),int(bounds[3])
            d.shell('input','swipe',width//2,int(height*.82),width//2,int(height*.30),300)
        raise AssertionError('Expected entries are not visible. Open the launcher app drawer at CAP Test Sandbox, then retry; inspect the screen instead of assuming visibility.')
    def screenshot(name):
        folder=ROOT/'build/device-screenshots';folder.mkdir(exist_ok=True)
        r=subprocess.run(['adb','-s',d.serial,'exec-out','screencap','-p'],capture_output=True,check=True)
        (folder/(name+'.png')).write_bytes(r.stdout)
    def tap(c):
        before=d.evidence(c['packageVerification']['targetDataDir'])
        root=catalog([c['proxyLabel']]);n=find(c['proxyLabel'],root)
        x1,y1,x2,y2=map(int,re.findall(r'\d+',n.attrib['bounds']))
        d.shell('input','tap',(x1+x2)//2,(y1+y2)//2)
        for _ in range(30):
            after=d.evidence(c['packageVerification']['targetDataDir'])
            if after['launches']>before['launches'] and after['time']>before['time']:
                assert after['uid']==c['packageVerification']['targetUid'] and after['marker']==before['marker']
                # The fixture writes before ActivityManager returns and the proxy
                # finishes. Let that transition settle before another HOME intent.
                time.sleep(1.2)
                return after
            time.sleep(.25)
        raise AssertionError('Actual launcher tap did not resume the expected fixture sandbox')

    try:
        labels=['CAP Test Sandbox']+[c['proxyLabel'] for c in rows]
        root=catalog(labels);screenshot('three-visible-entries')
        results.append({'test':'A-B-C-visible-distinct-entries','passed':True,'labels':labels})
        for i,c in enumerate(rows):
            proof=tap(c);results.append({'test':f'actual-icon-tap-clone-{i+1}','passed':True,'uid':proof['uid'],'marker':proof['marker']});screenshot('clone-'+str(i+1)+'-sandbox')
        # Restart HOME and clear cache only when the exact advertised option exists.
        help_text=d.shell('pm','help')
        cache_cleared=False
        if re.search(r'clear[^\n]*\[--cache-only\]',help_text):
            output=d.shell('pm','clear','--user',parent,'--cache-only',home)
            assert 'Success' in output,output;cache_cleared=True
        d.shell('am','force-stop','--user',parent,home)
        catalog(labels)
        results.append({'test':'G-launcher-restart-cache','passed':True,'cacheOnlyApiUsed':cache_cleared,'tapUid':tap(rows[1])['uid']})
        # A missing managed proxy must be reconstructed without touching the target app.
        d.shell('pm','uninstall','--user',parent,rows[0]['proxyPackage'])
        d.core('reconcile')
        restored=d.core('clones')['details'];assert {c['id'] for c in restored}=={c['id'] for c in rows}
        results.append({'test':'G-reconcile-missing-entry','passed':True,'tapUid':tap(rows[0])['uid']})
        screenshot('reconciled-sandbox')
        d.core('clone-remove',FIXTURE,rows[0]['targetUserId'])
        remaining=['CAP Test Sandbox',rows[1]['proxyLabel']]
        root=catalog(remaining);assert find(rows[0]['proxyLabel'],root) is None
        assert FIXTURE in d.shell('pm','list','packages','--user',parent,FIXTURE)
        results.append({'test':'D-remove-one-entry','passed':True,'remainingTapUid':tap(rows[1])['uid']})
        d.core('profile-delete',rows[1]['targetUserId'])
        root=catalog(['CAP Test Sandbox']);assert find(rows[1]['proxyLabel'],root) is None
        results.append({'test':'E-delete-profile-entries','passed':True})
        d.core('profile-delete',rows[0]['targetUserId'])
        after=d.core('profiles')['details']
        original={u['serialNumber'] for u in status['status']['profiles'] if not u['moduleManaged']}
        assert {u['serialNumber'] for u in after}==original
        assert d.evidence(rows[0]['packageVerification']['sourceDataDir'])['marker']==snapshot['owner']['marker']
        d.shell('pm','uninstall',FIXTURE)
        final=d.core('status')['details'];assert final['cloned']==0 and final['used']==len(original)
        results.append({'test':'fixture-cleanup','passed':True,'description':final['description']})
        print('A–G visual/routing/persistence/cleanup checks passed. Module remains installed; disposable fixtures removed.',flush=True)
    finally:
        (ROOT/'build/acceptance-final.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
        print('Evidence: build/acceptance-final.json',flush=True)

if __name__=='__main__':main()
