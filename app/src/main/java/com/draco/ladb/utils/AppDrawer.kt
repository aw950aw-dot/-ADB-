package com.draco.ladb.utils

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.view.GravityCompat
import com.draco.ladb.BuildConfig
import com.draco.ladb.views.AboutActivity
import com.draco.ladb.views.AppManagerHubActivity
import com.draco.ladb.views.AppTransferActivity
import com.draco.ladb.views.ConnectionActivity
import com.draco.ladb.views.DashboardActivity
import com.draco.ladb.views.HelpActivity
import com.draco.ladb.views.MainActivity
import com.draco.ladb.views.PresetsActivity
import com.draco.ladb.views.SettingsActivity

/** Shared RTL navigation drawer for the main screens. */
object AppDrawer {
    private data class Destination(
        val title: String,
        val glyph: String,
        val activity: Class<out Activity>
    )

    fun create(activity: Activity): DrawerLayout =
        DrawerLayout(activity).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setScrimColor(Color.argb(150, 3, 9, 20))
            setDrawerElevation(UiComponents.dp(activity, 18).toFloat())
        }

    fun attach(activity: Activity, drawer: DrawerLayout, content: View) {
        drawer.addView(
            content,
            DrawerLayout.LayoutParams(
                DrawerLayout.LayoutParams.MATCH_PARENT,
                DrawerLayout.LayoutParams.MATCH_PARENT
            )
        )

        val palette = UiTheme.palette(activity)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(palette.surface)
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                UiComponents.dp(activity, 22),
                UiComponents.dp(activity, 26),
                UiComponents.dp(activity, 22),
                UiComponents.dp(activity, 20)
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(25, 105, 169), Color.rgb(12, 46, 83))
            ).apply { cornerRadius = UiComponents.dp(activity, 0).toFloat() }
        }
        header.addView(TextView(activity).apply {
            text = "ADB SMART"
            textSize = 11f
            letterSpacing = 0.14f
            setTextColor(Color.rgb(164, 222, 255))
            typeface = Typeface.DEFAULT_BOLD
        })
        header.addView(TextView(activity).apply {
            text = "מרכז שליטה למכשיר"
            textSize = 21f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, UiComponents.dp(activity, 5), 0, 0)
        })
        header.addView(TextView(activity).apply {
            text = "גרסה " + BuildConfig.VERSION_NAME
            textSize = 12f
            setTextColor(Color.rgb(216, 233, 249))
            setPadding(0, UiComponents.dp(activity, 7), 0, 0)
        })
        panel.addView(header)

        val scroll = ScrollView(activity).apply { isFillViewport = true }
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                UiComponents.dp(activity, 12),
                UiComponents.dp(activity, 12),
                UiComponents.dp(activity, 12),
                UiComponents.dp(activity, 14)
            )
        }
        scroll.addView(list)

        val destinations = listOf(
            Destination("דף הבית", "⌂", DashboardActivity::class.java),
            Destination("חיבור Wireless Debugging", "⌁", ConnectionActivity::class.java),
            Destination("גיבוי ושחזור אפליקציות", "▣", AppTransferActivity::class.java),
            Destination("ניהול אפליקציות", "▦", AppManagerHubActivity::class.java),
            Destination("פקודות מוכנות", "⌘", PresetsActivity::class.java),
            Destination("מסוף ADB + AI", ">_", MainActivity::class.java),
            Destination("הגדרות", "⚙", SettingsActivity::class.java),
            Destination("אודות", "ⓘ", AboutActivity::class.java),
            Destination("עזרה והדרכה", "?", HelpActivity::class.java)
        )

        destinations.forEachIndexed { index, destination ->
            if (index == 6 || index == 8) {
                list.addView(View(activity).apply {
                    setBackgroundColor(palette.outline)
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    UiComponents.dp(activity, 1)
                ).apply {
                    topMargin = UiComponents.dp(activity, 8)
                    bottomMargin = UiComponents.dp(activity, 8)
                })
            }
            val selected = activity.javaClass == destination.activity
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    UiComponents.dp(activity, 13),
                    0,
                    UiComponents.dp(activity, 13),
                    0
                )
                background = UiComponents.surface(
                    activity,
                    if (selected) palette.surfaceAlt else palette.surface,
                    13,
                    if (selected) palette.accent else null,
                    clickable = true
                )
                isClickable = true
                isFocusable = true
                minimumHeight = UiComponents.dp(activity, 50)
                setOnClickListener {
                    drawer.closeDrawer(GravityCompat.START)
                    if (activity.javaClass != destination.activity) {
                        activity.startActivity(Intent(activity, destination.activity))
                    }
                }
            }
            row.addView(TextView(activity).apply {
                text = destination.glyph
                textSize = 19f
                gravity = Gravity.CENTER
                setTextColor(if (selected) palette.accent else palette.muted)
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(
                UiComponents.dp(activity, 32),
                LinearLayout.LayoutParams.MATCH_PARENT
            ))
            row.addView(TextView(activity).apply {
                text = destination.title
                textSize = 14f
                setTextColor(if (selected) palette.accent else palette.text)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
                setPadding(UiComponents.dp(activity, 8), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            list.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                UiComponents.dp(activity, 50)
            ).apply {
                bottomMargin = UiComponents.dp(activity, 5)
            })
        }

        panel.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        val profile = TextView(activity).apply {
            text = "GitHub  •  aw950aw-dot"
            textSize = 12f
            setTextColor(palette.accent)
            gravity = Gravity.CENTER
            setPadding(0, UiComponents.dp(activity, 14), 0, UiComponents.dp(activity, 18))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                drawer.closeDrawer(GravityCompat.START)
                runCatching {
                    activity.startActivity(Intent(
                        Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/aw950aw-dot")
                    ))
                }
            }
        }
        panel.addView(profile)

        val params = DrawerLayout.LayoutParams(
            UiComponents.dp(activity, 310),
            DrawerLayout.LayoutParams.MATCH_PARENT,
            GravityCompat.START
        )
        drawer.addView(panel, params)
    }

    fun menuButton(activity: Activity, drawer: DrawerLayout): TextView {
        val palette = UiTheme.palette(activity)
        return UiComponents.button(
            activity,
            "☰",
            palette.surfaceAlt,
            palette.text,
            palette.outline,
            48,
            1
        ) {
            if (drawer.isDrawerOpen(GravityCompat.START)) {
                drawer.closeDrawer(GravityCompat.START)
            } else {
                drawer.openDrawer(GravityCompat.START)
            }
        }
    }
}
