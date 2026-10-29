package com.tvkamar.framextv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Full-screen WebView for frameXTV. The site is built for a mouse, so the remote drives an
 * on-screen pointer: arrows move it, OK taps under it, and pushing past an edge scrolls.
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var cursor: ImageView
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private var cursorX = 0f
    private var cursorY = 0f
    private val hideCursor = Runnable { cursor.visibility = View.GONE }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            // window.open() goes through onCreateWindow, where pop-ups are dropped.
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = false
            webViewClient = SiteClient()
            webChromeClient = Chrome()
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        val size = (24 * resources.displayMetrics.density).toInt()
        cursor = ImageView(this).apply {
            setImageResource(R.drawable.cursor)
            layoutParams = FrameLayout.LayoutParams(size, size)
        }
        root = FrameLayout(this).apply {
            addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(cursor)
        }
        setContentView(root)

        root.post {
            cursorX = root.width / 2f
            cursorY = root.height / 2f
            placeCursor()
        }

        if (savedInstanceState == null) web.loadUrl(HOME) else web.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        // Faster the longer an arrow is held.
        val step = STEP * resources.displayMetrics.density * (1 + minOf(event.repeatCount, 12) / 3f)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) move(-step, 0f); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) move(step, 0f); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (down) move(0f, -step); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) move(0f, step); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down && event.repeatCount == 0) tap()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (!down) back()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (down && event.repeatCount == 0) {
                    web.evaluateJavascript(
                        "(function(){var v=document.querySelector('video');if(v){v.paused?v.play():v.pause();}})()",
                        null,
                    )
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun move(dx: Float, dy: Float) {
        val maxX = root.width - 1f
        val maxY = root.height - 1f
        val nx = cursorX + dx
        val ny = cursorY + dy
        // Past an edge: scroll whatever is under the pointer instead of moving it.
        if (ny < 0 || ny > maxY) scroll(0f, if (ny < 0) 1f else -1f)
        if (nx < 0 || nx > maxX) scroll(if (nx < 0) -1f else 1f, 0f)
        cursorX = nx.coerceIn(0f, maxX)
        cursorY = ny.coerceIn(0f, maxY)
        placeCursor()
    }

    private fun placeCursor() {
        val half = cursor.layoutParams.width / 2f
        cursor.translationX = cursorX - half
        cursor.translationY = cursorY - half
        cursor.visibility = View.VISIBLE
        cursor.bringToFront()
        root.removeCallbacks(hideCursor)
        root.postDelayed(hideCursor, CURSOR_HIDE_MS)
    }

    private fun target(): View = fullscreenView ?: web

    private fun tap() {
        placeCursor()
        val time = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(time, time, action, cursorX, cursorY, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            target().dispatchTouchEvent(e)
            e.recycle()
        }
    }

    private fun scroll(h: Float, v: Float) {
        val props = MotionEvent.PointerProperties().apply { toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply {
            x = cursorX
            y = cursorY
            setAxisValue(MotionEvent.AXIS_HSCROLL, h)
            setAxisValue(MotionEvent.AXIS_VSCROLL, v)
        }
        val time = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(
            time, time, MotionEvent.ACTION_SCROLL, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        target().dispatchGenericMotionEvent(e)
        e.recycle()
    }

    private fun back() {
        when {
            fullscreenView != null -> fullscreenCallback?.onCustomViewHidden()
            web.canGoBack() -> web.goBack()
            else -> finish()
        }
    }

    private inner class SiteClient : WebViewClient() {
        // Keeps ad redirects out: the page itself may only move between frameXTV domains.
        // Embedded players load in iframes, which this does not touch.
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            return !isSite(request.url)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (!isSite(Uri.parse(url))) view.stopLoading()
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message) =
            false

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            fullscreenView?.let { root.removeView(it) }
            fullscreenView = view
            fullscreenCallback = callback
            root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
            placeCursor()
        }

        override fun onHideCustomView() {
            fullscreenView?.let { root.removeView(it) }
            fullscreenView = null
            fullscreenCallback = null
        }
    }

    private companion object {
        const val HOME = "https://framextv.xyz/"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val STEP = 12f
        const val CURSOR_HIDE_MS = 5000L

        fun isSite(url: Uri): Boolean {
            val host = url.host ?: return false
            if (url.scheme != "https" && url.scheme != "http") return false
            return SITE_DOMAINS.any { host == it || host.endsWith(".$it") }
        }

        val SITE_DOMAINS = listOf("framextv.xyz", "framextv.cc")
    }
}
