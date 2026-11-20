package com.draco.ladb.views

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.draco.ladb.utils.ADB
import com.draco.ladb.utils.UiComponents
import com.draco.ladb.utils.UiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** User-controlled runtime permission manager powered by ADB or an approved Shizuku shell. */
class AppPermissionActivity : AppCompatActivity() {
    private data class AppEntry(
        val packageName: String,
        val label: String,
        val info: ApplicationInfo
    )
    private data class PermissionEntry(
        val name: String,
        val label: String,
        val granted: Boolean
    )

    private val adb by lazy { ADB.getInstance(this) }
    private val palette get() = UiTheme.palette(this)
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var subtitle: TextView
    private var allApps: List<AppEntry> = emptyList()
    private var visibleApps: List<AppEntry> = emptyList()
    private var busy = false

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        UiTheme.applySavedMode(this)
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(15), dp(16), dp(14))
            setBackgroundColor(palette.background)
        }
        root.addView(text("ניהול הרשאות אפליקציות", 23, palette.text, true))
        subtitle = text("טוען אפליקציות…", 12, palette.muted, false).apply {
            setPadding(0, dp(5), 0, dp(12))
        }
        root.addView(subtitle)
        search = EditText(this).apply {
            hint = "חיפוש אפליקציה לפי שם או package"
            setSingleLine(true)
            textDirection = View.TEXT_DIRECTION_RTL
            setTextColor(palette.text)
            setHintTextColor(palette.muted)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), palette.outline)
            }
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(51)).apply { bottomMargin = dp(8) })
        list = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(palette.outline)
            dividerHeight = dp(1)
            cacheColorHint = Color.TRANSPARENT
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(UiComponents.button(
            this, "חזרה", palette.surfaceAlt, palette.text, palette.outline, 48, 1
        ) { finish() }, LinearLayout.LayoutParams(-1, dp(49)).apply { topMargin = dp(9) })
        setContentView(root)
        UiComponents.applySystemBars(this, root, palette)

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filterApps() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        list.setOnItemClickListener { _, _, position, _ ->
            if (!busy) visibleApps.getOrNull(position)?.let(::showAppPermissions)
        }
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        updateConnectionStatus()
    }

    private fun updateConnectionStatus() {
        if (!::subtitle.isInitialized) return
        subtitle.text = if (adb.hasPrivilegedShell()) {
            "בחר אפליקציה כדי להציג הרשאות Android מסוכנות שהיא ביקשה. ניתן להעניק או לבטל אותן דרך ADB/Shizuku."
        } else {
            "נדרש חיבור ADB או Shizuku. אפשר לפתוח את מסך החיבור לאחר בחירת אפליקציה."
        }
    }

    private fun loadApps() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    .mapNotNull { info ->
                        if (info.packageName == packageName) return@mapNotNull null
                        val label = runCatching {
                            packageManager.getApplicationLabel(info).toString()
                        }.getOrDefault(info.packageName)
                        AppEntry(info.packageName, label, info)
                    }
                    .sortedWith(compareBy<AppEntry> { it.label.lowercase(Locale.ROOT) }.thenBy { it.packageName })
            }
            allApps = loaded
            filterApps()
            updateConnectionStatus()
        }
    }

    private fun filterApps() {
        if (!::list.isInitialized) return
        val query = search.text.toString().trim().lowercase(Locale.ROOT)
        visibleApps = allApps.filter {
            query.isBlank() || it.label.lowercase(Locale.ROOT).contains(query) ||
                it.packageName.lowercase(Locale.ROOT).contains(query)
        }
        list.adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = visibleApps.size
            override fun getItem(position: Int) = visibleApps[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
                val item = convertView as? LinearLayout ?: LinearLayout(this@AppPermissionActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                }
                item.removeAllViews()
                item.addView(text(visibleApps[position].label, 15, palette.text, true))
                item.addView(text(visibleApps[position].packageName, 10, palette.muted, false).apply {
                    textDirection = View.TEXT_DIRECTION_LTR
                    gravity = Gravity.LEFT
                    setPadding(0, dp(3), 0, 0)
                })
                return item
            }
        }
    }

    private fun showAppPermissions(app: AppEntry) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { loadRuntimePermissions(app.packageName) }
            }
            result.onFailure { error ->
                AlertDialog.Builder(this@AppPermissionActivity)
                    .setTitle("לא ניתן לקרוא הרשאות")
                    .setMessage(error.message ?: "Android לא החזיר את רשימת ההרשאות.")
                    .setPositiveButton("סגור", null)
                    .show()
            }.onSuccess { permissions ->
                showPermissionDialog(app, permissions)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun loadRuntimePermissions(packageName: String): List<PermissionEntry> {
        val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        val names = packageInfo.requestedPermissions ?: return emptyList()
        return names.mapNotNull { permission ->
            val info = runCatching { packageManager.getPermissionInfo(permission, 0) }.getOrNull()
                ?: return@mapNotNull null
            val baseProtection = info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
            if (baseProtection != PermissionInfo.PROTECTION_DANGEROUS) return@mapNotNull null
            val label = runCatching { info.loadLabel(packageManager).toString() }.getOrDefault(permission)
            PermissionEntry(
                permission,
                label,
                packageManager.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED
            )
        }.sortedBy { it.label.lowercase(Locale.ROOT) }
    }

    private fun showPermissionDialog(app: AppEntry, permissions: List<PermissionEntry>) {
        if (permissions.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(app.label)
                .setMessage("האפליקציה לא ביקשה הרשאות Android מסוג Runtime שניתן לנהל מכאן. הרשאות מיוחדות מנוהלות במסך ההגדרות של Android.")
                .setPositiveButton("סגור", null)
                .setNeutralButton("הרשאות מיוחדות") { _, _ -> openSpecialSettings(app) }
                .show()
            return
        }

        val labels = permissions.map { "${it.label}\n${it.name}" }.toTypedArray()
        val checked = permissions.map { it.granted }.toBooleanArray()
        val selected = checked.copyOf()
        AlertDialog.Builder(this)
            .setTitle("הרשאות — ${app.label}")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> selected[which] = isChecked }
            .setPositiveButton("החל שינויים") { _, _ ->
                applyPermissionChanges(app, permissions, selected)
            }
            .setNeutralButton("הרשאות מיוחדות") { _, _ -> openSpecialSettings(app) }
            .setNegativeButton("ביטול", null)
            .setMessage("מסומנות הרשאות שיוענקו. הסרת סימון תנסה לבטל הרשאה קיימת. לא ניתן לשנות מכאן הרשאות חתימה או הרשאות מיוחדות.")
            .show()
    }

    private fun applyPermissionChanges(
        app: AppEntry,
        permissions: List<PermissionEntry>,
        selected: BooleanArray
    ) {
        if (!adb.hasPrivilegedShell()) {
            AlertDialog.Builder(this)
                .setTitle("נדרש חיבור")
                .setMessage("התחבר תחילה דרך ADB או Shizuku. הפעולות דורשות אישור shell.")
                .setPositiveButton("פתח מסך חיבור") { _, _ ->
                    startActivity(Intent(this, ConnectionActivity::class.java))
                }
                .setNegativeButton("ביטול", null)
                .show()
            return
        }
        if (busy) return
        busy = true
        val progress = android.app.ProgressDialog.show(this, "מעדכן הרשאות", "מבצע שינויים שאישרת…", true, false)
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching {
                val results = mutableListOf<String>()
                val userResult = adb.executeShell("am get-current-user", 15)
                if (userResult.exitCode != 0) error("לא ניתן לזהות את המשתמש הפעיל ב-Android.")
                val userId = userResult.output.lineSequence().mapNotNull { it.trim().toIntOrNull() }.lastOrNull()
                    ?: error("Android לא החזיר מזהה משתמש תקין.")
                permissions.forEachIndexed { index, permission ->
                    val shouldGrant = selected[index]
                    if (shouldGrant == permission.granted) return@forEachIndexed
                    if (!permission.name.matches(Regex("[A-Za-z0-9_.]+"))) {
                        results.add("נדחה שם הרשאה לא תקין: ${permission.name}")
                        return@forEachIndexed
                    }
                    val action = if (shouldGrant) "grant" else "revoke"
                    val response = adb.executeShell(
                        "pm $action --user $userId ${app.packageName} ${permission.name}",
                        20
                    )
                    val failed = response.exitCode != 0 ||
                        response.output.contains("Failure", ignoreCase = true) ||
                        response.output.contains("Unknown", ignoreCase = true)
                    results.add(
                        (if (failed) "נכשל" else "הצליח") + ": ${permission.label}" +
                            (if (response.output.isNotBlank()) " — ${response.output}" else "")
                    )
                }
                results
            } }
            busy = false
            runCatching { progress.dismiss() }
            if (isFinishing || isDestroyed) return@launch
            val failure = outcome.exceptionOrNull()
            if (failure != null) {
                AlertDialog.Builder(this@AppPermissionActivity)
                    .setTitle("עדכון ההרשאות נכשל")
                    .setMessage(failure.message ?: "Android לא אישר את הפעולה.")
                    .setPositiveButton("סגור", null)
                    .show()
                return@launch
            }
            val message = outcome.getOrDefault(emptyList()).joinToString("\n").ifBlank { "לא נדרשו שינויים." }
            AlertDialog.Builder(this@AppPermissionActivity)
                .setTitle("תוצאות שינוי ההרשאות")
                .setMessage(message)
                .setPositiveButton("סגור") { _, _ -> showAppPermissions(app) }
                .show()
        }
    }

    private fun openSpecialSettings(app: AppEntry) {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${app.packageName}")
                }
            )
        }.onFailure {
            Toast.makeText(this, "לא ניתן לפתוח את הגדרות האפליקציה.", Toast.LENGTH_LONG).show()
        }
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean) =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            gravity = Gravity.RIGHT
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
}
