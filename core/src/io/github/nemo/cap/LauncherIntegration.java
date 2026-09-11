package io.github.nemo.cap;

import io.github.nemo.cap.launcher.*;
import android.content.pm.ApplicationInfo;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

public final class LauncherIntegration {
    private final AndroidPlatform platform;
    private final Store store;
    private final ProxyApk apk;
    private final File module;
    public LauncherIntegration(AndroidPlatform platform,Store store,File module) {this.platform=platform;this.store=store;this.module=module;this.apk=new ProxyApk(store.dir,module);}
    public JSONObject inspect(JSONObject clone) throws Exception {
        int parent=clone.getInt("sourceUserId"),target=clone.getInt("targetUserId");
        JSONObject home=platform.home(parent);
        LauncherAdapter adapter=ZteAdapter.matches(home)?new ZteAdapter():new GenericAndroidAdapter();
        return Json.obj("home",home,"adapter",adapter.inspect(platform,home,parent),
            "native",platform.launcherQuery(clone.getString("packageName"),target,parent));
    }
    public void validateTarget(JSONObject c) throws Exception {
        JSONObject target=platform.identity(c.getInt("targetUserId"),c.getLong("targetSerial"));
        platform.identity(c.getInt("sourceUserId"),c.getLong("parentSerial"));
        Failure.require(AndroidPlatform.CLONE.equals(target.getString("type")) && target.getInt("parentUserId")==c.getInt("sourceUserId")
            && !target.getBoolean("partial") && target.getBoolean("enabled"),"PROFILE_IDENTITY_MISMATCH","Target must remain the same enabled clone profile and parent");
    }
    public void validatePackageOwnership(JSONObject c) throws Exception {
        int target=c.getInt("targetUserId");String pkg=c.getString("packageName");
        android.content.pm.PackageInfo info=platform.pm(target).getPackageInfo(pkg,0);
        Failure.require(c.has("targetFirstInstallTime") && info.firstInstallTime==c.getLong("targetFirstInstallTime")
            && platform.certificate(pkg,target).equals(c.getString("targetCertificate")),"PACKAGE_IDENTITY_MISMATCH","Target package installation/signature changed; ownership requires inspection");
    }
    public void verifyProxy(JSONObject c) throws Exception {
        String expected=ProxyApk.PREFIX+c.getString("id");
        Failure.require(expected.equals(c.getString("proxyPackage")),"PROXY_IDENTITY_MISMATCH","Invalid registered proxy package");
        ApplicationInfo a=platform.app(expected,c.getInt("sourceUserId"));
        Failure.require(a!=null && a.enabled && platform.certificate(expected,c.getInt("sourceUserId")).equals(c.getString("proxyCertificate")),"PROXY_IDENTITY_MISMATCH","Proxy missing, disabled or signed by another key");
    }
    public boolean ensure(JSONObject c,boolean forceProbe) throws Exception {
        try {
            validateTarget(c);
            validatePackageOwnership(c);
            platform.startProfile(c.getInt("targetUserId"),c.getLong("targetSerial"));
            c.put("packageVerification",platform.verifyClone(c.getString("packageName"),c.getInt("sourceUserId"),c.getInt("targetUserId")));
            platform.component(c.getString("packageName"),c.getInt("targetUserId"));
            JSONObject capabilities=inspect(c);c.put("launcherCapabilities",capabilities);
            Failure.require(capabilities.getJSONObject("adapter").getBoolean("managedProxySupported"),"LAUNCHER_UNSUPPORTED","No safe launcher adapter available");
            Broker.ping();
            String pkg=ProxyApk.PREFIX+c.getString("id");int parent=c.getInt("sourceUserId");
            ApplicationInfo existing=platform.app(pkg,parent);
            android.content.pm.PackageInfo sourceInfo=platform.pm(parent).getPackageInfo(c.getString("packageName"),0);
            String label=platform.pm(parent).getApplicationLabel(sourceInfo.applicationInfo).toString()+" — "+platform.requireUser(c.getInt("targetUserId")).getString("name")+" (u"+c.getInt("targetUserId")+")";
            String contentFingerprint=Json.hash("profile-badge-v1:"+sourceInfo.getLongVersionCode()+":"+sourceInfo.lastUpdateTime+":"+label+":"+Json.hash(Files.readAllBytes(new File(module,"lib/proxy-template.apk").toPath())));
            String certificate=apk.certificate();
            c.put("proxyPackage",pkg).put("proxyCertificate",certificate);
            if(existing!=null) verifyProxy(c);
            if(existing==null || !contentFingerprint.equals(c.optString("proxyContentFingerprint"))) {
                // Check every user before installing/replacing a globally named package.
                JSONArray users=platform.profiles();
                for(int i=0;i<users.length();i++) {
                    int id=users.getJSONObject(i).getInt("userId");
                    Failure.require(id==parent || platform.app(pkg,id)==null,"PROXY_PACKAGE_COLLISION","Proxy package exists outside registered parent");
                }
                c.put("launcherEntryState","INSTALLING_PROXY");store.save();
                File file=apk.build(c.getString("id"),label,platform.icon(c.getString("packageName"),parent,Integer.toString(c.getInt("targetUserId"))));
                platform.requirePm("install ");
                // AOSP doRunInstall treats an empty file list as stdin. A bare '-' is
                // parsed as an option by some ROMs' makeInstallParams.
                AndroidPlatform.runInput(60,file,"/system/bin/pm","install","--user",Integer.toString(parent),"-r","-S",Long.toString(file.length()));
                verifyProxy(c);forceProbe=true;c.put("proxyLabel",label).put("proxyContentFingerprint",contentFingerprint);
            }
            platform.component(pkg,parent);
            JSONObject enumeration=platform.launcherQuery(pkg,parent,parent);
            Failure.require(enumeration.getBoolean("enumerated"),"PROXY_NOT_ENUMERATED","Parent LauncherApps does not enumerate the proxy");
            String fingerprint=Json.hash(capabilities.getJSONObject("home").toString());
            if(!fingerprint.equals(c.optString("homeFingerprint")))forceProbe=true;
            if(forceProbe || !"READY_PROXY".equals(c.optString("launcherEntryState"))) {
                String nonce=Json.hash(UUID.randomUUID().toString());
                c.put("probeNonce",nonce).put("probeStarted",System.currentTimeMillis()).put("launcherEntryState","PROBING_PROXY");store.save();
                File proofs=new File(store.dir,"probes");Files.createDirectories(proofs.toPath());File proof=new File(proofs,nonce+".json");
                platform.requireAm("start ");
                AndroidPlatform.run(20,"/system/bin/am","start","--user",Integer.toString(parent),"-W","-n",pkg+"/"+ProxyApk.ACTIVITY,"--es","cap_probe",nonce);
                long until=System.nanoTime()+12_000_000_000L;
                while(!proof.exists() && System.nanoTime()<until)Thread.sleep(150);
                Failure.require(proof.exists(),"PROXY_TRANSPORT_FAILED","Proxy could not authenticate to root broker; inspect SELinux and service logs");
                JSONObject result=new JSONObject(Store.read(proof));
                Failure.require(c.getString("id").equals(result.getString("id")) && nonce.equals(result.getString("nonce")),"PROXY_TRANSPORT_FAILED","Invalid probe acknowledgement");
                Files.delete(proof.toPath());c.remove("probeNonce");c.remove("probeStarted");
            }
            c.put("homeFingerprint",fingerprint).put("launcherEntryState","READY_PROXY").put("launcherIntegration","Success")
                .put("launcherEvidence",enumeration).put("launcherCheckedAt",System.currentTimeMillis()).put("state","ACTIVE");
            c.remove("launcherError");store.save();return true;
        } catch(Exception | LinkageError e) {
            c.put("launcherEntryState","FAILED").put("launcherIntegration","Failed").put("launcherError",e.toString());
            c.put("nextLauncherRetry",System.currentTimeMillis()+300000);
            store.save();return false;
        }
    }
    public void cleanup(JSONObject c) throws Exception {
        if(!c.has("proxyPackage"))return;
        int parent=c.getInt("sourceUserId");
        JSONObject actual=platform.user(parent);
        if(actual==null)return;
        platform.identity(parent,c.getLong("parentSerial"));
        String pkg=c.getString("proxyPackage");
        Failure.require(pkg.equals(ProxyApk.PREFIX+c.getString("id")),"PROXY_IDENTITY_MISMATCH","Unsafe cleanup identity");
        if(platform.app(pkg,parent)!=null) {
            verifyProxy(c);platform.requirePm("uninstall");
            AndroidPlatform.run(45,"/system/bin/pm","uninstall","--user",Integer.toString(parent),pkg);
        }
        Failure.require(platform.app(pkg,parent)==null && platform.resolve(pkg,parent)==null,"LAUNCHER_CLEANUP_FAILED","Proxy is still installed or resolvable");
        c.put("launcherEntryState","REMOVED");store.save();
        Files.deleteIfExists(new File(store.dir,"apks/"+c.getString("id")+".apk").toPath());
    }
}
