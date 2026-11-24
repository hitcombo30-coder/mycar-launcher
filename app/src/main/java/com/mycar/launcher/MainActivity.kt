package com.mycar.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mycar.launcher.databinding.ActivityMainBinding
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var isNightMode = true
    private var isPlaying = false
    private var queueIndex = 0

    private var odometerKm = 48320.0
    private var tripKm = 0.0
    private var maxSpeedKmh = 0
    private var driveSeconds = 0
    private var lastLocation: Location? = null
    private var currentLat: Double? = null
    private var currentLon: Double? = null

    private val playNextQueue = mutableListOf(
        "Midnight Cruise • Synthwave",
        "Highway Breeze • Chill Acoustic",
        "Night Drive • Deep Focus",
        "Kuantan Coast • Lo-Fi Beats",
        "Late Express • Smooth House"
    )

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            currentLat = location.latitude
            currentLon = location.longitude

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

            binding.surroundMapView.latitude = currentLat
            binding.surroundMapView.longitude = currentLon
            resolveRoadName(location.latitude, location.longitude)
            fetchWeather(location.latitude, location.longitude)
            updateTelemetryUi(speedKmh)
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val clockRunnable = object : Runnable {
        override fun run() {
            val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault())
            val sdfDate = SimpleDateFormat("EEE, dd MMM", Locale.getDefault())
            val now = Date()
            binding.tvBigClock.text = sdfTime.format(now)
            binding.tvDateLabel.text = "${sdfDate.format(now)} • ${binding.surroundMapView.areaLabel}"

            if (isPlaying) {
                driveSeconds += 1
            }
            val currentSpeed = binding.tvSpeed.text.toString().toIntOrNull() ?: 0
            updateTelemetryUi(currentSpeed)
            updateSyncStatus()
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
        updateMusicQueueUi()
        applyTheme()

        // Allow 1-2 minute startup sync cycle for BT/network, GPS, weather & queue
        handler.postDelayed({
            fetchWeather(currentLat ?: 3.8168, currentLon ?: 103.3317)
            updateSyncStatus()
        }, 1500L)

        handler.post(clockRunnable)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        startLocationUpdates()
        updateSyncStatus()
    }

    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        )
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun updateSyncStatus() {
        val online = isNetworkAvailable()
        binding.tvStatusPill.text = if (online) "BT/NET CONNECTED" else "WAITING BT/NET..."
    }

    private fun requestPermissionsIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 202)
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
        if (requestCode == 202) {
            startLocationUpdates()
        }
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            val lastKnown = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (lastKnown != null) {
                currentLat = lastKnown.latitude
                currentLon = lastKnown.longitude
                binding.surroundMapView.latitude = currentLat
                binding.surroundMapView.longitude = currentLon
                resolveRoadName(lastKnown.latitude, lastKnown.longitude)
            }
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 1f, locationListener)
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2500L, 5f, locationListener)
            }
        } catch (_: Exception) {
        }
    }

    private fun resolveRoadName(lat: Double, lon: Double) {
        executor.execute {
            try {
                val geocoder = Geocoder(this, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                val road = addresses?.firstOrNull()?.let { addr ->
                    addr.thoroughfare ?: addr.subLocality ?: addr.locality
                }
                if (!road.isNullOrBlank()) {
                    runOnUiThread {
                        binding.surroundMapView.areaLabel = road
                        binding.tvLocationHeader.text = "Jalan Sekitar: $road"
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun fetchWeather(lat: Double, lon: Double) {
        if (!isNetworkAvailable()) return
        executor.execute {
            try {
                val urlStr = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code"
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body).optJSONObject("current")
                    val temp = json?.optDouble("temperature_2m", Double.NaN) ?: Double.NaN
                    val code = json?.optInt("weather_code", -1) ?: -1
                    val desc = weatherCodeToLabel(code)
                    if (!temp.isNaN()) {
                        runOnUiThread {
                            binding.tvWeatherTemp.text = "${Math.round(temp)}°C"
                            binding.tvWeatherDesc.text = desc
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun weatherCodeToLabel(code: Int): String = when (code) {
        0 -> "Clear Sky"
        1, 2 -> "Partly Cloudy"
        3 -> "Overcast"
        45, 48 -> "Mist / Fog"
        51, 53, 55, 61, 63, 65 -> "Rain"
        80, 81, 82 -> "Rain Showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "Synced"
    }

    private fun setupControls() {
        binding.btnTheme.setOnClickListener {
            isNightMode = !isNightMode
            applyTheme()
        }

        binding.btnRestoreHome.setOnClickListener {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }

        binding.btnApps.setOnClickListener {
            openIconAppsGridDialog()
        }

        binding.btnPlayPause.setOnClickListener {
            isPlaying = !isPlaying
            sendMediaKey(if (isPlaying) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE)
            updateMusicQueueUi()
        }

        binding.btnNextTrack.setOnClickListener {
            advanceNextTrack()
        }

        binding.btnOpenYTM.setOnClickListener {
            launchYouTubeMusic()
        }
    }

    private fun advanceNextTrack() {
        if (playNextQueue.isNotEmpty()) {
            queueIndex = (queueIndex + 1) % playNextQueue.size
        }
        isPlaying = true
        sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        updateMusicQueueUi()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                advanceNextTrack()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (playNextQueue.isNotEmpty()) {
                    queueIndex = (queueIndex - 1 + playNextQueue.size) % playNextQueue.size
                }
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                updateMusicQueueUi()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                isPlaying = !isPlaying
                sendMediaKey(keyCode)
                updateMusicQueueUi()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun launchYouTubeMusic() {
        val ytmIntent = packageManager.getLaunchIntentForPackage("com.google.android.apps.youtube.music")
        if (ytmIntent != null) {
            startActivity(ytmIntent)
        } else {
            Toast.makeText(this, "YouTube Music not found on head unit", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendMediaKey(keyCode: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun updateMusicQueueUi() {
        val current = playNextQueue[queueIndex]
        val n1 = playNextQueue[(queueIndex + 1) % playNextQueue.size]
        val n2 = playNextQueue[(queueIndex + 2) % playNextQueue.size]
        val n3 = playNextQueue[(queueIndex + 3) % playNextQueue.size]

        binding.tvTrack.text = if (isPlaying) "▶ YTM • $current" else "❚❚ YTM • $current"
        binding.tvPlayNextList.text = "PlayNext: 1. $n1  |  2. $n2  |  3. $n3"
        binding.btnPlayPause.text = if (isPlaying) "Pause" else "Play"
    }

    private fun updateTelemetryUi(speedKmh: Int) {
        val mins = driveSeconds / 60
        val secs = driveSeconds % 60
        val timeFormatted = String.format(Locale.getDefault(), "%02d:%02d", mins, secs)
        binding.tvSpeed.text = speedKmh.toString()
        binding.tvTripInfo.text = "ODO: ${String.format(Locale.getDefault(), "%,d", odometerKm.toInt())} km
" +
            "TRIP: ${String.format(Locale.getDefault(), "%.1f", tripKm)} km  |  MAX: $maxSpeedKmh km/h
" +
            "DRIVE TIME: $timeFormatted"
    }

    private fun applyTheme() {
        binding.surroundMapView.isNightMode = isNightMode
        val bgColor = ContextCompat.getColor(this, if (isNightMode) R.color.night_bg else R.color.day_bg)
        val cardColor = ContextCompat.getColor(this, if (isNightMode) R.color.night_card else R.color.day_card)
        val textColor = ContextCompat.getColor(this, if (isNightMode) R.color.night_text else R.color.day_text)
        val subColor = ContextCompat.getColor(this, if (isNightMode) R.color.night_subtext else R.color.day_subtext)
        val accentColor = ContextCompat.getColor(this, if (isNightMode) R.color.night_accent else R.color.day_accent)

        binding.rootLayout.setBackgroundColor(bgColor)
        binding.clockWeatherCard.setBackgroundColor(cardColor)
        binding.telemetryCard.setBackgroundColor(cardColor)
        binding.mapCard.setBackgroundColor(cardColor)
        binding.musicCard.setBackgroundColor(cardColor)

        binding.tvBrand.setTextColor(accentColor)
        binding.tvBigClock.setTextColor(textColor)
        binding.tvDateLabel.setTextColor(subColor)
        binding.tvWeatherTemp.setTextColor(accentColor)
        binding.tvWeatherDesc.setTextColor(subColor)
        binding.tvSpeed.setTextColor(accentColor)
        binding.tvTripInfo.setTextColor(textColor)
        binding.tvLocationHeader.setTextColor(textColor)
        binding.tvTrack.setTextColor(textColor)
        binding.tvPlayNextList.setTextColor(subColor)
        binding.btnTheme.text = if (isNightMode) "Day Mode" else "Night Mode"
    }

    private fun openIconAppsGridDialog() {
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val appsList = packageManager.queryIntentActivities(mainIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }

        val recyclerView = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 4)
            setPadding(16, 16, 16, 16)
        }

        var dialogRef: AlertDialog? = null
        recyclerView.adapter = AppGridAdapter(appsList, isNightMode) { info ->
            dialogRef?.dismiss()
            val launchIntent = packageManager.getLaunchIntentForPackage(info.activityInfo.packageName)
            if (launchIntent != null) {
                startActivity(launchIntent)
            }
        }

        dialogRef = AlertDialog.Builder(this)
            .setTitle("MYCAR Apps")
            .setView(recyclerView)
            .setNegativeButton("Close", null)
            .create()
        dialogRef.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(clockRunnable)
        executor.shutdown()
    }
}
