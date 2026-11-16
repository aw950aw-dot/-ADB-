package com.draco.ladb.views

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import java.util.Locale

class PresetsActivity : Activity() {
    private data class Preset(val category: String, val title: String, val command: String)
    private val blue = Color.rgb(23, 105, 210)
    private lateinit var listContainer: LinearLayout
    private lateinit var search: EditText
    private val presets = listOf(
        Preset("מידע על המכשיר", "דגם המכשיר", "getprop ro.product.model"),
        Preset("מידע על המכשיר", "יצרן המכשיר", "getprop ro.product.manufacturer"),
        Preset("מידע על המכשיר", "גרסת Android", "getprop ro.build.version.release"),
        Preset("מידע על המכשיר", "רמת API של Android", "getprop ro.build.version.sdk"),
        Preset("מידע על המכשיר", "מספר Build", "getprop ro.build.display.id"),
        Preset("מידע על המכשיר", "כל מאפייני המערכת", "getprop"),
        Preset("מידע על המכשיר", "זמן פעילות המכשיר", "cat /proc/uptime"),
        Preset("מידע על המכשיר", "פרטי kernel", "uname -a"),
        Preset("מידע על המכשיר", "תאריך ושעה במכשיר", "date"),
        Preset("זיכרון ואחסון", "זיכרון RAM", "cat /proc/meminfo"),
        Preset("זיכרון ואחסון", "נפח אחסון בכל המחיצות", "df -h"),
        Preset("זיכרון ואחסון", "פרטי אחסון מערכת", "dumpsys diskstats"),
        Preset("זיכרון ואחסון", "פרטי CPU", "cat /proc/cpuinfo"),
        Preset("זיכרון ואחסון", "עומס מערכת", "cat /proc/loadavg"),
        Preset("זיכרון ואחסון", "נתוני שימוש בסוללה", "dumpsys batterystats"),
        Preset("סוללה וחשמל", "מצב סוללה", "dumpsys battery"),
        Preset("סוללה וחשמל", "מצב צריכת חשמל", "dumpsys power"),
        Preset("סוללה וחשמל", "מצב חיסכון בסוללה", "settings get global low_power"),
        Preset("סוללה וחשמל", "נתוני שירות תרמי", "dumpsys thermalservice"),
        Preset("רשת ואינטרנט", "כתובות IP", "ip addr"),
        Preset("רשת ואינטרנט", "טבלת ניתוב", "ip route"),
        Preset("רשת ואינטרנט", "פרטי Wi-Fi", "dumpsys wifi"),
        Preset("רשת ואינטרנט", "מצב חיבורי רשת", "dumpsys connectivity"),
        Preset("רשת ואינטרנט", "סטטיסטיקת רשת", "dumpsys netstats"),
        Preset("רשת ואינטרנט", "רשימת ממשקי רשת", "ip link"),
        Preset("מסך ותצוגה", "רזולוציית מסך", "wm size"),
        Preset("מסך ותצוגה", "צפיפות מסך", "wm density"),
        Preset("מסך ותצוגה", "בהירות מסך", "settings get system screen_brightness"),
        Preset("מסך ותצוגה", "זמן כיבוי מסך", "settings get system screen_off_timeout"),
        Preset("מסך ותצוגה", "מצב כהה", "cmd uimode night"),
        Preset("אפליקציות וחבילות", "אפליקציות משתמש", "pm list packages -3"),
        Preset("אפליקציות וחבילות", "אפליקציות מערכת", "pm list packages -s"),
        Preset("אפליקציות וחבילות", "כל האפליקציות", "pm list packages"),
        Preset("אפליקציות וחבילות", "אפליקציות מושבתות", "pm list packages -d"),
        Preset("אפליקציות וחבילות", "אפליקציות פעילות", "pm list packages -e"),
        Preset("אפליקציות וחבילות", "נתיבי APK של האפליקציות", "pm list packages -f"),
        Preset("אפליקציות וחבילות", "הרשאות מסוכנות שהוגדרו", "pm list permissions -d"),
        Preset("אפליקציות וחבילות", "תכונות המכשיר", "pm list features"),
        Preset("אפליקציות וחבילות", "משתמשים במכשיר", "pm list users"),
        Preset("תהליכים ושירותים", "תהליכים פעילים", "ps -A"),
        Preset("תהליכים ושירותים", "רשימת שירותי Android", "service list"),
        Preset("תהליכים ושירותים", "שירותי התראות", "dumpsys notification"),
        Preset("תהליכים ושירותים", "שירותי נגישות", "dumpsys accessibility"),
        Preset("תהליכים ושירותים", "מנהל חלונות", "dumpsys window"),
        Preset("תהליכים ושירותים", "פעילויות פעילות", "dumpsys activity activities"),
        Preset("תהליכים ושירותים", "שירותי שעונים והתראות", "dumpsys alarm"),
        Preset("תהליכים ושירותים", "מקלדת ברירת מחדל", "settings get secure default_input_method"),
        Preset("תהליכים ושירותים", "יומן מערכת – 300 שורות אחרונות", "logcat -d -t 300")
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(11), dp(14), dp(11))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), Color.rgb(225, 235, 248))
        }
        isClickable = true
        isFocusable = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(234, 243, 255)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(244, 248, 255))
        }
        root.addView(TextView(this).apply {
            text = "פקודות מוכנות"
            textSize = 25f
            setTextColor(Color.rgb(20, 45, 80))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.RIGHT
        })
        root.addView(TextView(this).apply {
            text = "בחר פקודה כדי לשלוח אותה למסוף ADB"
            textSize = 13f
            setTextColor(Color.rgb(85, 105, 130))
            gravity = Gravity.RIGHT
            setPadding(0, dp(4), 0, dp(10))
        })
        search = EditText(this).apply {
            hint = "חיפוש פקודה או קטגוריה…"
            setSingleLine(true)
            gravity = Gravity.RIGHT
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(220, 230, 243))
            }
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(52)))
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(listContainer)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        root.addView(android.widget.Button(this).apply {
            text = "חזרה למסך הבית"
            isAllCaps = false
            setOnClickListener { finish() }
        })
        setContentView(root)
        render()
    }

    private fun render() {
        listContainer.removeAllViews()
        val query = search.text.toString().trim().lowercase(Locale.getDefault())
        val filtered = presets.filter {
            query.isBlank() ||
                it.category.lowercase(Locale.getDefault()).contains(query) ||
                it.title.lowercase(Locale.getDefault()).contains(query) ||
                it.command.lowercase(Locale.getDefault()).contains(query)
        }
        var previousCategory = ""
        filtered.forEach { preset ->
            if (preset.category != previousCategory) {
                previousCategory = preset.category
                listContainer.addView(TextView(this).apply {
                    text = preset.category
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(23, 105, 210))
                    gravity = Gravity.RIGHT
                    setPadding(0, dp(14), 0, dp(6))
                })
            }
            val item = card().apply {
                addView(TextView(this@PresetsActivity).apply {
                    text = preset.title
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(30, 55, 85))
                    gravity = Gravity.RIGHT
                })
                addView(TextView(this@PresetsActivity).apply {
                    text = preset.command
                    textSize = 11f
                    setTextColor(Color.rgb(95, 115, 140))
                    gravity = Gravity.LEFT
                    textDirection = View.TEXT_DIRECTION_LTR
                    setPadding(0, dp(4), 0, 0)
                })
                setOnClickListener {
                    getSharedPreferences("quick_command", 0).edit().putString("command", preset.command).apply()
                    Toast.makeText(this@PresetsActivity, "הפקודה נשלחה למסך הבית", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            listContainer.addView(item, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(7)
            })
        }
        if (filtered.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "לא נמצאו פקודות מתאימות."
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
                setTextColor(Color.GRAY)
            })
        }
    }
}