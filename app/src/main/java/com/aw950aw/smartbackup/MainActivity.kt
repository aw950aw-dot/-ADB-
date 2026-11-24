package com.aw950aw.smartbackup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.aw950aw.smartbackup.shell.ShizukuLink
import com.aw950aw.smartbackup.ui.AppRoot
import com.aw950aw.smartbackup.ui.SmartBackupTheme
import com.aw950aw.smartbackup.wireless.WirelessLink

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuLink.init(applicationContext)
        WirelessLink.init(applicationContext)

        // Android 13+ asks before showing the progress notification. Backups also work if the user declines.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        setContent {
            SmartBackupTheme {
                AppRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ShizukuLink.refresh()
    }
}
