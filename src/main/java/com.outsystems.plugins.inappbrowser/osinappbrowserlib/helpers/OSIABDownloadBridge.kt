package com.outsystems.plugins.inappbrowser.osinappbrowserlib.helpers

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.OutputStream
import java.util.concurrent.Executors

/** Write-only, main-frame bridge. Never reads device files or retrieves credentials.
 * Restricted to the initial WebView HTTP(S) origin, one 50 MiB transfer at a time.
 * Bytes are accepted only AFTER the user chooses a destination in Android's Save UI.
 */
internal class OSIABDownloadBridge(private val activity: AppCompatActivity) {
    private var webView: WebView? = null
    private var allowedOrigin: String? = null
    private var installed = false
    private var documentStart = false
    private var script = ""
    private var transfer: Transfer? = null
    private var closed = false
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { cancel("Download timed out. Please try again.") }

    private class Transfer(val id: String, val size: Long, val reply: JavaScriptReplyProxy) {
        var output: OutputStream? = null // used exclusively on worker
        var received = 0L
        var busy = true
    }

    // Register before Activity STARTED, independently from the existing upload launcher.
    private val saveLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val item = transfer ?: return@registerForActivityResult
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            cancel(null)
        } else {
            resetTimeout()
            worker.execute {
                try {
                    item.output = activity.contentResolver.openOutputStream(uri, "wt")
                        ?: throw IllegalStateException("Unable to open destination")
                    onMain(item) { item.busy = false; reply(item, "next") }
                } catch (_: Exception) { onMain(item) { cancel("Unable to open the selected destination.") } }
            }
        }
    }

    fun attach(view: WebView, initialUrl: String?) {
        webView = view
        allowedOrigin = origin(initialUrl?.let(Uri::parse))
        if (allowedOrigin == null || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        script = activity.assets.open("osiab-download.js").bufferedReader().use { it.readText() }
        WebViewCompat.addWebMessageListener(view, "osiabDownload", setOf(allowedOrigin!!)) {
                _, message, sourceOrigin, isMainFrame, replyProxy ->
            if (!isMainFrame || origin(sourceOrigin) != allowedOrigin ||
                origin(view.url?.let(Uri::parse)) != allowedOrigin || closed) return@addWebMessageListener
            val raw = message.data ?: return@addWebMessageListener
            if (raw.length > 70000) { cancel("Invalid download chunk."); return@addWebMessageListener }
            try { receive(JSONObject(raw), replyProxy) }
            catch (_: Exception) { cancel("Unable to process this download.") }
        }
        installed = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, script, setOf(allowedOrigin!!))
            documentStart = true
        }
        Log.i("OSIABDownload", "2.1.1-mapp.1 bridge installed (launch origin only)")
    }

    fun pageStarted() { cancel(null) }

    fun pageFinished() {
        val view = webView ?: return
        if (installed && !documentStart && origin(view.url?.let(Uri::parse)) == allowedOrigin) {
            view.evaluateJavascript(script, null)
        }
    }

    fun download(url: String, filename: String?, mime: String?): Boolean {
        if (!url.startsWith("blob:", true) && !url.startsWith("data:", true)) return false
        val view = webView
        if (!installed || view == null || origin(view.url?.let(Uri::parse)) != allowedOrigin) {
            notice("This export must be opened from the original website. Please use its download button.")
            return true
        }
        // Anchor interception avoids passing long data URLs through native code.
        if (url.length > 2 * 1024 * 1024) {
            notice("Please use the website's download button for this export.")
            return true
        }
        view.evaluateJavascript(script + "\nwindow.__osiabDownloads && window.__osiabDownloads.download(" +
            JSONObject.quote(url) + "," + JSONObject.quote(filename ?: "") + "," +
            JSONObject.quote(mime ?: "") + ");", null)
        return true
    }

    private fun receive(message: JSONObject, proxy: JavaScriptReplyProxy) {
        val id = message.optString("id")
        if (id.isBlank() || id.length > 100) return
        if (message.optString("type") == "begin") {
            if (transfer != null) {
                proxy.postMessage(JSONObject().put("type", "cancel").put("id", id).toString())
                return
            }
            val size = message.optLong("size", -1)
            if (size < 0 || size > 50L * 1024 * 1024) {
                proxy.postMessage(JSONObject().put("type", "cancel").put("id", id).toString())
                notice("The maximum in-page export size is 50 MiB.")
                return
            }
            val mime = message.optString("mime").substringBefore(';').trim()
                .takeIf { it.matches(Regex("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+")) }
                ?: "application/octet-stream"
            val name = message.optString("name", "download")
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(180).ifBlank { "download" }
            transfer = Transfer(id, size, proxy)
            handler.removeCallbacks(timeout)
            handler.postDelayed(timeout, 300000L) // allow time in the system picker
            saveLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime
                putExtra(Intent.EXTRA_TITLE, name)
            })
            return
        }
        val item = transfer ?: return
        if (id != item.id) return
        if (message.optString("type") == "error") { cancel("The website could not prepare the export."); return }
        if (item.busy) { cancel("Unexpected download data. Please try again."); return }
        when (message.optString("type")) {
            "chunk" -> {
                val data = Base64.decode(message.getString("data"), Base64.NO_WRAP)
                require(data.isNotEmpty() && data.size <= 48 * 1024)
                require(message.getLong("offset") == item.received && item.received + data.size <= item.size)
                item.busy = true
                resetTimeout()
                worker.execute {
                    try {
                        (item.output ?: throw IllegalStateException("No destination")).write(data)
                        onMain(item) { item.received += data.size; item.busy = false; reply(item, "next") }
                    } catch (_: Exception) { onMain(item) { cancel("Unable to save the file. A partial file may remain.") } }
                }
            }
            "end" -> {
                require(item.received == item.size)
                item.busy = true
                worker.execute {
                    try {
                        item.output?.close()
                        item.output = null
                        onMain(item) {
                            handler.removeCallbacks(timeout)
                            reply(item, "done")
                            transfer = null
                            notice("File saved.")
                        }
                    } catch (_: Exception) { onMain(item) { cancel("Unable to finish saving the file.") } }
                }
            }
        }
    }

    private fun resetTimeout() { handler.removeCallbacks(timeout); handler.postDelayed(timeout, 60000L) }
    private fun reply(item: Transfer, type: String) {
        try { item.reply.postMessage(JSONObject().put("type", type).put("id", item.id).toString()) }
        catch (_: Exception) { cancel(null) }
    }
    private fun onMain(item: Transfer, action: () -> Unit) {
        handler.post { if (!closed && transfer === item) action() }
    }
    private fun cancel(message: String?) {
        handler.removeCallbacks(timeout)
        val item = transfer
        transfer = null
        if (item != null) {
            try { item.reply.postMessage(JSONObject().put("type", "cancel").put("id", item.id).toString()) }
            catch (_: Exception) { }
            if (!worker.isShutdown) worker.execute { try { item.output?.close() } catch (_: Exception) { }; item.output = null }
        }
        if (message != null && !closed) notice(message)
    }
    private fun notice(message: String) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
    fun dispose() {
        cancel(null)
        closed = true
        worker.shutdown()
        webView = null
    }
    private fun origin(uri: Uri?): String? {
        val scheme = uri?.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host?.lowercase() ?: return null
        val port = if (uri.port == -1) (if (scheme == "https") 443 else 80) else uri.port
        return "$scheme://$host:$port"
    }
}
