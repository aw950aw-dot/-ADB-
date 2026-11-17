package com.draco.ladb.views

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import com.draco.ladb.BuildConfig
import com.draco.ladb.utils.AppDrawer
import com.draco.ladb.utils.UiComponents
import com.draco.ladb.utils.UiTheme

class AboutActivity : AppCompatActivity() {
    private val palette get() = UiTheme.palette(this)
    private fun dp(value: Int) = UiComponents.dp(this, value)

    override fun onCreate(savedInstanceState: Bundle?) {
        UiTheme.applySavedMode(this)
        super.onCreate(savedInstanceState)

        val drawer = AppDrawer.create(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(14), dp(18), dp(22))
            setBackgroundColor(palette.background)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(AppDrawer.menuButton(this, drawer),
            LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(this).apply {
            text = "אודות ADB SMART"
            textSize = 22f
            setTextColor(palette.text)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(header)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = UiComponents.surface(this@AboutActivity, palette.surface, 22, palette.outline)
            elevation = dp(2).toFloat()
        }
        hero.addView(TextView(this).apply {
            text = "ADB SMART"
            textSize = 12f
            letterSpacing = 0.16f
            setTextColor(palette.accent)
            typeface = Typeface.DEFAULT_BOLD
        })
        hero.addView(TextView(this).apply {
            text = "גיבוי, שחזור וניהול Android"
            textSize = 21f
            setTextColor(palette.text)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(6))
        })
        hero.addView(TextView(this).apply {
            text = "גרסה " + BuildConfig.VERSION_NAME + "  •  build " + BuildConfig.VERSION_CODE
            textSize = 13f
            setTextColor(palette.muted)
        })
        content.addView(hero, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(16)
        })

        val author = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(17), dp(17), dp(17))
            background = UiComponents.surface(this@AboutActivity, palette.surface, 19, palette.outline)
        }
        author.addView(TextView(this).apply {
            text = "פיתוח ותחזוקה"
            textSize = 15f
            setTextColor(palette.text)
            typeface = Typeface.DEFAULT_BOLD
        })
        author.addView(TextView(this).apply {
            text = "פרופיל במתמחים טופ"
            textSize = 17f
            setTextColor(palette.accent)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(7), 0, dp(3))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://mitmachim.top/user/%D7%90%D7%94%D7%A8%D7%9F")))
            }
        })
        author.addView(TextView(this).apply {
            text = "פתיחת פרופיל המפתח במתמחים טופ"
            textSize = 12f
            setTextColor(palette.muted)
        })
        content.addView(author, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12)
        })

        val credits = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(17), dp(17), dp(17))
            background = UiComponents.surface(this@AboutActivity, palette.surface, 19, palette.outline)
        }
        credits.addView(TextView(this).apply {
            text = "קרדיטים ורישיונות"
            textSize = 15f
            setTextColor(palette.text)
            typeface = Typeface.DEFAULT_BOLD
        })
        credits.addView(TextView(this).apply {
            text = "האפליקציה מבוססת על מנוע LADB. קרדיט מלא ורישיונות התלויות נשמרים במערכת הרישיונות."
            textSize = 12f
            setTextColor(palette.muted)
            setPadding(0, dp(7), 0, dp(7))
        })
        credits.addView(TextView(this).apply {
            text = "הגרסה הזו כוללת התאמות, כלי גיבוי ושחזור ושיפורי ממשק."
            textSize = 12f
            setTextColor(palette.muted)
            setPadding(0, dp(9), 0, 0)
        })
        content.addView(credits, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12)
        })

        page.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        AppDrawer.attach(this, drawer, page)
        setContentView(drawer)
        UiComponents.applySystemBars(this, drawer, palette)
    }
}
