package io.github.jenziner.nisaba.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API-Schlüssel, verschlüsselt mit einem nicht exportierbaren AES-Schlüssel im
 * Android Keystore. Im app-privaten Speicher liegt nur der Geheimtext.
 * Der Klartext wird nie angezeigt, protokolliert oder in Projektdateien geschrieben.
 */
class KeyVault(context: Context) {
    private val prefs = context.getSharedPreferences("schluessel", Context.MODE_PRIVATE)

    @Volatile private var cached: String? = null

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun hasKey(): Boolean = prefs.contains(K_DATA)

    /** Liefert den Schlüssel oder null; null auch, wenn die Entschlüsselung scheitert. */
    fun get(): String? {
        cached?.let { return it }
        val data = prefs.getString(K_DATA, null) ?: return null
        val iv = prefs.getString(K_IV, null) ?: return null
        return runCatching {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()?.also { cached = it }
    }

    fun set(key: String) {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val enc = c.doFinal(key.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(K_DATA, Base64.encodeToString(enc, Base64.NO_WRAP))
            .putString(K_IV, Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .apply()
        cached = key
    }

    fun clear() {
        prefs.edit().clear().apply()
        cached = null
    }

    private companion object {
        const val ALIAS = "nisaba-api-schluessel"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val K_DATA = "daten"
        const val K_IV = "iv"
    }
}
