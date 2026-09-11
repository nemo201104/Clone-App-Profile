package io.github.nemo.cap;

import android.net.*;
import android.content.pm.ApplicationInfo;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** AF_UNIX peer authentication; two fixed app verbs. Never a shell endpoint. */
public final class Broker {
    public static final String SOCKET="clone_app_profile.broker.v1";
    public static void ping() throws Exception {
        try(LocalSocket socket=new LocalSocket()) {
            socket.connect(new LocalSocketAddress(SOCKET,LocalSocketAddress.Namespace.ABSTRACT));socket.setSoTimeout(2000);
            Failure.require(socket.getPeerCredentials().getUid()==0,"BROKER_UNAVAILABLE","Unexpected broker UID");
            socket.getOutputStream().write("PING\n".getBytes(StandardCharsets.US_ASCII));
            Failure.require(new JSONObject(line(socket.getInputStream(),1024)).getBoolean("success"),"BROKER_UNAVAILABLE","Broker ping failed");
        } catch(Exception e) {throw new Failure("BROKER_UNAVAILABLE","Start module service: "+e.getMessage());}
    }
    private static String line(InputStream in,int max) throws Exception {
        ByteArrayOutputStream b=new ByteArrayOutputStream();int c;
        while((c=in.read())!=-1 && c!='\n') {Failure.require(b.size()<max,"INVALID_REQUEST","Request too large");b.write(c);}
        Failure.require(c=='\n',"INVALID_REQUEST","Incomplete request");return b.toString("UTF-8");
    }
    private static JSONObject authenticate(AndroidPlatform p,JSONObject registry,int uid) throws Exception {
        JSONArray rows=registry.getJSONArray("clones");
        JSONObject match=null;
        for(int i=0;i<rows.length();i++) {
            JSONObject c=rows.getJSONObject(i);
            if(!c.has("proxyPackage"))continue;
            String pkg=c.getString("proxyPackage");
            Failure.require(pkg.equals(ProxyApk.PREFIX+c.getString("id")),"REGISTRY_CORRUPT","Invalid proxy mapping");
            ApplicationInfo info=p.app(pkg,c.getInt("sourceUserId"));
            if(info!=null && info.uid==uid) {
                Failure.require(match==null && p.certificate(pkg,c.getInt("sourceUserId")).equals(c.getString("proxyCertificate")),"UNAUTHORIZED","Ambiguous or unsigned caller");
                String[] packages=p.pm(c.getInt("sourceUserId")).getPackagesForUid(uid);
                Failure.require(packages!=null && packages.length==1 && packages[0].equals(pkg),"UNAUTHORIZED","Shared UID is not allowed");match=c;
            }
        }
        Failure.require(match!=null,"UNAUTHORIZED","Caller is not a managed proxy");return match;
    }
    public static void serve(AndroidPlatform p,File dir,File module) throws Exception {
        ThreadPoolExecutor pool=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8));
        try(LocalServerSocket server=new LocalServerSocket(SOCKET)) {
            while(!new File(module,"disable").exists() && !new File(module,"remove").exists()) {
                LocalSocket socket=server.accept();
                try {pool.execute(()->handle(p,dir,module,socket));}
                catch(RejectedExecutionException e) {socket.close();}
            }
        } finally {pool.shutdownNow();}
    }
    private static void handle(AndroidPlatform p,File dir,File module,LocalSocket socket) {
        try(LocalSocket client=socket) {
            JSONObject reply;
            try {
                client.setSoTimeout(3000);int uid=client.getPeerCredentials().getUid();String verb=line(client.getInputStream(),80);
                Failure.require(!new File(module,"disable").exists() && !new File(module,"remove").exists() && new File(module,"module.prop").exists(),"MODULE_DISABLED","Module is disabled or removed");
                if(uid==0 && verb.equals("PING"))reply=Json.obj("success",true);
                else if(verb.startsWith("PROBE ") && verb.substring(6).matches("[a-f0-9]{64}")) {
                    // Atomic snapshot avoids waiting on the installer's registry lock. Probe only writes its unique ack file.
                    JSONObject registry=new JSONObject(Store.read(new File(dir,"registry.json")));Store.validate(registry);
                    JSONObject c=authenticate(p,registry,uid);String nonce=verb.substring(6);
                    Failure.require(nonce.equals(c.optString("probeNonce")) && System.currentTimeMillis()-c.optLong("probeStarted")<60000,"UNAUTHORIZED","Expired or invalid probe");
                    File proofs=new File(dir,"probes");java.nio.file.Files.createDirectories(proofs.toPath());
                    Store.atomic(new File(proofs,nonce+".json"),Json.obj("id",c.getString("id"),"nonce",nonce).toString().getBytes(StandardCharsets.UTF_8));
                    reply=Json.obj("success",true);
                } else {
                    Failure.require(verb.equals("LAUNCH"),"INVALID_REQUEST","Unknown operation");
                    try(Store store=new Store(dir,false)) {
                        JSONObject c=authenticate(p,store.data,uid);
                        Failure.require("ACTIVE".equals(c.optString("state")) && "READY_PROXY".equals(c.optString("launcherEntryState")),"CLONE_UNAVAILABLE","Clone needs launcher reconciliation");
                        LauncherIntegration layer=new LauncherIntegration(p,store,module);layer.validateTarget(c);
                        layer.validatePackageOwnership(c);
                        Failure.require(p.currentUser()==c.getInt("sourceUserId"),"PARENT_NOT_FOREGROUND","Switch to the clone's parent user first");
                        p.startProfile(c.getInt("targetUserId"),c.getLong("targetSerial"));
                        p.verifyClone(c.getString("packageName"),c.getInt("sourceUserId"),c.getInt("targetUserId"));
                        String component=p.component(c.getString("packageName"),c.getInt("targetUserId"));
                        p.requireAm("--user");
                        AndroidPlatform.run(20,"/system/bin/am","start","--user",Integer.toString(c.getInt("targetUserId")),"-W","-n",component,
                            "-a","android.intent.action.MAIN","-c","android.intent.category.LAUNCHER");
                        store.log(c.getString("operationId"),"LAUNCH_CLONE",c.getString("packageName"),c.getInt("targetUserId"),"SUCCESS",component);
                        reply=Json.obj("success",true,"targetUserId",c.getInt("targetUserId"));
                    }
                }
            } catch(Exception e) {reply=Json.obj("success",false,"message",e.getMessage(),"errorCode",e instanceof Failure?((Failure)e).code:"BROKER_FAILED");}
            client.getOutputStream().write((reply+"\n").getBytes(StandardCharsets.UTF_8));
        } catch(Exception e) {System.err.println("Broker connection failed: "+e.getClass().getSimpleName());}
    }
}
