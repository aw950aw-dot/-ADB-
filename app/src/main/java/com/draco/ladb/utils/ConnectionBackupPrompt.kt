package com.draco.ladb.utils

import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.draco.ladb.views.AppDataBackupActivity

/** Offers one encrypted app-data backup after the first successful wireless ADB connection. */
object ConnectionBackupPrompt {
    fun maybeShow(activity: AppCompatActivity, adb: ADB) {
        if (activity.isFinishing || activity.isDestroyed || !adb.hasAdbHostKey()) return

        val prefs = activity.getSharedPreferences("connection_backup_prompt", AppCompatActivity.MODE_PRIVATE)
        if (prefs.getBoolean("backup_completed", false) ||
            prefs.getBoolean("prompted_after_connection", false)
        ) return

        prefs.edit().putBoolean("prompted_after_connection", true).apply()
        AlertDialog.Builder(activity)
            .setTitle("שמור את החיבור לגיבוי")
            .setMessage(
                "נמצא מפתח ADB שאיתו המכשיר משויך. כדי לשמור את החיבור גם במקרה של הסרה והתקנה מחדש, " +
                    "צור עכשיו גיבוי מוצפן של נתוני ADB SMART. הגיבוי יכלול את מפתח ADB והגדרות השיוך. " +
                    "לאחר התקנה מחדש משחזרים את הקובץ, סוגרים את האפליקציה ופותחים אותה מחדש. " +
                    "עדכון רגיל של האפליקציה אינו מוחק את הנתונים ואינו דורש שחזור."
            )
            .setPositiveButton("צור גיבוי מוצפן") { _, _ ->
                activity.startActivity(
                    Intent(activity, AppDataBackupActivity::class.java)
                        .putExtra("backup_for_connection", true)
                )
            }
            .setNegativeButton("אחר כך", null)
            .show()
    }
}
