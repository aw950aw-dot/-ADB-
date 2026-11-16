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
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
    private fun card(): LinearLayout = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(14),dp(16),dp(14)); background=GradientDrawable().apply { setColor(Color.WHITE); cornerRadius=dp(16).toFloat(); setStroke(dp(1),Color.rgb(224,235,249)) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=Color.rgb(234,243,255); window.decorView.systemUiVisibility=android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(18),dp(18),dp(18)); setBackgroundColor(Color.rgb(244,248,255)) }
        root.addView(TextView(this).apply { text="הגדרות"; textSize=26f; setTextColor(Color.rgb(20,45,80)); typeface=Typeface.DEFAULT_BOLD; gravity=Gravity.RIGHT })
        root.addView(TextView(this).apply { text="סוכן הבינה המלאכותית"; textSize=18f; setTextColor(blue); setPadding(0,dp(18),0,dp(8)); gravity=Gravity.RIGHT })
        val key=EditText(this).apply { hint="מפתח API של Gemini"; text=android.text.SpannableStringBuilder(ApiKeyVault.read(this@SettingsActivity)); inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; gravity=Gravity.RIGHT; setSingleLine(true) }
        root.addView(card().apply { addView(TextView(this@SettingsActivity).apply { text="מפתח API"; textSize=14f; setTextColor(Color.DKGRAY); gravity=Gravity.RIGHT }); addView(key) })
        val model=EditText(this).apply { hint="מודל Gemini"; setText(getSharedPreferences("ai_settings",0).getString("model","gemini-2.0-flash")); gravity=Gravity.RIGHT; setSingleLine(true) }
        root.addView(model)
        root.addView(Button(this).apply { text="שמירת הגדרות"; setOnClickListener { ApiKeyVault.save(this@SettingsActivity,key.text.toString().trim()); getSharedPreferences("ai_settings",0).edit().putString("model",model.text.toString().trim()).apply(); Toast.makeText(this@SettingsActivity,"ההגדרות נשמרו",Toast.LENGTH_SHORT).show(); finish() } })
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