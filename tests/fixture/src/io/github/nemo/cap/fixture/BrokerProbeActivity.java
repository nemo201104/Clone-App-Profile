package io.github.nemo.cap.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.*;
import org.json.JSONObject;

/** Negative test: an ordinary, unsigned-by-module app must never launch a clone. */
public final class BrokerProbeActivity extends Activity {
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(()->{
            JSONObject evidence=new JSONObject();
            try(LocalSocket socket=new LocalSocket()) {
                socket.connect(new LocalSocketAddress("clone_app_profile.broker.v1",LocalSocketAddress.Namespace.ABSTRACT));
                socket.setSoTimeout(5000);
                socket.getOutputStream().write("LAUNCH\n".getBytes("UTF-8"));
                ByteArrayOutputStream out=new ByteArrayOutputStream();int b;
                while((b=socket.getInputStream().read())!=-1 && b!='\n' && out.size()<8192)out.write(b);
                evidence.put("uid",android.os.Process.myUid()).put("response",new JSONObject(out.toString("UTF-8")));
            } catch(Exception e) {try {evidence.put("transportError",e.toString());} catch(Exception ignored) {}}
            try(FileOutputStream out=openFileOutput("broker-negative.json",0)) {
                out.write(evidence.toString().getBytes("UTF-8"));out.getFD().sync();
            } catch(Exception e) {throw new RuntimeException(e);}
            runOnUiThread(this::finish);
        },"cap-negative").start();
    }
}
