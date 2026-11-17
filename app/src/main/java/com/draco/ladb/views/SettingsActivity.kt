package com.draco.ladb.views

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import com.draco.ladb.utils.ApiKeyVault
import com.draco.ladb.utils.AiProviderClient
import com.draco.ladb.utils.RootAccessManager
import com.draco.ladb.utils.UiTheme
import com.draco.ladb.utils.UiComponents

class SettingsActivity : AppCompatActivity() {
    private val palette get() = UiTheme.palette(this)
    private val blue get() = palette.accent
    private val providerIds = listOf("gemini", "openai", "anthropic", "custom")
    private val providerNames = listOf("Google Gemini", "OpenAI", "Anthropic Claude", "ספק תואם OpenAI")
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun card(): LinearLayout = UiComponents.panel(this, palette, 16, 16)

    private fun heading(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(palette.accent)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.RIGHT
        setPadding(0, dp(18), 0, dp(8))
    }

    private fun textField(hintText: String, value: String = "", secret: Boolean = false): EditText =
        EditText(this).apply {
            hint = hintText
            setText(value)
            gravity = Gravity.RIGHT
            setSingleLine(true)
            textDirection = View.TEXT_DIRECTION_LTR
            if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(8), dp(8), dp(8), dp(8))
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
                        status.text = if (provider == "custom") "בספק מותאם יש להזין מזהה מודל ידנית." else "לא נמצאו מודלים זמינים."
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
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "טעינת המודלים נכשלה: " + (e.message ?: "שגיאה")
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        UiTheme.applySavedMode(this)
        super.onCreate(savedInstanceState)
        window.statusBarColor = palette.statusBar
        window.navigationBarColor = palette.navigationBar
        window.decorView.systemUiVisibility = if (palette.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val prefs = getSharedPreferences("ai_settings", 0)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(18), dp(18), dp(22))
            setBackgroundColor(palette.background)
        }
        root.addView(TextView(this).apply {
            text = "הגדרות"
            textSize = 27f
            setTextColor(palette.text)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.RIGHT
        })
        root.addView(heading("מראה האפליקציה"))
        root.addView(card().apply {
            addView(Button(this@SettingsActivity).apply {
                text = if (palette.dark) "עבור לעיצוב בהיר" else "עבור לעיצוב כהה"
                isAllCaps = false
                setOnClickListener {
                    UiTheme.setDarkMode(this@SettingsActivity, !UiTheme.isDark(this@SettingsActivity))
                }
            })
        })

        root.addView(heading("הרשאת Root"))
        val rootStatus = TextView(this).apply {
            text = if (RootAccessManager.isMarkedGranted(this@SettingsActivity)) {
                "נשמרה הרשאת Root. לחץ על בדיקה כדי לוודא שהיא עדיין פעילה."
            } else {
                "הרשאת Root לא הוגדרה. ניתן לבקש גישה דרך Magisk, KernelSU או מנהל Root אחר."
            }
            textSize = 12f
            setTextColor(palette.muted)
            gravity = Gravity.RIGHT
        }
        root.addView(card().apply {
            addView(rootStatus)
            addView(Button(this@SettingsActivity).apply {
                text = "בדוק / בקש הרשאת Root"
                isAllCaps = false
                setOnClickListener {
                    isEnabled = false
                    text = "בודק הרשאת Root…"
                    Thread {
                        val result = runCatching { RootAccessManager.requestAccess(this@SettingsActivity) }
                        runOnUiThread {
                            isEnabled = true
                            text = "בדוק / בקש הרשאת Root"
                            val access = result.getOrNull()
                            rootStatus.text = when {
                                access?.granted == true -> "✓ הרשאת Root פעילה עבור ADB חכם.\n" + access.output
                                access != null -> "לא התקבלה הרשאת Root.\n" + access.output
                                else -> "לא ניתן לבדוק Root: " + (result.exceptionOrNull()?.message ?: "שגיאה")
                            }
                            rootStatus.setTextColor(if (access?.granted == true) Color.rgb(24, 150, 105) else palette.muted)
                        }
                    }.start()
                }
            })
            addView(TextView(this@SettingsActivity).apply {
                text = "יש לאשר את הבקשה בחלון מנהל ה־Root. ההרשאה ניתנת לאפליקציה עצמה; ניתן לבטל אותה בכל עת דרך מנהל ה־Root."
                textSize = 11f
                setTextColor(palette.muted)
                gravity = Gravity.RIGHT
            })
        })

        root.addView(heading("ספקי AI ומפתחות API"))

        val keys = linkedMapOf<String, EditText>()
        val providerFields = listOf(
            Triple("gemini", "Google Gemini API Key", ApiKeyVault.read(this, "gemini")),
            Triple("openai", "OpenAI API Key", ApiKeyVault.read(this, "openai")),
            Triple("anthropic", "Anthropic API Key", ApiKeyVault.read(this, "anthropic")),
            Triple("custom", "מפתח API של ספק מותאם", ApiKeyVault.read(this, "custom"))
        )
        root.addView(card().apply {
            addView(TextView(this@SettingsActivity).apply {
                text = "כל מפתח נשמר אוטומטית בזמן ההקלדה ומוצפן במכשיר."
                textSize = 12f
                setTextColor(palette.muted)
                gravity = Gravity.RIGHT
            })
            providerFields.forEach { (id, label, value) ->
                addView(TextView(this@SettingsActivity).apply {
                    text = label
                    textSize = 13f
                    setTextColor(palette.text)
                    gravity = Gravity.RIGHT
                    setPadding(0, dp(10), 0, 0)
                })
                val input = textField(label, value, true)
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
                addView(input)
            }
        })

        root.addView(heading("מודל ראשי"))
        val primaryProvider = providerSpinner(prefs.getString("primary_provider", "gemini") ?: "gemini")
        val primaryModel = textField(
            "מזהה מודל ראשי",
            prefs.getString("primary_model", prefs.getString("model", "gemini-3.8-flash")) ?: "gemini-3.8-flash"
        )
        val primaryStatus = TextView(this).apply {
            text = "בחר ספק ומודל. אפשר לטעון את רשימת המודלים האמיתית מהחשבון."
            textSize = 12f
            setTextColor(palette.muted)
            gravity = Gravity.RIGHT
        }
        root.addView(card().apply {
            addView(TextView(this@SettingsActivity).apply { text = "ספק ראשי"; gravity = Gravity.RIGHT; setTextColor(palette.text) })
            addView(primaryProvider)
            addView(primaryModel)
            addView(Button(this@SettingsActivity).apply {
                text = "טען מודלים זמינים ובחר"
                isAllCaps = false
                setOnClickListener {
                    val provider = selectedProvider(primaryProvider)
                    refreshModelList(provider, keys[provider]?.text?.toString()?.trim().orEmpty(), primaryModel, primaryStatus)
                }
            })
            addView(primaryStatus)
        })

        root.addView(heading("מודל גיבוי אוטומטי"))
        val fallbackProvider = providerSpinner(prefs.getString("fallback_provider", "gemini") ?: "gemini")
        val fallbackModel = textField(
            "מזהה מודל גיבוי",
            prefs.getString("fallback_model", "gemini-3.7-flash") ?: "gemini-3.7-flash"
        )
        val fallbackStatus = TextView(this).apply {
            text = "כאשר המודל הראשי עמוס או מחזיר שגיאת שרת זמנית, האפליקציה תנסה את מודל הגיבוי."
            textSize = 12f
            setTextColor(palette.muted)
            gravity = Gravity.RIGHT
        }
        val extraModels = textField(
            "מודלי גיבוי נוספים, מופרדים בפסיקים",
            prefs.getString("extra_fallback_models", "") ?: ""
        )
        root.addView(card().apply {
            addView(TextView(this@SettingsActivity).apply { text = "ספק גיבוי"; gravity = Gravity.RIGHT; setTextColor(palette.text) })
            addView(fallbackProvider)
            addView(fallbackModel)
            addView(Button(this@SettingsActivity).apply {
                text = "טען מודלי גיבוי זמינים"
                isAllCaps = false
                setOnClickListener {
                    val provider = selectedProvider(fallbackProvider)
                    refreshModelList(provider, keys[provider]?.text?.toString()?.trim().orEmpty(), fallbackModel, fallbackStatus)
                }
            })
            addView(fallbackStatus)
            addView(TextView(this@SettingsActivity).apply {
                text = "אפשר להוסיף כמה מזהי מודלים מאותו ספק. האפליקציה תנסה אותם לפי הסדר."
                textSize = 12f
                setTextColor(palette.muted)
                gravity = Gravity.RIGHT
            })
            addView(extraModels)
        })

        root.addView(heading("ספק API מותאם"))
        val customEndpoint = textField(
            "כתובת HTTPS מלאה, לדוגמה https://api.example.com/v1/chat/completions",
            prefs.getString("custom_endpoint", "https://api.openai.com/v1/chat/completions") ?: ""
        )
        root.addView(card().apply {
            addView(TextView(this@SettingsActivity).apply {
                text = "מאפשר להשתמש בשירותים נוספים שתומכים בממשק OpenAI Chat Completions."
                textSize = 12f
                setTextColor(Color.rgb(85, 105, 130))
                gravity = Gravity.RIGHT
            })
            addView(customEndpoint)
        })

        root.addView(Button(this).apply {
            text = "שמירת הגדרות"
            isAllCaps = false
            setOnClickListener {
                val chosenPrimaryProvider = selectedProvider(primaryProvider)
                val chosenPrimaryModel = primaryModel.text.toString().trim()
                val chosenFallbackProvider = selectedProvider(fallbackProvider)
                val chosenFallbackModel = fallbackModel.text.toString().trim()
                if (chosenPrimaryModel.isBlank() || chosenFallbackModel.isBlank()) {
                    Toast.makeText(this@SettingsActivity, "יש להגדיר מודל ראשי ומודל גיבוי", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                prefs.edit()
                    .putString("primary_provider", chosenPrimaryProvider)
                    .putString("primary_model", chosenPrimaryModel)
                    .putString("model", chosenPrimaryModel)
                    .putString("fallback_provider", chosenFallbackProvider)
                    .putString("fallback_model", chosenFallbackModel)
                    .putString("extra_fallback_models", extraModels.text.toString().trim())
                    .putString("custom_endpoint", customEndpoint.text.toString().trim())
                    .apply()
                Toast.makeText(this@SettingsActivity, "הגדרות AI נשמרו", Toast.LENGTH_SHORT).show()
                finish()
            }
        })

        root.addView(heading("יצירת מפתח Gemini"))
        root.addView(card().apply {
            addView(TextView(this@SettingsActivity).apply {
                text = "אפשר ליצור או לנהל מפתח API אישי ב־Google AI Studio."
                textSize = 13f
                setTextColor(palette.muted)
                gravity = Gravity.RIGHT
            })
            addView(Button(this@SettingsActivity).apply {
                text = "פתיחת Google AI Studio – יצירת API Key"
                isAllCaps = false
                setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey")))
                }
            })
        })

        root.addView(heading("גיבוי נתוני ADB SMART"))
        root.addView(card().apply {
            addView(TextView(this@SettingsActivity).apply {
                text = "גיבוי מוצפן של ההגדרות, מפתחות ה־API, הקבצים ומסדי הנתונים. אפשר לשחזר אחרי התקנה מחדש באמצעות הסיסמה שבחרת."
                textSize = 12f
                setTextColor(palette.muted)
                gravity = Gravity.RIGHT
            })
            addView(Button(this@SettingsActivity).apply {
                text = "גיבוי או שחזור נתוני האפליקציה"
                isAllCaps = false
                setOnClickListener {
                    startActivity(Intent(this@SettingsActivity, AppDataBackupActivity::class.java))
                }
            })
        })

        root.addView(heading("אודות"))
        root.addView(card().apply {
            addView(TextView(this@SettingsActivity).apply {
                text = "האפליקציה פותחה על ידי @האמריקאי מפורום מתמחים טופ."
                textSize = 15f
                setTextColor(palette.text)
                gravity = Gravity.RIGHT
            })
            addView(Button(this@SettingsActivity).apply {
                text = "לפרופיל @האמריקאי במתמחים טופ"
                isAllCaps = false
                setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mitmachim.top/user/%D7%94%D7%90%D7%9E%D7%A8%D7%99%D7%A7%D7%90%D7%99")))
                }
            })
        })

        val pageScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(pageScroll)
        UiComponents.applySystemBars(this, pageScroll, palette)
    }
}