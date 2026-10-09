package com.quick.claudetts

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 80, 40, 40) }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }.also { col.addView(it) }
        col.addView(TextView(this).apply {
            text = "1) Mic permission  2) Enable 'Claude TTS' accessibility service  3) Open Claude Code on screen  4) Toggle hands-free"
            setTextColor(Color.BLACK)
        })
        btn("1. Grant mic permission") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
        btn("2. Open accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        val sw = Switch(this).apply { text = "Read screen aloud"; isChecked = true }
        sw.setOnCheckedChangeListener { _, c -> Prefs.read = c; if (!c) ScreenService.inst?.stopAll() }
        col.addView(sw)
        val sw2 = Switch(this).apply { text = "Hands-free voice reply (listens after speaking)"; isChecked = true }
        sw2.setOnCheckedChangeListener { _, c -> Prefs.listen = c; if (!c) ScreenService.inst?.stopAll() else ScreenService.inst?.maybeListen() }
        col.addView(sw2)
        btn("Change voice (system speech settings)") {
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        col.addView(TextView(this).apply { text = "Speech speed"; setTextColor(Color.BLACK) })
        col.addView(SeekBar(this).apply {
            max = 30; progress = ((Prefs.rate - 0.5f) * 10).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { Prefs.rate = 0.5f + p / 10f; ScreenService.inst?.applyVoice() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        col.addView(TextView(this).apply { text = "Gemini voice (optional): paste a Gemini API key from aistudio.google.com/apikey. Empty = phone voice."; setTextColor(Color.BLACK) })
        Prefs.load(this)
        val key = EditText(this).apply { hint = "Gemini API key"; setText(Prefs.geminiKey); setSingleLine() }
        val voice = EditText(this).apply { hint = "Voice name (e.g. Kore, Puck, Charon, Aoede, Fenrir)"; setText(Prefs.geminiVoice); setSingleLine() }
        val model = EditText(this).apply { hint = "Model"; setText(Prefs.geminiModel); setSingleLine() }
        col.addView(key); col.addView(voice); col.addView(model)
        btn("Save Gemini settings") {
            Prefs.geminiKey = key.text.toString().trim()
            Prefs.geminiVoice = voice.text.toString().trim().ifEmpty { "Kore" }
            Prefs.geminiModel = model.text.toString().trim().ifEmpty { "gemini-2.5-flash-preview-tts" }
            Prefs.save(this)
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
        btn("Skip / stop speaking") { ScreenService.inst?.stopAll() }
        btn("Re-sync (mark current screen as already read)") { ScreenService.inst?.resync() }
        col.addView(TextView(this).apply {
            text = "Always listening. Talk; words appear in the Claude text box. End with \"send\" to submit. Say \"delete\" to wipe the draft, \"stop\" to silence reading, \"pause\" to stop listening."
            setTextColor(Color.DKGRAY)
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }
}

object Prefs {
    @Volatile var geminiKey = ""
    @Volatile var geminiOn = true
    @Volatile var geminiVoice = "Kore"
    @Volatile var geminiModel = "gemini-2.5-flash-preview-tts"
    fun load(c: android.content.Context) {
        val p = c.getSharedPreferences("p", 0)
        geminiKey = p.getString("gk", "") ?: ""
        geminiOn = p.getBoolean("go", true)
        geminiVoice = p.getString("gv", "Kore") ?: "Kore"
        geminiModel = p.getString("gm", "gemini-2.5-flash-preview-tts") ?: "gemini-2.5-flash-preview-tts"
    }
    fun save(c: android.content.Context) {
        c.getSharedPreferences("p", 0).edit().putString("gk", geminiKey).putString("gv", geminiVoice).putString("gm", geminiModel).putBoolean("go", geminiOn).apply()
    }
    @Volatile var read = true
    @Volatile var listen = true
    @Volatile var rate = 1.2f
}
