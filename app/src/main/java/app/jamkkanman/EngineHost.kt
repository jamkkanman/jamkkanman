package app.jamkkanman

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.jamkkanman.core.DataSet
import app.jamkkanman.core.Effect
import app.jamkkanman.core.Engine
import app.jamkkanman.core.EngineSnapshot
import app.jamkkanman.core.ParkingFinder
import app.jamkkanman.core.Phase
import app.jamkkanman.core.Scenario
import app.jamkkanman.core.Signal
import app.jamkkanman.core.SignalCodec
import app.jamkkanman.core.SilenceStore
import app.jamkkanman.core.WallClock
import app.jamkkanman.core.ZoneIndex
import app.jamkkanman.core.ZoneJudge
import app.jamkkanman.live.Listener
import app.jamkkanman.live.Navigator
import app.jamkkanman.live.SourceStatus
import app.jamkkanman.live.Speaker
import app.jamkkanman.live.VehicleSignalSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Mode(val label: String) { LIVE("실차 신호"), REPLAY("시나리오 재생") }

data class UiLog(val t: Double, val phase: Phase, val event: String, val decision: String)

data class UiState(
    val mode: Mode = Mode.LIVE,
    val scenarioTitle: String? = null,
    val replayProgress: Float? = null,
    val snapshot: EngineSnapshot? = null,
    val sources: List<SourceStatus> = emptyList(),
    val logs: List<UiLog> = emptyList(),
    val speaking: String? = null,
    val voiceEvent: String = "",
    val reactions: List<Long> = emptyList(),
    val dataCounts: Map<String, Int> = emptyMap(),
    val naviConnected: Boolean = false,
)

/** Persists the 30-day silence list across app restarts. */
class PrefsSilenceStore(context: Context) : SilenceStore {
    private val prefs = context.getSharedPreferences("silence", Context.MODE_PRIVATE)
    override fun until(placeKey: String): Long? = prefs.getLong(placeKey, 0L).takeIf { it > 0 }
    override fun put(placeKey: String, untilWallMs: Long) { prefs.edit().putLong(placeKey, untilWallMs).apply() }
    fun clear() { prefs.edit().clear().apply() }
}

/**
 * Owns the engine and connects it to the car: live signals or a scenario go in,
 * sentences, listening, and route changes come out. Everything runs on the main thread.
 */
