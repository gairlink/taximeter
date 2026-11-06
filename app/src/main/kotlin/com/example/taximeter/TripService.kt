package com.example.taximeter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps GPS running while a ride is started, even when the app is in the background.
 * Each fix is passed to [RideGps.listener], which the open screen uses to update the ride.
 *
 * While the app is on screen the notification is removed; it is shown again when the app leaves the screen.
 */
class TripService : Service() {
    private lateinit var tracker: GpsTracker

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        tracker = GpsTracker(this) { fix -> RideGps.listener?.invoke(fix) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be promoted to foreground right away after startForegroundService().
        startForegroundCompat()
        if (!hasLocationPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        tracker.start()
        applyVisibility()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        tracker.stop()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Shows the notification when the app is off screen, and removes it while the app is on screen. */
    private fun applyVisibility() {
        if (appVisible) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            startForegroundCompat()
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            // IMPORTANCE_MIN keeps the icon out of the status bar; Android still requires the
            // foreground notification to exist, so it stays only in the collapsed shade.
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "მგზავრობა", NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
            )
        }
    }

    private fun buildNotification(): Notification {
        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("TAXI PRO")
            .setContentText("მგზავრობა მიმდინარეობს, GPS ჩართულია")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "trip_gps_min"
        private const val NOTIFICATION_ID = 1

        @Volatile
        private var instance: TripService? = null

        @Volatile
        private var appVisible = false

        /** Called by the activity when it starts or stops being on screen. */
        fun setAppVisible(visible: Boolean) {
            appVisible = visible
            instance?.applyVisibility()
        }

        fun start(context: Context) {
            val intent = Intent(context, TripService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TripService::class.java))
        }
    }
}

/** Hands GPS fixes from [TripService] to the screen that owns the ride. Set while the screen is shown. */
object RideGps {
    @Volatile
    var listener: ((GpsFix) -> Unit)? = null
}
