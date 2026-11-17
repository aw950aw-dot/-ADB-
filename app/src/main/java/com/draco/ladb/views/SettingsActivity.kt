package com.draco.ladb.views

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import com.draco.ladb.utils.ApiKeyVault
import com.draco.ladb.utils.AiProviderClient
import com.draco.ladb.utils.AppDrawer
import com.draco.ladb.utils.RootAccessManager
import com.draco.ladb.utils.UiTheme
import com.draco.ladb.utils.UiComponents

class SettingsActivity : AppCompatActivity() {
    private val palette get() = UiTheme.palette(this)
    private val providerIds = listOf("gemini", "openai", "anthropic", "custom")
    private val providerNames = listOf("Google Gemini", "OpenAI", "Anthropic Claude", "ספק תואם OpenAI")
    private fun dp(value: Int) = UiComponents.dp(this, value)

    private fun card(): LinearLayout = UiComponents.panel(this, palette, 15, 17)

    private fun label(value: String, size: Int = 13, muted: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(if (muted) palette.muted else palette.text)
            gravity = Gravity.RIGHT
        }

    private fun textField(hintText: String, value: String = "", secret: Boolean = false): EditText =
        EditText(this).apply {
            hint = hintText
            setText(value)
            gravity = Gravity.RIGHT
            setSingleLine(true)
            textDirection = View.TEXT_DIRECTION_LTR
            setTextColor(palette.text)
            setHintTextColor(palette.muted)
            background = UiComponents.shape(this@SettingsActivity, palette.inputBackground, 12, palette.outline)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            if (secret) {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }

    private fun providerSpinner(selected: String): Spinner {
        val spinner = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, providerNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(providerIds.indexOf(selected).takeIf { it >= 0 } ?: 0)
        return spinner
    }

    private fun selectedProvider(spinner: Spinner): String =
        providerIds.getOrNull(spinner.selectedItemPosition) ?: "gemini"

    private fun category(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        initiallyExpanded: Boolean = false,
        nested: Boolean = false,
        build: LinearLayout.() -> Unit
    ) {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiComponents.surface(
                this@SettingsActivity,
                if (nested) palette.surfaceAlt else palette.surface,
                if (nested) 15 else 19,
                palette.outline
            )
            setPadding(dp(if (nested) 12 else 15), dp(9), dp(if (nested) 12 else 15), dp(10))
            elevation = if (nested) 0f else dp(1).toFloat()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
        }
        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleColumn.addView(label(title, if (nested) 15 else 17).apply {
            typeface = Typeface.DEFAULT_BOLD
        })
        titleColumn.addView(label(subtitle, 11, true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))
        val arrow = label(if (initiallyExpanded) "⌃" else "⌄", 20).apply {
            gravity = Gravity.CENTER
            setTextColor(palette.accent)
        }
        header.addView(arrow, LinearLayout.LayoutParams(dp(32), dp(42)))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(2))
            visibility = if (initiallyExpanded) View.VISIBLE else View.GONE
        }
        build(body)
        header.setOnClickListener {
            val expand = body.visibility != View.VISIBLE
            body.visibility = if (expand) View.VISIBLE else View.GONE
            arrow.text = if (expand) "⌃" else "⌄"
        }
        outer.addView(header)
        outer.addView(body)
        parent.addView(outer, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(9)
        })
    }

    private fun refreshModelList(provider: String, key: String, modelField: EditText, status: TextView) {
        if (key.isBlank()) {
            Toast.makeText(this, "הזן מפתח API של הספק לפני טעינת מודלים", Toast.LENGTH_LONG).show()
            return
        }
        status.text = "טוען מודלים מהספק…"
        Thread {
            try {
                val models = AiProviderClient.listModels(this, provider, key)
                runOnUiThread {
                    if (models.isEmpty()) {
                        status.text = if (provider == "custom") {
                            "בספק מותאם יש להזין מזהה מודל ידנית."
                        } else {
                            "לא נמצאו מודלים זמינים."
                        }
                        return@runOnUiThread
                    }
                    status.text = "נמצאו " + models.size + " מודלים זמינים. בחר אחד מהרשימה."
                    val labels = models.map { it.second + "  •  " + it.first }.toTypedArray()
                    android.app.AlertDialog.Builder(this)
                        .setTitle("בחירת מודל")
                        .setItems(labels) { _, which -> modelField.setText(models[which].first) }
                        .setNegativeButton("סגור", null)
                        .show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    status.text = "טעינת המודלים נכשלה: " + (error.message ?: "שגיאה")
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        UiTheme.applySavedMode(this)
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("ai_settings", 0)
        val drawer = AppDrawer.create(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(14), dp(18), dp(22))
            setBackgroundColor(palette.background)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(AppDrawer.menuButton(this, drawer), LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("הגדרות", 23).apply { typeface = Typeface.DEFAULT_BOLD })
            addView(label("ניהול והתאמה אישית של ADB SMART", 11, true))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
        root.addView(header)
        root.addView(label("ההגדרות מחולקות לנושאים. פתח קטגוריה כדי לראות את האפשרויות שבתוכה.", 12, true)
            .apply { setPadding(0, dp(8), 0, dp(14)) })

        category(root, "מראה האפליקציה", "ערכת צבעים כהה או בהירה", initiallyExpanded = true) {
            addView(label(
                "בחר את המראה הנוח לך. השינוי חל על כל המסכים באפליקציה.",
                12,
                true
            ))
            addView(UiComponents.button(
                this@SettingsActivity,
                if (palette.dark) "עבור לעיצוב בהיר" else "עבור לעיצוב כהה",
                palette.accent,
                Color.WHITE,
                null,
                48,
                1
            ) {
                UiTheme.setDarkMode(this@SettingsActivity, !UiTheme.isDark(this@SettingsActivity))
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        }

        category(root, "הרשאות מערכת", "בדיקת הרשאת Root לניהול נתונים ואפליקציות") {
            val rootStatus = label(
                if (RootAccessManager.isMarkedGranted(this@SettingsActivity)) {
                    "נשמרה הרשאת Root. לחץ על הבדיקה כדי לוודא שהיא עדיין פעילה."
                } else {
                    "הרשאת Root לא הוגדרה. אפשר לבקש אותה דרך Magisk, KernelSU או מנהל Root אחר."
                },
                12,
                true
            )
            addView(rootStatus)
            lateinit var rootButton: TextView
            rootButton = UiComponents.button(
                this@SettingsActivity,
                "בדוק / בקש הרשאת Root",
                palette.accent,
                Color.WHITE,
                null,
                48,
                2
            ) {
                rootButton.isEnabled = false
                rootButton.text = "בודק הרשאת Root…"
                Thread {
                    val result = runCatching {
                        RootAccessManager.requestAccess(this@SettingsActivity)
                    }
                    runOnUiThread {
                        rootButton.isEnabled = true
                        rootButton.text = "בדוק / בקש הרשאת Root"
                        val access = result.getOrNull()
                        rootStatus.text = when {
                            access?.granted == true -> "✓ הרשאת Root פעילה עבור ADB SMART.\n" + access.output
                            access != null -> "לא התקבלה הרשאת Root.\n" + access.output
                            else -> "לא ניתן לבדוק Root: " +
                                (result.exceptionOrNull()?.message ?: "שגיאה")
                        }
                        rootStatus.setTextColor(
                            if (access?.granted == true) Color.rgb(24, 150, 105) else palette.muted
                        )
                    }
                }.start()
            }
            addView(rootButton, LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(12)
            })
            addView(label(
                "יש לאשר את בקשת הגישה בחלון מנהל ה־Root. ניתן לבטל את ההרשאה בכל עת דרך מנהל ה־Root.",
                11,
                true
            ).apply { setPadding(0, dp(7), 0, 0) })
        }

        category(root, "בינה מלאכותית", "מפתחות, מודלים וספקי AI") {
            val keys = linkedMapOf<String, EditText>()
            val providerFields = listOf(
                Triple("gemini", "Google Gemini API Key", ApiKeyVault.read(this, "gemini")),
                Triple("openai", "OpenAI API Key", ApiKeyVault.read(this, "openai")),
                Triple("anthropic", "Anthropic Claude API Key", ApiKeyVault.read(this, "anthropic")),
                Triple("custom", "מפתח API של ספק מותאם", ApiKeyVault.read(this, "custom"))
            )

            category(this, "מפתחות API", "הזנת מפתחות לספקי הבינה המלאכותית", initiallyExpanded = true, nested = true) {
                addView(label("כל מפתח נשמר אוטומטית ומוצפן במכשיר.", 11, true))
                providerFields.forEach { (id, fieldLabel, value) ->
                    addView(label(fieldLabel, 12).apply {
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(0, dp(10), 0, dp(3))
                    })
                    val input = textField(fieldLabel, value, true)
                    input.addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                        override fun afterTextChanged(s: android.text.Editable?) {
                            runCatching {
                                ApiKeyVault.save(this@SettingsActivity, s?.toString()?.trim().orEmpty(), id)
                            }
                        }
                    })
                    keys[id] = input
                    addView(input, LinearLayout.LayoutParams(-1, dp(50)))
                }
            }

            category(this, "מודלים ראשי וגיבוי", "בחירת ספק ומודל לכל תפקיד", nested = true) {
                val primaryProvider = providerSpinner(
                    prefs.getString("primary_provider", "gemini") ?: "gemini"
                )
                val primaryModel = textField(
                    "מזהה מודל ראשי",
                    prefs.getString("primary_model", prefs.getString("model", "gemini-3.8-flash"))
                        ?: "gemini-3.8-flash"
                )
                val primaryStatus = label("אפשר לטעון את רשימת המודלים הזמינה בחשבון.", 11, true)
                addView(label("ספק ראשי", 12).apply { setPadding(0, dp(7), 0, dp(3)) })
                addView(primaryProvider)
                addView(primaryModel, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(6) })
                addView(UiComponents.button(
                    this@SettingsActivity,
                    "טען מודלים זמינים למודל הראשי",
                    palette.surfaceAlt,
                    palette.text,
                    palette.outline,
                    44,
                    2
                ) {
                    val provider = selectedProvider(primaryProvider)
                    refreshModelList(
                        provider,
                        keys[provider]?.text?.toString()?.trim().orEmpty(),
                        primaryModel,
                        primaryStatus
                    )
                }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(7) })
                addView(primaryStatus.apply { setPadding(0, dp(5), 0, 0) })

                val fallbackProvider = providerSpinner(
                    prefs.getString("fallback_provider", "gemini") ?: "gemini"
                )
                val fallbackModel = textField(
                    "מזהה מודל גיבוי",
                    prefs.getString("fallback_model", "gemini-3.7-flash") ?: "gemini-3.7-flash"
                )
                val fallbackStatus = label(
                    "המודל הזה יופעל כאשר המודל הראשי אינו זמין.",
                    11,
                    true
                )
                addView(label("ספק גיבוי", 12).apply {
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(14), 0, dp(3))
                })
                addView(fallbackProvider)
                addView(fallbackModel, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(6) })
                addView(UiComponents.button(
                    this@SettingsActivity,
                    "טען מודלים זמינים למודל הגיבוי",
                    palette.surfaceAlt,
                    palette.text,
                    palette.outline,
                    44,
                    2
                ) {
                    val provider = selectedProvider(fallbackProvider)
                    refreshModelList(
                        provider,
                        keys[provider]?.text?.toString()?.trim().orEmpty(),
                        fallbackModel,
                        fallbackStatus
                    )
                }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(7) })
                addView(fallbackStatus.apply { setPadding(0, dp(5), 0, dp(3)) })

                val extraModels = textField(
                    "מודלי גיבוי נוספים, מופרדים בפסיקים",
                    prefs.getString("extra_fallback_models", "") ?: ""
                )
                addView(extraModels, LinearLayout.LayoutParams(-1, dp(50)).apply {
                    topMargin = dp(8)
                })

                val customEndpoint = textField(
                    "כתובת HTTPS של Chat Completions",
                    prefs.getString(
                        "custom_endpoint",
                        "https://api.openai.com/v1/chat/completions"
                    ) ?: ""
                )
                addView(label("כתובת API מותאמת", 12).apply {
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(14), 0, dp(3))
                })
                addView(customEndpoint, LinearLayout.LayoutParams(-1, dp(50)))

                addView(UiComponents.button(
                    this@SettingsActivity,
                    "שמור הגדרות מודלים",
                    palette.accent,
                    Color.WHITE,
                    null,
                    48,
                    1
                ) {
                    val chosenPrimaryProvider = selectedProvider(primaryProvider)
                    val chosenPrimaryModel = primaryModel.text.toString().trim()
                    val chosenFallbackProvider = selectedProvider(fallbackProvider)
                    val chosenFallbackModel = fallbackModel.text.toString().trim()
                    if (chosenPrimaryModel.isBlank() || chosenFallbackModel.isBlank()) {
                        Toast.makeText(
                            this@SettingsActivity,
                            "יש להגדיר מודל ראשי ומודל גיבוי",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        prefs.edit()
                            .putString("primary_provider", chosenPrimaryProvider)
                            .putString("primary_model", chosenPrimaryModel)
                            .putString("model", chosenPrimaryModel)
                            .putString("fallback_provider", chosenFallbackProvider)
                            .putString("fallback_model", chosenFallbackModel)
                            .putString("extra_fallback_models", extraModels.text.toString().trim())
                            .putString("custom_endpoint", customEndpoint.text.toString().trim())
                            .apply()
                        Toast.makeText(this@SettingsActivity, "הגדרות המודלים נשמרו", Toast.LENGTH_SHORT).show()
                    }
                }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(14) })
            }

            category(this, "יצירת מפתח Gemini", "פתיחת Google AI Studio", nested = true) {
                addView(label("אפשר ליצור או לנהל מפתח API אישי ב־Google AI Studio.", 12, true))
                addView(UiComponents.button(
                    this@SettingsActivity,
                    "פתח Google AI Studio",
                    palette.surfaceAlt,
                    palette.text,
                    palette.outline,
                    48,
                    1
                ) {
                    startActivity(Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://aistudio.google.com/app/apikey")
                    ))
                }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
            }
        }

        category(root, "גיבוי ושחזור נתוני האפליקציה", "שמירת הגדרות ומפתחות בקובץ מוצפן") {
            addView(label(
                "כאן אפשר ליצור גיבוי מוצפן של ההגדרות, מפתחות ה־API, הקבצים ומסדי הנתונים של ADB SMART.",
                12,
                true
            ))
            addView(UiComponents.button(
                this@SettingsActivity,
                "פתח גיבוי או שחזור נתוני ADB SMART",
                palette.accent,
                Color.WHITE,
                null,
                48,
                2
            ) {
                startActivity(Intent(this@SettingsActivity, AppDataBackupActivity::class.java))
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        }

        category(root, "אודות", "גרסה, פרטי פיתוח וקרדיטים") {
            addView(label("גרסה " + com.draco.ladb.BuildConfig.VERSION_NAME, 13))
            addView(UiComponents.button(
                this@SettingsActivity,
                "פתח מסך אודות",
                palette.surfaceAlt,
                palette.text,
                palette.outline,
                46,
                1
            ) {
                startActivity(Intent(this@SettingsActivity, AboutActivity::class.java))
            }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) })
        }

        page.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        AppDrawer.attach(this, drawer, page)
        setContentView(drawer)
        UiComponents.applySystemBars(this, drawer, palette)
    }
}
