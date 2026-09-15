package io.github.nemo.cap;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import org.json.*;

/** Exercises the installed classes; only a new private test directory is written. */
public final class DeviceReadOnlyChecks {
    interface Work {void run() throws Exception;}
    static JSONArray results=new JSONArray();
    static void reject(String name,String code,Work work) throws Exception {
        try {work.run();throw new AssertionError(name+" unexpectedly succeeded");}
        catch(Failure e) {if(!code.equals(e.code))throw e;results.put(Json.obj("test",name,"errorCode",e.code,"passed",true));}
    }
    public static final class DeniedApi {
        public void denied() {throw new SecurityException("Controlled Binder-style permission denial");}
    }
    public static void main(String[] args) throws Exception {
        AndroidPlatform p=new AndroidPlatform();int parent=p.currentUser();long serial=p.requireUser(parent).getLong("serialNumber");
        reject("recycled serial", "PROFILE_IDENTITY_MISMATCH",()->p.identity(parent,serial+1));
        reject("framework permission propagation", "FRAMEWORK_API_FAILED",()->AndroidPlatform.invoke(new DeniedApi(),"denied",new Class<?>[0]));
        p.requireAm("-S");results.put(Json.obj("test","probe restart advertised","passed",true));
        Field help=AndroidPlatform.class.getDeclaredField("amHelp");help.setAccessible(true);Object original=help.get(p);
        try {
            help.set(p,"");reject("missing activity capability","COMMAND_UNSUPPORTED",()->p.requireAm("-S"));
            p.startProfile(parent,serial);
            results.put(Json.obj("test","unlocked profile avoids redundant start command","passed",true));
            reject("running profile still rejects recycled serial","PROFILE_IDENTITY_MISMATCH",()->p.startProfile(parent,serial+1));
        }
        finally {help.set(p,original);}
        try {AndroidPlatform.run(2,"/system/bin/cap-nonexistent-test-binary");throw new AssertionError("Missing executable succeeded");}
        catch(IOException expected) {results.put(Json.obj("test","missing binary propagates IOException","passed",true));}
        File root=Files.createTempDirectory(new File(args[0]).toPath(),"cap-readonly-").toFile();
        Files.write(new File(root,"module.prop").toPath(),"id=clone_app_profile\nversionCode=10002\ndescription=test\n".getBytes("UTF-8"));
        try(Store s=new Store(root,true)) {
            s.data.getJSONArray("pending").put(Json.obj("operationId","controlled-interruption","action","clone"));s.save();
            Core core=new Core(p,s,root);
            reject("interrupted operation blocks clone before mutation","RECOVERY_REQUIRED",()->core.execute(new String[]{"clone","io.github.nemo.cap.fixture",Integer.toString(parent)}));
            core.execute(new String[]{"reconcile"});
            if(s.data.getJSONArray("pending").length()!=1 || s.data.getJSONArray("clones").length()!=0)throw new AssertionError("Reconcile adopted or cleared uncertain operation");
            results.put(Json.obj("test","reconcile preserves unresolved journal without adopting packages","passed",true));
        }
        Files.write(new File(root,"registry.json").toPath(),"invalid".getBytes("UTF-8"));
        reject("invalid Android registry fails closed","REGISTRY_CORRUPT",()->{try(Store s=new Store(root,false)) {throw new AssertionError("Invalid registry opened");}});
        System.out.println(Json.obj("success",true,"passed",results.length(),"tests",results,"privateTestDirectory",root.toString()));
        System.exit(0);
    }
}
