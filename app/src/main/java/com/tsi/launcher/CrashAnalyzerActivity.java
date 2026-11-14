package com.tsi.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.tsi.launcher.diagnostics.CrashDiagnostics;
import com.tsi.launcher.diagnostics.GeminiApiKeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local crash triage with an explicitly consented optional Gemini request. */
public final class CrashAnalyzerActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private EditText apiKeyInput;
    private EditText logInput;
    private CheckBox consent;
    private TextView status;
    private TextView result;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        loadSavedKey();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFEAF4F7);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(22));
        scroll.addView(root);
        setContentView(scroll);

        root.addView(text("CRASH ANALYZER", 22, 0xFF183B4A, true), matchWrap());
        root.addView(text("Load the latest Minecraft log, run local checks, or send it to Gemini. Nothing is uploaded until you check consent and tap SEND TO GEMINI.",
                12, 0xFF536D78, false), matchWrap());

        Button load = button("LOAD LATEST LOG / CRASH REPORT");
        load.setOnClickListener(v -> loadLatestLog());
        root.addView(load, matchWrap());

        root.addView(text("GEMINI API KEY", 14, 0xFF183B4A, true), matchWrap());
        apiKeyInput = new EditText(this);
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyInput.setHint("Paste your Google AI Studio API key");
        root.addView(apiKeyInput, matchWrap());

        Button saveKey = button("SAVE ENCRYPTED API KEY");
        saveKey.setOnClickListener(v -> saveKey());
        root.addView(saveKey, matchWrap());

        logInput = new EditText(this);
        logInput.setGravity(android.view.Gravity.TOP);
        logInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        logInput.setMinLines(9);
        logInput.setMaxLines(18);
        logInput.setHint("Paste latest.log or a crash report here. Review it before any upload.");
        root.addView(logInput, matchWrap());

        Button local = button("RUN LOCAL DIAGNOSTIC");
        local.setOnClickListener(v -> runLocal());
        root.addView(local, matchWrap());

        consent = new CheckBox(this);
        consent.setText("I reviewed this log and agree to send the device details, selected instance data, mod filenames and log text to Google Gemini for analysis.");
        root.addView(consent, matchWrap());

        Button send = button("SEND TO GEMINI");
        send.setOnClickListener(v -> sendToGemini());
        root.addView(send, matchWrap());

        status = text("Ready.", 12, 0xFF536D78, false);
        status.setPadding(0, dp(10), 0, dp(6));
        root.addView(status, matchWrap());
        result = text("", 13, 0xFF183B4A, false);
        result.setPadding(0, dp(8), 0, dp(12));
        root.addView(result, matchWrap());

        Button back = button("BACK");
        back.setOnClickListener(v -> finish());
        root.addView(back, matchWrap());
    }

    private void loadSavedKey() {
        io.execute(() -> {
            try {
                String saved = GeminiApiKeyStore.load(getApplicationContext());
                runOnUiThread(() -> {
                    apiKeyInput.setText(saved);
                    if (saved.isEmpty()) status.setText("Gemini key not configured. Local diagnostics work without a key.");
                });
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Stored key could not be decrypted. Enter it again."));
            }
        });
    }

    private void loadLatestLog() {
        status.setText("Reading the selected instance logs…");
        io.execute(() -> {
            try {
                String log = CrashDiagnostics.loadLatestLog(getApplicationContext());
                runOnUiThread(() -> {
                    logInput.setText(log);
                    status.setText(log.isEmpty() ? "No latest.log or crash report was found." :
                            "Log loaded. Review it before sending to Gemini.");
                });
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Could not load logs: " + message(error)));
            }
        });
    }

    private void saveKey() {
        String value = apiKeyInput.getText().toString().trim();
        io.execute(() -> {
            try {
                GeminiApiKeyStore.save(getApplicationContext(), value);
                runOnUiThread(() -> status.setText(value.isEmpty()
                        ? "Gemini API key removed." : "API key encrypted and saved in Android Keystore-backed storage."));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Could not save API key: " + message(error)));
            }
        });
    }

    private void runLocal() {
        String logs = logInput.getText().toString();
        result.setText(CrashDiagnostics.analyzeLocally(logs));
        status.setText("Local analysis completed. Findings are probable causes, not a confirmed root cause.");
    }

    private void sendToGemini() {
        if (!consent.isChecked()) {
            status.setText("Consent is required before any log data is sent.");
            return;
        }
        String logs = logInput.getText().toString();
        if (logs.trim().isEmpty()) {
            status.setText("Load or paste a crash log first.");
            return;
        }
        String typedKey = apiKeyInput.getText().toString().trim();
        status.setText("Preparing approved diagnostic data…");
        io.execute(() -> {
            try {
                String key = typedKey.isEmpty()
                        ? GeminiApiKeyStore.load(getApplicationContext()) : typedKey;
                String context = CrashDiagnostics.gatherContext(getApplicationContext(), logs);
                runOnUiThread(() -> status.setText("Sending the reviewed diagnostics to Gemini…"));
                String answer = CrashDiagnostics.analyzeWithGemini(key, context);
                runOnUiThread(() -> {
                    result.setText(answer);
                    status.setText("Gemini analysis received.");
                });
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Gemini analysis failed: " + message(error)));
            }
        });
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }
    private Button button(String value) { Button b = new Button(this); b.setText(value); b.setAllCaps(false); return b; }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private static String message(Throwable error) {
        Throwable x = error;
        while (x.getCause() != null && x.getCause() != x) x = x.getCause();
        return x.getMessage() == null ? x.getClass().getSimpleName() : x.getMessage();
    }
}
