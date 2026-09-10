package app.consolepocket.downloads

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import app.consolepocket.cache.HttpAssetFetcher
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * In-App-Downloads (PLAN Kapitel 7): Exporte aus der Console (Billing-CSV, Rechnungs-PDF,
 * SSH-Schluessel) landen in dieser Liste und werden NIE an einen Browser uebergeben.
 *
 * Speicherort ist der app-private externe Ordner `Android/data/<pkg>/files/Download`,
 * freigegeben ueber den FileProvider (res/xml/file_paths.xml).
 */
class DownloadStore(
    private val context: Context,
    private val client: OkHttpClient = HttpAssetFetcher.defaultClient(connectTimeoutSeconds = 10, readTimeoutSeconds = 60),
) {

    enum class State { RUNNING, DONE, FAILED }

    data class Item(
        val id: String,
        val url: String,
        val fileName: String,
        val mimeType: String,
        val totalBytes: Long,
        val downloadedBytes: Long,
        val state: State,
        val file: File?,
        val error: String? = null,
        val startedAt: Long = System.currentTimeMillis(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    private val downloadDir: File?
        get() = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.also { it.mkdirs() }

    fun enqueue(
        url: String,
        fileName: String,
        mimeType: String?,
        contentLength: Long,
        userAgent: String?,
        cookie: String?,
    ) {
        val dir = downloadDir ?: return
        val id = System.currentTimeMillis().toString(36) + fileName.hashCode().toString(36)
        val safeName = uniqueName(dir, sanitize(fileName))
        val target = File(dir, safeName)
        val mime = mimeType ?: guessMime(safeName)

        update { it + Item(id, url, safeName, mime, contentLength, 0, State.RUNNING, null) }

        scope.launch {
            runCatching {
                val builder = Request.Builder().url(url)
                if (!userAgent.isNullOrBlank()) builder.header("User-Agent", userAgent)
                if (!cookie.isNullOrBlank()) builder.header("Cookie", cookie)

                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("Leere Antwort")
                    val total = if (contentLength > 0) contentLength else body.contentLength()
                    target.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var read = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                read += n
                                updateItem(id) { copy(downloadedBytes = read, totalBytes = total) }
                            }
                        }
                    }
                    updateItem(id) { copy(state = State.DONE, file = target, downloadedBytes = target.length()) }
                }
            }.onFailure { t ->
                runCatching { target.delete() }
                updateItem(id) { copy(state = State.FAILED, error = t.message ?: t.javaClass.simpleName) }
            }
        }
    }

    fun delete(id: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        item.file?.let { runCatching { it.delete() } }
        update { list -> list.filterNot { it.id == id } }
    }

    fun shareUri(item: Item): Uri? = item.file?.takeIf { it.exists() }?.let { file ->
        runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

    private fun update(block: (List<Item>) -> List<Item>) {
        _items.value = block(_items.value)
    }

    private fun updateItem(id: String, block: Item.() -> Item) {
        update { list -> list.map { if (it.id == id) it.block() else it } }
    }

    private fun uniqueName(dir: File, name: String): String {
        var candidate = File(dir, name)
        var counter = 1
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        while (candidate.exists()) {
            candidate = File(dir, "$base-$counter$ext")
            counter++
        }
        return candidate.name
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._\\-]"), "_").trim('_')
        return cleaned.ifBlank { "download.bin" }
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "csv" -> "text/csv"
        "pdf" -> "application/pdf"
        "json" -> "application/json"
        "zip" -> "application/zip"
        "pem", "key" -> "application/x-pem-file"
        "txt", "log" -> "text/plain"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }
}
