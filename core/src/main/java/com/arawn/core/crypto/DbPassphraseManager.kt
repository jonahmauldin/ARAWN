package com.arawn.core.crypto

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Generates and persists the SQLCipher database passphrase.
 *
 * A random 32-byte passphrase is created once and stored in
 * [EncryptedSharedPreferences], which wraps it with an AES-256-GCM key held
 * in the Android Keystore. The Keystore key is device-bound (no user auth
 * required) so the scan service can access the database at any time while the
 * device is booted — the Keystore protects against offline extraction.
 *
 * The passphrase itself never appears in logs, cleartext files, or shared
 * storage. Call [getOrCreate] once from [ArawnDatabase] before opening the
 * Room + SQLCipher instance.
 */
object DbPassphraseManager {

    private const val PREFS_FILE   = "arawn_db_passphrase_prefs"
    private const val PREFS_KEY    = "db_passphrase_b64"
    private const val PASSPHRASE_LEN = 32  // 256-bit AES key

    /**
     * Returns the existing passphrase, or generates and persists a new one
     * on first call. Safe to call on any thread; [EncryptedSharedPreferences]
     * operations are synchronous but fast.
     */
    fun getOrCreate(context: Context): ByteArray {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val prefs = EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

        val stored = prefs.getString(PREFS_KEY, null)
        return if (stored != null) {
            Base64.decode(stored, Base64.NO_WRAP)
        } else {
            val passphrase = ByteArray(PASSPHRASE_LEN).also { SecureRandom().nextBytes(it) }
            prefs.edit()
                .putString(PREFS_KEY, Base64.encodeToString(passphrase, Base64.NO_WRAP))
                .apply()
            passphrase
        }
    }
}
