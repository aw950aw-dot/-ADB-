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
import com.draco.ladb.utils.UiComponents
import com.draco.ladb.utils.AppDrawer
import androidx.drawerlayout.widget.DrawerLayout
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
    private fun panel(): LinearLayout = UiComponents.panel(this, palette, 16, 18)
    private fun label(s:String,size:Float=15f):TextView=TextView(this).apply { text=s; textSize=size; setTextColor(palette.text); gravity=Gravity.RIGHT }
    private fun button(s: String, action: () -> Unit): TextView =
        UiComponents.button(this, s, palette.accent, Color.WHITE, null, 48, 2, action)
    private fun addSpace(h:Int=10){ root.addView(Space(this).apply { minimumHeight=dp(h) }) }
    override fun onCreate(savedInstanceState: Bundle?) {
        UiTheme.applySavedMode(this)
        super.onCreate(savedInstanceState)

        val drawer = AppDrawer.create(this)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isSmoothScrollingEnabled = true
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(13), dp(17), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(palette.background)
        }
        scroll.addView(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(AppDrawer.menuButton(this, drawer),
            LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("ADB SMART  /  TERMINAL + AI", 9f).apply {
                setTextColor(blue)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(label("מסוף פקודות חכם", 23f).apply {
                typeface = Typeface.DEFAULT_BOLD
            })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(11) })
        root.addView(header)
        root.addView(label(
            "הרץ פקודות, קבל עזרה מה־AI וצפה בתוצאות בזמן אמת.",
            12f
        ).apply { setTextColor(palette.muted); setPadding(0, dp(5), 0, dp(15)) })

        val connectionCard = panel()
        connectionCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("מצב חיבור", 16f).apply { typeface = Typeface.DEFAULT_BOLD })
                addView(label("Wireless Debugging  •  ADB", 10f).apply {
                    setTextColor(palette.muted)
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            connectionSpinner = ProgressBar(this@MainActivity).apply { isIndeterminate = true }
            addView(connectionSpinner, LinearLayout.LayoutParams(dp(26), dp(26)))
        })
        status = label("ממתין למצב החיבור…", 13f).apply {
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = UiComponents.shape(this@MainActivity, palette.surfaceAlt, 12, palette.outline)
        }
        connectionCard.addView(status, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
        })
        connectionCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("התחבר / שייך") { showPairDialog() },
                LinearLayout.LayoutParams(0, dp(47), 1f).apply { setMargins(0, dp(9), dp(4), 0) })
            addView(UiComponents.button(
                this@MainActivity,
                "פקודות מוכנות",
                palette.surfaceAlt,
                palette.text,
                palette.outline,
                47,
                2
            ) {
                startActivity(Intent(this@MainActivity, PresetsActivity::class.java))
            }, LinearLayout.LayoutParams(0, dp(47), 1f).apply { setMargins(dp(4), dp(9), 0, 0) })
        })
        root.addView(connectionCard)

        addSpace(13)
        val aiCard = panel()
        aiCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("עוזר AI לפקודות", 18f).apply {
                setTextColor(blue)
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("AI", 10f).apply {
                setTextColor(palette.accent)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = UiComponents.shape(this@MainActivity, palette.surfaceAlt, 8, palette.outline)
            })
        })
        aiRequest = EditText(this).apply {
            hint = "לדוגמה: הצג את כל האפליקציות שהתקנתי"
            minLines = 3
            maxLines = 5
            gravity = Gravity.RIGHT or Gravity.TOP
            setTextColor(palette.text)
            setHintTextColor(palette.muted)
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = UiComponents.shape(this@MainActivity, palette.inputBackground, 14, palette.outline)
        }
        aiCard.addView(aiRequest, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(11)
        })
        aiCard.addView(button("הכן פקודת ADB") { askAi() },
            LinearLayout.LayoutParams(-1, dp(49)).apply { topMargin = dp(10) })
        aiCard.addView(label(
            "פקודות משנות יוצגו לאישור לפני ביצוע. פקודות קריאה בלבד מאושרות אוטומטית.",
            11f
        ).apply { setTextColor(palette.muted); setPadding(0, dp(7), 0, 0) })
        root.addView(aiCard)

        addSpace(14)
        root.addView(label("פעולות מהירות", 18f).apply {
            setTextColor(palette.text)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(7))
        })
        val actions = listOf(
            "פרטי המכשיר" to "getprop | grep -E 'ro.product.model|ro.build.version.release|ro.build.version.sdk'",
            "רשימת אפליקציות" to "pm list packages -3",
            "נפח אחסון" to "df -h",
            "מצב סוללה" to "dumpsys battery",
            "זיכרון RAM" to "cat /proc/meminfo",
            "כתובת IP" to "ip addr",
            "רזולוציית מסך" to "wm size",
            "ניקוי מטמון" to "pm trim-caches 1G"
        )
        actions.chunked(2).forEachIndexed { rowIndex, pair ->
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                pair.forEachIndexed { index, (title, cmd) ->
                    val tint = when ((rowIndex * 2 + index) % 4) {
                        0 -> Color.rgb(34, 112, 178)
                        1 -> Color.rgb(20, 139, 126)
                        2 -> Color.rgb(104, 88, 171)
                        else -> Color.rgb(176, 111, 45)
                    }
                    addView(tile(title, tint) { runCommand(cmd) },
                        LinearLayout.LayoutParams(0, dp(70), 1f).apply {
                            setMargins(dp(3), dp(3), dp(3), dp(3))
                        })
                }
            })
        }

        addSpace(12)
        val appsCard = panel()
        appsCard.addView(label("ניהול אפליקציות", 17f).apply {
            setTextColor(blue)
            typeface = Typeface.DEFAULT_BOLD
        })
        val appActions = listOf<Pair<String, () -> Unit>>(
            "הסרה" to { openAppManager("remove") },
            "השבתה" to { openAppManager("disable") },
            "הפעלה" to { openAppManager("enable") },
            "התקנת APK" to {
                apkPicker.launch(arrayOf(
                    "application/vnd.android.package-archive",
                    "application/octet-stream"
                ))
            }
        )
        appActions.chunked(2).forEach { rowItems ->
            appsCard.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                rowItems.forEach { (title, action) ->
                    addView(UiComponents.button(
                        this@MainActivity,
                        title,
                        palette.surfaceAlt,
                        palette.text,
                        palette.outline,
                        45,
                        2,
                        action
                    ), LinearLayout.LayoutParams(0, dp(45), 1f).apply {
                        setMargins(dp(3), dp(4), dp(3), 0)
                    })
                }
            })
        }
        appsCard.addView(label(
            "הסרת אפליקציות מערכת משפיעה על המשתמש הנוכחי בלבד.",
            10f
        ).apply { setTextColor(palette.muted); setPadding(0, dp(7), 0, 0) })
        root.addView(appsCard)

        addSpace(12)
        val manualCard = panel()
        manualCard.addView(label("פקודה ידנית", 17f).apply {
            setTextColor(blue)
            typeface = Typeface.DEFAULT_BOLD
        })
        command = EditText(this).apply {
            hint = "הקלד פקודת ADB או shell…"
            setSingleLine(true)
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
            setTextColor(palette.text)
            setHintTextColor(palette.muted)
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = UiComponents.shape(this@MainActivity, palette.inputBackground, 13, palette.outline)
        }
        manualCard.addView(command, LinearLayout.LayoutParams(-1, dp(50)).apply {
            topMargin = dp(8)
        })
        manualCard.addView(button("בצע פקודה") {
            runCommand(command.text.toString())
        }, LinearLayout.LayoutParams(-1, dp(47)).apply { topMargin = dp(9) })
        root.addView(manualCard)

        addSpace(12)
        val terminalCard = panel()
        terminalCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("פלט ADB", 18f).apply {
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(blue)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("LIVE OUTPUT", 9f).apply {
                setTextColor(palette.muted)
                typeface = Typeface.DEFAULT_BOLD
            })
        })
        output = TextView(this).apply {
            text = "ממתין לפלט…"
            textSize = 12f
            setTextIsSelectable(true)
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.LEFT or Gravity.TOP
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(215, 230, 242))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setLineSpacing(dp(3).toFloat(), 1f)
            background = GradientDrawable().apply {
                setColor(Color.rgb(8, 17, 29))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.rgb(34, 58, 80))
            }
        }
        outputScroll = ScrollView(this).apply {
            isFillViewport = false
            isSmoothScrollingEnabled = true
            isNestedScrollingEnabled = true
            isVerticalScrollBarEnabled = true
            addView(output, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        terminalCard.addView(outputScroll, LinearLayout.LayoutParams(-1, dp(310)).apply {
            topMargin = dp(10)
        })
        terminalCard.addView(button("הסבר את הפלט בעזרת AI") { explainOutput() },
            LinearLayout.LayoutParams(-1, dp(47)).apply { topMargin = dp(10) })
        terminalCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(footerAction("נקה פלט") { vm.clearOutputText() },
                LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                    setMargins(dp(3), dp(8), dp(3), 0)
                })
            addView(footerAction("היסטוריה") { showHistory() },
                LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                    setMargins(dp(3), dp(8), dp(3), 0)
                })
        })
        root.addView(terminalCard)

        AppDrawer.attach(this, drawer, scroll)
        setContentView(drawer)
        UiComponents.applySystemBars(this, drawer, palette)

        vm.outputText.observe(this) { value ->
            val wasAtBottom = outputScroll.childCount == 0 ||
                outputScroll.scrollY + outputScroll.height >=
                outputScroll.getChildAt(0).height - dp(48)
            output.text = value.ifBlank { "ממתין לפלט…" }
            if (wasAtBottom) outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
        }

        vm.adb.running.observe(this) { ready ->
            status.text = if (ready == true) "●  מחובר ומוכן לפקודות" else "○  אין חיבור ADB פעיל"
            status.setTextColor(if (ready == true) Color.rgb(31, 170, 119) else palette.muted)
            connectionSpinner.visibility = if (ready == true) View.GONE else View.VISIBLE
            if (ready == true) {
                val queued = pendingPresetCommand
                if (!queued.isNullOrBlank()) {
                    pendingPresetCommand = null
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
    private fun footerAction(title: String, action: () -> Unit): TextView =
        UiComponents.button(this, title, palette.surfaceAlt, palette.text, palette.outline, 52, 2, action)

    private fun tile(title: String, color: Int, action: () -> Unit): TextView =
        UiComponents.button(this, title, color, Color.WHITE, null, 64, 2, action)

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
