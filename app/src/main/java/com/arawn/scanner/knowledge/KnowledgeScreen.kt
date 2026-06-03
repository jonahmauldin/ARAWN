package com.arawn.scanner.knowledge

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arawn.core.database.DocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Amber        = Color(0xFFE0B341)
private val PanelBlack   = Color(0xFF0A0A0A)
private val Ink          = Color(0xFFE6E6E6)
private val DimRed       = Color(0xFFCC3B3B)

private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
private fun fmtMs(ms: Long): String = dateFmt.format(Date(ms))

private val fieldColors
    @Composable get() = OutlinedTextFieldDefaults.colors(
        focusedTextColor      = Ink,
        unfocusedTextColor    = Ink,
        focusedBorderColor    = Amber,
        unfocusedBorderColor  = Color(0xFF333333),
        cursorColor           = Amber,
        focusedLabelColor     = Amber,
        unfocusedLabelColor   = Color(0xFF666666),
        focusedContainerColor   = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
    )

private val monoTextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

// =============================================================================
// ENTRY POINT
// =============================================================================

@Composable
fun KnowledgeScreen(knowledgeRepository: KnowledgeRepository) {
    var selectedDoc by remember { mutableStateOf<DocumentEntity?>(null) }

    val doc = selectedDoc
    if (doc == null) {
        LibraryView(
            repository = knowledgeRepository,
            onOpen     = { selectedDoc = it },
        )
    } else {
        DocumentViewer(
            doc        = doc,
            repository = knowledgeRepository,
            onBack     = { selectedDoc = null },
        )
    }
}

// =============================================================================
// LIBRARY LIST
// =============================================================================

