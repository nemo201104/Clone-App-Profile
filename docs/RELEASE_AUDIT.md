# v1.0.0 release audit — 2026-09-11

Scope: core/AndroidPlatform/registry, launcher adapters/proxy/broker, WebUI,
lifecycle, logs, updater, build/check scripts and release workflows. This is a
source audit with the recorded host/device tests, not exhaustive fault injection
or a security certification. Live release status is in RELEASE_GATE.md.

## Blockers found and corrected

| Finding | Correction and evidence |
|---|---|
| OEM native icon plus unconditional parent proxy | Native-first PRESENT/ABSENT/UNKNOWN decision, persisted NATIVE/PROXY mode; native detection removes the owned proxy before success |
| Privileged LauncherApps enumeration does not establish HOME rendering | Generic positive query stays UNKNOWN; reviewed OEM adapter joins HOME model to exact live profile serial |
| MiFavor raw AllAppsStore also includes hidden siblings | Inspected personal-drawer predicate filters hidden clone rows; reviewed APK digest gate prevents assuming private format compatibility after OEM updates |
| Native removal could outlive package removal | Bounded native disappearance check, retained PENDING_REMOVAL/REMOVING ownership and partial status; retry does not create a new instance |
| Reconcile/log/UI could conceal pending removal | Pending removal is reported, correct partial code is logged, retry-launcher is unavailable during removal |
| Updater compared with a compiled constant | Reads installed module.prop versionCode; real current/lower/restored device test is required after publication |
| Changelog URL returned GitHub HTML | Tagged raw Markdown URL, consistent with Next Module.kt fetch-and-render contract |
| CI could publish an unreviewed rebuild | Required reviewed SHA-256 input, strict artifact equality/layout check and refusal to overwrite an existing release tag |

## Reviewed boundaries

* Clone targets must remain enabled CLONE profiles under the foreground parent.
  Parent/target IDs and serials are validated; there is no secondary-user fallback,
  global user switch, fixed clone user ID, model or brand target selection.
* MODULE_MANAGED classification requires matching registry ownership. External
  OEM/generic profiles are selectable but never adopted or deleted. An already
  installed untracked target package is refused. App records retain install time
  and signing certificate; a changed identity requires inspection.
* ProcessBuilder passes validated argv to fixed pm/am executables. WebUI has an
  operation allowlist, strict argument syntax, textContent rendering, CSP and no
  remote script dependency. The proxy has no root grant, arbitrary shell/URI
  endpoint, caller-specified target user, package or component.
* AF_UNIX peer UID + unique package + signing certificate select the registry
  mapping. Fixed LAUNCH/PROBE verbs, nonce expiry, bounded requests/workers and
  parent/target checks limit the broker. The broker and proxy both verify peers.
  The native OEM launch path is controlled by Android/HOME and bypasses the broker.
* The shared OS FileLock covers CLI/WebUI/reconcile/removal/launch registry work.
  Saves validate schema, fsync files, atomically rename and retain a validated
  backup. Death releases locks; corrupt/unknown registries fail closed. Pending
  journal ambiguity blocks creation instead of claiming unverified ownership.
* PROXY-to-NATIVE removal persists transition state before uninstall. NATIVE-to-
  PROXY persists mode before writing proxy fields. Schema validation forbids both
  identities in a NATIVE record. Reconcile never reinstalls a removed user app;
  removal failures retain records. An unregistered orphan proxy requires recovery.
* Private registry/key storage uses root ownership, directory mode 0700 and umask
  077. Only generated proxies are signed, using AOSP apksig v2. A lost key is not
  silently rotated over registered proxies. No device key, test signing key, OEM
  APK/decompilation, raw device dump or generated fixture enters git/release ZIP.
* OEM cache access is read-only, with canonical-path confinement and parameterized
  queries. No system APK, launcher database or OEM clone metadata mutation occurs.
  PackageManager/HOME may naturally update their own records after package changes.
* Install/init creates no profile/app. Service starts after boot, uses a singleton
  lock and bounded periodic reconciliation. Disable/remove stops broker access.
  Uninstall preserves user data, registry and proxies; native OEM entries continue
  according to OEM behavior. Destructive cleanup stays an explicit scoped action.
* Logs are private, bounded/rotated and omit signing material. Public export uses
  a generated fixed-directory filename and exports event logs only. Metadata uses
  a fixed HTTPS endpoint, bounded response, strict version and release URL checks;
  it never downloads/runs arbitrary commands or embeds an access token.

## Limits retained explicitly

Other root/system actors are outside the trust boundary. Android's individual
package/user operations do not offer an atomic serial-and-mutate transaction;
revalidation and module locking do not serialize an unrelated privileged tool.
Crash after Android commits but before ownership is verified requires inspection.
Restoring an older registry can leave an unowned resource; recovery never guesses.

Other OEM builds/launchers and Android 12–15/17 are unverified. A changed MiFavor
APK requires inspecting its private UI predicate/schema before accepting a digest.
Delayed native cleanup, every crash boundary, ID reuse, signer rotation, hostile
root races and future version upgrades have not all been injected on a device.
Host parser/registry tests cover selected failure cases; they do not certify those
device scenarios. Native app drawer visibility does not promise a particular home
screen cell, override hidden-app preferences, or force OEM contact/media isolation.
