package app.jamkkanman

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.jamkkanman.core.Scenario
import app.jamkkanman.probe.ProbeRunner
import app.jamkkanman.ui.MainScreen
import app.jamkkanman.ui.MetricsScreen
import app.jamkkanman.ui.ProbeScreen
import app.jamkkanman.ui.ScenarioButton

class MainActivity : ComponentActivity() {

    private enum class Screen { MAIN, METRICS, PROBE }

    private var screen by mutableStateOf(Screen.MAIN)
    private var probe: ProbeRunner? = null
    private val host get() = (application as JamkkanmanApp).host

    private val scenarioAssets by lazy {
        assets.list("scenarios").orEmpty().filter { it.endsWith(".json") }.sorted().map { "scenarios/$it" }
    }
    private val scenarios by lazy { scenarioAssets.map { Scenario.parse(assets.open(it).bufferedReader().readText()) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Permissions.requestMissing(this)
        runCatching { EngineService.start(this) }.onFailure { Log.w(EngineHost.TAG, "service", it) }
        host.start()
        setContent {
            when (screen) {
                Screen.MAIN -> MainScreen(
                    host = host,
                    scenarios = scenarioAssets.zip(scenarios).map { (a, s) -> ScenarioButton(a, s.title) },
                    onOpenMetrics = { screen = Screen.METRICS },
                    onOpenProbe = { openProbe() },
                )
                Screen.METRICS -> MetricsScreen(host.data, scenarios, onBack = { screen = Screen.MAIN }) { r ->
                    r.scenarioRows.forEach { Log.i(EngineHost.TAG, "METRIC scenario $it") }
                    r.suiteRows.forEach { Log.i(EngineHost.TAG, "METRIC suite $it") }
                    r.timingRows.forEach { Log.i(EngineHost.TAG, "METRIC timing $it") }
                    Log.i(EngineHost.TAG, "METRIC elapsed ${r.elapsedMs} ms")
                }
                Screen.PROBE -> ProbeScreen(
                    runner = probe!!,
                    permissionLines = Permissions.report(this),
                    onTestRoute = { probe?.requestTestRoute(37.566535, 126.977969, "시험 목적지", "TEST-0001", "서울특별시 중구 세종대로 110") },
                    onBack = { closeProbe() },
                )
            }
        }
        handleIntent(intent)
    }

    private fun openProbe() {
        if (probe == null) probe = ProbeRunner(applicationContext).also { it.runAll() }
        screen = Screen.PROBE
    }

    private fun closeProbe() {
        probe?.release()
        probe = null
        screen = Screen.MAIN
    }

    override fun onStart() {
        super.onStart()
        host.uiVisible = true
    }

    override fun onStop() {
        host.uiVisible = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * adb hooks for scripted demos and tests, for example
     * `adb shell am start -n app.jamkkanman/.MainActivity --es action replay --es asset scenarios/02_hydrant_gear_p.json`.
     */
    private fun handleIntent(intent: Intent?) {
        when (intent?.getStringExtra("action")) {
            "replay" -> { screen = Screen.MAIN; host.startReplay(intent.getStringExtra("asset") ?: return) }
            "live" -> { screen = Screen.MAIN; host.backToLive() }
            "driver_voice" -> host.speakAsDriver(intent.getStringExtra("asset") ?: "demo_voice/reply_yes.wav")
            "reply" -> host.reply(intent.getStringExtra("text") ?: "응", "button")
            "metrics" -> screen = Screen.METRICS
            "probe" -> openProbe()
            "clear_silence" -> host.clearSilence()
            "cancel_route" -> host.cancelRoute()
            "live_view" -> screen = Screen.MAIN
        }
    }

    override fun onDestroy() {
        probe?.release()
        super.onDestroy()
    }
}
