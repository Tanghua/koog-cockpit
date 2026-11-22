package com.qwenaudio.cockpit;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Only encrypted credentials are stored in app preferences; the key stays in Android Keystore. */
final class CredentialVault {
    private static final String ALIAS = "qwa-public-access-v1";
    private final android.content.SharedPreferences preferences;
    CredentialVault(Context context) { preferences = context.getSharedPreferences("public-access", Context.MODE_PRIVATE); }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    void save(String origin, String username, String password) throws Exception {
        if (!origin.startsWith("https://")) throw new Exception("公网凭据仅能用于 HTTPS 地址");
        JSONObject record = new JSONObject().put("origin", origin).put("username", username).put("password", password);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        String data = Base64.encodeToString(cipher.doFinal(record.toString().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        String iv = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
        if (!preferences.edit().putString("data", data).putString("iv", iv).commit()) throw new Exception("登录信息保存失败");
    }
    JSONObject load(String origin) {
        try {
            String data = preferences.getString("data", ""), iv = preferences.getString("iv", "");
            if (data.isEmpty() || !origin.startsWith("https://")) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            JSONObject record = new JSONObject(new String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), StandardCharsets.UTF_8));
            return origin.equals(record.getString("origin")) ? record : null;
        } catch (Exception ignored) { return null; }
    }
}
