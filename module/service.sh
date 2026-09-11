#!/system/bin/sh
MODDIR=${0%/*}
umask 077
while [ "$(/system/bin/getprop sys.boot_completed)" != 1 ]; do
  [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ] && exit 0
  sleep 3
done
"$MODDIR/bin/capctl" init >> /data/adb/clone_app_profile/service.log 2>&1 || exit 1
# Java FileLock ensures one supervisor; it reconciles and restarts its broker.
while [ -f "$MODDIR/module.prop" ] && [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ]; do
  "$MODDIR/bin/capctl" service >> /data/adb/clone_app_profile/service.log 2>&1
  result=$?
  [ "$result" -eq 0 ] || [ "$result" -eq 3 ] && exit 0
  if [ "$(wc -c < /data/adb/clone_app_profile/service.log)" -gt 2097152 ]; then
    mv /data/adb/clone_app_profile/service.log /data/adb/clone_app_profile/service.previous.log
  fi
  sleep 10
done
