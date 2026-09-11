# Implementation report — v1.0.0

## Delivered files and architecture

* `module/`: KernelSU-Next metadata, lifecycle scripts, `bin/capctl`, WebUI.
* `core/`: Android compatibility helpers, clone/profile core, atomic registry,
  logs/updater, launcher integration, authenticated broker and reconciliation.
* `proxy/`: permission-free launcher activity template. A distinct signed parent
  package is generated for each package + parent serial + target serial.
* `tests/`, `tools/`: reproducible build, host/browser checks, disposable Android
  fixture, installed-module visual acceptance and negative device cases.
* `.github/workflows/`: build/validation artifacts and an opt-in manual release.
* README, CHANGELOG, Apache-2.0 LICENSE, update metadata and documentation.

WebUI → fixed `capctl` operation → Clone Core → Android compatibility layer →
UserManager/PackageManager. Clone Core calls LauncherIntegration, which selects
the generic or isolated ZTE adapter from resolved HOME and runtime inspection.
The root-only registry is shared by CLI, WebUI, daemon and broker under FileLock.

No system/OEM APK is patched. The proxy socket accepts only fixed protocol verbs;
it never accepts arbitrary commands, target users or packages. Launch identity is
derived from authenticated caller UID/certificate and validated registry records.

## Prompt coverage

| Requirement | Implementation / evidence |
|---|---|
| Metadata and Android range | Name/author/versionCode 10000, API 31–37 gate, required ZIP name |
| Official contracts | Sources and SDK inspections listed in COMPATIBILITY.md |
| Only CLONE profiles | Runtime type/parent/serial validation; no SECONDARY fallback |
| Counter | All live users / runtime maximum; clone count from module registry |
| Registry | Schema 1, FileLock, fsync + atomic rename, backup/recovery, ownership journals |
| Create | Support/capacity gates, start/unlock verification, scoped rollback |
| Profile list | All types, parent, serial, running state, ownership and clone count |
| Clone | User-app picker, install-existing per target, enabled/hidden/suspended/sandbox checks |
| Launcher DoD | Native-first exact identity detection, exclusive NATIVE/PROXY modes, proxy probe, partial failure |
| Remove | Owned proxy cleanup or verified native disappearance after target-only removal; pending failures retained |
| Delete | Only recorded module-owned profile with current serial/type/parent validation |
| Logs | Private rotating JSONL; WebUI table and fixed-path public export |
| WebUI | Responsive themes, loading/results, confirmations, shared backend, support/update |
| CLI | All requested commands plus launcher retry/reconcile and explicit recovery |
| Compatibility | Runtime-detected hidden framework methods and advertised shell options |
| OEM context | Runtime profile classification, reviewed read-only HOME/cache contract in isolated adapter, no fixed target user ID |
| Updates | Verified Next JSON contract, integer versionCode comparison, fixed HTTPS endpoint |
| CI/release | SHA-gated manual publication; live release/update decision is in RELEASE_GATE.md |
| Lifecycle | Actual Next install/reboot tested; updates preserve private registry/signing key |
| Uninstall | Preserves profile/app data by design; cleanup instructions documented |
| Dynamic status | Atomic module.prop description; final device count verified at 2/4, 0 clones |
| Security/concurrency | Strict inputs, fixed argv, UID/certificate/serial checks, common OS lock |
| Errors/doctor | Normalized JSON, explicit unsupported/partial states, read-only diagnostics |
| Tests | Host/static, browser, rooted A–G, ownership/error cases; separate pending matrix |
| Documentation | Build/install/update/recovery/compatibility/testing and known limits |

`LauncherApps`, `LauncherActivityInfo` and `UserHandle` provide user-scoped
enumeration evidence. A privileged query cannot prove a different launcher's
rendered UI. This release selects NATIVE only with exact native visibility
evidence and PROXY only after native absence is established. UNKNOWN remains
partial and does not introduce a potential duplicate. Unlike pinned shortcuts, parent package entries
can be removed reliably through PackageManager. Ordinary app entry placement and
hidden-app preferences still belong to the launcher.

Hidden methods such as UserManager metadata access and context bootstrap are
isolated and checked at runtime. `pm`/`am` commands and relevant options are gated
against runtime help; unsupported contracts fail explicitly. APK signing on the
device is also tested at runtime, including an authenticated proxy probe.

## Results and remaining coverage

47 host assertions, 3 bridge tests, shell/JavaScript/JSON/ZIP checks, real APK v2
signature verification, concurrent registry writes/recovery and mobile Chrome
light/dark UI checks passed. Historical A–G passed with two CLONE siblings on
NX769J, Android 16/API 36, RedMagicOS 11.0.8MR6, MiFavor versionCode 160000.
The newer native-first binary has a separate release-gate execution record.
Additional ownership rejection,
missing targets/packages, stale profile cleanup, logs/export and unavailable
update-endpoint handling passed. See TESTING.md for exact scope and evidence.

Android 12/13/14/15/17 devices, other launchers, arbitrary apps, signature rotation,
user-ID reuse, interrupted-write fault injection at every transaction boundary,
and actual future release download/update need further device/lab validation.
Capacity snapshots of 1/4 were not forced on the user's device because its
existing owner/OEM profiles are protected. Uninstall preservation is implemented
and documented; no uninstall of the final module was performed.

Artifact: `dist/Clone-App-Profile-v1.0.0.zip` with `.sha256` and `update.json`.
Final device cleanup, installed hash, remote/tag status and actual published
update endpoint results are recorded in [RELEASE_GATE.md](RELEASE_GATE.md).
