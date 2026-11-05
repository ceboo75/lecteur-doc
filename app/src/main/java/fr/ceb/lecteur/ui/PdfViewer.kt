package fr.ceb.lecteur.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f

/** Rendu PDF natif d'Android (PdfRenderer) : rapide, sans bibliothèque externe. */
private class PdfDoc(file: File) {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try {
        PdfRenderer(pfd)
    } catch (e: Exception) {
        pfd.close()
        throw e
    }
    private val mutex = Mutex()
    @Volatile private var closed = false

    val pageCount: Int = renderer.pageCount

    private val ratios = FloatArray(pageCount) { i ->
        val page = renderer.openPage(i)
        try {
            if (page.height > 0) page.width.toFloat() / page.height else 0.707f
        } finally {
            page.close()
        }
    }

    fun ratio(index: Int): Float = ratios[index]

    suspend fun render(index: Int, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock null
            val page = renderer.openPage(index)
            try {
                val w = width.coerceAtLeast(1)
                val h = (w / ratios[index]).roundToInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            } catch (e: OutOfMemoryError) {
                null
            } finally {
                page.close()
            }
        }
    }

    fun close() {
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                if (!closed) {
                    closed = true
                    runCatching { renderer.close() }
                    runCatching { pfd.close() }
                }
            }
        }
    }
}

@Composable
fun PdfViewer(file: File, bottomInset: Dp) {
    var doc by remember(file) { mutableStateOf<PdfDoc?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }

    LaunchedEffect(file) {
        try {
            doc = withContext(Dispatchers.IO) { PdfDoc(file) }
        } catch (e: SecurityException) {
            error = "Ce PDF est protégé par un mot de passe : il ne peut pas être ouvert ici."
        } catch (e: Exception) {
            error = "Impossible d'ouvrir ce PDF (fichier endommagé ?)."
        }
    }
    DisposableEffect(file) {
        onDispose { doc?.close() }
    }

    val d = doc
    when {
        error != null -> CenterMessage(error!!)
        d == null -> CenterLoader()
        d.pageCount == 0 -> CenterMessage("Ce PDF ne contient aucune page.")
        else -> PdfPages(d, bottomInset)
    }
}

@Composable
private fun PdfPages(doc: PdfDoc, bottomInset: Dp) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var zoom by remember { mutableFloatStateOf(1f) }
    // Décalage horizontal (≤ 0) quand la page zoomée dépasse l'écran.
    var pan by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportW = constraints.maxWidth.toFloat()
        val marginPx = with(density) { 8.dp.toPx() }
        val basePageW = (viewportW - 2 * marginPx).coerceAtLeast(1f)

        fun minPan(z: Float) = (viewportW - 2 * marginPx - basePageW * z).coerceAtMost(0f)

        fun applyZoom(target: Float, focusX: Float, focusY: Float) {
            val newZoom = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
            val factor = newZoom / zoom
            if (abs(factor - 1f) < 0.0001f) return
            val pageX = focusX - marginPx - pan
            pan = (focusX - marginPx - pageX * factor).coerceIn(minPan(newZoom), 0f)
            val dy = (listState.firstVisibleItemScrollOffset + focusY) * (factor - 1f)
            zoom = newZoom
            listState.dispatchRawDelta(dy)
        }

        // Qualité de rendu : on re-dessine plus fin quand on zoome franchement.
        val quality by remember { derivedStateOf { if (zoom < 1.4f) 1f else if (zoom < 2.4f) 2f else 3f } }
        val renderWidth = min(
            (basePageW * quality).toInt(),
            if (quality == 1f) 2600 else 3600,
        )

        val gestures = Modifier
            .pointerInput(viewportW) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2) {
                            val zoomChange = event.calculateZoom()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val panChange = event.calculatePan()
                            if (centroid != Offset.Unspecified && zoomChange != 1f) {
                                applyZoom(zoom * zoomChange, centroid.x, centroid.y)
                            }
                            pan = (pan + panChange.x).coerceIn(minPan(zoom), 0f)
                            if (panChange.y != 0f) listState.dispatchRawDelta(-panChange.y)
                            event.changes.forEach { it.consume() }
                        } else if (zoom > 1f) {
                            // Un doigt : le défilement vertical reste natif, on gère juste le latéral.
                            val panChange = event.calculatePan()
                            pan = (pan + panChange.x).coerceIn(minPan(zoom), 0f)
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(viewportW) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        scope.launch {
                            val target = if (zoom > 1.2f) 1f else 2.5f
                            animate(zoom, target, animationSpec = tween(260)) { value, _ ->
                                applyZoom(value, tap.x, tap.y)
                            }
                        }
                    },
                )
            }

        Box(Modifier.fillMaxSize().then(gestures)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 10.dp, bottom = bottomInset + 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(doc.pageCount) { index ->
                    val pageWidthDp = with(density) { (basePageW * zoom).toDp() }
                    PdfPage(
                        doc = doc,
                        index = index,
                        renderWidth = renderWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentWidth(Alignment.Start, unbounded = true)
                            .requiredWidth(pageWidthDp)
                            .aspectRatio(doc.ratio(index))
                            .graphicsLayer { translationX = marginPx + pan },
                    )
                }
            }

            PageIndicator(
                listState = listState,
                total = doc.pageCount,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 20.dp),
            )
        }
    }
}

@Composable
private fun PdfPage(doc: PdfDoc, index: Int, renderWidth: Int, modifier: Modifier) {
    var bitmap by remember(doc, index) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(doc, index, renderWidth) {
        if (bitmap != null) delay(150) // évite de re-rendre pendant un pincement
        doc.render(index, renderWidth)?.let { bitmap = it.asImageBitmap() }
    }

    Box(modifier.shadow(1.5.dp, RectangleShape).background(Color.White)) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = "Page ${index + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.High,
            )
        }
    }
}

@Composable
private fun PageIndicator(
    listState: androidx.compose.foundation.lazy.LazyListState,
    total: Int,
    modifier: Modifier,
) {
    val current by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) {
                1
            } else {
                val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
                (visible.firstOrNull { it.offset + it.size > middle } ?: visible.last()).index + 1
            }
        }
    }
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            visible = true
        } else {
            delay(1800)
            visible = false
        }
    }

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Surface(shape = CircleShape, color = Color(0xCC1B1C1E), contentColor = Color.White) {
            Text(
                "$current / $total",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}
