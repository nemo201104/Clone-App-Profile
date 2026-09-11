package io.github.nemo.cap.launcher;
import io.github.nemo.cap.AndroidPlatform;
import org.json.JSONObject;

public interface LauncherAdapter {
    JSONObject inspect(AndroidPlatform platform,JSONObject home,int parent) throws Exception;
}
