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
    private lateinit var gemini: CloudSpeaker
    init {
        gemini = CloudSpeaker(
            onChunkDone = { h.post { chunkFinished(); if (speaking > 0) speaking--; if (speaking == 0) { spokenWords.clear(); allDone(); maybeListen() } } },
            onFail = { text ->
                // Phone voice reads this chunk; block the Gemini worker until done so order is kept.
                val latch = java.util.concurrent.CountDownLatch(1)
                fallbackLatch = latch
                h.post { toastOnce("Cloud voice failed, phone voice for this bit: " + gemini.lastError); tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "f${System.nanoTime()}") }
                latch.await(90, java.util.concurrent.TimeUnit.SECONDS)
            }
        )
    }
    // Paragraph tracking so "repeat" can replay the paragraph being spoken, then carry on with the rest.
    private val paras = ArrayList<Pair<String, Int>>()   // cleaned text, number of audio chunks
    private var doneChunks = 0
    private var lastPara = ""
    private var ignoreUntil = 0L

    private fun chunkFinished() {
        if (System.currentTimeMillis() < ignoreUntil) return
        doneChunks++
    }
    private fun allDone() {
        paras.lastOrNull()?.let { lastPara = it.first }
        paras.clear(); doneChunks = 0
    }
    private fun currentIndex(): Int {
        var acc = 0
        for ((i, p) in paras.withIndex()) { acc += p.second; if (doneChunks < acc) return i }
        return -1
    }

    fun repeatLast() {
        val idx = currentIndex()
        val texts = if (idx >= 0) paras.subList(idx, paras.size).map { it.first } else listOfNotNull(lastPara.takeIf { it.isNotEmpty() })
        if (texts.isEmpty()) return
        stopSpeech(); speaking = 0; spokenWords.clear(); paras.clear(); doneChunks = 0
        ignoreUntil = System.currentTimeMillis() + 600
        texts.forEach { speakClean(it) }
    }

    @Volatile private var fallbackLatch: java.util.concurrent.CountDownLatch? = null
    private var lastToast = 0L
    private fun toastOnce(m: String) {
        if (System.currentTimeMillis() - lastToast < 15000) return
        lastToast = System.currentTimeMillis()
        android.widget.Toast.makeText(this, m.take(200), android.widget.Toast.LENGTH_LONG).show()
    }
    private fun useGemini() = Prefs.cloudReady()
    private fun say(chunk: String, id: String) {
        if (useGemini()) gemini.enqueue(chunk) else tts?.speak(chunk, TextToSpeech.QUEUE_ADD, null, id)
    }
    private fun stopSpeech() { tts?.stop(); gemini.stop(); fallbackLatch?.countDown() }
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
        Prefs.load(this)
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
            override fun onError(id: String?) { done(id) }
            override fun onDone(id: String?) { done(id) }
            private fun done(id: String?) {
                if (id?.startsWith("f") == true) { fallbackLatch?.countDown(); return }   // Gemini-fallback chunk: not counted
                finishOne()
            }
            private fun finishOne() = h.post { chunkFinished(); if (speaking > 0) speaking--; if (speaking == 0) { spokenWords.clear(); allDone(); maybeListen() } }
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
            if (!t.isNullOrEmpty() && t.length > 1 && !(Prefs.chatOnly && looksLikeToolLine(n, t))) out.add(t)
        }
        for (i in 0 until n.childCount) collect(n.getChild(i), out)
    }

    private val toolStart = Regex("""(?i)^(ran|run|running|read|reading|edit|edited|editing|write|wrote|writing|bash|grep|glob|search(ed|ing)?|fetch(ed|ing)?|list(ed|ing)?|used|using|called|calling|tool|exit code|pushed to|committed|\$ |> |mcp__|task |todo|worked for|loaded|loading|updated|created|deleted|added|checking|checked)\b""")
    private val cmdStart = Regex("""^(git|gh|gradle|\./gradlew|npm|npx|yarn|cd|ls|cat|sed|awk|python3?|pip3?|curl|wget|sleep|echo|mkdir|rm|mv|cp|rg|find|sh|bash|sudo|apt|export|chmod|tail|head|diff|grep|until|for|while|if)\s""")

    /** Heuristic: is this a tool call / command / status row rather than a chat message? */
    private fun looksLikeToolLine(n: AccessibilityNodeInfo, t: String): Boolean {
        if (toolStart.containsMatchIn(t) && t.length < 400) return true
        if (cmdStart.containsMatchIn(t)) return true
        if (t.contains("&&") || t.contains(" | ") || t.contains("--") && t.length < 200 && !t.contains(". ")) return true
        val words = t.split(Regex("\\s+"))
        if (t.count { it == '/' } >= 2 && words.size < 8) return true          // path-ish
        val odd = t.count { !it.isLetterOrDigit() && !it.isWhitespace() && it !in ".,'!?:;-()\"" }
        if (t.length > 12 && odd * 100 / t.length > 20) return true            // symbol soup: code / diffs
        if (n.isClickable && t.length < 160 && !t.trimEnd().endsWith(".") ) return true  // collapsible tool rows
        return false
    }

    private fun dump(n: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (n == null || sb.length > 14000) return
        val cls = n.className?.toString()?.substringAfterLast('.') ?: "?"
        val txt = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").replace('\n', ' ').take(100)
        val fl = (if (n.isClickable) "c" else "") + (if (n.isEditable) "e" else "") + (if (!n.isVisibleToUser) "h" else "")
        if (txt.isNotEmpty() || n.childCount == 0)
            sb.append("  ".repeat(depth)).append(cls).append('[').append(fl).append(']').append(n.viewIdResourceName ?: "").append(' ').append(txt).append('\n')
        for (i in 0 until n.childCount) dump(n.getChild(i), sb, depth + 1)
    }

    fun copyDump() {
        val sb = StringBuilder()
        dump(rootInActiveWindow, sb, 0)
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("screen", sb.toString()))
        android.widget.Toast.makeText(this, "Screen dump copied (${sb.length} chars)", android.widget.Toast.LENGTH_SHORT).show()
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

    // Don't read URLs / hashes out loud: "https://github.com/foo/bar/pull/3" -> "GitHub link".
    private val urlRe = Regex("""(?i)\b(?:https?://|www\.)[^\s)>\]"']+""")
    private val pathRe = Regex("""(?<![\w:/])(?:~/|\.\.?/|/)?(?:[\w.@-]+/)+[\w.@-]*""")
    private val hashRe = Regex("""\b[0-9a-f]{12,}\b""", RegexOption.IGNORE_CASE)
    private val friendly = mapOf(
        "github.com" to "GitHub", "claude.ai" to "Claude", "anthropic.com" to "Anthropic",
        "google.com" to "Google", "youtube.com" to "YouTube", "stackoverflow.com" to "Stack Overflow",
        "docs.google.com" to "Google Docs", "npmjs.com" to "npm", "pypi.org" to "PyPI"
    )

    private fun clean(s: String): String {
        var t = urlRe.replace(s) { m ->
            val host = m.value.replace(Regex("(?i)^(https?://)?(www\\.)?"), "").substringBefore('/').substringBefore('?').lowercase(Locale.ROOT)
            val name = friendly.entries.firstOrNull { host == it.key || host.endsWith("." + it.key) }?.value
                ?: host.split('.').let { if (it.size >= 2) it[it.size - 2] else host }
            "$name link"
        }
        // File paths -> just the last part: "app/src/main/java/com/x/Foo.kt" -> "Foo.kt"
        t = pathRe.replace(t) { m ->
            val p = m.value
            val last = p.trimEnd('/').substringAfterLast('/')
            val looksLikePath = p.count { it == '/' } >= 2 || p.startsWith("/") || p.startsWith("./") || p.startsWith("~") || last.contains('.')
            if (looksLikePath && last.isNotEmpty()) last else p
        }
        t = hashRe.replace(t, "hash")
        return t.replace(Regex("[`*#]+"), "").trim()
    }

    private fun speak(raw: String) {
        val s = clean(raw)
        if (s.isEmpty()) return
        speakClean(s)
    }

    private fun speakClean(s: String) {
        var n = 0
        // TTS has a ~4000 char limit per utterance: chunk on sentence-ish boundaries.
        var rest = s
        while (rest.isNotEmpty()) {
            val lim = if (useGemini()) 700 else 3000
            val cut = if (rest.length <= lim) rest.length
                else rest.lastIndexOfAny(charArrayOf('.', '\n', '!', '?'), lim).let { if (it < lim / 6) lim else it + 1 }
            n++
            speaking++
            spokenWords.addAll(words(rest.substring(0, cut)))
            say(rest.substring(0, cut), "u${System.nanoTime()}")
            rest = rest.substring(cut).trim()
        }
        paras.add(s to n)
    }

    fun resync() {
        seen.clear(); primed = false; process()
    }

    fun stopAll() {
        stopSpeech(); speaking = 0; spokenWords.clear()
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
            stopSpeech(); speaking = 0; spokenWords.clear()
        }
        val t = text.lowercase(Locale.ROOT).trim().trimEnd('.', ',', '!', '?')
        when (t) {
            "stop listening", "pause" -> { Prefs.listen = false; speakNow("Paused"); return }
            "repeat", "repeat that", "say that again", "again", "repeat it" -> { repeatLast(); return }
            "stop", "skip", "quiet", "be quiet" -> { stopSpeech(); speaking = 0; spokenWords.clear(); maybeListen(); return }
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

    private fun speakNow(s: String) { speaking++; spokenWords.addAll(words(s)); say(s, "n${System.nanoTime()}") }

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
    private val geminiVoices = listOf("Kore", "Puck", "Charon", "Aoede", "Fenrir", "Zephyr", "Leda", "Orus", "Callirrhoe", "Sulafat")

    // Floating bubble: tap to expand a control panel over any app; drag to move.
    private fun addOverlay() {
        if (overlay != null) return
        val ctx = this
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        fun bg(color: Int, r: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = r * dp }

        val root = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val lp = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.WRAP_CONTENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, android.graphics.PixelFormat.TRANSLUCENT
        ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START; x = px(8); y = px(200) }

        val bubble = android.widget.TextView(ctx).apply {
            text = "🎙"; textSize = 22f; gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(px(52), px(52))
        }
        val panel = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL; visibility = android.view.View.GONE
            background = bg(0xF2222222.toInt(), 12); setPadding(px(8), px(8), px(8), px(8))
            layoutParams = android.widget.LinearLayout.LayoutParams(px(290), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        fun refresh() { bubble.background = bg(if (Prefs.listen) 0xFF2E7D32.toInt() else 0xFF616161.toInt(), 26) }

        fun btn(label: () -> String, w: Float = 1f, onClick: (android.widget.Button) -> Unit) = android.widget.Button(ctx).apply {
            text = label(); textSize = 12f; isAllCaps = false; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(px(6), px(8), px(6), px(8))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, w)
            setOnClickListener { onClick(this); text = label() }
        }
        fun row(vararg v: android.view.View) = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            v.forEach { addView(it) }
        }.also { panel.addView(it) }

        row(btn({ "Skip" }) { stopAll(); h.postDelayed({ maybeListen() }, 300) },
            btn({ "Resync" }) { resync() },
            btn({ "Clear draft" }) { pending.setLength(0); setField("") })
        row(btn({ "\u21BB Repeat" }) { repeatLast() })
        row(btn({ if (Prefs.chatOnly) "Chat only: on" else "Chat only: off" }) { Prefs.chatOnly = !Prefs.chatOnly; Prefs.save(ctx) },
            btn({ "Copy screen dump" }) { copyDump() })
        row(btn({ if (Prefs.listen) "Mic: on" else "Mic: off" }) {
                Prefs.listen = !Prefs.listen; if (Prefs.listen) maybeListen() else stopListening(); refresh() },
            btn({ if (Prefs.read) "Read: on" else "Read: off" }) {
                Prefs.read = !Prefs.read; if (!Prefs.read) { stopSpeech(); speaking = 0; spokenWords.clear() } })
        row(btn({ "Engine: ${Prefs.engine}" }) { Prefs.cycleEngine(); Prefs.save(ctx) },
            btn({
                when (Prefs.engine) {
                    "eleven" -> "Voice: " + (Prefs.elevenVoices.entries.firstOrNull { it.value == Prefs.elevenVoice }?.key ?: "custom")
                    "gemini" -> "Voice: ${Prefs.geminiVoice}"
                    else -> "Voice: phone"
                }
            }) {
                if (Prefs.engine == "eleven") {
                    val ids = Prefs.elevenVoices.values.toList()
                    Prefs.elevenVoice = ids[(ids.indexOf(Prefs.elevenVoice) + 1) % ids.size]
                } else if (Prefs.engine == "gemini") {
                    val i = geminiVoices.indexOf(Prefs.geminiVoice)
                    Prefs.geminiVoice = geminiVoices[(i + 1) % geminiVoices.size]
                }
                Prefs.save(ctx)
            })
        val speedLabel = android.widget.TextView(ctx).apply { setTextColor(android.graphics.Color.WHITE); textSize = 12f }
        fun speedText() { speedLabel.text = "Speed %.1fx (phone voice)".format(Prefs.rate) }
        speedText()
        panel.addView(speedLabel)
        panel.addView(android.widget.SeekBar(ctx).apply {
            max = 30; progress = ((Prefs.rate - 0.5f) * 10).toInt()
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, u: Boolean) { Prefs.rate = 0.5f + p / 10f; applyVoice(); speedText() }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        })

        // tap = expand/collapse, drag = move
        bubble.setOnTouchListener(object : android.view.View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
            override fun onTouch(v: android.view.View, ev: android.view.MotionEvent): Boolean {
                when (ev.action) {
                    android.view.MotionEvent.ACTION_DOWN -> { sx = ev.rawX; sy = ev.rawY; ox = lp.x; oy = lp.y; moved = false }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (Math.abs(ev.rawX - sx) > 10 * dp || Math.abs(ev.rawY - sy) > 10 * dp) moved = true
                        if (moved) { lp.x = ox + (ev.rawX - sx).toInt(); lp.y = oy + (ev.rawY - sy).toInt(); wm.updateViewLayout(root, lp) }
                    }
                    android.view.MotionEvent.ACTION_UP -> if (!moved) {
                        panel.visibility = if (panel.visibility == android.view.View.GONE) android.view.View.VISIBLE else android.view.View.GONE
                    }
                }
                return true
            }
        })
        refresh()
        root.addView(bubble); root.addView(panel)
        overlay = root
        wm.addView(root, lp)
    }

    private fun removeOverlay() {
        overlay?.let { (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(it) }
        overlay = null
    }

    override fun onInterrupt() { stopAll() }
    override fun onDestroy() { removeOverlay(); stopAll(); tts?.shutdown(); inst = null; super.onDestroy() }
}
