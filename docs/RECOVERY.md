# Recovery

Keep a private backup of `/data/adb/clone_app_profile`, including `signing.json`.
It contains private key material and must not be attached to issues. Exported
event logs intentionally exclude that key. Do not edit Android user databases,
delete arbitrary `/data/user` directories or substitute a user ID from an example.

## Launcher failure

The app may be installed even if Clone App returns
`PARTIAL_SUCCESS_LAUNCHER_INTEGRATION_FAILED`. My Clones shows package success,
launcher error and target identity. Start the module service/reboot after install,
select the correct parent HOME, unlock the parent, and use Retry launcher. Doctor
reports broker readiness. Retry never creates another app data sandbox or adopts
an untracked package.

If SELinux blocks the authenticated socket, the operation remains partial. This
release does not install broad SELinux rules, switch SELinux to permissive, grant
root to a proxy or fall back to launching the owner's package.

## Registry corruption

All mutations fail closed. Preserve the damaged file first. The root-only command
`capctl registry-recover` takes the same lock, validates `registry.bak`, preserves
the damaged bytes in `registry.corrupt-<timestamp>.json`, and atomically restores
the backup. Unknown schemas and invalid backups are rejected. It does not mutate
Android profiles or packages. Recovered records still must match live user
serials and package installation/signing identities before destructive actions.
The backup may precede the last completed operation, so inspect Doctor and My
Clones after restoring it.

## Interrupted creation/install

`pending[]` stores the operation ID and the before/target identities. New creation
and clone operations are blocked until the ambiguous operation is inspected.
`capctl recover-pending <operationId>` clears a journal **only after verifying
that no corresponding new Android resources remain**. It cannot acquire
ownership or delete an unverified existing resource.

If Android committed a new profile/package but the process died before ownership
verification, manual inspection is required. Compare the pending before snapshot,
returned identity (if recorded), serials, parent and package metadata. Do not erase
the journal or adopt a package merely because its name is present. This is an
intentional fail-safe crash window; automatic destructive repair is not provided.

## Interrupted removal

Retry the same Remove Cloned/Delete Profile action. The original registry
identity is retained until target disappearance and launcher cleanup are verified.
The launcher entry may already have been removed, so the row can show
`REMOVING`/`REMOVED`. Reconciliation will not recreate an entry that is being
removed. A reused user ID or package installation mismatch blocks the retry.

## Lost signing key / module uninstall

Never generate a replacement key over registered proxies. Restore your private
backup. Module uninstall preserves profiles, app data, registry and proxy entries;
proxies fail closed while the broker is unavailable. Reinstall to manage them.
For complete cleanup, use Remove/Delete before uninstalling the module.
