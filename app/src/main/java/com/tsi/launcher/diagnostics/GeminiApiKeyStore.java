package com.tsi.launcher.diagnostics;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.security.Key;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Stores the Gemini API key encrypted with an Android Keystore AES-GCM key. */
public final class GeminiApiKeyStore {
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "tsi_gemini_api_key_v1";
    private static final String PREFS = "tsi_secure_settings";
    private static final String PREF_ENCRYPTED_KEY = "gemini_key_ciphertext";

    private GeminiApiKeyStore() { }

    public static void save(Context context, String apiKey) throws Exception {
        String value = apiKey == null ? "" : apiKey.trim();
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (value.isEmpty()) {
            preferences.edit().remove(PREF_ENCRYPTED_KEY).commit();
            return;
        }
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] nonce = cipher.getIV();
        ByteBuffer buffer = ByteBuffer.allocate(4 + nonce.length + encrypted.length);
        buffer.putInt(nonce.length);
        buffer.put(nonce);
        buffer.put(encrypted);
        String encoded = Base64.encodeToString(buffer.array(), Base64.NO_WRAP);
        if (!preferences.edit().putString(PREF_ENCRYPTED_KEY, encoded).commit()) {
            throw new IllegalStateException("Could not persist the encrypted API key.");
        }
    }

    public static String load(Context context) throws Exception {
        String encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_ENCRYPTED_KEY, null);
        if (encoded == null || encoded.trim().isEmpty()) return "";
        byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
        ByteBuffer buffer = ByteBuffer.wrap(packed);
        int nonceLength = buffer.getInt();
        if (nonceLength < 12 || nonceLength > 32 || buffer.remaining() <= nonceLength) {
            throw new IllegalStateException("Stored API key data is invalid.");
        }
        byte[] nonce = new byte[nonceLength];
        buffer.get(nonce);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                new javax.crypto.spec.GCMParameterSpec(128, nonce));
        byte[] clear = cipher.doFinal(ciphertext);
        return new String(clear, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance(ANDROID_KEY_STORE);
        store.load(null);
        Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
