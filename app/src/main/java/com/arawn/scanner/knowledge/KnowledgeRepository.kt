package com.arawn.scanner.knowledge

import android.content.Context
import android.net.Uri
import com.arawn.core.database.DocumentDao
import com.arawn.core.database.DocumentEntity
import com.arawn.core.database.VaultDao
import com.arawn.core.database.VaultEntryKind
import com.arawn.scanner.vault.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class KnowledgeRepository(
    private val context: Context,
    private val documentDao: DocumentDao,
    private val vaultDao: VaultDao,
    private val vaultRepository: VaultRepository,
) {

    fun observeDocuments(): Flow<List<DocumentEntity>> = documentDao.observeDocuments()

    fun search(q: String): Flow<List<DocumentEntity>> = documentDao.search(q)

    fun resolveDisplayName(uri: Uri): String = vaultRepository.resolveDisplayName(uri)

    /**
     * Encrypt the file at [uri] and insert a [DocumentEntity] row.
     *
     * The VaultEntry's displayName is the original filename (preserves the extension
     * for viewer type-detection in [decryptToTempFile]). [title] is the user-readable
     * label stored in the documents table.
     */
    suspend fun importDocument(
        uri: Uri,
        title: String,
        category: String?,
        tags: String?,
    ): DocumentEntity = withContext(Dispatchers.IO) {
        val originalName = vaultRepository.resolveDisplayName(uri)
        val entry = vaultRepository.addFile(uri, originalName, VaultEntryKind.DOCUMENT)
        val doc = DocumentEntity(
            title        = title.ifBlank { originalName },
            category     = category?.takeIf { it.isNotBlank() },
            tags         = tags?.takeIf { it.isNotBlank() },
            vaultEntryId = entry.vaultEntryId,
            addedMs      = System.currentTimeMillis(),
        )
        val docId = documentDao.insertDocument(doc)
        doc.copy(documentId = docId)
    }

    /**
     * Decrypt [doc] to a temp file in cacheDir/docs/.
     * Extension is taken from the VaultEntry's original filename so the viewer can
     * detect the file type without reading content.
     *
     * The caller is responsible for deleting the temp file when done (use
     * [DisposableEffect] in Compose).
     */
    suspend fun decryptToTempFile(doc: DocumentEntity): File = withContext(Dispatchers.IO) {
        val entry = vaultDao.getEntry(doc.vaultEntryId)
            ?: error("Vault entry ${doc.vaultEntryId} not found")
        val plaintext = vaultRepository.decrypt(entry)
        val dir = File(context.cacheDir, "docs").also { it.mkdirs() }
        val ext = entry.displayName.substringAfterLast('.', "")
            .let { if (it.isNotBlank()) ".$it" else "" }
        val file = File(dir, "doc_${doc.documentId}$ext")
        file.writeBytes(plaintext)
        file
    }

    /**
     * Crypto-shred: delete the encrypted blob + VaultEntry row.
     * The FK CASCADE on documents.vaultEntryId removes the DocumentEntity row.
     */
    suspend fun deleteDocument(doc: DocumentEntity) = withContext(Dispatchers.IO) {
        val entry = vaultDao.getEntry(doc.vaultEntryId) ?: return@withContext
        vaultRepository.shred(entry)
    }
}
