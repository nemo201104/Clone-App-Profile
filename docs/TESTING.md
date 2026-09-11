# Tests and acceptance

Host tests do not prove launcher UI behavior. Record Android version, ROM,
resolved HOME/version and actual profile serials for each device run.

## Host / CI

`python tools/build.py && python tools/check.py` checks:

* Java compilation, D8 dexing, POSIX shell syntax and JavaScript syntax.
* WebUI operation allowlist, shell/input injection rejection and partial-success
  propagation (3 Node tests).
* Sibling/parent/package identities, unsafe arguments, 48 concurrent registry
  increments, corrupted-registry rejection and explicit backup recovery.
* Two generated proxy APKs with distinct identities, Unicode manifest label,
  preserved icon, real APK v2 signature verification and persistent signer.
* ZIP root layout, metadata JSON/version contract, expected files, and isolation
  of OEM identifiers from generic Java core (47 Java assertions plus static checks).
* Native model completeness, exact live-serial joins, hidden sibling rows, stale
  cache, ambiguous identities, legacy mode migration and the NATIVE/proxy
  exclusivity invariant; installed-version update comparison and invalid URLs.

The build is repeated when implementation changes. Unit tests use temporary
host storage and cannot alter Android users. Generated APKs/fixture/keys are
excluded from git. `tests/fixture` is a separate permission-free activity that
records its UID, framework-provided data directory, random persisted sandbox
marker and launch counter. It is never included in the module ZIP.

## Device runner

```sh
python tools/build_fixture.py
python tools/device_test.py --serial <adb-serial> --run
```

Review the script first. It refuses an existing installed module, existing fixture
package, nonempty ownership registry or fewer than two spare user slots. It
stages the built module, installs its own fixture in the current parent, creates
two module-owned CLONE profiles and exercises both launch routes. On normal
completion it deletes its profiles, uninstalls the fixture and disables the
staged service. Private module registry/logs/signing key remain available for
inspection. The recorded original users must still have the same serials.

`--keep-on-failure` retains failed resources for debugging.
`--keep-for-visual-check` retains the two working clones, brings HOME forward and
prints their IDs; remove those profiles through capctl after visual checks.
Raw local evidence is `build/device-results.json`, not committed device/private
state. The runner never silently reboots or clears launcher data.

## A–G acceptance matrix

| Test | Required evidence |
|---|---|
| A | Profile 1 package/sandbox validated; parent launcher visibly shows entry; an actual tap produces fixture evidence with profile 1 UID |
| B | Same package in profile 2 has a separate visible entry; tap writes profile 2 UID |
| C | Owner + both clones have three distinct UIDs and persisted sandbox markers; proxy IDs differ |
| D | Remove clone 1; its launcher entry disappears; owner and clone 2 remain installed and launchable |
| E | Delete a module-owned profile; all of its proxy activities disappear; other users retain serials/data |
| F | With the module installed, reboot/unlock parent; service restarts; mapping, labels and three sandbox markers survive; tap both entries |
| G | Restart HOME without clearing user data, and separately rebuild its cache through the launcher's supported mechanism; verify entries or reconciliation restoration and tap again |

The scripted A/B checks directly start the parent proxy MAIN/LAUNCHER component;
they establish routing, not a physical icon tap. Do not mark visual A/B complete
from that result alone. Reboot with a **staged-only** module is not an F test:
install the ZIP through KernelSU-Next so the boot service actually runs.

## Historical A–G execution record, 2026-09-11, before native-first changes

This section records the initial implementation, not a claim that the newer
native-first binary repeated every old test. All device results below apply only
to **NX769J / Android 16 API 36 / RedMagicOS 11.0.8MR6 /
com.zte.mifavor.launcher versionCode 160000**. The release-gate run is recorded
separately in RELEASE_GATE.md.

* Host/static build and 30 core checks + 3 bridge tests passed.
* Headless Chrome: mobile light/dark rendering, separate partial result fields,
  unsafe app-label rendering, CLONE target filtering, ownership filtering and
  destructive-action cancellation passed with a mocked, contract-shaped bridge.
  This is not a substitute for loading the page in the actual Next WebView.
* Rooted API 36 device, resolved HOME `com.zte.mifavor.launcher`, version 160000:
  bootstrap, UserManager metadata/serials, runtime max-user count and clone
  capacity checks passed. Existing OEM profile was never adopted or deleted.
* First full fixture run: two additional siblings, separate proxy packages,
  authenticated launch to two target UIDs, three independent persisted sandbox
  markers, max-capacity rejection, owner deletion rejection, one-clone removal,
  profile deletion and original-user serial preservation passed.
* The first proxy install exposed a real Binder FD/SELinux issue: passing a
  root-private regular file as stdin failed. Sending an anonymous pipe and using
  AOSP's empty-file-list stdin contract resolved it. APK signing and authenticated
  broker transport then passed on this device without SELinux policy changes.
* Installed through the actual KernelSU-Next CLI (`ksud 3.3.0`, UAPI 2), rebooted,
  and opened the module in the installed manager's WebView. Dashboard rendered
  live backend data, including `Profile: 4/4 | Cloned: 2` during the fixture run.
