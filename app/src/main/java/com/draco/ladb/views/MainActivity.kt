package com.draco.ladb.views

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.core.content.edit
import com.draco.ladb.R
import com.draco.ladb.utils.ApiKeyVault
import com.draco.ladb.viewmodels.MainActivityViewModel
import androidx.activity.viewModels
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStreamWriter

class MainActivity : AppCompatActivity() {
    private val vm: MainActivityViewModel by viewModels()
    private val blue=Color.rgb(23,105,210)
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var command: EditText
    private lateinit var aiRequest: EditText
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun panel(): LinearLayout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(14),dp(16),dp(14)); background=GradientDrawable().apply { setColor(Color.WHITE); cornerRadius=dp(18).toFloat(); setStroke(dp(1),Color.rgb(225,235,248)) } }
    private fun label(s:String,size:Float=15f):TextView=TextView(this).apply { text=s; textSize=size; setTextColor(Color.rgb(25,48,78)); gravity=Gravity.RIGHT }
    private fun button(s:String, action:()->Unit)=Button(this).apply { text=s; isAllCaps=false; setOnClickListener { action() } }
    private fun addSpace(h:Int=10){ root.addView(Space(this).apply { minimumHeight=dp(h) }) }
    override fun onCreate(savedInstanceState:Bundle?){ super.onCreate(savedInstanceState)
        window.statusBarColor=Color.rgb(234,243,255); window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(22)); layoutDirection=View.LAYOUT_DIRECTION_RTL; setBackgroundColor(Color.rgb(244,248,255)) }
        root.addView(label("ADB חכם",28f).apply { setTextColor(blue); typeface=Typeface.DEFAULT_BOLD })
        root.addView(label("כלי ADB פשוטים, מהירים ובעברית",14f))
        addSpace()
        root.addView(panel().apply {
            addView(label("מצב החיבור",16f).apply { typeface=Typeface.DEFAULT_BOLD })
            status=label("מתחבר ל־ADB…",14f); addView(status)
            addView(button("התחברות / שיוך מחדש"){ showPairDialog() })
            addView(button("הגדרות ופקודות מוכנות"){ startActivity(Intent(this@MainActivity,SettingsActivity::class.java)) })
        })
        addSpace()
        root.addView(label("פעולות מהירות",20f).apply { setTextColor(blue); typeface=Typeface.DEFAULT_BOLD })
        val actions=listOf(
            "פרטי המכשיר" to "getprop | grep -E 'ro.product.model|ro.build.version.release|ro.build.version.sdk'",
            "רשימת אפליקציות" to "pm list packages -3",
            "נפח אחסון" to "df -h",
            "מצב סוללה" to "dumpsys battery",
            "זיכרון RAM" to "cat /proc/meminfo",
            "כתובת IP" to "ip addr",
            "רזולוציית מסך" to "wm size",
            "ניקוי זיכרון מטמון" to "pm trim-caches 1G"
        )
        actions.chunked(2).forEach { pair -> root.addView(LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; pair.forEach { (title,cmd)-> addView(button(title){ runCommand(cmd) },LinearLayout.LayoutParams(0,dp(52),1f).apply { setMargins(dp(4),dp(4),dp(4),dp(4)) }) }; if(pair.size==1)addView(Space(this@MainActivity),LinearLayout.LayoutParams(0,1,1f)) }) }
        addSpace()
        root.addView(panel().apply {
            addView(label("סוכן AI – כתוב מה תרצה לבצע",18f).apply { setTextColor(blue); typeface=Typeface.DEFAULT_BOLD })
            aiRequest=EditText(this@MainActivity).apply { hint="לדוגמה: הצג את כל האפליקציות שהתקנתי"; minLines=2; gravity=Gravity.RIGHT or Gravity.TOP }
            addView(aiRequest)
            addView(button("הכן פקודת ADB"){ askAi() })
            addView(label("הפקודה תוצג לאישור לפני ביצוע. המפתח נשמר מוצפן במכשיר.",12f))
        })
        addSpace()
        root.addView(panel().apply {
            addView(label("פקודת ADB ידנית",18f).apply { setTextColor(blue); typeface=Typeface.DEFAULT_BOLD })
            command=EditText(this@MainActivity).apply { hint="הקלד פקודה…"; setSingleLine(true); textDirection=View.TEXT_DIRECTION_LTR }
            addView(command)
            addView(button("בצע פקודה"){ runCommand(command.text.toString()) })
        })
        addSpace()
        root.addView(panel().apply {
            addView(label("פלט המסוף",18f).apply { setTextColor(blue); typeface=Typeface.DEFAULT_BOLD })
            output=label("הפלט יופיע כאן",12f).apply { setTextIsSelectable(true); textDirection=View.TEXT_DIRECTION_LTR; gravity=Gravity.LEFT }
            addView(ScrollView(this@MainActivity).apply { addView(output); layoutParams=LinearLayout.LayoutParams(-1,dp(180)) })
            addView(button("נקה פלט"){ vm.clearOutputText() })
            addView(button("היסטוריית פקודות"){ showHistory() })
        })
        setContentView(ScrollView(this).apply { isFillViewport=true; addView(root) })
        vm.outputText.observe(this){ output.text=it.ifBlank{"ממתין לפלט…"} }
        vm.adb.running.observe(this){ ready-> status.text=if(ready==true)"מחובר ומוכן לפקודות" else "לא מחובר – הפעל ניפוי באגים אלחוטי"; status.setTextColor(if(ready==true)Color.rgb(20,140,80) else Color.rgb(190,90,35)) }
        if(vm.needsToPair()) showPairDialog() else vm.startADBServer()
    }
    override fun onResume(){ super.onResume(); val p=getSharedPreferences("quick_command",0); val pending=p.getString("command",null); if(!pending.isNullOrBlank()){ p.edit().remove("command").apply(); runCommand(pending) } }
    private fun showHistory(){
        val items=getSharedPreferences("command_history",0).getString("items","")!!.split("\n").filter{it.isNotBlank()}
        if(items.isEmpty()){ Toast.makeText(this,"עדיין אין פקודות בהיסטוריה",Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("פקודות אחרונות").setItems(items.toTypedArray()){_,which-> command.setText(items[which]); command.setSelection(command.text.length) }.setNegativeButton("סגור",null).show()
    }
    private fun runCommand(cmd:String){ if(cmd.isBlank())return; if(vm.adb.running.value!=true){ Toast.makeText(this,"תחילה יש להתחבר ל־ADB",Toast.LENGTH_SHORT).show(); return }; saveHistory(cmd); vm.adb.sendToShellProcess(cmd); command.setText("") }
    private fun saveHistory(cmd:String){ val p=getSharedPreferences("command_history",0); val old=p.getString("items","")!!.split("\n").filter{it.isNotBlank()}; p.edit().putString("items",(listOf(cmd)+old).distinct().take(80).joinToString("\n")).apply() }
    private fun askAi(){
        val request=aiRequest.text.toString().trim(); val key=ApiKeyVault.read(this)
        if(request.isBlank()){ Toast.makeText(this,"כתוב מה תרצה לבצע",Toast.LENGTH_SHORT).show(); return }
        if(key.isBlank()){ Toast.makeText(this,"הוסף מפתח Gemini בהגדרות",Toast.LENGTH_LONG).show(); startActivity(Intent(this,SettingsActivity::class.java)); return }
        aiRequest.isEnabled=false
        lifecycleScope.launch {
            val result=withContext(Dispatchers.IO){ try {
                val model=getSharedPreferences("ai_settings",0).getString("model","gemini-2.0-flash") ?: "gemini-2.0-flash"
                val url=URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
                val conn=url.openConnection() as HttpURLConnection
                conn.requestMethod="POST"; conn.connectTimeout=20000; conn.readTimeout=30000; conn.doOutput=true; conn.setRequestProperty("Content-Type","application/json")
                val history=getSharedPreferences("command_history",0).getString("items","") ?: ""
                val prompt="אתה מומחה Android ADB. המשתמש מבקש: $request. היסטוריית פקודות קודמות: $history. החזר פקודת shell אחת בלבד שמתאימה ל-ADB shell במכשיר Android. בלי Markdown ובלי הסברים. אל תציע מחיקת מידע, איפוס, השבתת אבטחה או שינוי מסוכן ללא צורך ברור. אם הבקשה מסוכנת או לא ברורה החזר בדיוק: NEEDS_CLARIFICATION"
                val body=JSONObject().put("contents",org.json.JSONArray().put(JSONObject().put("parts",org.json.JSONArray().put(JSONObject().put("text",prompt))))).put("generationConfig",JSONObject().put("temperature",0.1).put("maxOutputTokens",250))
                OutputStreamWriter(conn.outputStream).use{it.write(body.toString())}
                val stream=if(conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                val raw=stream.bufferedReader().use{it.readText()}
                if(conn.responseCode !in 200..299) throw Exception("שגיאת API ${conn.responseCode}: $raw")
                val json=JSONObject(raw)
                json.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim().removeSurrounding("```").trim()
            }catch(e:Exception){ "ERROR: ${e.message}" } }
            aiRequest.isEnabled=true
            if(result.startsWith("ERROR:")){ AlertDialog.Builder(this@MainActivity).setTitle("שגיאת AI").setMessage(result.removePrefix("ERROR:")).setPositiveButton("אישור",null).show(); return@launch }
            if(result=="NEEDS_CLARIFICATION"){ Toast.makeText(this@MainActivity,"הבקשה דורשת הבהרה או פעולה בטוחה יותר",Toast.LENGTH_LONG).show(); return@launch }
            AlertDialog.Builder(this@MainActivity).setTitle("פקודה שהסוכן הכין").setMessage(result).setNegativeButton("ביטול",null).setNeutralButton("העתק"){_,_-> (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("ADB",result)); Toast.makeText(this@MainActivity,"הפקודה הועתקה",Toast.LENGTH_SHORT).show() }.setPositiveButton("בצע"){_,_-> runCommand(result) }.show()
        }
    }
    private fun showPairDialog(){
        val view=layoutInflater.inflate(R.layout.dialog_pair,null)
        val port=view.findViewById<TextInputEditText>(R.id.port); val code=view.findViewById<TextInputEditText>(R.id.code)
        val dialog=AlertDialog.Builder(this).setTitle("שיוך ניפוי באגים אלחוטי").setMessage("בהגדרות Android פתח ניפוי באגים אלחוטי ← שיוך באמצעות קוד. השאר את החלון פתוח והזן את פרטי השיוך.").setView(view).setNegativeButton("ביטול",null).setPositiveButton("שיוך",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val p=port.text?.toString()?.trim().orEmpty(); val c=code.text?.toString()?.trim().orEmpty()
            if(p.isBlank()||c.isBlank()){ Toast.makeText(this,"יש למלא פורט וקוד",Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            dialog.dismiss()
            lifecycleScope.launch(Dispatchers.IO){
                val ok=vm.adb.pair(p,c)
                withContext(Dispatchers.Main){ if(ok){ vm.setPairedBefore(true); vm.startADBServer() } else Toast.makeText(this@MainActivity,"השיוך נכשל. בדוק את הפורט והקוד.",Toast.LENGTH_LONG).show() }
            }
        } }
        dialog.show()
    }
}