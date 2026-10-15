package com.ylports.imagetopixel

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

typealias Reply = (Any?, String?) -> Unit

class MainActivity : ComponentActivity() {
    internal lateinit var webView: WebView
    internal var editorReady = false
    internal val io = Executors.newSingleThreadExecutor()
    internal lateinit var transfers: TransferStore
    private lateinit var bridge: NativeBridge
    private lateinit var root: FrameLayout
    private var chooser: ValueCallback<Array<Uri>>? = null
    private var pendingSave: Pair<String, Reply>? = null
    private val savedUris = mutableSetOf<String>()

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = chooser; chooser = null
        if (result.resultCode != Activity.RESULT_OK) callback?.onReceiveValue(null)
        else {
            val data = result.data
            val uris = data?.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri }.toTypedArray() }
                ?: data?.data?.let { arrayOf(it) }
            callback?.onReceiveValue(uris)
        }
    }
    private val createDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pending = pendingSave; pendingSave = null
        if (pending != null) {
            val (token, reply) = pending
            val uri = result.data?.data
            if (result.resultCode != Activity.RESULT_OK || uri == null) {
                io.execute { transfers.discard(token) }; reply(null, "Save cancelled")
            } else io.execute {
                try {
                    val e = transfers.ready(token)
                    contentResolver.openOutputStream(uri, "wt").use { out -> checkNotNull(out) { "The selected location cannot be written" }; e.file.inputStream().use { it.copyTo(out) } }
                    runOnUiThread { savedUris.add(uri.toString()) }
                    reply(JSONObject().put("uri", uri.toString()).put("type", e.type).put("name", e.name), null)
                } catch (e: Exception) { reply(null, "Could not save: ${e.message}") }
                finally { transfers.discard(token) }
            }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(17, 18, 22)) }
        setContentView(root)
        transfers = TransferStore(this)
        bridge = NativeBridge(this)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            val gestures = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
            view.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, gestures.bottom))
            insets
        }
        createWebView()
        immersive()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("Boolean(window.itpAndroid?.back())") { handled ->
                    if (handled == "true") return@evaluateJavascript
                    if (webView.canGoBack()) webView.goBack()
                    else {
                        webView.evaluateJavascript("window.itpAndroid?.pause()", null)
                        moveTaskToBack(true)
                    }
                }
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        editorReady = false
        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(17, 18, 22))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = true // System-granted document URIs from the file chooser only.
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = false
                setSupportZoom(false) // The original canvas implements pinch zoom itself.
                builtInZoomControls = false; displayZoomControls = false
                textZoom = 100
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            webViewClient = LocalContentClient(this@MainActivity, transfers)
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    chooser?.onReceiveValue(null); chooser = callback
                    val types = params.acceptTypes.flatMap { it.split(',') }.map(String::trim).filter { it.isNotEmpty() }
                        .map { type -> if (type.startsWith('.')) android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.drop(1)) ?: "application/octet-stream" else type }.distinct()
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).apply {
                        type = if (types.size == 1) types[0] else "*/*"
                        if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                    }
                    try { pickFiles.launch(intent) } catch (e: Exception) { chooser = null; callback.onReceiveValue(null); notifyUser("No system file picker is available") }
                    return true
                }
            }
        }
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            notifyUser("Please update Android System WebView to open Image-to-Pixel")
            return
        }
        WebViewCompat.addWebMessageListener(webView, "ITPNative", setOf(LocalContentClient.ORIGIN)) { _, message, source, mainFrame, proxy ->
            if (!mainFrame || source.toString().trimEnd('/') != LocalContentClient.ORIGIN) return@addWebMessageListener
            val reply: (Long, Any?, String?) -> Unit = { id, result, error ->
                runOnUiThread {
                    if (!isDestroyed) {
                        val json = JSONObject().put("id", id)
                        if (error != null) json.put("error", error) else json.put("result", result ?: JSONObject.NULL)
                        runCatching { proxy.postMessage(json.toString()) }
                    }
                }
            }
            try {
                val text = message.data ?: return@addWebMessageListener
                require(text.length <= 300_000) { "Bridge message is too large" }
                val request = JSONObject(text)
                val id = request.getLong("id")
                bridge.execute(request.getString("method"), request.optJSONObject("args") ?: JSONObject()) { result, error -> reply(id, result, error) }
            } catch (e: Exception) { notifyUser("Invalid editor request: ${e.message}") }
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView.loadUrl(LocalContentClient.EDITOR)
    }

    internal fun save(token: String, reply: Reply) {
        io.execute {
            try {
                val e = transfers.ready(token)
                val collection = when {
                    e.type.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    e.type.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else -> null
                }
                if (collection == null) runOnUiThread {
                    if (pendingSave != null) { reply(null, "Another save dialog is already open"); return@runOnUiThread }
                    pendingSave = token to reply
                    createDocument.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(e.type).putExtra(Intent.EXTRA_TITLE, e.name))
                } else {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, e.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, e.type)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, if (e.type.startsWith("image/")) "Pictures/Image-to-Pixel" else "Movies/Image-to-Pixel")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = checkNotNull(contentResolver.insert(collection, values)) { "Media storage is unavailable" }
                    try {
                        contentResolver.openOutputStream(uri).use { output -> checkNotNull(output); e.file.inputStream().use { it.copyTo(output) } }
                        contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                        runOnUiThread { savedUris.add(uri.toString()) }
                        reply(JSONObject().put("uri", uri.toString()).put("type", e.type).put("name", e.name), null)
                    } catch (error: Exception) { contentResolver.delete(uri, null, null); throw error }
                    finally { transfers.discard(token) }
                }
            } catch (e: Exception) { transfers.discard(token); reply(null, "Could not save: ${e.message}") }
        }
    }

    internal fun share(token: String, clipboard: Boolean, reply: Reply) {
        io.execute {
            try {
                val e = transfers.ready(token)
                val dir = File(cacheDir, "shared").apply { mkdirs() }
                dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
                val file = File(dir, "${System.nanoTime()}-${e.name}"); e.file.copyTo(file)
                val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                runOnUiThread {
                    if (clipboard) {
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newUri(contentResolver, e.name, uri))
                        reply(true, null)
                    } else {
                        savedUris.add(uri.toString()); shareUri(uri.toString(), e.type, reply)
                    }
                }
            } catch (e: Exception) { reply(null, "Could not share: ${e.message}") }
            finally { transfers.discard(token) }
        }
    }

    internal fun shareUri(uri: String, type: String, reply: Reply) {
        runOnUiThread {
            try {
                require(uri in savedUris) { "Only files exported by this editor can be shared" }
                val intent = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Image-to-Pixel", Uri.parse(uri)) }
                startActivity(Intent.createChooser(intent, "Share")); reply(true, null)
            } catch (e: Exception) { reply(null, e.message) }
        }
    }

    internal fun recoverRenderer(old: WebView) {
        root.removeView(old); old.destroy()
        notifyUser("The editor ran out of graphics memory. Restoring your saved session…")
        createWebView()
    }

    internal fun notifyUser(text: String) = runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    private fun immersive() {
        WindowInsetsControllerCompat(window, root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive() }
    override fun onConfigurationChanged(config: Configuration) { super.onConfigurationChanged(config); ViewCompat.requestApplyInsets(root); immersive() }
    override fun onPause() {
        webView.evaluateJavascript("window.itpAndroid?.pause().catch(console.error)", null)
        webView.onPause(); super.onPause()
    }
    override fun onResume() { super.onResume(); if (::webView.isInitialized) { webView.onResume(); webView.evaluateJavascript("window.itpAndroid?.resume()", null) } }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::webView.isInitialized) webView.evaluateJavascript("window.itpAndroid?.memory().catch(console.error)", null)
    }
    override fun onDestroy() {
        chooser?.onReceiveValue(null); chooser = null
        webView.stopLoading(); root.removeView(webView); webView.destroy()
        io.execute { bridge.close(); transfers.close() }; io.shutdown()
        super.onDestroy()
    }
}
