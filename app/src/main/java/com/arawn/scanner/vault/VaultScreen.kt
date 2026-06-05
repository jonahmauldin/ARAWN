package com.arawn.scanner.vault

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.arawn.core.database.VaultDao
import com.arawn.core.database.VaultEntryEntity
import com.arawn.core.database.VaultEntryKind
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Amber        = Color(0xFFE0B341)
private val TerminalGreen = Color(0xFF35D07F)
private val PanelBlack   = Color(0xFF0A0A0A)
private val Ink          = Color(0xFFE6E6E6)
private val DimRed       = Color(0xFFCC3B3B)

private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
private fun fmtMs(ms: Long): String = dateFmt.format(Date(ms))
private fun fmtBytes(b: Long): String = when {
    b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
    b >= 1_024     -> "%.0f KB".format(b / 1_024.0)
    else           -> "$b B"
}

private fun VaultEntryKind.icon() = when (this) {
    VaultEntryKind.REPORT     -> "▤"
    VaultEntryKind.MEDIA      -> "◈"
    VaultEntryKind.DOCUMENT   -> "⊟"
    VaultEntryKind.NOTE_EXPORT -> "≡"
    VaultEntryKind.OTHER      -> "◻"
}

// =============================================================================
// ENTRY POINT  (called from MainActivity with isUnlocked state)
// =============================================================================

@Composable
fun VaultScreen(
    isUnlocked: Boolean,
    onAuthenticate: () -> Unit,
    onWillPickFile: () -> Unit,
    onWillOpenExternal: () -> Unit,
    vaultRepository: VaultRepository,
    vaultDao: VaultDao,
) {
    if (!isUnlocked) {
        VaultLockedScreen(onAuthenticate = onAuthenticate)
    } else {
        VaultUnlockedScreen(
            onWillPickFile     = onWillPickFile,
            onWillOpenExternal = onWillOpenExternal,
            vaultRepository    = vaultRepository,
            vaultDao           = vaultDao,
        )
    }
}

// =============================================================================
// LOCKED STATE
// =============================================================================

@Composable
private fun VaultLockedScreen(onAuthenticate: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = "⊗",
                color = Color(0xFF2A2A2A),
                fontSize = 64.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "VAULT LOCKED",
                color = Color(0xFF2A2A2A),
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
            )
            Text(
                text = "// authenticate to access secure storage",
                color = Color(0xFF222222),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            Button(
                onClick = onAuthenticate,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF161616)),
            ) {
                Text(
                    text = "UNLOCK VAULT",
                    fontFamily = FontFamily.Monospace,
                    color = Amber,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

// =============================================================================
// UNLOCKED STATE
// =============================================================================

@Composable
private fun VaultUnlockedScreen(
    onWillPickFile: () -> Unit,
    onWillOpenExternal: () -> Unit,
    vaultRepository: VaultRepository,
    vaultDao: VaultDao,
) {
    val entries  = remember { mutableStateListOf<VaultEntryEntity>() }
    var entryToShred by remember { mutableStateOf<VaultEntryEntity?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vaultDao.observeEntries().collect { list ->
            entries.clear()
            entries.addAll(list)
        }
    }

    // Plaintext decrypted for viewing only ever lives in app-private cache; wipe it
    // the moment this screen leaves composition (tab change or vault auto-lock).
    DisposableEffect(Unit) {
        onDispose { vaultRepository.wipeViewCache() }
    }

    // Decrypt an entry to cache and hand it to a system viewer via FileProvider.
    fun viewEntry(entry: VaultEntryEntity) {
        importError = null
        scope.launch {
            runCatching {
                val file = vaultRepository.decryptToCache(entry)
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file,
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, vaultRepository.mimeFor(entry))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                // Launching an external viewer sends us through onStop; tell the
                // Activity not to auto-lock/wipe for this round-trip (same as the
                // import picker) so the viewer can still read the cached plaintext.
                onWillOpenExternal()
                context.startActivity(intent)
            }.onFailure { e ->
                importError = when (e) {
                    is ActivityNotFoundException -> "No app installed to open this file type."
                    else -> "View failed: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val name = vaultRepository.resolveDisplayName(uri)
                val kind = vaultRepository.guessKind(uri)
                vaultRepository.addFile(uri, name, kind)
            }.onFailure { e ->
                importError = "Import failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "ARAWN // SECURE VAULT",
                color = Amber,
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${entries.size} entries",
                color = Color.Gray,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1A1A1A)))

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "// vault is empty\n// import a file to encrypt and store it",
                    color = Color(0xFF3A3A3A),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(entries, key = { it.vaultEntryId }) { entry ->
                    VaultEntryRow(
                        entry    = entry,
                        onView   = { viewEntry(entry) },
                        onShred  = { entryToShred = entry },
                    )
                }
            }
        }

        importError?.let { err ->
            Text(
                text = err,
                color = DimRed,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        Button(
            onClick = { importError = null; onWillPickFile(); importLauncher.launch("*/*") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF161616)),
        ) {
            Text(
                text = "+ IMPORT FILE",
                fontFamily = FontFamily.Monospace,
                color = Amber,
                fontSize = 13.sp,
            )
        }
    }

    entryToShred?.let { entry ->
        AlertDialog(
            onDismissRequest = { entryToShred = null },
            containerColor = PanelBlack,
            title = {
                Text("CRYPTO-SHRED", fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
            },
            text = {
                Text(
                    text = "Permanently destroy \"${entry.displayName}\"?\n\n" +
                        "The encrypted file will be deleted. This cannot be undone.",
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        vaultRepository.shred(entry)
                        entryToShred = null
                    }
                }) {
                    Text("SHRED", fontFamily = FontFamily.Monospace, color = DimRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { entryToShred = null }) {
                    Text("CANCEL", fontFamily = FontFamily.Monospace, color = Color.Gray)
                }
            },
        )
    }
}

// =============================================================================
// ROW
// =============================================================================

@Composable
private fun VaultEntryRow(
    entry: VaultEntryEntity,
    onView: () -> Unit,
    onShred: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBlack, RoundedCornerShape(6.dp))
            .clickable(onClick = onView)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.kind.icon(),
            color = Color(0xFF444444),
            fontFamily = FontFamily.Monospace,
            fontSize = 16.sp,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
        ) {
            Text(
                text = entry.displayName,
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${fmtMs(entry.addedMs)}  ·  ${fmtBytes(entry.sizeBytes)}  ·  ${entry.kind.name}",
                color = Color(0xFF555555),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
        Text(
            text = "[VIEW]",
            color = TerminalGreen,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.clickable(onClick = onView),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "[SHRED]",
            color = Color(0xFF3A1A1A),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.clickable(onClick = onShred),
        )
    }
}
