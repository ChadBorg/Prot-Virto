package com.protvirto.capri

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject

/**
 * Her window: a transparent, click-through WebView over the whole screen running her original
 * renderer, plus small touchable windows over her body and her panel that hand their touches to
 * the WebView. Everything else on screen stays usable.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class Overlay(private val service: CapriService, private val bridge: Bridge) {
    private val displays = service.getSystemService(DisplayManager::class.java)
    lateinit var display: Display
        private set
    private var wm: WindowManager? = null
    private var web: WebView? = null
    private val webParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // Android 12+ lets touches pass through an overlay only if it is at most 80% opaque:
        // she's a construct anyway, a touch of transparency suits her.
        alpha = 0.8f
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        title = "Capri"
    }
    private val hits = mutableListOf<View>()
    private var focused = false

    val ready: Boolean get() = web != null

    fun allDisplays(): List<Display> = displays.displays.filter { it.state != Display.STATE_OFF || it.displayId == Display.DEFAULT_DISPLAY }

    /** The display she should live on from her settings: "bottom" (the Thor's second screen) or "top". */
    fun preferred(): Display {
        val all = displays.displays.toList()
        val second = all.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
        val wantBottom = service.config.string("screen", "bottom") == "bottom"
        return if (wantBottom && second != null) second else displays.getDisplay(Display.DEFAULT_DISPLAY)
    }

    fun create(target: Display = preferred()) {
        display = target
        val ctx = windowContext(target)
        wm = ctx.getSystemService(WindowManager::class.java)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
            .build()
        val w = WebView(ctx).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(false)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addJavascriptInterface(bridge, "CapriBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    Log.d("CapriWeb", "${m.message()} (${m.sourceId().substringAfterLast('/')}:${m.lineNumber()})")
                    return true
                }
            }
            // BACK while you type to her: Escape, which closes her chat
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && focused) {
                    if (event.action == KeyEvent.ACTION_UP) {
                        evaluateJavascript(
                            "document.activeElement && document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}))",
                            null,
                        )
                    }
                    true
                } else false
            }
        }
        web = w
        wm?.addView(w, webParams)
        w.loadUrl("https://appassets.androidplatform.net/assets/capri/src/renderer/index.html")
    }

    private fun windowContext(d: Display): Context =
        service.createDisplayContext(d).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)

    fun destroy() {
        clearHits()
        web?.let { w ->
            runCatching { wm?.removeView(w) }
            w.destroy()
        }
        web = null
    }

    // ---- talking to her ---------------------------------------------------------------------------
    fun emit(channel: String, payload: Any?) {
        val json = JSONObject.quote(payload?.toString() ?: "null")
        service.main.post { web?.evaluateJavascript("window.__hostEmit && window.__hostEmit(${JSONObject.quote(channel)}, $json)", null) }
    }

    fun resolve(id: Int, result: Any?) {
        if (id == 0) return
        val json = when (result) {
            null, JSONObject.NULL -> "null"
            is String -> JSONObject.quote(result)
            is Boolean, is Number -> result.toString()
            else -> result.toString()
        }
        service.main.post { web?.evaluateJavascript("window.__hostResolve($id, ${JSONObject.quote(json)})", null) }
    }

    // ---- screens ----------------------------------------------------------------------------------
    /** Her window's size in CSS pixels. */
    fun cssSize(): Pair<Int, Int> {
        val m = service.createDisplayContext(display).resources.displayMetrics
        val bounds = wm?.maximumWindowMetrics?.bounds
        val w = bounds?.width() ?: m.widthPixels
        val h = bounds?.height() ?: m.heightPixels
        val dpr = web?.let { it.resources.displayMetrics.density } ?: m.density
        return Pair((w / dpr).toInt(), (h / dpr).toInt())
    }

    fun screens(): JSONObject {
        val list = JSONArray()
        var y = 0
        for (d in displays.displays) {
            val ctx = service.createDisplayContext(d)
            val m = ctx.resources.displayMetrics
            val wpx = ctx.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            val w = (wpx.width() / m.density).toInt()
            val h = (wpx.height() / m.density).toInt()
            // stacked like the Thor's: no shared side edge, so she travels by teleport
            list.put(JSONObject().put("id", d.displayId).put("x", 0).put("y", y).put("w", w).put("h", h))
            y += h
        }
        val origin = list.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.getInt("id") == display.displayId }
        }
        return JSONObject()
            .put("current", display.displayId)
            .put("origin", JSONObject().put("x", 0).put("y", origin?.optInt("y") ?: 0))
            .put("list", list)
    }

    fun otherDisplay(): Display? = displays.displays.firstOrNull { it.displayId != display.displayId && it.isValid }

    /** Moves her (the same WebView, her state intact) to another screen. */
    fun moveTo(id: Int): JSONObject? {
        val target = displays.getDisplay(id) ?: return null
        val w = web ?: return null
        if (target.displayId == display.displayId) return null
        clearHits()
        runCatching { wm?.removeView(w) }
        return try {
            val newWm = windowContext(target).getSystemService(WindowManager::class.java)
            newWm.addView(w, webParams)
            wm = newWm
            display = target
            val (cw, ch) = cssSize()
            val origin = screens().optJSONObject("origin")
            JSONObject().put("width", cw).put("height", ch).put("gap", 0).put("origin", origin)
        } catch (e: Exception) {
            Log.w(TAG, "move failed, rebuilding on display $id", e)
            web = null
            runCatching { w.destroy() }
            create(target)
            null
        }
    }

    // ---- touch --------------------------------------------------------------------------------------
    /** Rectangles (CSS px) where she can be touched; everywhere else touches go through. */
    fun setHitRects(rects: JSONArray, dpr: Double) {
        val m = wm ?: return
        val scale = if (dpr > 0) dpr else 1.0
        while (hits.size > rects.length()) {
            val v = hits.removeAt(hits.size - 1)
            runCatching { m.removeView(v) }
        }
        for (i in 0 until rects.length()) {
            val r = rects.optJSONArray(i) ?: continue
            val lp = hitParams(
                (r.optDouble(0) * scale).toInt(), (r.optDouble(1) * scale).toInt(),
                (r.optDouble(2) * scale).toInt().coerceAtLeast(1), (r.optDouble(3) * scale).toInt().coerceAtLeast(1),
            )
            if (i < hits.size) {
                runCatching { m.updateViewLayout(hits[i], lp) }
            } else {
                val v = View(web?.context ?: service).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    setOnTouchListener { _, e -> forward(e) }
                }
                runCatching { m.addView(v, lp) }.onSuccess { hits.add(v) }
            }
        }
    }

    private fun hitParams(x: Int, y: Int, w: Int, h: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, BASE_FLAGS, PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        title = "Capri touch"
    }

    // a touch on one of her touch windows, into the WebView at the same spot of the screen
    private fun forward(e: MotionEvent): Boolean {
        val w = web ?: return false
        val loc = IntArray(2)
        w.getLocationOnScreen(loc)
        val ev = MotionEvent.obtain(e)
        ev.setLocation(e.rawX - loc[0], e.rawY - loc[1])
        w.dispatchTouchEvent(ev)
        ev.recycle()
        return true
    }

    private fun clearHits() {
        hits.forEach { v -> runCatching { wm?.removeView(v) } }
        hits.clear()
    }

    /** Her chat wants the keyboard: her window takes focus while you type. */
    fun setFocus(on: Boolean) {
        val w = web ?: return
        focused = on
        webParams.flags = if (on) {
            (BASE_FLAGS and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        webParams.softInputMode = if (on) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING else 0
        runCatching { wm?.updateViewLayout(w, webParams) }
        if (on) {
            w.requestFocus()
            w.evaluateJavascript("document.querySelector('.panel__input') && document.querySelector('.panel__input').focus()", null)
            if (Build.VERSION.SDK_INT >= 30) w.windowInsetsController?.show(android.view.WindowInsets.Type.ime())
        }
    }

    fun reload() {
        web?.reload()
    }

    companion object {
        private const val TAG = "CapriOverlay"
        private const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }
}
