package com.tsi.launcher.storage;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import java.io.File;

/** The canonical game root used by the embedded Amethyst Android launcher. */
public final class AmethystStoragePaths {
    private AmethystStoragePaths() { }

    public static File gameHome(Context context) {
        Context appContext = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File external = appContext.getExternalFilesDir(null);
            return external != null ? external : appContext.getFilesDir();
        }
        return new File(Environment.getExternalStorageDirectory(), "games/Amethyst");
    }

    public static File minecraftHome(Context context) {
        return new File(gameHome(context), ".minecraft");
    }

    public static boolean hasLegacyStoragePermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true;
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
    }
}
