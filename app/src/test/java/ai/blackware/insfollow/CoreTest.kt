package ai.blackware.insfollow

import ai.blackware.insfollow.data.*
import ai.blackware.insfollow.model.*
import ai.blackware.insfollow.queue.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime
import kotlin.random.Random

class CoreTest {
    private fun source(items: JSONArray=JSONArray().put(JSONObject().put("pageId"," Fiction.One ").put("name","Funda Özken"))): String = JSONObject().put("unrelated",JSONObject().put("enabled",true)).put("favorite_page_pref",JSONArray().put(JSONObject().put("name","FAV").put("items",items)).toString()).toString()
    private fun rejected(raw: String) { assertThrows(InputProblem::class.java) { FavoritesCodec.parse(raw) } }
    @Test fun validNestedFavorites() { val parsed=FavoritesCodec.parse(source()); assertEquals("FAV",parsed.folders.single().name); assertEquals("fiction.one",parsed.folders.single().items.single().key) }
    @Test fun originalUsernameAndUnicodePreserved() { val item=FavoritesCodec.parse(source()).folders.single().items.single(); assertEquals(" Fiction.One ",item.username); assertEquals("Funda Özken",item.name) }
    @Test fun missingKey() { rejected("{}") }
    @Test fun malformedOuter() { rejected("{broken") }
    @Test fun malformedNested() { rejected("{\"favorite_page_pref\":\"[bad\"}") }
    @Test fun wrongNestedType() { rejected("{\"favorite_page_pref\":[]}") }
    @Test fun emptyFolder() { assertTrue(FavoritesCodec.parse(source(JSONArray())).folders.single().items.isEmpty()) }
    @Test fun emptyUsername() { rejected(source(JSONArray().put(JSONObject().put("pageId"," ").put("name","Fiction")))) }
    @Test fun invalidUsernameUrlRejected() { rejected(source(JSONArray().put(JSONObject().put("pageId","https://example.com").put("name","Fiction")))) }
    @Test fun duplicateMembershipPreserved() { val outer=JSONObject(source()); val folder=JSONArray(outer.getString("favorite_page_pref")).getJSONObject(0); outer.put("favorite_page_pref",JSONArray().put(folder).put(folder).toString()); val parsed=FavoritesCodec.parse(outer.toString()); assertEquals(2,parsed.folders.size); assertEquals(1,parsed.folders.flatMap { it.items }.map { it.key }.distinct().size) }
    @Test fun repeatedParseStableFingerprint() { assertEquals(FavoritesCodec.parse(source()).hash,FavoritesCodec.parse(source()).hash) }
    @Test fun oversizeBackupRejected() { rejected(" ".repeat(FavoritesCodec.MAX_BYTES+1)) }
    @Test fun deepNestedInputRejected() { rejected("[".repeat(33)+"]".repeat(33)) }
    @Test fun largeValidBackup() { val items=JSONArray(); repeat(10000) { items.put(JSONObject().put("pageId","fiction.$it").put("name","Ü $it")) }; assertEquals(10000,FavoritesCodec.parse(source(items)).folders.single().items.size) }
    @Test fun exportReimportPreservesOuterAndPageId() { val output=FavoritesCodec.annotate(source(),mapOf("fiction.one" to Status.SUCCESS),false); val parsed=FavoritesCodec.parse(output); assertEquals(" Fiction.One ",parsed.folders.single().items.single().username); assertEquals("Funda Özken S",parsed.folders.single().items.single().name); assertTrue(JSONObject(output).getJSONObject("unrelated").getBoolean("enabled")) }
    @Test fun repeatedExportIsIdempotentFromSource() { val raw=source(); assertEquals(FavoritesCodec.annotate(raw,mapOf("fiction.one" to Status.ALREADY),true),FavoritesCodec.annotate(raw,mapOf("fiction.one" to Status.SUCCESS),true)); assertEquals("Funda Özken",FavoritesCodec.parse(raw).folders.single().items.single().name) }
    @Test fun originalStatusLikeSuffixNeverStripped() { val raw=source(JSONArray().put(JSONObject().put("pageId","fiction.one").put("name","Original S"))); assertEquals("Original S S",FavoritesCodec.parse(FavoritesCodec.annotate(raw,mapOf("fiction.one" to Status.SUCCESS),true)).folders.single().items.single().name) }
    @Test fun pendingAndUncertainNotAnnotated() { for(status in listOf(Status.REQUESTED,Status.UNCERTAIN,Status.UNPROCESSED)) assertEquals("Funda Özken",FavoritesCodec.parse(FavoritesCodec.annotate(source(),mapOf("fiction.one" to status),true)).folders.single().items.single().name) }
    @Test fun failureAnnotationOptional() { assertEquals("Funda Özken",FavoritesCodec.parse(FavoritesCodec.annotate(source(),mapOf("fiction.one" to Status.FAILURE),false)).folders.single().items.single().name) }
    @Test fun uniqueQueueExcludesCompleted() { assertEquals(listOf("b"),QueueOrder.eligible(listOf("a" to true,"b" to false,"b" to false),Random(7))) }
    @Test fun everyUniqueAccountOnce() { val keys=(1..1000).map { "fiction.$it" to false }; val order=QueueOrder.eligible(keys+keys,Random(8)); assertEquals(1000,order.size); assertEquals(keys.map { it.first }.toSet(),order.toSet()); assertNotEquals(keys.map { it.first },order) }
    @Test fun emptyQueueSafe() { assertTrue(QueueOrder.eligible(emptyList()).isEmpty()) }
    private fun hours(start: String,end: String)=Schedule(5,10,LocalTime.parse(start),LocalTime.parse(end),0,3)
    @Test fun normalOperatingWindow() { val s=hours("02:00","07:00"); assertTrue(s.allows(LocalTime.parse("02:00"))); assertFalse(s.allows(LocalTime.parse("07:00"))); assertFalse(s.allows(LocalTime.parse("01:59"))) }
    @Test fun midnightWindow() { val s=hours("23:00","03:00"); assertTrue(s.allows(LocalTime.parse("00:00"))); assertTrue(s.allows(LocalTime.parse("23:59"))); assertFalse(s.allows(LocalTime.parse("03:00"))); assertFalse(s.allows(LocalTime.NOON)) }
    @Test fun equalWindowMeansAllDay() { assertTrue(hours("00:00","00:00").allows(LocalTime.NOON)) }
    @Test fun intervalsValidateAndStayWithinBounds() { assertThrows(IllegalArgumentException::class.java) { Schedule(10,5,LocalTime.MIN,LocalTime.MIN,0,3) }; repeat(100) { assertTrue(hours("00:00","00:00").interval(Random(it)) in 5000..10000) } }
    @Test fun confirmedFollowRequiresObservation() { assertEquals(Status.SUCCESS,Results.decide(Observation.FOLLOWING,true).status) }
    @Test fun alreadyFollowing() { assertEquals(Status.ALREADY,Results.decide(Observation.FOLLOWING,false).status) }
    @Test fun requestIsNotSuccess() { assertEquals(Status.REQUESTED,Results.decide(Observation.REQUESTED,true).status) }
    @Test fun unavailableProfile() { assertEquals("PROFILE_NOT_FOUND",Results.decide(Observation.UNAVAILABLE,false).reason) }
    @Test fun loginUnknownAndRestrictionStayUncertain() { for(observation in listOf(Observation.LOGIN,Observation.RESTRICTED,Observation.UNKNOWN)) assertEquals(Status.UNCERTAIN,Results.decide(observation,true).status) }
    @Test fun confirmedResultSurvivesUncertainty() { for(status in Status.entries.filter { it.confirmed }) assertEquals(status,Results.retained(status,Status.UNCERTAIN)) }
    @Test fun verifiedFollowStillAbsentIsFailure() { assertEquals(Status.FAILURE,Results.decide(Observation.FOLLOW,true).status) }
}
