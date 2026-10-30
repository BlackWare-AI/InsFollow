package ai.blackware.insfollow.workflow

import android.os.Handler
import android.os.Looper
import ai.blackware.insfollow.data.*
import ai.blackware.insfollow.model.*
import ai.blackware.insfollow.queue.Schedule
import ai.blackware.insfollow.web.ProfileBrowser
import java.time.LocalDateTime

/** Follow execution is deliberately a separate user-only capability. */
interface FollowExecutor { fun requestUserAction(account: String) }
class AssistedWorkflow(
    private val store: Store,
    private val browser: () -> ProfileBrowser?,
    private val schedule: () -> Schedule?,
    private val executor: FollowExecutor,
    private val changed: (Stage,String) -> Unit,
    private val message: (String) -> Unit,
    private val pageTimeoutMs: Long = 30_000,
    private val enforceFilters: Boolean = false
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
    private val stopReasons=setOf("ACTION_RESTRICTED","LOGIN_REQUIRED","RENDERER_GONE","NAVIGATION_BLOCKED")
    private fun withStorageGuard(action: () -> Unit) {
        try { action() } catch (_: android.database.SQLException) { storageFailure() }
    }
    private fun post(delay: Long, action: () -> Unit) {
        handler.postDelayed({ withStorageGuard(action) },delay)
    }
    private fun transition(next: Stage) { stage=next; work?.let { store.stage(it,next,afterAction) }; changed(next,work?.key.orEmpty()) }
    private fun allowed(): Boolean = active && expanded && schedule()?.allows(LocalDateTime.now())==true
    private fun guard(): Boolean {
        if(!allowed()) { pause(); message("OUTSIDE_SCHEDULE"); return false }; return true
    }
    fun navigationAllowed(): Boolean = !running || guard()
    fun foreground(value: Boolean) { active=value; if(!value) pause() }
    fun start() {
        if(running) return
        val config=schedule() ?: return
        if(!active || !expanded) { message("EXPAND_REQUIRED"); return }
        if(!config.allows(LocalDateTime.now())) { message("OUTSIDE_SCHEDULE"); return }
        val id=store.session() ?: store.createQueue()
        if(!budget(id,config)) return
        running=true; store.setSession(id,"RUNNING")
        work=store.current()
        if(work!=null) {
            // A paused interaction may have changed the page. Only refresh and verify on resume.
            afterAction=store.readableDatabase.rawQuery("SELECT afterAction FROM operations WHERE operationId=?",arrayOf(work!!.operation.toString())).use { it.moveToFirst(); it.getInt(0)==1 }
            refreshed=afterAction; refresh()
        } else next()
        tick()
    }
    private fun budget(id: Long,config: Schedule): Boolean {
        if(config.dailyLimit>0 && store.dailyVisited()>=config.dailyLimit) { message("DAILY_LIMIT"); return false }
        val until=store.setting("break_until","0").toLongOrNull() ?: 0
        if(System.currentTimeMillis()<until) { message("SESSION_BREAK"); return false }
        val baseline=store.setting("budget_$id","0").toIntOrNull() ?: 0
        if(config.limit>0 && store.countProcessed(id)-baseline>=config.limit) {
            store.settings(mapOf("budget_$id" to store.countProcessed(id).toString(),"break_until" to (System.currentTimeMillis()+config.breakMinutes*60_000L).toString()))
            message("SESSION_LIMIT"); return false
        }
        return true
    }
    private fun tick() {
        if(!running) return
        if(!guard()) return
        post(1000) { tick() }
    }
    private fun next() {
        if(!running || !guard()) return
        val id=store.session() ?: run { complete(); return }
        val config=schedule() ?: run { pause(); return }
        if(!budget(id,config)) { pause(); return }
        val wait=store.nextAt(id)-System.currentTimeMillis()
        if(wait>0) { transition(Stage.QUEUED); post(wait.coerceAtMost(1000)) { if(running) next() }; return }
        work=store.acquire(id,store.setting("filtered_$id","false")=="true")
        if(work==null) { complete(); return }
        afterAction=false; refreshed=false; candidate=null
        transition(Stage.OPENING_PROFILE)
        beginWait(); browser()?.profile(work!!.key)
    }
    private fun beginWait() {
        generation++; candidate=null; deadline=System.currentTimeMillis()+pageTimeoutMs
        transition(if(refreshed) Stage.VERIFYING_RESULT else Stage.WAITING_FOR_PAGE)
        val token=generation
        // The deadline must also fire when a renderer never returns the JavaScript callback.
        post(pageTimeoutMs) {
            if(token==generation && running && allowed()) fail("VERIFICATION_TIMEOUT")
        }
        post(1200) { poll(token) }
    }
    private fun poll(token: Int) {
        if(token!=generation || !running || !guard()) return
        if(System.currentTimeMillis()>deadline) { fail("VERIFICATION_TIMEOUT"); return }
        val current=work ?: return
        transition(if(refreshed) Stage.VERIFYING_RESULT else Stage.INSPECTING_PROFILE)
        browser()?.inspect(current.key) { observation ->
            withStorageGuard {
                if(token!=generation || !running || !allowed()) return@withStorageGuard
                if(observation==Observation.LOGIN || observation==Observation.RESTRICTED) {
                    settle(Results.decide(observation,afterAction),observation.name,false)
                    message(if(observation==Observation.LOGIN) "LOGIN_REQUIRED" else "ACTION_RESTRICTED"); return@withStorageGuard
                }
                if(observation==Observation.UNKNOWN) { candidate=null; post(1000) { poll(token) }; return@withStorageGuard }
                if(candidate!=observation) { candidate=observation; post(1000) { poll(token) }; return@withStorageGuard }
                if(!refreshed && observation==Observation.FOLLOW && enforceFilters) {
                    inspectEligibility(token,current)
                } else if(!refreshed && observation==Observation.FOLLOW) {
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
    }
    private fun inspectEligibility(token: Int,current: Work,previous: ProfileFacts? = null) {
        browser()?.facts(current.key) { facts -> withStorageGuard {
            if(token!=generation || !running || !allowed()) return@withStorageGuard
            if(facts.reason in setOf("LOGIN_REQUIRED","ACTION_RESTRICTED")) { fail(facts.reason); return@withStorageGuard }
            if(previous==null) { post(1000) { if(running && token==generation && guard()) inspectEligibility(token,current,facts) }; return@withStorageGuard }
            val verified=if(previous==facts) facts else ProfileFacts(reason="PROFILE_FACTS_CHANGED")
            store.saveScan(current.key,verified.copy(observation=Observation.FOLLOW))
            if(store.accountFilter().evaluate(verified).second!=Eligibility.ELIGIBLE) {
                generation++
                store.transaction { store.deferFiltered(current); store.setNext(current.session,System.currentTimeMillis()+(schedule()?.interval() ?: 60_000)) }
                work=null; transition(Stage.COMPLETED); next()
            } else {
                generation++; transition(Stage.READY_FOR_ACTION); afterAction=true
                transition(Stage.AWAITING_USER_ACTION); executor.requestUserAction(current.key); watchUserAction(generation)
            }
        } }
    }
    private fun watchUserAction(token: Int) {
        post(2000) {
            if(token==generation && running && stage==Stage.AWAITING_USER_ACTION && guard()) {
                browser()?.inspect(work?.key ?: return@post) { observation ->
                    withStorageGuard {
                        if(token!=generation || !running || !allowed()) return@withStorageGuard
                        if(observation in setOf(Observation.LOGIN,Observation.RESTRICTED)) {
                            settle(Results.decide(observation,afterAction),observation.name,false)
                            message(if(observation==Observation.LOGIN) "LOGIN_REQUIRED" else "ACTION_RESTRICTED")
                        } else watchUserAction(token)
                    }
                }
            }
        }
    }
    fun verify() {
        if(!running || stage!=Stage.AWAITING_USER_ACTION || !guard()) return
        refreshed=true; generation++
        // Allow the genuine user interaction to settle, then perform a fresh navigation.
        transition(Stage.REFRESHING_PROFILE)
        post(1500) { if(running && guard()) refresh() }
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
        if(!running) return
        if(work==null) {
            if(reason in stopReasons) pause()
            message(reason)
            return
        }
        failures++
        val ceiling=schedule()?.failureLimit ?: 0
        settle(Verdict(Status.UNCERTAIN,reason),reason,reason !in stopReasons && (ceiling==0 || failures<ceiling))
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
