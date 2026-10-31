package ai.blackware.insfollow

import android.test.InstrumentationTestCase
import ai.blackware.insfollow.data.FavoritesCodec
import ai.blackware.insfollow.data.Store
import ai.blackware.insfollow.data.scanResults
import ai.blackware.insfollow.data.dailyReport
import ai.blackware.insfollow.model.*
import ai.blackware.insfollow.queue.Schedule
import ai.blackware.insfollow.web.ProfileBrowser
import ai.blackware.insfollow.workflow.AssistedWorkflow
import ai.blackware.insfollow.workflow.FollowExecutor
import java.time.LocalTime

/** Deterministic browser observations; no network requests or real follow actions. */
@Suppress("DEPRECATION")
class WorkflowDeviceTest : InstrumentationTestCase() {
    private lateinit var store: Store
    private var workflow: AssistedWorkflow? = null
    private val filename = "workflow-fixture.db"
    private val messages = mutableListOf<String>()
    private var actions = 0

    private class FixtureBrowser(var observation: Observation) : ProfileBrowser {
        var factsValue=ProfileFacts(Privacy.PUBLIC,FollowerNumbers.parse("4000"),observation=Observation.FOLLOW)
        override fun facts(key: String,result: (ProfileFacts)->Unit) { result(factsValue) }
        val visits=mutableListOf<String>()
        var onProfile: (String)->Unit = {}
        var navigations = 0
        var inspections = 0
        var defer = false
        var callback: ((Observation) -> Unit)? = null
        override fun profile(key: String) { navigations++; visits.add(key); onProfile(key) }
        override fun inspect(key: String, result: (Observation) -> Unit) {
            inspections++
            if (defer) callback = result else result(observation)
        }
    }

    override fun setUp() {
        super.setUp()
        instrumentation.targetContext.deleteDatabase(filename)
        store = Store(instrumentation.targetContext, filename)
        val raw = """{"favorite_page_pref":"[{\"name\":\"Fixture\",\"items\":[{\"pageId\":\"fiction.one\",\"name\":\"One\"},{\"pageId\":\"fiction.two\",\"name\":\"Two\"}]}]"}"""
        store.import(FavoritesCodec.parse(raw), "fixture.json")
        store.selectAll(true)
    }

    override fun tearDown() {
        instrumentation.runOnMainSync { workflow?.close() }
        store.close()
        instrumentation.targetContext.deleteDatabase(filename)
        super.tearDown()
    }

    private fun start(browser: FixtureBrowser, timeout: Long = 10_000, filters: Boolean = false, config: Schedule = Schedule(1,1,LocalTime.MIN,LocalTime.MIN,1,3), afterStart: () -> Unit = {}) {
        instrumentation.runOnMainSync {
            workflow = AssistedWorkflow(
                store, { browser }, { config },
                object : FollowExecutor { override fun requestUserAction(account: String) { actions++ } },
                { _, _ -> }, { messages.add(it) }, timeout,filters
            ).also { it.expanded = true; it.foreground(true); it.start() }
            afterStart()
        }
    }

