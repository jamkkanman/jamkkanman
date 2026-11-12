package app.jamkkanman.live

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.jamkkanman.core.ParkingLot

/**
 * Changes the navigation destination to a public parking lot with NaviHelper.requestRoute.
 * The lot's public-data management number is used as poiId because no place search SDK is available.
 */
class Navigator(context: Context, private val extraListener: NaviHelperEventListener, private val onEvent: (String, Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val navi = NaviHelper(context.applicationContext)
    @Volatile var connected = false; private set

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) { main.post { onEvent("내비 경로 안내 시작: ${info.name}", true) } }
        override fun onRoutingCompleted() { main.post { onEvent("내비 경로 탐색 완료", true) } }
        override fun onError(code: NaviErrorCode) { main.post { onEvent("내비 오류 $code", false) } }
    }

    init {
        try {
            navi.initialize({
                navi.addListener(listener)
                navi.addListener(extraListener)
                connected = true
                main.post { onEvent("내비 연결됨", true) }
            }, { e ->
                main.post { onEvent("내비 연결 실패 ${e.message}", false) }
            })
        } catch (e: Throwable) {
            onEvent("내비 연결 실패 ${e.message}", false)
        }
    }

    fun routeTo(lot: ParkingLot): Boolean {
        if (!connected) { onEvent("내비 미연결, 화면과 음성 안내만", false); return false }
        return try {
            navi.requestRoute(
                RouteInfo(
                    latitude = lot.pos.lat,
                    longitude = lot.pos.lon,
                    poiId = lot.mgmtNo,
                    poiName = lot.name,
                    poiSubId = "0",
                    address = lot.address,
                )
            )
            onEvent("내비 목적지 변경 요청: ${lot.name}", true)
            true
        } catch (e: Throwable) {
            onEvent("내비 목적지 변경 실패 ${e.message}", false)
            false
        }
    }

    fun cancel() {
        if (connected) runCatching { navi.cancelRoute() }
    }

    fun release() = runCatching { navi.removeListener(listener); navi.removeListener(extraListener); navi.release() }
}
