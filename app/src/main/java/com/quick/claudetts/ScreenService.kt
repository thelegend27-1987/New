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
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        tts?.language = Locale.getDefault()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { done() }
            override fun onDone(id: String?) { done() }
            private fun done() = h.post { if (speaking > 0) speaking--; if (speaking == 0) maybeListen() }
        })
    }

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
        val fresh = lines.filter { seen.add(it) }
        if (!primed) { primed = true; return }          // first look: just mark what's there
        if (!Prefs.read || !ttsReady || fresh.isEmpty()) { maybeListen(); return }
        // Skip short UI chrome (buttons/labels): only speak things that look like sentences.
        val say = fresh.filter { it.length > 25 || it.contains(' ') && it.length > 12 }
        for (s in say) speak(s)
        if (say.isEmpty()) maybeListen()
    }

    private fun speak(s: String) {
        speaking++
        tts?.speak(s.take(3500), TextToSpeech.QUEUE_ADD, null, "u${System.nanoTime()}")
    }

    fun resync() {
        seen.clear(); primed = false; process()
    }

    fun stopAll() {
        tts?.stop(); speaking = 0
        stopListening()
    }

    // ---- hands-free listening ----
    fun maybeListen() {
        if (!Prefs.listen || speaking > 0 || listening) return
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
            })
        }
    }

    private fun stopListening() {
        listening = false
        sr?.cancel(); sr?.destroy(); sr = null
    }

    private fun handleVoice(text: String) {
        val t = text.lowercase(Locale.ROOT)
        when {
            t == "stop listening" || t == "pause" -> { Prefs.listen = false; speakNow("Paused"); return }
            t == "stop" || t == "skip" || t == "quiet" -> { tts?.stop(); speaking = 0; maybeListen(); return }
        }
        val ok = sendToScreen(text)
        if (!ok) speakNow("Couldn't find the text box")
        else { // let the response stream in, then it'll be read and we listen again
            h.removeCallbacks(processRunnable); h.postDelayed(processRunnable, 2500)
        }
    }

    private fun speakNow(s: String) { speaking++; tts?.speak(s, TextToSpeech.QUEUE_ADD, null, "n${System.nanoTime()}") }

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

    private fun sendToScreen(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: findEditable(root) ?: return false
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        // Mark our own typed text as seen so it isn't read back.
        seen.add(text)
        h.postDelayed({
            val r = rootInActiveWindow
            val send = findSend(r)
            if (send != null) send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            else if (Build.VERSION.SDK_INT >= 30) field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }, 400)
        return true
    }

    override fun onInterrupt() { stopAll() }
    override fun onDestroy() { stopAll(); tts?.shutdown(); inst = null; super.onDestroy() }
}
