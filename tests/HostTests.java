package io.github.nemo.cap;

import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

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
        Path root=Files.createTempDirectory("cap-host-");
        try(Store s=new Store(root.toFile(),true)) {check(s.data.getJSONArray("clones").length()==0,"initialize");s.data.put("testCounter",0);s.save();}
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
