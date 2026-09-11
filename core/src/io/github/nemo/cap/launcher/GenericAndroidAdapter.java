package io.github.nemo.cap.launcher;
import io.github.nemo.cap.*;
import org.json.JSONObject;

public final class GenericAndroidAdapter implements LauncherAdapter {
    public JSONObject inspect(AndroidPlatform p,JSONObject home,int parent) throws Exception {
        p.requirePm("install ");p.requirePm("--user");
        return Json.obj("adapter","generic-android","nativeVisibility","UNVERIFIED",
            "managedProxySupported",true,"reason","No public API proves another launcher's rendered profile entries");
    }
}
