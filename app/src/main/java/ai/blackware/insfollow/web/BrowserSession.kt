package ai.blackware.insfollow.web

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.view.MotionEvent
import android.webkit.*
import ai.blackware.insfollow.R
import ai.blackware.insfollow.model.*
import ai.blackware.insfollow.model.Observation
import org.json.JSONTokener
import org.json.JSONObject

class BrowserSession(private val activity: Activity, val events: Events) : ProfileBrowser {
    interface Events {
        fun started(url: String)
        fun finished()
        fun error(reason: String)
        fun rendererGone()
        fun chooseFile(callback: ValueCallback<Array<Uri>>, types: Array<String>, multiple: Boolean): Boolean = false
    }
    private var destroyed = false
    private var expected: String? = null
    private var navigation: (() -> Boolean)? = null
    val view: WebView = create()
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun create(): WebView = WebView(activity).apply {
        WebView.setWebContentsDebuggingEnabled(false)
        setBackgroundColor(android.graphics.Color.BLACK)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            builtInZoomControls = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this,false)
        setOnTouchListener { _,event ->
            parent?.requestDisallowInterceptTouchEvent(event.action != MotionEvent.ACTION_UP && event.action != MotionEvent.ACTION_CANCEL)
            false
        }
        webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?,url: String?,message: String?,result: JsResult): Boolean { result.cancel(); return true }
            override fun onJsConfirm(view: WebView?,url: String?,message: String?,result: JsResult): Boolean { result.cancel(); return true }
            override fun onJsPrompt(view: WebView?,url: String?,message: String?,defaultValue: String?,result: JsPromptResult): Boolean { result.cancel(); return true }
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onShowFileChooser(webView: WebView,filePathCallback: ValueCallback<Array<Uri>>,params: FileChooserParams): Boolean {
                return events.chooseFile(filePathCallback,params.acceptTypes,params.mode==FileChooserParams.MODE_OPEN_MULTIPLE)
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if(!request.isForMainFrame) return false
                val uri=request.url
                val host=uri.host?.lowercase()
                val path=uri.path.orEmpty()
                val allowed=uri.scheme=="https" && host in setOf("www.instagram.com","instagram.com") &&
                    (expected==null || path.trim('/').equals(expected,true) || path.startsWith("/accounts/") || path.startsWith("/challenge/") || path.startsWith("/checkpoint/"))
                if(!allowed) { events.error("NAVIGATION_BLOCKED"); return true }
                if(navigation?.invoke()==false) return true
                return false
            }
            override fun onPageStarted(view: WebView,url: String,favicon: Bitmap?) { events.started(url) }
            override fun onPageFinished(view: WebView,url: String) { CookieManager.getInstance().flush(); events.finished() }
            override fun onReceivedError(view: WebView,request: WebResourceRequest,error: WebResourceError) { if(request.isForMainFrame) events.error("NETWORK_ERROR") }
            override fun onReceivedHttpError(view: WebView,request: WebResourceRequest,errorResponse: WebResourceResponse) { if(request.isForMainFrame) events.error(if(errorResponse.statusCode==429) "ACTION_RESTRICTED" else "HTTP_ERROR") }
            override fun onReceivedSslError(view: WebView,handler: SslErrorHandler,error: android.net.http.SslError) { handler.cancel(); events.error("TLS_ERROR") }
            override fun onRenderProcessGone(view: WebView,detail: RenderProcessGoneDetail): Boolean {
                this@BrowserSession.destroy(); events.rendererGone(); return true
            }
        }
    }
    fun setGuard(guard: () -> Boolean) { navigation=guard }
    override fun profile(key: String) { expected=Identity.normalize(key); view.loadUrl("https://www.instagram.com/$expected/") }
    fun login() { expected=null; view.loadUrl("https://www.instagram.com/accounts/login/") }
    fun reload() { if(!destroyed) view.reload() }
    override fun inspect(key: String, result: (Observation) -> Unit) {
        if(destroyed) { result(Observation.UNKNOWN); return }
        val script=activity.resources.openRawResource(R.raw.inspect).bufferedReader().use { it.readText() }.replace("__EXPECTED__",JSONObject.quote(key))
        view.evaluateJavascript(script) { raw ->
            val observation=runCatching { Observation.valueOf(JSONTokener(raw).nextValue() as String) }.getOrDefault(Observation.UNKNOWN)
            result(observation)
        }
    }
    override fun facts(key: String, result: (ProfileFacts) -> Unit) {
        if(destroyed) { result(ProfileFacts(reason="RENDERER_GONE")); return }
        val script=activity.resources.openRawResource(R.raw.facts).bufferedReader().use { it.readText() }.replace("__EXPECTED__",JSONObject.quote(key))
        view.evaluateJavascript(script) { raw ->
            val facts=runCatching {
                val json=JSONObject(JSONTokener(raw).nextValue() as String)
                ProfileFacts(Privacy.valueOf(json.getString("privacy")),FollowerNumbers.parse(json.getString("count")),json.getString("reason"))
            }.getOrDefault(ProfileFacts())
            result(facts)
        }
    }
    fun resume() { if(!destroyed) view.onResume() }
    fun pause() { if(!destroyed) { view.stopLoading(); view.onPause(); CookieManager.getInstance().flush() } }
    fun destroy() {
        if(destroyed) return
        destroyed=true
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.stopLoading(); view.destroy()
    }
}
