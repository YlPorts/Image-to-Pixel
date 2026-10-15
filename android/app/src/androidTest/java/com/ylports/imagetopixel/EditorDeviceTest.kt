package com.ylports.imagetopixel

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.app.Activity
import androidx.core.content.FileProvider
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs against the installed APK and its actual Android WebView, with radios off. */
@RunWith(AndroidJUnit4::class)
class EditorDeviceTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun open() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor { js("Boolean(window.itpAndroid?.ready)") == "true" }
        async("window.itpEditor.app.clearItems(); await window.itpEditor.idle(); return true")
    }
    @After fun close() { scenario.close() }

    private fun js(source: String): String {
        val latch = CountDownLatch(1); var value = "null"
        scenario.onActivity { it.webView.evaluateJavascript(source) { result -> value = result; latch.countDown() } }
        assertTrue("WebView did not answer", latch.await(20, TimeUnit.SECONDS))
        return value
    }
    private fun waitFor(timeout: Long = 90_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) { if (predicate()) return; Thread.sleep(100) }
        fail("Editor operation timed out")
    }
    private fun async(source: String): JSONObject {
        js("window.__deviceTest = null; (async()=>{$source})().then(value=>window.__deviceTest={ok:true,value}).catch(e=>window.__deviceTest={ok:false,error:String(e.stack||e)})")
        var result: JSONObject? = null
        waitFor {
            val value = JSONTokener(js("JSON.stringify(window.__deviceTest)")).nextValue()
            if (value is String && value != "null") result = JSONObject(value)
            result != null
        }
        assertTrue(result.toString(), result!!.getBoolean("ok"))
        return result!!
    }
    private fun screenshot(name: String) {
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "test-results/$name.png")
        output.parentFile!!.mkdirs()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun offlineImportConversionExportAndRotation() {
        val result = async("""
            const api=window.itpEditor;
            const c=document.createElement('canvas'); c.width=173;c.height=131;
            const ctx=c.getContext('2d');ctx.fillStyle='#de6132';ctx.fillRect(0,0,173,131);ctx.fillStyle='#223c88';ctx.fillRect(40,25,70,60);
            const b=await new Promise(r=>c.toBlob(r));
            await api.addFiles([new File([b],'device-fixture.png',{type:'image/png'})]);
            api.store.update({size:{width:48},dither:{method:'bayer4'}}); await api.idle();
            const core=await import('../lib/image-to-pixel.bundle.esm.js');
            const cpu=await core.createPixelator({backend:'cpu'});
            const expected=await cpu.process(api.app.current.source,api.store.settings);
            let diff=0;for(let i=0;i<expected.data.length;i++)if(expected.data[i]!==api.result.data[i])diff++;
            cpu.destroy();
            const saved=await window.itpPlatform.download(await api.result.toBlob(),'device-test.png');
            return {width:api.result.width,height:api.result.height,diff,uri:saved.uri};
        """).getJSONObject("value")
        assertEquals(0, result.getInt("diff"))
        val uri = Uri.parse(result.getString("uri"))
        val resolver = instrumentation.targetContext.contentResolver
        try {
            val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), bytes.copyOfRange(0, 8))
            val image = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertEquals(result.getInt("width"), image.width); assertEquals(result.getInt("height"), image.height); image.recycle()
        } finally { resolver.delete(uri, null, null) }
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        waitFor { js("innerHeight > innerWidth") == "true" }; screenshot("portrait")
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        waitFor { js("innerWidth > innerHeight") == "true" }; screenshot("landscape")
        assertEquals("1", js("window.itpEditor.app.items.length"))
        assertEquals("48", js("window.itpEditor.result.width"))
    }

    @Test fun sessionSourcesOwnSettingsAndUndoSurviveActivityRecreation() {
        async("""
            const api=window.itpEditor;await api.openSample('potion');await api.openSample('dusk');
            api.app.setOwn(api.app.current,true);api.store.update({size:{width:64}});api.store.update({dither:{method:'atkinson'}});
            await api.idle();await window.itpAndroid.pause();window.itpAndroid.resume();return true;
        """)
        scenario.recreate()
        waitFor { js("Boolean(window.itpAndroid?.ready)") == "true" }
        assertEquals("2", js("window.itpEditor.app.items.length"))
        assertEquals("64", js("window.itpEditor.store.settings.size.width"))
        assertEquals("\"atkinson\"", js("window.itpEditor.store.settings.dither.method"))
        assertEquals("true", js("window.itpEditor.store.canUndo"))
        async("window.itpEditor.store.undo();await window.itpEditor.idle();return true")
        assertEquals("\"bayer4\"", js("window.itpEditor.store.settings.dither.method"))
    }

    @Test fun nativeVideoAndChunkedTransfer() {
        val report = async("""
            const p=window.itpPlatform;const support=await p.videoSupport(160,120);
            if(!support.mp4&&!support.webm)throw Error('No Android video encoder was found');
            const format=support.mp4?'mp4':'webm';const enc=await p.createVideoEncoder({format,width:160,height:120,fps:5});
            const data=new Uint8ClampedArray(160*120*4);for(let i=0;i<data.length;i+=4){data[i]=220;data[i+1]=44;data[i+2]=88;data[i+3]=255;}
            for(let i=0;i<4;i++)await enc.add({width:160,height:120,data});
            const blob=await enc.finish();const bytes=new Uint8Array(await blob.arrayBuffer());
            return {format,size:blob.size,header:Array.from(bytes.slice(0,12))};
        """).getJSONObject("value")
        assertTrue(report.toString(), report.getInt("size") > 100)
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "test-results").apply { mkdirs() }
        File(dir, "video-report.json").writeText(report.toString(2))
    }

    @Test fun systemFilePickerResultAndAndroidBack() {
        val context = instrumentation.targetContext
        val image = File(context.cacheDir, "shared/picker-fixture.png").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.CYAN) }
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", image)
        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(ActivityResult(Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)))
            js("document.querySelector('.itp-empty .primary').click()")
            waitFor { js("window.itpEditor.app.current?.name === 'picker-fixture.png' && Boolean(window.itpEditor.result)") == "true" }
            js("document.querySelector('.itp-top > .primary').click()")
            assertEquals("true", js("Boolean(document.querySelector('.itp-menu'))"))
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor { js("Boolean(document.querySelector('.itp-menu'))") == "false" }
            screenshot("picker-imported")
        } finally { Intents.release(); image.delete() }
    }
}
