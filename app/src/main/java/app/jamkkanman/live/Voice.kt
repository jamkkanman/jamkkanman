package app.jamkkanman.live

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.Locale

/**
 * Pleos TextToSpeech in ON_DEVICE mode (no network, no app registration), Korean.
 * Reports when each sentence actually starts, which is the end point of the reaction-time measurement.
 */
class Speaker(context: Context, private val onEvent: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(context.applicationContext, ai.pleos.playground.tts.constant.Mode.ON_DEVICE)
    @Volatile var ready = false; private set
    private var pending: Pair<String, (Long) -> Unit>? = null
    private var onStarted: ((Long) -> Unit)? = null
    var onDone: (() -> Unit)? = null

    init {
        tts.initialize(object : ai.pleos.playground.tts.listener.OnInitializeListener {
            override fun onSuccess() {
                // The SDK requires initialize() to finish before addEventListener().
                tts.addEventListener(object : EventListener {
                    override fun onStart() {
                        val t = SystemClock.elapsedRealtime()
                        main.post { onStarted?.invoke(t); onStarted = null }
                    }
                    override fun onReady() {}
                    override fun onDone() { main.post { onDone?.invoke() } }
                    override fun onError(e: ai.pleos.playground.tts.constant.ErrorType) { main.post { onEvent("TTS 오류 $e") } }
                    override fun onStop() {}
                    override fun onUpdatedRms(rms: Double) {}
                })
                tts.setLocale(Locale.KOREA)
                ready = true
                main.post {
                    onEvent("TTS 준비됨 (ON_DEVICE, ko-KR)")
                    pending?.let { (text, cb) -> pending = null; speak(text, cb) }
                }
            }
            override fun onFailure(e: ai.pleos.playground.tts.constant.ErrorType) {
                main.post { onEvent("TTS 초기화 실패 $e, 화면 안내만 사용") }
            }
        })
    }

    /** Speaks one sentence; [started] receives the elapsedRealtime when audio starts. */
    fun speak(text: String, started: (Long) -> Unit) {
        if (!ready) { pending = text to started; return }
        runCatching { tts.stop() }
        onStarted = started
        tts.speak(text, true)
    }

    fun stop() = runCatching { tts.stop() }

    fun release() = runCatching { tts.stop(); tts.release() }
}

/**
 * Pleos SpeechToText in ON_DEVICE mode. [listen] uses the car microphone; [feedRecording] sends a recorded
 * 16 kHz mono PCM clip with sendAudio(), the documented path for audio that does not come from the microphone.
 * Only the final text is forwarded; the app classifies it with keyword rules.
 */
class Listener(private val context: Context, private val onText: (String) -> Unit, private val onEvent: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val stt = SpeechToText(context.applicationContext, ai.pleos.playground.stt.constant.Mode.ON_DEVICE)
    @Volatile private var ready = false
    @Volatile private var listening = false
    /** Latest partial text; used when the engine ends the input without a final result. */
    private var partial: String = ""
    private val timeout = Runnable {
        if (partial.isNotBlank()) deliver(partial, "최종 결과 없이 끝나 마지막 중간 결과를 씀")
        else stopListening("응답 없음")
    }

    private fun deliver(text: String, note: String? = null) {
        listening = false
        partial = ""
        main.removeCallbacks(timeout)
        note?.let(onEvent)
        onText(text)
    }

    init {
        stt.addListener(object : ResultListener {
            override fun onUpdated(text: String, completed: Boolean) {
                if (completed && text.isNotBlank()) main.post { if (listening) deliver(text) }
                else if (text.isNotBlank()) main.post { partial = text; onEvent("듣는 중: \"$text\"") }
            }
            override fun onUpdatedEpdData(start: Long, end: Long) {}
            override fun onStartedRecognition() { main.post { onEvent("음성 인식 시작") } }
            override fun onEndedRecognition() {}
            override fun onError(e: ai.pleos.playground.stt.constant.ErrorType) {
                main.post { listening = false; onEvent("STT 오류 $e") }
            }
            override fun onReady() {}
            override fun onUpdatedRms(rms: Double) {}
        })
        stt.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
            override fun onSuccess() {
                stt.setLocale(Locale.KOREA)
                ready = true
                main.post { onEvent("STT 준비됨 (ON_DEVICE)") }
            }
            override fun onFailure(e: ai.pleos.playground.stt.constant.ErrorType) {
                main.post { onEvent("STT 초기화 실패 $e, 화면 버튼만 사용") }
            }
        })
    }

    fun listen(windowMs: Long) {
        if (!ready) return
        runCatching { stt.stop() }
        listening = true
        partial = ""
        runCatching { stt.request() }.onFailure { onEvent("STT request 실패 ${it.message}") }
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, windowMs)
    }

    fun stopListening(reason: String) {
        if (!listening) return
        listening = false
        partial = ""
        main.removeCallbacks(timeout)
        runCatching { stt.stop() }
        onEvent("듣기 종료: $reason")
    }

    /** Streams a WAV asset (16 kHz, 16-bit, mono) at real time, 3,200 bytes per 100 ms, then 1.5 s of silence. */
    fun feedRecording(asset: String) {
        if (!ready) { onEvent("STT 준비 전"); return }
        main.removeCallbacks(timeout)
        listening = true
        partial = ""
        Thread {
            try {
                runCatching { stt.stop() }
                Thread.sleep(200)
                val wav = context.assets.open(asset).use { it.readBytes() }
                val pcm = pcmOf(wav)
                var i = 0
                while (i < pcm.size) {
                    val end = minOf(i + 3200, pcm.size)
                    stt.sendAudio(pcm.copyOfRange(i, end))
                    i = end
                    Thread.sleep(100)
                }
                // Trailing silence lets end-point detection close the utterance and send a final result.
                repeat(15) { stt.sendAudio(ByteArray(3200)); Thread.sleep(100) }
                stt.completeAudioSend()
                // If the engine ends the job without a final result, the latest partial text is used.
                main.postDelayed(timeout, 1_200)
            } catch (e: Throwable) {
                Log.e("JKM-STT", "feed", e)
                main.post { onEvent("녹음 입력 실패 ${e.message}") }
            }
        }.start()
    }

    fun release() = runCatching { stt.stop(); stt.release() }

    companion object {
        /** PCM bytes of a RIFF WAV: finds the "data" chunk instead of assuming a 44-byte header. */
        fun pcmOf(wav: ByteArray): ByteArray {
            var i = 12
            while (i + 8 <= wav.size) {
                val id = String(wav, i, 4, Charsets.US_ASCII)
                val size = (wav[i + 4].toInt() and 0xff) or ((wav[i + 5].toInt() and 0xff) shl 8) or
                    ((wav[i + 6].toInt() and 0xff) shl 16) or ((wav[i + 7].toInt() and 0xff) shl 24)
                if (id == "data") return wav.copyOfRange(i + 8, minOf(wav.size, i + 8 + size))
                i += 8 + size + (size and 1)
            }
            return wav.copyOfRange(44, wav.size)
        }
    }
}