@Composable
private fun LibraryView(
    repository: KnowledgeRepository,
    onOpen: (DocumentEntity) -> Unit,
) {
    val docs      = remember { mutableStateListOf<DocumentEntity>() }
    var searchQuery   by remember { mutableStateOf("") }
    var pendingUri    by remember { mutableStateOf<Uri?>(null) }
    var importTitle   by remember { mutableStateOf("") }
    var importCategory by remember { mutableStateOf("") }
    var importTags    by remember { mutableStateOf("") }
    var showImportDialog by remember { mutableStateOf(false) }
    var importing     by remember { mutableStateOf(false) }
    var importError   by remember { mutableStateOf<String?>(null) }
    var docToShred    by remember { mutableStateOf<DocumentEntity?>(null) }
    val scope = rememberCoroutineScope()

    // Collect documents (restarts on every query change, cancelling the previous collect).
    LaunchedEffect(searchQuery) {
        val flow = if (searchQuery.isBlank()) repository.observeDocuments()
                   else                       repository.search(searchQuery)
        flow.collect { list ->
            docs.clear()
            docs.addAll(list)
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingUri = uri
        scope.launch {
            val raw = withContext(Dispatchers.IO) { repository.resolveDisplayName(uri) }
            importTitle = raw.substringBeforeLast('.', raw)
            importCategory = ""
            importTags = ""
            showImportDialog = true
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // ---- Header ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text       = "ARAWN // KNOWLEDGE BASE",
                color      = Amber,
                fontFamily = FontFamily.Monospace,
                fontSize   = 15.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text       = "${docs.size} doc${if (docs.size == 1) "" else "s"}",
                color      = Color.Gray,
                fontFamily = FontFamily.Monospace,
                fontSize   = 11.sp,
            )
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1A1A1A)))

        // ---- Search bar ----
        OutlinedTextField(
            value         = searchQuery,
            onValueChange = { searchQuery = it },
            modifier      = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            placeholder   = {
                Text(
                    "// search title, tags",
                    color      = Color(0xFF444444),
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 12.sp,
                )
            },
            singleLine    = true,
            colors        = fieldColors,
            textStyle     = monoTextStyle,
        )

        // ---- Document list ----
        if (docs.isEmpty()) {
            Box(
                modifier            = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment    = Alignment.Center,
            ) {
                Text(
                    text       = if (searchQuery.isBlank())
                        "// library is empty\n// import a PDF or text file"
                    else
                        "// no results for \"$searchQuery\"",
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
                items(docs, key = { it.documentId }) { doc ->
                    DocRow(
                        doc     = doc,
                        onClick = { onOpen(doc) },
                        onShred = { docToShred = doc },
                    )
                }
            }
        }

        // ---- Error + import button ----
        importError?.let { err ->
            Text(
                text       = err,
                color      = DimRed,
                fontFamily = FontFamily.Monospace,
                fontSize   = 11.sp,
                modifier   = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        Button(
            onClick   = { importError = null; filePicker.launch("*/*") },
            enabled   = !importing,
            modifier  = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            colors    = ButtonDefaults.buttonColors(containerColor = Color(0xFF161616)),
        ) {
            Text(
                text       = if (importing) "IMPORTING…" else "+ IMPORT DOCUMENT",
                fontFamily = FontFamily.Monospace,
                color      = Amber,
                fontSize   = 13.sp,
            )
        }
    }

    // ---- Import dialog ----
    if (showImportDialog && pendingUri != null) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            containerColor   = PanelBlack,
            title = {
                Text("IMPORT DOCUMENT", fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value         = importTitle,
                        onValueChange = { importTitle = it },
                        label         = { Text("Title", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                        colors        = fieldColors,
                        textStyle     = monoTextStyle,
                    )
                    OutlinedTextField(
                        value         = importCategory,
                        onValueChange = { importCategory = it },
                        label         = { Text("Category (optional)", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                        colors        = fieldColors,
                        textStyle     = monoTextStyle,
                    )
                    OutlinedTextField(
                        value         = importTags,
                        onValueChange = { importTags = it },
                        label         = { Text("Tags, comma-separated (optional)", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                        colors        = fieldColors,
                        textStyle     = monoTextStyle,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = importTitle.isNotBlank(),
                    onClick = {
                        val uri = pendingUri ?: return@TextButton
                        showImportDialog = false
                        pendingUri = null
                        importing = true
                        importError = null
                        scope.launch {
                            runCatching {
                                repository.importDocument(uri, importTitle, importCategory, importTags)
                            }.onFailure { e ->
                                importError = "Import failed: ${e.message ?: e.javaClass.simpleName}"
                            }
                            importing = false
                        }
                    },
                ) {
                    Text("IMPORT", fontFamily = FontFamily.Monospace, color = Amber)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false; pendingUri = null }) {
                    Text("CANCEL", fontFamily = FontFamily.Monospace, color = Color.Gray)
                }
            },
        )
    }

    // ---- Shred confirm dialog ----
    docToShred?.let { target ->
        AlertDialog(
            onDismissRequest = { docToShred = null },
            containerColor   = PanelBlack,
            title = {
                Text("CRYPTO-SHRED", fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
            },
            text = {
                Text(
                    "Permanently destroy \"${target.title}\"?\n\n" +
                        "The encrypted file will be deleted. This cannot be undone.",
                    color      = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 12.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repository.deleteDocument(target)
                        docToShred = null
                    }
                }) {
                    Text("SHRED", fontFamily = FontFamily.Monospace, color = DimRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { docToShred = null }) {
                    Text("CANCEL", fontFamily = FontFamily.Monospace, color = Color.Gray)
                }
            },
        )
    }
}

@Composable
private fun DocRow(
    doc: DocumentEntity,
    onClick: () -> Unit,
    onShred: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBlack, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⊟", color = Color(0xFF444444), fontFamily = FontFamily.Monospace, fontSize = 16.sp)
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                text       = doc.title,
                color      = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize   = 12.sp,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            val meta = buildString {
                doc.category?.let { append(it).append("  ·  ") }
                append(fmtMs(doc.addedMs))
            }
            Text(meta, color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            if (!doc.tags.isNullOrBlank()) {
                Text("# ${doc.tags}", color = Color(0xFF444444), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }
        Text(
            text     = "[SHRED]",
            color    = Color(0xFF3A1A1A),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier
                .clickable(onClick = onShred)
                .padding(start = 8.dp),
        )
    }
}

// =============================================================================
// DOCUMENT VIEWER
// =============================================================================

@Composable
private fun DocumentViewer(
    doc: DocumentEntity,
    repository: KnowledgeRepository,
    onBack: () -> Unit,
) {
    var tempFile      by remember { mutableStateOf<File?>(null) }
    var isPdf         by remember { mutableStateOf(false) }
    var loadError     by remember { mutableStateOf<String?>(null) }
    var docToShred    by remember { mutableStateOf<DocumentEntity?>(null) }
    val scope = rememberCoroutineScope()

    // Decrypt on IO; check magic bytes for PDF detection.
    LaunchedEffect(doc.documentId) {
        loadError = null
        tempFile = null
        runCatching {
            val f = repository.decryptToTempFile(doc)
            val pdf = withContext(Dispatchers.IO) { isPdfFile(f) }
            f to pdf
        }.onSuccess { (f, pdf) ->
            tempFile = f
            isPdf = pdf
        }.onFailure { e ->
            loadError = e.message ?: "Decrypt failed"
        }
    }

    // Delete temp file when this composable leaves composition.
    DisposableEffect(doc.documentId) {
        onDispose { tempFile?.delete() }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // ---- Header ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text       = "◀ BACK",
                color      = Amber,
                fontFamily = FontFamily.Monospace,
                fontSize   = 13.sp,
                modifier   = Modifier.clickable(onClick = onBack),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text       = doc.title,
                color      = Ink,
                fontFamily = FontFamily.Monospace,
                fontSize   = 13.sp,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier.weight(3f),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text       = "[SHRED]",
                color      = Color(0xFF3A1A1A),
                fontFamily = FontFamily.Monospace,
                fontSize   = 11.sp,
                modifier   = Modifier.clickable { docToShred = doc },
            )
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1A1A1A)))

        // ---- Content area ----
        val file = tempFile
        when {
            loadError != null -> Box(
                modifier         = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "// decrypt error: $loadError",
                    color      = DimRed,
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 12.sp,
                )
            }
            file == null -> Box(
                modifier         = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "// decrypting document…",
                    color      = Color.Gray,
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 12.sp,
                )
            }
            isPdf -> PdfPageViewer(file = file, knownPageCount = doc.pageCount, modifier = Modifier.weight(1f))
            else  -> TextFileViewer(file = file, modifier = Modifier.weight(1f))
        }
    }

    // ---- Shred confirm dialog (navigates back on confirm) ----
    docToShred?.let { target ->
        AlertDialog(
            onDismissRequest = { docToShred = null },
            containerColor   = PanelBlack,
            title = {
                Text("CRYPTO-SHRED", fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
            },
            text = {
                Text(
                    "Permanently destroy \"${target.title}\"?\n\n" +
                        "The encrypted file will be deleted. This cannot be undone.",
                    color      = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 12.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repository.deleteDocument(target)
                        docToShred = null
                        onBack()
                    }
                }) {
                    Text("SHRED", fontFamily = FontFamily.Monospace, color = DimRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { docToShred = null }) {
                    Text("CANCEL", fontFamily = FontFamily.Monospace, color = Color.Gray)
                }
            },
        )
    }
}

