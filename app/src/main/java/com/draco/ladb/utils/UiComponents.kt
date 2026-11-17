package com.draco.ladb.utils

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Shared visual primitives used by every custom screen in ADB SMART. */
object UiComponents {
    fun dp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun shape(
        context: android.content.Context,
        color: Int,
        radiusDp: Int = 18,
        strokeColor: Int? = null
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(context, radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(context, 1), strokeColor)
    }

    fun surface(
        context: android.content.Context,
        color: Int,
        radiusDp: Int = 18,
        strokeColor: Int? = null,
        clickable: Boolean = false
    ): android.graphics.drawable.Drawable {
        val content = shape(context, color, radiusDp, strokeColor)
        if (!clickable) return content
        val ripple = ColorStateList.valueOf(Color.argb(42, 105, 165, 220))
        return RippleDrawable(ripple, content, shape(context, Color.WHITE, radiusDp))
    }

    fun panel(
        context: android.content.Context,
        palette: UiPalette,
        paddingDp: Int = 16,
        radiusDp: Int = 20
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(context, paddingDp)
        val inset = dp(context, 2)
        setPadding(pad, (pad - inset).coerceAtLeast(0), pad, (pad - inset).coerceAtLeast(0))
        background = shape(context, palette.surface, radiusDp, palette.outline)
        elevation = dp(context, 2).toFloat()
        clipToOutline = true
    }

    fun button(
        context: android.content.Context,
        label: String,
        backgroundColor: Int,
        textColor: Int = Color.WHITE,
        outlineColor: Int? = null,
        minHeightDp: Int = 50,
        maxLines: Int = 2,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        textSize = if (maxLines > 1) 13f else 14f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(textColor)
        setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
        minHeight = dp(context, minHeightDp.coerceAtLeast(46))
        minWidth = 0
        this.maxLines = maxLines.coerceIn(1, 2)
        setLineSpacing(dp(context, 2).toFloat(), 1.02f)
        ellipsize = android.text.TextUtils.TruncateAt.END
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        background = surface(context, backgroundColor, 16, outlineColor, clickable = true)
        elevation = dp(context, 1).toFloat()
        isClickable = true
        isFocusable = true
        contentDescription = label
        setOnClickListener { onClick() }
    }

    fun applySystemBars(activity: Activity, root: View, palette: UiPalette) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = palette.statusBar
        window.navigationBarColor = palette.navigationBar
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = !palette.dark
        controller.isAppearanceLightNavigationBars = !palette.dark
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom
            )
            WindowInsetsCompat.CONSUMED
        }
        root.post { ViewCompat.requestApplyInsets(root) }
    }
}
