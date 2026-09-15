# WildKSU clone investigation

Baseline: `885e67a2fb06ce8e9927f419043dc3c06578dc3a`, v1.0.1 / 10001.
Investigation: 2026-09-14 to 2026-09-15 (Asia/Bangkok; evidence timestamps use UTC).
The investigation below predates publication. The accepted patch is promoted
to v1.0.2 because the existing v1.0.1 tag/release is preserved.

## ROOT CAUSE

WildKSU's missing proxy-to-broker socket permission blocked clone launching;
reused failed proxy Activities blocked Retry, and redundant starts of unlocked
sibling profiles recreated this ROM's foreground WebUI.

## EVIDENCE

Classification **E (SELinux), at L (broker IPC)**: the generated targetSdk 35
proxy runs in `untrusted_app`. WildKSU v3.1.2 does not supply the
`untrusted_app -> su:unix_stream_socket connectto` permission that the module's
root broker requires. KernelSU-Next supplies a broader domain-to-su socket grant.
Package installation, sandbox creation, launcher resolution, proxy APK signing
and installation all succeeded before the connection failed.

Actual command: `capctl clone io.github.nemo.cap.fixture 13`.
Operation `6174c865-d8ab-4c2c-bd56-491dd07fbfba` returned exit **2**, JSON
`PARTIAL_SUCCESS_LAUNCHER_INTEGRATION_FAILED`, `packageCloned=Success`,
`launcherMode=PROXY`, `launcherEntryState=FAILED`. The installed proxy displayed
`Permission denied`. Parent serial was 0; target serial was 1014. The fixture
had UID 10562 in its parent and 1310562 in the target, with distinct data paths.

Relevant kernel audit record (UTC 09:22:51.817):

```text
avc: denied { connectto } for comm="clone-launch"
path=00636C6F6E655F6170705F70726F66696C652E62726F6B65722E7631
scontext=u:r:untrusted_app:s0:c51,c258,c512,c768
tcontext=u:r:priv_app:s0:c512,c768
tclass=unix_stream_socket permissive=0
```

The path decodes to the abstract socket `clone_app_profile.broker.v1`.
The audit line omits a PID; the contemporaneous process snapshot identifies the
proxy as PID 2899 and the broker as PID 6634. `/proc/6634/attr/current` is
`u:r:su:s0`. WildKSU's enabled AVC spoof feature replaces **audit output** for
the target su SID with priv_app; adding a rule for priv_app would target the
wrong domain. No spoof setting was changed.

One exact live `allow untrusted_app su unix_stream_socket connectto` restored
proxy authentication. No socket read/write, file, Binder, wildcard or permissive
grant was needed. An ordinary fixture app could then connect but received
`UNAUTHORIZED` from the unchanged broker, proving that transport permission
does not bypass package/UID/certificate authentication.

A second, directly blocking defect was reproduced: a failed proxy dialog left
on top could receive a subsequent `am start` without `onCreate`; the new probe
nonce was never transmitted. Restarting the verified proxy for a probe fixes
Retry without restarting the target app. The controlled regression denied the
one socket permission, observed exit 2, restored it, and successfully retried
with the old error dialog still open, operation
`61f99acd-c550-4593-8f34-049b271282d4`.

A third blocker appeared in the real WebUI acceptance run with two siblings.
`AndroidPlatform.startProfile` unconditionally called `am start-user -w`, even
for a profile already running and unlocked. With two additional CLONE siblings,
this ROM changes global resource configuration when those redundant calls
alternate between siblings. The foreground WildKSU WebUI Activity is recreated,
losing form state and the in-memory operation callback. Periodic reconciliation
therefore interrupted the UI about every 49 seconds, although clone installation
and the broker continued to succeed.

The isolated test temporarily suspended the module service, ran only
`am start-user -w 10`, then `11`, then `10`, and resumed the service in `finally`.
Both profiles were already `RUNNING_UNLOCKED` (serials 1018 and 1019).
At 22:17:49Z, `dumpsys activity activities` showed assetsSeq 25. The commands
advanced it to 26, 27 and 28 and replaced the foreground WebUI window each time;
the manager PID remained 24867. The user controller showed all four users
running and `mMaxRunningUsers=4`, excluding capacity eviction. No package install,
proxy probe or UI hierarchy dump ran between these configuration captures.

