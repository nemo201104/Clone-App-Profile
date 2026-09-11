# Clone App Profile

KernelSU-Next module by **nemo201104**, v1.0.0. Manage Android `profile.CLONE`
profiles and app instances with a shared CLI/WebUI, private registry and persistent
launcher entries. Target Android 12–17 (API 31–37); every ROM must pass runtime
capability checks. This repository supplies an installable build and tests, not
certification for all Android/OEM combinations.

**A clone is complete only when its package, separate sandbox, target launcher
activity and parent launcher entry are ready.** An installed package with failed
launcher integration returns `PARTIAL_SUCCESS_LAUNCHER_INTEGRATION_FAILED` and
`success: false`. Retry launcher integration from My Clones.

The launcher fallback is a small, separately signed proxy package per
package/profile identity. It displays the app icon with a profile-number badge and a profile-specific label
in the parent's launcher and opens the exact target instance through an
authenticated broker. No root grant is requested for the proxy. Profile serials
prevent recycled user IDs from routing to another clone. There is no fixed clone
user ID and no fallback to a secondary full user.

## Requirements and installation

* KernelSU-Next with root and WebUI support; Android API 31–37.
* A ROM that actually supports `android.os.usertype.profile.CLONE` and has spare
  runtime user/profile capacity. Multiple siblings are supported when creation is
  permitted by that framework.
* A selected parent HOME launcher and framework/bootstrap/PackageManager APIs
  exposed to the root module. SELinux remains enforcing; blocked broker IPC is an
  explicit launcher failure.

Build or download `Clone-App-Profile-v1.0.0.zip`, install it in KernelSU-Next's
module page, then reboot. Open the module WebUI, inspect Doctor and Create
Profile. Select a user app and a target clone profile. Clone App reports package
and launcher results separately. Installation never creates profiles or clones.

Normal proxy packages give launcher app entries. Home screen cell placement,
hidden-app settings and app drawer presentation belong to the launcher. See
[compatibility](docs/COMPATIBILITY.md) and the device acceptance matrix before
relying on an untested launcher. Screenshots: reserved for verified device/WebUI
captures; no illustrative screenshots are presented as device evidence.

Verified on the connected Android 16/API 36 REDMAGIC test device: actual
KernelSU-Next installation/WebUI, two sibling clone entries, real icon taps to
separate sandboxes, reboot, launcher restart/cache and scoped cleanup passed.
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

```sh
python tools/build.py
python tools/check.py
python tools/build_fixture.py  # optional rooted-device test fixture
```

Output: `dist/Clone-App-Profile-v1.0.0.zip` plus SHA-256 and `update.json`.
ZIP entry ordering/timestamps/modes are deterministic for a given toolchain.
Build does not fetch an SDK, publish anything or contain a release signing key.
Each device generates its own private proxy signing key on first use. The
checker downloads a checksum-pinned host-only JSON library from Maven Central.

GitHub Actions validates and builds artifacts for pushes/PRs. Publishing is a
separate manually dispatched workflow, disabled unless its publish input is
selected. Updates use KernelSU-Next's `updateJson` contract and integer
`versionCode`. Until the first release is actually published, Check Update
reports metadata unavailable. No GitHub token is embedded.

## Data, recovery and uninstall

Registry, operation journal, logs, per-device signing key and generated APKs live
in `/data/adb/clone_app_profile` (root-only). Back up this directory privately;
**never share `signing.json`**. Registry corruption fails closed. See
[recovery](docs/RECOVERY.md) before restoring a validated backup or resolving an
interrupted operation. User-data sandbox isolation follows Android's per-user
storage; a ROM may still share contacts/media according to its clone policies.

Remove Cloned and Delete Profile are destructive and show confirmation in WebUI.
They remove managed launcher proxies before removing the corresponding data.
Uninstalling or disabling the module preserves profiles, app data, proxy packages
and registry. Proxy launches then fail closed. For complete cleanup, remove your
clones/profiles through WebUI **before** uninstalling; reinstalling restores the
management path. Updates preserve the registry and signing key.

Logs export to `/sdcard/Cloned-App-Profile/Logs/log-<Date>-Clone-App-Profile.txt`.
Only logs go to public storage. No arbitrary shell API is exposed by the broker.

See [architecture](docs/ARCHITECTURE.md), [testing](docs/TESTING.md) and
[compatibility](docs/COMPATIBILITY.md). Support: [nemo.github.io](https://nemo.github.io).
License: Apache-2.0; Android apksig attribution is included in the built module.
