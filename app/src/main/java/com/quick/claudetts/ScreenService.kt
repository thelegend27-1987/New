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
        h.postDelayed({ maybeListen() }, 1000)
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
            stopListening()
            tts?.speak(rest.substring(0, cut), TextToSpeech.QUEUE_ADD, null, "u${System.nanoTime()}")
            rest = rest.substring(cut).trim()
        }
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
        val t = text.lowercase(Locale.ROOT).trim().trimEnd('.', ',', '!', '?')
        when (t) {
            "stop listening", "pause" -> { Prefs.listen = false; speakNow("Paused"); return }
            "stop", "skip", "quiet", "be quiet" -> { tts?.stop(); speaking = 0; maybeListen(); return }
            "cancel", "clear", "never mind", "scratch that" -> { pending.setLength(0); setField(""); maybeListen(); return }
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

    override fun onInterrupt() { stopAll() }
    override fun onDestroy() { stopAll(); tts?.shutdown(); inst = null; super.onDestroy() }
}
