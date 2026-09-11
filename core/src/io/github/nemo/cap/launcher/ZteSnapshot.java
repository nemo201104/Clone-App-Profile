package io.github.nemo.cap.launcher;

import io.github.nemo.cap.*;
import org.json.*;
import java.util.*;
import java.util.regex.*;

/** Strict parser for the inspected MiFavor diagnostic format, not an Android API. */
public final class ZteSnapshot {
    private static final Pattern HEADER=Pattern.compile("(?m)^\\s*AllAppsStore Apps\\[\\] size: ([0-9]+)\\s*$");
    private static final Pattern ROW=Pattern.compile("^\\s*Package index, name, class, description, bitmap flag: ([0-9]+)/([A-Za-z][A-Za-z0-9_.]*):([A-Za-z][A-Za-z0-9_.$]*), [^\\r\\n]*, ([01]{1,31})\\+([01]{1,31})\\s*$");
    public static JSONArray parse(String dump) throws Exception {
        Matcher header=HEADER.matcher(dump);
        Failure.require(header.find(),"NATIVE_DETECTION_UNAVAILABLE","HOME app model is not initialized or format changed");
        int count=Integer.parseInt(header.group(1)),start=header.end();
        Failure.require(count>0 && count<=20000 && !header.find(),"NATIVE_DETECTION_UNAVAILABLE","Ambiguous or empty HOME model");
        JSONArray rows=new JSONArray();
        for(String line:dump.substring(start).split("\\n")) {
            if(line.trim().isEmpty() && rows.length()==0)continue;
            Matcher row=ROW.matcher(line);
            Failure.require(row.matches() && Integer.parseInt(row.group(1))==rows.length(),"NATIVE_DETECTION_UNAVAILABLE","Incomplete HOME model; do not infer absence");
            rows.put(Json.obj("component",Json.pkg(row.group(2))+"/"+row.group(3),"flags",Integer.parseInt(row.group(4),2)));
            if(rows.length()==count)return rows;
        }
        throw new Failure("NATIVE_DETECTION_UNAVAILABLE","Truncated HOME model");
    }
    /** Cache rows alone cannot prove visibility. Only matching live HOME rows count. */
    public static JSONObject match(JSONArray model,JSONArray cache,String pkg,long serial,Set<Long> liveSerials) throws Exception {
        int matches=0;JSONArray components=new JSONArray();
        for(int i=0;i<model.length();i++) {
            JSONObject entry=model.getJSONObject(i);String component=entry.getString("component");
            if(!component.startsWith(pkg+"/"))continue;
            // The inspected personal drawer accepts owner + OEM canonical-profile
            // badge metadata. Its raw AllAppsStore also contains hidden siblings.
            // A generic CLONE bitmap flag (4) alone is NOT visible native evidence.
            if((entry.getInt("flags")&1024)==0)continue;
            Set<Long> candidates=new HashSet<>();
            for(int j=0;j<cache.length();j++) {
                JSONObject row=cache.getJSONObject(j);long candidate=row.getLong("serial");
                if(component.equals(row.getString("component")) && entry.getInt("flags")==row.getInt("flags") && liveSerials.contains(candidate))candidates.add(candidate);
            }
            Failure.require(!candidates.isEmpty(),"NATIVE_DETECTION_UNAVAILABLE","HOME/cache identity not synchronized");
            if(candidates.contains(serial)) {
                Failure.require(candidates.size()==1,"NATIVE_DETECTION_AMBIGUOUS","HOME row maps to multiple live profile serials");
                matches++;components.put(component);
            }
        }
        return Json.obj("state",matches>0?"PRESENT":"ABSENT","entryCount",matches,"components",components,
            "targetSerial",serial,"source","HOME AllAppsStore + read-only icon cache serial mapping");
    }
}
