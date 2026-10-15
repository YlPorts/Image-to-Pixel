package com.ylports.imagetopixel

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.graphics.HardwareRendererCompat
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
        // ATD images disable drawing by default. Pixel parity and screenshots
        // must use real rendering, just as the installed app does on a phone.
        instrumentation.runOnMainSync { HardwareRendererCompat.setDrawingEnabled(true) }
        assertTrue("Hardware drawing is required for pixel tests", HardwareRendererCompat.isDrawingEnabled())
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
        async("await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));return true")
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "test-results/$name.png")
        output.parentFile!!.mkdirs()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertTrue("The screenshot must contain the rendered UI", (1..20).any { i -> bitmap.getPixel(bitmap.width * i / 21, bitmap.height * i / 21) and 0xffffff != 0 })
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun tap(selector: String) {
        val geometry = JSONObject(JSONTokener(js("JSON.stringify((()=>{const r=document.querySelector(${JSONObject.quote(selector)}).getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,d:devicePixelRatio};})())")).nextValue() as String)
        val origin = IntArray(2)
        scenario.onActivity { it.webView.getLocationOnScreen(origin) }
        val x = origin[0] + (geometry.getDouble("x") * geometry.getDouble("d")).toFloat()
        val y = origin[1] + (geometry.getDouble("y") * geometry.getDouble("d")).toFloat()
        val down = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            SystemClock.sleep(30)
        }
    }

    @Test fun offlineImportConversionExportAndRotation() {
        val result = async("""
            const api=window.itpEditor;
            const c=document.createElement('canvas'); c.width=173;c.height=131;
            const ctx=c.getContext('2d');ctx.fillStyle='#de6132';ctx.fillRect(0,0,173,131);ctx.fillStyle='#223c88';ctx.fillRect(40,25,70,60);
            const b=await new Promise(r=>c.toBlob(r));
            await api.addFiles([new File([b],'device-fixture.png',{type:'image/png'})]);
            api.store.update({size:{width:48},dither:{method:'bayer4'}}); await api.idle();
            const core=await import(new URL('../lib/image-to-pixel.bundle.esm.js',location.href).href);
            const cpu=await core.createPixelator({backend:'cpu'});
            const expected=await cpu.process(api.app.current.source,api.store.settings);
            let diff=0;for(let i=0;i<expected.data.length;i++)if(expected.data[i]!==api.result.data[i])diff++;
            cpu.destroy();
            const saved=await window.itpPlatform.download(await api.result.toBlob(),'device-test.png');
            return {width:api.result.width,height:api.result.height,diff,uri:saved.uri,backend:api.result.backend,
                actual:Array.from(api.result.data.slice(0,16)),expected:Array.from(expected.data.slice(0,16)),
                actualBytes:api.result.data.length,expectedBytes:expected.data.length};
        """).getJSONObject("value")
        val reportDir = File(instrumentation.targetContext.getExternalFilesDir(null), "test-results").apply { mkdirs() }
        File(reportDir, "pixel-comparison.json").writeText(result.toString(2))
        assertEquals(result.toString(), 0, result.getInt("diff"))
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
            const p=window.itpPlatform;const support=await p.videoSupport(320,240);
            if(!support.mp4&&!support.webm)throw Error('No Android video encoder was found');
            const reports=[];
            for(const format of ['mp4','webm'].filter(f=>support[f])){
                const enc=await p.createVideoEncoder({format,width:320,height:240,fps:5});
                // 307,200 bytes per frame: each native upload needs two chunks.
                const data=new Uint8ClampedArray(320*240*4);for(let i=0;i<data.length;i+=4){data[i]=220;data[i+1]=44;data[i+2]=88;data[i+3]=255;}
                for(let i=0;i<4;i++)await enc.add({width:320,height:240,data});
                const blob=await enc.finish();const bytes=new Uint8Array(await blob.arrayBuffer());
                let binary='';for(let i=0;i<bytes.length;i+=8192)binary+=String.fromCharCode(...bytes.subarray(i,i+8192));
                reports.push({format,size:blob.size,header:Array.from(bytes.slice(0,12)),encoded:btoa(binary)});
            }
            return reports;
        """).getJSONArray("value")
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "test-results").apply { mkdirs() }
        for (i in 0 until report.length()) {
            val item = report.getJSONObject(i)
            assertTrue(item.toString(), item.getInt("size") > 100)
            val file = File(dir, "native-video.${item.getString("format")}")
            file.writeBytes(android.util.Base64.decode(item.getString("encoded"), android.util.Base64.DEFAULT))
            item.remove("encoded")
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                assertEquals(1, extractor.trackCount)
                val track = extractor.getTrackFormat(0)
                assertEquals(320, track.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(240, track.getInteger(MediaFormat.KEY_HEIGHT))
                extractor.selectTrack(0)
                var count = 0; var previous = -1L
                while (extractor.sampleTrackIndex >= 0) {
                    assertTrue("Video timestamps must increase", extractor.sampleTime > previous)
                    previous = extractor.sampleTime; count++; extractor.advance()
                }
                assertEquals("Every encoded frame must be readable", 4, count)
                item.put("readableFrames", count)
            } finally { extractor.release() }
        }
        File(dir, "video-report.json").writeText(report.toString(2))
    }

    @Test fun nativeTouchPinchPanAndFingerHandoff() {
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        waitFor { js("innerHeight > innerWidth") == "true" }
        async("await window.itpEditor.openSample('potion');await window.itpEditor.idle();return true")
        if (js("document.querySelector('.itp-app').dataset.lib === 'open'") == "true") tap(".itp-top > button")
        val geometry = JSONObject(JSONTokener(js("JSON.stringify((()=>{const r=document.querySelector('.itp-view').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,d:devicePixelRatio};})())")).nextValue() as String)
        val origin = IntArray(2)
        scenario.onActivity { it.webView.getLocationOnScreen(origin) }
        val density = geometry.getDouble("d").toFloat()
        val cx = origin[0] + geometry.getDouble("x").toFloat() * density
        val cy = origin[1] + geometry.getDouble("y").toFloat() * density
        val down = SystemClock.uptimeMillis()
        fun input(action: Int, points: List<Triple<Int, Float, Float>>) {
            val properties = points.map { p -> MotionEvent.PointerProperties().apply { id = p.first; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
            val coordinates = points.map { p -> MotionEvent.PointerCoords().apply { x = p.second; y = p.third; pressure = 1f; size = 1f } }.toTypedArray()
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, points.size, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        fun view() = JSONObject(JSONTokener(js("JSON.stringify(window.itpEditor.view)")).nextValue() as String)
        fun point(id: Int, dx: Float, dy: Float = 0f) = Triple(id, cx + dx * density, cy + dy * density)
        val first = view()
        input(MotionEvent.ACTION_DOWN, listOf(point(0, -40f)))
        input(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(point(0, -40f), point(1, 40f)))
        input(MotionEvent.ACTION_MOVE, listOf(point(0, -60f), point(1, 60f)))
        waitFor { view().getDouble("zoom") > first.getDouble("zoom") * 1.3 }
        val pinched = view()
        assertEquals(1.5, pinched.getDouble("zoom") / first.getDouble("zoom"), 0.04)
        input(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(point(0, -60f), point(1, 60f)))
        input(MotionEvent.ACTION_MOVE, listOf(point(0, -40f, 12f)))
        waitFor { view().getDouble("x") > pinched.getDouble("x") + 15 }
        val panned = view()
        input(MotionEvent.ACTION_UP, listOf(point(0, -40f, 12f)))
        assertEquals(20.0, panned.getDouble("x") - pinched.getDouble("x"), 2.0)
        assertEquals(12.0, panned.getDouble("y") - pinched.getDouble("y"), 2.0)
        assertEquals(pinched.getDouble("zoom"), panned.getDouble("zoom"), 0.01)
        screenshot("touch-pinch-pan")
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
            tap(".itp-empty .primary")
            waitFor { js("window.itpEditor.app.current?.name === 'picker-fixture.png' && Boolean(window.itpEditor.result)") == "true" }
            Intents.intended(hasAction(Intent.ACTION_OPEN_DOCUMENT))
            tap(".itp-top > .primary")
            assertEquals("true", js("Boolean(document.querySelector('.itp-menu'))"))
            assertTrue(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            waitFor { js("Boolean(document.querySelector('.itp-menu'))") == "false" }
            screenshot("picker-imported")
        } finally { Intents.release(); image.delete() }
    }
}
