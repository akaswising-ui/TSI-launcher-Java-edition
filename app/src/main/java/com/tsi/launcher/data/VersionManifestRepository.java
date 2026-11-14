package com.tsi.launcher.data;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.File;
import com.tsi.launcher.nativecore.NativeBridge;
import java.util.ArrayList;
import java.util.List;

/** Fetches Minecraft Java versions from Mojang's official version manifest. */
public final class VersionManifestRepository {
    public static final String MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    public List<MinecraftVersion> fetchVersions() throws IOException {
        File scratch = File.createTempFile("tsi-version-manifest-", ".json");
        String body = NativeBridge.downloadText(MANIFEST_URL, scratch);
        try {
            JSONArray versions = new JSONObject(body).getJSONArray("versions");
            List<MinecraftVersion> result = new ArrayList<>();
            for (int index = 0; index < versions.length(); index++) {
                JSONObject item = versions.getJSONObject(index);
                String id = item.optString("id");
                String type = item.optString("type");
                String url = item.optString("url");
                if (!id.trim().isEmpty() && !type.trim().isEmpty() && !url.trim().isEmpty()) {
                    result.add(new MinecraftVersion(id, type, url, item.optString("releaseTime")));
                }
            }
            return result;
        } catch (org.json.JSONException error) {
            throw new IOException("Mojang version manifest JSON is invalid.", error);
        }
    }
}
