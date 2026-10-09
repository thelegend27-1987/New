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
        val sw2 = Switch(this).apply { text = "Hands-free voice reply (listens after speaking)"; isChecked = false }
        sw2.setOnCheckedChangeListener { _, c -> Prefs.listen = c; if (!c) ScreenService.inst?.stopAll() else ScreenService.inst?.maybeListen() }
        col.addView(sw2)
        btn("Skip / stop speaking") { ScreenService.inst?.stopAll() }
        btn("Re-sync (mark current screen as already read)") { ScreenService.inst?.resync() }
        col.addView(TextView(this).apply {
            text = "Voice: say your reply. Say 'stop listening' to pause hands-free. Reply is typed into the focused/first text box and Send is tapped."
            setTextColor(Color.DKGRAY)
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }
}

object Prefs {
    @Volatile var read = true
    @Volatile var listen = false
}
