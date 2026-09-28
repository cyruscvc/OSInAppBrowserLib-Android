package com.outsystems.plugins.inappbrowser.osinappbrowserlib.views

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.webkit.WebView
import android.widget.Toast
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/** Only opens Teams chat. No general native-command dispatcher is exposed. */
internal class OSIABTeamsMessageBridge(private val activity: Activity) {
    private var webView: WebView? = null
    private var allowed = emptySet<String>()
    private var script: String? = null
    private var handler: ScriptHandler? = null
    private var installed = false
    private var lastLaunch = -1500L

    private fun origin(value: String?): String? = try {
        val uri = URI(value ?: "")
        val host = uri.host?.lowercase(Locale.ROOT)
        if (uri.scheme?.lowercase(Locale.ROOT) != "https" || host.isNullOrEmpty() || uri.userInfo != null ||
            uri.port < -1 || uri.port > 65535 || uri.port == 0) null
        else "https://$host" + if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
    } catch (_: Exception) { null }

    fun attach(view: WebView, origins: List<String>) {
        allowed = origins.take(10).mapNotNull { raw ->
            val parsed = origin(raw)
            if (parsed != null && (raw == parsed || raw == "$parsed/")) parsed else null
        }.toSet()
        if (allowed.isEmpty() || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        val source = activity.assets.open("osiab-teams.js").bufferedReader().use { it.readText() }
            .replace("__MAPP_TEAMS_ORIGINS__", JSONArray(allowed.toList()).toString())
        webView = view
        script = source
        WebViewCompat.addWebMessageListener(view, "mappTeamsNative", allowed) { current, message, sourceOrigin, mainFrame, _ ->
            if (!mainFrame || activity.isFinishing || activity.isDestroyed || current !== webView) return@addWebMessageListener
            val sender = origin(sourceOrigin.toString())
            if (sender !in allowed || sender != origin(current.url)) return@addWebMessageListener
            if (message.type != WebMessageCompat.TYPE_STRING) return@addWebMessageListener
            val text = message.data ?: return@addWebMessageListener
            if (text.length > 4096) return@addWebMessageListener
            val payload = try { JSONObject(text) } catch (_: Exception) { return@addWebMessageListener }
            if (payload.opt("type") != "cbt:open-teams") return@addWebMessageListener
            val email = (payload.opt("email") as? String)?.trim() ?: return@addWebMessageListener
            if (email.length > 254 || !Regex("^[^\\s@,;]+@[^\\s@,;]+\\.[^\\s@,;]+$").matches(email)) return@addWebMessageListener
            val now = SystemClock.elapsedRealtime()
            if (now - lastLaunch < 1500) return@addWebMessageListener
            lastLaunch = now
            val uri = Uri.Builder().scheme("https").authority("teams.microsoft.com")
                .path("/l/chat/0/0").appendQueryParameter("users", email).build()
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.microsoft.teams"))
            } catch (_: Exception) {
                Toast.makeText(activity, "Unable to open Microsoft Teams. Check that it is installed and available.", Toast.LENGTH_LONG).show()
            }
        }
        installed = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            handler = WebViewCompat.addDocumentStartJavaScript(view, source, allowed)
        }
    }

    fun pageFinished() {
        val view = webView ?: return
        if (origin(view.url) in allowed) script?.let { view.evaluateJavascript(it, null) }
    }

    fun dispose() {
        handler?.remove()
        handler = null
        if (installed) webView?.let { WebViewCompat.removeWebMessageListener(it, "mappTeamsNative") }
        installed = false
        webView = null
    }
}
