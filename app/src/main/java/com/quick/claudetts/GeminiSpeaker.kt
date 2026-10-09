package com.quick.claudetts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue

/** Speaks text with Gemini's TTS API (24kHz 16-bit mono PCM). One worker thread, in-order queue. */
class GeminiSpeaker(
    private val onChunkDone: () -> Unit,           // called (any thread) after each chunk, played or failed
    private val onFail: (String) -> Unit           // called (worker thread) when a chunk failed after retries; may block while it falls back
) {
    private val q = LinkedBlockingQueue<String>()
    @Volatile private var gen = 0
    @Volatile private var track: AudioTrack? = null
    @Volatile var lastError = ""

    init { Thread { loop() }.apply { isDaemon = true }.start() }

    fun enqueue(text: String) { q.put("$gen|$text") }

    fun stop() {
        gen++
        q.clear()
        try { track?.stop() } catch (_: Exception) {}
    }

    private fun loop() {
        while (true) {
            val item = q.take()
            val g = item.substringBefore('|').toInt()
            val text = item.substringAfter('|')
            if (g != gen) continue
            try {
                lastError = ""
                var pcm: ByteArray? = null
                var attempt = 0
                while (pcm == null) {
                    try { pcm = fetch(text) }
                    catch (e: Exception) {
                        lastError = e.message ?: e.toString()
                        // retry transient errors (rate limit / server / network) with backoff
                        if (++attempt >= 4 || g != gen) throw e
                        Thread.sleep(1500L * attempt)
                    }
                }
                lastError = ""
                if (g == gen) play(pcm, g)
                onChunkDone()
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()
                if (g == gen) onFail(text)
                onChunkDone()
            }
        }
    }

    private fun fetch(text: String): ByteArray {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${Prefs.geminiModel}:generateContent")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", Prefs.geminiKey)
        c.doOutput = true
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", Prefs.geminiVoice)))))
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode != 200) {
            val err = (c.errorStream ?: c.inputStream).bufferedReader().readText().take(200)
            throw RuntimeException("HTTP ${c.responseCode}: $err")
        }
        val resp = JSONObject(c.inputStream.bufferedReader().readText())
        val b64 = resp.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData").getString("data")
        return Base64.decode(b64, Base64.DEFAULT)
    }

    private fun play(pcm: ByteArray, g: Int) {
        if (pcm.isEmpty()) return
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size)
            .build()
        track = t
        try {
            t.write(pcm, 0, pcm.size)
            val frames = pcm.size / 2
            t.play()
            while (g == gen && t.playbackHeadPosition < frames && t.playState == AudioTrack.PLAYSTATE_PLAYING) Thread.sleep(40)
        } finally {
            try { t.stop() } catch (_: Exception) {}
            t.release(); track = null
        }
    }

    companion object {
        /** Names of models that mention "tts" (for debugging a wrong model name). */
        fun listTtsModels(): String {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200").openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 20000
            c.setRequestProperty("x-goog-api-key", Prefs.geminiKey)
            if (c.responseCode != 200) return "list failed: HTTP ${c.responseCode}"
            val arr = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("models") ?: return "no models"
            val out = (0 until arr.length()).map { arr.getJSONObject(it).getString("name").removePrefix("models/") }.filter { it.contains("tts", true) }
            return if (out.isEmpty()) "no TTS models available to this key" else "TTS models: " + out.joinToString(", ")
        }
    }
}
