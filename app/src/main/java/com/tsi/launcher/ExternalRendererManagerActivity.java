package com.tsi.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.tsi.launcher.instances.GameInstance;
import com.tsi.launcher.instances.GameInstanceRepository;
import com.tsi.launcher.plugins.ExternalRendererRegistry;
import java.util.List;

/** Lists installed Hyper/FCL-compatible renderer APKs and applies a selected id to the active instance. */
public final class ExternalRendererManagerActivity extends Activity {
    private LinearLayout list;
    private TextView status;
    private GameInstanceRepository instances;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        instances = new GameInstanceRepository(getApplicationContext());
        buildUi();
        refresh();
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
        root.addView(text("EXTERNAL RENDERER PLUGINS", 21, 0xFF183B4A, true), matchWrap());
        root.addView(text("Only separately installed FCL / Zalith renderer APKs for this phone's active ABI are listed. Renderer plugins contain native code and can crash the game process.",
                12, 0xFF536D78, false), matchWrap());
        Button refresh = button("RESCAN INSTALLED APPS");
        refresh.setOnClickListener(v -> refresh());
        root.addView(refresh, matchWrap());
        status = text("Scanning installed renderer packages…", 12, 0xFF536D78, false);
        root.addView(status, matchWrap());
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, matchWrap());
        Button automatic = button("USE AUTOMATIC / BUILT-IN RENDERER");
        automatic.setOnClickListener(v -> selectRenderer(null, "Automatic renderer"));
        root.addView(automatic, matchWrap());
        Button back = button("BACK");
        back.setOnClickListener(v -> finish());
        root.addView(back, matchWrap());
    }

    private void refresh() {
        list.removeAllViews();
        List<String> ids = ExternalRendererRegistry.getRendererIds(this);
        ExternalRendererRegistry.invalidateEngineRendererCache();
        status.setText(ids.size() + " compatible renderer APK(s) found. Plugins must already be installed.");
        if (ids.isEmpty()) {
            list.addView(text("No compatible FCL renderer APK was detected for this device ABI. This is not an error; built-in renderers remain available in Engine Settings.",
                    12, 0xFF536D78, false), matchWrap());
            return;
        }
        for (String id : ids) {
            String name = ExternalRendererRegistry.getRendererDisplayName(this, id);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(10), dp(10), dp(10), dp(10));
            card.setBackgroundColor(0xFFFFFFFF);
            LinearLayout.LayoutParams lp = matchWrap();
            lp.bottomMargin = dp(8);
            list.addView(card, lp);
            card.addView(text(name, 15, 0xFF183B4A, true), matchWrap());
            card.addView(text("Plugin key: " + id + "\n"
                            + ExternalRendererRegistry.getRendererCompatibilitySummary(this, id),
                    10, 0xFF536D78, false), matchWrap());
            Button use = button("USE THIS RENDERER");
            use.setOnClickListener(v -> selectRenderer(id, name));
            card.addView(use, matchWrap());
        }
    }

    private void selectRenderer(String rendererId, String label) {
        try {
            GameInstance current = instances.selected();
            if (current == null) {
                status.setText("Select or create a game instance before choosing its renderer.");
                return;
            }
            instances.updateLaunchSettings(current.getId(), rendererId,
                    current.getJavaArgs(), current.getSelectedRuntime(),
                    current.getControlLayout(), current.getArgsMode(), current.isSharedData());
            status.setText("Saved " + label + " for " + current.getName() + ". The engine checks it again at launch.");
        } catch (Exception error) {
            status.setText("Could not save renderer: " + message(error));
        }
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }
    private Button button(String label) { Button b = new Button(this); b.setText(label); b.setAllCaps(false); return b; }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private static String message(Throwable error) {
        Throwable x = error;
        while (x.getCause() != null && x.getCause() != x) x = x.getCause();
        return x.getMessage() == null ? x.getClass().getSimpleName() : x.getMessage();
    }
}
