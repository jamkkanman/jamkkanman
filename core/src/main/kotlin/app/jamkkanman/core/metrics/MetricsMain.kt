package app.jamkkanman.core.metrics

import app.jamkkanman.core.DataSet
import app.jamkkanman.core.Scenario
import app.jamkkanman.core.ScenarioRunner
import java.io.File
import java.time.LocalDate

/**
 * Writes docs/metrics/metrics.md: demo scenario results, the 100 traffic light waits, 100 in-zone stops,
 * zone accuracy under noise, and judge timing. Usage: metrics <assetsDir> <outDir>
 */
fun main(args: Array<String>) {
    val assets = File(args.getOrElse(0) { "app/src/main/assets" })
    val out = File(args.getOrElse(1) { "docs/metrics" }).apply { mkdirs() }
    val data = DataSet.parse(File(assets, "data/facilities.json").readText())
    val md = StringBuilder()
    fun line(s: String = "") { md.append(s).append('\n'); println(s) }

    line("# 지표 측정 결과")
    line()
    line("`./gradlew :core:metrics`로 다시 만들 수 있습니다. 모든 시나리오는 고정 시드로 생성하므로 다시 돌려도 같은 숫자가 나옵니다(판정 시간만 기기 상태에 따라 달라짐).")
    line("측정일 ${LocalDate.now()}, 내장 데이터 " + data.counts().entries.joinToString(", ") { "${it.key} ${it.value}" })
    line()

    line("## 1. 시연 시나리오 (app/src/main/assets/scenarios)")
    line()
    line("| 시나리오 | 예상 안내 | 실제 안내 | 주차장 안내 | 결과 |")
    line("|---|---|---|---|---|")
    val scenarioFiles = File(assets, "scenarios").listFiles { f -> f.extension == "json" }?.sortedBy { it.name }.orEmpty()
    for (f in scenarioFiles) {
        val sc = Scenario.parse(f.readText())
        val r = ScenarioRunner.run(sc, data)
        line("| ${sc.title} | ${sc.expectAlerts ?: "-"} | ${r.alerts.size} | ${if (r.routed) "예" else "아니오"} | ${if (r.passed) "통과" else "실패"} |")
    }
    line()

    val waits = Suites.run("신호 대기", Suites.signalWaits(data), data)
    val stops = Suites.run("금지 구역 정차", Suites.intentStops(data), data)
    line("## 2. 신호 대기 오경보와 발화 예산")
    line()
    line("| 묶음 | 시나리오 | 기대대로 | 정차 수 | 안내 수 | 정차당 평균 | 정차당 최대 |")
    line("|---|---|---|---|---|---|---|")
    for (s in listOf(waits, stops)) {
        line("| ${s.name} | ${s.scenarios} | ${s.passed} | ${s.stops} | ${s.alerts} | %.2f | ${s.maxAlertsPerStop} |".format(s.alertsPerStop))
    }
    val allStops = waits.stops + stops.stops
    val allAlerts = waits.alerts + stops.alerts
    line()
    line("- 신호 대기 100개(횡단보도, 정류장, 소화전의 금지 거리 안에서 20~120초 정지, 기어 D, 벨트 착용, 문 닫힘, 일부는 GPS 흔들림과 서다 가다 포함)의 오경보: **${waits.alerts}회**.")
    line("- 금지 구역 정차 100개의 응답 가정: 응답 없이 문 열림 40%, \"응\" 30%, \"금방 가\" 후 50초 넘게 정차 15%, \"괜찮아\" 15%. 의도 신호는 기어 P 60%, 목적지 도착 20%, 문 열림만 20%.")
    line("- 두 묶음을 합친 정차 ${allStops}번의 정차당 발화는 평균 %.2f회, 최대 ${maxOf(waits.maxAlertsPerStop, stops.maxAlertsPerStop)}회. 주차장 안내와 응답 확인 문장은 운전자가 요청한 대답이라 발화 예산에서 뺐다.".format(allAlerts.toDouble() / allStops))
    if (waits.failures.isNotEmpty() || stops.failures.isNotEmpty()) {
        line()
        line("기대와 다른 시나리오:")
        (waits.failures + stops.failures).forEach { line("- $it") }
    }
    line()

    line("## 3. 구역 판정 정밀도와 재현율 (위치 잡음)")
    line()
    line("시설 200곳(소화전형 R=5m 100곳, 정류장형 R=10m 100곳), 참 거리 2, 4, 6, 8, 12, 20m, 축마다 정규 잡음, 지점마다 50회 반복(조합당 60,000건).")
    line("\"안\"은 d <= R만 금지로 본 결과, \"안+가까이\"는 d <= R + m까지 말한 결과다.")
    line()
    line("| 잡음 표준편차 | 오차 여유 m | \"안\" 정밀도 | \"안\" 재현율 | \"안+가까이\" 정밀도 | \"안+가까이\" 재현율 |")
    line("|---|---|---|---|---|---|")
    for (r in ZoneAccuracy.measure()) {
        line("| %.0fm | %.0fm | %.3f | %.3f | %.3f | %.3f |".format(r.sigmaM, r.marginM, r.strictPrecision, r.strictRecall, r.lenientPrecision, r.lenientRecall))
    }
    line()

    line("## 4. 판정 시간 (JVM, 이 PC)")
    line()
    val (mean, p99, max) = Suites.judgeTiming(data.facilities)
    val (cMean, cP99, cMax) = Suites.judgeTiming(Suites.cityScale())
    line("| 데이터 | 시설 수 | 평균 | p99 | 최대 |")
    line("|---|---|---|---|---|")
    line("| 내장 시연 데이터 | ${data.facilities.size} | %.1f us | %.1f us | %.1f us |".format(mean, p99, max))
    line("| 서울 규모 가상 시설 | 60000 | %.1f us | %.1f us | %.1f us |".format(cMean, cP99, cMax))
    line()
    line("에뮬레이터 안에서 잰 판정 시간과 반응 시간은 앱의 지표 화면과 docs/metrics/device.md에 따로 적는다.")

    File(out, "metrics.md").writeText(md.toString())
    println("wrote ${File(out, "metrics.md").absolutePath}")
}
