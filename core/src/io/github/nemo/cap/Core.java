package io.github.nemo.cap;

import org.json.*;
import android.content.pm.*;
import android.os.Build;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.net.*;
import javax.net.ssl.HttpsURLConnection;
import java.util.*;
import java.util.regex.*;

public final class Core {
    public static final String UPDATE="https://github.com/nemoforge/Clone-App-Profile/releases/latest/download/update.json";
    public static final File DATA=new File("/data/adb/clone_app_profile");
    private final AndroidPlatform p;private final Store s;private final File module;private final LauncherIntegration launcher;
    private final String op=UUID.randomUUID().toString();
    Core(AndroidPlatform p,Store s,File module) {this.p=p;this.s=s;this.module=module;launcher=new LauncherIntegration(p,s,module);}
    private JSONArray profiles() throws Exception {
        JSONArray all=p.profiles();
        for(int i=0;i<all.length();i++) {
            JSONObject u=all.getJSONObject(i),r=Json.find(s.data.getJSONArray("profiles"),"userId",u.getInt("userId"));int count=0;
            JSONArray clones=s.data.getJSONArray("clones");
            for(int j=0;j<clones.length();j++)if(clones.getJSONObject(j).getLong("targetSerial")==u.getLong("serialNumber") && !clones.getJSONObject(j).optBoolean("packageRemoved"))count++;
            u.put("moduleManaged",r!=null && r.getLong("serialNumber")==u.getLong("serialNumber")).put("clonedApps",count);
            if(AndroidPlatform.CLONE.equals(u.getString("type")))u.put("profileClassification",launcher.profileClass(u,u.getBoolean("moduleManaged")));
        }return all;
    }
    private JSONObject status() throws Exception {
        JSONArray all=profiles();int max=p.maxUsers(),current=p.currentUser(),count=0;
        JSONArray clones=s.data.getJSONArray("clones");for(int i=0;i<clones.length();i++)if(!"MISSING".equals(clones.getJSONObject(i).optString("state")) && !clones.getJSONObject(i).optBoolean("packageRemoved"))count++;
        return Json.obj("used",all.length(),"max",max,"cloned",count,"description","Profile: "+all.length()+"/"+max+" | Cloned: "+count,
            "android",Build.VERSION.RELEASE,"sdk",Build.VERSION.SDK_INT,"currentUser",current,"cloneSupport",p.support(current),
            "profiles",all,"pending",s.data.getJSONArray("pending"),"kernelSU",new File("/data/adb/ksu").isDirectory(),"moduleVersion","v1.0.2");
    }
    private void metadata() throws Exception {
        JSONObject st=status();File prop=new File(module,"module.prop");String text=Store.read(prop);
        Failure.require(text.contains("id=clone_app_profile"),"MODULE_IDENTITY_MISMATCH","Wrong module metadata");
        String next=text.replaceAll("(?m)^description=.*$",Matcher.quoteReplacement("description="+st.getString("description")));
        Store.atomic(prop,next.getBytes(StandardCharsets.UTF_8));
    }
    private JSONObject target(int id) throws Exception {
        JSONObject u=p.requireUser(id);
        Failure.require(id>0 && AndroidPlatform.CLONE.equals(u.getString("type")) && !u.getBoolean("partial") && u.getBoolean("enabled"),"TARGET_NOT_CLONE","Select an enabled CLONE profile");
        Failure.require(u.getInt("parentUserId")==p.currentUser(),"INVALID_PARENT","Clone must belong to current foreground parent");
        return u;
    }
    private JSONObject managedProfile(int id,boolean allowMissing) throws Exception {
        Failure.require(id>0,"PROFILE_NOT_MANAGED","Cannot delete system/owner user");
        JSONObject r=Json.find(s.data.getJSONArray("profiles"),"userId",id);
        Failure.require(r!=null && r.getBoolean("createdByModule"),"PROFILE_NOT_MANAGED","Only module-created profiles can be deleted");
        p.identity(r.getInt("parentUserId"),r.getLong("parentSerial"));
        Failure.require(p.currentUser()==r.getInt("parentUserId"),"INVALID_PARENT","Switch to the profile parent");
        if(p.user(id)!=null) {JSONObject actual=target(id);Failure.require(actual.getLong("serialNumber")==r.getLong("serialNumber"),"PROFILE_IDENTITY_MISMATCH","Refusing reused user ID");}
        else Failure.require(allowMissing,"PROFILE_NOT_FOUND","Profile no longer exists");
        return r;
    }
    private JSONObject managedClone(String pkg,int target) throws Exception {
        JSONArray rows=s.data.getJSONArray("clones");
        for(int i=0;i<rows.length();i++) {
            JSONObject c=rows.getJSONObject(i);
            if(pkg.equals(c.getString("packageName")) && target==c.getInt("targetUserId")) {
                p.identity(c.getInt("sourceUserId"),c.getLong("parentSerial"));
                Failure.require(p.currentUser()==c.getInt("sourceUserId"),"INVALID_PARENT","Switch to the clone's parent");
                if(p.user(target)!=null)launcher.validateTarget(c);return c;
            }
        }throw new Failure("PACKAGE_NOT_MANAGED","No module ownership record for this package/profile");
    }
    private void noPending() throws Exception {
        Failure.require(s.data.getJSONArray("pending").length()==0,"RECOVERY_REQUIRED","An interrupted operation needs inspection; see doctor and docs/RECOVERY.md");
    }
    private JSONObject pending(String action,Object...details) throws Exception {
        JSONObject r=Json.obj(details).put("action",action).put("operationId",op).put("createdAt",Instant.now().toString());
        s.data.getJSONArray("pending").put(r);s.save();return r;
    }
    private void complete(JSONObject pending) throws Exception {Json.remove(s.data.getJSONArray("pending"),pending);s.save();}
    private JSONObject create() throws Exception {
        noPending();int parent=p.currentUser();JSONObject par=p.requireUser(parent);
        Failure.require(par.getBoolean("canHaveProfile") && !par.getBoolean("partial") && par.getBoolean("unlocked"),"INVALID_PARENT","Foreground user cannot host a clone profile now");
        JSONObject support=p.support(parent);
        Failure.require("SUPPORTED".equals(support.getString("state")),"CLONE_PROFILE_UNSUPPORTED","ROM clone type unavailable or not verifiable");
        Failure.require(p.profiles().length()<p.maxUsers(),"MAX_USERS_REACHED","Runtime total user limit reached");
        p.requirePm("create-user");p.requirePm("--profileOf");p.requirePm("--user-type");p.requirePm("remove-user");p.requireAm("start-user");
        JSONArray existingUsers=p.profiles();int number=1;
        while(Json.find(existingUsers,"name","Clone "+number)!=null)number++;
        String name="Clone "+number;
        JSONArray before=p.profiles();JSONObject journal=pending("CREATE_PROFILE","parentUserId",parent,"parentSerial",par.getLong("serialNumber"),"name",name,"beforeUsers",before);
        JSONObject created=null;
        try {
            // Capacity APIs are advisory on customized ROMs; create-user is the authoritative final check.
            String out=AndroidPlatform.run(40,"/system/bin/pm","create-user","--profileOf",Integer.toString(parent),"--user-type",AndroidPlatform.CLONE,name);
            Matcher m=Pattern.compile("(?m)^Success: created user id ([0-9]+)\\s*$").matcher(out);
            Failure.require(m.find(),"PROFILE_CREATE_FAILED","Unrecognized create result; inspect pending journal");int id=Json.user(m.group(1));
            Failure.require(Json.find(before,"userId",id)==null,"PROFILE_IDENTITY_MISMATCH","Returned user existed before operation");
            created=target(id);
            Failure.require(name.equals(created.getString("name")),"PROFILE_IDENTITY_MISMATCH","Created profile name does not match operation");
            journal.put("createdUser",created);s.save();
            p.startProfile(id,created.getLong("serialNumber"));created=target(id);
            JSONObject record=Json.obj("userId",id,"serialNumber",created.getLong("serialNumber"),"type",AndroidPlatform.CLONE,
                "parentUserId",parent,"parentSerial",par.getLong("serialNumber"),"name",name,"createdAt",Instant.now().toString(),"createdByModule",true,"state","ACTIVE");
            s.data.getJSONArray("profiles").put(record);complete(journal);return record;
        } catch(Exception e) {
            if(created!=null) {
                try {
                    JSONObject actual=p.identity(created.getInt("userId"),created.getLong("serialNumber"));
                    Failure.require(name.equals(actual.getString("name")) && actual.getInt("parentUserId")==parent && AndroidPlatform.CLONE.equals(actual.getString("type")),"ROLLBACK_UNSAFE","Rollback identity changed");
                    deleteUser(actual.getInt("userId"),actual.getLong("serialNumber"));
                    JSONObject added=Json.find(s.data.getJSONArray("profiles"),"userId",actual.getInt("userId"));
                    if(added!=null && added.getLong("serialNumber")==actual.getLong("serialNumber"))Json.remove(s.data.getJSONArray("profiles"),added);
                    complete(journal);
                    s.log(op,"RECOVERY","",actual.getInt("userId"),"ROLLED_BACK","New profile removed after initialization failure");
                } catch(Exception rollback) {journal.put("rollbackError",rollback.toString());s.save();}
            } else {
                // No ownership is inferred from a package/profile appearing after a failed command.
                journal.put("error",e.toString());s.save();
            }
            throw new Failure("PROFILE_CREATE_FAILED",e.toString());
        }
    }
    private void deleteUser(int id,long serial) throws Exception {
        p.identity(id,serial);p.requirePm("remove-user");
        AndroidPlatform.run(45,"/system/bin/pm","remove-user",Integer.toString(id));
        long until=System.nanoTime()+30_000_000_000L;
        while(p.user(id)!=null && System.nanoTime()<until) {p.identity(id,serial);Thread.sleep(300);}
        Failure.require(p.user(id)==null,"PROFILE_DELETE_FAILED","Profile removal did not finish");
    }
    private JSONObject cloneApp(String pkg,int id) throws Exception {
        noPending();JSONObject target=target(id);int source=target.getInt("parentUserId");JSONObject parent=p.requireUser(source);
        JSONObject ownedProfile=Json.find(s.data.getJSONArray("profiles"),"userId",id);
        Failure.require(ownedProfile==null || !"DELETING".equals(ownedProfile.optString("state")),"RECOVERY_REQUIRED","Profile deletion must complete before adding clones");
        ApplicationInfo a=p.app(pkg,source);
        Failure.require(a!=null,"PACKAGE_NOT_FOUND","Package is not installed for parent");
        Failure.require((a.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))==0 && !pkg.startsWith(ProxyApk.PREFIX),"SYSTEM_PACKAGE_FORBIDDEN","Select a user-installed app");
        Failure.require(a.enabled && p.resolve(pkg,source)!=null,"PACKAGE_NOT_LAUNCHABLE","Source app has no enabled launcher activity");
        String identity=Json.identity(pkg,parent.getLong("serialNumber"),target.getLong("serialNumber"));
        JSONObject existing=Json.find(s.data.getJSONArray("clones"),"id",identity);
        if(existing!=null) {
            Failure.require("ACTIVE".equals(existing.optString("state")),"RECOVERY_REQUIRED","Clone is being removed or missing; resolve that state before cloning again");
            boolean ready=launcher.ensure(existing,true);return cloneResult(existing,ready);
        }
        Failure.require(p.app(pkg,id)==null,"PACKAGE_ALREADY_PRESENT","Package already exists outside module ownership; it was not adopted");
        p.requirePm("install-existing");p.requirePm("--user");p.requirePm("enable");
        p.startProfile(id,target.getLong("serialNumber"));
        JSONObject journal=pending("CLONE_APP","packageName",pkg,"targetUserId",id,"targetSerial",target.getLong("serialNumber"),"sourceUserId",source,"parentSerial",parent.getLong("serialNumber"));
        try {
            AndroidPlatform.run(45,"/system/bin/pm","install-existing","--user",Integer.toString(id),pkg);
            p.identity(id,target.getLong("serialNumber"));
            AndroidPlatform.run(15,"/system/bin/pm","enable","--user",Integer.toString(id),pkg);
            JSONObject verified=p.verifyClone(pkg,source,id);PackageInfo pi=p.pm(source).getPackageInfo(pkg,0);
            JSONObject c=Json.obj("id",identity,"packageName",pkg,"appLabel",p.pm(source).getApplicationLabel(a).toString(),
                "sourceUserId",source,"targetUserId",id,"parentSerial",parent.getLong("serialNumber"),"targetSerial",target.getLong("serialNumber"),
                "clonedAt",Instant.now().toString(),"operationId",op,"versionCode",pi.getLongVersionCode(),"versionName",pi.versionName,
                "managedByModule",true,"state","ACTIVE","packageCloned","Success","packageVerification",verified,
                "targetFirstInstallTime",p.pm(id).getPackageInfo(pkg,0).firstInstallTime,"targetCertificate",p.certificate(pkg,id),
                "launcherEntryState","PENDING","launcherIntegration","Failed");
            s.data.getJSONArray("clones").put(c);complete(journal);
            boolean ready=launcher.ensure(c,true);return cloneResult(c,ready);
        } catch(Exception e) {
            if(p.user(id)!=null && p.identity(id,target.getLong("serialNumber"))!=null && p.app(pkg,id)==null)complete(journal);
            else {journal.put("error",e.toString());s.save();}
            throw new Failure("PACKAGE_INSTALL_FAILED",e.toString());
        }
    }
    private JSONObject cloneResult(JSONObject c,boolean ready) throws Exception {
        return Json.obj("operationStatus",ready?"SUCCESS":"PARTIAL_SUCCESS_LAUNCHER_INTEGRATION_FAILED","packageCloned",c.optString("packageCloned","Success"),
            "launcherIntegration",ready?"Success":"Failed","targetProfile",Json.obj("userId",c.getInt("targetUserId"),"serialNumber",c.getLong("targetSerial")),
            "launcherEntryState",c.getString("launcherEntryState"),"launcherMode",c.optString("launcherMode","UNASSIGNED"),"clone",c);
    }
    private JSONObject removeClone(String pkg,int id) throws Exception {
        JSONObject c=managedClone(pkg,id);c.put("state","REMOVING");s.save();
        launcher.cleanup(c); // Entry first: no stale icon may route after data removal.
        if(p.user(id)!=null && p.app(pkg,id)!=null) {
            launcher.validateTarget(c);launcher.validatePackageOwnership(c);p.requirePm("uninstall");
            AndroidPlatform.run(45,"/system/bin/pm","uninstall","--user",Integer.toString(id),pkg);
            Failure.require(p.app(pkg,id)==null,"PACKAGE_REMOVE_FAILED","Package remains installed in target");
        }
        c.put("packageRemoved",true);s.save();
        if(!launcher.verifyNativeRemoved(c))return Json.obj("operationStatus","PARTIAL_SUCCESS_LAUNCHER_REMOVAL_PENDING","packageRemoved",true,
            "launcherEntryState","PENDING_REMOVAL","targetUserId",id,"packageName",pkg,"message","Package removed; HOME entry cleanup is still pending. Retry removal.");
        Json.remove(s.data.getJSONArray("clones"),c);s.save();return Json.obj("removed",true,"targetUserId",id,"packageName",pkg);
    }
    private JSONObject recoverPending(String operationId) throws Exception {
        Failure.require(operationId.matches("[a-f0-9-]{36}"),"INVALID_ARGUMENT","Expected operationId from doctor");
        JSONObject journal=Json.find(s.data.getJSONArray("pending"),"operationId",operationId);
        Failure.require(journal!=null,"OPERATION_NOT_FOUND","No such pending operation");
        String action=journal.getString("action");
        if(action.equals("CREATE_PROFILE")) {
            if(journal.has("createdUser")) {
                JSONObject created=journal.getJSONObject("createdUser");JSONObject actual=p.user(created.getInt("userId"));
                Failure.require(actual==null,"RECOVERY_REQUIRES_INSPECTION","A created profile still exists. Inspect the journal; never adopt or delete uncertain ownership automatically.");
            } else {
                JSONArray before=journal.getJSONArray("beforeUsers"),now=p.profiles();
                for(int i=0;i<now.length();i++)Failure.require(Json.find(before,"serialNumber",now.getJSONObject(i).getInt("serialNumber"))!=null,
                    "RECOVERY_REQUIRES_INSPECTION","A new user exists since the interrupted operation; cannot discard its journal safely");
            }
        } else if(action.equals("CLONE_APP")) {
            JSONObject actual=p.user(journal.getInt("targetUserId"));
            if(actual!=null) {
                p.identity(actual.getInt("userId"),journal.getLong("targetSerial"));
                Failure.require(p.app(journal.getString("packageName"),actual.getInt("userId"))==null,"RECOVERY_REQUIRES_INSPECTION","An unverified package is present; retain journal and inspect manually");
            }
        } else throw new Failure("RECOVERY_REQUIRES_INSPECTION","Unknown journal action");
        complete(journal);return Json.obj("cleared",operationId,"reason","Verified operation has no remaining Android resources");
    }
    private JSONObject deleteProfile(int id) throws Exception {
        JSONObject profile=managedProfile(id,true);profile.put("state","DELETING");s.save();
        JSONArray clones=s.data.getJSONArray("clones");
        for(int i=0;i<clones.length();i++) {
            JSONObject c=clones.getJSONObject(i);
            if(c.getInt("targetUserId")==id && c.getLong("targetSerial")==profile.getLong("serialNumber")) {c.put("state","REMOVING");s.save();launcher.cleanup(c);}
        }
        if(p.user(id)!=null)deleteUser(id,profile.getLong("serialNumber"));
        for(int i=0;i<clones.length();i++) {
            JSONObject c=clones.getJSONObject(i);
            if(c.getInt("targetUserId")==id && c.getLong("targetSerial")==profile.getLong("serialNumber") && !launcher.verifyNativeRemoved(c))
                return Json.obj("operationStatus","PARTIAL_SUCCESS_LAUNCHER_REMOVAL_PENDING","profileRemoved",true,"targetUserId",id,"launcherEntryState","PENDING_REMOVAL");
        }
        for(int i=clones.length()-1;i>=0;i--)if(clones.getJSONObject(i).getInt("targetUserId")==id && clones.getJSONObject(i).getLong("targetSerial")==profile.getLong("serialNumber"))clones.remove(i);
        Json.remove(s.data.getJSONArray("profiles"),profile);s.save();return Json.obj("removed",true,"targetUserId",id);
    }
    private JSONArray apps() throws Exception {
        int user=p.currentUser();JSONArray apps=new JSONArray();PackageManager pm=p.pm(user);
        List<ApplicationInfo> list=pm.getInstalledApplications(0);list.sort(Comparator.comparing(a->a.packageName));
        for(ApplicationInfo a:list) if((a.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))==0 && a.enabled && !a.packageName.startsWith(ProxyApk.PREFIX) && p.resolve(a.packageName,user)!=null)
            apps.put(Json.obj("packageName",a.packageName,"appLabel",pm.getApplicationLabel(a).toString()));
        return apps;
    }
    private JSONObject reconcile() throws Exception {
        JSONArray rows=s.data.getJSONArray("clones"),results=new JSONArray();
        boolean partial=false,removalPending=false;
        for(int i=0;i<rows.length();i++) {
            JSONObject c=rows.getJSONObject(i);
            if("REMOVING".equals(c.optString("state"))) {
                removalPending=true;
                results.put(Json.obj("id",c.getString("id"),"state","REMOVING","launcherEntryState",c.optString("launcherEntryState"),
                    "operationStatus","PARTIAL_SUCCESS_LAUNCHER_REMOVAL_PENDING","message","Retry Remove Cloned or Delete Profile to finish the recorded removal."));
                continue;
            }
            if(!"ACTIVE".equals(c.optString("state")))continue;
            try {
                JSONObject current=p.user(c.getInt("targetUserId"));
                if(current==null || current.getLong("serialNumber")!=c.getLong("targetSerial")) {
                    c.put("state","MISSING");s.save();launcher.cleanup(c);results.put(Json.obj("id",c.getString("id"),"state","MISSING"));continue;
                }
                if(p.app(c.getString("packageName"),c.getInt("targetUserId"))==null) {c.put("state","MISSING");s.save();launcher.cleanup(c);continue;}
                if(p.currentUser()!=c.getInt("sourceUserId") || !p.requireUser(c.getInt("sourceUserId")).getBoolean("unlocked"))continue;
                if("FAILED".equals(c.optString("launcherEntryState")) && c.optLong("nextLauncherRetry")>System.currentTimeMillis()) {partial=true;continue;}
                // Never re-install a removed user package. Only recover the module's launcher representation.
                boolean ready=launcher.ensure(c,false);partial|=!ready;results.put(cloneResult(c,ready));
            } catch(Exception e) {partial=true;c.put("launcherEntryState","FAILED").put("launcherIntegration","Failed").put("launcherError",e.toString());s.save();results.put(Json.obj("id",c.getString("id"),"error",e.toString()));}
        }
        metadata();return Json.obj("operationStatus",partial?"PARTIAL_SUCCESS_LAUNCHER_INTEGRATION_FAILED":removalPending?"PARTIAL_SUCCESS_LAUNCHER_REMOVAL_PENDING":"SUCCESS","results",results);
    }
    private JSONArray logs() throws Exception {
        JSONArray out=new JSONArray();File file=new File(s.dir,"events.jsonl");
        if(file.exists()) {String[] lines=Store.read(file).split("\n");for(int i=Math.max(0,lines.length-300);i<lines.length;i++)if(!lines[i].isEmpty())out.put(new JSONObject(lines[i]));}return out;
    }
    private JSONObject update() throws Exception {
        HttpsURLConnection connection=(HttpsURLConnection)new URL(UPDATE).openConnection();connection.setConnectTimeout(10000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(true);
        try {
            Failure.require(connection.getResponseCode()==200,"UPDATE_CHECK_FAILED","Update metadata unavailable (HTTP "+connection.getResponseCode()+")");
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();try(InputStream in=connection.getInputStream()) {byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1) {Failure.require(buffer.size()+n<=65536,"INVALID_UPDATE_METADATA","Metadata too large");buffer.write(b,0,n);}}
            JSONObject data=new JSONObject(buffer.toString("UTF-8"));
            return UpdateMetadata.evaluate(data,UpdateMetadata.installedVersion(module));
        } finally {connection.disconnect();}
    }
    public JSONObject execute(String[] args) throws Exception {
        String cmd=args[0];Object details;boolean mutates=false;
        if(!Arrays.asList("profile-delete","recover-pending","clone","clone-remove","launcher-retry").contains(cmd))arity(args,1);
        switch(cmd) {
            case "status": details=status();break;
            case "profiles":details=profiles();break;
            case "apps":details=apps();break;
            case "clones":details=s.data.getJSONArray("clones");break;
            case "logs":details=logs();break;
            case "doctor":
                details=Json.obj("status",status(),"registryIntegrity","VALID","storageWritable",s.dir.canWrite(),"frameworkBootstrap",true,"webUIBackendReady",new File(module,"webroot/index.html").isFile(),"pending",s.data.getJSONArray("pending"));
                try {Broker.ping();((JSONObject)details).put("broker","READY");}catch(Exception e){((JSONObject)details).put("broker",e.getMessage());}
                p.requirePm("install-existing");p.requirePm("create-user");p.requirePm("uninstall");break;
            case "profile-create":details=create();mutates=true;break;
            case "recover-pending":arity(args,2);details=recoverPending(args[1]);mutates=true;break;
            case "profile-delete":arity(args,2);details=deleteProfile(Json.user(args[1]));mutates=true;break;
            case "clone":arity(args,3);details=cloneApp(Json.pkg(args[1]),Json.user(args[2]));mutates=true;break;
            case "clone-remove":arity(args,3);details=removeClone(Json.pkg(args[1]),Json.user(args[2]));mutates=true;break;
            case "launcher-retry":arity(args,3);JSONObject c=managedClone(Json.pkg(args[1]),Json.user(args[2]));
                Failure.require("ACTIVE".equals(c.optString("state")),"RECOVERY_REQUIRED","Finish pending removal before retrying launcher integration");
                details=cloneResult(c,launcher.ensure(c,true));mutates=true;break;
            case "reconcile":details=reconcile();mutates=true;break;
            case "update-check":
                try {details=update();}
                catch(JSONException e){throw new Failure("INVALID_UPDATE_METADATA",e.getMessage());}
                catch(IOException e){throw new Failure("UPDATE_CHECK_FAILED","Network error: "+e.getMessage());}
                s.log(op,"UPDATE_CHECK","",-1,"SUCCESS",((JSONObject)details).getString("state"));break;
            case "export-log":
                File dir=new File("/sdcard/Cloned-App-Profile/Logs");Files.createDirectories(dir.toPath());
                File export=new File(dir,"log-"+Instant.now().toString().replaceAll("[^0-9TZ]","-")+"-Clone-App-Profile.txt");
                // No caller-supplied filename or path; public storage receives logs only.
                try(OutputStream out=Files.newOutputStream(export.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {out.write(logs().toString(2).getBytes(StandardCharsets.UTF_8));}
                details=Json.obj("path",export.getAbsolutePath());break;
            default:throw new Failure("INVALID_ARGUMENT","Unknown command: "+cmd);
        }
        boolean partial=details instanceof JSONObject && ((JSONObject)details).optString("operationStatus").startsWith("PARTIAL_SUCCESS");
        if(mutates) {
            int logUser=-1;
            if(args.length>1 && args[args.length-1].matches("[0-9]{1,9}"))logUser=Integer.parseInt(args[args.length-1]);
            if(cmd.equals("profile-create"))logUser=((JSONObject)details).getInt("userId");
            String action=cmd.equals("clone")?"CLONE_APP":cmd.equals("clone-remove")?"REMOVE_CLONED":cmd.toUpperCase(Locale.ROOT).replace('-','_');
            s.log(op,action,args.length==3?args[1]:"",logUser,partial?((JSONObject)details).getString("operationStatus"):"SUCCESS",cmd);
            try{metadata();}catch(Exception e){s.log(op,"ERROR","",-1,"STATUS_UPDATE_FAILED",e.toString());}
        }
        String resultCode=partial?((JSONObject)details).getString("operationStatus"):"SUCCESS";
        return Json.obj("success",!partial,"errorCode",resultCode,"message",partial?((JSONObject)details).optString("message",resultCode):"Completed","details",details,"operationId",op);
    }
    private static void arity(String[] args,int length)throws Failure {Failure.require(args.length==length,"INVALID_ARGUMENT","Invalid argument count");}
    public static void main(String[] argv) {
        String op=UUID.randomUUID().toString();int exit=0;
        try {
            Failure.require(android.os.Process.myUid()==0,"ROOT_REQUIRED","Run through KernelSU-Next");
            Failure.require(argv.length>=2,"INVALID_ARGUMENT","Internal module path and command required");
            File module=new File(argv[0]).getCanonicalFile();String[] args=Arrays.copyOfRange(argv,1,argv.length);
            Failure.require(new File(module,"module.prop").isFile(),"MODULE_NOT_FOUND","Module directory missing");
            if(args[0].equals("version"))System.out.println(Json.obj("success",true,"errorCode","SUCCESS","message","v1.0.2","details",Json.obj("version","v1.0.2","versionCode",10002),"operationId",op));
            else if(args[0].equals("doctor") && !new File(DATA,"registry.json").exists()) {
                AndroidPlatform p=new AndroidPlatform();
                System.out.println(Json.obj("success",true,"errorCode","SUCCESS","message","Read-only preinstall diagnostics","operationId",op,
                    "details",Json.obj("sdk",Build.VERSION.SDK_INT,"currentUser",p.currentUser(),"users",p.profiles(),"maxUsers",p.maxUsers(),
                        "cloneSupport",p.support(p.currentUser()),"home",p.home(p.currentUser()),"registryIntegrity","NOT_INITIALIZED")));
            }
            else if(args[0].equals("init")) {
                try(Store store=new Store(DATA,true)) {store.log(op,"MODULE_UPDATE","",-1,"SUCCESS","Registry preserved/initialized; no profiles or apps created");}
                System.out.println(Json.obj("success",true,"errorCode","SUCCESS","message","Initialized","details",new JSONObject(),"operationId",op));
            } else if(args[0].equals("broker")) {Broker.serve(new AndroidPlatform(),DATA,module);}
            else if(args[0].equals("service")) {Daemon.run(new AndroidPlatform(),DATA,module);}
            else {
                Failure.require(!new File(module,"disable").exists() && !new File(module,"remove").exists(),"MODULE_DISABLED","Module disabled");
                AndroidPlatform p=new AndroidPlatform();
                try(Store store=new Store(DATA,false,args[0].equals("registry-recover"))) {
                    if(args[0].equals("registry-recover"))args=new String[]{"doctor"};
                    Core core=new Core(p,store,module);
                    op=core.op;
                    try {JSONObject result=core.execute(args);System.out.println(result);if(!result.getBoolean("success"))exit=2;}
                    catch(Exception e) {store.log(op,"ERROR",args.length==3?args[1]:"",-1,e instanceof Failure?((Failure)e).code:"INTERNAL_ERROR",e.toString());throw e;}
                }
            }
        } catch(Throwable e) {
            try {System.out.println(Json.obj("success",false,"errorCode",e instanceof Failure?((Failure)e).code:"INTERNAL_ERROR","message",String.valueOf(e.getMessage()),"details",new JSONObject(),"operationId",op));}
            catch(Exception ignored) {System.out.println("{\"success\":false,\"errorCode\":\"SERIALIZATION_FAILED\"}");}
            exit=e instanceof Failure && ((Failure)e).code.equals("SERVICE_ALREADY_RUNNING")?3:1;
        }
        System.exit(exit);
    }
}
