# Compatibility and verified contracts

Research refreshed: 2026-09-11. Implemented API range is 31–37 (Android 12–17),
subject to runtime capability checks. Verified device behavior is limited to
NX769J, Android 16/API 36, RedMagicOS 11.0.8MR6, HOME
`com.zte.mifavor.launcher` versionCode 160000.

## Authoritative sources

* [AOSP multi-user types](https://source.android.com/docs/devices/admin/multi-user):
  CLONE is a profile type; OEMs must provide end-to-end integration. Existence of
  this type alone does not promise launcher enumeration or multiple siblings.
* [LauncherApps](https://developer.android.com/reference/android/content/pm/LauncherApps),
  [LauncherActivityInfo](https://developer.android.com/reference/android/content/pm/LauncherActivityInfo):
  getActivityList / getProfiles / startMainActivity are user scoped. A successful
  query by a privileged helper is not evidence of what another launcher renders.
* [ShortcutManager](https://developer.android.com/reference/android/content/pm/ShortcutManager):
  pinning requires launcher support and user interaction. Publishers can disable
  pinned shortcuts, but cannot force their removal. Pinned shortcuts are therefore
  not the managed fallback in this release.
* [PackageManagerShellCommand](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/PackageManagerShellCommand.java):
  create-user --profileOf --user-type, install-existing --user, install --user,
  uninstall --user, get-max-users and remove-user. Runtime help gates commands.
* [ActivityManagerShellCommand](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActivityManagerShellCommand.java):
  get-current-user, start-user -w, start --user -W -n. Arguments are passed as an
  argv array, never as an input-built shell program.
* Android SDK source packages 32, 33, 35, 36.1 and 37.2: UserInfo.userType,
  serialNumber, canHaveProfile; UserManager.getUsers(boolean,boolean,boolean),
  getUserInfo(int), getProfileParent(int), canAddMoreProfilesToUser(String,int);
  ActivityThread.systemMain/getSystemContext; Context.createContextAsUser.
  These are **hidden framework APIs**, not public SDK promises. Reflection is
  confined to AndroidPlatform, checked at runtime, and failure is explicit.
  No guessed Binder transaction numbers or global hidden-API setting changes.
* [Android 17/API 37](https://developer.android.com/about/versions/17/behavior-changes-17).
* [KernelSU module contract](https://kernelsu.org/guide/module.html),
  [WebUI](https://kernelsu.org/guide/module-webui.html), and
  [KernelSU-Next source](https://github.com/KernelSU-Next/KernelSU-Next/tree/b93da6492492eee898da06828df86f8a3d5354be):
  module.prop, customize.sh, service.sh, uninstall.sh, webroot/index.html.
  Next's ModuleViewModel reads version, integer versionCode, zipUrl, changelog.
  Its Module screen fetches the changelog URL as text and renders Markdown;
  update.json therefore points to the tagged raw CHANGELOG.md, not an HTML page.
  Atomic description replacement preserves all other module.prop fields.
  The WebUI bridge and optional getPackagesIcons were checked against Next's
  [API document](https://github.com/KernelSU-Next/KernelSU-Next/blob/b93da6492492eee898da06828df86f8a3d5354be/docs/WebUi_Next/API_DOC.md)
  and WebViewInterface.kt at that same commit.
* [AOSP apksig](https://android.googlesource.com/platform/tools/apksig/): the
  SDK's Apache-2.0 signing implementation supplies APK v2 signatures. It is
  designed for host use; its on-device execution is a separately tested runtime
  capability, not an Android API guarantee.

## Launcher policy

Generic Clone Core → LauncherIntegration → GenericAndroidAdapter or ZteAdapter.
The home activity is resolved for the parent at runtime. A positive privileged
LauncherApps query alone does not prove HOME visibility. The adapter reports
`PRESENT`, `ABSENT` or `UNKNOWN` for the exact package + live target serial.
PRESENT selects NATIVE and removes the owned proxy; ABSENT may select PROXY after
capability checks; UNKNOWN returns partial failure without creating a duplicate.
Initial/explicit retries allow a 10-second observation window for OEM refresh.
Reconciliation rechecks native visibility and transitions modes idempotently.
The generic public-API adapter can establish query absence, but cannot assert a
positive HOME presentation; a positive query without a reviewed launcher adapter
therefore remains UNKNOWN. This is a deliberate unverified compatibility limit.
Each proxy is an ordinary MAIN/LAUNCHER package installed **only
in the parent** with the cloned app's icon and a profile-specific label. Package
identity hashes package name, parent serial and target serial. There is no limit
of one sibling in core.

Proxy activities have no root grant, permissions, generic URI handler or command
arguments. A root broker validates Linux peer UID, registered proxy package,
certificate, profile serial and parent before a fixed launch of the resolved
target MAIN/LAUNCHER activity. PROBE must complete before READY_PROXY. Socket or
SELinux denial yields partial success; no broad SELinux allow rule is installed.

Normal package installation supplies a persistent launcher app entry; exact
workspace cell placement is launcher policy. A launcher that hides even ordinary
parent apps still needs device verification. We do not patch its database or APK.

## REDMAGIC / ZTE adapter

Selected by resolved HOME identity, without model/brand checks in generic core.
`com.zte.mifavor.launcher.quickstep.taskprovider` and `com.zte.cn.doubleapp` are
runtime observations, not writable API contracts. Numeric user IDs are never
substituted into target selection.

The inspected MiFavor AllAppsStore dump contains hidden sibling rows as well as
visible apps. Consequently, raw model membership is insufficient. The adapter
uses the inspected personal-drawer OEM badge predicate, joins component/flags to
the read-only icon cache's profile **serial**, and validates live UserManager
metadata and target LauncherActivityInfo. Stale cache rows never establish app
visibility. Truncated dumps, ambiguous identity, missing cache, altered APK or
changed formats produce UNKNOWN. Only this reviewed launcher APK is accepted:
SHA-256 `02aca1d5d77eb193908ddf10c66a413c04160c106ffa26fa03bbd43999861310`.
An OEM update requires reviewing its actual predicate/schema before adding its
digest; changing the allowlist alone is not a compatibility fix.

SQLite is opened read-only under the HOME ApplicationInfo data directory. No
launcher/OEM database, clone service metadata or system APK is written or patched.
The framework itself performs its ordinary package/model/cache updates.

| Platform/capability | Status |
|---|---|
| NX769J / Android 16 API 36 / RedMagicOS 11.0.8MR6 / MiFavor 160000, inspected APK above | Device verified; see TESTING.md for each run |
| Android 12/12L/13/14/15/17 | Implemented gates/APIs; device behavior unverified |
| Other launchers, other MiFavor APK builds | Unverified; unknown native visibility blocks success |
| Arbitrary apps, signature rotation, every crash boundary, future releases | Unverified |

Earlier `pm clear --cache-only` returned Success on the recorded MiFavor device.
That demonstrates the observed command result and subsequent launcher behavior;
it is not proof of a full model/database rebuild or a generic cross-version
launcher-cache contract. The module itself never clears launcher cache or data.
