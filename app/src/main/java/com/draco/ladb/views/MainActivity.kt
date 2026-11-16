package com.draco.ladb.views

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
    private val blue=Color.rgb(23,105,210)
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
    private fun panel(): LinearLayout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(14),dp(16),dp(14)); background=GradientDrawable().apply { setColor(Color.WHITE); cornerRadius=dp(18).toFloat(); setStroke(dp(1),Color.rgb(225,235,248)) } }
    private fun label(s:String,size:Float=15f):TextView=TextView(this).apply { text=s; textSize=size; setTextColor(Color.rgb(25,48,78)); gravity=Gravity.RIGHT }
    private fun button(s:String, action:()->Unit)=Button(this).apply { text=s; isAllCaps=false; setOnClickListener { action() } }
    private fun addSpace(h:Int=10){ root.addView(Space(this).apply { minimumHeight=dp(h) }) }
    override fun onCreate(savedInstanceState:Bundle?){ super.onCreate(savedInstanceState)
        window.statusBarColor=Color.rgb(234,243,255); window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(22)); layoutDirection=View.LAYOUT_DIRECTION_RTL; setBackgroundColor(Color.rgb(244,248,255)) }
        root.addView(LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            layoutDirection=View.LAYOUT_DIRECTION_RTL
            gravity=Gravity.CENTER_VERTICAL
            addView(button("⚙"){ startActivity(Intent(this@MainActivity,SettingsActivity::class.java)) },
                LinearLayout.LayoutParams(dp(58),dp(52)).apply { setMargins(0,0,dp(8),0) })
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
            val rows=listOf(
                listOf("הסרת אפליקציות"){ openAppManager("remove") },
                listOf("השבתת אפליקציות מערכת"){ openAppManager("disable") },
                listOf("החזרת אפליקציות מושבתות"){ openAppManager("enable") },
                listOf("התקנת קובצי APK"){ apkPicker.launch(arrayOf("application/vnd.android.package-archive","application/octet-stream")) }
            )
            rows.chunked(2).forEach { pair ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation=LinearLayout.HORIZONTAL
                    pair.forEach { entry ->
                        val title=entry[0] as String
                        val action=entry[1] as ()->Unit
                        addView(tile(title, listOf(Color.rgb(28,125,205),Color.rgb(20,150,135),Color.rgb(109,89,190),Color.rgb(210,126,37))[pair.indexOf(entry) % 4], action),
                            LinearLayout.LayoutParams(0,dp(78),1f).apply { setMargins(dp(4),dp(4),dp(4),dp(4)) })
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
                setTextColor(Color.rgb(225,239,255))
                setPadding(dp(14),dp(14),dp(14),dp(14))
                setLineSpacing(dp(3).toFloat(),1f)
                background=GradientDrawable().apply { setColor(Color.rgb(19,31,49)); cornerRadius=dp(14).toFloat() }
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
                orientation=LinearLayout.HORIZONTAL
                addView(button("נקה פלט"){ vm.clearOutputText() },LinearLayout.LayoutParams(0,dp(50),1f).apply { setMargins(dp(3),0,dp(3),0) })
                addView(button("היסטוריית פקודות"){ showHistory() },LinearLayout.LayoutParams(0,dp(50),1f).apply { setMargins(dp(3),0,dp(3),0) })
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
        val guidePrefs=getSharedPreferences("first_run",0)
        if(!guidePrefs.getBoolean("guide_shown",false)) showFirstRunGuide() 
        else if(vm.needsToPair()) showPairDialog() else vm.startADBServer()
    }
    override fun onResume(){
        super.onResume()
        val prefs=getSharedPreferences("quick_command",0)
        val pending=prefs.getString("command",null)
        if(!pending.isNullOrBlank()) {
            prefs.edit().remove("command").apply()
            if(vm.adb.running.value==true) runCommand(pending) else pendingPresetCommand=pending
        }
    }
    private fun tile(title:String, color:Int, action:()->Unit):TextView = TextView(this).apply {
        text=title
        textSize=14f
        typeface=Typeface.DEFAULT_BOLD
        gravity=Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(8),dp(8),dp(8),dp(8))
        background=GradientDrawable().apply {
            setColor(color)
            cornerRadius=dp(16).toFloat()
        }
        elevation=dp(2).toFloat()
        isClickable=true
        isFocusable=true
        setOnClickListener { action() }
    }

    private fun showFirstRunGuide() {
        val message = "כדי להתחבר בפעם הראשונה:\\n\\n" +
            "1. פתח הגדרות Android → אודות הטלפון → מספר Build / מספר גרסה. הקש עליו 7 פעמים עד שתופיע הודעה שאפשרויות למפתחים הופעלו. ייתכן שתידרש סיסמת המכשיר.\\n\\n" +
            "2. חזור להגדרות → מערכת (או הגדרות נוספות) → אפשרויות למפתחים → הפעל ניפוי באגים אלחוטי. אשר את ההודעה.\\n\\n" +
            "3. בתוך ניפוי באגים אלחוטי בחר 'שיוך מכשיר באמצעות קוד'. בחלון שיופיע קח את מספר הפורט שמופיע אחרי כתובת ה־IP ואת קוד השיוך בן 6 הספרות.\\n\\n" +
            "4. חזור לכאן והזן את פורט השיוך ואת הקוד. שים לב: זהו פורט השיוך מהחלון הקטן, לא הפורט שמופיע במסך הראשי של ניפוי באגים אלחוטי.\\n\\n" +
            "5. לאחר השיוך האפליקציה תאתר ותתחבר לפורט ADB האלחוטי. השאר את ניפוי הבאגים האלחוטי פעיל."
        AlertDialog.Builder(this)
            .setTitle("ברוך הבא ל־ADB חכם – מדריך התחברות")
            .setMessage(message)
            .setPositiveButton("הבנתי, המשך") { _, _ ->
                getSharedPreferences("first_run",0).edit().putBoolean("guide_shown",true).apply()
                if(vm.needsToPair()) showPairDialog() else vm.startADBServer()
            }
            .setNeutralButton("פתח אודות הטלפון") { _, _ ->
                getSharedPreferences("first_run",0).edit().putBoolean("guide_shown",true).apply()
                try { startActivity(Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS)) } catch (_:Exception) {}
            }
            .setNegativeButton("פתח אפשרויות למפתחים") { _, _ ->
                getSharedPreferences("first_run",0).edit().putBoolean("guide_shown",true).apply()
                try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } catch (_:Exception) {}
            }
            .setCancelable(false)
            .show()
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
                        temp=java.io.File.createTempFile("adb-install-"+index+"-",".apk",cacheDir)
                        contentResolver.openInputStream(uri)?.use { input ->
                            temp.outputStream().use { output -> input.copyTo(output) }
                        } ?: throw IllegalStateException("לא ניתן לקרוא את הקובץ")
                        val output=vm.adb.installApk(temp)
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
            "התאמות מקומיות לשמות אפליקציות מותקנות (שם אפליקציה => package id):\\n" + matches
        }
        aiRequest.isEnabled=false
        lifecycleScope.launch {
            val result=withContext(Dispatchers.IO){
                try {
                    val history=getSharedPreferences("command_history",0).getString("items","") ?: ""
                    val prompt="אתה מומחה Android ADB. המשתמש מבקש: $request.\\n$appContext\\nהשתמש במזהה החבילה המדויק מהרשימה אם הבקשה מתייחסת לאחת האפליקציות. אל תמציא package id. היסטוריית פקודות: $history. החזר פקודת ADB shell אחת בלבד, ללא Markdown, הסברים או עטיפות. אם חסר מידע חיוני החזר בדיוק NEEDS_CLARIFICATION. אל תייצר פקודות שמוחקות מידע, מאפסות את המכשיר או משביתות רכיבי אבטחה."
                    AiProviderClient.generate(this@MainActivity,prompt,300).let { it }
                } catch(e:Exception) { "ERROR: ${e.message}" }
            }
            aiRequest.isEnabled=true
            if(result.text.startsWith("ERROR:")){
                AlertDialog.Builder(this@MainActivity).setTitle("שגיאת AI")
                    .setMessage(result.text.removePrefix("ERROR:")).setPositiveButton("אישור",null).show()
                return@launch
            }
            val commandText=result.text.trim().removeSurrounding("```").trim()
            if(commandText=="NEEDS_CLARIFICATION"){
                Toast.makeText(this@MainActivity,"הבקשה דורשת הבהרה. בדוק את שם האפליקציה או פרט נוסף.",Toast.LENGTH_LONG).show()
                return@launch
            }
            if(result.usedFallback) {
                Toast.makeText(this@MainActivity,"המודל הראשי עמוס. התשובה התקבלה ממודל הגיבוי ${result.model}.",Toast.LENGTH_LONG).show()
            }
            if(isSafeReadOnlyCommand(commandText)) {
                runCommand(commandText)
                Toast.makeText(this@MainActivity,"פקודת קריאה בלבד הופעלה ללא אישור נוסף.",Toast.LENGTH_SHORT).show()
            } else {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("פקודה מוכנה – נדרש אישור")
                    .setMessage(commandText + "\\n\\nהפקודה עשויה לשנות נתונים או הגדרות. היא לא תבוצע עד לאישור שלך.")
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

    private fun showPairDialog(){
        // A custom, height-bounded dialog keeps the action buttons visible in split-screen.
        val dialog = android.app.Dialog(this)
        val density = resources.displayMetrics.density
        fun d(value: Int) = (value * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(d(20), d(18), d(20), d(12))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = d(18).toFloat()
                setStroke(d(1), Color.rgb(220, 232, 248))
            }
        }

        container.addView(label("שיוך ניפוי באגים אלחוטי", 20f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(blue)
        })

        val instructions = label(
            "בהגדרות Android פתח ניפוי באגים אלחוטי ← שיוך באמצעות קוד. השאר את החלון פתוח והזן כאן את הפורט ואת קוד השיוך.",
            14f
        ).apply {
            setPadding(0, d(8), 0, d(10))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(instructions)
            })
        }
        val portInput = EditText(this).apply {
            hint = "פורט שיוך (לדוגמה 39953)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
            setSingleLine(true)
        }
        val codeInput = EditText(this).apply {
            hint = "קוד שיוך בן 6 ספרות"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
            setSingleLine(true)
        }
        val fields = scroll.getChildAt(0) as LinearLayout
        fields.addView(portInput, LinearLayout.LayoutParams(-1, d(56)).apply {
            bottomMargin = d(10)
        })
        fields.addView(codeInput, LinearLayout.LayoutParams(-1, d(56)))

        container.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(0, d(10), 0, 0)
        }
        val pairButton = Button(this).apply {
            text = "שיוך"
            isAllCaps = false
            setOnClickListener {
                val p = portInput.text.toString().trim()
                val c = codeInput.text.toString().trim()
                if (p.isBlank() || c.length != 6) {
                    Toast.makeText(this@MainActivity, "יש להזין פורט וקוד שיוך בן 6 ספרות", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                isEnabled = false
                text = "משייך…"
                dialog.dismiss()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = vm.adb.pair(p, c)
                    withContext(Dispatchers.Main) {
                        if (ok) {
                            vm.setPairedBefore(true)
                            vm.startADBServer()
                        } else {
                            Toast.makeText(this@MainActivity, "השיוך נכשל. בדוק שהפורט והקוד עדיין בתוקף ונסה שוב.", Toast.LENGTH_LONG).show()
                            showPairDialog()
                        }
                    }
                }
            }
        }
        val cancelButton = Button(this).apply {
            text = "ביטול"
            isAllCaps = false
            setOnClickListener { dialog.dismiss() }
        }
        footer.addView(pairButton, LinearLayout.LayoutParams(0, d(52), 1f).apply {
            marginEnd = d(6)
        })
        footer.addView(cancelButton, LinearLayout.LayoutParams(0, d(52), 1f).apply {
            marginStart = d(6)
        })
        container.addView(footer, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(container)
        dialog.setCancelable(true)
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                val availableWidth = resources.displayMetrics.widthPixels - d(32)
                val availableHeight = (resources.displayMetrics.heightPixels * 0.82f).toInt()
                setLayout(minOf(availableWidth, d(520)), minOf(availableHeight, d(560)))
            }
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val availableWidth = resources.displayMetrics.widthPixels - d(32)
            val availableHeight = (resources.displayMetrics.heightPixels * 0.82f).toInt()
            setLayout(minOf(availableWidth, d(520)), minOf(availableHeight, d(560)))
        }
    }}