    private fun assertFilteredContinuation(observation: Observation,facts: ProfileFacts,timeout: Long=10_000) {
        val session=store.createQueue()
        val order=store.readableDatabase.rawQuery("SELECT accountKey FROM queue WHERE sessionId=? ORDER BY queuePosition",arrayOf(session.toString())).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
        val deadlines=mutableListOf<Pair<Long,Long>>()
        val browser=FixtureBrowser(observation).apply {
            factsValue=facts
            onProfile={ deadlines.add(System.currentTimeMillis() to store.nextAt(session)) }
        }
        start(browser,timeout,filters=true,config=Schedule(1,2,LocalTime.MIN,LocalTime.MIN,0,3))
        awaitCondition { workflow?.running==false }
        assertEquals(order,browser.visits)
        assertEquals(2,store.scanResults().size); assertEquals(2,store.countProcessed(session))
        assertEquals(0,actions); assertFalse("VERIFICATION_TIMEOUT" in messages)
        assertTrue(store.accounts().all { it.status==Status.UNPROCESSED })
        assertTrue(deadlines[1].second>0); assertTrue(deadlines[1].first>=deadlines[1].second)
        assertEquals(2,store.queueStats(session)["SKIPPED"])
    }
    fun testPrivateRejectionContinuesWithIntervalAndOriginalQueueOrder() = assertFilteredContinuation(
        Observation.FOLLOW,ProfileFacts(Privacy.PRIVATE,FollowerNumbers.parse("5000")))
    fun testUnreadableFollowControlStillSkipsBelowThresholdProfile() = assertFilteredContinuation(
        Observation.UNKNOWN,ProfileFacts(Privacy.PUBLIC,FollowerNumbers.parse("1000")))
    fun testResponsiveUnknownProfileGoesToReviewAndContinues() = assertFilteredContinuation(
        Observation.UNKNOWN,ProfileFacts(),3500)
    fun testDirectFilteringNeverRequestsUserFollowForPrivateAccounts() {
        val browser=FixtureBrowser(Observation.FOLLOW).apply { factsValue=ProfileFacts(Privacy.PRIVATE,FollowerNumbers.parse("5000")) }
        start(browser,filters=true)
        awaitCondition { workflow?.running==false }
        assertEquals(0,actions); assertTrue(store.accounts().all { it.status==Status.UNPROCESSED })
        assertEquals(1,store.scanResults().size)
        val report=org.json.JSONObject(store.dailyReport()); assertEquals(1,report.getInt("visited")); assertEquals(1,report.getInt("observations"))
    }
    fun testDirectRoundedBoundaryRequiresReview() {
        val browser=FixtureBrowser(Observation.FOLLOW).apply { factsValue=ProfileFacts(Privacy.PUBLIC,FollowerNumbers.parse("3K")) }
        start(browser,filters=true); awaitCondition { workflow?.running==false }
        assertEquals(0,actions); assertTrue(store.scanResults().all { it.eligibility==Eligibility.REVIEW })
    }
    fun testDirectEligibleProfileRequestsAssistedAction() {
        start(FixtureBrowser(Observation.FOLLOW),filters=true)
        awaitCondition { actions==1 }; assertEquals(Stage.AWAITING_USER_ACTION,workflow!!.stage)
        assertTrue(store.accounts().all { it.status==Status.UNPROCESSED })
    }
    fun testDailyTargetBlocksNewNavigation() {
        val id=store.createQueue(); store.finish(store.acquire(id)!!,Verdict(Status.ALREADY,"FOLLOWING_VERIFIED"),"FOLLOWING")
        val browser=FixtureBrowser(Observation.FOLLOW)
        start(browser,config=Schedule(1,1,LocalTime.MIN,LocalTime.MIN,0,3,dailyLimit=1))
        assertFalse(workflow!!.running); assertEquals(0,browser.navigations); assertTrue("DAILY_LIMIT" in messages)
    }
    fun testSessionBreakSurvivesEngineRecreation() {
        val id=store.createQueue(); store.finish(store.acquire(id)!!,Verdict(Status.ALREADY,"FOLLOWING_VERIFIED"),"FOLLOWING")
        val browser=FixtureBrowser(Observation.FOLLOW)
        val config=Schedule(1,1,LocalTime.MIN,LocalTime.MIN,1,3,breakMinutes=60)
        start(browser,config=config); assertTrue("SESSION_LIMIT" in messages); assertFalse(workflow!!.running)
        start(browser,config=config); assertTrue("SESSION_BREAK" in messages); assertEquals(0,browser.navigations)
    }
    fun testQueueCompletionAppliesSessionBreakWithoutAnExtraInterval() {
        val browser=FixtureBrowser(Observation.FOLLOWING)
        start(browser,config=Schedule(3600,3600,LocalTime.MIN,LocalTime.MIN,0,3,breakMinutes=60),afterStart={
            // Keep one queued account so exhaustion is reached by this verified operation.
            val current=store.current()!!
            store.writableDatabase.delete("queue","sessionId=? AND accountKey!=?",arrayOf(current.session.toString(),current.key))
        })
        awaitCondition { workflow?.running==false }
        assertEquals(Stage.COMPLETED,workflow!!.stage)
        assertTrue(store.setting("break_until","0").toLong()>System.currentTimeMillis())
        assertEquals(1,store.accounts().count { it.status==Status.ALREADY })
    }
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        var satisfied = false
        while (!satisfied && android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { satisfied = condition() }
            if (!satisfied) android.os.SystemClock.sleep(25)
        }
        assertTrue("Timed out waiting for the workflow condition", satisfied)
    }

    private fun finishedAccount() = store.accounts().single { it.attempts == 1 }

    private fun assertAutomatic(observation: Observation, expected: Status, reason: String) {
        val browser = FixtureBrowser(observation)
        start(browser)
        awaitCondition { workflow?.running == false }
        assertEquals(expected, finishedAccount().status)
        assertEquals(reason, finishedAccount().reason)
        assertEquals(0, actions)
        assertEquals(2, browser.navigations)
        assertEquals(4, browser.inspections)
    }

    fun testAlreadyFollowingRequiresRefreshAndRepeatedObservations() {
        assertAutomatic(Observation.FOLLOWING, Status.ALREADY, "FOLLOWING_VERIFIED")
    }

    fun testPendingRequestIsNotFollowing() {
        assertAutomatic(Observation.REQUESTED, Status.REQUESTED, "FOLLOW_REQUEST_PENDING")
    }

    fun testUnavailableProfileRequiresRefresh() {
        assertAutomatic(Observation.UNAVAILABLE, Status.FAILURE, "PROFILE_NOT_FOUND")
    }

    private fun assertAfterAction(observation: Observation, status: Status) {
        val browser = FixtureBrowser(Observation.FOLLOW)
        start(browser)
        awaitCondition { workflow?.stage == Stage.AWAITING_USER_ACTION }
        assertEquals(Status.UNPROCESSED, finishedAccount().status)
        assertEquals(1, actions)
        instrumentation.runOnMainSync {
            browser.observation = observation
            workflow!!.verify()
        }
        awaitCondition { workflow?.running == false }
        assertEquals(status, finishedAccount().status)
        assertEquals(2, browser.navigations)
    }

    fun testUserActionIsVerifiedAfterRefresh() = assertAfterAction(Observation.FOLLOWING, Status.SUCCESS)
    fun testUserActionCanResultInPendingRequest() = assertAfterAction(Observation.REQUESTED, Status.REQUESTED)
    fun testUserActionWithoutFollowingIsNotSuccess() = assertAfterAction(Observation.FOLLOW, Status.FAILURE)

    fun testUnknownPageTimesOutWithoutFailureOrAction() {
        start(FixtureBrowser(Observation.UNKNOWN), 3_000)
        awaitCondition { workflow?.running == false }
        assertEquals(Status.UNCERTAIN, finishedAccount().status)
        assertEquals("VERIFICATION_TIMEOUT", finishedAccount().reason)
        assertEquals(0, actions)
    }

    fun testForegroundPausePreservesOperationAndRequiresManualResume() {
        val browser = FixtureBrowser(Observation.FOLLOW)
        start(browser)
        instrumentation.runOnMainSync { workflow!!.foreground(false) }
        assertEquals(Status.UNPROCESSED, finishedAccount().status)
        assertNotNull(store.current())
        instrumentation.runOnMainSync { workflow!!.foreground(true) }
        assertFalse(workflow!!.running)
        assertEquals(1, browser.navigations)
        instrumentation.runOnMainSync { browser.observation = Observation.FOLLOWING; workflow!!.start() }
        awaitCondition { workflow?.running == false }
        assertEquals(Status.ALREADY, finishedAccount().status)
        assertEquals(1, finishedAccount().attempts)
        assertEquals(0, actions)
    }

    fun testLateInspectionCallbackAfterPauseCannotCommit() {
        val browser = FixtureBrowser(Observation.FOLLOWING).apply { defer = true }
        start(browser)
        awaitCondition { browser.callback != null }
        instrumentation.runOnMainSync {
            workflow!!.pause()
            browser.callback!!.invoke(Observation.FOLLOWING)
        }
        assertFalse(workflow!!.running)
        assertEquals(Status.UNPROCESSED, finishedAccount().status)
        assertNotNull(store.current())
    }

    fun testRestrictionsBetweenAccountsStopWithoutConsumingQueue() {
        val id = store.createQueue()
        store.setNext(id, System.currentTimeMillis() + 60_000)
        start(FixtureBrowser(Observation.FOLLOW))
        instrumentation.runOnMainSync { workflow!!.fail("ACTION_RESTRICTED") }
        assertFalse(workflow!!.running)
        assertTrue(messages.contains("ACTION_RESTRICTED"))
        assertTrue(store.accounts().all { it.attempts == 0 && it.status == Status.UNPROCESSED })
        assertEquals(2, store.queueStats(id)["QUEUED"])
    }

    fun testLoginChallengeBetweenAccountsStops() {
        val id = store.createQueue()
        store.setNext(id, System.currentTimeMillis() + 60_000)
        start(FixtureBrowser(Observation.FOLLOW))
        instrumentation.runOnMainSync { workflow!!.navigation("https://www.instagram.com/accounts/login/") }
        assertFalse(workflow!!.running)
        assertTrue(messages.contains("LOGIN_REQUIRED"))
        assertNull(store.current())
        assertEquals(2, store.queueStats(id)["QUEUED"])
    }

    fun testScheduledDatabaseFailurePausesAndCanRecover() {
        start(FixtureBrowser(Observation.FOLLOWING), afterStart = {
            store.writableDatabase.execSQL("CREATE TRIGGER reject_stage BEFORE UPDATE ON operations BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        })
        try {
            awaitCondition { messages.contains("DATABASE_ERROR") }
            assertFalse(workflow!!.running)
            assertEquals(Status.UNPROCESSED, finishedAccount().status)
            assertNotNull(store.current())
        } finally { store.writableDatabase.execSQL("DROP TRIGGER reject_stage") }
        store.recover()
        assertEquals(Status.UNCERTAIN, finishedAccount().status)
        assertEquals("INTERRUPTED_VERIFICATION", finishedAccount().reason)
    }

    fun testAsynchronousDatabaseFailureCannotCrashOrOpenAnotherPage() {
        val browser = FixtureBrowser(Observation.FOLLOWING).apply { defer = true }
        start(browser)
        awaitCondition { browser.inspections == 1 }
        instrumentation.runOnMainSync { browser.callback!!.invoke(Observation.FOLLOWING) }
        awaitCondition { browser.inspections == 2 }
        store.writableDatabase.execSQL("CREATE TRIGGER reject_stage BEFORE UPDATE ON operations BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        try {
            instrumentation.runOnMainSync { browser.callback!!.invoke(Observation.FOLLOWING) }
            assertFalse(workflow!!.running)
            assertTrue(messages.contains("DATABASE_ERROR"))
            assertEquals(1, browser.navigations)
            assertEquals(Status.UNPROCESSED, finishedAccount().status)
        } finally { store.writableDatabase.execSQL("DROP TRIGGER reject_stage") }
    }
    fun testSkipRecordsUncertaintyWithoutRepeatingAction() {
        start(FixtureBrowser(Observation.FOLLOW))
        awaitCondition { workflow?.stage == Stage.AWAITING_USER_ACTION }
        instrumentation.runOnMainSync { workflow!!.skip() }
        assertFalse(workflow!!.running)
        assertEquals(Status.UNCERTAIN, finishedAccount().status)
        assertEquals("USER_SKIPPED", finishedAccount().reason)
        assertEquals(1, store.queueStats(store.session()!!)["SKIPPED"])
        assertNull(store.current())
    }

    private fun assertBrowserFailure(reason: String) {
        start(FixtureBrowser(Observation.FOLLOW))
        instrumentation.runOnMainSync { workflow!!.fail(reason) }
        assertFalse(workflow!!.running)
        assertEquals(Status.UNCERTAIN, finishedAccount().status)
        assertEquals(reason, finishedAccount().reason)
        assertEquals(0, actions)
    }

    fun testNetworkFailureCannotBecomeConfirmedFailure() = assertBrowserFailure("NETWORK_ERROR")
    fun testRendererLossCannotBecomeSuccess() = assertBrowserFailure("RENDERER_GONE")

    fun testRestrictionWhileAwaitingUserActionStopsWithoutVerification() {
        val browser = FixtureBrowser(Observation.FOLLOW)
        start(browser)
        awaitCondition { workflow?.stage == Stage.AWAITING_USER_ACTION }
        instrumentation.runOnMainSync { browser.observation = Observation.RESTRICTED }
        awaitCondition { workflow?.running == false }
        assertEquals(Status.UNCERTAIN, finishedAccount().status)
        assertEquals("ACTION_RESTRICTED", finishedAccount().reason)
        assertEquals(1, browser.navigations)
    }

}
