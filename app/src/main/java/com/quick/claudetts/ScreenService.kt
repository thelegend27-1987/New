package com.quick.claudetts

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Quick & dirty: watches whatever is on screen (Claude Code in a browser / app / Termux),
 * speaks new text once it stops changing (streaming), then optionally listens for a spoken
 * reply, types it into the text box and taps Send.
 */
class ScreenService : AccessibilityService(), TextToSpeech.OnInitListener {
    companion object { @Volatile var inst: ScreenService? = null }

    private val h = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = 0
    private val spokenWords = HashSet<String>()   // words of what we are currently saying (echo filter)
    private var sr: SpeechRecognizer? = null
    private var listening = false
    private val seen = HashSet<String>()
    private var primed = false
    private var lastPkg = ""

    private val STABLE_MS = 1800L
    private val processRunnable = Runnable { process() }

    override fun onServiceConnected() {
        inst = this
        tts = TextToSpeech(this, this)
        addOverlay()
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        h.postDelayed({ maybeListen() }, 1000)
        tts?.language = Locale.getDefault()
        applyVoice()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { done() }
            override fun onDone(id: String?) { done() }
            private fun done() = h.post { if (speaking > 0) speaking--; if (speaking == 0) { spokenWords.clear(); maybeListen() } }
        })
    }

    // Pick the best-quality installed voice for the current language; user can also pick one in system TTS settings.
    fun applyVoice() {
        val t = tts ?: return
        t.setSpeechRate(Prefs.rate)
        try {
            val best = t.voices?.filter { it.locale.language == Locale.getDefault().language && !it.features.contains("notInstalled") }
                ?.maxByOrNull { it.quality * 10 + (if (it.isNetworkConnectionRequired) 1 else 0) }
            if (best != null) t.voice = best
        } catch (_: Exception) {}
    }

    private fun words(s: String) = s.lowercase(Locale.ROOT).split(Regex("[^a-z0-9']+")).filter { it.length > 2 }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.packageName == packageName) return
        if (e.packageName?.toString()?.contains("systemui") == true) return
        lastPkg = e.packageName?.toString() ?: ""
        // Don't process while we are talking/listening; screen changes get picked up afterwards.
        h.removeCallbacks(processRunnable)
        h.postDelayed(processRunnable, STABLE_MS)
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (n == null || !n.isVisibleToUser) return
        if (!n.isEditable) {
            val t = n.text?.toString()?.trim()
            if (!t.isNullOrEmpty() && t.length > 1) out.add(t)
        }
        for (i in 0 until n.childCount) collect(n.getChild(i), out)
    }

    private fun process() {
        val root = rootInActiveWindow ?: return
        if (root.packageName == packageName) return
        val lines = ArrayList<String>().also { collect(root, it) }
        // Streaming messages grow in place: if a line extends one we already spoke, say only the new tail.
        val fresh = ArrayList<String>()
        for (l in lines) {
            if (l in seen) continue
            val prev = seen.filter { it.length > 25 && l.startsWith(it) }.maxByOrNull { it.length }
            seen.add(l)
            val tail = if (prev != null) l.substring(prev.length).trim() else l
            if (tail.isNotEmpty()) fresh.add(tail)
        }
        if (!primed) { primed = true; return }          // first look: just mark what's there
        if (!Prefs.read || !ttsReady || fresh.isEmpty()) { maybeListen(); return }
        // Skip short UI chrome (buttons/labels): only speak things that look like sentences.
        val say = fresh.filter { it.length > 25 || it.contains(' ') && it.length > 12 }
        for (s in say) speak(s)
        if (say.isEmpty()) maybeListen()
    }

    private fun speak(s: String) {
        // TTS has a ~4000 char limit per utterance: chunk on sentence-ish boundaries.
        var rest = s
        while (rest.isNotEmpty()) {
            val cut = if (rest.length <= 3000) rest.length
                else rest.lastIndexOfAny(charArrayOf('.', '\n', '!', '?'), 3000).let { if (it < 500) 3000 else it + 1 }
            speaking++
            spokenWords.addAll(words(rest.substring(0, cut)))
            tts?.speak(rest.substring(0, cut), TextToSpeech.QUEUE_ADD, null, "u${System.nanoTime()}")
            rest = rest.substring(cut).trim()
        }
    }

    fun resync() {
        seen.clear(); primed = false; process()
    }

    fun stopAll() {
        tts?.stop(); speaking = 0; spokenWords.clear()
        stopListening()
    }

    // ---- hands-free listening ----
    fun maybeListen() {
        if (!Prefs.listen || listening) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        listening = true
        sr?.destroy()
        sr = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(r: Bundle?) {
                    listening = false
                    val text = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                    if (!text.isNullOrEmpty()) handleVoice(text) else h.postDelayed({ maybeListen() }, 500)
                }
                override fun onError(err: Int) {
                    listening = false
                    // timeouts/no-match: just try again after a short pause
                    if (Prefs.listen) h.postDelayed({ maybeListen() }, 800)
                }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            })
        }
    }

    private fun stopListening() {
        listening = false
        sr?.cancel(); sr?.destroy(); sr = null
    }

    private val pending = StringBuilder()

    // Continuous dictation: everything you say is appended to the text box; ending with "send" submits.
    private fun handleVoice(text: String) {
        if (speaking > 0) {
            // We were talking while the mic was open: ignore our own voice echoing back, otherwise barge in.
            val w = words(text)
            val echo = w.isEmpty() || w.count { it in spokenWords } >= w.size * 0.6
            if (echo) { maybeListen(); return }
            tts?.stop(); speaking = 0; spokenWords.clear()
        }
        val t = text.lowercase(Locale.ROOT).trim().trimEnd('.', ',', '!', '?')
        when (t) {
            "stop listening", "pause" -> { Prefs.listen = false; speakNow("Paused"); return }
            "stop", "skip", "quiet", "be quiet" -> { tts?.stop(); speaking = 0; spokenWords.clear(); maybeListen(); return }
            "delete", "delete it", "delete message", "cancel", "clear", "never mind", "scratch that" -> { pending.setLength(0); setField(""); maybeListen(); return }
        }
        val m = Regex("(?i)^(.*?)[\\s,.!?]*\\bsend( it| message)?[.!?]*$").find(text.trim())
        val body = if (m != null) m.groupValues[1].trim() else text.trim()
        if (body.isNotEmpty()) { if (pending.isNotEmpty()) pending.append(' '); pending.append(body) }
        if (m != null) {
            if (pending.isEmpty()) { maybeListen(); return }
            val msg = pending.toString(); pending.setLength(0)
            if (!submit(msg)) speakNow("Couldn't find the text box")
            h.removeCallbacks(processRunnable); h.postDelayed(processRunnable, 2500)
        } else {
            setField(pending.toString())   // show what's been heard so far
        }
        maybeListen()
    }

    private fun speakNow(s: String) { speaking++; spokenWords.addAll(words(s)); tts?.speak(s, TextToSpeech.QUEUE_ADD, null, "n${System.nanoTime()}") }

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) findEditable(n.getChild(i))?.let { return it }
        return null
    }

    private fun findSend(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        val label = ((n.contentDescription?.toString() ?: "") + " " + (n.text?.toString() ?: "")).lowercase(Locale.ROOT)
        if ((n.isClickable) && n.isVisibleToUser && (label.contains("send") || label.contains("submit"))) return n
        for (i in 0 until n.childCount) findSend(n.getChild(i))?.let { return it }
        return null
    }

    private fun editable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: findEditable(root)
    }

    private fun setField(text: String): Boolean {
        val field = editable() ?: return false
        seen.add(text)
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun submit(text: String): Boolean {
        if (!setField(text)) return false
        h.postDelayed({
            val send = findSend(rootInActiveWindow)
            if (send != null) send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            else if (Build.VERSION.SDK_INT >= 30) editable()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }, 400)
        return true
    }

    private var overlay: android.view.View? = null

    // Floating Skip / Resync / Mic buttons (drag the ⋮ handle to move). Needs no extra permission.
    private fun addOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val dp = resources.displayMetrics.density
        fun b(t: String, f: () -> Unit) = android.widget.Button(this).apply {
            text = t; textSize = 12f; setOnClickListener { f() }
            minWidth = 0; minimumWidth = 0; setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
        }
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC222222.toInt()); alpha = 0.9f
        }
        val lp = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START; x = 20; y = (160 * dp).toInt() }
        val handle = android.widget.TextView(this).apply {
            text = " ⋮ "; textSize = 22f; setTextColor(android.graphics.Color.WHITE)
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
            setOnTouchListener { _, ev ->
                when (ev.action) {
                    android.view.MotionEvent.ACTION_DOWN -> { sx = ev.rawX; sy = ev.rawY; ox = lp.x; oy = lp.y }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        lp.x = ox + (ev.rawX - sx).toInt(); lp.y = oy + (ev.rawY - sy).toInt()
                        wm.updateViewLayout(row, lp)
                    }
                }
                true
            }
        }
        row.addView(handle)
        row.addView(b("Skip") { stopAll(); h.postDelayed({ maybeListen() }, 300) })
        row.addView(b("Resync") { resync() })
        val mic = b(if (Prefs.listen) "Mic on" else "Mic off") {}
        mic.setOnClickListener {
            Prefs.listen = !Prefs.listen
            mic.text = if (Prefs.listen) "Mic on" else "Mic off"
            if (Prefs.listen) maybeListen() else stopListening()
        }
        row.addView(mic)
        overlay = row
        wm.addView(row, lp)
    }

    private fun removeOverlay() {
        overlay?.let { (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(it) }
        overlay = null
    }

    override fun onInterrupt() { stopAll() }
    override fun onDestroy() { removeOverlay(); stopAll(); tts?.shutdown(); inst = null; super.onDestroy() }
}
