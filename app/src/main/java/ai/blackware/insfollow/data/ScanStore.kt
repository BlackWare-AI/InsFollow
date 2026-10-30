package ai.blackware.insfollow.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import ai.blackware.insfollow.model.*
import org.json.JSONArray
import org.json.JSONObject

internal object ScanSchema {
    val tables=listOf("scans","scan_history","scan_runs","scan_queue")
    fun create(db: SQLiteDatabase) {
        val fields="accountKey TEXT NOT NULL REFERENCES accounts(accountKey), privacy TEXT NOT NULL, lowerCount INTEGER, upperCount INTEGER, exactCount INTEGER NOT NULL, rawCount TEXT NOT NULL, checkedAt INTEGER NOT NULL, state TEXT NOT NULL, eligibility TEXT NOT NULL, reason TEXT NOT NULL, observation TEXT NOT NULL"
        db.execSQL("CREATE TABLE scans ($fields, PRIMARY KEY(accountKey))")
        db.execSQL("CREATE TABLE scan_history (id INTEGER PRIMARY KEY, $fields)")
        db.execSQL("CREATE TABLE scan_runs (id INTEGER PRIMARY KEY, createdAt INTEGER NOT NULL, state TEXT NOT NULL, nextAt INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE scan_queue (runId INTEGER NOT NULL REFERENCES scan_runs(id), accountKey TEXT NOT NULL REFERENCES accounts(accountKey), position INTEGER NOT NULL, state TEXT NOT NULL, PRIMARY KEY(runId,accountKey), UNIQUE(runId,position))")
    }
}
private fun rowValues(vararg pairs: Pair<String,Any?>)=ContentValues().apply { pairs.forEach { (k,v) -> when(v) { null -> putNull(k); is Long -> put(k,v); is Int -> put(k,v); else -> put(k,v.toString()) } } }
fun Store.accountFilter()=AccountFilter(setting("followers","3000").toLong(),setting("public_only","true")=="true",setting("skip_private","true")=="true")
fun Store.scanResults(): List<ScanResult> = readableDatabase.rawQuery("SELECT * FROM scans ORDER BY accountKey",null).use { c -> buildList {
    while(c.moveToNext()) {
        fun s(n: String)=c.getString(c.getColumnIndexOrThrow(n))
        fun l(n: String)=c.getLong(c.getColumnIndexOrThrow(n))
        val count=if(c.isNull(c.getColumnIndexOrThrow("lowerCount"))) null else FollowerCount(l("lowerCount"),l("upperCount"),l("exactCount")==1L,s("rawCount"))
        add(ScanResult(s("accountKey"),ProfileFacts(Privacy.valueOf(s("privacy")),count,s("reason").takeIf { s("state")=="SCAN_ERROR" || s("state")=="PROFILE_UNAVAILABLE" }.orEmpty(),Observation.valueOf(s("observation"))),l("checkedAt"),ScanState.valueOf(s("state")),Eligibility.valueOf(s("eligibility")),s("reason")))
    }
} }
fun Store.saveScan(key: String,facts: ProfileFacts,filter: AccountFilter=accountFilter()) = transaction { db ->
    val (state,eligible,reason)=filter.evaluate(facts)
    val values=rowValues("accountKey" to key,"privacy" to facts.privacy.name,"lowerCount" to facts.count?.lower,"upperCount" to facts.count?.upper,"exactCount" to if(facts.count?.exact==true) 1 else 0,"rawCount" to facts.count?.raw.orEmpty(),"checkedAt" to System.currentTimeMillis(),"state" to state.name,"eligibility" to eligible.name,"reason" to reason,"observation" to facts.observation.name)
    db.insertOrThrow("scan_history",null,values)
    db.insertWithOnConflict("scans",null,values,SQLiteDatabase.CONFLICT_REPLACE)
    Unit
}
fun Store.recalculateScans() = transaction { db ->
    val filter=accountFilter()
    scanResults().forEach { row -> val (state,eligible,reason)=filter.evaluate(row.facts); db.update("scans",rowValues("state" to state.name,"eligibility" to eligible.name,"reason" to reason),"accountKey=?",arrayOf(row.key)) }
}
fun Store.selectedKeys(): List<String> = readableDatabase.rawQuery("SELECT DISTINCT m.accountKey FROM memberships m JOIN folders f ON m.folderId=f.id WHERE f.selected=1 ORDER BY m.accountKey",null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
fun Store.scanRun(): Long? = readableDatabase.rawQuery("SELECT id FROM scan_runs WHERE state NOT IN ('COMPLETED','STOPPED') ORDER BY id DESC LIMIT 1",null).use { if(it.moveToFirst()) it.getLong(0) else null }
fun Store.createScan(policy: ScanPolicy,selected: Set<String> = emptySet()): Long = transaction { db ->
    scanRun()?.let { return@transaction it }
    recalculateScans()
    val known=scanResults().associateBy { it.key }
    val keys=selectedKeys().filter { key -> when(policy) {
        ScanPolicy.USE_CACHE,ScanPolicy.MISSING -> key !in known
        ScanPolicy.ERRORS -> known[key]?.eligibility==Eligibility.REVIEW
        ScanPolicy.SELECTED -> key in selected
        ScanPolicy.ALL -> true
    } }.shuffled()
    val id=db.insertOrThrow("scan_runs",null,rowValues("createdAt" to System.currentTimeMillis(),"state" to if(keys.isEmpty()) "COMPLETED" else "PAUSED"))
    keys.forEachIndexed { i,key -> db.insertOrThrow("scan_queue",null,rowValues("runId" to id,"accountKey" to key,"position" to i,"state" to "QUEUED")) }; id
}
fun Store.scanNext(id: Long): String? = transaction { db ->
    val key=db.rawQuery("SELECT accountKey FROM scan_queue WHERE runId=? AND state!='DONE' ORDER BY position LIMIT 1",arrayOf(id.toString())).use { if(it.moveToFirst()) it.getString(0) else null }
    if(key==null) db.update("scan_runs",rowValues("state" to "COMPLETED"),"id=?",arrayOf(id.toString()))
    else db.update("scan_queue",rowValues("state" to "SCANNING"),"runId=? AND accountKey=?",arrayOf(id.toString(),key))
    key
}
fun Store.finishScan(id: Long,key: String,facts: ProfileFacts,nextAt: Long) = transaction { db ->
    saveScan(key,facts)
    db.update("scan_queue",rowValues("state" to "DONE"),"runId=? AND accountKey=?",arrayOf(id.toString(),key))
    db.update("scan_runs",rowValues("nextAt" to nextAt),"id=?",arrayOf(id.toString()))
}
fun Store.scanNextAt(id: Long)=readableDatabase.rawQuery("SELECT nextAt FROM scan_runs WHERE id=?",arrayOf(id.toString())).use { if(it.moveToFirst()) it.getLong(0) else 0 }
fun Store.filteredQueue(): Long = transaction { db ->
    require(current()==null) { "OPERATION_ACTIVE" }
    recalculateScans()
    val id=session() ?: createQueue()
    settings(mapOf("filtered_$id" to "true"))
    id
}
fun Store.deferFiltered(work: Work) = transaction { db ->
    // A filtering decision is not a follow outcome and never changes the account's follow code.
    db.update("operations",rowValues("finishedAt" to System.currentTimeMillis(),"verificationResult" to "FILTERED","errorCode" to "FILTERED","resultingStatus" to null,"stage" to "COMPLETED"),"operationId=? AND finishedAt IS NULL",arrayOf(work.operation.toString()))
    db.update("queue",rowValues("queueState" to "SKIPPED"),"sessionId=? AND accountKey=?",arrayOf(work.session.toString(),work.key))
}
fun Store.exportScans(kind: String): String {
    val selected=scanResults().filter { when(kind) { "eligible" -> it.eligibility==Eligibility.ELIGIBLE; "filtered" -> it.eligibility==Eligibility.FILTERED; "errors" -> it.eligibility==Eligibility.REVIEW; else -> true } }
    val rows=JSONArray()
    selected.forEach { r -> rows.put(JSONObject().put("username",r.key).put("privacy",r.facts.privacy.name).put("followerCount",if(r.facts.count?.exact==true) r.facts.count.lower else JSONObject.NULL).put("followerLower",r.facts.count?.lower ?: JSONObject.NULL).put("followerUpper",r.facts.count?.upper ?: JSONObject.NULL).put("displayedCount",r.facts.count?.raw.orEmpty()).put("checkedAt",r.checkedAt).put("state",r.state.name).put("eligibility",r.eligibility.name).put("reason",r.reason)) }
    return JSONObject().put("version","0.1").put("exportedAt",System.currentTimeMillis()).put("accounts",rows).toString(2)
}

fun Store.dailyReport(date: java.time.LocalDate = java.time.LocalDate.now()): String {
    val zone=java.time.ZoneId.systemDefault()
    val start=date.atStartOfDay(zone).toInstant().toEpochMilli()
    val end=date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val report=JSONObject().put("date",date.toString()).put("timezone",zone.id)
    readableDatabase.rawQuery("SELECT count(*),sum(CASE WHEN resultingStatus IN ('S','A') THEN 1 ELSE 0 END),sum(CASE WHEN resultingStatus='R' THEN 1 ELSE 0 END),sum(CASE WHEN resultingStatus='F' THEN 1 ELSE 0 END),sum(CASE WHEN resultingStatus='U' THEN 1 ELSE 0 END) FROM operations WHERE startedAt>=? AND startedAt<?",arrayOf(start.toString(),end.toString())).use { c ->
        c.moveToFirst(); listOf("visited","following","requested","failed","uncertain").forEachIndexed { i,k -> report.put(k,c.getLong(i)) }
    }
    val scanned=readableDatabase.rawQuery("SELECT count(*) FROM scan_history WHERE checkedAt>=? AND checkedAt<?",arrayOf(start.toString(),end.toString())).use { it.moveToFirst(); it.getInt(0) }
    report.put("processingVisits",report.getInt("visited")).put("scanned",scanned).put("visited",report.getInt("visited")+scanned)
    return report.toString(2)
}
fun Store.dailyVisited()=JSONObject(dailyReport()).getInt("processingVisits")

fun Store.processingQueueStats(id: Long): Map<String,Int> {
    if(setting("filtered_$id","false")!="true") return queueStats(id)
    return readableDatabase.rawQuery("SELECT queueState,count(*) FROM queue WHERE sessionId=? AND (queueState!='QUEUED' OR accountKey IN (SELECT accountKey FROM scans WHERE eligibility='ELIGIBLE' AND observation='FOLLOW')) GROUP BY queueState",arrayOf(id.toString())).use { c -> buildMap { while(c.moveToNext()) put(c.getString(0),c.getInt(1)) } }
}
