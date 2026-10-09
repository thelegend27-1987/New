package com.quick.claudetts

import android.content.Context

object Prefs {
    const val DEFAULT_ELEVEN_VOICE = "21m00Tcm4TlvDq8ikWAM" // Rachel (premade)
    // Premade ElevenLabs voices for the bubble's voice button: name -> id
    val elevenVoices = linkedMapOf(
        "Rachel" to "21m00Tcm4TlvDq8ikWAM", "Sarah" to "EXAVITQu4vr4xnSDxMaL", "Brian" to "nPczCjzI2devNBz1zQrb",
        "Adam" to "pNInz6obpgDQGcFmaJgB", "George" to "JBFqnCBsd6RMkjVDRZzb", "Laura" to "FGY2WhTYpPnrIDTdsKH5",
        "Aria" to "9BWtsMINqrJLrRacOk9x", "Charlie" to "IKne3meq5aSn9XLyUdCD", "Lily" to "pFZP5JQG7iQjIQuC4Bku"
    )

    @Volatile var engine = "phone"            // phone | gemini | eleven
    @Volatile var elevenKey = ""
    @Volatile var elevenVoice = DEFAULT_ELEVEN_VOICE
    @Volatile var elevenModel = "eleven_flash_v2_5"
    @Volatile var geminiKey = ""
    @Volatile var geminiVoice = "Kore"
    @Volatile var geminiModel = "gemini-2.5-flash-preview-tts"
    @Volatile var read = true
    @Volatile var listen = true
    @Volatile var rate = 1.2f

    fun cloudReady() = (engine == "eleven" && elevenKey.isNotBlank()) || (engine == "gemini" && geminiKey.isNotBlank())

    /** Next engine that has a key (phone is always available). */
    fun cycleEngine() {
        val order = listOf("phone", "eleven", "gemini")
        for (step in 1..3) {
            val n = order[(order.indexOf(engine) + step) % 3]
            if (n == "phone" || (n == "eleven" && elevenKey.isNotBlank()) || (n == "gemini" && geminiKey.isNotBlank())) { engine = n; return }
        }
    }

    fun load(c: Context) {
        val p = c.getSharedPreferences("p", 0)
        geminiKey = p.getString("gk", "") ?: ""
        geminiVoice = p.getString("gv", "Kore") ?: "Kore"
        geminiModel = p.getString("gm", "gemini-2.5-flash-preview-tts") ?: "gemini-2.5-flash-preview-tts"
        elevenKey = p.getString("ek", "") ?: ""
        elevenVoice = p.getString("ev", DEFAULT_ELEVEN_VOICE) ?: DEFAULT_ELEVEN_VOICE
        elevenModel = p.getString("em", "eleven_flash_v2_5") ?: "eleven_flash_v2_5"
        engine = p.getString("eng", null) ?: if (elevenKey.isNotEmpty()) "eleven" else if (geminiKey.isNotEmpty()) "gemini" else "phone"
    }

    fun save(c: Context) {
        c.getSharedPreferences("p", 0).edit()
            .putString("gk", geminiKey).putString("gv", geminiVoice).putString("gm", geminiModel)
            .putString("ek", elevenKey).putString("ev", elevenVoice).putString("em", elevenModel)
            .putString("eng", engine).apply()
    }
}
