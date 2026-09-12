# Changelog

## v1.0.1

* Changed project author/maintainer identity to nemoforge.
* Migrated GitHub update and release URLs to the nemoforge account.
* Updated support URL to https://nemoforge.github.io.
* Bundled the module banner locally for offline availability.

## v1.0.0

* Repository logo, immutable repository-hosted module banner, KernelSU-Next
  WebUI icon, primary blue `#0E60E2`, English runtime copy and readable light/dark
  layouts. Destructive actions and their confirmation buttons remain red.
  Clone artwork/badges, backend/proxy binaries and routing are unchanged.
* KernelSU-Next module, CLI and responsive WebUI for runtime-gated Android CLONE
  profiles, package management, diagnostics, logs and release update metadata.
* Atomic, locked and versioned private ownership registry with operation journals
  and explicit corruption recovery.
* Fix duplicate launcher icons on the inspected OEM clone profile: native-first
  detection persists NATIVE or PROXY per package/profile identity and removes an
  owned proxy when the exact native entry appears. Unknown visibility stays
  partial; it never silently creates a potentially duplicate representation.
* Classify module-managed, external OEM and external generic CLONE profiles.
  External profile selection owns only the newly cloned app; existing packages
  are not adopted and external profiles cannot be deleted by the module.
* Isolated REDMAGIC/ZTE compatibility adapter uses read-only runtime HOME/profile
  evidence with a reviewed launcher APK contract. No OEM database or APK writes.
* Unique per-clone parent proxy APKs, per-device signing and authenticated exact
  target launches. Failed launcher integration reports partial success; native
  removal waits for entry disappearance and retains pending cleanup on delay.
* Per-instance/profile launcher cleanup, persistent identities and scheduled
  reconciliation; profile/package ownership checks before destructive actions.
* Deterministic ZIP packaging, host checks, disposable device fixture and explicit
  A–G acceptance matrix. No system APK patches or hard-coded clone user ID.
* Update comparison reads installed versionCode; changelog uses tagged Markdown.
  Manual CI publication requires the device-reviewed artifact's exact SHA-256.

Verified platform: NX769J, Android 16/API 36, RedMagicOS 11.0.8MR6,
`com.zte.mifavor.launcher` versionCode 160000, inspected APK contract documented
in COMPATIBILITY.md. Historical A–G and the native/proxy release gate are separate
execution records. Android 12–15/17, other ROMs/launchers and changed OEM launcher
APKs remain unverified; runtime capability failures are explicit. Observed
MiFavor cache-only command success is not a cross-version cache-rebuild promise.
