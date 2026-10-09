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
        col.addView(TextView(this).apply {
            text = "Cloud voices (optional). ElevenLabs: key from elevenlabs.io (Profile > API keys). Gemini: key from aistudio.google.com/apikey. Empty = phone voice."
            setTextColor(Color.BLACK)
        })
        Prefs.load(this)
        fun field(h: String, v: String) = EditText(this).apply { hint = h; setText(v); setSingleLine() }.also { col.addView(it) }
        val eKey = field("ElevenLabs API key", Prefs.elevenKey)
        val eVoice = field("ElevenLabs voice ID (default Rachel)", Prefs.elevenVoice)
        val eModel = field("ElevenLabs model", Prefs.elevenModel)
        val gKey = field("Gemini API key", Prefs.geminiKey)
        val gVoice = field("Gemini voice name (Kore, Puck, Charon, Aoede...)", Prefs.geminiVoice)
        val gModel = field("Gemini model", Prefs.geminiModel)
        val engineBtn = btn("") { }
        fun engineLabel() { engineBtn.text = "Voice engine: ${Prefs.engine} (tap to change)" }
        engineLabel()
        engineBtn.setOnClickListener { Prefs.cycleEngine(); Prefs.save(this); engineLabel() }
        btn("Save voice settings") {
            Prefs.elevenKey = eKey.text.toString().trim()
            Prefs.elevenVoice = eVoice.text.toString().trim().ifEmpty { Prefs.DEFAULT_ELEVEN_VOICE }
            Prefs.elevenModel = eModel.text.toString().trim().ifEmpty { "eleven_flash_v2_5" }
            Prefs.geminiKey = gKey.text.toString().trim()
            Prefs.geminiVoice = gVoice.text.toString().trim().ifEmpty { "Kore" }
            Prefs.geminiModel = gModel.text.toString().trim().ifEmpty { "gemini-2.5-flash-preview-tts" }
            if (Prefs.engine == "phone") Prefs.engine = if (Prefs.elevenKey.isNotEmpty()) "eleven" else if (Prefs.geminiKey.isNotEmpty()) "gemini" else "phone"
            Prefs.save(this); engineLabel()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
        val status = TextView(this).apply { setTextColor(Color.YELLOW) }
        btn("Test current voice engine") {
            Prefs.load(this)
            if (!Prefs.cloudReady()) { status.text = "Engine is '${Prefs.engine}' or its key is missing: nothing cloud to test"; return@btn }
            status.text = "Testing ${Prefs.engine}..."
            lateinit var sp: CloudSpeaker
            sp = CloudSpeaker(
                onChunkDone = { runOnUiThread { if (sp.lastError.isEmpty()) status.text = "${Prefs.engine} OK (you should have heard it)" } },
                onFail = { _ ->
                    val err = sp.lastError
                    Thread {
                        val extra = try { if (Prefs.engine == "gemini") CloudSpeaker.listTtsModels() else "" } catch (e: Exception) { "" }
                        runOnUiThread { status.text = "${Prefs.engine} FAILED: $err\n$extra" }
                    }.start()
                }
            )
            sp.enqueue("Hello, this is the cloud voice.")
        }
        col.addView(status)
        btn("Repeat last paragraph") { ScreenService.inst?.repeatLast() }
        btn("Skip / stop speaking") { ScreenService.inst?.stopAll() }
        btn("Re-sync (mark current screen as already read)") { ScreenService.inst?.resync() }
        col.addView(TextView(this).apply {
            text = "Always listening. Talk; words appear in the Claude text box. End with \"send\" to submit. Say \"delete\" to wipe the draft, \"stop\" to silence reading, \"pause\" to stop listening."
            setTextColor(Color.DKGRAY)
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }
}
