package ai.blackware.insfollow.workflow

import android.os.Handler
import android.os.Looper
import ai.blackware.insfollow.data.Store
import ai.blackware.insfollow.model.*
import ai.blackware.insfollow.queue.Schedule
import ai.blackware.insfollow.web.BrowserSession
import java.time.LocalTime

/** Follow execution is deliberately a separate user-only capability. */
interface FollowExecutor { fun requestUserAction(account: String) }
class AssistedWorkflow(
    private val store: Store,
    private val browser: () -> BrowserSession?,
    private val schedule: () -> Schedule?,
    private val executor: FollowExecutor,
    private val changed: (Stage,String) -> Unit,
    private val message: (String) -> Unit
) {
    private val handler=Handler(Looper.getMainLooper())
    var stage=Stage.PAUSED; private set
    var active=false; private set
    var running=false; private set
    var expanded=false
    var work: Work?=null; private set
    private var afterAction=false
    private var refreshed=false
    private var candidate: Observation?=null
    private var generation=0
    private var deadline=0L
    private var failures=0
    private fun transition(next: Stage) { stage=next; work?.let { store.stage(it,next,afterAction) }; changed(next,work?.key.orEmpty()) }
    private fun allowed(): Boolean = active && expanded && schedule()?.allows(LocalTime.now())==true
    private fun guard(): Boolean {
        if(!allowed()) { pause(); message("OUTSIDE_SCHEDULE"); return false }; return true
    }
    fun navigationAllowed(): Boolean = !running || guard()
    fun foreground(value: Boolean) { active=value; if(!value) pause() }
    fun start() {
        if(running) return
        val config=schedule() ?: return
        if(!active || !expanded) { message("EXPAND_REQUIRED"); return }
        if(!config.allows(LocalTime.now())) { message("OUTSIDE_SCHEDULE"); return }
        val id=store.session() ?: store.createQueue()
        if(config.limit>0 && store.countProcessed(id)>=config.limit) { message("SESSION_LIMIT"); return }
        running=true; store.setSession(id,"RUNNING")
        work=store.current()
        if(work!=null) {
            // A paused interaction may have changed the page. Only refresh and verify on resume.
            afterAction=store.readableDatabase.rawQuery("SELECT afterAction FROM operations WHERE operationId=?",arrayOf(work!!.operation.toString())).use { it.moveToFirst(); it.getInt(0)==1 }
            refreshed=afterAction; refresh()
        } else next()
        tick()
    }
    private fun tick() {
        if(!running) return
        if(!guard()) return
        handler.postDelayed({ tick() },1000)
    }
    private fun next() {
        if(!running || !guard()) return
        val id=store.session() ?: run { complete(); return }
        val config=schedule() ?: run { pause(); return }
        if(config.limit>0 && store.countProcessed(id)>=config.limit) { pause(); message("SESSION_LIMIT"); return }
        val wait=store.nextAt(id)-System.currentTimeMillis()
        if(wait>0) { transition(Stage.QUEUED); handler.postDelayed({ if(running) next() },wait.coerceAtMost(1000)); return }
        work=store.acquire(id)
        if(work==null) { complete(); return }
        afterAction=false; refreshed=false; candidate=null
        transition(Stage.OPENING_PROFILE)
        beginWait(); browser()?.profile(work!!.key)
    }
    private fun beginWait() {
        generation++; candidate=null; deadline=System.currentTimeMillis()+30_000
        transition(if(refreshed) Stage.VERIFYING_RESULT else Stage.WAITING_FOR_PAGE)
        val token=generation
        handler.postDelayed({ poll(token) },1200)
    }
    private fun poll(token: Int) {
        if(token!=generation || !running || !guard()) return
        if(System.currentTimeMillis()>deadline) { fail("VERIFICATION_TIMEOUT"); return }
        val current=work ?: return
        transition(if(refreshed) Stage.VERIFYING_RESULT else Stage.INSPECTING_PROFILE)
        browser()?.inspect(current.key) { observation ->
            if(token!=generation || !running || !allowed()) return@inspect
            if(observation==Observation.LOGIN || observation==Observation.RESTRICTED) {
                settle(Results.decide(observation,afterAction),observation.name,false)
                message(if(observation==Observation.LOGIN) "LOGIN_REQUIRED" else "ACTION_RESTRICTED"); return@inspect
            }
            if(observation==Observation.UNKNOWN) { candidate=null; handler.postDelayed({ poll(token) },1000); return@inspect }
            if(candidate!=observation) { candidate=observation; handler.postDelayed({ poll(token) },1000); return@inspect }
            if(!refreshed && observation==Observation.FOLLOW) {
                generation++; transition(Stage.READY_FOR_ACTION); afterAction=true
                transition(Stage.AWAITING_USER_ACTION); executor.requestUserAction(current.key)
                watchUserAction(generation)
            } else if(!refreshed) {
                refreshed=true; refresh()
            } else {
                failures=0
                settle(Results.decide(observation,afterAction),observation.name,true)
            }
        }
    }
    private fun watchUserAction(token: Int) {
        handler.postDelayed({
            if(token==generation && running && stage==Stage.AWAITING_USER_ACTION && guard()) {
                browser()?.inspect(work?.key ?: return@postDelayed) { observation ->
                    if(token!=generation || !running) return@inspect
                    if(observation in setOf(Observation.LOGIN,Observation.RESTRICTED)) {
                        settle(Results.decide(observation,afterAction),observation.name,false)
                        message(if(observation==Observation.LOGIN) "LOGIN_REQUIRED" else "ACTION_RESTRICTED")
                    } else watchUserAction(token)
                }
            }
        },2000)
    }
    fun verify() {
        if(!running || stage!=Stage.AWAITING_USER_ACTION || !guard()) return
        refreshed=true; generation++
        // Allow the genuine user interaction to settle, then perform a fresh navigation.
        transition(Stage.REFRESHING_PROFILE)
        handler.postDelayed({ if(running && guard()) refresh() },1500)
    }
    private fun refresh() {
        if(!guard()) return
        transition(Stage.REFRESHING_PROFILE); beginWait(); browser()?.profile(work?.key ?: return)
    }
    private fun settle(verdict: Verdict, observation: String, advance: Boolean, skipped: Boolean=false) {
        generation++; val current=work ?: return
        try {
            transition(Stage.SAVING_RESULT)
            store.transaction { store.finish(current,verdict,observation,skipped); store.setNext(current.session,System.currentTimeMillis()+(schedule()?.interval() ?: 60_000)) }
            work=null
            transition(if(verdict.status==Status.UNCERTAIN) Stage.UNCERTAIN else Stage.COMPLETED)
            if(advance && running) next() else pause()
        } catch (_: Exception) { storageFailure() }
    }
    fun fail(reason: String) {
        if(!running || work==null) return
        failures++
        val ceiling=schedule()?.failureLimit ?: 0
        settle(Verdict(Status.UNCERTAIN,reason),reason,reason !in setOf("ACTION_RESTRICTED","LOGIN_REQUIRED","RENDERER_GONE","NAVIGATION_BLOCKED") && (ceiling==0 || failures<ceiling))
        message(reason)
    }
    fun navigation(url: String) {
        if(!running) return
        val path=android.net.Uri.parse(url).path.orEmpty()
        when {
            path.contains("challenge") || path.contains("checkpoint") || path.contains("suspended") -> fail("ACTION_RESTRICTED")
            path.startsWith("/accounts/login") -> fail("LOGIN_REQUIRED")
        }
    }
    fun pause() {
        if(!running && stage==Stage.PAUSED) return
        running=false; generation++; handler.removeCallbacksAndMessages(null)
        work?.let { store.stage(it,Stage.PAUSED,afterAction) }
        store.session()?.let { store.setSession(it,"PAUSED") }
        stage=Stage.PAUSED; changed(stage,work?.key.orEmpty())
    }
    fun stop() { pause(); store.session()?.let { store.setSession(it,"STOPPED") } }
    fun skip() {
        if(work==null) return
        settle(Verdict(Status.UNCERTAIN,"USER_SKIPPED"),"USER_SKIPPED",running,true)
    }
    private fun complete() { running=false; handler.removeCallbacksAndMessages(null); transition(Stage.COMPLETED) }
    fun storageFailure() {
        running=false; generation++; handler.removeCallbacksAndMessages(null); stage=Stage.PAUSED
        runCatching { changed(stage,work?.key.orEmpty()) }; message("DATABASE_ERROR")
    }
    fun close() { pause(); handler.removeCallbacksAndMessages(null) }
}
