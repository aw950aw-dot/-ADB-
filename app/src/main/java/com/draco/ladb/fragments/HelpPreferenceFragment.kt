package com.draco.ladb.fragments

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.edit
import androidx.preference.*
import com.draco.ladb.R
import com.draco.ladb.utils.ADB
import com.draco.ladb.views.DashboardActivity
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class HelpPreferenceFragment : PreferenceFragmentCompat() {
    private lateinit var adb: ADB

    override fun onAttach(context: Context) {
        super.onAttach(context)
        adb = ADB.getInstance(context)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.help, rootKey)
    }

    private fun restartApp() {
        val intent = Intent(requireContext(), DashboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        requireActivity().finish()
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            getString(R.string.unpair_key) -> {
                val context = requireContext()
                PreferenceManager.getDefaultSharedPreferences(context).edit(commit = true) {
                    putBoolean(context.getString(R.string.paired_key), false)
                }
                restartApp()
            }

            getString(R.string.restart_key) -> restartApp()
            getString(R.string.developer_key) -> openURL(getString(R.string.developer_url))
            getString(R.string.licenses_key) -> {
                val intent = Intent(requireContext(), OssLicensesMenuActivity::class.java)
                startActivity(intent)
            }

            else -> {
                if (preference !is SwitchPreference && preference !is EditTextPreference) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(preference.title)
                        .setMessage(preference.summary)
                        .show()
                }
            }
        }

        return super.onPreferenceTreeClick(preference)
    }

    /**
     * Open a URL for the user
     */
    private fun openURL(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.w("HelpPreference", "Could not open help URL", e)
            Snackbar.make(requireView(), getString(R.string.snackbar_intent_failed), Snackbar.LENGTH_SHORT).show()
        }
    }
}