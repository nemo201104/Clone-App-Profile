# Clone App Profile

KernelSU module by **nemoforge**, v1.0.2. Manage Android `profile.CLONE`
profiles and app instances with a shared CLI/WebUI, private registry and persistent
launcher entries. Target Android 12–17 (API 31–37); every ROM must pass runtime
capability checks. This repository supplies an installable build and tests, not
certification for all Android/OEM combinations.

The WebUI uses English copy, the repository's logo and primary color `#0E60E2`
in light and dark modes. `assets/` is the branding source of truth. The build
validates both images and encodes `assets/icon.png` into `webroot/icon.png` for
the WebUI and KernelSU-Next's `webuiIcon`. The supplied icon has JPEG bytes despite
its filename; conversion preserves every decoded pixel and leaves the source
unchanged. Proxy icons continue to use each cloned app's artwork/profile badge.
The build copies `assets/banner.png` byte-for-byte into `webroot/banner.png`.
KernelSU-Next reads `banner=webroot/banner.png` from the local module directory,
so the module card does not need Internet access for its banner. The source
contract, device checks and migration limits are recorded in the
[maintenance report](docs/MAINTENANCE_V1.0.1.md). The earlier
[branding validation](docs/BRANDING.md) is historical evidence.

WildKSU device validation for the compatibility patch passed. KernelSU-Next
compatibility was source-reviewed; dedicated device revalidation is pending.
See the [WildKSU validation report](docs/WILDKSU-VALIDATION.md).

**A clone is complete only when its package, separate sandbox, target launcher
activity and parent launcher entry are ready.** An installed package with failed
launcher integration returns `PARTIAL_SUCCESS_LAUNCHER_INTEGRATION_FAILED` and
`success: false`. Retry launcher integration from My Clones.

Launcher integration is native-first: a verified native entry records `NATIVE`
and removes any owned proxy for that identity. Only verified native absence may
select `PROXY`; ambiguous visibility returns partial success. Profiles are
classified from runtime metadata and ownership as `MODULE_MANAGED`, `EXTERNAL_OEM`
or `EXTERNAL_GENERIC`. Selecting an external profile owns only the newly cloned
app; it never makes that profile deletable by this module.

The launcher fallback is a small, separately signed proxy package per
package/profile identity. It displays the app icon with a profile-number badge and a profile-specific label
in the parent's launcher and opens the exact target instance through an
authenticated broker. No root grant is requested for the proxy. Profile serials
prevent recycled user IDs from routing to another clone. There is no fixed clone
user ID and no fallback to a secondary full user.

## Requirements and installation

* KernelSU-Next or WildKSU with root and WebUI support; Android API 31–37.
* A ROM that actually supports `android.os.usertype.profile.CLONE` and has spare
  runtime user/profile capacity. Multiple siblings are supported when creation is
  permitted by that framework.
* A selected parent HOME launcher and framework/bootstrap/PackageManager APIs
  exposed to the root module. SELinux remains enforcing; blocked broker IPC is an
  explicit launcher failure.

Build or download `Clone-App-Profile-v1.0.2.zip`, install it in your root manager's
module page, then reboot. Open the module WebUI, inspect Doctor and Create
Profile. Select a user app and a target clone profile. Clone App reports package
and launcher results separately. Installation never creates profiles or clones.

Normal proxy packages give launcher app entries. Home screen cell placement,
hidden-app settings and app drawer presentation belong to the launcher. See
[compatibility](docs/COMPATIBILITY.md) and the device acceptance matrix before
relying on an untested launcher. Screenshots: reserved for verified device/WebUI
captures; no illustrative screenshots are presented as device evidence.

Verified platform: **NX769J, Android 16/API 36, RedMagicOS 11.0.8MR6,
`com.zte.mifavor.launcher` versionCode 160000**. Earlier A–G testing covered two
managed siblings on this device. The native-first release gate separately checks
owner + OEM native clone + managed proxy coexistence. Other Android versions and
launchers remain unverified; the inspected private OEM visibility contract is
restricted to a reviewed launcher APK digest. An unknown contract fails explicitly.
See the [execution record](docs/TESTING.md) and
[implementation report](docs/IMPLEMENTATION_REPORT.md) for the limits of this result.

## CLI

Run as root, or use the WebUI which calls the same backend:

