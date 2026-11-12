package app.jamkkanman

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Resident service (plan section 5): keeps the engine receiving vehicle signals and speaking while the screen
 * shows something else. Pleos Connect brings navigation to the front when the car moves, so without this
 * service the app process is frozen in the background and signals wait until the app is opened again.
 */
class EngineService : Service() {

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "정차 순간 살피기", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("잠깐만")
            .setContentText("길가에 서는 순간을 살피는 중입니다")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        (application as JamkkanmanApp).host.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "watch"
        private const val ID = 7

        fun start(context: Context) {
            context.startForegroundService(Intent(context, EngineService::class.java))
        }
    }
}
