package io.github.nemo.cap;

import java.io.*;
import java.nio.channels.*;

/** Single supervisor per device, with a separately authenticated launch broker. */
public final class Daemon {
    public static void run(AndroidPlatform p,File dir,File module) throws Exception {
        try(RandomAccessFile file=new RandomAccessFile(new File(dir,"service.lock"),"rw")) {
            FileLock lock=file.getChannel().tryLock();
            Failure.require(lock!=null,"SERVICE_ALREADY_RUNNING","Another module supervisor owns service.lock");
            try(FileLock held=lock) {
                Thread broker=new Thread(()->{
                    while(enabled(module)) {
                        try {Broker.serve(p,dir,module);}
                        catch(Exception e) {System.err.println("Broker: "+e.getClass().getSimpleName()+": "+e.getMessage());}
                        try {Thread.sleep(10000);}catch(InterruptedException e){return;}
                    }
                },"launch-broker");broker.setDaemon(true);broker.start();
                Thread.sleep(500);
                while(enabled(module)) {
                    try(Store store=new Store(dir,false)) {new Core(p,store,module).execute(new String[]{"reconcile"});}
                    catch(Exception e) {System.err.println("Reconciliation: "+e.getMessage());}
                    // Poll flags without holding the registry lock, so disable does not wait 45 seconds.
                    for(int i=0;i<45 && enabled(module);i++)Thread.sleep(1000);
                }
            }
        }
    }
    static boolean enabled(File module) {return new File(module,"module.prop").isFile() && !new File(module,"disable").exists() && !new File(module,"remove").exists();}
}
