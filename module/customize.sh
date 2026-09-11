#!/system/bin/sh
SKIPUNZIP=0
[ "$KSU" = true ] || abort 'Clone App Profile requires KernelSU / KernelSU-Next.'
SDK=$(/system/bin/getprop ro.build.version.sdk)
case "$SDK" in ''|*[!0-9]*) abort 'Cannot detect Android SDK.' ;; esac
[ "$SDK" -ge 31 ] && [ "$SDK" -le 37 ] || abort 'Android API 31–37 required.'
for tool in app_process pm am dumpsys; do
  [ -x "/system/bin/$tool" ] || abort "Missing Android tool: $tool"
done
[ -s "$MODPATH/lib/core.jar" ] && [ -s "$MODPATH/lib/proxy-template.apk" ] || abort 'Incomplete ZIP: build binaries first.'
umask 077
mkdir -p /data/adb/clone_app_profile || abort 'Cannot create private registry storage.'
chmod 0700 /data/adb/clone_app_profile
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/bin/capctl" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
"$MODPATH/bin/capctl" init || abort 'Registry initialization failed. Existing data was preserved.'
ui_print 'Profiles and apps are created only through WebUI / capctl.'
ui_print 'Reboot to start the authenticated launcher broker.'