Earlier tests with only one additional sibling did **not** reproduce the reset.
An initial apparent correlation with `uiautomator dump` was rejected by isolated
controls. The fix checks the live user/serial first and returns if it is already
running and unlocked. Stopped/locked profiles retain the existing start and
bounded readiness checks. This is based on live state, without ROM/user-number
conditions or changes to the Manager.

## Call graph and assumptions

```text
app.js: operate / clone-submit
  -> bridge.js: command / call (allowlist, ksu.exec callback, exit 2 preserved)
  -> module/bin/capctl (CLASSPATH, app_process)
  -> Core.main / execute
     -> AndroidPlatform profiles / identity / support
     -> Core.cloneApp: validate parent+serial, refuse untracked package,
        persist pending journal, install-existing --user, enable, verifyClone
     -> LauncherIntegration.ensure: validate ownership; inspect HOME;
        GenericAndroidAdapter or isolated ZteAdapter native visibility
        -> NATIVE: verified live HOME model + profile serial mapping
        -> PROXY: ProxyApk.build / BinaryXml / APK v2 signature
           -> pm install --user parent from pipe
           -> LauncherApps enumeration
           -> fresh EntryActivity PROBE with nonce
           -> abstract LocalSocket / Binder-provided package identity
           -> Broker.authenticate / nonce acknowledgement
     -> Store atomic registry commit / Core.cloneResult / JSON + exit code
icon tap -> EntryActivity LAUNCH -> Broker.authenticate -> live serial and
package ownership checks -> am start --user target -> target sandbox
service.sh -> Daemon.run -> Broker thread + periodic Core.reconcile
```

Relevant source locations:

- `Core.java`: main 317, execute 269, create 79, cloneApp 129, cloneResult 166,
  removeClone 171, deleteProfile 207, reconcile 230, managedProfile 50,
  managedClone 60, noPending 71.
- `AndroidPlatform.java`: bootstrap 21, identity 68, support 80, requireAm 96,
  startProfile 100, verifyClone 133, launcherQuery 153, icon 169, runInput 192.
- `LauncherIntegration.java`: validateTarget 56, validatePackageOwnership 62,
  verifyProxy 68, ensure 74, probe restart 137, cleanup 159.
- `Broker.java`: authenticate 26, serve 43, handle 53. `EntryActivity.java`:
  onCreate 13, socket connect 21. `Store.java`: validate 51, save 68, atomic 73.

The installer requires `KSU=true`; WildKSU actually supplied it. `/data/adb/ksu`
is used for a status indicator, not to select clone logic. The runtime depends
on UID 0, framework bootstrap, app_process, pm/am/dumpsys and standard module
paths. `service.sh` uses the manager's shell utilities, boot-completed wait,
disable/remove flags, and a file-locked single daemon. There is `skip_mount`
and no system overlay payload or post-fs-data script. The patch uses standard
module `sepolicy.rule` loading. Next-specific wording remains in a few error
messages; it does not gate a supported WildKSU bridge. No manager-name branch
or hardcoded user/UID was introduced.

## KERNELSU-NEXT VS WILDKSU DELTA

Device: NX769J, Android 16/API 36, kernel 6.1.145-android14-11,
WildKSU manager Spoofed-v3.1.2 / 33208, kernel interface 33208, ksud 3.1.2,
mount mode **Meta**, root and module service domain **su**, SELinux **Enforcing**.
The installed ksud SHA-256
`a8568b9e30cb095598c220705829aaf564f145065ed2865ffdf7e587ad62df89`
matches `lib/arm64-v8a/libksud.so` inside the official spoofed 3.1.2 APK.

Upstream inspected at pinned revisions:

