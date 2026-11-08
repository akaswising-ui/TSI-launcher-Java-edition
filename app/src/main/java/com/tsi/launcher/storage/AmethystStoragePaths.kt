package com.tsi.launcher.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.io.File

/**
 * The canonical game root used by the embedded Amethyst Android launcher.
 * It mirrors Amethyst's SDK-dependent storage rule.
 */
object AmethystStoragePaths {
    fun gameHome(context: Context): File {
        val appContext = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appContext.getExternalFilesDir(null) ?: appContext.filesDir
        } else {
            File(Environment.getExternalStorageDirectory(), "games/Amethyst")
        }
    }

    fun minecraftHome(context: Context): File = File(gameHome(context), ".minecraft")

    fun hasLegacyStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            (
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
            )
}
