package com.mycar.launcher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var lastLuxValue: Float? = null
    private var headlightSignalNight: Boolean? = null

    private var isNightMode = true
    private var isRadioMode = false
    private var isPlaying = false
    private var playlistIndex = 0
    private var trackIndex = 0

    private var odometerKm = 48320.0
    private var tripKm = 0.0
    private var maxSpeedKmh = 0
    private var driveSeconds = 0
    private var lastLocation: Location? = null
    private var currentLat: Double? = null
    private var currentLon: Double? = null

    private val ytmPlaylists = listOf(
        listOf("Highway Breeze - Chill Acoustic", "Synthwave Cruise", "Lo-Fi Beats", "Night Drive"),
        listOf("Pantai Timur Acoustic", "Midnight Express", "Smooth Lounge", "City Lights Mix")
    )

    private val radioPlaylists = listOf(
        listOf("FM 96.4 - Kuantan Drive Radio", "FM 102.8 - Coastal Hits", "FM 88.9 - Chill & Jazz FM", "FM 94.5 - Late Night Talk"),
        listOf("FM 105.2 - Classic Rock & Pop", "FM 91.1 - Morning Cruiser", "FM 99.7 - Acoustic Waves", "FM 107.3 - Weekend Vibes")
    )

    private val illuminationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action.contains("ILLUM", true) || action.contains("LIGHT", true) || action.contains("HEADLIGHT", true)) {
                val stateInt = intent.getIntExtra("state", intent.getIntExtra("illum", -1))
                val stateBool = intent.getBooleanExtra("enabled", false)
                headlightSignalNight = when {
                    stateInt == 1 -> true
                    stateInt == 0 -> false
                    intent.hasExtra("enabled") -> stateBool
                    else -> headlightSignalNight
                }
                updateAutoDayNightTheme()
                updateSyncStatus()
            }
        }
    }

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

            if (location.hasBearing() && speedKmh > 1) {
                binding.surroundMapView.bearing = location.bearing
            }
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
            binding.tvDateLabel.text = "${sdfDate.format(now)} -${binding.surroundMapView.areaLabel}"

            if (isPlaying) driveSeconds += 1
            val currentSpeed = binding.tvSpeed.text.toString().toIntOrNull() ?: 0
            updateTelemetryUi(currentSpeed)
            updateAutoDayNightTheme()
            updateSyncStatus()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

        hideSystemBars()
        requestPermissionsIfNeeded()
        setupControls()
        updateMusicQueueUi()
        updateAutoDayNightTheme()

        handler.postDelayed({
            fetchWeather(currentLat ?: 3.8168, currentLon ?: 103.3317)
            updateAutoDayNightTheme()
            updateSyncStatus()
        }, 1500L)

        handler.post(clockRunnable)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        registerIlluminationReceiver()
        lightSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        startLocationUpdates()
        updateAutoDayNightTheme()
        updateSyncStatus()
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
        try {
            unregisterReceiver(illuminationReceiver)
        } catch (_: Exception) {
        }
    }

    private fun registerIlluminationReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction("android.intent.action.ILLUMINATION_CHANGED")
                addAction("com.android.systemui.ILLUM_CHANGE")
                addAction("android.car.intent.action.ILLUMINATION_CHANGED")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(illuminationReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(illuminationReceiver, filter)
            }
        } catch (_: Exception) {
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
            lastLuxValue = event.values.firstOrNull()
            updateAutoDayNightTheme()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun computeShouldBeNight(): Boolean {
        headlightSignalNight?.let { return it }
        val lux = lastLuxValue
        if (lux != null) return lux < 25f
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour < 7 || hour >= 19
    }

    private fun updateAutoDayNightTheme() {
        val shouldNight = computeShouldBeNight()
        if (shouldNight != isNightMode || binding.tvStatusPill.text.isNullOrEmpty()) {
            isNightMode = shouldNight
            applyTheme()
        }
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
        val modeTag = when {
            headlightSignalNight == true -> "LAMP NIGHT"
            headlightSignalNight == false -> "LAMP DAY"
            isNightMode -> "AUTO NIGHT"
            else -> "AUTO DAY"
        }
        val netTag = if (online) "BT/NET OK" else "WAITING BT/NET..."
        binding.tvStatusPill.text = "$modeTag -$netTag"
    }

    private fun requestPermissionsIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 202)
        } else {
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 202) startLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
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
                val road = addresses?.firstOrNull()?.let { it.thoroughfare ?: it.subLocality ?: it.locality }
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

    private fun activePlaylists(): List<List<String>> = if (isRadioMode) radioPlaylists else ytmPlaylists

    private fun currentPlaylist(): List<String> {
        val lists = activePlaylists()
        return lists[playlistIndex % lists.size]
    }

    private fun setupControls() {
        binding.btnRestoreHome.setOnClickListener { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        binding.btnApps.setOnClickListener { openIconAppsGridDialog() }
        binding.btnNextList.setOnClickListener { advanceNextPlaylist() }
        binding.btnPlayPause.setOnClickListener {
            isPlaying = !isPlaying
            if (!isRadioMode) sendMediaKey(if (isPlaying) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE)
            updateMusicQueueUi()
        }
        binding.btnNextTrack.setOnClickListener { advanceNextTrack() }
        binding.btnSwitchSource.setOnClickListener {
            isRadioMode = !isRadioMode
            playlistIndex = 0
            trackIndex = 0
            if (!isRadioMode && isPlaying) sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            else if (isRadioMode) sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            updateMusicQueueUi()
        }
    }

    private fun advanceNextPlaylist() {
        val lists = activePlaylists()
        playlistIndex = (playlistIndex + 1) % lists.size
        trackIndex = 0
        isPlaying = true
        if (!isRadioMode) sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        updateMusicQueueUi()
    }

    private fun advanceNextTrack() {
        val queue = currentPlaylist()
        if (queue.isNotEmpty()) trackIndex = (trackIndex + 1) % queue.size
        isPlaying = true
        if (!isRadioMode) sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        updateMusicQueueUi()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                advanceNextTrack()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                val queue = currentPlaylist()
                if (queue.isNotEmpty()) trackIndex = (trackIndex - 1 + queue.size) % queue.size
                if (!isRadioMode) sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                updateMusicQueueUi()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                isPlaying = !isPlaying
                if (!isRadioMode) sendMediaKey(keyCode)
                updateMusicQueueUi()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun sendMediaKey(keyCode: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun updateMusicQueueUi() {
        val queue = currentPlaylist()
        val current = queue[trackIndex % queue.size]
        val n1 = queue[(trackIndex + 1) % queue.size]
        val n2 = queue[(trackIndex + 2) % queue.size]
        val sourceTag = if (isRadioMode) "RADIO" else "YTM"
        val stateIcon = if (isPlaying) "PLAY" else "PAUSE"

        binding.tvTrack.text = "[$stateIcon] $sourceTag -$current"
        binding.tvPlayNextList.text = "PlayNext: 1. $n1  \vert{}  2.$n2"
        binding.btnPlayPause.text = if (isPlaying) "Pause" else "Play"
        binding.btnSwitchSource.text = if (isRadioMode) "YTM" else "Radio"
    }

    private fun updateTelemetryUi(speedKmh: Int) {
        val mins = driveSeconds / 60
        val secs = driveSeconds % 60
        val timeFormatted = String.format(Locale.getDefault(), "%02d:%02d", mins, secs)
        binding.tvSpeed.text = speedKmh.toString()
        binding.tvOdoValue.text = "${String.format(Locale.getDefault(), "%,d", odometerKm.toInt())} km"
        binding.tvTripSub.text = "${String.format(Locale.getDefault(), "%.1f", tripKm)} km"
        binding.tvDriveTimeValue.text = timeFormatted
        binding.tvMaxSub.text = "MAX $maxSpeedKmh km/h"
    }

    private fun styleCard(view: View, cardColor: Int, borderColor: Int) {
        val gd = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * resources.displayMetrics.density
            setColor(cardColor)
            setStroke((1f * resources.displayMetrics.density).toInt(), borderColor)
        }
        view.background = gd
    }

    private fun applyTheme() {
        binding.surroundMapView.isNightMode = isNightMode
        val bgColor = ContextCompat.getColor(this, if (isNightMode) R.color.night_bg else R.color.day_bg)
        val cardColor = ContextCompat.getColor(this, if (isNightMode) R.color.night_card else R.color.day_card)
        val borderColor = ContextCompat.getColor(this, if (isNightMode) R.color.night_border else R.color.day_border)
        val textColor = ContextCompat.getColor(this, if (isNightMode) R.color.night_text else R.color.day_text)
        val subColor = ContextCompat.getColor(this, if (isNightMode) R.color.night_subtext else R.color.day_subtext)
        val accentColor = ContextCompat.getColor(this, if (isNightMode) R.color.night_accent else R.color.day_accent)
        val pillColor = ContextCompat.getColor(this, if (isNightMode) R.color.night_pill else R.color.day_pill)

        binding.rootLayout.setBackgroundColor(bgColor)
        styleCard(binding.clockWeatherCard, cardColor, borderColor)
        styleCard(binding.telemetryCard, cardColor, borderColor)
        styleCard(binding.mapCard, cardColor, borderColor)
        styleCard(binding.musicCard, cardColor, borderColor)

        val pillBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(pillColor)
        }
        binding.tvStatusPill.background = pillBg

        binding.tvBrand.setTextColor(accentColor)
        binding.tvCardClockTitle.setTextColor(subColor)
        binding.tvBigClock.setTextColor(textColor)
        binding.tvDateLabel.setTextColor(subColor)
        binding.tvWeatherTemp.setTextColor(accentColor)
        binding.tvWeatherDesc.setTextColor(subColor)
        binding.tvSpeedLabel.setTextColor(subColor)
        binding.tvSpeed.setTextColor(accentColor)
        binding.tvSpeedUnit.setTextColor(subColor)
        binding.tvOdoLabel.setTextColor(subColor)
        binding.tvOdoValue.setTextColor(textColor)
        binding.tvTripSub.setTextColor(subColor)
        binding.tvTripLabel.setTextColor(subColor)
        binding.tvDriveTimeValue.setTextColor(textColor)
        binding.tvMaxSub.setTextColor(subColor)
        binding.tvMapTitle.setTextColor(textColor)
        binding.tvLocationHeader.setTextColor(subColor)
