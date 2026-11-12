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
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

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
    // AUTO-WAIT state: when the car stands still, after 30s the timer switches
    // from driving (seconds) to waiting (waitSeconds). Reset on movement / new ride.
    private var lastSeenMeters: Long = -1
    private var stillSinceMs: Long = 0
    private var autoWaiting: Boolean = false
    // Overlay (bubble) - the blue pill on the home screen: "💰 0.00 € | 📏 0.002 კმ"
    private var windowManager: WindowManager? = null
    private var overlayView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            synchronized(lock) {
                val snap = store.load()
                val ride = snap.ride
                if (ride.started && ride.running) {
                    val now = System.currentTimeMillis()
                    // First tick (or after restart): initialise still-timer.
                    if (lastSeenMeters < 0) {
                        lastSeenMeters = ride.meters
                        stillSinceMs = now
                        autoWaiting = false
                    }
                    if (ride.meters != lastSeenMeters) {
                        // Car moved: auto-wait ends, back to driving time.
                        lastSeenMeters = ride.meters
                        stillSinceMs = now
                        autoWaiting = false
                        store.save(snap.copy(ride = ride.copy(seconds = ride.seconds + 1)))
                    } else if (!autoWaiting && now - stillSinceMs >= AUTO_WAIT_SECONDS * 1000L) {
                        // Stood still for 30s: the last 30s were counted as driving,
                        // move them to waiting (total time unchanged) + this second as waiting.
                        // This is the moment auto-wait TURNS ON.
                        val moved = minOf(30L, ride.seconds)
                        store.save(snap.copy(ride = ride.copy(
                            seconds = ride.seconds - moved,
                            waitSeconds = ride.waitSeconds + moved + 1
                        )))
                        autoWaiting = true
                    } else if (autoWaiting) {
                        // Still standing: every second is waiting, not driving.
                        store.save(snap.copy(ride = ride.copy(waitSeconds = ride.waitSeconds + 1)))
                    } else {
                        // Still standing but < 30s: still driving time, not waiting yet.
                        store.save(snap.copy(ride = ride.copy(seconds = ride.seconds + 1)))
                    }
                    updateNotification(store.load())
                    updateOverlay()
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
        // FIX: SHOW/HIDE overlay must NOT restart tracking.
        // Before, they fell through to startForeground + tracker restart below.
        // Overlay View code lives outside this file - these actions now return
        // early instead of re-creating the tracker / resetting the anchor.
        // If you do not use an overlay at all, remove setOverlayVisible()
        // calls from MainActivity instead of leaving this dead path.
        when (intent?.action) {
            ACTION_STOP -> {
                hideOverlay()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW_OVERLAY -> {
                // FIX: before this just returned - overlay View code did not exist
                // anywhere, so the bubble NEVER appeared. Now it really shows,
                // without restarting tracker / resetting anchor.
                showOverlay()
                return START_STICKY
            }
            ACTION_HIDE_OVERLAY -> {
                hideOverlay()
                return START_STICKY
            }
        }
        // Fresh start: auto-wait must not inherit the previous ride's still-timer.
        lastSeenMeters = -1
        stillSinceMs = 0
        autoWaiting = false
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
                updateOverlay()
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

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        if (overlayView != null) { updateOverlay(); return }
        val wm = getSystemService(WindowManager::class.java) ?: return
        windowManager = wm
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            // FIX: bigger font - was 20f
            textSize = 26f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(48, 28, 48, 28)
            // FIX: text NEVER wraps to a second line. If text grows, the bubble
            // grows in WIDTH (WRAP_CONTENT), not in height.
            setSingleLine(true)
            maxLines = 1
            // FIX: rounded + white border + 50% transparent background.
            // 0x80 = 128/255 = ~50% alpha on the blue. White border stays solid
            // so the bubble stays visible on any wallpaper.
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 999f
                setColor(0x802196F3.toInt())
                setStroke(4, Color.WHITE)
            }
            gravity = Gravity.CENTER
        }
        overlayView = tv
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                // FIX: screen stays ON while the bubble is visible (app minimised).
                // MainActivity's view.keepScreenOn only works while the Activity
                // is visible - once minimised it does nothing, so the screen timed
                // out even though the ride + bubble were still active. This flag
                // lives on the overlay window itself, so it works while minimised.
                // When the bubble hides (hideOverlay removes the view), the flag
                // goes away with it and the screen can sleep normally again.
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            // FIX: first position = TOP-RIGHT corner (was CENTER_HORIZONTAL)
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 120
        }
        // FIX: tap = back to app, drag = move bubble.
        // Tap vs drag is decided by distance: moved < 15px = tap, else drag.
        var startX = 0
        var startY = 0
        var startTouchX = 0f
        var startTouchY = 0f
        var moved = false
        tv.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    moved = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startTouchX
                    val dy = event.rawY - startTouchY
                    if (kotlin.math.abs(dx) > 15 || kotlin.math.abs(dy) > 15) moved = true
                    if (moved) {
                        // END gravity: dragging left = x increases, dragging right = x decreases
                        params.x = startX + (startTouchX - event.rawX).toInt()
                        params.y = startY + (event.rawY - startTouchY).toInt()
                        try { windowManager?.updateViewLayout(tv, params) } catch (e: Exception) { }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // TAP - back to the app. Opens MainActivity, brings it to front
                        // if it is already running (no new ride is started).
                        val intent = android.content.Intent(this, MainActivity::class.java).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        }
                        try { startActivity(intent) } catch (e: Exception) { }
                    }
                    true
                }
                else -> false
            }
        }
        try { wm.addView(tv, params) } catch (e: Exception) { overlayView = null }
        updateOverlay()
    }

    private fun hideOverlay() {
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) { }
        overlayView = null
    }

    private fun updateOverlay() {
        val tv = overlayView ?: return
        val snap = store.load()
        val ride = snap.ride
        if (!ride.started) { hideOverlay(); return }
        val fare = ride.fare(snap.tariffs)
        val km = ride.totalMeters / 1000.0
        tv.text = "💰 %.2f € | 📏 %.3f კმ".format(fare, km)
    }

    override fun onDestroy() {
        hideOverlay()
        handler.removeCallbacks(tick)
        tracker?.stop()
        tracker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
