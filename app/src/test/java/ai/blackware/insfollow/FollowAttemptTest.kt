package ai.blackware.insfollow

import ai.blackware.insfollow.web.FollowAttempt
import org.junit.Assert.assertEquals
import org.junit.Test

class FollowAttemptTest {
    @Test fun dispatchedIsNotAConfirmedRelationship() = assertEquals(FollowAttempt.DISPATCHED,FollowAttempt.fromScript("CLICKED"))
    @Test fun onlyDefiniteNonDispatchAllowsFallback() = assertEquals(FollowAttempt.NOT_DISPATCHED,FollowAttempt.fromScript("NOT_FOUND"))
    @Test fun loginIsDistinctFromNonDispatch() = assertEquals(FollowAttempt.LOGIN_REQUIRED,FollowAttempt.fromScript("LOGIN"))
    @Test fun restrictionIsDistinctFromNonDispatch() = assertEquals(FollowAttempt.RESTRICTED,FollowAttempt.fromScript("RESTRICTED"))
    @Test fun ambiguousAndUnexpectedResultsStayUncertain() {
        listOf("AMBIGUOUS","STATE_CHANGED","WRONG_PROFILE","NO_HEADER","FAILED","UNCERTAIN","null","").forEach {
            assertEquals(it,FollowAttempt.UNCERTAIN,FollowAttempt.fromScript(it))
        }
    }
}
