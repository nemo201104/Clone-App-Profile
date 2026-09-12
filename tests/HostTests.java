package io.github.nemo.cap;

import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import io.github.nemo.cap.launcher.ZteSnapshot;

public final class HostTests {
    static int passed;
    static void check(boolean condition,String name) {if(!condition)throw new AssertionError(name);passed++;}
    static void rejects(Callable<?> work,String name) throws Exception {try{work.call();throw new AssertionError(name);}catch(Failure expected){passed++;}}
    public static void main(String[] args) throws Exception {
        String id1=Json.identity("com.example.app",0,10),id2=Json.identity("com.example.app",0,11);
        check(!id1.equals(id2),"siblings have distinct package identities");
        check(!id1.equals(Json.identity("com.example.app",1,10)),"different parent serial");
        check(!id1.equals(Json.identity("com.example.other",0,10)),"different package");
        for(String bad:Arrays.asList("a;id","com.foo$(id)","com.foo`id`","../com.foo","com.foo\nbar","com..foo","--user","", "a.b/Activity")) rejects(()->Json.pkg(bad),"package injection "+bad);
        for(String bad:Arrays.asList("-1","all","current","01","0;id","2147483648","1\n","")) rejects(()->Json.user(bad),"user validation");
        rejects(()->Json.identity("com.example.app",0,0),"owner target rejected");
        String model="  AllAppsStore Apps[] size: 2\n  Package index, name, class, description, bitmap flag: 0/com.example.app:com.example.app.Main, Example, 0+0\n  Package index, name, class, description, bitmap flag: 1/com.example.app:com.example.app.Main, Example, 10000000100+0\nSectionInfo[] size: 1\n";
        JSONArray nativeRows=ZteSnapshot.parse(model),cache=new JSONArray();
        cache.put(Json.obj("component","com.example.app/com.example.app.Main","serial",0,"flags",0));
        cache.put(Json.obj("component","com.example.app/com.example.app.Main","serial",55,"flags",1028));
        cache.put(Json.obj("component","com.example.app/com.example.app.Main","serial",54,"flags",1028)); // stale cache
        Set<Long> live=new HashSet<>(Arrays.asList(0L,55L,56L));
        check("PRESENT".equals(ZteSnapshot.match(nativeRows,cache,"com.example.app",55,live).getString("state")),"native entry joins exact live serial");
        check("ABSENT".equals(ZteSnapshot.match(nativeRows,cache,"com.example.app",56,live).getString("state")),"same package in another profile does not match");
        check("ABSENT".equals(ZteSnapshot.match(nativeRows,cache,"com.example.other",55,live).getString("state")),"different package does not match");
        check("ABSENT".equals(ZteSnapshot.match(ZteSnapshot.parse(model.replace("10000000100+0","100+0")),cache,"com.example.app",55,live).getString("state")),"raw hidden CLONE model rows do not prove personal drawer visibility");
        rejects(()->ZteSnapshot.parse(model.replace("size: 2","size: 3")),"truncated native model cannot prove absence");
        rejects(()->ZteSnapshot.parse(model+model),"ambiguous model headers rejected");
        rejects(()->ZteSnapshot.parse(model.replace("flag: 1/","flag: 0/")),"duplicate model index rejected");
        cache.put(Json.obj("component","com.example.app/com.example.app.Main","serial",56,"flags",1028));
        rejects(()->ZteSnapshot.match(nativeRows,cache,"com.example.app",55,live),"ambiguous profile identity fails closed");
        JSONObject update=new JSONObject(new String(Files.readAllBytes(Paths.get(args[0]).getParent().resolve("update.json")),"UTF-8"));
        check("UP_TO_DATE".equals(UpdateMetadata.evaluate(update,10001).getString("state")),"current release is up to date");
        check("UPDATE_AVAILABLE".equals(UpdateMetadata.evaluate(update,10000).getString("state")),"previous module version detects maintenance update with the migrated validator");
        // Historical owner is retained only as a negative migration fixture.
        JSONObject obsolete=new JSONObject(update.toString().replace("nemoforge","nemo201104"));
        rejects(()->UpdateMetadata.evaluate(obsolete,10001),"old repository owner is not accepted by the migrated updater");
        JSONObject hostile=new JSONObject(update.toString()).put("zipUrl",update.getString("zipUrl")+"/../other.zip");
        rejects(()->UpdateMetadata.evaluate(hostile,10001),"update path traversal rejected");
        rejects(()->UpdateMetadata.evaluate(new JSONObject(update.toString()).put("versionCode","10001"),10001),"noninteger update version rejected");
        Path root=Files.createTempDirectory("cap-host-");
        try(Store s=new Store(root.toFile(),true)) {check(s.data.getJSONArray("clones").length()==0,"initialize");s.data.put("testCounter",0);s.save();}
        JSONObject legacy=Json.obj("packageName","com.example.app","parentSerial",0,"targetSerial",10,"id",id1,"managedByModule",true,"proxyPackage",ProxyApk.PREFIX+id1);
        try(Store s=new Store(root.toFile(),false)) {
            s.data.getJSONArray("clones").put(legacy);s.save();
            legacy.put("launcherMode","PROXY");s.save();
            legacy.put("launcherMode","NATIVE");
            rejects(()->{Store.validate(s.data);return null;},"native and proxy identity cannot coexist in registry");
            legacy.remove("proxyPackage");s.save();
        }
        try(Store s=new Store(root.toFile(),false)) {
            JSONObject restored=s.data.getJSONArray("clones").getJSONObject(0);
            check(restored.getString("launcherMode").equals("NATIVE") && restored.getString("id").equals(id1),"legacy migration persists mode without changing identity");
            restored.put("launcherMode","BOTH");
            rejects(()->{Store.validate(s.data);return null;},"unknown launcher mode rejected");
            s.data.put("clones",new JSONArray());s.save();
        }
        Path props=Files.createTempDirectory("cap-update-");
        Files.write(props.resolve("module.prop"),"id=clone_app_profile\nversionCode=10000\n".getBytes("UTF-8"));
        check(UpdateMetadata.installedVersion(props.toFile())==10000,"update comparison reads previous installed module metadata");
        Files.write(props.resolve("module.prop"),"versionCode=10001x\n".getBytes("UTF-8"));
        rejects(()->UpdateMetadata.installedVersion(props.toFile()),"malformed installed version rejected");
        ExecutorService pool=Executors.newFixedThreadPool(4);List<Future<?>> futures=new ArrayList<>();
        for(int i=0;i<4;i++)futures.add(pool.submit(()->{for(int j=0;j<12;j++)try(Store s=new Store(root.toFile(),false)){s.data.put("testCounter",s.data.getInt("testCounter")+1);s.save();}catch(Exception e){throw new RuntimeException(e);}}));
        for(Future<?> f:futures)f.get();pool.shutdown();
        try(Store s=new Store(root.toFile(),false)){check(s.data.getInt("testCounter")==48,"concurrent writes not lost");}
        Files.write(root.resolve("registry.json"),"broken".getBytes("UTF-8"));
        rejects(()->new Store(root.toFile(),false),"corruption fails closed");
        try(Store s=new Store(root.toFile(),false,true)){check(s.data.getInt("testCounter")>=47,"backup recovery");check(Files.list(root).anyMatch(p->p.getFileName().toString().startsWith("registry.corrupt-")),"damaged bytes preserved");}
        try(Store s=new Store(root.toFile(),false)){
            ProxyApk proxy=new ProxyApk(root.toFile(),new File(args[0]));
            byte[] icon=Files.readAllBytes(Paths.get(args[1]));
            File one=proxy.build(id1,"Zalo — Clone 1 · Unicode thử nghiệm",icon);
            File two=proxy.build(id2,"Zalo — Clone 2",icon);
            check(one.isFile() && two.isFile(),"distinct signed proxy APKs generated");
            com.android.apksig.ApkVerifier.Result verify=new com.android.apksig.ApkVerifier.Builder(one).build().verify();
            check(verify.isVerified() && verify.isVerifiedUsingV2Scheme(),"APK v2 signatures valid");
            check(Json.hash(verify.getSignerCertificates().get(0).getEncoded()).equals(proxy.certificate()),"persistent signing certificate");
            Files.copy(one.toPath(),Paths.get(args[2]),StandardCopyOption.REPLACE_EXISTING);
            try(ZipFile z=new ZipFile(one)) {check(Arrays.equals(icon,read(z.getInputStream(z.getEntry("res/drawable/app_icon.png")))),"source icon preserved");}
        }
        System.out.println("Host checks passed: "+passed+"; artifacts: "+root);
    }
    static byte[] read(InputStream in)throws Exception {try(InputStream source=in){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] part=new byte[4096];int n;while((n=source.read(part))!=-1)b.write(part,0,n);return b.toByteArray();}}
}
