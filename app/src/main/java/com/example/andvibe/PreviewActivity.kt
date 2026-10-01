package com.example.andvibe

import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLDecoder

class PreviewActivity : AppCompatActivity() {
    private lateinit var web: WebView

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (::web.isInitialized && web.canGoBack()) web.goBack() else finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        val repos = File(filesDir, "repos").canonicalFile
        val page = path?.let { File(it).canonicalFile }
        if (page == null || !page.isFile || !page.path.startsWith(repos.path + File.separator)) {
            finish()
            return
        }
        val site = page.parentFile ?: run {
            finish()
            return
        }
        web = WebView(this)
        setContentView(web)
        onBackPressedDispatcher.addCallback(this, backCallback)
        web.setBackgroundColor(0xFF121212.toInt())
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        web.settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        web.settings.allowUniversalAccessFromFileURLs = false
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.settings.builtInZoomControls = true
        web.settings.displayZoomControls = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url ?: return null
                if (uri.host != HOST) return null
                return serve(site, uri.path)
            }
        }
        val html = page.readText()
        web.loadDataWithBaseURL("https://$HOST/${page.name}", html, "text/html", "utf-8", null)
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            web.stopLoading()
            web.destroy()
        }
        super.onDestroy()
    }

    private fun serve(site: File, rawPath: String?): WebResourceResponse {
        val decoded = try {
            URLDecoder.decode(rawPath ?: "/", "UTF-8")
        } catch (_: Exception) {
            return notFound()
        }
        if (decoded.split('/').any { it == ".." }) return notFound()
        val relative = decoded.trimStart('/')
        if (relative.isBlank()) return notFound()
        val target = File(site, relative).canonicalFile
        val root = site.canonicalFile
        val inside = target == root || target.path.startsWith(root.path + File.separator)
        if (!inside || !target.isFile || target.length() > 20L * 1024 * 1024) return notFound()
        val mime = mime(target)
        val encoding = if (mime.startsWith("text/") || mime == "application/javascript" || mime == "application/json" || mime == "image/svg+xml") {
            "utf-8"
        } else {
            null
        }
        return WebResourceResponse(mime, encoding, target.inputStream())
    }

    private fun notFound(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            404,
            "Not Found",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private fun mime(file: File): String = when (file.extension.lowercase()) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js", "mjs" -> "text/javascript"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "txt", "md" -> "text/plain"
        else -> "application/octet-stream"
    }

    companion object {
        const val EXTRA_PATH = "path"
        private const val HOST = "andvibe.preview"
    }
}
