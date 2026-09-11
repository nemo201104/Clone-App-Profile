# v1.0.0 final release gate

Date: 2026-09-11. **Pre-publication checks passed; publication authorization and
the mandatory live post-publication update check are still pending.** No tag or
release has been created at this checkpoint. This is not a RELEASED declaration.

## Repository and artifact

Initial repository: clean main, HEAD
`e957086a62087b708f66bd0f0f4e475af59dd2f0`, one commit, no tags or untracked source.
Origin: https://github.com/nemo201104/Clone-App-Profile.git. Remote branch/tag
listing and GitHub Releases were empty at the pre-publication check. Existing
ignored caches, private test material and generated outputs were preserved.

Initial ZIP: 217668 bytes, SHA-256
`f33a8e90ed69df8a46ae6ab5b2b9d28418f665466f6aa47f94c9f6349e7eca40`.

Final reviewed ZIP: `dist/Clone-App-Profile-v1.0.0.zip`, **223607 bytes**.
SHA-256: **`bb6a851436429c992ec426f7239612a3322525ae41f980ddaeaf4fe1b9a07fd5`**.
Installed core SHA-256:
`ffa573521d6afb14b1740371b318df26f0253f1c8d4e39557d275661d13c5e41`.
The ZIP contains exactly 13 reviewed module files. All 11 retained non-metadata
installed files match the ZIP; module.prop fields match except the intentionally
dynamic description. customize.sh is consumed by the installer. update.json
remains version v1.0.0 / integer versionCode 10000.

## Duplicate root cause and correction

The old integration recorded a positive privileged LauncherApps query but always
created a proxy. On the canonical OEM clone profile, MiFavor already rendered a
native entry, so one package/profile received two icons.

The integration now records NATIVE or PROXY, detects exact native identity before
fallback, removes an owned proxy when native appears and never equates UNKNOWN
with absence. Profile classification is MODULE_MANAGED, EXTERNAL_OEM or
EXTERNAL_GENERIC. External targets acquire app ownership only, never profile
ownership. The inspected MiFavor raw AllAppsStore also includes hidden siblings;
the adapter must use its reviewed personal-drawer predicate and join component/
flags to the icon cache's live profile serial. Cache presence alone is insufficient.
No OEM database, clone metadata or system APK is written. See
[compatibility](COMPATIBILITY.md) and the [source audit](RELEASE_AUDIT.md).

NATIVE removal uses per-user PackageManager removal, then bounded native-entry
disappearance verification. Delay retains PENDING_REMOVAL and a partial result.
Reconcile preserves pending removal and cannot recreate it. Unknown native
visibility remains PARTIAL_SUCCESS_LAUNCHER_INTEGRATION_FAILED.

## Final build and device evidence

Verified platform: **NX769J / Android 16 API 36 / RedMagicOS 11.0.8MR6 /
HOME com.zte.mifavor.launcher versionCode 160000**, with the reviewed launcher
APK digest listed in COMPATIBILITY.md. KernelSU-Next ksud 3.3.0 / UAPI 2.

| Gate | Result |
|---|---|
| Clean rebuild, shell/JavaScript/JSON/ZIP validation | PASS |
| 47 Java assertions and 3 bridge tests | PASS |
| Browser light/dark, unsafe labels, native/proxy/classification, partial fields, external-profile deletion exclusion, cancel | PASS |
| Old proxy-to-native migration without changing clone identity | PASS during release fix; owned duplicate removed |
| Fresh OEM clone on final installed ZIP | PASS: NATIVE, one native entry, no registered/global proxy |
| Managed sibling with no visible native entry | PASS: separate PROXY, source icon/profile badge |
| Owner + OEM + managed sibling simultaneously | PASS: exactly three fixture entries; actual icon taps resumed UIDs 10558, 99910558, 1510558 respectively |
| Sandbox isolation | PASS: three distinct persistent markers and directories |
| Three repeated reconciles | PASS: unchanged modes/identities, no duplicates |
| Final ZIP install through actual Next installer, then reboot | PASS: changed kernel boot ID, mappings and sandbox markers retained |
| HOME force-stop/restart | PASS: all three entries and exact taps survived |
| Missing owned proxy reconstruction | PASS: same identity restored; all three actual icon taps still correct |
| Native Remove Cloned | PASS: native icon gone, owner/sibling launchable, OEM user metadata/serial retained |
| External OEM profile deletion rejection | PASS: PROFILE_NOT_MANAGED |
| Proxy Remove Cloned and fresh proxy creation | PASS: scoped disappearance/recreation, correct target tap |
| Delete managed profile | PASS: proxy gone; owner and original OEM profile preserved |
| Final cleanup | PASS: fixture and test profile removed; Profile: 2/4, Cloned: 0; original serials 0 and 11 retained |
| Final actual Next WebUI / counters / logs / Save Log / Support href | PASS; WebUI visually inspected because this Next WebView exposes an empty accessibility tree |
| Registry and boot service | PASS: VALID / READY; installed files equal final ZIP |
| Actual published update endpoint/current/lower/restored device test | PENDING publication; host-only comparison does not satisfy this gate |

Raw evidence is intentionally ignored/private: `build/release-device-capture.json`,
`release-device-reboot-check.json`, `release-device-cleanup.json`,
`release-installed-files.json`, `release-final-smoke.json`, `release-install.txt`
and actual WebUI captures. The snapshot/capture preceded final metadata/UI/error-
reporting edits; reboot-check and cleanup explicitly verify the final core hash
above. Historical A–G with two module-created siblings remains a separate earlier
record in TESTING.md, not a re-labelled test of the newer binary.

## Contract refresh and limits

KernelSU-Next HEAD was rechecked as
`b93da6492492eee898da06828df86f8a3d5354be`. Its WebUI API, update parser,
Markdown-fetching Module screen, installer and module lifecycle were reread.
Android LauncherApps/LauncherActivityInfo, multi-user types and current AOSP pm/
am/LauncherAppsService contracts were rechecked; links are in COMPATIBILITY.md.

Android 12/12L/13/14/15/17, other launchers/ROMs, changed MiFavor APK builds,
arbitrary apps and every crash/race boundary remain **unverified**. Generic public
APIs cannot certify another HOME's positive presentation. Unknown contracts fail
explicitly and may need another reviewed adapter. MiFavor cache-only command
Success from historical A–G is an observed device result, not proof of a generic
cross-version launcher cache/model rebuild. The module itself never clears it.

## Publication and mandatory follow-up

Prepared assets: ZIP above, matching .zip.sha256, update.json; release notes:
CHANGELOG.md. User prompt section 20 requires authorization to push and final
ZIP/hash confirmation before creating the GitHub release. No force-push or
history rewrite is used. Manual CI publication also requires this exact reviewed
SHA-256 and refuses an existing tag.

After publishing v1.0.0, run:

```sh
python tools/device_update_gate.py --serial <serial> --run
```

The installed core must fetch
https://github.com/nemo201104/Clone-App-Profile/releases/latest/download/update.json
successfully, show UP_TO_DATE at 10000, show UPDATE_AVAILABLE with only installed
module.prop temporarily set to 9999, validate version/download/changelog metadata,
and restore 10000/UP_TO_DATE. The script restores metadata in finally and restarts
the supervisor. No lower-version stable artifact is published. Download the
published ZIP independently and compare its SHA-256 before declaring RELEASED.

Prepared release URL:
https://github.com/nemo201104/Clone-App-Profile/releases/tag/v1.0.0.
At this checkpoint it is not yet an existing release.