* Final installed backend: A–G passed with `tools/device_verify_installed.py`.
  Owner UID 10540, clone UIDs 1310540 and 1410540 had three distinct persisted
  sandbox markers. Parent launcher showed three entries; actual coordinate taps
  on the two clone icons resumed the expected UID and preserved its marker.
* Reboot changed the kernel boot ID; the installed boot service authenticated
  correctly and both package/profile mappings and sandbox markers survived.
  The verifier compares the installed backend SHA-256 to the built backend.
* HOME's advertised `pm clear --user <parent> --cache-only <home>` succeeded;
  force-stop/restart of HOME preserved entries and exact routing. This did not
  clear launcher user data or home layouts. The reported Success and observed
  MiFavor behavior are not a generic cross-version cache/model rebuild guarantee.
  Removing one managed proxy followed
  by Reconcile recreated the same identity; an actual icon tap worked again.
* Remove Cloned removed only clone 1's entry; clone 2 remained visible and
  launchable. Delete Profile removed the second profile's entry. Both disposable
  profiles and the fixture were then removed. Original owner/OEM user serials
  were preserved; final status was `Profile: 2/4 | Cloned: 0`.
* Negative device cases passed using a separate fresh disposable profile:
  owner/unmanaged deletion rejection, non-CLONE/missing targets, invalid package
  syntax, missing source package, untracked clone/removal rejection and registry
  cleanup after external deletion of that disposable profile. No ownership was
  acquired for the test's manually installed, untracked package.
* Logs returned all required columns; Save Log produced valid data under the
  required `/sdcard/Cloned-App-Profile/Logs/` directory. Check Update returned
  `UPDATE_CHECK_FAILED` with HTTP 404 while the release endpoint was unpublished.
* A runtime icon-rendering failure was caught during development: root
  `app_process` has no default font preload. The final badge uses vector digits.
  The failing build returned launcher partial success and refused proxy launch;
  it was not accepted as a completed clone.
* After A–G, a WebUI-only correction added the four result fields to early clone
  validation failures too. Browser checks passed, the installed WebUI was updated
  and its hash verified. The backend/proxy binaries used for A–G were unchanged.
  Thus the earlier full ZIP hash in raw A–G evidence differs from the final ZIP
  solely because of that WebUI correction.
* A fresh disposable clone was then created to check the vector badge visually.
  Its parent launcher entry showed the source artwork with the target-number
  badge. This fixture/profile was also cleaned up. All 11 retained installed
  module files matched the final ZIP by SHA-256; version/update metadata matched
  too (description is intentionally dynamic, customize.sh is installer-only).

Local raw evidence: `build/acceptance-final.json`, `build/device-fault-results.json`,
`build/ancillary-device-checks.json`, `build/installed-final-files.json` and
`build/device-screenshots/`. These files
are ignored by git. API 31/32/33/34/35/37 device behavior, other launchers, arbitrary
third-party apps and cross-version module upgrades remain untested.

To repeat installed-module tests, first retain the fixture clones from the
staged runner, install the ZIP through Next, record a pre-reboot snapshot, reboot
and unlock. `device_verify_installed.py` requires two matching disposable clones,
their sandbox/owner snapshot and a changed boot ID; it cleans those resources.
`python tools/device_fault_checks.py --serial <serial> --run` exercises the
additional negative cases without adopting existing test packages.

## Additional fault cases for a rooted test device

Verify unsupported type, full capacity, creation/start failure rollback, missing
source package, already-present untracked package, non-CLONE target, externally
deleted profile, ID reuse, external app reinstall, locked parent, blocked socket,
daemon death, concurrent WebUI/CLI actions, signer collision, and failed
PackageManager removal. Force-kill during each journal/commit boundary on a
disposable device. Corrupt/restore a copy of the registry, never production data.
Check partial results and retained ownership/error evidence after every failure.

## Native-first release gate

`tools/device_release_gate.py` requires two explicitly prepared, module-owned
clones of the disposable fixture: one in a selected external OEM profile and one
in a newly module-created CLONE profile. It refuses other packages or unexpected
ownership. It does not create/adopt/delete the OEM profile. Capture validates
the selected resources and stores their identity/sandbox evidence locally.

```sh
python tools/device_release_gate.py --serial <serial> --run --phase capture
# Install the final ZIP through Next, reboot, then unlock the parent.
python tools/device_release_gate.py --serial <serial> --run --phase reboot-check
python tools/device_release_gate.py --serial <serial> --run --phase cleanup
```

The runner checks exactly three fixture entries and actually taps every icon.
Identical owner/OEM labels are distinguished by the resulting UID and sandbox
launch counter, not screen order. It checks no proxy for OEM NATIVE, one distinct
managed PROXY, three idempotent reconciles, reboot markers, launcher restart,
reconstruction of a removed owned proxy, scoped native removal, proxy removal,
fresh fallback creation and profile cleanup. Partial/failed runs remain failed;
raw JSON is stored in ignored `build/release-device-*.json`.

The final artifact gate is `python tools/release_check.py --sha256 <reviewed-hash>`.
It checks the exact reviewed ZIP hash, complete file allowlist, packaged/current
file equality, metadata and absence of private/debug content. Publication must
also be followed by the real device update test documented in RELEASE_GATE.md;
a host fixture or unpublished HTTP 404 is not a successful update test.
