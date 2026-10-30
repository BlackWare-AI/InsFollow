package ai.blackware.insfollow

import ai.blackware.insfollow.model.*
import ai.blackware.insfollow.queue.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class ScanTest {
    @Test fun exactSeparators() { listOf("3.000","3,000","3000","3 000").forEach { assertEquals(FollowerCount(3000,3000,true,it),FollowerNumbers.parse(it)) } }
    @Test fun roundedForms() { listOf("3K","3.5K","3,5 B","12.4K","1M").forEach { assertNotNull(it,FollowerNumbers.parse(it)); assertFalse(FollowerNumbers.parse(it)!!.exact) } }
    @Test fun turkishThousandIsNotBillion() { assertEquals(3450,FollowerNumbers.parse("3,5 B")!!.lower) }
    @Test fun boundaryRoundedIsReview() { val f=AccountFilter(); assertEquals(Eligibility.REVIEW,f.evaluate(ProfileFacts(Privacy.PUBLIC,FollowerNumbers.parse("3K"))).second) }
    @Test fun exactBoundaryIsEligible() { assertEquals(Eligibility.ELIGIBLE,AccountFilter().evaluate(ProfileFacts(Privacy.PUBLIC,FollowerNumbers.parse("3.000"))).second) }
    @Test fun safelyAboveRoundedIsEligible() { assertEquals(Eligibility.ELIGIBLE,AccountFilter().evaluate(ProfileFacts(Privacy.PUBLIC,FollowerNumbers.parse("3.5K"))).second) }
    @Test fun definitelyBelowIsFiltered() { assertEquals(Eligibility.FILTERED,AccountFilter().evaluate(ProfileFacts(Privacy.PUBLIC,FollowerNumbers.parse("2K"))).second) }
    @Test fun privateIsSeparateFromFollowStatus() { assertEquals(ScanState.PRIVATE_ACCOUNT,AccountFilter().evaluate(ProfileFacts(Privacy.PRIVATE,FollowerNumbers.parse("5K"))).first) }
    @Test fun disablingBothPrivacyFiltersIsExplicit() { assertEquals(Eligibility.ELIGIBLE,AccountFilter(3000,false,false).evaluate(ProfileFacts(Privacy.PRIVATE,FollowerNumbers.parse("5K"))).second) }
    @Test fun unknownPrivacyNeverEligible() { assertEquals(Eligibility.REVIEW,AccountFilter(0,false,false).evaluate(ProfileFacts(count=FollowerNumbers.parse("5000"))).second) }
    @Test fun unknownCountNeverEligible() { assertEquals(ScanState.FOLLOWER_COUNT_UNKNOWN,AccountFilter(0).evaluate(ProfileFacts(Privacy.PUBLIC)).first) }
    @Test fun ambiguousFormatsRejected() { listOf("3,5","3.00","3,000.000","-3000","3e3","Infinity","3KK","3B","3.000K","3,000K","","9999999999999999999999K").forEach { assertNull(it,FollowerNumbers.parse(it)) } }
    @Test fun multipleWindowsAndDisabledDays() {
        val s=Schedule(1,2,LocalTime.MIN,LocalTime.MIN,0,0,mapOf(DayOfWeek.MONDAY to WeeklyWindows.parse("09:00-12:00,13:00-17:00")))
        assertTrue(s.allows(LocalDateTime.parse("2026-11-02T09:00")))
        assertFalse(s.allows(LocalDateTime.parse("2026-11-02T12:30")))
        assertFalse(s.allows(LocalDateTime.parse("2026-11-03T10:00")))
    }
    @Test fun midnightBelongsToPreviousDay() {
        val s=Schedule(1,1,LocalTime.MIN,LocalTime.MIN,0,0,mapOf(DayOfWeek.MONDAY to WeeklyWindows.parse("23:00-03:00")))
        assertTrue(s.allows(LocalDateTime.parse("2026-11-03T02:59"))); assertFalse(s.allows(LocalDateTime.parse("2026-11-03T03:00")))
    }
    @Test fun allDayAndEmptyWindows() { assertEquals(0,WeeklyWindows.parse("").size); assertEquals(1,WeeklyWindows.parse("00:00-00:00").size) }
    @Test fun invalidWindowsRejected() { assertThrows(Exception::class.java) { WeeklyWindows.parse("25:00-03:00") } }
    @Test fun filterChangesReevaluateSameObservation() { val facts=ProfileFacts(Privacy.PUBLIC,FollowerNumbers.parse("4000")); assertEquals(Eligibility.ELIGIBLE,AccountFilter(3000).evaluate(facts).second); assertEquals(Eligibility.FILTERED,AccountFilter(5000).evaluate(facts).second) }
}
