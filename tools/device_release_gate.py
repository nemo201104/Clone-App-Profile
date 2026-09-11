"""Reviewed-fixture release gate for native OEM + managed sibling coexistence.

Requires an installed module and exactly two module-owned clones of the disposable
fixture: one explicitly selected external OEM profile, one module-created profile.
Never creates/adopts an external profile. Review --capture before --run; later
phases require its identity snapshot. Raw device evidence stays in ignored build/.
"""
import argparse
import hashlib
import json
import re
import time
import xml.etree.ElementTree as ET
from device_test import Device, FIXTURE, ROOT

MODULE = '/data/adb/modules/clone_app_profile'
LABEL = 'CAP Test Sandbox'
PREFIX = 'io.github.nemo.cap.entry.p'


class Gate:
    def __init__(self, device, snapshot):
        self.d, self.snapshot, self.events = device, snapshot, []
        self.rows = snapshot['clones']
        self.parent = self.rows[0]['sourceUserId']
        self.home = self.rows[0]['launcherCapabilities']['home']['packageName']

    def record(self, name, **values):
        self.events.append(dict(test=name, passed=True, **values))
        print(name, 'PASS', flush=True)

    def tree(self):
        self.d.shell('input', 'keyevent', 'KEYCODE_WAKEUP')
        assert 'showing=true' not in self.d.shell('dumpsys', 'window', 'policy'), 'Unlock the device; no lockscreen bypass is attempted.'
        dest = '/data/local/tmp/cap-release-window.xml'
        self.d.shell('uiautomator', 'dump', dest)
        return ET.fromstring(self.d.shell('cat', dest))

    def entries(self, tree):
        return [n for n in tree.iter('node') if n.get('package') == self.home
                and n.get('clickable') == 'true'
                and (n.get('text', '').startswith(LABEL) or n.get('content-desc', '').startswith(LABEL))]

    def catalog(self, count):
        self.d.shell('input', 'keyevent', 'KEYCODE_WAKEUP')
        self.d.shell('am', 'start', '--user', self.parent, '-W', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
        time.sleep(1.2)
        for _ in range(4):
            tree = self.tree()
            nodes = self.entries(tree)
            if len(nodes) == count:
                return nodes
            bounds = list(map(int, re.findall(r'\d+', tree.find('node').get('bounds'))))
            self.d.shell('input', 'swipe', bounds[2] // 2, int(bounds[3] * .82), bounds[2] // 2, int(bounds[3] * .30), 300)
        raise AssertionError(f'Expected exactly {count} fixture entries, observed {len(nodes)}; inspect the drawer.')

    def evidence(self, data_dir):
        raw = self.d.shell('cat', data_dir + '/files/launch-evidence.json', check=False)
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return {'launches': 0, 'time': 0}

    def paths(self, rows):
        paths = [rows[0]['packageVerification']['sourceDataDir']] if rows else [self.snapshot['ownerDataDir']]
        return paths + [c['packageVerification']['targetDataDir'] for c in rows]

    def tap_all(self, rows):
        paths = self.paths(rows)
        expected = {self.snapshot['ownerUid']} | {c['packageVerification']['targetUid'] for c in rows}
        seen, proofs = set(), []
        for index in range(len(paths)):
            nodes = self.catalog(len(paths))
            # Identical owner/OEM labels are resolved by actual sandbox evidence,
            # never by icon order or guessed screen coordinates.
            before = [self.evidence(p) for p in paths]
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', nodes[index].get('bounds')))
            self.d.shell('input', 'tap', (x1+x2)//2, (y1+y2)//2)
            for _ in range(30):
                after = [self.evidence(p) for p in paths]
                changed = [e for e, b in zip(after, before) if e['launches'] > b['launches'] and e['time'] > b['time']]
                if changed:
                    assert len(changed) == 1, 'One icon resumed more than one instance'
                    proof = changed[0]
                    assert proof['uid'] in expected and proof['uid'] not in seen, 'Wrong or duplicate sandbox routing'
                    seen.add(proof['uid']); proofs.append(proof)
                    time.sleep(1.2)
                    break
                time.sleep(.25)
            else:
                raise AssertionError('Launcher tap did not resume any expected fixture sandbox')
        assert seen == expected
        assert len({p['marker'] for p in proofs}) == len(proofs)
        return proofs

    def validate(self):
        rows = self.d.core('clones')['details']
        assert len(rows) == 2 and all(c['packageName'] == FIXTURE for c in rows)
        assert {c['id'] for c in rows} == {c['id'] for c in self.rows}
        for c in rows:
            old = next(o for o in self.rows if o['id'] == c['id'])
            for key in ('targetSerial', 'parentSerial', 'targetUserId', 'sourceUserId', 'targetCertificate', 'targetFirstInstallTime'):
                assert c[key] == old[key], f'Fixture identity changed: {key}'
            assert c['launcherIntegration'] == 'Success'
            if c['profileClassification'] == 'EXTERNAL_OEM':
                assert c['launcherMode'] == 'NATIVE' and c['launcherEntryState'] == 'READY_NATIVE'
                assert c['nativeEvidence']['entryCount'] == 1 and 'proxyPackage' not in c
                assert not self.d.shell('pm', 'list', 'packages', '-u', PREFIX+c['id']), 'OEM native clone still has proxy'
            else:
                assert c['profileClassification'] == 'MODULE_MANAGED'
                assert c['launcherMode'] == 'PROXY' and c['launcherEntryState'] == 'READY_PROXY'
                assert c['nativeEvidence']['state'] == 'ABSENT'
                assert c['proxyPackage'] == PREFIX+c['id']
        return rows

    def inspect(self):
        rows = self.validate()
        proofs = self.tap_all(rows)
        self.record('owner-oem-managed-visible-and-exact-taps', uids=sorted(p['uid'] for p in proofs), markers=[p['marker'] for p in proofs])
        before = [(c['id'], c['launcherMode'], c.get('proxyPackage'), c.get('proxyContentFingerprint')) for c in rows]
        for _ in range(3):
            self.d.core('reconcile')
            after = self.validate()
            assert before == [(c['id'], c['launcherMode'], c.get('proxyPackage'), c.get('proxyContentFingerprint')) for c in after]
        self.catalog(3)
        self.record('three-reconciles-no-duplicate-or-identity-change')
        return proofs

    def reboot_check(self):
        assert self.d.shell('cat', '/proc/sys/kernel/random/boot_id') != self.snapshot['bootId'], 'Actual reboot required'
        rows = self.validate()
        for path in self.paths(rows):
            proof = self.evidence(path)
            old = next(e for e in self.snapshot['sandboxes'] if e['uid'] == proof['uid'])
            assert proof['marker'] == old['marker']
        self.inspect()
        self.record('reboot-preserves-native-proxy-identities-and-sandboxes')
        self.d.shell('am', 'force-stop', '--user', self.parent, self.home)
        self.catalog(3)
        self.d.core('reconcile')
        self.tap_all(self.validate())
        self.record('launcher-restart-preserves-three-entries-and-routing')
        proxy = next(c for c in rows if c['launcherMode'] == 'PROXY')
        self.d.shell('pm', 'uninstall', '--user', self.parent, proxy['proxyPackage'])
        self.d.core('reconcile')
        self.tap_all(self.validate())
        self.record('missing-owned-proxy-reconstructed-with-same-identity')

    def cleanup(self):
        rows = self.validate()
        external = next(c for c in rows if c['profileClassification'] == 'EXTERNAL_OEM')
        managed = next(c for c in rows if c['profileClassification'] == 'MODULE_MANAGED')
        profiles = self.d.core('profiles')['details']
        oem = next(p for p in profiles if p['serialNumber'] == external['targetSerial'])
        assert not oem['moduleManaged']
        # External profile deletion must be refused before any mutation.
        denied = self.d.core('profile-delete', oem['userId'], success=False)
        assert denied['errorCode'] == 'PROFILE_NOT_MANAGED'
        self.d.core('clone-remove', FIXTURE, external['targetUserId'])
        self.tap_all([managed])
        current = next(p for p in self.d.core('profiles')['details'] if p['serialNumber'] == oem['serialNumber'])
        for key in ('userId', 'serialNumber', 'name', 'type', 'parentUserId', 'moduleManaged'):
            assert current[key] == oem[key], 'OEM profile metadata changed'
        self.record('native-remove-preserves-owner-sibling-oem-profile')
        native = self.d.core('clone', FIXTURE, external['targetUserId'])['details']['clone']
        assert native['launcherMode'] == 'NATIVE' and native['nativeEvidence']['entryCount'] == 1
        assert 'proxyPackage' not in native
        assert not self.d.shell('pm', 'list', 'packages', '-u', PREFIX+native['id'])
        self.tap_all([native, managed])
        self.d.core('reconcile')
        self.catalog(3)
        self.record('fresh-oem-clone-native-only-exact-tap-no-proxy')
        self.d.core('clone-remove', FIXTURE, external['targetUserId'])
        self.tap_all([managed])
        # Exercise proxy removal and a fresh ABSENT -> PROXY creation too.
        self.d.core('clone-remove', FIXTURE, managed['targetUserId'])
        self.tap_all([])
        assert not self.d.shell('pm', 'list', 'packages', '-u', managed['proxyPackage'])
        recreated = self.d.core('clone', FIXTURE, managed['targetUserId'])['details']['clone']
        assert recreated['launcherMode'] == 'PROXY' and recreated['id'] == managed['id']
        self.tap_all([recreated])
        self.record('proxy-remove-and-fresh-clone-no-native-duplicate')
        self.d.core('profile-delete', managed['targetUserId'])
        self.tap_all([])
        assert not self.d.shell('pm', 'list', 'packages', '-u', managed['proxyPackage'])
        remaining = self.d.core('profiles')['details']
        assert {p['serialNumber'] for p in remaining} == set(self.snapshot['baselineSerials'])
        assert not self.d.core('clones')['details']
        assert self.evidence(self.snapshot['ownerDataDir'])['marker'] == self.snapshot['ownerMarker']
        self.record('delete-managed-profile-cleans-entries-preserves-baseline')
        self.d.shell('pm', 'uninstall', FIXTURE)
        assert not self.d.shell('pm', 'list', 'packages', '-u', FIXTURE)
        self.record('disposable-fixture-cleanup', status=self.d.core('status')['details']['description'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--run', action='store_true')
    parser.add_argument('--phase', choices=['capture', 'inspect', 'reboot-check', 'cleanup'], required=True)
    parser.add_argument('--snapshot', default='build/release-device-snapshot.json')
    args = parser.parse_args()
    if not args.run:
        raise SystemExit('Review fixture identities and the selected phase before passing --run.')
    d = Device(args.serial, MODULE)
    path = ROOT / args.snapshot
    if args.phase == 'capture':
        assert not path.exists(), 'Snapshot already exists; preserve previous evidence.'
        rows = d.core('clones')['details']
        assert len(rows) == 2 and all(c['packageName'] == FIXTURE for c in rows)
        assert {c['profileClassification'] for c in rows} == {'EXTERNAL_OEM', 'MODULE_MANAGED'}
        profiles = d.core('profiles')['details']
        assert len([p for p in profiles if p['moduleManaged']]) == 1
        snapshot = dict(clones=rows, baselineSerials=[p['serialNumber'] for p in profiles if not p['moduleManaged']],
                        ownerUid=rows[0]['packageVerification']['sourceUid'], ownerDataDir=rows[0]['packageVerification']['sourceDataDir'],
                        bootId=d.shell('cat', '/proc/sys/kernel/random/boot_id'))
    else:
        snapshot = json.loads(path.read_text(encoding='utf-8'))
    gate = Gate(d, snapshot)
    expected = hashlib.sha256((ROOT/'module/lib/core.jar').read_bytes()).hexdigest()
    assert d.shell('sha256sum', MODULE+'/lib/core.jar').split()[0] == expected, 'Installed core differs from local build'
    try:
        if args.phase == 'capture':
            proofs = gate.inspect()
            snapshot.update(sandboxes=proofs, ownerMarker=next(p['marker'] for p in proofs if p['uid'] == snapshot['ownerUid']), coreSha256=expected)
            path.write_text(json.dumps(snapshot, indent=2), encoding='utf-8')
        else:
            getattr(gate, args.phase.replace('-', '_'))()
    finally:
        output = ROOT / ('build/release-device-' + args.phase + '.json')
        output.write_text(json.dumps(dict(coreSha256=expected, events=gate.events), indent=2), encoding='utf-8')
        print('Evidence:', output, flush=True)


if __name__ == '__main__':
    main()
