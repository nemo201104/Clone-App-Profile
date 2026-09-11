package io.github.nemo.cap.launcher;
import io.github.nemo.cap.*;
import org.json.*;
import android.content.pm.*;

/** OEM knowledge is scoped here, selected from resolved HOME, never Build.BRAND/MODEL. */
public final class ZteAdapter implements LauncherAdapter {
    public static boolean matches(JSONObject home) {return "com.zte.mifavor.launcher".equals(home.optString("packageName"));}
    public JSONObject inspect(AndroidPlatform p,JSONObject home,int parent) throws Exception {
        JSONObject out=new GenericAndroidAdapter().inspect(p,home,parent);
        ProviderInfo provider=p.pm(parent).resolveContentProvider("com.zte.mifavor.launcher.quickstep.taskprovider",0);
        out.put("adapter","redmagic-zte").put("taskProviderPresent",provider!=null)
            .put("doubleAppPresent",p.app("com.zte.cn.doubleapp",parent)!=null)
            .put("nativeVisibility","UNVERIFIED")
            .put("reason","OEM user_999 canonical-profile handling does not establish sibling enumeration; use parent-user proxy");
        return out;
    }
}
