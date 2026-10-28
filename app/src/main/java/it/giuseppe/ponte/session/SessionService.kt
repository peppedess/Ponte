package it.giuseppe.ponte.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import it.giuseppe.ponte.R
import it.giuseppe.ponte.scrcpy.SessionState
import it.giuseppe.ponte.ui.DexActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Servizio in primo piano: tiene viva la sessione quando Ponte non è visibile e mostra una
 * notifica con "Torna al desktop" e "Termina". Tiene anche il Wi-Fi in modalità bassa latenza.
 */
class SessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireWifiLock()
        scope.launch {
            SessionManager.status.collect { state ->
                if (state is SessionState.Closed) {
                    stopSelf()
                } else {
                    notificationManager().notify(NOTIFICATION_ID, buildNotification(state))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SessionManager.stop(this)
            return START_NOT_STICKY
        }
        val notification = buildNotification(SessionManager.status.value)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wifiLock?.release() }
        super.onDestroy()
    }

    /** Wi-Fi sempre attivo e a bassa latenza durante la sessione: evita gli scatti del risparmio energetico. */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifi.createWifiLock(mode, "ponte").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun buildNotification(state: SessionState): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, DexActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, SessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val (title, text) = when (state) {
            is SessionState.Running -> "Desktop attivo" to "Collegato a ${state.deviceName}"
            is SessionState.Starting -> "Ponte" to state.step
            is SessionState.Failed -> "Desktop interrotto" to state.message
            SessionState.Closed -> "Ponte" to "Sessione chiusa"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(state !is SessionState.Failed)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Torna al desktop", open)
            .addAction(0, "Termina", stop)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Sessione desktop", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Mostrata mentre il desktop del telefono è collegato"
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val CHANNEL_ID = "sessione"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "it.giuseppe.ponte.STOP"
    }
}
