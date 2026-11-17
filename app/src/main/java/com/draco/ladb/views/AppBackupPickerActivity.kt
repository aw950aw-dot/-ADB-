package com.draco.ladb.views

import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import com.draco.ladb.backup.AppBackupManager
import com.draco.ladb.utils.ADB
import com.draco.ladb.utils.UiTheme
import com.draco.ladb.utils.UiComponents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Dedicated, fully scrollable installed-app list for selecting a backup source. */
class AppBackupPickerActivity : AppCompatActivity() {
    private val adb by lazy { ADB.getInstance(this) }
    private val palette get() = UiTheme.palette(this)
    private lateinit var appList: android.widget.ListView
    private lateinit var search: EditText
    private lateinit var includeSystem: androidx.appcompat.widget.SwitchCompat
    private lateinit var count: TextView
    private var apps: List<AppBackupManager.AppItem> = emptyList()
    private var filteredApps: List<AppBackupManager.AppItem> = emptyList()
    private var loadError: String? = null
    private var pendingPackage: String? = null
    private var reloadGeneration = 0
    private val iconCache = object : android.util.LruCache<String, android.graphics.drawable.Drawable>(72) {}
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val filterRunnable = Runnable { renderApps() }

    private val saveBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val packageName = pendingPackage
        pendingPackage = null
        if (uri != null && packageName != null) exportToUri(packageName, uri)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        UiTheme.applySavedMode(this)
        super.onCreate(savedInstanceState)
        buildScreen()
        reloadApps()
    }

    override fun onDestroy() {
        searchHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildScreen() {
        window.statusBarColor = palette.statusBar
        window.navigationBarColor = palette.navigationBar
        window.decorView.systemUiVisibility =
            if (palette.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(17), dp(12), dp(17), dp(20))
            setBackgroundColor(palette.background)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(actionButton("‹", palette.surfaceAlt, Color.WHITE) { finish() },
            LinearLayout.LayoutParams(dp(42), dp(42)))
        header.addView(text("בחר אפליקציה לגיבוי", 20, palette.text, true),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
        root.addView(header)

        root.addView(text(
            "לחץ על האפליקציה הרצויה כדי ליצור לה קובץ ZIP.",
            13, palette.muted, false
        ).apply { setPadding(0, dp(9), 0, dp(15)) })

        search = EditText(this).apply {
            hint = "חיפוש לפי שם או מזהה חבילה"
            textSize = 14f
            setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setTextColor(palette.text)
            setHintTextColor(palette.muted)
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(palette.inputBackground, dp(13), palette.outline)
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(49)))
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchHandler.removeCallbacks(filterRunnable)
                searchHandler.postDelayed(filterRunnable, 180L)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(7))
        }
        count = text("טוען אפליקציות…", 12, palette.muted, false)
        filters.addView(count, LinearLayout.LayoutParams(0, -2, 1f))
        includeSystem = androidx.appcompat.widget.SwitchCompat(this).apply {
            text = "כולל אפליקציות מערכת"
            textSize = 11f
            setTextColor(palette.text)
            setOnCheckedChangeListener { _, _ -> reloadApps() }
        }
        filters.addView(includeSystem)
        root.addView(filters)

        appList = android.widget.ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(8)
            cacheColorHint = Color.TRANSPARENT
            isFastScrollEnabled = true
            isVerticalScrollBarEnabled = true
            adapter = AppAdapter()
        }
        root.addView(appList, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(text("בחר אפליקציה ליצירת גיבוי ZIP.", 11, palette.muted, false)
            .apply { setPadding(0, dp(8), 0, 0) })

        setContentView(root)
        UiComponents.applySystemBars(this, root, palette)
    }

    private fun reloadApps() {
        if (!::appList.isInitialized) return
        val generation = ++reloadGeneration
        val showSystem = includeSystem.isChecked
        count.text = "טוען אפליקציות…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AppBackupManager.loadApps(this@AppBackupPickerActivity, showSystem) }
            }
            if (generation != reloadGeneration || isFinishing) return@launch
            result.onSuccess { apps = it }
                .onFailure {
                    apps = emptyList()
                    loadError = "לא ניתן לקרוא את רשימת האפליקציות: " + (it.message ?: "שגיאה לא ידועה")
                }
            if (result.isSuccess) loadError = null
            renderApps()
        }
    }

    private fun renderApps() {
        if (!::appList.isInitialized) return
        val query = if (::search.isInitialized) search.text.toString().trim().lowercase() else ""
        filteredApps = apps.filter {
            query.isBlank() || it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query)
        }
        count.text = loadError ?: "${filteredApps.size} אפליקציות"
        (appList.adapter as? AppAdapter)?.notifyDataSetChanged()
    }

    private inner class AppAdapter : android.widget.BaseAdapter() {
        override fun getCount(): Int = if (filteredApps.isEmpty()) 1 else filteredApps.size
        override fun getItem(position: Int): Any? = filteredApps.getOrNull(position)
        override fun getItemId(position: Int): Long =
            filteredApps.getOrNull(position)?.packageName?.hashCode()?.toLong() ?: Long.MIN_VALUE

        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val app = filteredApps.getOrNull(position)
            if (app == null) {
                val empty = (convertView as? TextView) ?: text("", 14, palette.muted, false)
                empty.text = loadError ?: "לא נמצאו אפליקציות תואמות."
                empty.setTextColor(if (loadError != null) Color.RED else palette.muted)
                empty.gravity = Gravity.CENTER
                empty.setPadding(dp(12), dp(28), dp(12), dp(28))
                empty.background = null
                return empty
            }

            val card = (convertView as? LinearLayout) ?: LinearLayout(this@AppBackupPickerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                background = UiComponents.surface(this@AppBackupPickerActivity, palette.surface, 16, palette.outline, clickable = true)
                isClickable = true
                isFocusable = true
            }
            card.removeAllViews()
            val icon = ImageView(this@AppBackupPickerActivity).apply {
                val cached = iconCache.get(app.packageName) ?: runCatching {
                    packageManager.getApplicationIcon(app.applicationInfo)
                }.getOrNull()?.also { iconCache.put(app.packageName, it) }
                setImageDrawable(cached ?: AppCompatResources.getDrawable(this@AppBackupPickerActivity, android.R.drawable.sym_def_app_icon))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            card.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))
            val details = LinearLayout(this@AppBackupPickerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), 0, 0, 0)
            }
            details.addView(text(app.label, 15, palette.text, true))
            details.addView(text(app.packageName, 10, palette.muted, false))
            details.addView(text(
                "גרסה ${app.versionName} · ${app.versionCode}" + if (app.isSystem) " · מערכת" else "",
                10, palette.muted, false
            ))
            card.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(text("גבה  ›", 12, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = rounded(palette.accent, dp(11))
            })
            card.setOnClickListener { prepareBackup(app) }
            return card
        }
    }

    private fun prepareBackup(app: AppBackupManager.AppItem) {
        if (adb.running.value != true) {
            AlertDialog.Builder(this)
                .setTitle("נדרש חיבור Wireless Debugging")
                .setMessage("חבר תחילה את המכשיר ל־ADB. לאחר החיבור חזור למסך הזה ובחר אפליקציה.")
                .setNegativeButton("ביטול", null)
                .setPositiveButton("פתח מסך חיבור") { _, _ ->
                    startActivity(Intent(this, ConnectionActivity::class.java))
                }.show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("גיבוי ${app.label}")
            .setMessage(
                "ייווצר קובץ ZIP עם ה־APK וכל קובצי ה־Split, וכן נתונים שניתן לקרוא. " +
                    "נתונים פרטיים ייכללו רק אם הרשאת Root פעילה או אם האפליקציה מאפשרת run-as."
            )
            .setNegativeButton("ביטול", null)
            .setPositiveButton("המשך") { _, _ ->
                pendingPackage = app.packageName
                saveBackup.launch("${app.packageName}-adb-smart-backup.zip")
            }.show()
    }

    private fun exportToUri(packageName: String, uri: android.net.Uri) {
        val progress = ProgressDialog.show(this, "יוצר גיבוי", "קורא APK ונתונים דרך ADB ו־Root…", true, false)
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val file = File(cacheDir, "exports/${packageName}-${System.currentTimeMillis()}.zip")
                try {
                    val summary = AppBackupManager.createBackup(this@AppBackupPickerActivity, adb, packageName, file)
                    contentResolver.openOutputStream(uri, "w")?.use { output ->
                        file.inputStream().use { it.copyTo(output, 64 * 1024) }
                    } ?: error("לא ניתן לכתוב למיקום שנבחר.")
                    summary to null
                } catch (e: Exception) {
                    null to (e.message ?: "שגיאת גיבוי לא ידועה")
                } finally {
                    file.delete()
                }
            }
            progress.dismiss()
            val summary = outcome.first
            if (summary == null) {
                AlertDialog.Builder(this@AppBackupPickerActivity)
                    .setTitle("הגיבוי נכשל")
                    .setMessage(outcome.second ?: "לא ניתן ליצור גיבוי.")
                    .setPositiveButton("סגור", null)
                    .show()
            } else {
                val details = buildString {
                    append("נוצר גיבוי עבור ${summary.appLabel}.\n\n")
                    append(if (summary.rootAvailable) "Root זמין במכשיר המקור.\n" else "Root לא זמין במכשיר המקור.\n")
                    append(if (summary.privateDataIncluded) "נתונים פרטיים: נכללו.\n" else "נתונים פרטיים: לא נכללו.\n")
                    append("סוגי נתונים שנכללו: ${summary.payloadNames.joinToString().ifBlank { "אין" }}\n")
                    if (summary.notes.isNotEmpty()) append("\nהערות:\n" + summary.notes.joinToString("\n"))
                }
                AlertDialog.Builder(this@AppBackupPickerActivity)
                    .setTitle("הגיבוי הושלם")
                    .setMessage(details)
                    .setPositiveButton("אישור", null)
                    .show()
            }
        }
    }

    private fun actionButton(label: String, color: Int, textColor: Int = Color.WHITE, action: () -> Unit): TextView =
        UiComponents.button(this, label, color, textColor,
            if (color == palette.surfaceAlt) palette.outline else null, 48, 2, action)

    private fun text(value: String, size: Int, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            gravity = Gravity.END
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }
}
