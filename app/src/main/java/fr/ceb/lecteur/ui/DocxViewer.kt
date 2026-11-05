package fr.ceb.lecteur.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import fr.ceb.lecteur.DocxToHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DocxViewer(file: File) {
    var html by remember(file) { mutableStateOf<String?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }

    LaunchedEffect(file) {
        try {
            html = withContext(Dispatchers.IO) { DocxToHtml.convert(file) }
        } catch (e: OutOfMemoryError) {
            error = "Ce document est trop volumineux pour être affiché."
        } catch (e: Exception) {
            error = "Impossible de lire ce document Word (fichier endommagé ?)."
        }
    }

    val content = html
    when {
        error != null -> CenterMessage(error!!)
        content == null -> CenterLoader()
        else -> AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.WHITE)
                    isVerticalScrollBarEnabled = true
                    settings.apply {
                        javaScriptEnabled = false
                        builtInZoomControls = true
                        displayZoomControls = false
                        setSupportZoom(true)
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        allowFileAccess = false
                        allowContentAccess = false
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            runCatching {
                                view.context.startActivity(
                                    Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                            return true
                        }
                    }
                    loadDataWithBaseURL(null, content, "text/html", "utf-8", null)
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
