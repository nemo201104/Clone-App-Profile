# Branding and English localization validation

Completed on 2026-09-11, starting from
`e337f3107587a0c1b00897eefb4ac848aba0a25c`. Existing user formatting of style.css
was preserved. The supplied source assets were inspected before implementation;
no replacement artwork was generated. Remote main already contained the same
assets in the independent asset-upload commit below. No v1.0.0 tag/release existed.

## Asset and metadata contract

* Source logo: `assets/icon.png`, 500x500, SHA-256
  `247bc088f7eb6f834b1aea850241192b3913c15e77c2a7e9efe1e03604a18868`.
  Its actual encoding is JPEG despite the filename. Host-only `tools/Branding.java`
  decodes and writes PNG, verifies every decoded pixel, and leaves the source
  untouched. `tools/build.py` generates `module/webroot/icon.png` (ignored output).
* Runtime logo: `webroot/icon.png`, 500x500 PNG, 60736 bytes, SHA-256
  `4794773dd331497821ee0cb025c478f4118bf9e91a8494ab4495e207ae34a80d`.
  Used by the WebUI header and `webuiIcon=webroot/icon.png`. No actionIcon is added.
* Source banner: `assets/banner.png`, 1672x941 PNG, 1408254 bytes, SHA-256
  `91c9cb3af61901d4032c841580db85d8ad00aa87f681ec12942243b74734202e`.
* Final immutable banner URL:
  [repository asset at its existing commit](https://raw.githubusercontent.com/nemo201104/Clone-App-Profile/9f319ca5244d268742348fe868e06fc9a41e1561/assets/banner.png).
  HTTP 200, MIME image/png and downloaded bytes equal the source asset. The
  existing commit avoids a nonexistent tag URL or mutable main URL. It remains
  appropriate for v1.0.0; tagging/publishing is a separate authorized action.
* Banner stays remote, outside the module ZIP. Source assets remain tracked under
  assets/; the generated runtime copy is ignored. Missing/invalid images fail the
  build rather than producing a placeholder.

KernelSU-Next's [ModuleViewModel](https://github.com/KernelSU-Next/KernelSU-Next/blob/b93da6492492eee898da06828df86f8a3d5354be/manager/app/src/main/java/com/rifsxd/ksunext/ui/viewmodel/ModuleViewModel.kt)
reads banner/webuiIcon metadata. Its [module card](https://github.com/KernelSU-Next/KernelSU-Next/blob/b93da6492492eee898da06828df86f8a3d5354be/manager/app/src/main/java/com/rifsxd/ksunext/ui/screen/Module.kt)
loads remote banners with crop, opacity and a fade overlay for readable card text.
Its [module reader](https://github.com/KernelSU-Next/KernelSU-Next/blob/b93da6492492eee898da06828df86f8a3d5354be/userspace/ksud/src/module.rs)
resolves a relative webuiIcon inside the installed module. These were rechecked
against that current Next source commit and the actual installed manager.

## Theme and language

One `--color-primary: #0E60E2` token supplies the primary brand color. Separate
hover/active/soft/text/focus/progress tokens support light/dark contrast. Primary
buttons and selected tabs use white text on blue; dark links/focus use a lighter
blue. Destructive controls and their confirmation buttons remain red. Neutral
surfaces and existing danger semantics are retained. The user's expanded CSS
formatting is retained; the header uses a bounded image and responsive alignment.

All module-owned runtime strings are English: HTML, JS status/results/dialogs,
busy/empty states and bridge errors. Installer, CLI, Java/backend/proxy messages
were already English and needed no source/binary changes. Visible enum labels are
formatted as Native/Proxy/Managed/External/Running/etc., without changing JSON
keys, registry schema, operation enums or package/class identifiers.

Intentionally unchanged: app labels and profile names supplied by users/Android,
technical identifiers/raw diagnostic JSON, original OS error text, proper names,
historical documentation and Unicode test fixtures. The manager's own Vietnamese
locale and Android system UI belong to those applications and are not rewritten.
Static checks scan runtime-owned source files, excluding those external/test/docs
inputs and third-party notices. Dynamic module description remains exactly
`Profile: <USED>/<MAX> | Cloned: <COUNT>`.

## Validation

| Check | Result |
|---|---|
| Build, 47 Java assertions, 3 bridge tests, shell/JS/JSON checks | PASS |
| English runtime scan, logo/pixel validation, metadata/ZIP checks | PASS |
| Clean source copy without generated files or runtime icon | PASS: same final ZIP hash |
| Chrome at 320/390/960px, light and dark | PASS: logo loads, no header overlap/overflow, readable dialogs/buttons |
| Primary/selected/link text contrast >= 4.5, focus >= 3, red destructive confirmation contrast >= 4.5 | PASS |
| Existing browser partial-success, ownership, cancel and injection checks | PASS |
| Remote banner HTTP/MIME/source hash | PASS |
| Actual Next installation and reboot | PASS |
| Actual module list banner/name/author/dynamic description readability | PASS |
| Actual module card Open button -> WebUI logo, blue theme and English copy | PASS, device dark mode |
| Actual ksud webuiIcon resolution | PASS: installed webroot/icon.png |
| Installed file/metadata equality, registry VALID and broker READY | PASS |
| Existing profiles/cloned count across install | Preserved: Profile: 2/4, Cloned: 1 |

Device: NX769J, Android 16/API 36, RedMagicOS 11.0.8MR6, installed KernelSU-Next
manager v3.3.0-41-g39ba3821-spoofed (33255), ksud 3.3.0/UAPI 2. The manager card
was inspected directly, not inferred from browser screenshots. Device dark-mode
WebUI was checked; light mode is verified by browser tests. Raw captures and
device JSON remain local/ignored under build/branding-*. No screenshots enter ZIP.

Core and proxy sources are unchanged, and final binaries exactly match the
previously accepted installed device build:

* core.jar: `ffa573521d6afb14b1740371b318df26f0253f1c8d4e39557d275661d13c5e41`.
* proxy-template.apk: `adc76723794eb9dcf5f09632f00f96678be72d1defd8b42f57f617ed30f37870`.

The workspace initially contained other generated bytes from a different local
toolchain (system Java 26). Rebuilding with the documented JDK 17 reproduced the
accepted device binaries. The build now rejects another Java major instead of
silently changing them. Source artwork/profile badges, signing, native/proxy
selection and routing therefore retain the previous acceptance evidence. No
destructive full A–G retest was required or run for branding; only the appropriate
installed-module smoke/visual checks were performed. No existing clone was removed.

## Artifact and release impact

`dist/Clone-App-Profile-v1.0.0.zip`: **283897 bytes**, exactly 14 module files.
SHA-256: **`bd5280f9815ced1d91de2a625970c88303e54aeba2589d6d84b5c3c290ad4f9d`**.
Includes generated webroot/icon.png; excludes banner/source assets, tests,
screenshots, caches, private keys and development outputs. The prior launcher
release-gate ZIP hash is superseded for packaging; its functional evidence remains.

Release impact: **REQUIRES NEW COMMIT BEFORE v1.0.0 TAG**. This updates the
unreleased artifact, not an existing immutable release. No version bump, tag,
release publication or force-push is performed. The prior publication/live-update
gate remains separate; branding validation does not claim that endpoint published.
