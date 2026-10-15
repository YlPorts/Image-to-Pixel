package com.ylports.imagetopixel

import android.content.Intent
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import java.io.ByteArrayInputStream

class LocalContentClient(private val activity: MainActivity, transfers: TransferStore) : WebViewClientCompat() {
    private val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(activity))
        .addPathHandler("/generated/") { token -> transfers.response(token) }
        .build()

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        var uri = request.url
        if (uri.scheme == "https" && uri.host == HOST) {
            if (uri.path?.endsWith('/') == true) uri = uri.buildUpon().path(uri.path + "index.html").build()
            loader.shouldInterceptRequest(uri)?.let { response ->
                val headers = (response.responseHeaders ?: emptyMap()).toMutableMap()
                headers["Content-Security-Policy"] = "default-src 'self' data: blob:; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' data: blob:; connect-src 'self' data: blob:; font-src 'self' data:; object-src 'none'; frame-src 'none'"
                response.responseHeaders = headers
                return response
            }
        }
        return WebResourceResponse("text/plain", "UTF-8", 404, "Not found", emptyMap(), ByteArrayInputStream("Offline asset not found".toByteArray()))
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        if (uri.scheme == "https" && uri.host == HOST && uri.path.orEmpty().startsWith("/assets/site/")) return false
        if (request.isForMainFrame && request.hasGesture() && uri.scheme in setOf("https", "http")) {
            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        }
        return true
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        activity.recoverRenderer(view)
        return true
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val ORIGIN = "https://$HOST"
        const val EDITOR = "$ORIGIN/assets/site/editor/index.html"
        fun generated(token: String) = "$ORIGIN/generated/$token"
    }
}
