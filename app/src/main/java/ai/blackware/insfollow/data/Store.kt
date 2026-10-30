package ai.blackware.insfollow.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteCursor
import android.database.CursorWindow
import ai.blackware.insfollow.model.*
import ai.blackware.insfollow.queue.QueueOrder
import org.json.JSONArray
import org.json.JSONObject

class Store(context: Context, name: String = "insfollow.db") : SQLiteOpenHelper(context, name, null, 2) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE accounts (accountKey TEXT PRIMARY KEY, originalUsername TEXT NOT NULL, originalDisplayName TEXT NOT NULL, status TEXT NOT NULL DEFAULT '—' CHECK(status IN ('—','S','A','R','F','U')), failureReason TEXT NOT NULL DEFAULT '', createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, lastCheckedAt INTEGER, attemptCount INTEGER NOT NULL DEFAULT 0, lastOperationId INTEGER)")
        db.execSQL("CREATE TABLE sources (id INTEGER PRIMARY KEY, hash TEXT UNIQUE NOT NULL, filename TEXT NOT NULL, raw TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE folders (id INTEGER PRIMARY KEY, sourceImportId INTEGER NOT NULL REFERENCES sources(id), ordinal INTEGER NOT NULL, name TEXT NOT NULL, selected INTEGER NOT NULL DEFAULT 0 CHECK(selected IN (0,1)), UNIQUE(sourceImportId,ordinal))")
        db.execSQL("CREATE TABLE memberships (folderId INTEGER NOT NULL REFERENCES folders(id), itemIndex INTEGER NOT NULL, accountKey TEXT NOT NULL REFERENCES accounts(accountKey), PRIMARY KEY(folderId,itemIndex))")
        db.execSQL("CREATE INDEX membership_account ON memberships(accountKey)")
        db.execSQL("CREATE TABLE sessions (sessionId INTEGER PRIMARY KEY, startedAt INTEGER NOT NULL, endedAt INTEGER, selectedFolders TEXT NOT NULL, state TEXT NOT NULL, nextAt INTEGER NOT NULL DEFAULT 0, processedCount INTEGER NOT NULL DEFAULT 0, successCount INTEGER NOT NULL DEFAULT 0, failureCount INTEGER NOT NULL DEFAULT 0, pendingCount INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE queue (sessionId INTEGER NOT NULL REFERENCES sessions(sessionId), accountKey TEXT NOT NULL REFERENCES accounts(accountKey), queuePosition INTEGER NOT NULL, queueState TEXT NOT NULL CHECK(queueState IN ('QUEUED','INFLIGHT','DONE','SKIPPED')), PRIMARY KEY(sessionId,accountKey), UNIQUE(sessionId,queuePosition))")
        db.execSQL("CREATE TABLE operations (operationId INTEGER PRIMARY KEY, sessionId INTEGER NOT NULL REFERENCES sessions(sessionId), accountKey TEXT NOT NULL REFERENCES accounts(accountKey), startedAt INTEGER NOT NULL, finishedAt INTEGER, previousStatus TEXT NOT NULL, resultingStatus TEXT, verificationResult TEXT NOT NULL DEFAULT '', errorCode TEXT NOT NULL DEFAULT '', stage TEXT NOT NULL, afterAction INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE UNIQUE INDEX one_open_operation ON operations(sessionId) WHERE finishedAt IS NULL")
        db.execSQL("CREATE TABLE settings (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE provenance (hash TEXT PRIMARY KEY, sourceId INTEGER NOT NULL REFERENCES sources(id))")
        ScanSchema.create(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { if(oldVersion<2) ScanSchema.create(db) }
    private fun now() = System.currentTimeMillis()
    fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try { val value = block(db); db.setTransactionSuccessful(); return value } finally { db.endTransaction() }
    }
    private fun values(vararg pairs: Pair<String, Any?>) = ContentValues().apply {
        pairs.forEach { (k,v) -> when(v) { null -> putNull(k); is Long -> put(k,v); is Int -> put(k,v); else -> put(k,v.toString()) } }
    }
    private fun largeQuery(db: SQLiteDatabase, sql: String, args: Array<String>? = null) = (db.rawQuery(sql,args) as SQLiteCursor).apply { window=CursorWindow("InsFollow source",40L*1024*1024) }
    private fun scalar(sql: String, args: Array<String> = emptyArray()): Long = readableDatabase.rawQuery(sql,args).use { if(it.moveToFirst()) it.getLong(0) else 0 }
    fun setting(key: String, default: String) = readableDatabase.rawQuery("SELECT value FROM settings WHERE name=?",arrayOf(key)).use { if(it.moveToFirst()) it.getString(0) else default }
    fun settings(settings: Map<String,String>) = transaction { db -> settings.forEach { (k,v) -> db.insertWithOnConflict("settings",null,values("name" to k,"value" to v),SQLiteDatabase.CONFLICT_REPLACE) }; Unit }
    fun import(source: Favorites, filename: String): Long = transaction { db ->
        val known = db.rawQuery("SELECT sourceId FROM provenance WHERE hash=? UNION ALL SELECT id FROM sources WHERE hash=? LIMIT 1",arrayOf(source.hash,source.hash)).use { if(it.moveToFirst()) it.getLong(0) else null }
        if (known != null) return@transaction known
        val id = db.insertOrThrow("sources",null,values("hash" to source.hash,"filename" to filename.take(512),"raw" to source.raw,"createdAt" to now()))
        source.folders.forEachIndexed { index, folder ->
            val folderId = db.insertOrThrow("folders",null,values("sourceImportId" to id,"ordinal" to index,"name" to folder.name))
            folder.items.forEachIndexed { itemIndex, item ->
                db.insertWithOnConflict("accounts",null,values("accountKey" to item.key,"originalUsername" to item.username,"originalDisplayName" to item.name,"createdAt" to now(),"updatedAt" to now()),SQLiteDatabase.CONFLICT_IGNORE)
                db.insertOrThrow("memberships",null,values("folderId" to folderId,"itemIndex" to itemIndex,"accountKey" to item.key))
            }
        }
        if(scalar("SELECT count(*) FROM accounts")>FavoritesCodec.MAX_ITEMS) throw InputProblem("TOO_MANY_ACCOUNTS")
        id
    }
    fun folders(): List<Folder> = readableDatabase.rawQuery("SELECT f.id,f.name,f.selected,count(m.accountKey),group_concat(a.status,' ') FROM folders f LEFT JOIN memberships m ON m.folderId=f.id LEFT JOIN accounts a ON a.accountKey=m.accountKey GROUP BY f.id ORDER BY f.sourceImportId,f.ordinal",null).use { c ->
        buildList { while(c.moveToNext()) { val codes = (c.getString(4) ?: "").split(' ').filter { it.isNotEmpty() }.groupingBy { it }.eachCount(); add(Folder(c.getLong(0),c.getString(1),c.getInt(3),c.getInt(2)==1,codes.entries.joinToString(" · ") { "${it.key}: ${it.value}" })) } }
    }
    fun select(id: Long, selected: Boolean) { writableDatabase.update("folders",values("selected" to if(selected) 1 else 0),"id=?",arrayOf(id.toString())) }
    fun selectAll(selected: Boolean) { writableDatabase.update("folders",values("selected" to if(selected) 1 else 0),null,null) }
    fun selectedCounts() = scalar("SELECT count(*) FROM memberships m JOIN folders f ON f.id=m.folderId WHERE f.selected=1").toInt() to scalar("SELECT count(DISTINCT accountKey) FROM memberships m JOIN folders f ON f.id=m.folderId WHERE f.selected=1").toInt()
    fun accounts(): List<Account> = readableDatabase.rawQuery("SELECT accountKey,originalUsername,originalDisplayName,status,failureReason,attemptCount FROM accounts ORDER BY accountKey",null).use { c -> buildList { while(c.moveToNext()) add(Account(c.getString(0),c.getString(1),c.getString(2),Status.from(c.getString(3)),c.getString(4),c.getInt(5))) } }
    fun latestSource() = readableDatabase.rawQuery("SELECT filename FROM sources ORDER BY id DESC LIMIT 1",null).use { if(it.moveToFirst()) it.getString(0) else "" }
    fun session(): Long? = readableDatabase.rawQuery("SELECT sessionId FROM sessions WHERE state!='COMPLETED' ORDER BY sessionId DESC LIMIT 1",null).use { if(it.moveToFirst()) it.getLong(0) else null }
    fun state(id: Long) = readableDatabase.rawQuery("SELECT state FROM sessions WHERE sessionId=?",arrayOf(id.toString())).use { if(it.moveToFirst()) it.getString(0) else "COMPLETED" }
    fun createQueue(): Long = transaction { db ->
        session()?.let { return@transaction it }
        val keys = db.rawQuery("SELECT DISTINCT a.accountKey,a.status FROM accounts a JOIN memberships m ON a.accountKey=m.accountKey JOIN folders f ON f.id=m.folderId WHERE f.selected=1",null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0) to Status.from(c.getString(1)).confirmed) } }
        val order = QueueOrder.eligible(keys)
        val selected = JSONArray(folders().filter { it.selected }.map { it.id }).toString()
        val id = db.insertOrThrow("sessions",null,values("startedAt" to now(),"selectedFolders" to selected,"state" to if(order.isEmpty()) "COMPLETED" else "PAUSED"))
        order.forEachIndexed { pos,key -> db.insertOrThrow("queue",null,values("sessionId" to id,"accountKey" to key,"queuePosition" to pos,"queueState" to "QUEUED")) }; id
    }
    fun latestSession(): Long? = readableDatabase.rawQuery("SELECT sessionId FROM sessions ORDER BY sessionId DESC LIMIT 1",null).use { if(it.moveToFirst()) it.getLong(0) else null }
    fun queueStats(id: Long): Map<String,Int> = readableDatabase.rawQuery("SELECT queueState,count(*) FROM queue WHERE sessionId=? GROUP BY queueState",arrayOf(id.toString())).use { c -> buildMap { while(c.moveToNext()) put(c.getString(0),c.getInt(1)) } }
    fun setSession(id: Long, state: String) { writableDatabase.update("sessions",values("state" to state,"endedAt" to if(state=="COMPLETED" || state=="STOPPED") now() else null),"sessionId=?",arrayOf(id.toString())) }
    fun nextAt(id: Long) = scalar("SELECT nextAt FROM sessions WHERE sessionId=?",arrayOf(id.toString()))
    fun setNext(id: Long, timestamp: Long) { writableDatabase.update("sessions",values("nextAt" to timestamp),"sessionId=?",arrayOf(id.toString())) }
    fun countProcessed(id: Long) = scalar("SELECT processedCount FROM sessions WHERE sessionId=?",arrayOf(id.toString())).toInt()
    fun current(): Work? = readableDatabase.rawQuery("SELECT accountKey,operationId,sessionId,previousStatus FROM operations WHERE finishedAt IS NULL ORDER BY operationId DESC LIMIT 1",null).use { if(it.moveToFirst()) Work(it.getString(0),it.getLong(1),it.getLong(2),Status.from(it.getString(3))) else null }
    fun acquire(id: Long, eligibleOnly: Boolean = false): Work? = transaction { db ->
        current()?.let { return@transaction it }
        val key = db.rawQuery("SELECT accountKey FROM queue WHERE sessionId=? AND queueState='QUEUED'" + (if(eligibleOnly) " AND accountKey IN (SELECT accountKey FROM scans WHERE eligibility='ELIGIBLE' AND observation='FOLLOW') AND accountKey IN (SELECT accountKey FROM eligible_queue WHERE sessionId=queue.sessionId) AND accountKey IN (SELECT accountKey FROM accounts WHERE status NOT IN ('S','A','R','F'))" else "") + " ORDER BY queuePosition LIMIT 1",arrayOf(id.toString())).use { if(it.moveToFirst()) it.getString(0) else null } ?: run { setSession(id,"COMPLETED"); return@transaction null }
        val previous = db.rawQuery("SELECT status FROM accounts WHERE accountKey=?",arrayOf(key)).use { it.moveToFirst(); Status.from(it.getString(0)) }
        val operation = db.insertOrThrow("operations",null,values("sessionId" to id,"accountKey" to key,"startedAt" to now(),"previousStatus" to previous.code,"stage" to Stage.OPENING_PROFILE.name))
        db.execSQL("UPDATE accounts SET attemptCount=attemptCount+1,lastOperationId=? WHERE accountKey=?",arrayOf<Any>(operation,key))
        db.update("queue",values("queueState" to "INFLIGHT"),"sessionId=? AND accountKey=?",arrayOf(id.toString(),key))
        Work(key,operation,id,previous)
    }
    fun stage(work: Work, stage: Stage, afterAction: Boolean = false) { writableDatabase.update("operations",values("stage" to stage.name,"afterAction" to if(afterAction) 1 else 0),"operationId=? AND finishedAt IS NULL",arrayOf(work.operation.toString())) }
    fun finish(work: Work, verdict: Verdict, observation: String, skipped: Boolean = false) = transaction { db ->
        val live = db.rawQuery("SELECT previousStatus FROM operations WHERE operationId=? AND finishedAt IS NULL",arrayOf(work.operation.toString())).use { if(it.moveToFirst()) Status.from(it.getString(0)) else null } ?: return@transaction
        val status = Results.retained(live,verdict.status)
        val stamp = now()
        db.update("operations",values("finishedAt" to stamp,"resultingStatus" to verdict.status.code,"verificationResult" to observation,"errorCode" to verdict.reason,"stage" to if(verdict.status == Status.UNCERTAIN) Stage.UNCERTAIN.name else Stage.COMPLETED.name),"operationId=?",arrayOf(work.operation.toString()))
        val accountValues = values("status" to status.code,"updatedAt" to stamp,"lastOperationId" to work.operation)
        if (status == verdict.status) accountValues.put("failureReason",verdict.reason)
        if (observation in setOf("FOLLOWING","REQUESTED","UNAVAILABLE","FOLLOW")) accountValues.put("lastCheckedAt",stamp)
        db.update("accounts",accountValues,"accountKey=?",arrayOf(work.key))
        db.update("queue",values("queueState" to if(skipped) "SKIPPED" else "DONE"),"sessionId=? AND accountKey=?",arrayOf(work.session.toString(),work.key))
        db.execSQL("UPDATE sessions SET processedCount=processedCount+1,successCount=successCount+?,failureCount=failureCount+?,pendingCount=pendingCount+? WHERE sessionId=?",arrayOf(if(verdict.status in setOf(Status.SUCCESS,Status.ALREADY)) 1 else 0,if(verdict.status==Status.FAILURE) 1 else 0,if(verdict.status==Status.REQUESTED) 1 else 0,work.session))
    }
    fun recover() = transaction { db ->
        val interrupted = db.rawQuery("SELECT accountKey,operationId,sessionId,previousStatus FROM operations WHERE finishedAt IS NULL",null).use { c -> buildList { while(c.moveToNext()) add(Work(c.getString(0),c.getLong(1),c.getLong(2),Status.from(c.getString(3)))) } }
        interrupted.forEach { finish(it,Verdict(Status.UNCERTAIN,"INTERRUPTED_VERIFICATION"),"INTERRUPTED") }
        db.execSQL("UPDATE sessions SET state='PAUSED' WHERE state='RUNNING'")
        db.execSQL("UPDATE scan_queue SET state='QUEUED' WHERE state='SCANNING'")
    }
    fun recheck(key: String): Long = transaction { db ->
        require(current() == null) { "OPERATION_ACTIVE" }
        val id = session() ?: createQueue()
        val position = scalar("SELECT coalesce(max(queuePosition),-1)+1 FROM queue WHERE sessionId=?",arrayOf(id.toString()))
        db.delete("queue","sessionId=? AND accountKey=?",arrayOf(id.toString(),key))
        db.insertOrThrow("queue",null,values("sessionId" to id,"accountKey" to key,"queuePosition" to position,"queueState" to "QUEUED"))
        setSession(id,"PAUSED"); id
    }
    fun shuffleRemaining(id: Long) = transaction { db ->
        require(current() == null) { "OPERATION_ACTIVE" }
        val keys = db.rawQuery("SELECT accountKey FROM queue WHERE sessionId=? AND queueState='QUEUED'",arrayOf(id.toString())).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }.shuffled()
        val offset = scalar("SELECT coalesce(max(queuePosition),-1)+1 FROM queue WHERE sessionId=?",arrayOf(id.toString()))
        keys.forEachIndexed { i,key -> db.update("queue",values("queuePosition" to offset+i),"sessionId=? AND accountKey=?",arrayOf(id.toString(),key)) }
    }
    fun annotated(includeFailures: Boolean): String = transaction { db ->
        val source = largeQuery(db,"SELECT id,raw FROM sources ORDER BY id DESC LIMIT 1").use { require(it.moveToFirst()) { "NO_SOURCE" }; it.getLong(0) to it.getString(1) }
        val output = FavoritesCodec.annotate(source.second,accounts().associate { it.key to it.status },includeFailures)
        db.insertWithOnConflict("provenance",null,values("hash" to FavoritesCodec.digest(output),"sourceId" to source.first),SQLiteDatabase.CONFLICT_REPLACE); output
    }
    private val tables = listOf("accounts","sources","folders","memberships","sessions","queue","operations","settings","provenance") + ScanSchema.tables
    fun export(statuses: Set<Status>?): String = transaction { db ->
        val rows = JSONArray()
        db.rawQuery("SELECT * FROM accounts ORDER BY accountKey",null).use { c -> while(c.moveToNext()) {
            if (statuses == null || Status.from(c.getString(c.getColumnIndexOrThrow("status"))) in statuses) {
                val row = JSONObject(); for(i in 0 until c.columnCount) row.put(c.getColumnName(i),if(c.isNull(i)) JSONObject.NULL else if(c.getType(i)==android.database.Cursor.FIELD_TYPE_INTEGER) c.getLong(i) else c.getString(i)); rows.put(row)
            }
        } }
        val output = JSONObject().put("version","0.1").put("exportedAt",now()).put("accounts",rows)
        if(statuses==null) { val full=JSONObject(backup()); listOf("operations","sessions","queue").forEach { output.put(it,full.getJSONArray(it)) } }
        output.toString(2)
    }
    fun backup(): String = transaction { db ->
        val json = JSONObject().put("format","insfollow-local").put("schema",2).put("createdAt",now())
        tables.forEach { table -> val rows = JSONArray(); largeQuery(db,"SELECT * FROM $table").use { c -> while(c.moveToNext()) { val row = JSONObject(); for(i in 0 until c.columnCount) row.put(c.getColumnName(i),if(c.isNull(i)) JSONObject.NULL else if(c.getType(i)==android.database.Cursor.FIELD_TYPE_INTEGER) c.getLong(i) else c.getString(i)); rows.put(row) } }; json.put(table,rows) }; json.toString().also { FavoritesCodec.bounded(it,32*1024*1024) }
    }
    fun restore(raw: String) {
        FavoritesCodec.bounded(raw,32*1024*1024); JsonText.validate(raw)
        val json = JSONObject(raw)
        require(json.getString("format")=="insfollow-local" && json.getInt("schema") in 1..2) { "INVALID_BACKUP" }
        val accounts = json.getJSONArray("accounts")
        require(accounts.length() <= FavoritesCodec.MAX_ITEMS) { "INPUT_TOO_LARGE" }
        for(i in 0 until accounts.length()) { val row=accounts.getJSONObject(i); require(Identity.normalize(row.getString("originalUsername"))==row.getString("accountKey")); Status.from(row.getString("status")) }
        val sources=json.getJSONArray("sources")
        for(i in 0 until sources.length()) { val row=sources.getJSONObject(i); val source=FavoritesCodec.parse(row.getString("raw")); require(source.hash==row.getString("hash")) }
        transaction { db ->
            db.execSQL("PRAGMA defer_foreign_keys=ON")
            tables.reversed().forEach { db.delete(it,null,null) }
            tables.forEach { table ->
                val columns=db.rawQuery("SELECT * FROM $table LIMIT 0",null).use { it.columnNames.toSet() }
                val rows=if(json.getInt("schema")==1 && table in ScanSchema.tables) JSONArray() else json.getJSONArray(table); require(rows.length() <= 500_000)
                for(i in 0 until rows.length()) {
                    val row=rows.getJSONObject(i); require(row.keys().asSequence().toSet()==columns) { "INVALID_BACKUP" }
                    val vals=ContentValues(); columns.forEach { column -> val value=row.get(column); when { value==JSONObject.NULL -> vals.putNull(column); value is Number -> vals.put(column,value.toLong()); value is String -> vals.put(column,value); else -> error("INVALID_BACKUP") } }
                    db.insertOrThrow(table,null,vals)
                }
            }
            db.rawQuery("PRAGMA foreign_key_check",null).use { require(!it.moveToFirst()) { "INVALID_BACKUP" } }
            db.rawQuery("SELECT accountKey FROM queue WHERE queueState='INFLIGHT' AND NOT EXISTS(SELECT 1 FROM operations o WHERE o.accountKey=queue.accountKey AND o.sessionId=queue.sessionId AND o.finishedAt IS NULL)",null).use { require(!it.moveToFirst()) { "INVALID_BACKUP" } }
            scanResults().forEach { row ->
                require(row.checkedAt>=0)
                row.facts.count?.let { require(it.lower>=0 && it.upper>=it.lower && it.upper<=1_000_000_000_000L) }
            }
            recover()
        }
    }
}
