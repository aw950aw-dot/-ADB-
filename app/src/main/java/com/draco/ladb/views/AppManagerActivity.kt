package com.draco.ladb.views

import android.app.AlertDialog
import android.app.ProgressDialog
import android.os.Bundle
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.draco.ladb.utils.ADB
import com.draco.ladb.utils.UiTheme
import java.util.Locale

class AppManagerActivity : AppCompatActivity() {
    private enum class Mode { REMOVE, DISABLE, ENABLE }
    private data class AppEntry(
        val info: ApplicationInfo,
        val title: String,
        val packageName: String,
        val system: Boolean,
        val disabled: Boolean
    )

    private val palette get() = UiTheme.palette(this)
    private val blue get() = palette.accent
    private val adb by lazy { ADB.getInstance(this) }
    private lateinit var mode: Mode
    private lateinit var list: ListView
    private lateinit var subtitle: TextView
    private var entries: List<AppEntry> = emptyList()
    private var allEntries: List<AppEntry> = emptyList()
    private lateinit var searchInput: EditText
    private lateinit var sortSpinner: Spinner
    private var loadGeneration = 0
    private var actionInProgress = false
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        UiTheme.applySavedMode(this)
        super.onCreate(savedInstanceState)
        mode = when (intent.getStringExtra("mode")) {
            "disable" -> Mode.DISABLE
            "enable" -> Mode.ENABLE
            else -> Mode.REMOVE
        }
        window.statusBarColor = palette.statusBar
        window.navigationBarColor = palette.navigationBar
        window.decorView.systemUiVisibility = if (palette.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            setBackgroundColor(palette.background)
        }
        val title = when (mode) {
            Mode.REMOVE -> "הסרת אפליקציות"
            Mode.DISABLE -> "השבתת אפליקציות מערכת"
            Mode.ENABLE -> "אפליקציות מושבתות"
        }
        root.addView(TextView(this).apply {
            text = title
            textSize = 24f
            setTextColor(palette.text)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.RIGHT
        })
        subtitle = TextView(this).apply {
            textSize = 13f
            setTextColor(palette.muted)
            gravity = Gravity.RIGHT
            setPadding(0, dp(6), 0, dp(12))
            text = when (mode) {
                Mode.REMOVE -> "בחר אפליקציה להסרה מהמשתמש הנוכחי"
                Mode.DISABLE -> "מוצגות אפליקציות מערכת שפעילות כרגע"
                Mode.ENABLE -> "בחר אפליקציה מושבתת כדי להחזיר אותה"
            }
        }
        root.addView(subtitle)
        searchInput = EditText(this).apply {
            hint = "חיפוש לפי שם אפליקציה או package id"
            setSingleLine(true)
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), palette.outline)
            }
        }
        root.addView(searchInput, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) })
        sortSpinner = Spinner(this)
        val sortAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            listOf("מיון: שם א׳–ת׳", "מיון: שם ת׳–א׳", "מיון: אפליקציות מערכת תחילה", "מיון: מזהה חבילה"))
        sortAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sortSpinner.adapter = sortAdapter
        root.addView(sortSpinner, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { applyFilterAndSort() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        sortSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { applyFilterAndSort() }
        }
        list = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(palette.outline)
            dividerHeight = dp(1)
            cacheColorHint = Color.TRANSPARENT
            isFastScrollEnabled = true
            adapter = AppAdapter()
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val close = android.widget.Button(this).apply {
            text = "חזרה"
            isAllCaps = false
            setOnClickListener { finish() }
        }
        root.addView(close, LinearLayout.LayoutParams(-1, dp(50)))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        loadApps()
    }

    private fun loadApps() {
        val generation = ++loadGeneration
        subtitle.text = "טוען אפליקציות…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val pm = packageManager
                    val installed = pm.getInstalledApplications(
                        PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS
                    )
                    installed.mapNotNull { info ->
                        val pkg = info.packageName
                        if (pkg == packageName) return@mapNotNull null
                        val system = info.flags and
                            (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                        val state = runCatching { pm.getApplicationEnabledSetting(pkg) }
                            .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
                        val disabled = state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
                        val title = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(pkg)
                        AppEntry(info, title, pkg, system, disabled)
                    }.filter { entry ->
                        when (mode) {
                            Mode.REMOVE -> true
                            Mode.DISABLE -> entry.system && !entry.disabled && entry.info.enabled
                            Mode.ENABLE -> entry.disabled
                        }
                    }
                }
            }
            if (generation != loadGeneration || isFinishing) return@launch
            result.onSuccess { loaded ->
                allEntries = loaded
                applyFilterAndSort()
                if (entries.isEmpty()) {
                    subtitle.text = subtitle.text.toString() + "\nלא נמצאו אפליקציות ברשימה זו."
                }
            }.onFailure { error ->
                allEntries = emptyList()
                applyFilterAndSort()
                subtitle.text = "לא ניתן לקרוא את רשימת האפליקציות: " +
                    (error.message ?: "שגיאה לא ידועה")
            }
        }
    }

    private fun applyFilterAndSort() {
        if (!::list.isInitialized) return
        val query = if (::searchInput.isInitialized) searchInput.text.toString().trim().lowercase(Locale.getDefault()) else ""
        val filtered = allEntries.filter { entry ->
            query.isBlank() ||
                entry.title.lowercase(Locale.getDefault()).contains(query) ||
                entry.packageName.lowercase(Locale.getDefault()).contains(query)
        }
        entries = when (if (::sortSpinner.isInitialized) sortSpinner.selectedItemPosition else 0) {
            1 -> filtered.sortedWith(compareByDescending<AppEntry> { it.title.lowercase(Locale.getDefault()) }.thenBy { it.packageName })
            2 -> filtered.sortedWith(compareByDescending<AppEntry> { it.system }.thenBy { it.title.lowercase(Locale.getDefault()) })
            3 -> filtered.sortedBy { it.packageName.lowercase(Locale.getDefault()) }
            else -> filtered.sortedWith(compareBy<AppEntry> { it.title.lowercase(Locale.getDefault()) }.thenBy { it.packageName })
        }
        (list.adapter as? AppAdapter)?.notifyDataSetChanged()
        val countText = when (mode) {
            Mode.REMOVE -> entries.size.toString() + " אפליקציות • ההסרה למשתמש הנוכחי בלבד"
            Mode.DISABLE -> entries.size.toString() + " אפליקציות מערכת זמינות להשבתה"
            Mode.ENABLE -> entries.size.toString() + " אפליקציות מושבתות שניתן להחזיר"
        }
        if (::subtitle.isInitialized && allEntries.isNotEmpty()) subtitle.text = countText
    }

    private fun requestAction(entry: AppEntry) {
        val action = when (mode) {
            Mode.REMOVE -> "להסיר את האפליקציה ${entry.title}?"
            Mode.DISABLE -> "להשבית את אפליקציית המערכת ${entry.title}?"
            Mode.ENABLE -> "להחזיר לפעולה את ${entry.title}?"
        }
        val detail = when (mode) {
            Mode.REMOVE -> "הפעולה משתמשת ב־pm uninstall --user 0. היא מסירה את האפליקציה מהמשתמש הנוכחי ואינה מוחקת קובצי מערכת מהמחיצה. באפליקציות מערכת ייתכן שההסרה לא תתאפשר."
            Mode.DISABLE -> "השבתת רכיבי מערכת חיוניים עלולה לגרום לתקלות במכשיר. אל תשבית את משגר הבית, System UI, הגדרות, טלפון או רכיבי מערכת שאינך מזהה."
            Mode.ENABLE -> "האפליקציה תופעל מחדש באמצעות מנהל החבילות של Android."
        }
        AlertDialog.Builder(this)
            .setTitle(action)
            .setMessage("$detail\n\n${entry.packageName}")
            .setNegativeButton("ביטול", null)
            .setPositiveButton("אישור") { _, _ -> performAction(entry) }
            .show()
    }

    private fun performAction(entry: AppEntry) {
        if (actionInProgress) return
        if (adb.running.value != true) {
            Toast.makeText(this, "אין חיבור ADB פעיל. התחבר ונסה שוב.", Toast.LENGTH_LONG).show()
            return
        }
        actionInProgress = true
        val progress = ProgressDialog.show(this, "מבצע פעולה", "ממתין לתשובת Android…", true, false)
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val userResult = adb.executeShell("am get-current-user", 15)
                    val userId = userResult.output.lineSequence()
                        .mapNotNull { it.trim().toIntOrNull() }
                        .lastOrNull() ?: 0
                    val command = when (mode) {
                        Mode.REMOVE -> "pm uninstall --user $userId ${entry.packageName}"
                        Mode.DISABLE -> "pm disable-user --user $userId ${entry.packageName}"
                        Mode.ENABLE -> "pm enable --user $userId ${entry.packageName}"
                    }
                    val result = adb.executeShell(command, 30)
                    if (result.exitCode != 0 ||
                        result.output.contains("Failure", ignoreCase = true) ||
                        result.output.contains("Unknown package", ignoreCase = true)
                    ) {
                        throw IllegalStateException(result.output.ifBlank { "Android דחה את הפעולה." })
                    }
                    result.output.ifBlank { "הפעולה הסתיימה." }
                }
            }
            actionInProgress = false
            progress.dismiss()
            if (outcome.isSuccess) {
                Toast.makeText(this@AppManagerActivity, "הפעולה הושלמה.", Toast.LENGTH_SHORT).show()
                loadApps()
            } else {
                AlertDialog.Builder(this@AppManagerActivity)
                    .setTitle("הפעולה נכשלה")
                    .setMessage(outcome.exceptionOrNull()?.message ?: "Android לא אישר את הפעולה.")
                    .setPositiveButton("סגור", null)
                    .show()
                loadApps()
            }
        }
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = entries[position].packageName.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val entry = getItem(position)
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@AppManagerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(dp(8), dp(10), dp(8), dp(10))
                minimumHeight = dp(76)
            }
            row.removeAllViews()
            val icon = ImageView(this@AppManagerActivity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(try { packageManager.getApplicationIcon(entry.info) } catch (_: Exception) { null })
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
            val textColumn = LinearLayout(this@AppManagerActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
            textColumn.addView(TextView(this@AppManagerActivity).apply {
                text = entry.title
                textSize = 15f
                setTextColor(palette.text)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            textColumn.addView(TextView(this@AppManagerActivity).apply {
                text = entry.packageName
                textSize = 11f
                setTextColor(palette.muted)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val kind = when {
                entry.disabled -> "מושבתת"
                entry.system -> "אפליקציית מערכת"
                else -> "אפליקציית משתמש"
            }
            textColumn.addView(TextView(this@AppManagerActivity).apply {
                text = kind
                textSize = 11f
                setTextColor(if (entry.disabled) Color.rgb(190, 95, 35) else blue)
            })
            row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
            row.setOnClickListener { requestAction(entry) }
            return row
        }
    }
}