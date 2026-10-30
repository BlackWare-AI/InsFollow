package ai.blackware.insfollow

import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import ai.blackware.insfollow.ui.MainActivity

@Suppress("DEPRECATION")
class ScreenDeviceTest : ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    private fun onUi(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { action() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
    private fun descendants(view: View): List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    fun testSingleWebViewAndCollapseDoesNotRecreate() {
        val target=activity
        onUi {
            val root=target.window.decorView
            val first=descendants(root).filterIsInstance<WebView>().single()
            descendants(root).filterIsInstance<Button>().first { it.text==target.getString(R.string.expand) }.performClick()
            assertSame(first,descendants(root).filterIsInstance<WebView>().single())
            descendants(root).filterIsInstance<Button>().first { it.text==target.getString(R.string.collapse) }.performClick()
            assertSame(first,descendants(root).filterIsInstance<WebView>().single())
        }
    }
    fun testHomeBrowserTabsKeepSameViewAndNavigation() {
        val target=activity
        onUi {
            val root=target.window.decorView
            val original=descendants(root).filterIsInstance<WebView>().single()
            val url=original.url
            repeat(3) {
                descendants(root).filterIsInstance<Button>().first { it.text==target.getString(R.string.browser_tab) }.performClick()
                assertSame(original,descendants(root).filterIsInstance<WebView>().single()); assertEquals(url,original.url)
                descendants(root).filterIsInstance<Button>().first { it.text==target.getString(R.string.home_tab) }.performClick()
                assertSame(original,descendants(root).filterIsInstance<WebView>().single()); assertEquals(url,original.url)
            }
        }
    }
    fun testTabsDoNotPauseRunningScan() {
        val target=activity
        val data=ai.blackware.insfollow.data.Store(instrumentation.targetContext,"tab-fixture.db")
        val fixture=org.json.JSONObject().put("favorite_page_pref",org.json.JSONArray().put(org.json.JSONObject().put("name","Test").put("items",org.json.JSONArray().put(org.json.JSONObject().put("pageId","fiction.one").put("name","Test")))).toString()).toString()
        data.import(ai.blackware.insfollow.data.FavoritesCodec.parse(fixture),"fixture.json"); data.selectAll(true)
        val field=MainActivity::class.java.getDeclaredField("scanner").apply { isAccessible=true }
        val original=field.get(target)
        val fake=object: ai.blackware.insfollow.web.ProfileBrowser {
            override fun profile(key: String) {}
            override fun inspect(key: String,result: (ai.blackware.insfollow.model.Observation)->Unit) {}
        }
        val scan=ai.blackware.insfollow.workflow.ScanWorkflow(data,{fake},{true},{0},{},{},{})
        try {
            onUi {
                field.set(target,scan); scan.start(ai.blackware.insfollow.model.ScanPolicy.ALL)
                val views=descendants(target.window.decorView)
                views.filterIsInstance<Button>().first { it.text==target.getString(R.string.browser_tab) }.performClick(); assertTrue(scan.running)
                views.filterIsInstance<Button>().first { it.text==target.getString(R.string.home_tab) }.performClick(); assertTrue(scan.running)
            }
        } finally { onUi { scan.pause(); field.set(target,original) }; data.close(); instrumentation.targetContext.deleteDatabase("tab-fixture.db") }
    }
    fun testPrivacyAndFollowerFactsOnReadOnlyFixtures() {
        val target=activity
        onUi { descendants(target.window.decorView).filterIsInstance<Button>().first { it.text==target.getString(R.string.browser_tab) }.performClick() }
        val cases=listOf(
            Triple("<button>Follow</button></header><article><a href='/p/fixture/'>Post</a></article>","PUBLIC","3,000"),
            Triple("<button>Takip Et</button></header><article><a href='/p/fixture/'>Post</a></article>","PUBLIC","3,5 B"),
            Triple("<button>Follow</button></header><p>This account is private</p>","PRIVATE","4K"),
            Triple("<button>Follow</button></header><p>This account is private</p><article><a href='/p/fixture/'>Post</a></article>","UNKNOWN","4K"),
            Triple("<button>Following</button></header><article><a href='/p/fixture/'>Post</a></article>","UNKNOWN","4K")
        )
        cases.forEach { (markup,privacy,count) ->
            val loaded=java.util.concurrent.CountDownLatch(1)
            lateinit var web: WebView
            onUi {
                web=descendants(target.window.decorView).filterIsInstance<WebView>().single()
                web.webViewClient=object: android.webkit.WebViewClient() { override fun onPageFinished(view: WebView,url: String) { loaded.countDown() } }
                web.loadDataWithBaseURL("https://www.instagram.com/fiction.one/","<main><header><h2>fiction.one</h2><ul><li><a><span title='$count'>$count</span> followers</a></li></ul>$markup</main>","text/html","UTF-8",null)
            }
            assertTrue(loaded.await(20,java.util.concurrent.TimeUnit.SECONDS))
            val inspected=java.util.concurrent.CountDownLatch(1); var json=org.json.JSONObject()
            onUi {
                val script=target.resources.openRawResource(R.raw.facts).bufferedReader().use { it.readText() }.replace("__EXPECTED__","\"fiction.one\"")
                web.evaluateJavascript(script) { raw -> json=org.json.JSONObject(org.json.JSONTokener(raw).nextValue() as String); inspected.countDown() }
            }
            assertTrue(inspected.await(20,java.util.concurrent.TimeUnit.SECONDS)); assertEquals(privacy,json.getString("privacy")); assertEquals(count,json.getString("count"))
        }
    }
    fun testCardsAreInlineAndLocalizationsExist() {
        val target=activity
        onUi {
            val views=descendants(target.window.decorView)
            assertTrue(views.filterIsInstance<Button>().any { it.text==target.getString(R.string.configuration) })
            assertTrue(views.filterIsInstance<Button>().any { it.text==target.getString(R.string.restore) })
            val config=android.content.res.Configuration(target.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag("tr")) }
            assertEquals("Duraklat",target.createConfigurationContext(config).getString(R.string.pause))
        }
    }
    fun testReadOnlyDetectorOnLocalFixtures() {
        val target=activity
        onUi { descendants(target.window.decorView).filterIsInstance<Button>().firstOrNull { it.text==target.getString(R.string.expand) }?.performClick() }
        val cases=listOf(
            "Following" to "FOLLOWING", "Takiptesin" to "FOLLOWING",
            "Requested" to "REQUESTED", "İstek Gönderildi" to "REQUESTED",
            "Follow" to "FOLLOW", "Takip Et" to "FOLLOW", "Unrecognized" to "UNKNOWN"
        )
        cases.forEach { (label,expected) ->
            val loaded=java.util.concurrent.CountDownLatch(1)
            lateinit var web: WebView
            onUi {
                web=descendants(target.window.decorView).filterIsInstance<WebView>().single()
                web.webViewClient=object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView,url: String) { loaded.countDown() }
                }
                web.loadDataWithBaseURL("https://www.instagram.com/fiction.one/","<main><header><h2>fiction.one</h2><button>$label</button></header></main>","text/html","UTF-8",null)
            }
            assertTrue(loaded.await(15,java.util.concurrent.TimeUnit.SECONDS))
            val inspected=java.util.concurrent.CountDownLatch(1)
            var result=""
            onUi {
                val script=target.resources.openRawResource(R.raw.inspect).bufferedReader().use { it.readText() }.replace("__EXPECTED__","\"fiction.one\"")
                web.evaluateJavascript(script) { raw -> result=org.json.JSONTokener(raw).nextValue().toString(); inspected.countDown() }
            }
            assertTrue(inspected.await(15,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(label,expected,result)
        }
    }

    fun testActivityRecreationPreservesSettingsAndSingleBrowser() {
        val target=activity
        val monitor=instrumentation.addMonitor(MainActivity::class.java.name,null,false)
        val data=ai.blackware.insfollow.data.Store(instrumentation.targetContext)
        val old=data.setting("min","60")
        data.settings(mapOf("min" to "79"))
        try {
            onUi { target.recreate() }
            val recreated=monitor.waitForActivityWithTimeout(15000) as? MainActivity
            assertNotNull(recreated)
            setActivity(recreated)
            instrumentation.waitForIdleSync()
            onUi {
                val views=descendants(recreated!!.window.decorView)
                assertEquals(1,views.filterIsInstance<WebView>().size)
                val minimum=views.filterIsInstance<android.widget.EditText>().single { it.contentDescription==recreated.getString(R.string.min_interval) }
                assertEquals("79",minimum.text.toString())
            }
        } finally { instrumentation.removeMonitor(monitor); data.settings(mapOf("min" to old)); data.close() }
    }

    fun testSystemBackCollapsesWithoutReplacingBrowser() {
        val target=activity
        lateinit var original: WebView
        onUi {
            original=descendants(target.window.decorView).filterIsInstance<WebView>().single()
            descendants(target.window.decorView).filterIsInstance<Button>().first { it.text==target.getString(R.string.expand) }.performClick()
        }
        assertTrue(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        val deadline=android.os.SystemClock.uptimeMillis()+5000
        var collapsed=false
        while(!collapsed && android.os.SystemClock.uptimeMillis()<deadline) {
            onUi {
                collapsed=descendants(target.window.decorView).filterIsInstance<Button>().any { it.text==target.getString(R.string.expand) }
            }
            if(!collapsed) android.os.SystemClock.sleep(50)
        }
        assertTrue("System Back did not collapse the browser",collapsed)
        onUi {
            assertFalse(target.isFinishing)
            assertSame(original,descendants(target.window.decorView).filterIsInstance<WebView>().single())
        }
    }

    fun testRendererTerminationAllowsOneExplicitReplacement() {
        val target=activity
        lateinit var original: WebView
        onUi {
            original=descendants(target.window.decorView).filterIsInstance<WebView>().single()
            descendants(target.window.decorView).filterIsInstance<Button>().first { it.text==target.getString(R.string.expand) }.performClick()
            original.loadDataWithBaseURL("https://www.instagram.com/fiction.one/","<main>Local renderer fixture</main>","text/html","UTF-8",null)
        }
        val deadline=android.os.SystemClock.uptimeMillis()+15000
        var terminated=false
        while(!terminated && android.os.SystemClock.uptimeMillis()<deadline) {
            onUi { terminated=original.webViewRenderProcess?.terminate()==true }
            if(!terminated) android.os.SystemClock.sleep(50)
        }
        assertTrue("Renderer did not start or terminate",terminated)
        val handledDeadline=android.os.SystemClock.uptimeMillis()+15000
        var handled=false
        while(!handled && android.os.SystemClock.uptimeMillis()<handledDeadline) {
            onUi { handled=target.findViewById<android.widget.TextView>(R.id.error).text==target.getString(R.string.renderer_gone) }
            if(!handled) android.os.SystemClock.sleep(50)
        }
        assertTrue("Renderer termination was not handled",handled)
        onUi {
            assertFalse(target.isFinishing)
            assertTrue(descendants(target.window.decorView).filterIsInstance<WebView>().isEmpty())
            descendants(target.window.decorView).filterIsInstance<Button>().first { it.text==target.getString(R.string.collapse) }.performClick()
            descendants(target.window.decorView).filterIsInstance<Button>().first { it.text==target.getString(R.string.expand) }.performClick()
            val replacement=descendants(target.window.decorView).filterIsInstance<WebView>().single()
            assertNotSame(original,replacement)
        }
    }

}
