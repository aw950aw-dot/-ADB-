package com.draco.ladb.views

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import com.draco.ladb.utils.ApiKeyVault

class SettingsActivity : Activity() {
    private val blue = Color.rgb(23,105,210)
    private lateinit var modelSpinner: Spinner
    private lateinit var modelStatus: TextView
    private val modelIds = mutableListOf<String>()
    private val modelLabels = mutableListOf<String>()
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
    private fun card(): LinearLayout = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(14),dp(16),dp(14)); background=GradientDrawable().apply { setColor(Color.WHITE); cornerRadius=dp(16).toFloat(); setStroke(dp(1),Color.rgb(224,235,249)) } }
    private fun setModelOptions(options: List<Pair<String,String>>, selected: String) {
        modelIds.clear()
        modelLabels.clear()
        options.forEach { pair ->
            modelIds.add(pair.first)
            modelLabels.add(pair.second + "  •  " + pair.first)
        }
        val adapter=ArrayAdapter(this,android.R.layout.simple_spinner_item,modelLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modelSpinner.adapter=adapter
        val index=modelIds.indexOf(selected).takeIf { it >= 0 } ?: 0
        if(modelIds.isNotEmpty()) modelSpinner.setSelection(index)
    }

    private fun refreshModels(apiKey: String) {
        if(apiKey.isBlank()) {
            Toast.makeText(this,"הזן מפתח Gemini לפני רענון רשימת המודלים",Toast.LENGTH_LONG).show()
            return
        }
        modelStatus.text="טוען מודלים שזמינים למפתח שהזנת…"
        Thread {
            try {
                val connection=java.net.URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=100")
                    .openConnection() as java.net.HttpURLConnection
                connection.requestMethod="GET"
                connection.connectTimeout=15000
                connection.readTimeout=20000
                connection.setRequestProperty("x-goog-api-key",apiKey)
                val code=connection.responseCode
                val stream=if(code in 200..299) connection.inputStream else connection.errorStream
                val raw=stream.bufferedReader().use { it.readText() }
                if(code !in 200..299) throw Exception("HTTP " + code + ": " + raw)
                val json=org.json.JSONObject(raw)
                val array=json.optJSONArray("models") ?: org.json.JSONArray()
                val found=mutableListOf<Pair<String,String>>()
                for(i in 0 until array.length()) {
                    val item=array.getJSONObject(i)
                    val id=item.optString("name").removePrefix("models/")
                    val methods=item.optJSONArray("supportedGenerationMethods") ?: org.json.JSONArray()
                    val supportsGenerate=(0 until methods.length()).any { methods.optString(it)=="generateContent" }
                    if(id.startsWith("gemini-") && supportsGenerate) {
                        val display=item.optString("displayName",id).ifBlank { id }
                        found.add(Pair(id,display))
                    }
                }
                val sorted=found.distinctBy { it.first }.sortedBy { it.second }
                runOnUiThread {
                    if(sorted.isEmpty()) {
                        modelStatus.text="לא נמצאו מודלים זמינים ליצירת טקסט עבור המפתח הזה."
                    } else {
                        val selected=modelIds.getOrNull(modelSpinner.selectedItemPosition) ?: "gemini-3.8-flash"
                        setModelOptions(sorted,selected)
                        modelStatus.text="נמצאו " + sorted.size + " מודלים שתומכים ב־generateContent. הבחירה תישמר כברירת מחדל."
                        Toast.makeText(this,"רשימת המודלים עודכנה",Toast.LENGTH_SHORT).show()
                    }
                }
            } catch(e:Exception) {
                runOnUiThread {
                    modelStatus.text="לא ניתן לטעון מודלים: " + e.message
                    Toast.makeText(this,"טעינת המודלים נכשלה",Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=Color.rgb(234,243,255); window.decorView.systemUiVisibility=android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(18),dp(18),dp(18)); setBackgroundColor(Color.rgb(244,248,255)) }
        root.addView(TextView(this).apply { text="הגדרות"; textSize=26f; setTextColor(Color.rgb(20,45,80)); typeface=Typeface.DEFAULT_BOLD; gravity=Gravity.RIGHT })
        root.addView(TextView(this).apply { text="סוכן הבינה המלאכותית"; textSize=18f; setTextColor(blue); setPadding(0,dp(18),0,dp(8)); gravity=Gravity.RIGHT })
        val key=EditText(this).apply { hint="מפתח API של Gemini"; text=android.text.SpannableStringBuilder(ApiKeyVault.read(this@SettingsActivity)); inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; gravity=Gravity.RIGHT; setSingleLine(true) }
        root.addView(card().apply { addView(TextView(this@SettingsActivity).apply { text="מפתח API"; textSize=14f; setTextColor(Color.DKGRAY); gravity=Gravity.RIGHT }); addView(key) })
        val prefs=getSharedPreferences("ai_settings",0)
        var selectedModel=prefs.getString("model",null) ?: "gemini-3.8-flash"
        if(selectedModel.isBlank() || selectedModel=="gemini-2.0-flash") selectedModel="gemini-3.8-flash"
        modelSpinner=Spinner(this)
        modelStatus=TextView(this).apply {
            text="המודל שנבחר כאן יהיה מודל ברירת המחדל לכל פעולות ה־AI."
            textSize=12f
            setTextColor(Color.rgb(85,105,130))
            gravity=Gravity.RIGHT
            setPadding(0,dp(6),0,dp(6))
        }
        val fallback=listOf(
            Pair("gemini-3.8-flash","Gemini 3.8 Flash – מומלץ ומהיר"),
            Pair("gemini-3.6-flash","Gemini 3.6 Flash"),
            Pair("gemini-3.5-flash-lite","Gemini 3.5 Flash Lite")
        ).toMutableList()
        if(fallback.none{it.first==selectedModel}) fallback.add(0,Pair(selectedModel,selectedModel))
        setModelOptions(fallback,selectedModel)
        root.addView(card().apply {
            addView(TextView(this@SettingsActivity).apply { text="מודל ברירת מחדל"; textSize=14f; setTextColor(Color.DKGRAY); gravity=Gravity.RIGHT })
            addView(modelSpinner)
            addView(modelStatus)
            addView(Button(this@SettingsActivity).apply {
                text="רענן רשימת מודלים מהחשבון"
                isAllCaps=false
                setOnClickListener { refreshModels(key.text.toString().trim()) }
            })
        })
        root.addView(Button(this).apply {
            text="שמירת הגדרות"
            isAllCaps=false
            setOnClickListener {
                ApiKeyVault.save(this@SettingsActivity,key.text.toString().trim())
                val chosen=modelIds.getOrNull(modelSpinner.selectedItemPosition) ?: "gemini-3.8-flash"
                prefs.edit().putString("model",chosen).apply()
                Toast.makeText(this@SettingsActivity,"ההגדרות נשמרו. מודל ברירת המחדל: " + chosen,Toast.LENGTH_SHORT).show()
                finish()
            }
        })
        root.addView(TextView(this).apply { text="פקודות מוכנות"; textSize=20f; setTextColor(blue); gravity=Gravity.RIGHT; setPadding(0,dp(24),0,dp(8)) })
        val presets=listOf(
            "הצגת פרטי המכשיר" to "getprop",
            "גרסת Android" to "getprop ro.build.version.release",
            "דגם המכשיר" to "getprop ro.product.model",
            "רשימת אפליקציות מותקנות" to "pm list packages",
            "אפליקציות מערכת" to "pm list packages -s",
            "אפליקציות משתמש" to "pm list packages -3",
            "נפח אחסון" to "df -h",
            "זיכרון RAM" to "cat /proc/meminfo",
            "מצב סוללה" to "dumpsys battery",
            "רזולוציית מסך" to "wm size",
            "צפיפות מסך" to "wm density",
            "כתובת IP" to "ip addr",
            "רשת Wi-Fi" to "dumpsys wifi",
            "תהליכים פעילים" to "ps -A",
            "שימוש בסוללה" to "dumpsys batterystats",
            "הפעלת מצב כהה" to "cmd uimode night yes",
            "כיבוי מצב כהה" to "cmd uimode night no",
            "הצגת שירותים" to "service list",
            "יומן מערכת אחרון" to "logcat -d -t 200",
            "רשימת משתמשים" to "pm list users",
            "בדיקת הרשאות" to "dumpsys package",
            "ניקוי מטמון DNS" to "ndc resolver flushdefaultif"
        )
        presets.forEach { (title, cmd) ->
            val item = TextView(this).apply {
                text = "$title\n$cmd"
                textSize = 14f
                setTextColor(Color.rgb(30,55,85))
                gravity = Gravity.RIGHT
                setPadding(dp(12),dp(12),dp(12),dp(12))
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(12).toFloat()
                }
                setOnClickListener {
                    getSharedPreferences("quick_command",0).edit().putString("command",cmd).apply()
                    Toast.makeText(this@SettingsActivity,"הפקודה נשמרה. חזור למסך הראשי כדי להריץ אותה.",Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            root.addView(item)
            root.addView(Space(this).apply { minimumHeight = dp(7) })
        }
        val scroll=ScrollView(this).apply { addView(root) }; setContentView(scroll)
    }
}