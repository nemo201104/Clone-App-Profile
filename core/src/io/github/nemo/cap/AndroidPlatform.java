package io.github.nemo.cap;

import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** All hidden APIs are isolated here. No private Binder layouts or OEM user IDs. */
public final class AndroidPlatform {
    public static final String CLONE="android.os.usertype.profile.CLONE";
    public final Context context;
    private final UserManager users;
    private String pmHelp, amHelp;
    public AndroidPlatform() throws Exception {
        Failure.require(Build.VERSION.SDK_INT>=31 && Build.VERSION.SDK_INT<=37,"UNSUPPORTED_ANDROID","Supported API range is 31–37");
        try {
            if(Looper.getMainLooper()==null) Looper.prepareMainLooper();
            Class<?> thread=Class.forName("android.app.ActivityThread");
            Object instance=thread.getMethod("systemMain").invoke(null);
            context=(Context) thread.getMethod("getSystemContext").invoke(instance);
            users=context.getSystemService(UserManager.class);
            UserManager.class.getMethod("getUsers",boolean.class,boolean.class,boolean.class);
            UserManager.class.getMethod("getUserInfo",int.class);
            UserManager.class.getMethod("getProfileParent",int.class);
            Context.class.getMethod("createContextAsUser",UserHandle.class,int.class);
        } catch(Exception e) { throw new Failure("FRAMEWORK_API_UNAVAILABLE",e.toString()); }
    }
    public static Object invoke(Object receiver,String name,Class<?>[] types,Object...args) throws Exception {
        try {return receiver.getClass().getMethod(name,types).invoke(receiver,args);}
        catch(InvocationTargetException e) {throw new Failure("FRAMEWORK_API_FAILED",name+": "+e.getCause());}
    }
    public static UserHandle handle(int id) throws Exception {
        return (UserHandle) UserHandle.class.getMethod("of",int.class).invoke(null,id);
    }
    public Context context(int id) throws Exception {
        return (Context) Context.class.getMethod("createContextAsUser",UserHandle.class,int.class).invoke(context,handle(id),0);
    }
    public PackageManager pm(int id) throws Exception {return context(id).getPackageManager();}
    public JSONObject normalize(Object info) throws Exception {
        Class<?> c=info.getClass(); int id=c.getField("id").getInt(info);
        Object parent=invoke(users,"getProfileParent",new Class[]{int.class},id);
        boolean running=users.isUserRunning(handle(id)), unlocked=users.isUserUnlocked(handle(id));
        return Json.obj("userId",id,"serialNumber",c.getField("serialNumber").getInt(info),
            "name",c.getField("name").get(info),"type",c.getField("userType").get(info),
            "parentUserId",parent==null ? -1 : parent.getClass().getField("id").getInt(parent),
            "partial",c.getField("partial").getBoolean(info),"enabled",invoke(info,"isEnabled",new Class[]{}),
            "canHaveProfile",invoke(info,"canHaveProfile",new Class[]{}),
            "running",running,"unlocked",unlocked,"state",unlocked ? "RUNNING_UNLOCKED" : running ? "RUNNING_LOCKED" : "STOPPED");
    }
    public JSONArray profiles() throws Exception {
        List<?> list=(List<?>) invoke(users,"getUsers",new Class[]{boolean.class,boolean.class,boolean.class},false,false,false);
        JSONArray out=new JSONArray(); for(Object info:list) out.put(normalize(info)); return out;
    }
    public JSONObject user(int id) throws Exception {
        Object info=invoke(users,"getUserInfo",new Class[]{int.class},id);
        return info==null ? null : normalize(info);
    }
    public JSONObject requireUser(int id) throws Exception {
        JSONObject u=user(id); Failure.require(u!=null,"PROFILE_NOT_FOUND","User no longer exists: "+id); return u;
    }
    public JSONObject identity(int id,long serial) throws Exception {
        JSONObject u=requireUser(id);
        Failure.require(u.getLong("serialNumber")==serial,"PROFILE_IDENTITY_MISMATCH","User ID was reused: "+id); return u;
    }
    public int currentUser() throws Exception {
        String s=run(10,"/system/bin/am","get-current-user").trim(); return Json.user(s);
    }
    public int maxUsers() throws Exception {
        requirePm("get-max-users"); String s=run(10,"/system/bin/pm","get-max-users");
        Matcher m=Pattern.compile("(?m)^(?:Maximum supported users:\\s*)?([1-9][0-9]*)\\s*$").matcher(s);
        Failure.require(m.find(),"CAPABILITY_PARSE_FAILED","Unrecognized get-max-users result"); return Integer.parseInt(m.group(1));
    }
    public JSONObject support(int parent) throws Exception {
        boolean can=(Boolean) invoke(users,"canAddMoreProfilesToUser",new Class[]{String.class,int.class},CLONE,parent);
        String dump=run(15,"/system/bin/dumpsys","user");
        Matcher m=Pattern.compile("(?ms)^\\s*"+Pattern.quote(CLONE)+":\\s*\\n(.*?)(?=^\\s*android\\.os\\.usertype\\.|\\z)").matcher(dump);
        String enabled="UNKNOWN";
        if(m.find()) {
            Matcher e=Pattern.compile("mEnabled:\\s*(true|false)").matcher(m.group(1));
            if(e.find()) enabled=e.group(1).equals("true")?"SUPPORTED":"UNSUPPORTED";
        }
        if(can) enabled="SUPPORTED";
        return Json.obj("state",enabled,"canAddMoreProfiles",can,"evidence","UserManager capacity + runtime user-type metadata");
    }
    public void requirePm(String flag) throws Exception {
        if(pmHelp==null) pmHelp=run(15,"/system/bin/pm","help");
        Failure.require(pmHelp.contains(flag),"COMMAND_UNSUPPORTED","pm lacks "+flag);
    }
    public void requireAm(String flag) throws Exception {
        if(amHelp==null) amHelp=run(15,"/system/bin/am","help");
        Failure.require(amHelp.contains(flag),"COMMAND_UNSUPPORTED","am lacks "+flag);
    }
    public void startProfile(int id,long serial) throws Exception {
        identity(id,serial); requireAm("start-user");
        run(45,"/system/bin/am","start-user","-w",Integer.toString(id));
        long until=System.nanoTime()+30_000_000_000L;
        do {
            JSONObject u=identity(id,serial);
            if(u.getBoolean("running") && u.getBoolean("unlocked")) return;
            Thread.sleep(400);
        } while(System.nanoTime()<until);
        throw new Failure("PROFILE_START_FAILED","Profile did not become RUNNING_UNLOCKED");
    }
    public ApplicationInfo app(String pkg,int user) throws Exception {
        try {
            ApplicationInfo a=pm(user).getApplicationInfo(pkg,0);
            return (a.flags & ApplicationInfo.FLAG_INSTALLED)!=0 ? a : null;
        } catch(PackageManager.NameNotFoundException e) {return null;}
    }
    public ResolveInfo resolve(String pkg,int user) throws Exception {
        List<ResolveInfo> all=pm(user).queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg),0);
        all.sort(Comparator.comparing(r->r.activityInfo.name));
        for(ResolveInfo r:all) if(r.activityInfo.exported && r.activityInfo.enabled && r.activityInfo.applicationInfo.enabled
            && pkg.equals(r.activityInfo.packageName)) return r;
        return null;
    }
    public String component(String pkg,int user) throws Exception {
        ResolveInfo r=resolve(pkg,user);
        Failure.require(r!=null,"LAUNCHER_ACTIVITY_NOT_FOUND","No exported MAIN/LAUNCHER activity in user "+user);
        return new ComponentName(pkg,r.activityInfo.name).flattenToString();
    }
    public JSONObject verifyClone(String pkg,int source,int target) throws Exception {
        ApplicationInfo a=app(pkg,target), b=app(pkg,source);
        Failure.require(a!=null,"PACKAGE_INSTALL_FAILED","Package is not installed in target");
        Failure.require(a.enabled,"PACKAGE_DISABLED","Target package is disabled");
        Failure.require(!pm(target).isPackageSuspended(pkg),"PACKAGE_SUSPENDED","Target package is suspended");
        boolean hidden=(Boolean) PackageManager.class.getMethod("getApplicationHiddenSettingAsUser",String.class,UserHandle.class).invoke(pm(target),pkg,handle(target));
        Failure.require(!hidden,"PACKAGE_HIDDEN","Target package is hidden");
        Failure.require(b!=null && a.uid!=b.uid && a.dataDir!=null && b.dataDir!=null
            && !new File(a.dataDir).getCanonicalPath().equals(new File(b.dataDir).getCanonicalPath()) && new File(a.dataDir).isDirectory(),
            "SANDBOX_VERIFICATION_FAILED","Distinct target UID and data sandbox must exist");
        ResolveInfo resolved=resolve(pkg,target);
        return Json.obj("targetUid",a.uid,"sourceUid",b.uid,"targetDataDir",a.dataDir,"sourceDataDir",b.dataDir,"sandboxVerified",true,
            "component",resolved==null?JSONObject.NULL:new ComponentName(pkg,resolved.activityInfo.name).flattenToString());
    }
    public JSONObject home(int parent) throws Exception {
        ResolveInfo r=pm(parent).resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_DEFAULT_ONLY);
        Failure.require(r!=null && r.activityInfo!=null && !"android".equals(r.activityInfo.packageName),"LAUNCHER_NOT_SELECTED","Select a default home launcher");
        PackageInfo pi=pm(parent).getPackageInfo(r.activityInfo.packageName,0);
        return Json.obj("packageName",r.activityInfo.packageName,"component",new ComponentName(r.activityInfo.packageName,r.activityInfo.name).flattenToString(),"versionCode",pi.getLongVersionCode());
    }
    public JSONObject launcherQuery(String pkg,int target,int parent) throws Exception {
        JSONObject e=Json.obj("api","LauncherApps","enumerated",false,"visibleVerified",false);
        try {
            LauncherApps la=context(parent).getSystemService(LauncherApps.class);
            List<LauncherActivityInfo> infos=la.getActivityList(pkg,handle(target));
            JSONArray names=new JSONArray();
            for(LauncherActivityInfo i:infos) if(i.getUser().equals(handle(target))) names.put(i.getComponentName().flattenToString());
            e.put("enumerated",names.length()>0).put("activities",names).put("profileCount",la.getProfiles().size());
        } catch(Exception ex) {e.put("error",ex.toString());}
        return e;
    }
    public String certificate(String pkg,int user) throws Exception {
        PackageInfo p=pm(user).getPackageInfo(pkg,PackageManager.GET_SIGNING_CERTIFICATES);
        Failure.require(p.signingInfo!=null && !p.signingInfo.hasMultipleSigners(),"PROXY_IDENTITY_MISMATCH","Expected single signer");
        return Json.hash(p.signingInfo.getApkContentsSigners()[0].toByteArray());
    }
    public byte[] icon(String pkg,int user,String badge) throws Exception {
        Drawable d=pm(user).getApplicationIcon(pkg);
        Bitmap b=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(b);d.setBounds(0,0,192,192);d.draw(canvas);
        // Preserve the app artwork while making siblings distinguishable even when
        // the launcher ellipsizes the profile suffix in a long application label.
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.WHITE);canvas.drawCircle(151,151,39,paint);
        paint.setColor(0xff242039);canvas.drawCircle(151,151,35,paint);
        // app_process does not inherit Zygote's font preloading. Numeric vector
        // glyphs work without hidden font initialization or a Typeface.DEFAULT.
        paint.setColor(Color.WHITE);paint.setStrokeCap(Paint.Cap.ROUND);
        int[] masks={0x3f,0x06,0x5b,0x4f,0x66,0x6d,0x7d,0x07,0x7f,0x6f};
        float[][] segments={{2,0,12,0},{14,2,14,10},{14,14,14,22},{2,24,12,24},{0,14,0,22},{0,2,0,10},{2,12,12,12}};
        float scale=Math.min(1.8f,54f/(badge.length()*18-4));paint.setStrokeWidth(2.5f*scale);
        float start=151-(badge.length()*18-4)*scale/2,top=151-12*scale;
        for(int i=0;i<badge.length();i++)for(int bit=0;bit<7;bit++)if((masks[badge.charAt(i)-'0']&(1<<bit))!=0) {
            float[] segment=segments[bit];float x=start+i*18*scale;
            canvas.drawLine(x+segment[0]*scale,top+segment[1]*scale,x+segment[2]*scale,top+segment[3]*scale,paint);
        }
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        Failure.require(b.compress(Bitmap.CompressFormat.PNG,100,out),"ICON_FAILED","Could not render icon"); b.recycle(); return out.toByteArray();
    }
    public static String run(int timeout,String...argv) throws Exception {return runInput(timeout,null,argv);}
    public static String runInput(int timeout,File input,String...argv) throws Exception {
        ProcessBuilder pb=new ProcessBuilder(argv).redirectErrorStream(true);
        java.lang.Process p=pb.start();
        if(input==null) p.getOutputStream().close();
        else {
            // Feed a pipe: handing system_server an FD for a root-private regular file can
            // fail Binder's SELinux file receive check even when the caller is root.
            Thread writer=new Thread(()->{
                try(InputStream in=new FileInputStream(input);OutputStream out=p.getOutputStream()) {
                    byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
                } catch(IOException ignored) { /* command output/exit status supplies the failure */ }
            },"command-input");writer.setDaemon(true);writer.start();
        }
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        Thread reader=new Thread(()->{
            try(InputStream in=p.getInputStream()) {byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1) {if(output.size()+n<=4*1024*1024) output.write(b,0,n);}}
            catch(IOException ignored) { }
        },"command-output"); reader.setDaemon(true); reader.start();
        if(!p.waitFor(timeout,TimeUnit.SECONDS)) {p.destroyForcibly();reader.join(1000);throw new Failure("COMMAND_TIMEOUT",argv[0]+" "+argv[1]+" timed out; inspect pending journal");}
        reader.join(2000); String s=output.toString("UTF-8");
        Failure.require(p.exitValue()==0 && !s.matches("(?s).*\\n?Error:.*") && !s.contains("Failure ["),"ANDROID_COMMAND_FAILED",s.trim().isEmpty()?argv[1]+" failed":s.trim());
        return s;
    }
}
