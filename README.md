# Claude TTS (quick & dirty)

Android accessibility service that reads whatever Claude Code session is on screen (claude.ai/code in a browser, the Claude app, Termux, etc.) aloud once the text stops streaming, then (optionally) listens for your spoken reply, types it into the text box and taps Send, and loops.

## Build
Push → GitHub Actions "Build APK" artifact, or locally: `gradle assembleDebug` (needs Android SDK) → `app/build/outputs/apk/debug/app-debug.apk`.

## Use
1. Install APK (allow unknown sources). If "restricted setting" blocks the service: App info → ⋮ → Allow restricted settings.
2. Grant mic permission, enable **Claude TTS** in Accessibility settings.
3. Open Claude Code on screen. Toggle *Hands-free voice reply* in the app.
4. Voice commands: "stop"/"skip" (silence), "stop listening"/"pause" (turn off hands-free).

Overlay: a floating mic bubble (green = listening). Tap to open the control panel (Skip, Resync, Mic, Read, Gemini/voice, speed); drag to move.

Limitations: hacky text diffing (reads new on-screen lines, ignores short UI labels); Send button found by label containing "send"; uses Android's built-in recognizer (may beep / needs network on some devices).
