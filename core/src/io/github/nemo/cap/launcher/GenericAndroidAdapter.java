package io.github.nemo.cap.launcher;
import io.github.nemo.cap.*;
import org.json.JSONObject;

public final class GenericAndroidAdapter implements LauncherAdapter {
    public JSONObject inspect(AndroidPlatform p,JSONObject home,int parent) throws Exception {
        p.requirePm("install ");p.requirePm("--user");
        return Json.obj("adapter","generic-android","nativeVisibility","UNVERIFIED",
            "managedProxySupported",true,"reason","No public API proves another launcher's rendered profile entries");
    }
    public JSONObject nativeEntry(AndroidPlatform p,JSONObject home,JSONObject c) throws Exception {
        JSONObject framework=p.launcherQuery(c.getString("packageName"),c.getInt("targetUserId"),c.getInt("sourceUserId"));
        // A privileged positive query is not a statement about HOME's own model.
        // Unknown visibility must not create a potentially duplicate representation.
        String state=!framework.has("error") && !framework.getBoolean("enumerated")?"ABSENT":"UNKNOWN";
        return Json.obj("state",state,"framework",framework,"reason","HOME native identity is not verifiable through a public query alone");
    }
    public String externalProfileClass(AndroidPlatform p,JSONObject home,JSONObject profile) {return "EXTERNAL_GENERIC";}
}
