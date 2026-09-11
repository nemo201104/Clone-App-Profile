package io.github.nemo.cap;

import org.json.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;

/** OS-backed locks are released on death. All core, broker and service writers share this file. */
public final class Store implements AutoCloseable {
    public final File dir;
    private final RandomAccessFile lockFile;
    private final FileLock lock;
    public JSONObject data;
    public Store(File dir, boolean initialize) throws Exception {
        this(dir,initialize,false);
    }
    public Store(File dir, boolean initialize,boolean recover) throws Exception {
        this.dir=dir;
        if (initialize) Files.createDirectories(dir.toPath());
        Failure.require(dir.isDirectory(), "REGISTRY_NOT_INITIALIZED", "Install the module to initialize private storage");
        lockFile=new RandomAccessFile(new File(dir,"registry.lock"),"rw");
        FileLock acquired=null;
        long until=System.nanoTime()+10_000_000_000L;
        while(acquired==null && System.nanoTime()<until) {
            try { acquired=lockFile.getChannel().tryLock(); } catch (OverlappingFileLockException ignored) { }
            if(acquired==null) Thread.sleep(50);
        }
        if(acquired==null) { lockFile.close(); throw new Failure("REGISTRY_LOCK_FAILED","Another operation is busy"); }
        lock=acquired;
        try {
            File f=new File(dir,"registry.json");
            if(!f.exists() && initialize && !new File(dir,"registry.bak").exists()) {
                data=Json.obj("schemaVersion",1,"profiles",new JSONArray(),"clones",new JSONArray(),"pending",new JSONArray());
                save();
            } else {
                try { data=new JSONObject(read(f)); validate(data); }
                catch(Exception e) {
                    if(!recover) throw new Failure("REGISTRY_CORRUPT","Registry invalid; preserve files and use registry-recover after inspecting doctor output: "+e.getMessage());
                    JSONObject backup=new JSONObject(read(new File(dir,"registry.bak")));validate(backup);
                    // Preserve the damaged bytes; recovering old ownership always requires live serial validation.
                    if(f.exists()) Files.copy(f.toPath(),new File(dir,"registry.corrupt-"+System.currentTimeMillis()+".json").toPath());
                    atomic(f,backup.toString().getBytes(StandardCharsets.UTF_8));data=backup;
                    log("recovery","RECOVERY","",-1,"SUCCESS","Validated backup restored; live profile identities still required for every mutation");
                }
            }
        } catch(Exception e) { close(); throw e; }
    }
    public static void validate(JSONObject d) throws Exception {
        Failure.require(d.getInt("schemaVersion")==1,"REGISTRY_SCHEMA_UNSUPPORTED","Unknown registry schema");
        HashSet<Integer> users=new HashSet<>(); HashSet<String> clones=new HashSet<>();
        JSONArray p=d.getJSONArray("profiles"), c=d.getJSONArray("clones"); d.getJSONArray("pending");
        for(int i=0;i<p.length();i++) {
            JSONObject r=p.getJSONObject(i);
            Failure.require(r.getBoolean("createdByModule") && r.getInt("userId")>0 && r.getLong("serialNumber")>=0
                && "android.os.usertype.profile.CLONE".equals(r.getString("type")) && users.add(r.getInt("userId")),"REGISTRY_CORRUPT","Invalid profile ownership");
        }
        for(int i=0;i<c.length();i++) {
            JSONObject r=c.getJSONObject(i); String id=Json.identity(r.getString("packageName"),r.getLong("parentSerial"),r.getLong("targetSerial"));
            Failure.require(r.getBoolean("managedByModule") && id.equals(r.getString("id")) && clones.add(id),"REGISTRY_CORRUPT","Invalid clone identity");
            String mode=r.optString("launcherMode","");
            Failure.require(mode.isEmpty() || mode.equals("NATIVE") || mode.equals("PROXY"),"REGISTRY_CORRUPT","Unknown launcher mode");
            Failure.require(!mode.equals("NATIVE") || !r.has("proxyPackage"),"REGISTRY_CORRUPT","Native record must not retain a proxy identity");
        }
    }
    public void save() throws Exception {
        validate(data); File f=new File(dir,"registry.json");
        if(f.exists()) { JSONObject old=new JSONObject(read(f)); validate(old); atomic(new File(dir,"registry.bak"),old.toString().getBytes(StandardCharsets.UTF_8)); }
        atomic(f,data.toString().getBytes(StandardCharsets.UTF_8));
    }
    public static void atomic(File file, byte[] bytes) throws Exception {
        File tmp=new File(file.getParentFile(),file.getName()+".tmp");
        try(FileOutputStream out=new FileOutputStream(tmp)) { out.write(bytes); out.getFD().sync(); }
        Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        // Directory fsync where supported; file fsync + atomic rename remains mandatory.
        try(FileChannel ch=FileChannel.open(file.getParentFile().toPath(),StandardOpenOption.READ)) { ch.force(true); }
        catch(UnsupportedOperationException | IOException ignored) { }
    }
    public static String read(File file) throws IOException {
        if(file.length()>8*1024*1024) throw new IOException("File exceeds size limit");
        return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
    }
    public void log(String op,String action,String pkg,int user,String result,String message) throws Exception {
        File f=new File(dir,"events.jsonl");
        if(f.length()>2*1024*1024) Files.move(f.toPath(),new File(dir,"events.previous.jsonl").toPath(),StandardCopyOption.REPLACE_EXISTING);
        JSONObject row=Json.obj("timestamp",Instant.now().toString(),"operationId",op,"action",action,"package",pkg,"userId",user,"result",result,"message",message);
        try(FileOutputStream out=new FileOutputStream(f,true)) {out.write((row+"\n").getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
    }
    public void close() throws IOException { try {lock.release();} finally {lockFile.close();} }
}
