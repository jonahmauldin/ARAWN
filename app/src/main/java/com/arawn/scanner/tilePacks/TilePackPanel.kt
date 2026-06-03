package com.arawn.scanner.tilePacks

import android.content.Context
import android.content.SharedPreferences
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Amber      = Color(0xFFE0B341)
private val PanelBlack = Color(0xFF0A0A0A)
private val Ink        = Color(0xFFE6E6E6)
private val DimRed     = Color(0xFFCC3B3B)
private val TermGreen  = Color(0xFF35D07F)

private const val PREFS_NAME   = "arawn_tile_packs"
private const val KEY_ACTIVE   = "active_pack"

// =============================================================================
// Public API
// =============================================================================

/** Directory where tile packs (.mbtiles / .sqlite) are stored. */
fun tilePackDir(context: Context): File =
    (context.getExternalFilesDir("tiles") ?: File(context.filesDir, "tiles"))
        .also { it.mkdirs() }

/** Returns the currently active tile pack File, or null (= online MAPNIK). */
fun loadActivePack(context: Context): File? {
    val name = tilePackPrefs(context).getString(KEY_ACTIVE, null) ?: return null
    val f = File(tilePackDir(context), name)
    return if (f.exists()) f else null
}

private fun tilePackPrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

// =============================================================================
// UI
// =============================================================================

/**
 * Tile-pack management panel shown in the OPS → PACKS tab.
 *
 * Lists .mbtiles/.sqlite archives from the app's private external tiles
 * directory. Users can import archives via the system file picker, mark one
 * as active (used by the Ops map), or delete packs to reclaim space.
 *
 * Switching the active pack rebuilds the OpsMapPanel MapView immediately
 * (because [onPackChanged] updates state that is keyed to the MapView's
 * remember). MAPNIK (online) is restored when the active pack is cleared.
 */
@Composable
fun TilePackPanel(
    activePack: File?,
    onPackChanged: (File?) -> Unit,
) {
    val context     = LocalContext.current
    val scope       = rememberCoroutineScope()
    val packs       = remember { mutableStateListOf<File>() }
    var importing   by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var packToDelete by remember { mutableStateOf<File?>(null) }

    // Scan directory on first composition.
    remember {
        packs.addAll(tilePackDir(context).listFiles()
            ?.filter { it.extension.lowercase() in listOf("mbtiles", "sqlite", "zip") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList())
        Unit
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        importError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val srcName = uri.lastPathSegment?.substringAfterLast('/') ?: "tilePack.mbtiles"
                    val dest = File(tilePackDir(context), srcName)
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    dest
                }
            }
            importing = false
            result.onSuccess { file ->
                if (!packs.contains(file)) packs.add(0, file)
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
        // ── Info header ────────────────────────────────────────────────────
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text       = "OFFLINE TILE PACKS",
                color      = Amber,
                fontFamily = FontFamily.Monospace,
                fontSize   = 13.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text       = "Supported: .mbtiles  .sqlite  .zip (osmdroid)\n" +
                    "Import or copy to: …/Android/data/com.arawn.scanner/files/tiles/",
                color      = Color(0xFF555555),
                fontFamily = FontFamily.Monospace,
                fontSize   = 10.sp,
            )
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1A1A1A)))

        // ── Active pack status ─────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text       = if (activePack != null) "● ${activePack.name}" else "● ONLINE (MAPNIK)",
                color      = if (activePack != null) TermGreen else Color.Gray,
                fontFamily = FontFamily.Monospace,
                fontSize   = 11.sp,
                modifier   = Modifier.weight(1f),
            )
            if (activePack != null) {
                Text(
                    text       = "[ USE ONLINE ]",
                    color      = Color(0xFF444444),
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 11.sp,
                    modifier   = Modifier.clickable {
                        tilePackPrefs(context).edit().remove(KEY_ACTIVE).apply()
                        onPackChanged(null)
                    },
                )
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1A1A1A)))

        // ── Pack list ──────────────────────────────────────────────────────
        if (packs.isEmpty()) {
            Box(
                modifier         = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text       = "// no tile packs imported yet\n// tap IMPORT to add one",
                    color      = Color(0xFF3A3A3A),
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 12.sp,
                )
            }
        } else {
            LazyColumn(
                modifier        = Modifier.weight(1f).fillMaxWidth(),
                contentPadding  = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(packs, key = { it.absolutePath }) { pack ->
                    TilePackRow(
                        pack       = pack,
                        isActive   = pack.absolutePath == activePack?.absolutePath,
                        onUse      = {
                            tilePackPrefs(context).edit()
                                .putString(KEY_ACTIVE, pack.name)
                                .apply()
                            onPackChanged(pack)
                        },
                        onDelete   = { packToDelete = pack },
                    )
                }
            }
        }

        // ── Error + import button ──────────────────────────────────────────
        importError?.let { err ->
            Text(
                text       = err,
                color      = DimRed,
                fontFamily = FontFamily.Monospace,
                fontSize   = 11.sp,
                modifier   = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        Text(
            text       = if (importing) "// copying file…" else "+ IMPORT TILE PACK",
            color      = if (importing) Color.Gray else Amber,
            fontFamily = FontFamily.Monospace,
            fontSize   = 13.sp,
            modifier   = Modifier
                .fillMaxWidth()
                .clickable(enabled = !importing) {
                    importError = null
                    filePicker.launch("*/*")
                }
                .padding(horizontal = 12.dp, vertical = 14.dp),
        )
    }

    // ── Delete confirm dialog ──────────────────────────────────────────────
    packToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { packToDelete = null },
            containerColor   = PanelBlack,
            title = {
                Text("DELETE TILE PACK", fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
            },
            text = {
                Text(
                    "Delete \"${target.name}\"?\n" +
                        "${"%.1f".format(target.length() / 1_048_576.0)} MB will be freed.",
                    color      = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 12.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (activePack?.absolutePath == target.absolutePath) {
                        tilePackPrefs(context).edit().remove(KEY_ACTIVE).apply()
                        onPackChanged(null)
                    }
                    target.delete()
                    packs.remove(target)
                    packToDelete = null
                }) {
                    Text("DELETE", fontFamily = FontFamily.Monospace, color = DimRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { packToDelete = null }) {
                    Text("CANCEL", fontFamily = FontFamily.Monospace, color = Color.Gray)
                }
            },
        )
    }
}

@Composable
private fun TilePackRow(
    pack: File,
    isActive: Boolean,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    val sizeMb = pack.length() / 1_048_576.0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBlack, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = pack.name,
                color      = if (isActive) TermGreen else Ink,
                fontFamily = FontFamily.Monospace,
                fontSize   = 12.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text       = "${"%.1f".format(sizeMb)} MB" +
                    if (isActive) "  ●  ACTIVE" else "",
                color      = if (isActive) TermGreen else Color(0xFF555555),
                fontFamily = FontFamily.Monospace,
                fontSize   = 10.sp,
            )
        }
        if (!isActive) {
            Text(
                text       = "[ USE ]",
                color      = Color(0xFF444444),
                fontFamily = FontFamily.Monospace,
                fontSize   = 11.sp,
                modifier   = Modifier
                    .clickable(onClick = onUse)
                    .padding(horizontal = 8.dp),
            )
        }
        Text(
            text       = "[×]",
            color      = Color(0xFF3A1A1A),
            fontFamily = FontFamily.Monospace,
            fontSize   = 11.sp,
            modifier   = Modifier.clickable(onClick = onDelete),
        )
    }
}
