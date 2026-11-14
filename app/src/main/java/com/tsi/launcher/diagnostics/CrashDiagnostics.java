package com.tsi.launcher.diagnostics;

import android.content.Context;
import android.os.Build;
import com.tsi.launcher.instances.GameInstance;
import com.tsi.launcher.instances.GameInstanceRepository;
import com.tsi.launcher.runtime.DevicePerformanceProfile;
import com.tsi.launcher.runtime.DevicePerformanceProfileSnapshot;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Local crash triage and explicit, user-triggered Gemini analysis. */
public final class CrashDiagnostics {
    private static final int MAX_LOG_CHARS = 45_000;
    private static final String GEMINI_MODEL = "gemini-3.8-flash";

    private CrashDiagnostics() { }

    public static String loadLatestLog(Context context) throws Exception {
        GameInstance selected = new GameInstanceRepository(context).selected();
        if (selected == null) throw new IllegalStateException("Select a Minecraft instance first.");
        File game = selected.getGameDirectory();
        File latest = new File(game, "latest.log");
        if (!latest.isFile()) {
            File rootLatest = new File(game.getParentFile(), "latest.log");
            if (rootLatest.isFile()) latest = rootLatest;
        }
        String log = latest.isFile() ? readTail(latest, MAX_LOG_CHARS) : "";
        File crashDir = new File(game, "crash-reports");
        if (!crashDir.isDirectory() && game.getParentFile() != null) {
            File alternative = new File(game.getParentFile(), "crash-reports");
            if (alternative.isDirectory()) crashDir = alternative;
        }
        File[] reports = crashDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".txt"));
        if (reports != null && reports.length > 0) {
            java.util.Arrays.sort(reports, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            String crash = readTail(reports[0], 20_000);
            log = "LATEST CRASH REPORT: " + reports[0].getName() + "\n" + crash
                    + (log.isEmpty() ? "" : "\n\nLATEST.LOG\n" + log);
        }
        return sanitize(log);
    }

    public static String gatherContext(Context context, String rawLog) throws Exception {
        StringBuilder data = new StringBuilder();
        data.append("DEVICE\n");
        data.append("Manufacturer: ").append(Build.MANUFACTURER).append('\n');
        data.append("Model: ").append(Build.MODEL).append('\n');
        data.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        data.append("Supported ABI: ").append(Build.SUPPORTED_ABIS.length == 0 ? "unknown" : Build.SUPPORTED_ABIS[0]).append('\n');
        DevicePerformanceProfileSnapshot profile = DevicePerformanceProfile.detect(context);
        data.append("Physical RAM MB: ").append(profile.getTotalPhysicalRamMb()).append('\n');
        data.append("Performance tier: ").append(profile.getTierLabel()).append('\n');
        data.append("\nSELECTED INSTANCE\n");
        GameInstance instance = new GameInstanceRepository(context).selected();
        if (instance != null) {
            data.append("Name: ").append(instance.getName()).append('\n');
            data.append("Minecraft: ").append(instance.getVersionId()).append('\n');
            data.append("Renderer ID: ").append(or(instance.getRendererId(), "Auto")).append('\n');
            data.append("Runtime: ").append(or(instance.getSelectedRuntime(), "Auto")).append('\n');
            data.append("Custom control layout: ").append(or(instance.getControlLayout(), "Default")).append('\n');
            File mods = new File(instance.getGameDirectory(), "mods");
            File[] modFiles = mods.listFiles((dir, name) -> {
                String n = name.toLowerCase(Locale.ROOT);
                return n.endsWith(".jar") || n.endsWith(".jar.disabled");
            });
            if (modFiles != null) {
                data.append("Installed mod files (up to 40):\n");
                int count = Math.min(40, modFiles.length);
                for (int i = 0; i < count; i++) data.append("- ").append(modFiles[i].getName()).append('\n');
                if (modFiles.length > count) data.append("... plus ").append(modFiles.length - count).append(" more\n");
            }
        } else {
            data.append("No selected instance\n");
        }
        data.append("\nLOG TAIL\n").append(sanitize(limit(rawLog, MAX_LOG_CHARS)));
        return limit(data.toString(), MAX_LOG_CHARS + 4000);
    }

    public static String analyzeLocally(String logs) {
        String value = logs == null ? "" : logs;
        String lower = value.toLowerCase(Locale.ROOT);
        List<String> findings = new ArrayList<>();
        if (lower.contains("outofmemoryerror") || lower.contains("java heap space")) {
            findings.add("Memory: Minecraft ran out of Java heap. Lower render distance, close other apps, and use a heap size that fits the phone's free RAM.");
        }
        if (lower.contains("unsupportedclassversionerror")) {
            findings.add("Java version: a mod or library was compiled for a newer Java version than the runtime selected for this Minecraft profile.");
        }
        if (lower.contains("nosuchmethoderror") || lower.contains("noclassdeffounderror")) {
            findings.add("Missing or incompatible dependency: check that every mod matches the Minecraft version and the active loader.");
        }
        if (lower.contains("mixin apply failed") || lower.contains("mixinapplyerror")) {
            findings.add("Mixin conflict: one or more mods are patching incompatible classes. Test by disabling recently added mods in small groups.");
        }
        if (lower.contains("exit code 139") || lower.contains("sigsegv") ||
                lower.contains("fatal signal 11") || lower.contains("unsatisfiedlinkerror")) {
            findings.add("Native engine or graphics layer: this looks like a native-library/JNI/renderer failure, not an ordinary Java exception. Preserve the full log and test a compatible built-in renderer.");
        }
        if (lower.contains("gl_out_of_memory") || lower.contains("vk_error_out_of_device_memory")) {
            findings.add("GPU memory: lower texture resolution, shader load and render distance. Remove heavy shaders or high-resolution resource packs for the next test.");
        }
        if (lower.contains("failed to load") && lower.contains("lwjgl")) {
            findings.add("LWJGL component: a required native or Java LWJGL component could not load. Re-run the launcher component preparation and check the selected Java runtime.");
        }
        if (findings.isEmpty()) {
            return "No well-known crash signature was detected in the supplied log. The cause is not confirmed. Keep the complete latest.log and crash report; do not guess a fix from a short stack-trace fragment.";
        }
        StringBuilder result = new StringBuilder("Local diagnostic (probable causes, not a confirmed root cause):\n");
        for (String finding : findings) result.append("\n• ").append(finding);
        return result.toString();
    }

    public static String analyzeWithGemini(String apiKey, String diagnosticContext) throws Exception {
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty()) throw new IllegalArgumentException("A Gemini API key is required.");
        String safeData = limit(sanitize(diagnosticContext), MAX_LOG_CHARS + 4000);
        String prompt = "You are diagnosing Minecraft Java crashes on Android using TSI Launcher. " +
                "Analyze the device context, selected instance, renderer/runtime details and log. " +
                "Separate confirmed evidence from hypotheses. Identify the earliest meaningful exception, " +
                "missing dependency, mod conflict, Java mismatch, memory failure or native renderer/JNI failure. " +
                "Give short, ordered repair steps. Never invent a cause or claim a phone-specific fix without evidence. " +
                "If the evidence is insufficient, say what exact additional log is needed.\n\n" +
                "DIAGNOSTIC DATA (user-approved):\n" + safeData;

        URL endpoint = new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                + GEMINI_MODEL + ":generateContent");
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("x-goog-api-key", key);
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(45_000);
        connection.setDoOutput(true);
        JSONObject part = new JSONObject().put("text", prompt);
        JSONObject content = new JSONObject().put("parts", new JSONArray().put(part));
        JSONObject payload = new JSONObject().put("contents", new JSONArray().put(content));
        try {
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(body);
                output.flush();
            }
            int status = connection.getResponseCode();
            java.io.InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = stream == null ? "" : readBounded(stream, 96_000);
            if (status < 200 || status >= 300) {
                throw new java.io.IOException("Gemini API returned HTTP " + status + ": " + limit(response, 1500));
            }
            JSONObject json = new JSONObject(response);
            JSONArray candidates = json.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                throw new java.io.IOException("Gemini returned no analysis candidate.");
            }
            JSONObject responseContent = candidates.getJSONObject(0).optJSONObject("content");
            JSONArray parts = responseContent == null ? null : responseContent.optJSONArray("parts");
            if (parts == null || parts.length() == 0) {
                throw new java.io.IOException("Gemini response did not contain analysis text.");
            }
            String answer = parts.getJSONObject(0).optString("text", "").trim();
            if (answer.isEmpty()) throw new java.io.IOException("Gemini returned an empty answer.");
            return answer;
        } finally {
            connection.disconnect();
        }
    }

    private static String readTail(File file, int maxChars) throws Exception {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length();
            long start = Math.max(0, length - (long) maxChars * 3);
            input.seek(start);
            byte[] bytes = new byte[(int) Math.min(length - start, (long) maxChars * 3)];
            input.readFully(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return limit(text, maxChars);
        }
    }

    private static String readBounded(java.io.InputStream input, int maxBytes) throws Exception {
        try (java.io.InputStream in = input;
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (output.size() + count > maxBytes) throw new java.io.IOException("Gemini response exceeded the size limit.");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String sanitize(String value) {
        if (value == null) return "";
        return value.replaceAll("(?i)(access_token|refresh_token|client_token|authorization|bearer)\\s*[:=]?\\s*[^\\s,;]+",
                "$1=[redacted]");
    }

    private static String limit(String value, int maxChars) {
        if (value == null) return "";
        if (value.length() <= maxChars) return value;
        return value.substring(value.length() - maxChars);
    }

    private static String or(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
