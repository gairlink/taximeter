package com.example.taximeter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps counting while the app is minimised / in background.
 *
 * WHY THIS EXISTS:
 * Before, GPS + the 1-second timer lived in MainActivity (Compose).
 * When you minimised the app, Android stopped giving the app GPS updates
 * (background location needs a foreground service) and paused the timer,
 * so km + minutes + fare froze until you reopened the app.
 *
 * NOW:
 * - The SERVICE owns GPS (GpsTracker) + the timer. It runs even when the app is minimised.
 * - It is the ONLY writer of ride progress: every fix / every second it loads the
 *   saved Snapshot, applies the change, and saves it back.
 * - MainActivity only READS the saved ride (polls it 1x/sec) and writes USER ACTIONS
 *   (start / board / pause / resume / finish). So there is no double counting.
 * - The notification keeps Android from killing the service and shows live fare/km.
 */
class RideTrackingService : Service() {

        companion object {
        const val ACTION_START = "com.example.taximeter.START"
        const val ACTION_STOP = "com.example.taximeter.STOP"
        const val ACTION_SHOW_OVERLAY = "com.example.taximeter.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.example.taximeter.HIDE_OVERLAY"
        private const val AUTO_WAIT_SECONDS = 30
        // Movement that proves the car really drove off (not GPS jitter).
        // Billing still uses 5m (MIN_STEP_M); auto-wait needs a bigger 15m so a
        // jitter jump while standing still does not cancel waiting.
        private const val AUTO_WAIT_MOVE_M = 15.0
        private const val CHANNEL_ID = "ride_tracking"
        private const val NOTIF_ID = 1

        fun start(context: android.content.Context) {
            val intent = Intent(context, RideTrackingService::class.java).setAction(ACTION_START)
            // startForegroundService is REQUIRED on Android 12+ for a foreground service.
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: android.content.Context) {
            val intent = Intent(context, RideTrackingService::class.java).setAction(ACTION_STOP)
            // FIX: startForegroundService, not startService - startService from
            // background (app minimised) is blocked on Android 12+ and the intent
            // is dropped. Same for setOverlayVisible below.
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        /** MainActivity calls this onPause (app minimised -> show) / onResume (back in app -> hide). */
        fun setOverlayVisible(context: android.content.Context, visible: Boolean) {
            val action = if (visible) ACTION_SHOW_OVERLAY else ACTION_HIDE_OVERLAY
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, RideTrackingService::class.java).setAction(action)
            )
        }
    }

    private lateinit var store: Store
    private var tracker: GpsTracker? = null
    private var anchor: GpsFix? = null
    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            synchronized(lock) {
                val snap = store.load()
                val ride = snap.ride
                if (ride.started && ride.running) {
                    store.save(snap.copy(ride = ride.copy(seconds = ride.seconds + 1)))
                    updateNotification(store.load())
                }
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        val channel = NotificationChannel(CHANNEL_ID, "მგზავრობა", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification(store.load()))
        // Anchor: resume from the last saved route point, so the first fix after
        // a service restart does not count a jump from (0,0) / a stale point.
        val last = store.load().ride.path.lastOrNull()
        anchor = last?.let { GpsFix(it.lat, it.lon, 10f) }
        tracker?.stop()
        tracker = GpsTracker(this) { fix -> onFix(fix) }.also { it.start() }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000L)
        return START_STICKY
    }

    private fun onFix(fix: GpsFix) {
        synchronized(lock) {
            val snap = store.load()
            val ride = snap.ride
            if (!ride.started) return
            val (newRide, newAnchor) = applyFix(ride, anchor, fix)
            anchor = newAnchor
            if (newRide != ride) {
                store.save(snap.copy(ride = newRide))
                updateNotification(store.load())
            }
        }
    }

    private fun buildNotification(snap: Snapshot): Notification {
        val ride = snap.ride
        val fare = ride.fare(snap.tariffs)
        val km = ride.totalMeters / 1000.0
        val text = if (ride.started) "€%.2f • %.3f კმ • %.1f წთ".format(fare, km, ride.totalSeconds / 60.0)
                   else "მგზავრობა"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("TAXI PRO — მგზავრობა მიმდინარეობს")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(snap: Snapshot) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(snap))
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        tracker?.stop()
        tracker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
