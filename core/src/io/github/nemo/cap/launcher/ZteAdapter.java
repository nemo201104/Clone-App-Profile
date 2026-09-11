package io.github.nemo.cap.launcher;
import io.github.nemo.cap.*;
import org.json.*;
import android.content.pm.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.InputStream;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/** OEM knowledge is scoped here, selected from resolved HOME, never Build.BRAND/MODEL. */
public final class ZteAdapter implements LauncherAdapter {
    private static String verifiedApkStamp;
    private static synchronized void verifyContract(AndroidPlatform p,JSONObject home,int parent) throws Exception {
        PackageInfo info=p.pm(parent).getPackageInfo(home.getString("packageName"),0);
        File source=new File(info.applicationInfo.sourceDir);
        String stamp=source.getPath()+":"+info.lastUpdateTime+":"+source.length()+":"+source.lastModified();
        if(stamp.equals(verifiedApkStamp))return;
        // Private UI predicates are not generic API contracts. A changed OEM APK
        // needs inspection before its model can be treated as visibility evidence.
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(source)) {byte[] bytes=new byte[32768];int n;while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);}
        StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));
        Failure.require(hash.toString().equals("02aca1d5d77eb193908ddf10c66a413c04160c106ffa26fa03bbd43999861310"),
            "OEM_ADAPTER_REVIEW_REQUIRED","MiFavor APK differs from the inspected native-drawer contract");
        verifiedApkStamp=stamp;
    }
    public static boolean matches(JSONObject home) {return "com.zte.mifavor.launcher".equals(home.optString("packageName"));}
    public JSONObject inspect(AndroidPlatform p,JSONObject home,int parent) throws Exception {
        JSONObject out=new GenericAndroidAdapter().inspect(p,home,parent);
        ProviderInfo provider=p.pm(parent).resolveContentProvider("com.zte.mifavor.launcher.quickstep.taskprovider",0);
        out.put("adapter","redmagic-zte").put("taskProviderPresent",provider!=null)
            .put("doubleAppPresent",p.app("com.zte.cn.doubleapp",parent)!=null)
            .put("nativeVisibility","UNVERIFIED")
            .put("reason","Native visibility requires a complete HOME model joined to live profile serials");
        return out;
    }
    private JSONArray cache(AndroidPlatform p,JSONObject home,int parent,String pkg,Long serial) throws Exception {
        verifyContract(p,home,parent);
        ApplicationInfo app=p.app(home.getString("packageName"),parent);
        Failure.require(app!=null && app.dataDir!=null,"NATIVE_DETECTION_UNAVAILABLE","HOME storage unavailable");
        File root=new File(app.dataDir).getCanonicalFile(),db=new File(root,"databases/app_icons.db");
        Failure.require(db.isFile() && !Files.isSymbolicLink(db.toPath()) && db.getCanonicalPath().startsWith(root.getPath()+File.separator),"NATIVE_DETECTION_UNAVAILABLE","Inspected HOME cache is unavailable");
        JSONArray result=new JSONArray();
        // OPEN_READONLY: never create, migrate, repair, clear or write an OEM DB.
        try(SQLiteDatabase database=SQLiteDatabase.openDatabase(db.getPath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            Cursor rows=database.query("icons",new String[]{"componentName","profileId","flags"},
                serial!=null?"profileId=?":"componentName LIKE ?",new String[]{serial!=null?Long.toString(serial):pkg+"/%"},null,null,null,"20000")) {
            while(rows.moveToNext()) result.put(Json.obj("component",rows.getString(0),"serial",rows.getLong(1),"flags",rows.getInt(2)));
        }
        return result;
    }
    public JSONObject nativeEntry(AndroidPlatform p,JSONObject home,JSONObject c) throws Exception {
        try {
            int parent=c.getInt("sourceUserId");
            JSONArray model=ZteSnapshot.parse(AndroidPlatform.run(12,"/system/bin/dumpsys","activity",home.getString("component")));
            JSONArray cache=cache(p,home,parent,c.getString("packageName"),null),users=p.profiles();
            Set<Long> live=new HashSet<>();
            for(int i=0;i<users.length();i++) {JSONObject u=users.getJSONObject(i);if(u.getInt("userId")==parent || u.getInt("parentUserId")==parent)live.add(u.getLong("serialNumber"));}
            JSONObject result=ZteSnapshot.match(model,cache,c.getString("packageName"),c.getLong("targetSerial"),live);
            JSONObject framework=p.launcherQuery(c.getString("packageName"),c.getInt("targetUserId"),parent);
            // PRESENT must also resolve to the same exported target activity now.
            if("PRESENT".equals(result.getString("state")) && p.app(c.getString("packageName"),c.getInt("targetUserId"))!=null) {
                Failure.require(framework.optBoolean("enumerated"),"NATIVE_DETECTION_UNAVAILABLE","HOME entry is not target-resolvable");
                Set<String> exposed=new HashSet<>();JSONArray activities=framework.getJSONArray("activities");
                for(int i=0;i<activities.length();i++)exposed.add(activities.getString(i));
                JSONArray components=result.getJSONArray("components");
                for(int i=0;i<components.length();i++)Failure.require(exposed.contains(components.getString(i)),"NATIVE_DETECTION_UNAVAILABLE","HOME component differs from target LauncherActivityInfo");
            }
            return result.put("framework",framework).put("targetUserId",c.getInt("targetUserId"));
        } catch(Exception e) {return Json.obj("state","UNKNOWN","error",e.toString());}
    }
    public String externalProfileClass(AndroidPlatform p,JSONObject home,JSONObject profile) throws Exception {
        JSONArray rows=cache(p,home,profile.getInt("parentUserId"),null,profile.getLong("serialNumber"));
        // This inspected OEM badge metadata identifies the profile's integration,
        // not the presence of any particular app. No numeric user ID is consulted.
        for(int i=0;i<rows.length();i++)if((rows.getJSONObject(i).getInt("flags")&1024)!=0)return "EXTERNAL_OEM";
        return "EXTERNAL_GENERIC";
    }
}
