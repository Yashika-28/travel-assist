package com.example.taskmanager

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow

class LocationAlarmService : Service() {

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    private var destinationLat: Double = 0.0
    private var destinationLon: Double = 0.0
    private var targetRadius: Float = 500f

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    // --- OPTIMIZATION: Reuse FloatArray across GPS ticks (zero-allocation hot path) ---
    private val distanceResult = FloatArray(1)

    // --- OPTIMIZATION: Cache system service references ---
    private var notificationManager: NotificationManager? = null

    // --- OPTIMIZATION: Throttle notification updates to reduce IPC overhead ---
    private var lastNotificationUpdateMs: Long = 0L

    // --- OPTIMIZATION: Adaptive GPS accuracy based on proximity ---
    private var currentPriority: Int = Priority.PRIORITY_BALANCED_POWER_ACCURACY

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_START_TRACKING -> {
                val destLat = intent.getDoubleExtra(EXTRA_DEST_LAT, 0.0)
                val destLon = intent.getDoubleExtra(EXTRA_DEST_LON, 0.0)
                val destName = intent.getStringExtra(EXTRA_DEST_NAME) ?: "Destination"
                val destRadius = intent.getFloatExtra(EXTRA_DEST_RADIUS, 500f)

                startTracking(destLat, destLon, destName, destRadius)
            }
            ACTION_STOP_TRACKING -> {
                stopTracking()
                stopSelf()
            }
            ACTION_DISMISS_ALARM -> {
                dismissAlarm()
                stopTracking()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startTracking(lat: Double, lon: Double, name: String, radius: Float) {
        destinationLat = lat
        destinationLon = lon
        destinationName.value = name
        targetRadius = radius

        isTracking.value = true
        alarmTriggered.value = false
        currentDistance.value = null
        lastNotificationUpdateMs = 0L

        val notification = buildNotification("Starting location tracking...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startLocationUpdates(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
    }

    private fun stopTracking() {
        stopLocationUpdates()
        stopAlarm()
        isTracking.value = false
        alarmTriggered.value = false
        currentDistance.value = null
        destinationName.value = ""
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun dismissAlarm() {
        stopAlarm()
        alarmTriggered.value = false
    }

    private fun startLocationUpdates(priority: Int) {
        if (fusedLocationClient == null) return
        currentPriority = priority

        // Adaptive interval: faster polling when closer
        val interval = if (priority == Priority.PRIORITY_HIGH_ACCURACY) 3000L else 6000L
        val minInterval = if (priority == Priority.PRIORITY_HIGH_ACCURACY) 1500L else 3000L

        val locationRequest = LocationRequest.Builder(priority, interval).apply {
            setMinUpdateIntervalMillis(minInterval)
            setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                checkDistance(location)
            }
        }

        try {
            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (unlikely: SecurityException) {
            Log.e("LocationAlarmService", "Lost location permission: $unlikely")
            stopTracking()
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient?.removeLocationUpdates(it)
        }
        locationCallback = null
    }

    private fun checkDistance(location: Location) {
        // --- OPTIMIZATION: Reuse pre-allocated FloatArray ---
        Location.distanceBetween(
            location.latitude,
            location.longitude,
            destinationLat,
            destinationLon,
            distanceResult
        )
        val distance = distanceResult[0]
        currentDistance.value = distance

        // --- OPTIMIZATION: Adaptive GPS accuracy switching ---
        // Switch to high accuracy when within 2km, back to balanced when far away
        val desiredPriority = if (distance <= 2000f) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        if (desiredPriority != currentPriority) {
            stopLocationUpdates()
            startLocationUpdates(desiredPriority)
            return // New callback will handle next tick
        }

        if (distance <= targetRadius) {
            if (!alarmTriggered.value) {
                triggerAlarm()
            }
            // Always update notification immediately on alarm
            val text = formatDistance(distance)
            updateNotification("\uD83D\uDEA8 Arrived! Within $text of ${destinationName.value}!", force = true)
        } else {
            val text = formatDistance(distance)
            updateNotification("Remaining: $text to ${destinationName.value}")
        }
    }

    // --- OPTIMIZATION: Avoid String.format (uses Locale & regex internally) ---
    private fun formatDistance(distance: Float): String {
        return if (distance >= 1000f) {
            val km = distance / 1000f
            "${(km * 100).toInt() / 100f} km"
        } else {
            "${distance.toInt()} m"
        }
    }

    private fun triggerAlarm() {
        alarmTriggered.value = true
        startAlarmSound()
        startVibration()
    }

    private fun startAlarmSound() {
        if (mediaPlayer == null) {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, alarmUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        }
    }

    private fun startVibration() {
        if (vibrator == null) {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = longArrayOf(0, 800, 400, 800)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        }
    }

    private fun stopAlarm() {
        mediaPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null
        vibrator?.cancel()
        vibrator = null
    }

    // --- OPTIMIZATION: Throttled notification updates (max once per 3s unless forced) ---
    private fun updateNotification(contentText: String, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && (now - lastNotificationUpdateMs) < NOTIFICATION_THROTTLE_MS) return
        lastNotificationUpdateMs = now
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MetroNap Location Alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Real-time location monitoring and proximity alerts"
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val stopIntent = Intent(this, LocationAlarmService::class.java).apply {
            action = ACTION_STOP_TRACKING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(this, LocationAlarmService::class.java).apply {
            action = ACTION_DISMISS_ALARM
        }
        val dismissPendingIntent = PendingIntent.getService(
            this, 2, dismissIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, mainActivityIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (alarmTriggered.value) "\uD83D\uDEA8 Wake Up!" else "MetroNap Tracking")
            .setContentText(contentText)
            .setContentIntent(contentPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)

        if (alarmTriggered.value) {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPendingIntent)
        } else {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
        }

        return builder.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTracking()
    }

    companion object {
        private const val NOTIFICATION_THROTTLE_MS = 3000L

        const val CHANNEL_ID = "location_alarm_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_TRACKING = "ACTION_START_TRACKING"
        const val ACTION_STOP_TRACKING = "ACTION_STOP_TRACKING"
        const val ACTION_DISMISS_ALARM = "ACTION_DISMISS_ALARM"

        const val EXTRA_DEST_LAT = "EXTRA_DEST_LAT"
        const val EXTRA_DEST_LON = "EXTRA_DEST_LON"
        const val EXTRA_DEST_NAME = "EXTRA_DEST_NAME"
        const val EXTRA_DEST_RADIUS = "EXTRA_DEST_RADIUS"

        val currentDistance = MutableStateFlow<Float?>(null)
        val isTracking = MutableStateFlow(false)
        val alarmTriggered = MutableStateFlow(false)
        val destinationName = MutableStateFlow("")
    }
}
