package ai.blackware.insfollow.workflow

import android.os.Handler
import android.os.Looper
import ai.blackware.insfollow.data.*
import ai.blackware.insfollow.model.*
import ai.blackware.insfollow.web.ProfileBrowser

/** Read-only scan engine: has no FollowExecutor capability. */
class ScanWorkflow(private val store: Store,private val browser: () -> ProfileBrowser?,private val allowed: () -> Boolean,
    private val interval: () -> Long,private val changed: () -> Unit,private val message: (String) -> Unit,private val completed: () -> Unit,
    private val timeout: Long=30_000) {
    private val handler=Handler(Looper.getMainLooper())
    var running=false; private set
    var current: String?=null; private set
    private var token=0
    private var run: Long?=null
    private var lastFacts: ProfileFacts?=null
    private var lastResponseAt=0L
    private fun guarded(action: () -> Unit) { try { action() } catch(_: android.database.SQLException) { pause(); message("DATABASE_ERROR") } }
    private fun post(delay: Long,action: () -> Unit) { handler.postDelayed({ guarded(action) },delay) }
    fun start(policy: ScanPolicy,selected: Set<String> = emptySet()) = guarded {
        if(running) return@guarded
        if(!allowed()) { message("OUTSIDE_SCHEDULE"); return@guarded }
        run=store.scanRun() ?: store.createScan(policy,selected); running=true; tick(); next()
    }
    private fun tick() {
        if(!running) return
        if(!allowed()) { pause(); message("OUTSIDE_SCHEDULE"); return }
        post(1000) { tick() }
    }
    private fun next() {
        if(!running) return
        if(!allowed()) { pause(); message("OUTSIDE_SCHEDULE"); return }
        val id=run ?: return
        val wait=store.scanNextAt(id)-System.currentTimeMillis()
        if(wait>0) { post(wait.coerceAtMost(1000)) { next() }; return }
        current=store.scanNext(id)
        val key=current ?: run { running=false; changed(); completed(); return }
        token++; lastFacts=null; lastResponseAt=0L; val generation=token
        browser()?.profile(key); changed()
        post(timeout) {
            if(running && token==generation) {
                if(allowed()) {
                    val facts=lastFacts
                    if(facts!=null && System.currentTimeMillis()-lastResponseAt<3500) {
                        // A responsive page with unreadable facts belongs in review; it must not stall the scan.
                        finish(ProfileFacts(reason="PROFILE_FACTS_UNAVAILABLE"))
                    } else { finish(ProfileFacts(reason="SCAN_TIMEOUT"),false); message("VERIFICATION_TIMEOUT") }
                } else pause()
            }
        }
        post(1200) { poll(generation,null) }
    }
    private fun poll(generation: Int,previous: ProfileFacts?) {
        if(!running || token!=generation) return
        if(!allowed()) { pause(); message("OUTSIDE_SCHEDULE"); return }
        val key=current ?: return
        browser()?.facts(key) { facts -> guarded {
            if(!running || token!=generation) return@guarded
            if(!allowed()) { pause(); return@guarded }
            lastFacts=facts; lastResponseAt=System.currentTimeMillis()
            if(facts.reason in setOf("LOGIN_REQUIRED","ACTION_RESTRICTED")) { pause(); message(facts.reason); return@guarded }
            if(facts==previous && (facts.privacy!=Privacy.UNKNOWN || facts.count!=null || facts.reason=="PROFILE_UNAVAILABLE")) {
                browser()?.inspect(key) { observation -> guarded {
                    if(running && token==generation && allowed()) {
                        if(observation in setOf(Observation.LOGIN,Observation.RESTRICTED)) { pause(); message(if(observation==Observation.LOGIN) "LOGIN_REQUIRED" else "ACTION_RESTRICTED") }
                        else finish(facts.copy(observation=observation))
                    }
                } }
            } else post(1000) { poll(generation,facts) }
        } }
    }
    private fun finish(facts: ProfileFacts,advance: Boolean = true) {
        val key=current ?: return; val id=run ?: return
        store.finishScan(id,key,facts,System.currentTimeMillis()+interval())
        token++; handler.removeCallbacksAndMessages(null); current=null; if(!advance) running=false; changed(); tick(); next()
    }
    fun fail(reason: String) = guarded {
        if(!running) return@guarded
        if(reason in setOf("LOGIN_REQUIRED","ACTION_RESTRICTED","RENDERER_GONE","NAVIGATION_BLOCKED")) { pause(); message(reason) }
        else { finish(ProfileFacts(reason=reason),false); message(reason) }
    }
    fun pause() { running=false; token++; handler.removeCallbacksAndMessages(null); runCatching { changed() } }
    fun stop() { pause() }
    fun skip() = guarded { if(running) finish(ProfileFacts(reason="USER_SKIPPED")) }
}