```sh
/data/adb/modules/clone_app_profile/bin/capctl doctor
/data/adb/modules/clone_app_profile/bin/capctl status
/data/adb/modules/clone_app_profile/bin/capctl profiles
/data/adb/modules/clone_app_profile/bin/capctl profile-create
/data/adb/modules/clone_app_profile/bin/capctl apps
/data/adb/modules/clone_app_profile/bin/capctl clone com.example.app <profile-id>
/data/adb/modules/clone_app_profile/bin/capctl clones
/data/adb/modules/clone_app_profile/bin/capctl launcher-retry com.example.app <profile-id>
/data/adb/modules/clone_app_profile/bin/capctl reconcile
/data/adb/modules/clone_app_profile/bin/capctl clone-remove com.example.app <profile-id>
/data/adb/modules/clone_app_profile/bin/capctl profile-delete <profile-id>
/data/adb/modules/clone_app_profile/bin/capctl logs
/data/adb/modules/clone_app_profile/bin/capctl export-log
/data/adb/modules/clone_app_profile/bin/capctl update-check
```

Exit status: `0` complete, `2` launcher partial success, `1` failure. Every normal
command returns JSON with `success`, `errorCode`, `message`, `details`,
`operationId`. Profile deletion requires recorded module ownership, serial, type
and parent validation. Clone removal affects only the selected target user.
Untracked packages are never adopted automatically.

## Build, checks and updates

Install JDK 17, Android SDK platform 35 and build-tools 36.0.0, Python 3.10+,
Node.js 20+ and bash. Set `JAVA_HOME` and `ANDROID_HOME`.
The build rejects other Java major versions to avoid changing accepted binaries
through an unnoticed compiler change. Host ImageIO validates/encodes branding;
no new image-processing dependency or substitute artwork is downloaded.

```sh
python tools/build.py
python tools/check.py
python tools/build_fixture.py  # optional rooted-device test fixture
```

Output: `dist/Clone-App-Profile-v1.0.2.zip` plus SHA-256 and `update.json`.
ZIP entry ordering/timestamps/modes are deterministic for a given toolchain.
Build does not fetch an SDK, publish anything or contain a release signing key.
Each device generates its own private proxy signing key on first use. The
checker downloads a checksum-pinned host-only JSON library from Maven Central.

GitHub Actions validates and builds artifacts for pushes/PRs. Publishing is a
separate manually dispatched workflow, disabled unless its publish input is
selected and its rebuilt ZIP matches the supplied device-reviewed SHA-256.
Updates use KernelSU-Next's `updateJson` contract and integer `versionCode` at
`https://github.com/nemoforge/Clone-App-Profile/releases/latest/download/update.json`.
Install v1.0.1 or later manually once when migrating from the released v1.0.0: the old
updater only accepts the former owner's URLs. The new updater accepts the
canonical repository's release and tagged changelog URLs. Before publication,
the public endpoint still serves the previous release; controlled metadata
checks are distinct from the post-publication HTTPS update gate. No GitHub token
is embedded. The immutable previous release and tag are preserved.

## Data, recovery and uninstall

Registry, operation journal, logs, per-device signing key and generated APKs live
in `/data/adb/clone_app_profile` (root-only). Back up this directory privately;
**never share `signing.json`**. Registry corruption fails closed. See
[recovery](docs/RECOVERY.md) before restoring a validated backup or resolving an
interrupted operation. User-data sandbox isolation follows Android's per-user
storage; a ROM may still share contacts/media according to its clone policies.

Remove Cloned and Delete Profile are destructive and show confirmation in WebUI.
They remove managed launcher proxies before removing the corresponding data.
Native entries disappear through per-user PackageManager removal and launcher
refresh; if bounded verification is incomplete, the record remains
`PENDING_REMOVAL` with a partial result. Retry the same removal to finish.
Uninstalling or disabling the module preserves profiles, app data, proxy packages
and registry. Proxy launches then fail closed. OEM native entries remain under
the OEM launcher's control and do not require the broker. For complete cleanup, remove your
clones/profiles through WebUI **before** uninstalling; reinstalling restores the
management path. Updates preserve the registry and signing key.

Logs export to `/sdcard/Cloned-App-Profile/Logs/log-<Date>-Clone-App-Profile.txt`.
Only logs go to public storage. No arbitrary shell API is exposed by the broker.

See [architecture](docs/ARCHITECTURE.md), [testing](docs/TESTING.md) and
[compatibility](docs/COMPATIBILITY.md). Support: [nemoforge.github.io](https://nemoforge.github.io).
License: Apache-2.0; Android apksig attribution is included in the built module.