// =============================================================================
// PDF VIEWER
// =============================================================================

@Composable
private fun PdfPageViewer(file: File, knownPageCount: Int? = null, modifier: Modifier = Modifier) {
    val density      = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val renderWidthPx = remember(screenWidthDp, density) {
        with(density) { screenWidthDp.dp.roundToPx() }
    }

    var pageIndex  by remember { mutableStateOf(0) }
    // Pre-seed from the stored page count so the total is shown before first render.
    var totalPages by remember { mutableStateOf(knownPageCount ?: 0) }
    var bitmap     by remember { mutableStateOf<Bitmap?>(null) }
    var renderError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(file, pageIndex, renderWidthPx) {
        bitmap = null
        renderError = null
        // Render entirely on IO; return a Result pair so state mutations stay on main.
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    val renderer = PdfRenderer(pfd)
                    try {
                        val pages    = renderer.pageCount
                        val safePage = pageIndex.coerceIn(0, (pages - 1).coerceAtLeast(0))
                        val page     = renderer.openPage(safePage)
                        try {
                            val w  = renderWidthPx
                            val h  = (w.toFloat() / page.width * page.height).toInt().coerceAtLeast(1)
                            val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bm.eraseColor(android.graphics.Color.WHITE)
                            page.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            pages to bm
                        } finally { page.close() }
                    } finally { renderer.close() }
                } finally { pfd.close() }
            }
        }
        // Back on main — safe to write Compose state.
        result.onSuccess { (pages, bm) ->
            totalPages = pages
            bitmap     = bm
        }.onFailure { e ->
            renderError = e.message ?: "Render failed"
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Page bitmap (scrollable in case height exceeds screen)
        Box(
            modifier         = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            val bm = bitmap
            when {
                renderError != null -> Text(
                    "// cannot render page: $renderError",
                    color      = DimRed,
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 12.sp,
                    modifier   = Modifier.padding(16.dp),
                )
                bm != null -> Image(
                    bitmap             = bm.asImageBitmap(),
                    contentDescription = null,
                    modifier           = Modifier.fillMaxWidth(),
                    contentScale       = ContentScale.FillWidth,
                )
                else -> Text(
                    "// rendering page ${pageIndex + 1}…",
                    color      = Color.Gray,
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 12.sp,
                    modifier   = Modifier.padding(16.dp),
                )
            }
        }

        // Page navigation (only shown when there is more than one page)
        if (totalPages > 1) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1A1A1A)))
            Row(
                modifier            = Modifier
                    .fillMaxWidth()
                    .background(PanelBlack)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment   = Alignment.CenterVertically,
            ) {
                Text(
                    text     = "◀ PREV",
                    color    = if (pageIndex > 0) Amber else Color(0xFF333333),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable(enabled = pageIndex > 0) { pageIndex-- },
                )
                Text(
                    text       = "${pageIndex + 1} / $totalPages",
                    color      = Color.Gray,
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 12.sp,
                )
                Text(
                    text     = "NEXT ▶",
                    color    = if (pageIndex < totalPages - 1) Amber else Color(0xFF333333),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable(enabled = pageIndex < totalPages - 1) { pageIndex++ },
                )
            }
        }
    }
}

// =============================================================================
// TEXT VIEWER
// =============================================================================

@Composable
private fun TextFileViewer(file: File, modifier: Modifier = Modifier) {
    var content by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(file) {
        content = withContext(Dispatchers.IO) {
            runCatching { file.readText() }
                .getOrElse { "// Cannot read file: ${it.message}" }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(
            text       = content ?: "// loading…",
            color      = Ink,
            fontFamily = FontFamily.Monospace,
            fontSize   = 12.sp,
        )
    }
}

// =============================================================================
// HELPERS
// =============================================================================

private fun isPdfFile(file: File): Boolean = runCatching {
    file.inputStream().use { stream ->
        val magic = ByteArray(4)
        stream.read(magic) == 4 &&
            magic[0] == '%'.code.toByte() &&
            magic[1] == 'P'.code.toByte()
    }
}.getOrDefault(false)
