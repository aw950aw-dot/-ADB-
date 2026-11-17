package com.draco.ladb.views

import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.draco.ladb.backup.AppBackupManager
import com.draco.ladb.utils.ADB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Main app transfer screen: APK + accessible data backup and guided restore. */
class AppTransferActivity : AppCompatActivity() {
    private val adb by lazy { ADB.getInstance(this) }
    private lateinit var appList: LinearLayout
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var includeSystem: Switch
    private var apps: List<AppBackupManager.AppItem> = emptyList()
    private var pendingPackage: String? = null

    private val saveBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val packageName = pendingPackage
        pendingPackage = null
        if (uri != null && packageName != null) exportToUri(packageName, uri)
    }

    private val selectBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreFromUri(uri)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        reloadApps()
        adb.running.observe(this) { connected -> updateStatus(connected == true) }
    }

    override fun onResume() {
        super.onResume()
        updateStatus(adb.running.value == true)
    }

    private fun buildScreen() {
        window.statusBarColor = Color.rgb(10, 17, 31)
        window.navigationBarColor = Color.rgb(10, 17, 31)
        window.decorView.systemUiVisibility = 0
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(12), dp(18), dp(20))
            setBackgroundColor(Color.rgb(10, 17, 31))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(text("‹", 34, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(42), dp(48)))
        top.addView(text("גיבוי ושחזור אפליקציות", 21, Color.WHITE, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(text("ZIP", 12, Color.rgb(125, 211, 252), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(Color.rgb(20, 42, 68), dp(20), Color.rgb(40, 90, 135))
        })
        root.addView(top)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(23, 55, 91), Color.rgb(22, 42, 72), Color.rgb(23, 35, 58))
            ).apply { cornerRadius = dp(22).toFloat() }
        }
        hero.addView(text("APP TRANSFER", 11, Color.rgb(125, 211, 252), true))
        hero.addView(text("מעבירים אפליקציה\nעם הנתונים שלה", 25, Color.WHITE, true).apply {
            setPadding(0, dp(7), 0, dp(6))
        })
        hero.addView(text(
            "יוצרים חבילת ZIP שכוללת את ה־APK, קובצי Split ונתונים שניתן לקרוא. נתונים פרטיים ייכללו רק כאשר Root או run-as מאפשרים זאת.",
            13, Color.rgb(202, 218, 238), false
        ))
        hero.addView(actionButton("בחר קובץ ZIP לשחזור", Color.rgb(31, 150, 180)) {
            selectBackup.launch(arrayOf("application/zip", "application/octet-stream"))
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(14) })
        root.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        status = text("בודק חיבור ADB…", 13, Color.rgb(253, 186, 116), true).apply {
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = rounded(Color.rgb(34, 31, 40), dp(12))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val connectionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        connectionRow.addView(text("הגיבוי והשחזור משתמשים בחיבור Wireless Debugging הפעיל של LADB.", 12, Color.rgb(151, 169, 193), false),
            LinearLayout.LayoutParams(0, -2, 1f))
        connectionRow.addView(actionButton("פתח חיבור", Color.rgb(48, 75, 115)) {
            startActivity(Intent(this@AppTransferActivity, MainActivity::class.java))
        })
        root.addView(connectionRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, dp(8))
        }
        section.addView(text("האפליקציות במכשיר", 18, Color.WHITE, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        includeSystem = Switch(this).apply {
            text = "כולל מערכת"
            textSize = 11f
            setTextColor(Color.rgb(184, 199, 219))
            setOnCheckedChangeListener { _, _ -> reloadApps() }
        }
        section.addView(includeSystem)
        root.addView(section)

        search = EditText(this).apply {
            hint = "חיפוש לפי שם או מזהה חבילה"
            textSize = 14f
            setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(130, 149, 176))
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(Color.rgb(20, 31, 49), dp(13), Color.rgb(47, 65, 91))
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(50)))
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderApps()
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val listScroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            addView(appList)
        }
        root.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        root.addView(text(
            "הערה: גיבוי ללא Root אינו יכול לקרוא תיקיות פרטיות של אפליקציות רגילות. האפליקציה תציג בדוח אילו חלקים נכללו ואילו לא.",
            11, Color.rgb(145, 164, 188), false
        ).apply { setPadding(dp(2), dp(10), dp(2), 0) })

        setContentView(root)
    }

    private fun reloadApps() {
        if (!::appList.isInitialized) return
        apps = runCatching { AppBackupManager.loadApps(this, includeSystem.isChecked) }.getOrElse {
            appList.removeAllViews()
            appList.addView(text("לא ניתן לקרוא את רשימת האפליקציות: ${it.message}", 13, Color.RED, false))
            return
        }
        renderApps()
    }

    private fun renderApps() {
        if (!::appList.isInitialized) return
        appList.removeAllViews()
        val query = if (::search.isInitialized) search.text.toString().trim().lowercase() else ""
        val filtered = apps.filter {
            query.isBlank() || it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query)
        }
        if (filtered.isEmpty()) {
            appList.addView(text("לא נמצאו אפליקציות תואמות.", 14, Color.rgb(163, 180, 203), false).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(28), 0, dp(28))
            })
            return
        }
        filtered.forEach { app ->
            appList.addView(appCard(app), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        }
    }

    private fun appCard(app: AppBackupManager.AppItem): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = rounded(Color.rgb(19, 30, 48), dp(17), Color.rgb(42, 60, 84))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(this).apply {
            try { setImageDrawable(packageManager.getApplicationIcon(app.applicationInfo)) }
            catch (_: Exception) { setImageResource(android.R.drawable.sym_def_app_icon) }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, 0, 0)
        }
        details.addView(text(app.label, 15, Color.WHITE, true))
        details.addView(text(app.packageName, 10, Color.rgb(143, 164, 190), false))
        details.addView(text("גרסה ${app.versionName} · ${app.versionCode}" +
            if (app.isSystem) " · מערכת" else "", 10, Color.rgb(143, 164, 190), false))
        row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row)
        card.addView(actionButton("גבה אפליקציה + נתונים", Color.rgb(48, 112, 190)) {
            prepareBackup(app)
        }, LinearLayout.LayoutParams(-1, dp(43)).apply { topMargin = dp(10) })
        return card
    }

    private fun prepareBackup(app: AppBackupManager.AppItem) {
        if (adb.running.value != true) {
            AlertDialog.Builder(this)
                .setTitle("נדרש חיבור Wireless Debugging")
                .setMessage("חבר את האפליקציה ל־ADB ממסך החיבור, חזור לכאן והתחל את הגיבוי.")
                .setNegativeButton("ביטול", null)
                .setPositiveButton("פתח מסך חיבור") { _, _ ->
                    startActivity(Intent(this, MainActivity::class.java))
                }.show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("גיבוי ${app.label}")
            .setMessage(
                "החבילה תכלול את ה־APK ואת כל קובצי ה־Split. בנוסף יועתקו תיקיות נתונים משותפות שנגישות דרך Shell. " +
                    "נתונים פרטיים ב־/data ייכללו רק אם Root זמין או אם האפליקציה מאפשרת run-as. " +
                    "האם ליצור קובץ ZIP?"
            )
            .setNegativeButton("ביטול", null)
            .setPositiveButton("המשך") { _, _ ->
                pendingPackage = app.packageName
                saveBackup.launch("${app.packageName}-adb-smart-backup.zip")
            }.show()
    }

    private fun exportToUri(packageName: String, uri: Uri) {
        val progress = ProgressDialog.show(this, "יוצר גיבוי", "קורא APK ונתונים דרך ADB…", true, false)
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val file = File(cacheDir, "exports/${packageName}-${System.currentTimeMillis()}.zip")
                try {
                    val summary = AppBackupManager.createBackup(this@AppTransferActivity, adb, packageName, file)
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
                AlertDialog.Builder(this@AppTransferActivity)
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
                    append("\n\nהקובץ נשמר במיקום שבחרת.")
                }
                AlertDialog.Builder(this@AppTransferActivity)
                    .setTitle("הגיבוי הושלם")
                    .setMessage(details)
                    .setPositiveButton("אישור", null)
                    .show()
            }
        }
    }

    private fun restoreFromUri(uri: Uri) {
        if (adb.running.value != true) {
            AlertDialog.Builder(this)
                .setTitle("נדרש חיבור ADB")
                .setMessage("חבר תחילה את Wireless Debugging, ואז בחר שוב את קובץ הגיבוי.")
                .setPositiveButton("פתח מסך חיבור") { _, _ ->
                    startActivity(Intent(this, MainActivity::class.java))
                }
                .setNegativeButton("ביטול", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("אישור שחזור")
            .setMessage(
                "השחזור עשוי להתקין APK מתוך קובץ ה־ZIP אם האפליקציה אינה מותקנת, " +
                    "ולכתוב נתונים במיקומים השייכים לאפליקציה. השתמש רק בקובץ גיבוי ממקור שאתה סומך עליו. " +
                    "הנתונים הקיימים אינם נמחקים מראש; קבצים בעלי אותו שם עשויים להידרס, וקבצים נוספים עשויים להישאר. " +
                    "מפתחות Android Keystore ותוכן הקשור למכשיר אינם מובטחים לשחזור.\n\nלהמשיך?"
            )
            .setNegativeButton("ביטול", null)
            .setPositiveButton("שחזר") { _, _ -> performRestore(uri) }
            .show()
    }

    private fun performRestore(uri: Uri) {
        val progress = ProgressDialog.show(this, "משחזר אפליקציה", "בודק את חבילת הגיבוי ומחזיר נתונים…", true, false)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val temp = File(cacheDir, "restore-${System.currentTimeMillis()}.zip")
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                    } ?: error("לא ניתן לקרוא את קובץ הגיבוי.")
                    AppBackupManager.restoreBackup(this@AppTransferActivity, adb, temp) to null
                } catch (e: Exception) {
                    null to (e.message ?: "שגיאת שחזור לא ידועה")
                } finally {
                    temp.delete()
                }
            }
            progress.dismiss()
            AlertDialog.Builder(this@AppTransferActivity)
                .setTitle(if (result.first != null) "תוצאות השחזור" else "השחזור נכשל")
                .setMessage(result.first ?: result.second ?: "לא ניתן לשחזר את הגיבוי.")
                .setPositiveButton("סגור", null)
                .show()
        }
    }

    private fun updateStatus(connected: Boolean) {
        if (!::status.isInitialized) return
        status.text = if (connected) "●  ADB מחובר — אפשר להתחיל גיבוי ושחזור"
            else "○  ADB לא מחובר — חבר Wireless Debugging כדי לבצע פעולות"
        status.setTextColor(if (connected) Color.rgb(110, 231, 183) else Color.rgb(253, 186, 116))
        status.background = rounded(
            if (connected) Color.rgb(17, 49, 48) else Color.rgb(49, 39, 35),
            dp(12)
        )
    }

    private fun actionButton(label: String, color: Int, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isClickable = true
            isFocusable = true
            setPadding(dp(9), dp(7), dp(9), dp(7))
            background = rounded(color, dp(12))
            setOnClickListener { action() }
        }
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean): TextView {
        return TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            gravity = Gravity.RIGHT
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }
}
