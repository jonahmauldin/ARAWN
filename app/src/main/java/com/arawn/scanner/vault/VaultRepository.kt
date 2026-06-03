package com.arawn.scanner.vault

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.arawn.core.crypto.VaultCrypto
import com.arawn.core.database.VaultDao
import com.arawn.core.database.VaultEntryEntity
import com.arawn.core.database.VaultEntryKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * ARAWN Vault repository — add / shred / decrypt vault entries (Phase E).
 *
 * All files are stored as AES-256-GCM ciphertext under [context.filesDir]/vault/.
 * The Tink [VaultCrypto] keyset lives in EncryptedSharedPreferences, wrapped by
 * Android Keystore — so the raw key material never leaves secure hardware.
 *
 * Security properties:
 *  - Ciphertext bound to its DB row via the entry UUID (associated data).
 *    Moving a blob to a different row will cause decryption to fail.
 *  - Crypto-shred: deleting the encrypted file and its DB row makes the data
 *    irrecoverable without physically recovering deleted storage blocks.
 *  - The Tink keyset itself is NOT shredded per-entry (v1). Shredding the keyset
 *    (= full vault wipe) can be added in a later phase.
 */
class VaultRepository(
    private val context: Context,
    private val vaultDao: VaultDao,
    private val vaultCrypto: VaultCrypto,
) {

    private val vaultDir: File
        get() = File(context.applicationContext.filesDir, "vault").also { it.mkdirs() }

    /**
     * Encrypt the file at [sourceUri] and store it in the vault.
     * Reads the entire file into memory — suitable for documents and reports
     * (< ~50 MB); large video should use streaming in a future iteration.
     *
     * @return the inserted [VaultEntryEntity] with its auto-generated ID.
     */
    suspend fun addFile(
        sourceUri: Uri,
        displayName: String,
        kind: VaultEntryKind,
    ): VaultEntryEntity = withContext(Dispatchers.IO) {
        val id   = UUID.randomUUID().toString()
        val adBytes = id.toByteArray(Charsets.UTF_8)

        // Read plaintext
        val plaintext = context.contentResolver.openInputStream(sourceUri)!!
            .use { it.readBytes() }

        // Compute SHA-256 of plaintext for dedup / integrity
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(plaintext)
            .joinToString("") { "%02x".format(it) }

        // Encrypt and write to internal storage
        val ciphertext  = vaultCrypto.encrypt(plaintext, adBytes)
        val encFile     = File(vaultDir, "$id.enc")
        encFile.writeBytes(ciphertext)

        val entry = VaultEntryEntity(
            kind             = kind,
            displayName      = displayName,
            encryptedBlobUri = encFile.absolutePath,
            keyId            = id,
            sizeBytes        = plaintext.size.toLong(),
            sha256           = sha256,
            addedMs          = System.currentTimeMillis(),
        )
        val rowId = vaultDao.insertEntry(entry)
        entry.copy(vaultEntryId = rowId)
    }

    /**
     * Decrypt [entry] and return the plaintext bytes.
     * Throws on key mismatch or corrupt ciphertext.
     */
    suspend fun decrypt(entry: VaultEntryEntity): ByteArray = withContext(Dispatchers.IO) {
        val ciphertext = File(entry.encryptedBlobUri).readBytes()
        vaultCrypto.decrypt(ciphertext, entry.keyId.toByteArray(Charsets.UTF_8))
    }

    /**
     * Delete the encrypted file and its DB row — the data is unrecoverable
     * without the Tink keyset AND the ciphertext.
     */
    suspend fun shred(entry: VaultEntryEntity) = withContext(Dispatchers.IO) {
        runCatching { File(entry.encryptedBlobUri).delete() }
        vaultDao.deleteEntry(entry)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Best-effort display name from the URI's metadata. Falls back to the raw path. */
    fun resolveDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (col >= 0) return cursor.getString(col)
                }
            }
        return uri.lastPathSegment ?: uri.toString()
    }

    /** Guess [VaultEntryKind] from the URI's MIME type. */
    fun guessKind(uri: Uri): VaultEntryKind {
        val mime = context.contentResolver.getType(uri) ?: return VaultEntryKind.OTHER
        return when {
            mime.startsWith("image/")             -> VaultEntryKind.MEDIA
            mime.startsWith("video/")             -> VaultEntryKind.MEDIA
            mime.startsWith("audio/")             -> VaultEntryKind.MEDIA
            mime == "application/pdf"             -> VaultEntryKind.DOCUMENT
            mime == "text/html"                   -> VaultEntryKind.REPORT
            mime.startsWith("text/")              -> VaultEntryKind.DOCUMENT
            else                                  -> VaultEntryKind.OTHER
        }
    }
}
