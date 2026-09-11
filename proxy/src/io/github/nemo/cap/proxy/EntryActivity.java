package io.github.nemo.cap.proxy;

import android.app.*;
import android.os.*;
import android.net.*;
import android.widget.TextView;
import java.io.*;
import org.json.JSONObject;

/** No root permission, shell, package, component, URI or target-user inputs. */
public final class EntryActivity extends Activity {
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view=new TextView(this);view.setPadding(40,40,40,40);view.setText("Opening cloned app…");setContentView(view);
        String nonce=getIntent().getStringExtra("cap_probe");
        final String request=nonce!=null && nonce.matches("[a-f0-9]{64}")?"PROBE "+nonce:"LAUNCH";
        new Thread(()->{
            try(LocalSocket socket=new LocalSocket()) {
                socket.connect(new LocalSocketAddress("clone_app_profile.broker.v1",LocalSocketAddress.Namespace.ABSTRACT));socket.setSoTimeout(30000);
                if(socket.getPeerCredentials().getUid()!=0)throw new IOException("Invalid broker identity");
                socket.getOutputStream().write((request+"\n").getBytes("UTF-8"));
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();int b;
                while((b=socket.getInputStream().read())!=-1 && b!='\n') {if(bytes.size()>=8192)throw new IOException("Oversized broker response");bytes.write(b);}
                JSONObject result=new JSONObject(bytes.toString("UTF-8"));
                if(!result.getBoolean("success"))throw new IOException(result.optString("message","Launch failed"));
                runOnUiThread(this::finish);
            } catch(Exception e) {
                runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Clone unavailable").setMessage("Open Clone App Profile WebUI → Reconcile launcher.\n\n"+e.getMessage()).setPositiveButton("Close",(d,w)->finish()).setOnCancelListener(d->finish()).show());
            }
        },"clone-launch").start();
    }
}
