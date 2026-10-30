package ai.blackware.insfollow.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import ai.blackware.insfollow.R
import ai.blackware.insfollow.data.*
import ai.blackware.insfollow.model.*
import ai.blackware.insfollow.queue.Schedule
import ai.blackware.insfollow.queue.WeeklyWindows
import java.time.DayOfWeek
import java.time.LocalDateTime
import ai.blackware.insfollow.web.BrowserSession
import ai.blackware.insfollow.workflow.*
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.concurrent.Executors

class MainActivity : Activity(), BrowserSession.Events, FollowExecutor {
    private val backCallback = android.window.OnBackInvokedCallback {
        safely { if(expanded) setExpanded(false) else finish() }
    }
    private lateinit var store: Store
    private lateinit var scanner: ScanWorkflow
    private lateinit var dailyStats: TextView
    private lateinit var scanStats: TextView
    private var scanFilter: Eligibility?=null
    private val rescanSelected=mutableSetOf<String>()
    private var foreground=false
    private var fileCallback: android.webkit.ValueCallback<Array<Uri>>?=null
    private var browserTab=false
    private val homeCards=mutableListOf<View>()
    private var browserCard: View?=null
    private lateinit var workflow: AssistedWorkflow
    private var browser: BrowserSession?=null
    private lateinit var content: LinearLayout
    private lateinit var folderList: LinearLayout
    private lateinit var resultList: LinearLayout
    private lateinit var frame: FrameLayout
    private lateinit var stats: TextView
    private lateinit var status: TextView
    private lateinit var error: TextView
    private lateinit var counts: TextView
    private lateinit var queueInfo: TextView
    private lateinit var sourceName: TextView
    private lateinit var current: TextView
    private lateinit var expand: Button
    private lateinit var verify: Button
    private lateinit var start: Button
    private lateinit var pause: Button
    private lateinit var stop: Button
    private lateinit var skip: Button
    private lateinit var search: EditText
    private lateinit var filterButton: Button
    private lateinit var configBody: LinearLayout
    private val fields=linkedMapOf<String,EditText>()
    private var results=listOf<Account>()
    private var filter: Status?=null
    private var page=50
    private var busy=false
    private var expanded=false
    private var pendingExport: Pair<String,String>?=null
    private var failures=false
    private var destroyed=false
    private val io=Executors.newSingleThreadExecutor()
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun vertical()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; layoutParams=LinearLayout.LayoutParams(-1,-2) }
    private fun text(parent: LinearLayout, value: String)=TextView(this).apply { text=value; setTextColor(Color.WHITE); textSize=14f; setPadding(0,dp(6),0,dp(6)); parent.addView(this) }
    private fun button(parent: LinearLayout, label: Int, action: () -> Unit)=Button(this).apply {
        setText(label); isAllCaps=false; minHeight=dp(48)
        setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled),intArrayOf()),intArrayOf(Color.WHITE,Color.rgb(100,100,100))))
        background=GradientDrawable().apply { setColor(Color.BLACK); setStroke(dp(1),Color.rgb(40,40,40)); cornerRadius=dp(10).toFloat() }
        layoutParams=LinearLayout.LayoutParams(-1,dp(52)).apply { setMargins(0,dp(3),0,dp(3)) }
        setOnClickListener { if(!busy) safely(action) }; parent.addView(this)
    }
    private fun card(title: Int, open: Boolean=false): LinearLayout {
        val outer=vertical().apply { setPadding(dp(10),dp(6),dp(10),dp(6)); background=GradientDrawable().apply { setColor(Color.BLACK); setStroke(dp(1),Color.rgb(40,40,40)); cornerRadius=dp(10).toFloat() }; layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) } }
        content.addView(outer)
        if(title==R.string.browser) browserCard=outer else homeCards.add(outer)
        val body=vertical(); val header=button(outer,title) { body.visibility=if(body.visibility==View.VISIBLE) View.GONE else View.VISIBLE; if(title==R.string.browser && body.visibility==View.GONE) { body.visibility=View.VISIBLE; setExpanded(false) } }
        body.visibility=if(open) View.VISIBLE else View.GONE; outer.addView(body); header.contentDescription=getString(title)
        return body
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        content=findViewById(R.id.content); stats=findViewById(R.id.stats); status=findViewById(R.id.status); error=findViewById(R.id.error)
        content.setOnApplyWindowInsetsListener { view,insets ->
            val bars=insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            view.setPadding(dp(16)+bars.left,dp(16)+bars.top,dp(16)+bars.right,dp(16)+bars.bottom); insets
        }; content.requestApplyInsets()
        store=Store(this)
        // Opening and recovering the small transactional state precedes any browser activity.
        safely { store.recover() }
        workflow=AssistedWorkflow(store,{browser},{schedule()},this,{ stage,key ->
            if(!destroyed) {
                status.text=stageText(stage); current.text=getString(R.string.current_account,key,stageText(stage))
                if(stage in setOf(Stage.COMPLETED,Stage.UNCERTAIN,Stage.PAUSED)) refresh() else updateControls()
            }
        },{ showReason(it) },enforceFilters=true)
        scanner=ScanWorkflow(store,{browser},{ foreground && schedule()?.allows(LocalDateTime.now())==true },{ schedule()?.interval() ?: 60_000 },{ if(!destroyed) refresh() },{ showReason(it) },{
            refresh()
            if(store.setting("auto_follow","false")=="true") beginFiltered() else error.setText(R.string.scan_complete)
        })
        button(content,R.string.home_tab) { switchTab(false) }
        button(content,R.string.browser_tab) { switchTab(true) }
        buildSource(); buildConfig(); buildScanning(); buildBrowser(); buildControls(); buildResults(); buildExports()
        status.setText(R.string.idle); refresh()
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,backCallback)
    }
    private fun workMode()=store.setting("mode","SCAN").takeIf { it in setOf("DIRECT","SCAN") } ?: "SCAN"
    private fun scanPolicy()=ScanPolicy.entries.firstOrNull { it.name==store.setting("scan_policy","USE_CACHE") } ?: ScanPolicy.USE_CACHE
    private fun toggle(parent: LinearLayout,label: Int,key: String,default: Boolean,action: () -> Unit) {
        parent.addView(CheckBox(this).apply { setText(label); isChecked=store.setting(key,default.toString())=="true"; setOnCheckedChangeListener { _,v -> safely { workflow.pause(); if(::scanner.isInitialized) scanner.pause(); store.settings(mapOf(key to v.toString())); action() } } })
    }
    private fun switchTab(browserVisible: Boolean) {
        browserTab=browserVisible
        homeCards.forEach { it.visibility=if(browserVisible) View.GONE else View.VISIBLE }
        (browserCard as? ViewGroup)?.let { if(it.childCount>1) it.getChildAt(1).visibility=View.VISIBLE }
        setExpanded(browserVisible)
    }
    private fun beginFiltered() {
        if(scanner.running || workflow.work!=null) { error.setText(R.string.operation_active); return }
        store.filteredQueue(); ensureBrowser(); browser?.resume(); workflow.expanded=true; workflow.start()
    }
    private fun scanStateText(state: ScanState)=getString(when(state) {
        ScanState.NOT_SCANNED -> R.string.scan_not_scanned
        ScanState.SCANNING -> R.string.scan_scanning
        ScanState.ELIGIBLE -> R.string.scan_eligible
        ScanState.PRIVATE_ACCOUNT -> R.string.scan_private_account
        ScanState.BELOW_THRESHOLD -> R.string.scan_below_threshold
        ScanState.FOLLOWER_COUNT_UNKNOWN -> R.string.scan_follower_count_unknown
        ScanState.PRIVACY_UNKNOWN -> R.string.scan_privacy_unknown
        ScanState.PROFILE_UNAVAILABLE -> R.string.scan_profile_unavailable
        ScanState.SCAN_ERROR -> R.string.scan_scan_error
    })
    private fun buildScanning() {
        val body=card(R.string.scanning,true)
        val modes=RadioGroup(this)
        listOf("DIRECT" to R.string.mode_direct,"SCAN" to R.string.mode_scan).forEach { (value,label) ->
            modes.addView(RadioButton(this).apply { id=View.generateViewId(); setText(label); isChecked=workMode()==value; setOnCheckedChangeListener { _,checked -> if(checked) safely { workflow.pause(); scanner.pause(); store.settings(mapOf("mode" to value)) } } })
        }; body.addView(modes)
        toggle(body,R.string.auto_follow,"auto_follow",false) {}
        scanStats=text(body,"")
        val policies=ScanPolicy.entries
        val labels=listOf(R.string.cache_use,R.string.cache_missing,R.string.cache_errors,R.string.cache_selected,R.string.cache_all)
        val policy=RadioGroup(this)
        policies.forEachIndexed { i,value -> policy.addView(RadioButton(this).apply { id=View.generateViewId(); setText(labels[i]); isChecked=store.setting("scan_policy","USE_CACHE")==value.name; setOnCheckedChangeListener { _,checked -> if(checked) safely { store.settings(mapOf("scan_policy" to value.name)) } } }) }; body.addView(policy)
        button(body,R.string.new_scan_plan) { workflow.pause(); scanner.pause(); store.readableDatabase.execSQL("UPDATE scan_runs SET state='STOPPED' WHERE state!='COMPLETED'"); refresh() }
        button(body,R.string.start_eligible) { scanner.pause(); beginFiltered() }
        text(body,getString(R.string.scan_help))
    }
    private fun buildSource() {
        val body=card(R.string.source,true)
        button(body,R.string.import_json) { choose(IMPORT,"application/json") }
        sourceName=text(body,""); counts=text(body,"")
        button(body,R.string.select_all) { store.selectAll(true); refresh() }
        button(body,R.string.deselect_all) { store.selectAll(false); refresh() }
        folderList=vertical(); body.addView(folderList)
    }
    private fun buildConfig() {
        configBody=card(R.string.configuration)
        val specs=listOf(Triple("min",R.string.min_interval,"60"),Triple("max",R.string.max_interval,"120"),Triple("start",R.string.start_time,"00:00"),Triple("end",R.string.end_time,"00:00"),Triple("limit",R.string.session_limit,"0"),Triple("failures",R.string.failure_limit,"3"),Triple("daily",R.string.daily_limit,"0"),Triple("break",R.string.session_break,"0"),Triple("followers",R.string.minimum_followers,"3000"))
        specs.forEach { (key,label,default) ->
            val caption=text(configBody,getString(label))
            val input=EditText(this).apply {
                id=View.generateViewId(); setSingleLine(true); setTextColor(Color.WHITE); setText(store.setting(key,default)); contentDescription=getString(label)
                inputType=if(key=="start" || key=="end") android.text.InputType.TYPE_CLASS_DATETIME or android.text.InputType.TYPE_DATETIME_VARIATION_TIME else android.text.InputType.TYPE_CLASS_NUMBER
                imeOptions=EditorInfo.IME_ACTION_DONE
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
                    override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) {}
                    override fun afterTextChanged(s: Editable?) { safely { workflow.pause(); scanner.pause(); store.settings(mapOf(key to s.toString())); if(key=="followers" && s.toString().toLongOrNull() in 0..1_000_000_000_000L) { store.recalculateScans(); refresh() }; if(schedule()==null) this@MainActivity.error.setText(R.string.error_settings) else this@MainActivity.error.text="" } }
                })
            }; caption.labelFor=input.id; fields[key]=input; configBody.addView(input)
        }
        toggle(configBody,R.string.public_only,"public_only",true) { store.recalculateScans(); refresh() }
        toggle(configBody,R.string.skip_private,"skip_private",true) { store.recalculateScans(); refresh() }
        text(configBody,getString(R.string.unknown_review))
        toggle(configBody,R.string.weekly_enabled,"weekly_enabled",false) {}
        val labels=listOf(R.string.monday,R.string.tuesday,R.string.wednesday,R.string.thursday,R.string.friday,R.string.saturday,R.string.sunday)
        DayOfWeek.entries.forEachIndexed { i,day ->
            toggle(configBody,labels[i],"day_${day.value}",true) {}
            val key="weekly_${day.value}"
            val field=EditText(this).apply {
                setTextColor(Color.WHITE); setSingleLine(true); setText(store.setting(key,"00:00-00:00")); contentDescription=getString(labels[i])
                addTextChangedListener(object: TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
                    override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) {}
                    override fun afterTextChanged(s: Editable?) { safely { workflow.pause(); scanner.pause(); store.settings(mapOf(key to s.toString())); if(schedule()==null) this@MainActivity.error.setText(R.string.error_settings) } }
                })
            }; configBody.addView(field)
        }
        text(configBody,getString(R.string.weekly_help))
        text(configBody,getString(R.string.schedule_help))
    }
    private fun schedule(): Schedule? = runCatching {
        val time=DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)
        Schedule(fields.getValue("min").text.toString().toInt(),fields.getValue("max").text.toString().toInt(),LocalTime.parse(fields.getValue("start").text,time),LocalTime.parse(fields.getValue("end").text,time),fields.getValue("limit").text.toString().toInt(),fields.getValue("failures").text.toString().toInt(),
            if(store.setting("weekly_enabled","false")=="true") DayOfWeek.entries.filter { store.setting("day_${it.value}","true")=="true" }.associateWith { WeeklyWindows.parse(store.setting("weekly_${it.value}","00:00-00:00")) } else null,
            fields.getValue("daily").text.toString().toInt(),fields.getValue("break").text.toString().toInt()).also { store.accountFilter() }
    }.getOrNull()
    private fun buildBrowser() {
        // This card stays inline; its WebView child alone collapses, without recreating it.
        val body=card(R.string.browser,true)
        current=text(body,getString(R.string.idle))
        expand=button(body,R.string.expand) { setExpanded(!expanded) }
        button(body,R.string.login) { workflow.pause(); scanner.pause(); ensureBrowser(); setExpanded(true); browser?.login() }
        frame=FrameLayout(this).apply { layoutParams=LinearLayout.LayoutParams(-1,1); visibility=View.VISIBLE }; body.addView(frame)
        verify=button(body,R.string.verify) { workflow.verify() }
        text(body,getString(R.string.browser_help))
        ensureBrowser()
    }
    private fun ensureBrowser() {
        if(browser!=null) return
        browser=BrowserSession(this,this).also { session ->
            session.setGuard { workflow.navigationAllowed() && (!scanner.running || foreground && schedule()?.allows(LocalDateTime.now())==true) }
            frame.addView(session.view,FrameLayout.LayoutParams(-1,-1))
        }
    }
    private fun setExpanded(value: Boolean) {
        expanded=value; workflow.expanded=true
        frame.visibility=View.VISIBLE
        frame.layoutParams=LinearLayout.LayoutParams(-1,if(value) (resources.displayMetrics.heightPixels*0.6).toInt().coerceAtLeast(dp(280)) else 1)
        expand.setText(if(value) R.string.collapse else R.string.expand)
        ensureBrowser(); if(foreground) browser?.resume()
    }
    private fun buildControls() {
        val body=card(R.string.controls,true)
        queueInfo=text(body,""); dailyStats=text(body,"")
        start=button(body,R.string.start_resume) { if(schedule()==null) { error.setText(R.string.error_settings); return@button }; ensureBrowser(); browser?.resume(); workflow.expanded=true
            if(workMode()=="SCAN") { scanner.start(scanPolicy(),rescanSelected) } else { scanner.pause(); (store.session() ?: store.createQueue()).let { store.settings(mapOf("filtered_$it" to "false")) }; workflow.start() } }
        pause=button(body,R.string.pause) { workflow.pause(); scanner.pause(); browser?.pause() }
        stop=button(body,R.string.stop) { workflow.stop(); scanner.stop(); browser?.pause() }
        skip=button(body,R.string.skip) { if(scanner.running) scanner.skip() else workflow.skip() }
        button(body,R.string.shuffle) { workflow.pause(); scanner.pause(); store.session()?.let { store.shuffleRemaining(it) }; refresh() }
    }
    private fun buildResults() {
        val body=card(R.string.results)
        search=EditText(this).apply { setHint(R.string.search); setSingleLine(true); setTextColor(Color.WHITE); addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
            override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { page=50; renderResults() }
            override fun afterTextChanged(s: Editable?) {}
        }) }; body.addView(search)
        filterButton=button(body,R.string.all) { val choices=listOf<Status?>(null)+Status.entries; filter=choices[(choices.indexOf(filter)+1)%choices.size]; page=50; renderResults() }
        button(body,R.string.scan_filter) { val choices=listOf<Eligibility?>(null)+Eligibility.entries; scanFilter=choices[(choices.indexOf(scanFilter)+1)%choices.size]; renderResults() }.apply { tag="scan-filter" }
        resultList=vertical(); body.addView(resultList)
        button(body,R.string.more) { page+=50; renderResults() }
    }
    private fun buildExports() {
        val body=card(R.string.backup_tools)
        text(body,getString(R.string.export_warning))
        listOf(Triple(R.string.export_success,"successful.json",setOf(Status.SUCCESS,Status.ALREADY)),Triple(R.string.export_failed,"failed.json",setOf(Status.FAILURE)),Triple(R.string.export_pending,"pending.json",setOf(Status.REQUESTED)),Triple(R.string.export_unknown,"unknown.json",setOf(Status.UNCERTAIN,Status.UNPROCESSED))).forEach { (label,name,statuses) -> button(body,label) { prepareExport(name) { store.export(statuses) } } }
        listOf(Triple(R.string.export_scans,"scan-results.json","all"),Triple(R.string.export_eligible,"eligible-accounts.json","eligible"),Triple(R.string.export_filtered,"filtered-accounts.json","filtered"),Triple(R.string.export_scan_errors,"scan-errors.json","errors")).forEach { (label,name,kind) -> button(body,label) { prepareExport(name) { store.exportScans(kind) } } }
        button(body,R.string.export_daily) { prepareExport("daily-report.json") { store.dailyReport() } }
        button(body,R.string.export_progress) { prepareExport("progress.json") { store.export(null) } }
        button(body,R.string.export_txt) { prepareExport("insfollow-results.txt","text/plain") { store.accounts().joinToString("\n") { "${it.username.trim()} ${it.status.code}" }+"\n" } }
        val check=CheckBox(this).apply { setText(R.string.include_failures); isChecked=store.setting("annotate_failures","false")=="true"; failures=isChecked; setOnCheckedChangeListener { _,v -> failures=v; store.settings(mapOf("annotate_failures" to v.toString())) } }; body.addView(check)
        button(body,R.string.export_favorites) { prepareExport("insfollow-annotated-favorites.json") { store.annotated(failures) } }
        button(body,R.string.backup) { workflow.pause(); scanner.pause(); prepareExport("insfollow-backup.json") { store.backup() } }
        val confirmation=vertical().apply { visibility=View.GONE }
        button(body,R.string.restore) { workflow.pause(); scanner.pause(); confirmation.visibility=View.VISIBLE }
        body.addView(confirmation); text(confirmation,getString(R.string.restore_warning))
        button(confirmation,R.string.confirm_restore) { choose(RESTORE,"application/json"); confirmation.visibility=View.GONE }
        button(confirmation,R.string.cancel) { confirmation.visibility=View.GONE }
    }
    private fun refresh() {
        if(!::queueInfo.isInitialized) return
        results=store.accounts()
        val (selected,unique)=store.selectedCounts()
        counts.text=getString(R.string.selected_counts,selected,unique)
        val selectedKeys=store.readableDatabase.rawQuery("SELECT DISTINCT m.accountKey FROM memberships m JOIN folders f ON f.id=m.folderId WHERE f.selected=1",null).use { c -> buildSet { while(c.moveToNext()) add(c.getString(0)) } }
        val chosen=results.filter { it.key in selectedKeys }
        stats.text=getString(R.string.statistics,unique,chosen.count { it.status in setOf(Status.SUCCESS,Status.ALREADY) },chosen.count { it.status==Status.REQUESTED },chosen.count { it.status==Status.FAILURE },chosen.count { !it.status.confirmed })
        sourceName.text=store.latestSource().ifEmpty { getString(R.string.no_source) }
        folderList.removeAllViews()
        store.folders().forEach { folder -> folderList.addView(CheckBox(this).apply { text=getString(R.string.folder_row,folder.name,folder.count,folder.summary); isChecked=folder.selected; setOnCheckedChangeListener { _,v -> safely { store.select(folder.id,v); refresh() } } }) }
        val q=store.latestSession()?.let { store.processingQueueStats(it) }.orEmpty()
        val done=q.getOrDefault("DONE",0); val skipped=q.getOrDefault("SKIPPED",0); val remaining=q.getOrDefault("QUEUED",0)+q.getOrDefault("INFLIGHT",0); val total=q.values.sum()
        queueInfo.text=getString(R.string.queue_statistics,total,if(remaining==0) total else done+skipped+1,remaining,done,skipped)
        if(::scanStats.isInitialized) {
            val scans=store.scanResults().filter { it.key in selectedKeys }
            scanStats.text=getString(R.string.scan_statistics,unique,scans.size,scans.count { it.eligibility==Eligibility.ELIGIBLE },scans.count { it.state==ScanState.PRIVATE_ACCOUNT },scans.count { it.state==ScanState.BELOW_THRESHOLD },scans.count { it.eligibility==Eligibility.REVIEW },(unique-scans.size).coerceAtLeast(0))
            if(scanner.running) status.text=getString(R.string.scan_current,scanner.current.orEmpty()) else status.text=stageText(workflow.stage)
        }
        if(::dailyStats.isInitialized) {
            val report=org.json.JSONObject(store.dailyReport())
            dailyStats.text=getString(R.string.daily_statistics,report.getInt("visited"),report.getInt("following"),report.getInt("requested"),report.getInt("failed"),report.getInt("uncertain"))
        }
        updateControls()
        if(::search.isInitialized) renderResults()
    }
    private fun updateControls() {
        if(!::start.isInitialized) return
        start.isEnabled=!workflow.running && !scanner.running && !busy; pause.isEnabled=workflow.running || scanner.running; stop.isEnabled=store.session()!=null || store.scanRun()!=null; skip.isEnabled=workflow.work!=null || scanner.current!=null && scanner.running
        verify.isEnabled=workflow.stage==Stage.AWAITING_USER_ACTION && workflow.running
    }
    private fun renderResults() {
        if(!::resultList.isInitialized) return
        filterButton.text=getString(R.string.filter,filter?.let { statusText(it) } ?: getString(R.string.all))
        content.findViewWithTag<Button>("scan-filter")?.text=getString(R.string.scan_filter_current,getString(when(scanFilter) { Eligibility.ELIGIBLE -> R.string.scan_eligible; Eligibility.FILTERED -> R.string.filtered; Eligibility.REVIEW -> R.string.review; null -> R.string.all }))
        val query=search.text.toString().trim()
        val scans=store.scanResults().associateBy { it.key }
        val rows=results.filter { (filter==null || it.status==filter) && (scanFilter==null || scans[it.key]?.eligibility==scanFilter) && (it.username.contains(query,true) || it.name.contains(query,true)) }.take(page)
        resultList.removeAllViews()
        if(rows.isEmpty()) text(resultList,getString(R.string.empty_results))
        rows.forEach { account ->
            val scan=scans[account.key]
            text(resultList,getString(R.string.scan_record,if(scanner.running && scanner.current==account.key) scanStateText(ScanState.SCANNING) else scan?.let { scanStateText(it.state) } ?: scanStateText(ScanState.NOT_SCANNED),scan?.facts?.count?.raw ?: getString(R.string.count_unknown),scan?.checkedAt?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toString() }.orEmpty()))
            resultList.addView(CheckBox(this).apply { text=getString(R.string.select_rescan,account.username); isChecked=account.key in rescanSelected; setOnCheckedChangeListener { _,checked -> if(checked) rescanSelected.add(account.key) else rescanSelected.remove(account.key) } })
            text(resultList,getString(R.string.record,account.username,account.name,"${account.status.code} · ${statusText(account.status)}",if(account.reason.isEmpty()) getString(R.string.no_reason) else getString(R.string.reason,account.reason)))
            button(resultList,R.string.results) { workflow.pause(); scanner.pause(); store.recheck(account.key); refresh() }.apply { text=getString(R.string.recheck,account.username); isEnabled=workflow.work==null }
        }
    }
    private fun stageText(stage: Stage): String = getString(when(stage) {
        Stage.QUEUED -> R.string.stage_queued; Stage.OPENING_PROFILE -> R.string.stage_opening_profile; Stage.WAITING_FOR_PAGE -> R.string.stage_waiting_for_page; Stage.INSPECTING_PROFILE -> R.string.stage_inspecting_profile; Stage.READY_FOR_ACTION -> R.string.stage_ready_for_action; Stage.AWAITING_USER_ACTION -> R.string.stage_awaiting_user_action; Stage.REFRESHING_PROFILE -> R.string.stage_refreshing_profile; Stage.VERIFYING_RESULT -> R.string.stage_verifying_result; Stage.SAVING_RESULT -> R.string.stage_saving_result; Stage.COMPLETED -> R.string.stage_completed; Stage.FAILED -> R.string.stage_failed; Stage.PAUSED -> R.string.stage_paused; Stage.UNCERTAIN -> R.string.stage_uncertain
    })
    private fun statusText(status: Status)=getString(when(status) { Status.UNPROCESSED -> R.string.status_unprocessed; Status.SUCCESS -> R.string.status_success; Status.ALREADY -> R.string.status_already; Status.REQUESTED -> R.string.status_requested; Status.FAILURE -> R.string.status_failure; Status.UNCERTAIN -> R.string.status_uncertain })
    private fun showReason(reason: String) { error.setText(when(reason) {
        "DAILY_LIMIT" -> R.string.daily_reached; "SESSION_BREAK" -> R.string.break_active; "LOGIN_REQUIRED" -> R.string.login_required; "ACTION_RESTRICTED" -> R.string.restricted; "VERIFICATION_TIMEOUT" -> R.string.timeout; "RENDERER_GONE" -> R.string.renderer_gone; "OUTSIDE_SCHEDULE" -> R.string.outside_schedule; "SESSION_LIMIT" -> R.string.limit_reached; "EXPAND_REQUIRED" -> R.string.expand_required; "OPERATION_ACTIVE" -> R.string.operation_active; "NAVIGATION_BLOCKED" -> R.string.navigation_blocked; "DATABASE_ERROR" -> R.string.error_database; "NO_SOURCE" -> R.string.no_source_error; "INPUT_TOO_LARGE","INVALID_NESTING" -> R.string.error_size; else -> R.string.network_error
    }) }
    private fun safely(action: () -> Unit) { try { action() } catch(e: InputProblem) { showReason(e.reason) } catch(_: android.database.SQLException) { if(::scanner.isInitialized) scanner.pause(); if(::workflow.isInitialized) workflow.storageFailure() else error.setText(R.string.error_database) } catch(e: Exception) { if(e.message in setOf("OPERATION_ACTIVE","NO_SOURCE")) showReason(e.message!!) else error.setText(R.string.error_io) } }
    private fun choose(code: Int,mime: String) { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime),code) }
    private fun prepareExport(name: String,mime: String="application/json",create: () -> String) {
        workflow.pause(); scanner.pause()
        background({ create() }) { value -> pendingExport=name to value; startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE,name),EXPORT) }
    }
    private fun <T> background(task: () -> T,done: (T) -> Unit) {
        if(busy) return
        busy=true; status.setText(R.string.busy)
        io.execute {
            val result=runCatching(task)
            runOnUiThread {
                if(destroyed) return@runOnUiThread
                busy=false
                result.fold({ safely { done(it) } },{ e -> if(e is InputProblem) { if(e.reason in setOf("INPUT_TOO_LARGE","INVALID_NESTING")) showReason(e.reason) else error.setText(R.string.error_input) } else if(e is android.database.SQLException) error.setText(R.string.error_database) else if(e.message=="NO_SOURCE") showReason("NO_SOURCE") else error.setText(R.string.error_io) })
                status.text=stageText(workflow.stage); refresh()
            }
        }
    }
    @Deprecated("System SAF callback")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==WEB_FILE) {
            val uris=if(resultCode==RESULT_OK) data?.clipData?.let { clip -> (0 until clip.itemCount.coerceAtMost(20)).map { clip.getItemAt(it).uri }.toTypedArray() } ?: data?.data?.let { arrayOf(it) } else null
            fileCallback?.onReceiveValue(uris); fileCallback=null; return
        }
        val uri=data?.data
        if(resultCode!=RESULT_OK || uri==null) { pendingExport=null; return }
        when(requestCode) {
            IMPORT -> background({ val raw=read(uri,FavoritesCodec.MAX_BYTES); val name=contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else "" }.orEmpty(); store.import(FavoritesCodec.parse(raw),name) }) { error.setText(R.string.import_ok) }
            RESTORE -> background({ store.restore(read(uri,32*1024*1024)) }) { error.setText(R.string.restore_ok); recreate() }
            EXPORT -> { val value=pendingExport?.second ?: run { error.setText(R.string.error_io); return }; pendingExport=null; background({ contentResolver.openOutputStream(uri,"wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(value) } ?: kotlin.error("OUTPUT_UNAVAILABLE") }) { error.setText(R.string.export_ok) } }
        }
    }
    private fun read(uri: Uri,limit: Int): String = contentResolver.openInputStream(uri)?.use { input ->
        val bytes=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192); var total=0
        while(true) { val count=input.read(buffer); if(count<0) break; total+=count; if(total>limit) throw InputProblem("INPUT_TOO_LARGE"); bytes.write(buffer,0,count) }
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
    } ?: kotlin.error("INPUT_UNAVAILABLE")
    override fun chooseFile(callback: android.webkit.ValueCallback<Array<Uri>>,types: Array<String>,multiple: Boolean): Boolean {
        workflow.pause(); scanner.pause()
        fileCallback?.onReceiveValue(null); fileCallback=callback
        val allowedTypes=types.filter { it.matches(Regex("[a-zA-Z0-9.+*-]+/[a-zA-Z0-9.+*-]+")) }.take(20)
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(allowedTypes.singleOrNull() ?: "*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,multiple)
        if(allowedTypes.size>1) intent.putExtra(Intent.EXTRA_MIME_TYPES,allowedTypes.toTypedArray())
        return try { startActivityForResult(intent,WEB_FILE); true } catch(_: Exception) { fileCallback=null; callback.onReceiveValue(null); false }
    }
    override fun started(url: String) { safely {
        workflow.navigation(url)
        val path=Uri.parse(url).path.orEmpty()
        if(path.contains("challenge") || path.contains("checkpoint") || path.contains("suspended")) scanner.fail("ACTION_RESTRICTED")
        else if(path.startsWith("/accounts/login")) scanner.fail("LOGIN_REQUIRED")
    } }
    override fun finished() { /* A finished navigation alone never commits a result. */ }
    override fun error(reason: String) { safely { workflow.fail(reason); scanner.fail(reason); showReason(reason) } }
    override fun rendererGone() { browser=null; safely { workflow.fail("RENDERER_GONE"); scanner.fail("RENDERER_GONE"); workflow.pause(); showReason("RENDERER_GONE") } }
    override fun requestUserAction(account: String) { setExpanded(true) }
    override fun onResume() { super.onResume(); foreground=true; if(::workflow.isInitialized) workflow.foreground(true); browser?.resume() }
    override fun onPause() { foreground=false; if(::scanner.isInitialized) scanner.pause(); if(::workflow.isInitialized) safely { workflow.foreground(false) }; browser?.pause(); super.onPause() }
    override fun onDestroy() {
        destroyed=true
        fileCallback?.onReceiveValue(null); fileCallback=null
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        if(::workflow.isInitialized) safely { workflow.close() }
        if(::scanner.isInitialized) scanner.pause()
        browser?.destroy(); browser=null
        io.execute { store.close() }; io.shutdown()
        super.onDestroy()
    }
    companion object { private const val IMPORT=10; private const val EXPORT=11; private const val RESTORE=12; private const val WEB_FILE=13 }
}
