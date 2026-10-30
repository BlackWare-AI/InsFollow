package ai.blackware.insfollow

import android.test.InstrumentationTestCase
import ai.blackware.insfollow.data.*
import ai.blackware.insfollow.model.*
import ai.blackware.insfollow.web.ProfileBrowser
import ai.blackware.insfollow.workflow.ScanWorkflow
import org.json.JSONObject

@Suppress("DEPRECATION")
class ScanDeviceTest : InstrumentationTestCase() {
    private lateinit var store: Store
    private val name="scan-fixture.db"
    private var engine: ScanWorkflow?=null
    private val good=ProfileFacts(Privacy.PUBLIC,FollowerNumbers.parse("4,000"),observation=Observation.FOLLOW)
    override fun setUp() {
        super.setUp(); instrumentation.targetContext.deleteDatabase(name); store=Store(instrumentation.targetContext,name)
        val raw="""{"favorite_page_pref":"[{\"name\":\"One\",\"items\":[{\"pageId\":\"fiction.one\",\"name\":\"One\"},{\"pageId\":\"fiction.two\",\"name\":\"Two\"}]},{\"name\":\"Two\",\"items\":[{\"pageId\":\" FICTION.ONE \",\"name\":\"Duplicate\"}]}]"}"""
        store.import(FavoritesCodec.parse(raw),"fixture.json"); store.selectAll(true)
    }
    override fun tearDown() { instrumentation.runOnMainSync { engine?.pause() }; store.close(); instrumentation.targetContext.deleteDatabase(name); super.tearDown() }
    private fun count(table: String)=store.readableDatabase.rawQuery("SELECT count(*) FROM $table",null).use { it.moveToFirst(); it.getInt(0) }
    fun testUniquePersistentScanAndRecovery() {
        val run=store.createScan(ScanPolicy.ALL); val first=store.scanNext(run)!!
        store.finishScan(run,first,good,0); val next=store.scanNext(run)!!; assertTrue(first!=next)
        store.close(); store=Store(instrumentation.targetContext,name); store.recover()
        assertEquals(run,store.scanRun()); assertEquals(next,store.scanNext(run)); assertEquals(1,store.scanResults().size)
    }
    fun testRescanHistoryDoesNotChangeFollowResult() {
        val id=store.createQueue(); val work=store.acquire(id)!!; store.finish(work,Verdict(Status.SUCCESS,"FOLLOWING_VERIFIED"),"FOLLOWING")
        store.saveScan(work.key,good); store.saveScan(work.key,ProfileFacts(Privacy.PRIVATE))
        assertEquals(Status.SUCCESS,store.accounts().first { it.key==work.key }.status); assertEquals(2,count("scan_history")); assertEquals(1,count("operations"))
    }
    fun testRecalculateWithoutDeletingObservations() {
        store.saveScan("fiction.one",good); store.settings(mapOf("followers" to "5000")); store.recalculateScans()
        assertEquals(Eligibility.FILTERED,store.scanResults().single().eligibility); assertEquals(1,count("scan_history"))
        store.settings(mapOf("followers" to "3000")); store.recalculateScans(); assertEquals(Eligibility.ELIGIBLE,store.scanResults().single().eligibility)
    }
    fun testFilteredQueuePreservesOriginalOrderAndRecords() {
        val id=store.createQueue(); val before=JSONObject(store.backup()).getJSONArray("queue").toString()
        store.saveScan("fiction.one",good); store.saveScan("fiction.two",ProfileFacts(Privacy.PRIVATE))
        assertEquals(id,store.filteredQueue()); assertEquals(before,JSONObject(store.backup()).getJSONArray("queue").toString())
        val work=store.acquire(id,true)!!; assertEquals("fiction.one",work.key)
        store.finish(work,Verdict(Status.SUCCESS,"FOLLOWING_VERIFIED"),"FOLLOWING"); assertNull(store.acquire(id,true))
        assertEquals(Status.UNPROCESSED,store.accounts().first { it.key=="fiction.two" }.status)
    }
    fun testPartialRescanExcludesStaleEligibilityButKeepsHistory() {
        store.saveScan("fiction.one",good); store.saveScan("fiction.two",good)
        val run=store.createScan(ScanPolicy.ALL)
        assertEquals(2,store.scanPendingKeys().size)
        val fresh=store.scanNext(run)!!; store.finishScan(run,fresh,good,0)
        assertEquals(1,store.scanPendingKeys().size); assertEquals(3,count("scan_history"))
        val session=store.filteredQueue(); val work=store.acquire(session,true)!!
        assertEquals(fresh,work.key); store.finish(work,Verdict(Status.ALREADY,"FOLLOWING_VERIFIED"),"FOLLOWING")
        assertNull(store.acquire(session,true)); assertEquals(2,store.scanResults().size)
    }
    fun testScanExportsAndBackupRoundtrip() {
        store.saveScan("fiction.one",good); store.saveScan("fiction.two",ProfileFacts(reason="SCAN_TIMEOUT"))
        assertEquals(2,JSONObject(store.exportScans("all")).getJSONArray("accounts").length())
        assertEquals(1,JSONObject(store.exportScans("eligible")).getJSONArray("accounts").length())
        assertEquals(1,JSONObject(store.exportScans("errors")).getJSONArray("accounts").length())
        val backup=store.backup(); store.restore(backup); assertEquals(2,store.scanResults().size); assertEquals(2,count("scan_history"))
    }
    fun testSchemaOneBackupRestoresWithoutScans() {
        val backup=JSONObject(store.backup()).put("schema",1)
        listOf("scans","scan_history","scan_runs","scan_queue","eligible_queue").forEach { backup.remove(it) }
        store.saveScan("fiction.one",good); store.restore(backup.toString())
        assertEquals(2,store.accounts().size); assertTrue(store.scanResults().isEmpty())
    }
    fun testSchemaOneDatabaseMigratesWithoutAccountLoss() {
        store.settings(mapOf("min" to "91")); val db=store.writableDatabase
        listOf("eligible_queue","scan_queue","scan_runs","scan_history","scans").forEach { db.execSQL("DROP TABLE $it") }; db.version=1
        store.close(); store=Store(instrumentation.targetContext,name)
        assertEquals(2,store.accounts().size); assertEquals("91",store.setting("min","")); assertTrue(store.scanResults().isEmpty()); assertEquals(2,store.readableDatabase.version)
    }
    fun testScanPolicies() {
        store.saveScan("fiction.one",good); val id=store.createScan(ScanPolicy.MISSING)
        assertEquals("fiction.two",store.scanNext(id)); store.finishScan(id,"fiction.two",ProfileFacts(reason="SCAN_TIMEOUT"),0); assertNull(store.scanNext(id))
        val errors=store.createScan(ScanPolicy.ERRORS); assertEquals("fiction.two",store.scanNext(errors))
    }
    fun testDailyReportsDoNotCarryOver() {
        val id=store.createQueue(); val work=store.acquire(id)!!; store.finish(work,Verdict(Status.REQUESTED,"FOLLOW_REQUEST_PENDING"),"REQUESTED")
        assertEquals(1,JSONObject(store.dailyReport()).getInt("requested")); assertEquals(0,JSONObject(store.dailyReport(java.time.LocalDate.now().plusDays(1))).getInt("visited"))
    }
    private class Browser(val facts: ProfileFacts): ProfileBrowser {
        val visits=mutableListOf<String>()
        override fun profile(key: String) { visits.add(key) }
        override fun facts(key: String,result: (ProfileFacts)->Unit) { result(facts) }
        override fun inspect(key: String,result: (Observation)->Unit) { result(Observation.FOLLOW) }
    }
    private fun waitUntil(condition: ()->Boolean) {
        val end=android.os.SystemClock.uptimeMillis()+12_000
        while(android.os.SystemClock.uptimeMillis()<end) {
            var done=false; instrumentation.runOnMainSync { done=condition() }; if(done) return
            Thread.sleep(50)
        }; fail("Scan did not reach expected state")
    }
    fun testReadOnlyScanUsesOneBrowserAndNeverCreatesFollowOperations() {
        val browser=Browser(good)
        instrumentation.runOnMainSync { engine=ScanWorkflow(store,{browser},{true},{0},{},{},{},5000).also { it.start(ScanPolicy.ALL) } }
        waitUntil { engine?.running==false }
        assertEquals(2,browser.visits.distinct().size); assertEquals(2,browser.visits.size); assertEquals(2,store.scanResults().size); assertEquals(0,count("operations"))
        assertTrue(store.accounts().all { it.status==Status.UNPROCESSED })
    }
    fun testMissingCallbackDeadlinePausesInsteadOfDrainingScan() {
        val browser=object: ProfileBrowser {
            override fun profile(key: String) {}
            override fun facts(key: String,result: (ProfileFacts)->Unit) {}
            override fun inspect(key: String,result: (Observation)->Unit) {}
        }
        instrumentation.runOnMainSync { engine=ScanWorkflow(store,{browser},{true},{0},{},{},{},150).also { it.start(ScanPolicy.ALL) } }
        waitUntil { engine?.running==false }
        assertEquals(1,store.scanResults().size); assertEquals(ScanState.SCAN_ERROR,store.scanResults().single().state)
        assertEquals(0,count("operations")); assertNotNull(store.scanRun())
    }
    fun testSecurityWarningPausesWithoutConsumingScan() {
        val browser=Browser(ProfileFacts(reason="ACTION_RESTRICTED")); val messages=mutableListOf<String>()
        instrumentation.runOnMainSync { engine=ScanWorkflow(store,{browser},{true},{0},{},{messages.add(it)},{},5000).also { it.start(ScanPolicy.ALL) } }
        waitUntil { engine?.running==false }; assertEquals(listOf("ACTION_RESTRICTED"),messages); assertEquals(0,store.scanResults().size); assertNotNull(store.scanRun()); assertEquals(0,count("operations"))
    }
    fun testPausedScanIgnoresLateCallback() {
        var callback: ((ProfileFacts)->Unit)?=null
        val browser=object: ProfileBrowser {
            override fun profile(key: String) {}
            override fun inspect(key: String,result: (Observation)->Unit) { result(Observation.FOLLOW) }
            override fun facts(key: String,result: (ProfileFacts)->Unit) { callback=result }
        }
        instrumentation.runOnMainSync { engine=ScanWorkflow(store,{browser},{true},{0},{},{},{},5000).also { it.start(ScanPolicy.ALL) } }
        waitUntil { callback!=null }; instrumentation.runOnMainSync { engine!!.pause(); callback!!(good) }
        assertTrue(store.scanResults().isEmpty()); assertNotNull(store.scanRun())
    }
}
