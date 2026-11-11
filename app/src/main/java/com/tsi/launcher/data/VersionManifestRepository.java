package com.tsi.launcher.data;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Fetches Minecraft Java versions from Mojang's official version manifest. */
public final class VersionManifestRepository {
    public static final String MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    public List<MinecraftVersion> fetchVersions() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(MANIFEST_URL).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", "TSI-Launcher/0.1");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code > 299) {
                throw new IOException("Mojang manifest request failed: HTTP " + code);
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            JSONArray versions = new JSONObject(body.toString()).getJSONArray("versions");
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
        } finally {
            connection.disconnect();
        }
    }
}
