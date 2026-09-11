package io.github.nemo.cap;

import org.json.*;
import java.io.*;
import java.util.Properties;

/** Shared by the real device update check and the lower-version host test. */
public final class UpdateMetadata {
    public static int installedVersion(File module) throws Exception {
        Properties props=new Properties();try(Reader in=new StringReader(Store.read(new File(module,"module.prop")))) {props.load(in);}
        String code=props.getProperty("versionCode","");
        Failure.require(code.matches("[1-9][0-9]{0,8}"),"INVALID_MODULE_METADATA","Invalid installed versionCode");
        return Integer.parseInt(code);
    }
    public static JSONObject evaluate(JSONObject data,int current) throws Exception {
        Object code=data.get("versionCode");String version=data.getString("version");
        Failure.require(code instanceof Integer && (Integer)code>0 && version.matches("v[0-9]+\\.[0-9]+\\.[0-9]+"),"INVALID_UPDATE_METADATA","Invalid version metadata");
        String release="https://github.com/nemo201104/Clone-App-Profile/releases/";
        Failure.require(data.getString("zipUrl").equals(release+"download/"+version+"/Clone-App-Profile-"+version+".zip")
            && data.getString("changelog").equals("https://raw.githubusercontent.com/nemo201104/Clone-App-Profile/"+version+"/CHANGELOG.md"),"INVALID_UPDATE_METADATA","Unexpected release URL");
        return Json.obj("state",data.getInt("versionCode")>current?"UPDATE_AVAILABLE":"UP_TO_DATE","installedVersionCode",current,"metadata",data);
    }
}
