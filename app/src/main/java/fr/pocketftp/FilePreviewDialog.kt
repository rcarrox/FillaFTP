package fr.pocketftp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun FilePreviewDialog(file: File, name: String, onDismiss: () -> Unit) {
    if (file.extension.equals("pdf", ignoreCase = true)) {
        PdfPreviewDialog(file, name, onDismiss)
    } else {
        ImagePreviewDialog(file, name, onDismiss)
    }
}

@Composable
private fun ImagePreviewDialog(file: File, name: String, onDismiss: () -> Unit) {
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var loading by remember(file) { mutableStateOf(true) }

    LaunchedEffect(file) {
        val decoded = withContext(Dispatchers.IO) { decodePreviewImage(file) }
        bitmap = decoded
        if (decoded == null) error = "Impossible d'afficher cette image."
        loading = false
    }
    DisposableEffect(bitmap) {
        val current = bitmap
        onDispose { current?.recycle() }
    }

    PreviewDialogFrame(name, onDismiss) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            when {
                loading -> CircularProgressIndicator()
                bitmap != null -> Image(
                    bitmap = bitmap!!.asImageBitmap(), contentDescription = name,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit
                )
                else -> Text(error ?: "Aperçu indisponible.", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private data class RenderedPdfPage(val bitmap: Bitmap, val pageCount: Int)

@Composable
private fun PdfPreviewDialog(file: File, name: String, onDismiss: () -> Unit) {
    var pageIndex by remember(file) { mutableIntStateOf(0) }
    var pageCount by remember(file) { mutableIntStateOf(0) }
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var loading by remember(file) { mutableStateOf(true) }

    LaunchedEffect(file, pageIndex) {
        loading = true
        error = null
        bitmap = null
        try {
            val page = withContext(Dispatchers.IO) { renderPdfPage(file, pageIndex) }
            pageCount = page.pageCount
            pageIndex = pageIndex.coerceIn(0, page.pageCount - 1)
            bitmap = page.bitmap
        } catch (e: Exception) {
            error = e.localizedMessage ?: "Impossible d'ouvrir ce PDF."
        } finally {
            loading = false
        }
    }
    DisposableEffect(bitmap) {
        val current = bitmap
        onDispose { current?.recycle() }
    }

    PreviewDialogFrame(name, onDismiss) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            when {
                loading -> CircularProgressIndicator()
                bitmap != null -> Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "Page ${pageIndex + 1} de $name",
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit
                )
                else -> Text(error ?: "Aperçu indisponible.", color = MaterialTheme.colorScheme.error)
            }
        }
        if (pageCount > 0) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = { pageIndex-- }, enabled = pageIndex > 0 && !loading) { Text("‹ Précédente") }
                Text("Page ${pageIndex + 1} / $pageCount", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { pageIndex++ }, enabled = pageIndex + 1 < pageCount && !loading) { Text("Suivante ›") }
            }
        }
    }
}

@Composable
private fun PreviewDialogFrame(name: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(.92f).widthIn(max = 720.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name, modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = onDismiss) { Text("Fermer") }
                }
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}

private fun decodePreviewImage(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > 1_600 || bounds.outHeight / sampleSize > 1_600) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}

private fun renderPdfPage(file: File, index: Int): RenderedPdfPage {
    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = PdfRenderer(descriptor)
    try {
        require(renderer.pageCount > 0) { "Ce PDF ne contient aucune page." }
        val page = renderer.openPage(index.coerceIn(0, renderer.pageCount - 1))
        try {
            val scale = minOf(1f, 1_600f / page.width, 2_000f / page.height)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return RenderedPdfPage(bitmap, renderer.pageCount)
        } finally {
            page.close()
        }
    } finally {
        renderer.close()
        descriptor.close()
    }
}
