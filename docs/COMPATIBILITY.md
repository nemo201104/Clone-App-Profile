# Compatibility and verified contracts

Research date: 2026-09-11. Support is capability based, API 31–37 (Android
12–17). This is a testable implementation, not certification for every ROM.

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
The home activity is resolved for the parent at runtime. Native enumeration is
recorded as evidence but never equated to visible launcher integration. Until an
adapter can prove native visibility and identity, the layer selects the managed
proxy fallback. Each proxy is an ordinary MAIN/LAUNCHER package installed **only
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

Selected only by the resolved home package `com.zte.mifavor.launcher` and runtime
package/provider inspection. `com.zte.mifavor.launcher.quickstep.taskprovider` and
`com.zte.cn.doubleapp` are observations, not writable API contracts. Canonical
`user_999` special handling belongs only to this adapter's diagnostics. No user
identifier is substituted or inferred from it. This release never patches OEM
APKs or writes OEM launcher databases.

Read-only device observation: API 36, home package above, 2 existing users,
runtime maximum 4. This observation is not a passed clone/launcher acceptance
test. See TESTING.md for actual execution records and remaining device checks.

Subsequent installed-module testing on that device passed the A–G matrix,
including actual icon taps, reboot, cache-only clearing, HOME restart and entry
reconstruction. That result applies to this tested launcher/ROM and the separate
permission-free fixture; it does not certify other launchers or Android versions.