class EngineHost(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    val data: DataSet = DataSet.parse(context.assets.open("data/facilities.json").bufferedReader().readText())
    private val judge = ZoneJudge(ZoneIndex(data.facilities))
    private val parkingFinder = ParkingFinder(data.parking)
    private val silence = PrefsSilenceStore(context)

    private val _state = MutableStateFlow(UiState(dataCounts = data.counts()))
    val state: StateFlow<UiState> = _state

    private var engine: Engine = newEngine(liveClock())
    private val sessionStart = SystemClock.elapsedRealtime()
    private var replayStart = 0L
    private var replayEnd = 0L
    private var replayScenario: Scenario? = null
    private var replayDone = false
    private val replayRunnables = ArrayList<Runnable>()

    private var recorder: java.io.Writer? = null

    val speaker = Speaker(context) { voice(it) }
    val listener = Listener(context, onText = { onRecognized(it) }, onEvent = { voice(it) })
    private val live = VehicleSignalSource(context, emit = { onLiveSignal(it) }, onStatus = { s -> update { it.copy(sources = s) } })
    private val navigator = Navigator(context, live.naviListener) { msg, ok ->
        voice(msg)
        if (msg == "내비 연결됨") { update { it.copy(naviConnected = true) }; live.naviStatus(true, "구독 중") }
        if (msg.startsWith("내비 연결 실패")) live.naviStatus(false, msg)
        if (!ok) addLog(now(), "내비", msg)
    }
    private var pendingListenMs: Long? = null

    private val ticker = object : Runnable {
        override fun run() {
            handle(engine.tick(now()))
            if (_state.value.mode == Mode.REPLAY && !replayDone) {
                val p = ((now() - replayStart).toFloat() / (replayEnd - replayStart).coerceAtLeast(1)).coerceIn(0f, 1f)
                update { it.copy(replayProgress = p) }
                if (now() > replayEnd) finishReplay()
            }
            publish()
            main.postDelayed(this, if (_state.value.mode == Mode.REPLAY) 100 else 200)
        }
    }

    init {
        speaker.onDone = {
            update { it.copy(speaking = null) }
            pendingListenMs?.let { ms ->
                pendingListenMs = null
                if (_state.value.mode == Mode.LIVE) listener.listen(ms)
            }
        }
    }

    private fun now() = SystemClock.elapsedRealtime()

    private fun liveClock() = WallClock { t -> System.currentTimeMillis() - (SystemClock.elapsedRealtime() - t) }

    /** Live mode keeps the driver's 30-day silence list; a replay starts clean so it always gives the same result. */
    private fun newEngine(clock: WallClock, persistent: Boolean = true) =
        Engine(judge, parkingFinder, clock, silence = if (persistent) silence else app.jamkkanman.core.InMemorySilenceStore())

    private var started = false

    fun start() {
        if (started) return
        started = true
        live.start()
        openRecorder()
        main.post(ticker)
    }

    // ---------------- inputs ----------------

    private fun record(s: Signal) {
        recorder?.let { runCatching { it.write(SignalCodec.encodeLine(s, sessionStart) + "\n"); it.flush() } }
    }

    /** Latest value of each live input, used to seed a fresh engine with the car's current state. */
    private val lastLive = LinkedHashMap<Class<out Signal>, Signal>()

    private fun onLiveSignal(s: Signal) {
        record(s)
        if (s !is Signal.ReplyText && s !is Signal.NaviArrived && s !is Signal.NaviVoice) lastLive[s.javaClass] = s
        if (_state.value.mode != Mode.LIVE) return
        handle(engine.onSignal(s))
        publish()
    }

    fun reply(text: String, via: String) {
        listener.stopListening("응답 받음")
        val s = Signal.ReplyText(now(), text, via)
        if (_state.value.mode == Mode.LIVE) record(s)
        handle(engine.onSignal(s))
        publish()
    }

    private fun onRecognized(text: String) {
        voice("인식: \"$text\"")
        reply(text, "voice")
    }

    /** Plays a recorded driver answer aloud and feeds the same clip to speech recognition. */
    fun speakAsDriver(asset: String) {
        runCatching {
            val afd = context.assets.openFd(asset)
            MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                setOnCompletionListener { it.release() }
                prepare()
                start()
            }
        }.onFailure { Log.w(TAG, "driver clip playback", it) }
        listener.feedRecording(asset)
    }

    // ---------------- replay ----------------

    fun startReplay(asset: String) {
        stopReplay()
        val sc = Scenario.parse(context.assets.open(asset).bufferedReader().readText())
        replayScenario = sc
        replayDone = false
        replayStart = now() + 300
        replayEnd = replayStart + sc.durationMs + 8_000
        engine = newEngine(WallClock { t -> sc.startWallMs + (t - replayStart) }, persistent = false)
        speaker.stop()
        listener.stopListening("재생 시작")
        update {
            it.copy(mode = Mode.REPLAY, scenarioTitle = sc.title, replayProgress = 0f, logs = emptyList(), speaking = null)
        }
        addLog(replayStart, "재생 시작", sc.title)
        for (s in sc.signals) {
            val r = Runnable { playSignal(s) }
            replayRunnables.add(r)
            main.postAtTime(r, SystemClock.uptimeMillis() + (replayStart + s.t - now()))
        }
    }

    private fun playSignal(s: Signal) {
        val shifted = shift(s, replayStart)
        if (shifted is Signal.ReplyText && shifted.audio != null) {
            // The answer is spoken from a recording and must pass speech recognition like a real driver.
            speakAsDriver(shifted.audio!!)
            main.postDelayed({
                if (engine.snapshot().awaitingReply) {
                    voice("음성 인식 결과가 없어 시나리오의 응답 문장을 씀")
                    handle(engine.onSignal(shifted.copy(t = now(), via = "voice")))
                    publish()
                }
            }, 9_000)
            return
        }
        handle(engine.onSignal(shifted))
        publish()
    }

    private fun shift(s: Signal, base: Long): Signal = Scenario.shiftTime(s, base)

    private fun finishReplay() {
        replayDone = true
        addLog(now(), "재생 끝", replayScenario?.title ?: "")
        replayRunnables.clear()
        // Stay on the replay result until the driver goes back to live signals.
        update { it.copy(replayProgress = 1f) }
    }

    fun stopReplay() {
        replayRunnables.forEach { main.removeCallbacks(it) }
        replayRunnables.clear()
        replayScenario = null
    }

    fun backToLive() {
        stopReplay()
        speaker.stop()
        engine = newEngine(liveClock())
        update { it.copy(mode = Mode.LIVE, scenarioTitle = null, replayProgress = null, logs = emptyList(), speaking = null) }
        addLog(now(), "실차 신호로 전환", "엔진 상태 초기화, 현재 차량 값으로 시작")
        val t = now()
        lastLive.values.forEach { engine.onSignal(Scenario.shiftTime(it, t - it.t)) }
        publish()
    }

    fun cancelRoute() = navigator.cancel()

    fun clearSilence() {
        silence.clear()
        addLog(now(), "침묵 목록", "모두 지움")
    }

    // ---------------- outputs ----------------

    private fun handle(effects: List<Effect>) {
        for (e in effects) when (e) {
            is Effect.Log -> addLog(e.entry.t, e.entry.event, e.entry.decision, e.entry.phase)
            is Effect.Speak -> {
                update { it.copy(speaking = e.text) }
                if (e.kind.isAlert) bringScreenToFront()
                val trigger = e.triggerT
                speaker.speak(e.text) { startedAt ->
                    val ms = startedAt - trigger
                    update { it.copy(reactions = (it.reactions + ms).takeLast(50)) }
                    addLog(startedAt, "음성 시작", "신호에서 ${ms} ms")
                    Log.i(TAG, "REACTION ${e.kind} ${ms}ms \"${e.text}\"")
                }
                Log.i(TAG, "SPEAK ${e.kind} \"${e.text}\"")
            }
            is Effect.Listen -> pendingListenMs = e.windowMs
            is Effect.Route -> navigator.routeTo(e.option.lot)
        }
    }

    private fun publish() = update { it.copy(snapshot = engine.snapshot()) }

    /** Set by the activity; the stopped-car screen is brought forward only when it is not already shown. */
    @Volatile var uiVisible = false

    /**
     * Shows the confirmation screen when the app speaks at a stop. Pleos puts navigation in front while driving,
     * so the screen comes back only for the stop that needs it. Needs "display over other apps" (SYSTEM_ALERT_WINDOW)
     * to start from the background; without it the voice still works and the app icon opens the screen.
     */
    private fun bringScreenToFront() {
        Log.i(TAG, "FRONT uiVisible=$uiVisible")
        if (uiVisible) return
        runCatching {
            context.startActivity(
                android.content.Intent(context, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }.onFailure { Log.w(TAG, "bring to front", it) }
    }

    private fun addLog(t: Long, event: String, decision: String, phase: Phase = engine.phase) {
        val base = if (_state.value.mode == Mode.REPLAY) replayStart else sessionStart
        val line = UiLog((t - base) / 1000.0, phase, event, decision)
        // Decision logs can name a recognized answer; they go to logcat only in debug builds.
        if (BuildConfig.DEBUG) Log.i(TAG, "LOG %.1f [%s] %s | %s".format(line.t, phase.label, event, decision))
        update { it.copy(logs = (listOf(line) + it.logs).take(300)) }
    }

    private fun voice(msg: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, "VOICE $msg")
        update { it.copy(voiceEvent = msg) }
    }

    private inline fun update(f: (UiState) -> UiState) { _state.value = f(_state.value) }

    /**
     * Writes live signals (including location) to app-private storage in the replay format.
     * Debug builds only: a release build keeps no location trace on disk.
     */
    private fun openRecorder() {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val dir = File(context.filesDir, "recordings").apply { mkdirs() }
            val name = "live-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".jsonl"
            recorder = File(dir, name).bufferedWriter()
        }
    }

    fun release() {
        main.removeCallbacks(ticker)
        stopReplay()
        live.stop()
        speaker.release()
        listener.release()
        navigator.release()
        runCatching { recorder?.close() }
    }

    companion object { const val TAG = "JKM" }
}
