package io.github.nemo.cap.fixture;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.*;
import java.util.UUID;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    protected void onCreate(Bundle state) {
        super.onCreate(state);
    }
    protected void onResume() {
        super.onResume();
        try {
            android.content.SharedPreferences prefs=getSharedPreferences("sandbox",0);
            String marker=prefs.getString("marker",UUID.randomUUID().toString());int count=prefs.getInt("launches",0)+1;
            prefs.edit().putString("marker",marker).putInt("launches",count).commit();
            JSONObject evidence=new JSONObject().put("uid",android.os.Process.myUid()).put("userHandle",android.os.Process.myUserHandle().toString())
                .put("dataDir",getApplicationInfo().dataDir).put("marker",marker).put("launches",count).put("time",System.currentTimeMillis());
            try(FileOutputStream out=openFileOutput("launch-evidence.json",0)) {out.write(evidence.toString().getBytes("UTF-8"));out.getFD().sync();}
            TextView text=new TextView(this);text.setTextSize(22);text.setPadding(36,60,36,36);
            text.setText("CAP TEST SANDBOX\n\n"+evidence.toString(2));setContentView(text);
        } catch(Exception e) {throw new RuntimeException(e);}
    }
}
