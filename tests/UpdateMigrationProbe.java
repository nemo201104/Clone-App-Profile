package io.github.nemo.cap;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/** Device-only test entrypoint. Never included in the module or proxy APK. */
public final class UpdateMigrationProbe {
    public static void main(String[] args) throws Exception {
        int installed = UpdateMetadata.installedVersion(new File(args[0]));
        JSONObject metadata = new JSONObject(new String(Files.readAllBytes(new File(args[1]).toPath()), StandardCharsets.UTF_8));
        // Reflection prevents javac from inlining the test build's endpoint over
        // the actual installed legacy/current Core class being inspected.
        JSONObject result = new JSONObject().put("mode", "CONTROLLED_METADATA_NO_NETWORK")
            .put("endpoint", Core.class.getField("UPDATE").get(null))
            .put("installedVersionCode", installed);
        try {
            result.put("success", true).put("details", UpdateMetadata.evaluate(metadata, installed));
        } catch (Failure failure) {
            result.put("success", false).put("errorCode", failure.code).put("message", failure.getMessage());
        }
        System.out.println(result);
    }
}
