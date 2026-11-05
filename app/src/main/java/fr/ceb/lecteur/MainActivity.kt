package fr.ceb.lecteur

import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import fr.ceb.lecteur.ui.HomeScreen
import fr.ceb.lecteur.ui.LecteurTheme
import fr.ceb.lecteur.ui.ViewerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var store: DocStore

    private var currentPath by mutableStateOf<String?>(null)
    private var recents by mutableStateOf<List<DocItem>>(emptyList())
    private var loading by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)

    /** Ouvert depuis une autre app (mail, Fichiers…) : « retour » y ramène directement. */
    private var openedExternally = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importAndOpen(uri, external = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        store = DocStore(applicationContext)
        currentPath = savedInstanceState?.getString(KEY_PATH)
        openedExternally = savedInstanceState?.getBoolean(KEY_EXTERNAL) ?: false
        refresh()
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            LecteurTheme { AppContent() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PATH, currentPath)
        outState.putBoolean(KEY_EXTERNAL, openedExternally)
    }

    @Composable
    private fun AppContent() {
        val path = currentPath
        val item = remember(path) { path?.let { store.fromPath(it) } }

        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (item == null) {
                HomeScreen(
                    recents = recents,
                    onOpenPicker = { openPicker() },
                    onOpen = { openRecent(it) },
                    onRemove = {
                        store.remove(it)
                        refresh()
                    },
                )
            } else {
                BackHandler { closeViewer() }
                ViewerScreen(
                    item = item,
                    onBack = { closeViewer() },
                    onShare = { share(item) },
                    onRotate = { toggleOrientation() },
                )
            }

            if (loading) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }

            message?.let { msg ->
                AlertDialog(
                    onDismissRequest = { message = null },
                    confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
                    text = { Text(msg) },
                )
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) importAndOpen(uri, external = true)
    }

    private fun openPicker() {
        try {
            picker.launch(arrayOf(DocType.PDF.mime, DocType.DOCX.mime))
        } catch (e: Exception) {
            message = "Aucun gestionnaire de fichiers disponible."
        }
    }

    private fun importAndOpen(uri: Uri, external: Boolean) {
        lifecycleScope.launch {
            loading = true
            try {
                val item = withContext(Dispatchers.IO) { store.import(uri) }
                openedExternally = external
                currentPath = item.file.absolutePath
                refresh()
            } catch (e: UnsupportedDocException) {
                message = e.message
            } catch (e: Exception) {
                message = "Impossible d'ouvrir ce fichier."
            } finally {
                loading = false
            }
        }
    }

    private fun openRecent(item: DocItem) {
        store.touch(item)
        openedExternally = false
        currentPath = item.file.absolutePath
        refresh()
    }

    private fun closeViewer() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (openedExternally) {
            finish()
            return
        }
        currentPath = null
        refresh()
    }

    private fun toggleOrientation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun share(item: DocItem) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", item.file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = item.type.mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, item.name)
                clipData = ClipData.newRawUri(item.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Partager « ${item.name} »"))
        } catch (e: Exception) {
            message = "Le partage a échoué."
        }
    }

    private fun refresh() {
        recents = store.recents()
    }

    private companion object {
        const val KEY_PATH = "path"
        const val KEY_EXTERNAL = "external"
    }
}
