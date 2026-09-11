#!/system/bin/sh
MODDIR=${0%/*}
umask 077
# Deliberately preserve user profiles, app data, proxy packages and their registry.
# The broker checks module existence/disable flags on every request and fails closed.
if [ -d /data/adb/clone_app_profile ]; then
  printf '%s\n' 'Module uninstalled: profiles, clone data, proxy entries and private registry retained. Reinstall to manage them; remove clones/profiles through WebUI before uninstall for full cleanup.' >> /data/adb/clone_app_profile/uninstall.log
fi
