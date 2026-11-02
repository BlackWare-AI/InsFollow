package ai.blackware.insfollow

import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import ai.blackware.insfollow.ui.MainActivity
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the actual bundled click script on local HTML only; never visits Instagram. */
@Suppress("DEPRECATION")
class FollowClickDeviceTest : ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    private fun onUi(action: () -> Unit) {
        var failure: Throwable?=null
        instrumentation.runOnMainSync { try { action() } catch(t: Throwable) { failure=t } }
        failure?.let { throw it }
    }
    private fun descendants(view: View): List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun fixture(buttons: String,notice: String="",path: String="fiction.one",heading: String="fiction.one"): JSONObject {
        val target=activity
        val loaded=CountDownLatch(1)
        lateinit var web: WebView
        onUi {
            descendants(target.window.decorView).filterIsInstance<Button>().first { it.text==target.getString(R.string.browser_tab) }.performClick()
            web=descendants(target.window.decorView).filterIsInstance<WebView>().single()
            web.webViewClient=object: android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView,url: String) { loaded.countDown() }
            }
            val html="""<script>window.clickCount=0;window.lastTrusted=null;function record(e){window.clickCount++;window.lastTrusted=e.isTrusted;}</script><main>$notice<header><h2>$heading</h2>$buttons</header></main>"""
            web.loadDataWithBaseURL("https://www.instagram.com/$path/",html,"text/html","UTF-8",null)
        }
        assertTrue("Fixture page did not load",loaded.await(20,TimeUnit.SECONDS))
        val evaluated=CountDownLatch(1); var result=""
        onUi {
            val source=target.resources.openRawResource(R.raw.click_follow).bufferedReader().use { it.readText() }.replace("__EXPECTED__",JSONObject.quote("fiction.one"))
            val script="(function(){const status="+source+";return JSON.stringify({status:status,clicks:window.clickCount,trusted:window.lastTrusted});})();"
            web.evaluateJavascript(script) { raw -> result=JSONTokener(raw).nextValue() as String; evaluated.countDown() }
        }
        assertTrue("Click script did not return",evaluated.await(20,TimeUnit.SECONDS))
        return JSONObject(result)
    }
    private val follow="<button onclick='record(event)'>Follow</button>"
    private fun assertNoClick(result: JSONObject) {
        assertEquals("A guarded Follow attempt must not dispatch in this page state: $result",0,result.getInt("clicks"))
    }
    fun testEnglishFollowDispatchesOneSyntheticEvent() {
        val r=fixture(follow); assertEquals("CLICKED",r.getString("status")); assertEquals(1,r.getInt("clicks")); assertFalse(r.getBoolean("trusted"))
    }
    fun testTurkishFollowDispatchesOnce() {
        val r=fixture("<button onclick='record(event)'>Takip Et</button>"); assertEquals("CLICKED",r.getString("status")); assertEquals(1,r.getInt("clicks"))
    }
    fun testWrongUrlDoesNotClick() = assertNoClick(fixture(follow,path="fiction.other"))
    fun testWrongIdentityDoesNotClick() = assertNoClick(fixture(follow,heading="fiction.other"))
    fun testDuplicateFollowControlsDoNotClick() = assertNoClick(fixture(follow+follow))
    fun testDisabledControlDoesNotClick() = assertNoClick(fixture("<button disabled onclick='record(event)'>Follow</button>"))
    fun testInlineRestrictionDoesNotClick() = assertNoClick(fixture(follow,"<p>Try again later. We restrict certain activity.</p>"))
    fun testLoginOverlayDoesNotClick() = assertNoClick(fixture(follow,"<input type='password' aria-label='Password'>"))
    fun testConflictingFollowingStateDoesNotClick() = assertNoClick(fixture("<button>Following</button>"+follow))
    fun testTurkishRestrictionDoesNotClick() {
        val r=fixture(follow,"<p>İŞLEM ENGELLENDİ</p>"); assertNoClick(r); assertEquals("RESTRICTED",r.getString("status"))
    }
    fun testRequestedConflictDoesNotClick() {
        val r=fixture("<button>Requested</button>"+follow); assertNoClick(r); assertEquals("AMBIGUOUS",r.getString("status"))
    }
    fun testFollowingOnlyDoesNotClick() {
        val r=fixture("<button onclick='record(event)'>Following</button>"); assertNoClick(r); assertEquals("STATE_CHANGED",r.getString("status"))
    }
    fun testRestrictionAppearingDuringScrollDoesNotClick() {
        val setup="<script>document.querySelector('button').scrollIntoView=function(){document.querySelector('main').insertAdjacentHTML('afterbegin','<p>Try again later</p>');};</script>"
        val r=fixture(follow+setup); assertNoClick(r); assertEquals("RESTRICTED",r.getString("status"))
    }
    fun testReplacedButtonDuringScrollDoesNotClick() {
        val setup="<script>document.querySelector('button').scrollIntoView=function(){this.outerHTML=this.outerHTML;};</script>"
        val r=fixture(follow+setup); assertNoClick(r); assertEquals("AMBIGUOUS",r.getString("status"))
    }
    fun testExceptionAfterDispatchIsUncertainWithoutRetry() {
        val setup="<script>document.querySelector('button').click=function(){HTMLElement.prototype.click.call(this);throw new Error('fixture');};</script>"
        val r=fixture(follow+setup); assertEquals(1,r.getInt("clicks")); assertEquals("UNCERTAIN",r.getString("status"))
    }

    fun testRestrictionRaisedByClickIsNotReportedAsSafeDispatch() {
        val button="<button onclick=\"record(event);document.querySelector('main').insertAdjacentHTML('afterbegin','<p>Try again later</p>');\">Follow</button>"
        val r=fixture(button); assertEquals(1,r.getInt("clicks")); assertEquals("RESTRICTED",r.getString("status"))
    }

}
