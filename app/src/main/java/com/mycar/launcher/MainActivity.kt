package com.mycar.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mycar.launcher.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var isNightMode = true
    private var isPlaying = false
    private var trackIndex = 0
    private var odometerKm = 48320.0
    private var tripKm = 0.0
    private var maxSpeedKmh = 0
    private var tripSeconds = 0
    private var lastLocation: Location? = null

    private val tracks = listOf(
        "Midnight Cruise • Synthwave",
        "Highway Breeze • Chill Acoustic",
        "Night Drive • Deep Focus",
        "Kuantan Coast • Lo-Fi Beats"
    )

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val speedKmh = (location.speed * 3.6f).toInt().coerceAtLeast(0)
            if (speedKmh > maxSpeedKmh) maxSpeedKmh = speedKmh

            lastLocation?.let { prev ->
                val deltaKm = prev.distanceTo(location) / 1000.0
                if (deltaKm in 0.001..5.0) {
                    tripKm += deltaKm
                    odometerKm += deltaKm
                }
            }
            lastLocation = location

            if (location.hasBearing()) {
                binding.headingMapView.headingDeg = location.bearing
            }
            updateTelemetryUi(speedKmh)
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val tickerRunnable = object : Runnable {
        override fun run() {
            val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            binding.tvClock.text = now
            if (isPlaying || tripSeconds > 0) {
                tripSeconds += 1
            }
            val currentSpeed = binding.tvSpeed.text.toString().toIntOrNull() ?: 0
            updateTelemetryUi(currentSpeed)
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemBars()
        requestPermissionsIfNeeded()
        setupControls()
        updateMusicUi()
        applyTheme()
        handler.post(tickerRunnable)
    }

    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        )
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        } else {
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            startLocationUpdates()
        }
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, locationListener)
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 5f, locationListener)
            }
        } catch (_: Exception) {
        }
    }

    private fun setupControls() {
        binding.btnTheme.setOnClickListener {
            isNightMode = !isNightMode
            applyTheme()
        }

        binding.btnApps.setOnClickListener {
            openInstalledAppsDialog()
        }

        binding.btnPlayPause.setOnClickListener {
            isPlaying = !isPlaying
            sendMediaKey(if (isPlaying) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE)
            updateMusicUi()
        }

        binding.btnNextTrack.setOnClickListener {
            trackIndex = (trackIndex + 1) % tracks.size
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            updateMusicUi()
        }

        binding.btnNextList.setOnClickListener {
            Toast.makeText(this, "Next playlist queued", Toast.LENGTH_SHORT).show()
            updateMusicUi()
        }
    }

    private fun sendMediaKey(keyCode: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun updateMusicUi() {
        val current = tracks[trackIndex]
        val next1 = tracks[(trackIndex + 1) % tracks.size]
        val next2 = tracks[(trackIndex + 2) % tracks.size]
        binding.tvTrack.text = if (isPlaying) "▶ $current" else "❚❚ $current"
        binding.tvNextQueue.text = "Next: $next1 • $next2"
        binding.btnPlayPause.text = if (isPlaying) "Pause" else "Play"
    }

    private fun updateTelemetryUi(speedKmh: Int) {
        val mins = tripSeconds / 60
        val secs = tripSeconds % 60
        val timeFormatted = String.format(Locale.getDefault(), "%02d:%02d", mins, secs)
        binding.tvSpeed.text = speedKmh.toString()
        binding.tvTripInfo.text = "ODO: ${String.format(Locale.getDefault(), "%,d", odometerKm.toInt())} km\n" +
            "TRIP: ${String.format(Locale.getDefault(), "%.1f", tripKm)} km  |  MAX: $maxSpeedKmh km/h\n" +
            "TIME: $timeFormatted"
    }

    private fun applyTheme() {
        binding.headingMapView.isNightMode = isNightMode
        val bgColor = ContextCompat.getColor(this, if (isNightMode) R.color.night_bg else R.color.day_bg)
        val cardColor = ContextCompat.getColor(this, if (isNightMode) R.color.night_card else R.color.day_card)
        val textColor = ContextCompat.getColor(this, if (isNightMode) R.color.night_text else R.color.day_text)
        val subColor = ContextCompat.getColor(this, if (isNightMode) R.color.night_subtext else R.color.day_subtext)
        val accentColor = ContextCompat.getColor(this, if (isNightMode) R.color.night_accent else R.color.day_accent)

        binding.rootLayout.setBackgroundColor(bgColor)
        binding.telemetryCard.setBackgroundColor(cardColor)
        binding.musicCard.setBackgroundColor(cardColor)
        binding.tvBrand.setTextColor(textColor)
        binding.tvClock.setTextColor(subColor)
        binding.tvSpeed.setTextColor(accentColor)
        binding.tvTripInfo.setTextColor(textColor)
        binding.tvTrack.setTextColor(textColor)
        binding.tvNextQueue.setTextColor(subColor)
        binding.btnTheme.text = if (isNightMode) "Day Mode" else "Night Mode"
    }

    private fun openInstalledAppsDialog() {
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = packageManager.queryIntentActivities(mainIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }

        val labels = resolveInfos.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("MYCAR Apps")
            .setItems(labels) { _, which ->
                val launchIntent = packageManager.getLaunchIntentForPackage(resolveInfos[which].activityInfo.packageName)
                if (launchIntent != null) {
                    startActivity(launchIntent)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tickerRunnable)
    }
}
