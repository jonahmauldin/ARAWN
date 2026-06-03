package com.arawn.core.crypto

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * ARAWN Vault per-file encryption (Phase E).
 *
 * Uses Tink's AES-256-GCM [Aead] with a keyset stored in
 * [EncryptedSharedPreferences] and wrapped by an Android Keystore master key.
 *
 * Each vault file is encrypted as:
 *   ciphertext = aead.encrypt(plaintext, associatedData)
 *
 * where [associatedData] is the vault entry's UUID (a unique string binding
 * the ciphertext to this specific DB row — prevents ciphertext from being
 * silently moved to a different entry).
 *
 * Instantiate once in [AppContainer]; the keyset is loaded lazily on first
 * access and cached for the lifetime of the process.
 */
class VaultCrypto(context: Context) {

    private val aead: Aead

    init {
        AeadConfig.register()
        val keysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(context.applicationContext, KEYSET_NAME, PREFS_NAME)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://$MASTER_KEY_ALIAS")
            .build()
            .keysetHandle
        aead = keysetHandle.getPrimitive(Aead::class.java)
    }

    /**
     * Encrypt [plaintext] and return the ciphertext.
     * [associatedData] must match on decrypt; use the vault entry UUID.
     */
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        aead.encrypt(plaintext, associatedData)

    /**
     * Decrypt ciphertext previously produced by [encrypt].
     * Throws [com.google.crypto.tink.shaded.protobuf.InvalidProtocolBufferException]
     * or [GeneralSecurityException] if the data or associatedData are wrong.
     */
    fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
        aead.decrypt(ciphertext, associatedData)

    companion object {
        private const val KEYSET_NAME      = "arawn_vault_keyset"
        private const val PREFS_NAME       = "arawn_vault_keyset_prefs"
        private const val MASTER_KEY_ALIAS = "arawn_vault_master_key"
    }
}
