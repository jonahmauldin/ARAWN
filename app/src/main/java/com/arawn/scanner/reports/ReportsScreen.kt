package com.arawn.scanner.reports

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
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
import com.arawn.core.database.SessionEntity
import com.arawn.core.database.WirelessDao
import com.arawn.scanner.export.HtmlReportExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

private enum class ReportTab { GENERATE, HISTORY }

data class ReportFile(
    val name: String,
    val uri: Uri,
    val sizeBytes: Long,
    val dateMs: Long,
)

// =============================================================================
// ENTRY POINT
// =============================================================================

@Composable
fun ReportsScreen(
    wirelessDao: WirelessDao,
    htmlReportExporter: HtmlReportExporter,
) {
    var activeTab by remember { mutableStateOf(ReportTab.GENERATE) }
    var historyKey by remember { mutableStateOf(0) }
    val context = LocalContext.current

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
                text = "ARAWN // REPORT GENERATOR",
                color = Amber,
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
            )
        }

        // Tab bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ReportTab.entries.forEach { tab ->
                val selected = activeTab == tab
                Text(
                    text = "[ ${tab.name} ]",
                    color = if (selected) Amber else Color(0xFF333333),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { activeTab = tab },
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1A1A1A)))
        Spacer(Modifier.height(4.dp))

        when (activeTab) {
            ReportTab.GENERATE -> GenerateTab(
                wirelessDao = wirelessDao,
                htmlReportExporter = htmlReportExporter,
                onReportGenerated = { historyKey++ },
            )
            ReportTab.HISTORY -> HistoryTab(
                context = context,
                historyKey = historyKey,
            )
        }
    }
}

// =============================================================================
// GENERATE TAB
// =============================================================================

@Composable
private fun GenerateTab(
    wirelessDao: WirelessDao,
    htmlReportExporter: HtmlReportExporter,
    onReportGenerated: () -> Unit,
) {
    val sessions = remember { mutableStateListOf<SessionEntity>() }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var generating by remember { mutableStateOf(false) }
    var resultMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        wirelessDao.observeSessions().collect { list ->
            sessions.clear()
            sessions.addAll(list)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "// no sessions yet\n// run a RECON scan first",
                    color = Color(0xFF3A3A3A),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        } else {
            // Selection controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${selectedIds.size} selected",
                    color = if (selectedIds.isEmpty()) Color(0xFF3A3A3A) else Amber,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (selectedIds.size == sessions.size) "[ DESELECT ALL ]" else "[ SELECT ALL ]",
                    color = Color(0xFF444444),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable {
                        selectedIds = if (selectedIds.size == sessions.size)
                            emptySet()
                        else
                            sessions.map { it.sessionId }.toSet()
                    },
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(sessions, key = { it.sessionId }) { session ->
                    SessionSelectRow(
                        session = session,
                        selected = session.sessionId in selectedIds,
                        onToggle = {
                            selectedIds = if (session.sessionId in selectedIds)
                                selectedIds - session.sessionId
                            else
                                selectedIds + session.sessionId
                        },
                    )
                }
            }
        }

        // Result message
        resultMsg?.let { msg ->
            Text(
                text = msg,
                color = if (msg.startsWith("Report saved")) TerminalGreen else Color(0xFFCC3B3B),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        Button(
            onClick = {
                scope.launch {
                    generating = true
                    resultMsg = null
                    val result = htmlReportExporter.exportSessions(selectedIds.toList())
                    resultMsg = result.message
                    generating = false
                    if (result.success) {
                        selectedIds = emptySet()
                        onReportGenerated()
                    }
                }
            },
            enabled = selectedIds.isNotEmpty() && !generating,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF161616)),
        ) {
            Text(
                text = if (generating) "⊞ GENERATING…" else "⊞ GENERATE HTML REPORT",
                fontFamily = FontFamily.Monospace,
                color = Amber,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun SessionSelectRow(
    session: SessionEntity,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBlack, RoundedCornerShape(4.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (selected) "[✓]" else "[ ]",
            color = if (selected) TerminalGreen else Color.Gray,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
        ) {
            Text(
                text = "#${session.sessionId}  ${fmtMs(session.startTime)}",
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Text(
                text = "${session.pointsCollected} pts  |  ${"%.0f".format(session.totalDistanceM)} m",
                color = Color(0xFF555555),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
        if (session.endTime == null) {
            Text(
                text = "LIVE",
                color = TerminalGreen,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
    }
}

// =============================================================================
// HISTORY TAB
// =============================================================================

@Composable
private fun HistoryTab(context: Context, historyKey: Int) {
    val reports        = remember { mutableStateListOf<ReportFile>() }
    var reportToDelete by remember { mutableStateOf<ReportFile?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(historyKey) {
        val found = withContext(Dispatchers.IO) { queryReports(context) }
        reports.clear()
        reports.addAll(found)
    }

    if (reports.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "// no reports yet\n// generate one in the GENERATE tab",
                color = Color(0xFF3A3A3A),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(reports, key = { it.uri.toString() }) { report ->
                ReportFileRow(
                    report  = report,
                    onClick = { openReport(context, report.uri) },
                    onDelete = { reportToDelete = report },
                )
            }
        }
    }

    // Delete confirm dialog
    reportToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { reportToDelete = null },
            containerColor   = PanelBlack,
            title = {
                Text("DELETE REPORT", fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
            },
            text = {
                Text(
                    "Permanently delete \"${target.name}\"?\n\nThis cannot be undone.",
                    color      = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 12.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val deleted = withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.delete(target.uri, null, null) > 0
                            }.getOrDefault(false)
                        }
                        if (deleted) reports.remove(target)
                        reportToDelete = null
                    }
                }) {
                    Text("DELETE", fontFamily = FontFamily.Monospace, color = DimRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { reportToDelete = null }) {
                    Text("CANCEL", fontFamily = FontFamily.Monospace, color = Color.Gray)
                }
            },
        )
    }
}

@Composable
private fun ReportFileRow(report: ReportFile, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBlack, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = report.name,
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${fmtMs(report.dateMs)}  ·  ${fmtBytes(report.sizeBytes)}",
                color = Color(0xFF555555),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
        Text(
            text = "▶",
            color = Amber,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text     = "[×]",
            color    = Color(0xFF3A1A1A),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.clickable(onClick = onDelete),
        )
    }
}

// =============================================================================
// HELPERS
// =============================================================================

private fun queryReports(context: Context): List<ReportFile> {
    val result = mutableListOf<ReportFile>()
    val resolver = context.contentResolver
    val baseUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.DATE_MODIFIED,
    )
    val selection =
        "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? AND " +
        "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
    val selArgs = arrayOf("%ARAWN%", "ARAWN_Report%.html")
    val order = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

    resolver.query(baseUri, projection, selection, selArgs, order)?.use { cursor ->
        while (cursor.moveToNext()) {
            val id   = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
            val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME))
            val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE))
            val date = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)) * 1000L
            result.add(ReportFile(name, ContentUris.withAppendedId(baseUri, id), size, date))
        }
    }
    return result
}

private fun openReport(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "text/html")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}
