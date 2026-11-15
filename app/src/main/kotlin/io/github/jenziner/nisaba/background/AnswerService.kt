package io.github.jenziner.nisaba.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import io.github.jenziner.nisaba.MainActivity
import io.github.jenziner.nisaba.R
import io.github.jenziner.nisaba.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while at least one request (answer,
 * web search, saving) is running, so it continues when the screen is locked or
 * the user switches apps.
 *
 * - Type `dataSync`; started only from a user action (Send / Retry) while the
 *   app is visible. Stops itself as soon as no request is running.
 * - Holds a partial wake lock (max. [WAKE_LOCK_TIMEOUT_MS]) so the CPU keeps
 *   reading the response stream with the screen off.
 * - Android 15+ limits `dataSync` to 6 h per 24 h; [onTimeout] stops the
 *   service. Running requests then continue without protection.
 *
 * The service does no work itself: the requests run in [AppGraph.scope].
 */
class AnswerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val g = graph
        if (intent?.action == ACTION_STOP) {
            g.log.add("background: stop requested from notification")
            g.cancelAllRequests()
            stopNow()
            return START_NOT_STICKY
        }
        val started = runCatching {
            startForeground(NOTIFICATION_ID, notification(g.running.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
        if (started.isFailure) {
            // e.g. ForegroundServiceStartNotAllowedException; requests keep running unprotected.
            g.log.add("background: foreground service not allowed (${started.exceptionOrNull()?.javaClass?.simpleName})")
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TiinyChat:answer")
                .apply { setReferenceCounted(false); acquire(WAKE_LOCK_TIMEOUT_MS) }
        }
        if (watcher == null) {
            g.log.add("background: protection on")
            watcher = scope.launch {
                g.running.collectLatest { n ->
                    if (n <= 0) stopNow()
                    else getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(n))
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+: the daily `dataSync` budget is used up. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        graph.log.add("background: Android time limit reached, protection off")
        stopNow()
    }

    private fun stopNow() {
        watcher?.cancel(); watcher = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        scope.cancel()
        graph.log.add("background: protection off")
        super.onDestroy()
    }

    private fun notification(running: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AnswerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (running <= 1) "Answer in progress…" else "$running answers in progress…"
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "answers"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "io.github.jenziner.nisaba.STOP_REQUESTS"
        private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60 * 1000

        /** Creates the (silent) channel; safe to call repeatedly. */
        fun createChannel(context: Context) {
            val ch = NotificationChannel(CHANNEL_ID, "Running answers", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while an answer is generated in the background."
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        /** Call from a user action while the app is visible. */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, AnswerService::class.java)) }
                .onFailure { context.graph.log.add("background: service not started (${it.javaClass.simpleName})") }
        }

        fun notificationsAllowed(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        fun batteryUnrestricted(context: Context): Boolean =
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
}