- [WildKSU v3.1.2 rules](https://github.com/WildKernels/Wild_KSU/blob/95d59f6154b1d348adccf30c9bc0730593070bb4/kernel/selinux/rules.c):
  its default patch lacks the socket connect grant.
- [WildKSU audit spoof implementation](https://github.com/WildKernels/Wild_KSU/blob/95d59f6154b1d348adccf30c9bc0730593070bb4/kernel/extras.c#L83):
  rewrites the audited su target SID.
- [Next rules](https://github.com/KernelSU-Next/KernelSU-Next/blob/f9a69951d3b6db7f0904688da08658da646b2368/kernel/selinux/rules.c#L139):
  default domain-to-su socket read/write/connectto/getopt/getattr grants.
- [WildKSU module policy loader](https://github.com/WildKernels/Wild_KSU/blob/95d59f6154b1d348adccf30c9bc0730593070bb4/userspace/ksud/src/module.rs#L154)
  and `init_event.rs`: load active modules' sepolicy.rule before module services.
  Next uses the same lifecycle contract. Wild's 3.1.2 sepolicy ABI compatibility
  fallback worked on-device; no custom ioctl or manager patch was necessary.
- [WildKSU WebViewInterface](https://github.com/WildKernels/Wild_KSU/blob/95d59f6154b1d348adccf30c9bc0730593070bb4/manager/app/src/main/java/com/rifsxd/ksunext/ui/webui/WebViewInterface.kt#L79):
  compatible three-argument exec callback, separate stdout/stderr and exit code.
  `KsuCli.kt` uses its embedded ksud debug su with global mount namespace.

The measured compatibility delta is the socket permission, not mount mode,
package installation, profile capacity, root UID, signing, or registry format.
Policy is declarative and idempotent on either manager; proxy probing verifies
the real capability and continues to fail closed if policy loading is unavailable.

## Timeline

The following combines captured command timestamps, registry fields and bounded
event records for the same operation. Internal phases without a timestamp are
source-order observations, not invented instrumentation timestamps.

| Stage | Evidence |
| --- | --- |
| T0 request | 09:22:37.982Z, captured capctl invocation |
| T1 validation | Core.cloneApp / noPending / source package validation |
| T2 profile lookup | user 13, serial 1014, parent 0/serial 0 |
| T3 package operation | 09:22:39.097Z clonedAt; distinct target UID/dataDir verified |
| T4 launcher integration | ensure after package registry commit |
| T5 decision | 09:22:49.331Z UNASSIGNED -> PROXY; native absence established |
| T6 registry commit | FAILED with target identity and proxy identity retained |
| T7 response | 09:23:04.035Z CLONE_APP partial; host captured exit 2 |

## PATCH

- `module/sepolicy.rule`: one source type, target type, class and permission.
- `LauncherIntegration.java`: require advertised `am -S`, restart only the
  signature-verified module proxy in its registered parent for each probe.
- `AndroidPlatform.java`: avoid redundant `start-user` after validating the live
  identity and observing that the user is already running and unlocked.
- Host/bridge tests, isolated Android negative tests, fixture broker test and
  artifact checks cover the regression. No proxy APK code, clone ownership,
  registry schema or WebUI feature changed.

Supporting changed files: `tests/HostTests.java`, `tests/bridge.test.cjs`,
`tests/DeviceReadOnlyChecks.java`, `tests/fixture/AndroidManifest.xml`,
`tests/fixture/src/io/github/nemo/cap/fixture/BrokerProbeActivity.java`,
`tools/check.py`, `tools/release_check.py`, and this report.

Critical changes, abbreviated from the actual diff:

```diff
+allow untrusted_app su unix_stream_socket connectto

-identity(id,serial); requireAm("start-user");
+JSONObject current=identity(id,serial);
+if(current.getBoolean("running") && current.getBoolean("unlocked"))return;
+requireAm("start-user");

+platform.requireAm("-S");
-AndroidPlatform.run(20,"/system/bin/am","start","--user", ...);
+AndroidPlatform.run(20,"/system/bin/am","start","-S","--user", ...);
```

Device-reviewed candidate: `dist/Clone-App-Profile-v1.0.1.zip`, **1,680,449 bytes**, SHA-256:

```text
5500fa4f1ffda665f563626127c3bd6a48bbab808879ec3d1d151e8c672ba876
```

Compared with the saved v1.0.1 baseline, ZIP contents differ only in `lib/core.jar`
and the added `sepolicy.rule`. The baseline ZIP and tracked source archive are
retained under ignored `.cache/wildksu-investigation/rollback/`. The intermediate
policy/probe candidate (`5bdcce57...`) is also preserved there. No private
signing key was displayed or copied. The immutable v1.0.0 release was untouched.

## DEVICE TEST RESULT

Final installed artifact passed acceptance and cleanup on 2026-09-15.
Evidence names below refer to local files under ignored
`.cache/wildksu-investigation/`; raw captures and screenshots are not ZIP entries.

| Test | Before | After | Evidence |
| --- | --- | --- | --- |
| Doctor | Ready backend did not establish proxy transport | VALID registry, READY broker, no pending operation | `round3-after-doctor.json`; final operation `0c0938e8-2394-465b-8750-3a8bb9bcb9fe` |
| Profile detection | Framework exposed CLONE and two extra siblings | Correct live IDs/serials; original OEM profile remains external | `round3-after-profiles.json`; `completion-profiles.json` |
| Clone | Exit 2; launcher integration failed | CLI and actual WebUI return SUCCESS / READY_PROXY | `completion-sibling-clone.json`; WebUI operation `ff744aad-b905-4335-92d3-5e25f628d8f9` |
| Package cross-user state | Target installation and separate data already succeeded | Owner + two siblings retain distinct UIDs and sandbox markers; no fixture in unrelated OEM profile | `round3-e2e-snapshot.json`; `round3-after-taps.json` |
| Launcher integration | Proxy icon opened Permission denied | Three actual icons route to exactly three users, with unchanged sandbox markers | `round3-after-taps.json`; `completion-webui-clone.png` |
| Retry | Failed Activity reused without transmitting new nonce | Old error dialog retry passes; actual WebUI Retry also succeeds | Controlled operation `61f99acd-c550-4593-8f34-049b271282d4`; WebUI operation `5d5b8ca9-eb5b-4c13-ba67-da3e83607e79` |
| Stopped profile | Start path required | Stopped fixture profile is started and unlocked; same serial retained | `final-stopped-profile-retry.json`, operation `5abb038f-5325-4e96-8c53-6edba25c658c` |
| Reboot persistence | Baseline lacked required transport permission | Both final-candidate clone identities, data markers and icon routing survive reboot | `round3-e2e-snapshot.json`; `round3-after-taps.json` |
| Launcher restart / target relaunch | Original transport denied | Actual icon taps pass after HOME restart + reconcile and target force-stop | `round3-after-restart-launcher-taps.json`; `round3-after-stop-relaunch-taps.json` |
| Reconcile / WebUI stability | Redundant start-user calls increment assetsSeq and recreate WebUI | Two explicit reconciles preserve assetsSeq 13, the same window, and the successful Clone result | `completion-ui-stability.json`; `completion-ui-stable.png` |
| WebUI flow | Two siblings could reset the form/result | Doctor, profile/app listing, Clone, Cloned Apps, Retry and Remove exercised in installed WildKSU Manager | `final-webui-doctor.png`; `completion-webui-clone.png`; `final-webui-retry.png`; `final-webui-remove.png` |
| Clone remove | Not assumed from installation success | First fixture target and its proxy removed; owner and second clone icons still route correctly | Operation `0fb418a3-5267-4865-8416-24747d607146`; `completion-remove-proof.json` |
| Profile cleanup | Controlled test profiles existed | Both test profiles removed; deleting the second also removes its managed proxy; zero fixture icons after owner fixture uninstall | `completion-cleanup-proof.json`; delete operations `2831d389-87a9-4c17-92dd-735903f007be` and `5658b5b7-9977-48fe-b939-51144a3bf875` |
| Existing instances | Five personal native clones on resume | All five retain ID, user/serial, installation identity, certificate and native launcher state | `resumed-clones.json`; `round3-completion-delete-completion-final-clones.json` |
| SELinux / broker authorization | Enforcing; transport denied before auth | Enforcing; registered proxy works, ordinary fixture caller gets UNAUTHORIZED | `round3-completion-delete-final-broker-negative.json`; `round3-completion-delete-completion-final-selinux.json` |

Reboot acceptance used profile serials 1018/1019 and boot ID
`4b202d9f-467b-4119-ae46-9f0b18a72115`. The later UI/cleanup run used freshly
created serials 1020/1021, after prior fixtures had been removed during a session
pause. It did not substitute a fresh clone for a reboot-persistence check.

Final state: **Profile: 2/4 | Cloned: 5**, no module-managed fixture profiles,
no pending journal entries, no fixture packages/proxies, and broker READY.
The five existing personal native clones and the external OEM profile were
preserved. The actual WebUI backend process was observed with UID 0/domain su,
matching direct root CLI execution. UI operation IDs above were independently
confirmed in backend event records; a visible Success heading alone was not
used to infer command completion.

The installed core, policy and proxy SHA-256 values match the final ZIP
(`completion-installed-hashes.json`). The ZIP and its sidecar still match
`5500fa4f...72ba876` after acceptance and cleanup. At that validation checkpoint HEAD was
`885e67a` and the patch was local and uncommitted. Publication checks for
v1.0.2 are recorded separately below.

## REGRESSION STATUS

Host: 50 checks passed. Bridge: 7/7 passed. Browser: all six combinations of
320/390/960px and light/dark passed. Artifact structural/integrity check passed.
Android class tests: 10/10 passed against both the staged and installed final core
(isolated storage;
no live registry corruption or profile identity modification), including the
already-running path with the start capability deliberately unavailable and
serial rejection before that path can return.

Negative checks cover missing bridge/binary/capability, framework permission
propagation, SELinux partial success, contradictory exit status, invalid
registry, interrupted operation/reconcile without adoption, recycled serial,
retry and idempotency. The common Next-compatible bridge is exercised by host
mocks; the installed WildKSU bridge is exercised on the actual device.
No manager-name branch was introduced or used as a substitute for capability
validation. All final required checks passed; zero final test failures remain.

## REMAINING RISKS

This is a WildKSU device validation. Next's common policy/bridge/lifecycle
contract was inspected, but a new physical Next test is not claimed.
The one SELinux permission applies to the `untrusted_app` domain, not individual
package names; the broker's mandatory peer UID/certificate checks remain the
package-level authorization boundary. No generic root shell endpoint exists.

Initial fixtures in users 13/14 were subsequently removed by explicit
Remove/Delete operations while this session was paused. They were not treated
as a reboot-persistence failure or silently adopted again. A fresh controlled
run uses new serials and preserves the five personal clones present on resume.

The second fixture pair (serials 1016/1017) passed actual icon taps before/after
reboot, launcher restart and target force-stop before explicit Delete Profile
events at 15:07:26Z and 15:07:35Z removed them while the session was paused.
They were not silently lost on reboot. Final reboot acceptance used serials
1018/1019; final WebUI and cleanup used serials 1020/1021.

## v1.0.2 release verification

The existing v1.0.1 release/tag stays at `885e67a`; its published ZIP has SHA-256
`c44ecb99ba659ceca50d95548e5fb901d9324f2c713d4ae107ebf32a33feffa0`.
The WildKSU candidate documented above is promoted to v1.0.2 / 10002 with
version metadata changes only beyond the accepted fixes.

Release artifact: `dist/Clone-App-Profile-v1.0.2.zip`, **1,680,458 bytes**.
SHA-256:

```text
abf0733486adbafcfe0bc566089f1cb9356abeabfd028d26f96816836dc765f4
```

Compared with the device-reviewed candidate `5500fa4f...72ba876`, only
`module.prop`, `webroot/index.html` and `lib/core.jar` change. Of 16 core
classes, 15 remain byte-identical. The remaining `Core.class` disassembly is
identical after normalizing `v1.0.2`/10002 to `v1.0.1`/10001; no method logic
changed. Proxy APK, SELinux policy, service, launcher and broker logic retain
the validated bytes/behavior.

Fresh checks for v1.0.2 passed: 50 host checks, 7 bridge tests, six browser
configurations, ZIP integrity/structure, and 10 isolated on-device tests against
the staged v1.0.2 core. The full reboot/launcher/WebUI acceptance above belongs
to the identical-logic candidate; a second full installation/reboot run for the
version-only promotion is not claimed. Dedicated KernelSU-Next device
revalidation remains pending.

## VALIDATION COMMANDS

```powershell
python tools/build.py
python tools/check.py
node tests/webui.browser.cjs
python tools/release_check.py --sha256 abf0733486adbafcfe0bc566089f1cb9356abeabfd028d26f96816836dc765f4
git diff --check
adb shell su -c '/data/adb/modules/clone_app_profile/bin/capctl doctor'
adb shell su -c '/data/adb/modules/clone_app_profile/bin/capctl status'
adb shell su -c '/data/adb/modules/clone_app_profile/bin/capctl profiles'
adb shell su -c '/data/adb/modules/clone_app_profile/bin/capctl clones'
adb shell su -c 'getenforce'
```

Use a newly created module-owned fixture profile for mutation tests and retain
its returned serial. Do not reuse the numeric test IDs from this report after
cleanup. Real WebUI checks use the installed Manager's bridge and actual input;
browser mocks alone do not establish a device pass.
