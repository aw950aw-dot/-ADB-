package com.draco.ladb.views

import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.core.content.edit
import com.draco.ladb.R
import com.draco.ladb.utils.ApiKeyVault
import com.draco.ladb.utils.AiProviderClient
import com.draco.ladb.utils.UiTheme
import com.draco.ladb.viewmodels.MainActivityViewModel
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStreamWriter

class MainActivity : AppCompatActivity() {
    private val vm: MainActivityViewModel by viewModels()
    private val palette get() = UiTheme.palette(this)
    private val blue get() = palette.accent
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var connectionSpinner: ProgressBar
    private lateinit var output: TextView
    private lateinit var outputScroll: ScrollView
    private var pendingPresetCommand: String? = null
    private lateinit var command: EditText
    private lateinit var aiRequest: EditText
    private val apkPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) installSelectedApks(uris)
    }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun panel(): LinearLayout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(14),dp(16),dp(14)); background=GradientDrawable().apply { setColor(palette.surface); cornerRadius=dp(18).toFloat(); setStroke(dp(1),palette.outline) } }
    private fun label(s:String,size:Float=15f):TextView=TextView(this).apply { text=s; textSize=size; setTextColor(palette.text); gravity=Gravity.RIGHT }
    private fun button(s:String, action:()->Unit)=Button(this).apply { text=s; isAllCaps=false; setOnClickListener { action() } }
    private fun addSpace(h:Int=10){ root.addView(Space(this).apply { minimumHeight=dp(h) }) }
    override fun onCreate(savedInstanceState:Bundle?){ UiTheme.applySavedMode(this); super.onCreate(savedInstanceState)
        window.statusBarColor=palette.statusBar; window.navigationBarColor=palette.navigationBar; window.decorView.systemUiVisibility=if(palette.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(22)); layoutDirection=View.LAYOUT_DIRECTION_RTL; setBackgroundColor(palette.background) }
        root.addView(LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            layoutDirection=View.LAYOUT_DIRECTION_RTL
            gravity=Gravity.CENTER_VERTICAL
            addView(settingsGearButton(),
                LinearLayout.LayoutParams(dp(48),dp(48)).apply { setMargins(0,0,dp(8),0) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.VERTICAL
                addView(label("ADB חכם",27f).apply { setTextColor(blue); typeface=Typeface.DEFAULT_BOLD })
                addView(label("כלי ניהול Android בעברית",13f))
            },LinearLayout.LayoutParams(0,-2,1f))
        })
        addSpace()
        root.addView(panel().apply {
            addView(label("מצב החיבור",16f).apply { typeface=Typeface.DEFAULT_BOLD })
            addView(LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.HORIZONTAL
                gravity=Gravity.CENTER_VERTICAL
                layoutDirection=View.LAYOUT_DIRECTION_RTL
                connectionSpinner=ProgressBar(this@MainActivity).apply { isIndeterminate=true }
                addView(connectionSpinner,LinearLayout.LayoutParams(dp(28),dp(28)).apply { marginEnd=dp(8) })
                status=label("מתחבר ל־ADB…",14f)
                addView(status,LinearLayout.LayoutParams(0,-2,1f))
            })
            addView(button("התחברות / שיוך מחדש"){ showPairDialog() })
            addView(button("פקודות מוכנות"){ startActivity(Intent(this@MainActivity,PresetsActivity::class.java)) })
        })
        addSpace()
        root.addView(panel().apply {
            addView(label("ניהול אפליקציות",18f).apply { setTextColor(blue); typeface=Typeface.DEFAULT_BOLD })
            val rows = listOf<Pair<String, () -> Unit>>(
                "הסרת אפליקציות" to { openAppManager("remove") },
                "השבתת אפליקציות מערכת" to { openAppManager("disable") },
                "החזרת אפליקציות מושבתות" to { openAppManager("enable") },
                "התקנת קובצי APK" to { apkPicker.launch(arrayOf("application/vnd.android.package-archive","application/octet-stream")) }
            )
            rows.chunked(2).forEachIndexed { rowIndex, pair ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation=LinearLayout.HORIZONTAL
                    pair.forEachIndexed { index, entry ->
                        val color = listOf(Color.rgb(28,125,205),Color.rgb(20,150,135),Color.rgb(109,89,190),Color.rgb(210,126,37))[(rowIndex*2+index)%4]
                        addView(tile(entry.first,color,entry.second),
                            LinearLayout.LayoutParams(0,dp(74),1f).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) })
                    }
                    if(pair.size==1) addView(Space(this@MainActivity),LinearLayout.LayoutParams(0,dp(78),1f))
                })
            }
            addView(label("הסרה של אפליקציות מערכת היא למשתמש הנוכחי בלבד; לא ניתן למחוק את קובץ המערכת עצמו.",11f))
        })
        addSpace()
        root.addView(label("פעולות מהירות",20f).apply { setTextColor(blue); typeface=Typeface.DEFAULT_BOLD })
        val actions=listOf(
            "פרטי המכשיר" to "getprop | grep -E 'ro.product.model|ro.build.version.release|ro.build.version.sdk'",
            "רשימת אפליקציות" to "pm list packages -3",
            "נפח אחסון" to "df -h",
            "מצב סוללה" to "dumpsys battery",
            "זיכרון RAM" to "cat /proc/meminfo",
            "כתובת IP" to "ip addr",
            "רזולוציית מסך" to "wm size",
            "ניקוי זיכרון מטמון" to "pm trim-caches 1G"
        )
        actions.chunked(2).forEachIndexed { rowIndex, pair ->
            root.addView(LinearLayout(this).apply {
                orientation=LinearLayout.HORIZONTAL
                pair.forEachIndexed { index, (title,cmd) ->
                    val color=when((rowIndex*2+index)%4) {
                        0 -> Color.rgb(28,125,205)
                        1 -> Color.rgb(20,150,135)
                        2 -> Color.rgb(109,89,190)
                        else -> Color.rgb(210,126,37)
                    }
                    addView(tile(title,color){ runCommand(cmd) },LinearLayout.LayoutParams(0,dp(82),1f).apply { setMargins(dp(4),dp(4),dp(4),dp(4)) })
                }
                if(pair.size==1) addView(Space(this@MainActivity),LinearLayout.LayoutParams(0,dp(82),1f))
            })
        }
        addSpace()
        root.addView(panel().apply {
            addView(label("סוכן AI – כתוב מה תרצה לבצע",18f).apply { setTextColor(blue); typeface=Typeface.DEFAULT_BOLD })
            aiRequest=EditText(this@MainActivity).apply { hint="לדוגמה: הצג את כל האפליקציות שהתקנתי"; minLines=2; gravity=Gravity.RIGHT or Gravity.TOP }
            addView(aiRequest)
            addView(button("הכן פקודת ADB"){ askAi() })
            addView(label("הפקודה תוצג לאישור לפני ביצוע. המפתח נשמר מוצפן במכשיר.",12f))
        })
        addSpace()
        root.addView(panel().apply {
            addView(label("פקודת ADB ידנית",18f).apply { setTextColor(blue); typeface=Typeface.DEFAULT_BOLD })
            command=EditText(this@MainActivity).apply { hint="הקלד פקודה…"; setSingleLine(true); textDirection=View.TEXT_DIRECTION_LTR }
            addView(command)
            addView(button("בצע פקודה"){ runCommand(command.text.toString()) })
        })
        addSpace()
        root.addView(panel().apply {
            addView(label("פלט ADB",19f).apply { setTextColor(blue); typeface=Typeface.DEFAULT_BOLD })
            addView(label("אפשר לגלול לאורך כל התוצאה ולבחור טקסט להעתקה.",12f))
            output=label("הפלט יופיע כאן",12f).apply {
                setTextIsSelectable(true)
                textDirection=View.TEXT_DIRECTION_LTR
                gravity=Gravity.LEFT or Gravity.TOP
                typeface=Typeface.MONOSPACE
                setTextColor(palette.text)
                setPadding(dp(14),dp(14),dp(14),dp(14))
                setLineSpacing(dp(3).toFloat(),1f)
                background=GradientDrawable().apply { setColor(palette.surfaceAlt); cornerRadius=dp(14).toFloat() }
            }
            outputScroll=ScrollView(this@MainActivity).apply {
                isFillViewport=false
                isSmoothScrollingEnabled=true
                isNestedScrollingEnabled=true
                isVerticalScrollBarEnabled=true
                addView(output,android.widget.FrameLayout.LayoutParams(-1,-2))
            }
            addView(outputScroll,LinearLayout.LayoutParams(-1,dp(300)))
            addView(button("הסבר את הפלט בעזרת AI"){ explainOutput() })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                gravity = Gravity.CENTER_VERTICAL
                addView(footerAction("נקה פלט") { vm.clearOutputText() },
                    LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
                addView(footerAction("היסטוריית פקודות") { showHistory() },
                    LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
            })
        })
        setContentView(ScrollView(this).apply { isFillViewport=true; addView(root) })
        vm.outputText.observe(this){ value ->
            val wasAtBottom = outputScroll.childCount == 0 ||
                outputScroll.scrollY + outputScroll.height >= outputScroll.getChildAt(0).height - dp(48)
            output.text=value.ifBlank{"ממתין לפלט…"}
            if(wasAtBottom) outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
        }
        vm.adb.running.observe(this){ ready->
            status.text=if(ready==true)"מחובר ומוכן לפקודות" else "מתחבר ל־ADB…"
            status.setTextColor(if(ready==true)Color.rgb(20,140,80) else Color.rgb(190,90,35))
            connectionSpinner.visibility=if(ready==true)View.GONE else View.VISIBLE
            if(ready==true) {
                val queued=pendingPresetCommand
                if(!queued.isNullOrBlank()) {
                    pendingPresetCommand=null
                    runCommand(queued)
                }
            }
        }
        if (!vm.needsToPair()) vm.startADBServer()
    }
    override fun onResume(){
        super.onResume()
        val prefs=getSharedPreferences("quick_command",0)
        val pending=prefs.getString("command",null)
        if(!pending.isNullOrBlank()) {
            prefs.edit().remove("command").apply()
            if(vm.adb.running.value==true) runCommand(pending) else pendingPresetCommand=pending
        }
        if (!vm.needsToPair() && vm.adb.running.value != true) {
            vm.startADBServer()
        }
    }
    private fun settingsGearButton(): TextView = TextView(this).apply {
        text = "⚙"
        textSize = 23f
        gravity = Gravity.CENTER
        setTextColor(blue)
        background = GradientDrawable().apply {
            setColor(palette.surfaceAlt)
            cornerRadius = dp(15).toFloat()
            setStroke(dp(1), palette.outline)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
    }

    private fun footerAction(title: String, action: () -> Unit): TextView = TextView(this).apply {
        text = title
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
        setTextColor(palette.text)
        setPadding(dp(2), dp(4), dp(2), dp(4))
        background = GradientDrawable().apply {
            setColor(palette.surfaceAlt)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), palette.outline)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun tile(title:String, color:Int, action:()->Unit):TextView = TextView(this).apply {
        text=title
        textSize=14f
        maxLines=2
        includeFontPadding=false
        typeface=Typeface.DEFAULT_BOLD
        gravity=Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(6),dp(6),dp(6),dp(6))
        background=GradientDrawable().apply {
            setColor(color)
            cornerRadius=dp(16).toFloat()
        }
        elevation=dp(2).toFloat()
        isClickable=true
        isFocusable=true
        setOnClickListener { action() }
    }

    private fun installSelectedApks(uris:List<android.net.Uri>) {
        if(vm.adb.running.value!=true) {
            Toast.makeText(this,"יש להתחבר ל־ADB לפני התקנת קובצי APK",Toast.LENGTH_LONG).show()
            return
        }
        val progress=ProgressDialog.show(this,"התקנת אפליקציות","מעתיק ומתקין "+uris.size+" קבצים…",true,false)
        lifecycleScope.launch {
            val report=withContext(Dispatchers.IO) {
                val results=mutableListOf<String>()
                uris.forEachIndexed { index, uri ->
                    var temp:java.io.File?=null
                    try {
                        val name=contentResolver.query(uri,null,null,null,null)?.use { cursor ->
                            val col=cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if(col>=0 && cursor.moveToFirst()) cursor.getString(col) else null
                        } ?: "selected_"+(index+1)+".apk"
                        if(!name.endsWith(".apk",true)) throw IllegalArgumentException("הקובץ אינו APK: "+name)
                        val targetFile=java.io.File.createTempFile("adb-install-"+index+"-",".apk",cacheDir)
                        temp=targetFile
                        contentResolver.openInputStream(uri)?.use { input ->
                            targetFile.outputStream().use { output -> input.copyTo(output) }
                        } ?: throw IllegalStateException("לא ניתן לקרוא את הקובץ")
                        val output=vm.adb.installApk(targetFile)
                        results.add("✓ "+name+": "+output)
                    } catch(e:Exception) {
                        results.add("✗ "+uri.lastPathSegment+": "+(e.message ?: "שגיאה"))
                    } finally {
                        temp?.delete()
                    }
                }
                results.joinToString("\\n")
            }
            progress.dismiss()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("תוצאות התקנת APK")
                .setMessage(report)
                .setPositiveButton("סגור",null)
                .show()
        }
    }

    private fun openAppManager(mode:String) {
        startActivity(Intent(this,AppManagerActivity::class.java).putExtra("mode",mode))
    }
    private fun showHistory(){
        val items=getSharedPreferences("command_history",0).getString("items","")!!.split("\n").filter{it.isNotBlank()}
        if(items.isEmpty()){ Toast.makeText(this,"עדיין אין פקודות בהיסטוריה",Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("פקודות אחרונות").setItems(items.toTypedArray()){_,which-> command.setText(items[which]); command.setSelection(command.text.length) }.setNegativeButton("סגור",null).show()
    }
    private fun runCommand(cmd:String){ if(cmd.isBlank())return; if(vm.adb.running.value!=true){ Toast.makeText(this,"תחילה יש להתחבר ל־ADB",Toast.LENGTH_SHORT).show(); return }; saveHistory(cmd); vm.adb.sendToShellProcess(cmd); command.setText("") }
    private fun saveHistory(cmd:String){ val p=getSharedPreferences("command_history",0); val old=p.getString("items","")!!.split("\n").filter{it.isNotBlank()}; p.edit().putString("items",(listOf(cmd)+old).distinct().take(80).joinToString("\n")).apply() }
    private fun resolveAppCandidates(request: String): String {
        return try {
            val normalized = request.lowercase(java.util.Locale.ROOT)
            packageManager.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                .mapNotNull { info ->
                    val appLabel = try { packageManager.getApplicationLabel(info).toString() } catch (_: Exception) { info.packageName }
                    val label = appLabel.trim()
                    if (label.length < 2) null
                    else {
                        val name = label.lowercase(java.util.Locale.ROOT)
                        if (normalized.contains(name) || normalized.contains(info.packageName.lowercase(java.util.Locale.ROOT))) {
                            Pair(label, info.packageName)
                        } else null
                    }
                }
                .distinctBy { it.second }
                .sortedByDescending { it.first.length }
                .take(20)
                .joinToString("\\n") { it.first + " => " + it.second }
        } catch (_: Exception) { "" }
    }

    private fun isSafeReadOnlyCommand(command: String): Boolean {
        val cmd = command.trim()
        if (cmd.isEmpty() || cmd.length > 500) return false
        // No shell chaining, redirects, substitutions, quotes, or pipelines.
        if (!cmd.matches(Regex("[A-Za-z0-9_./:=,@+ -]+"))) return false
        val patterns = listOf(
            Regex("getprop(?: [A-Za-z0-9_.-]+)?"),
            Regex("pm list packages(?: -[A-Za-z0-9_-]+)*"),
            Regex("pm list permissions(?: -[A-Za-z0-9_-]+)*"),
            Regex("pm path [A-Za-z0-9._]+"),
            Regex("cmd package list packages(?: -[A-Za-z0-9_-]+)*"),
            Regex("dumpsys (battery|package|activity|window|meminfo|wifi|display|power|connectivity|deviceidle|jobscheduler|notification|input_method)(?: [A-Za-z0-9._:-]+)?"),
            Regex("df(?: -[hPk]+)?"),
            Regex("ip (addr|route|link)(?: show)?"),
            Regex("wm (size|density)"),
            Regex("settings get (system|secure|global) [A-Za-z0-9_.-]+"),
            Regex("appops get [A-Za-z0-9._]+(?: [A-Z_]+)?"),
            Regex("ps -A"),
            Regex("id"),
            Regex("uname -a"),
            Regex("cat /proc/(meminfo|cpuinfo|uptime|version|loadavg)")
        )
        return patterns.any { it.matches(cmd) }
    }

    private fun askAi(){
        val request=aiRequest.text.toString().trim()
        if(request.isBlank()){
            Toast.makeText(this,"כתוב מה תרצה לבצע",Toast.LENGTH_SHORT).show()
            return
        }
        val matches=resolveAppCandidates(request)
        val appContext=if(matches.isBlank()) {
            "לא נמצאה התאמה מדויקת לשם אפליקציה שהוזכר בבקשה. אל תנחש מזהה חבילה; בקש הבהרה."
        } else {
            "התאמות מקומיות לשמות אפליקציות מותקנות (שם אפליקציה => package id):\n" + matches
        }
        aiRequest.isEnabled=false
        lifecycleScope.launch {
            val outcome: Pair<AiProviderClient.Answer?, String?> = withContext(Dispatchers.IO) {
                try {
                    val history=getSharedPreferences("command_history",0).getString("items","") ?: ""
                    val prompt="אתה מומחה Android ADB. המשתמש מבקש: $request.\n$appContext\nהשתמש במזהה החבילה המדויק מהרשימה אם הבקשה מתייחסת לאחת האפליקציות. אל תמציא package id. היסטוריית פקודות: $history. החזר פקודת ADB shell אחת בלבד, ללא Markdown, הסברים או עטיפות. אם חסר מידע חיוני החזר בדיוק NEEDS_CLARIFICATION. אל תייצר פקודות שמוחקות מידע, מאפסות את המכשיר או משביתות רכיבי אבטחה."
                    Pair(AiProviderClient.generate(this@MainActivity,prompt,300),null)
                } catch(e:Exception) {
                    Pair(null,e.message ?: "שגיאה לא ידועה")
                }
            }
            aiRequest.isEnabled=true
            val result=outcome.first
            if(result==null){
                AlertDialog.Builder(this@MainActivity).setTitle("שגיאת AI")
                    .setMessage(outcome.second ?: "לא ניתן לקבל תשובה מהמודל הראשי או ממודלי הגיבוי.")
                    .setPositiveButton("אישור",null).show()
                return@launch
            }
            val commandText=result.text.trim()
            if(commandText=="NEEDS_CLARIFICATION"){
                Toast.makeText(this@MainActivity,"הבקשה דורשת הבהרה. בדוק את שם האפליקציה או פרט נוסף.",Toast.LENGTH_LONG).show()
                return@launch
            }
            if(result.usedFallback) {
                Toast.makeText(this@MainActivity,"המודל הראשי עמוס. התשובה התקבלה ממודל גיבוי: ${result.model}.",Toast.LENGTH_LONG).show()
            }
            if(isSafeReadOnlyCommand(commandText)) {
                runCommand(commandText)
                Toast.makeText(this@MainActivity,"פקודת קריאה בלבד הופעלה ללא אישור נוסף.",Toast.LENGTH_SHORT).show()
            } else {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("פקודה מוכנה – נדרש אישור")
                    .setMessage(commandText + "\n\nהפקודה עשויה לשנות נתונים או הגדרות. היא לא תבוצע עד לאישור שלך.")
                    .setNegativeButton("ביטול",null)
                    .setNeutralButton("העתק"){_,_->
                        (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("ADB",commandText))
                    }
                    .setPositiveButton("אשר ובצע"){_,_->runCommand(commandText)}
                    .show()
            }
        }
    }

    private fun explainOutput() {
        val response=vm.outputText.value.orEmpty().trim()
        if(response.isBlank()) {
            Toast.makeText(this,"אין פלט להסביר. הרץ קודם פקודת ADB.",Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val result=withContext(Dispatchers.IO) {
                try {
                    AiProviderClient.generate(
                        this@MainActivity,
                        "הסבר בעברית פשוטה את תגובת ADB הבאה. פתח בסיכום קצר, הסבר שורות וערכים חשובים, ציין שגיאות או סימני אזהרה והצע מה לבדוק בהמשך. אל תטען שפעולה הצליחה אם הפלט אינו מוכיח זאת.\\n\\nתגובת ADB:\\n" + response.takeLast(14000),
                        900
                    )
                } catch(e:Exception) { null }
            }
            if(result==null) {
                AlertDialog.Builder(this@MainActivity).setTitle("לא הצלחתי להסביר את הפלט")
                    .setMessage("בדוק שמוגדר מפתח API ושהמודל הראשי או מודל הגיבוי זמינים.")
                    .setPositiveButton("סגור",null).show()
            } else {
                val title=if(result.usedFallback) "הסבר תגובת ADB – מודל גיבוי" else "הסבר תגובת ADB"
                AlertDialog.Builder(this@MainActivity).setTitle(title)
                    .setMessage(result.text)
                    .setNeutralButton("העתק הסבר"){_,_->
                        (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("הסבר ADB",result.text))
                    }
                    .setPositiveButton("סגור",null).show()
            }
        }
    }

    private fun showPairDialog() {
        startActivity(Intent(this, ConnectionActivity::class.java))
    }
}
