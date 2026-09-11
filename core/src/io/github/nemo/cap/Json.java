package io.github.nemo.cap;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Json {
    private Json() {}
    public static JSONObject obj(Object... values) throws JSONException {
        JSONObject j = new JSONObject();
        for (int i = 0; i < values.length; i += 2) j.put((String) values[i], values[i+1]);
        return j;
    }
    public static String hash(byte[] bytes) throws Exception {
        StringBuilder b = new StringBuilder();
        for (byte v : MessageDigest.getInstance("SHA-256").digest(bytes)) b.append(String.format("%02x", v & 255));
        return b.toString();
    }
    public static String hash(String s) throws Exception { return hash(s.getBytes(StandardCharsets.UTF_8)); }
    public static String pkg(String s) throws Failure {
        Failure.require(s != null && s.length() <= 220 && s.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"), "INVALID_ARGUMENT", "Invalid package name");
        return s;
    }
    public static int user(String s) throws Failure {
        Failure.require(s != null && s.matches("0|[1-9][0-9]{0,8}"), "INVALID_ARGUMENT", "Expected numeric user ID");
        return Integer.parseInt(s);
    }
    public static JSONObject find(JSONArray a, String key, Object value) throws JSONException {
        for (int i=0;i<a.length();i++) if (value.equals(a.getJSONObject(i).opt(key))) return a.getJSONObject(i);
        return null;
    }
    public static void remove(JSONArray a, JSONObject row) {
        for (int i=a.length()-1;i>=0;i--) if (a.opt(i)==row) a.remove(i);
    }
    public static String identity(String pkg, long parentSerial, long targetSerial) throws Exception {
        pkg(pkg);
        Failure.require(parentSerial >= 0 && targetSerial >= 0 && parentSerial != targetSerial,
            "PROFILE_IDENTITY_MISMATCH", "Distinct valid parent/profile serial numbers required");
        return hash(pkg+":"+parentSerial+":"+targetSerial);
    }
}
