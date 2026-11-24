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
import android.webkit.WebSettings
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

    private var mapLoadedOnce = false
    private var lastActionTimestampMs = 0L

    private var isNightMode = true
    private var isRadioMode = false
    private var isPlaying = false
    private var playlistIndex = 0
    private var trackIndex = 0

    private var tripKm = 0.0
    private var maxSpeedKmh = 0
    private var movingSeconds = 0
    private var lastLocation: Location? = null
    private var currentLat: Double = 3.8168
    private var currentLon: Double = 103.3317
    private var currentBearing: Float = 0f

    private val ytmPlaylists = listOf(
        listOf(
            "Highway Breeze - Chill Acoustic",
            "Synthwave Cruise",
            "Lo-Fi Beats",
            "Night Drive"
        ),
        listOf(
            "Coastal Highway Mix",
            "Midnight Express",
            "Smooth Lounge",
            "City Lights Mix"
        ),
        listOf(
            "North-South Cruise",
            "Sunset Drive",
            "Deep Focus Beats",
            "After Midnight"
        )
    )

    private val radioAntennaStations = listOf(
        "FM Radio (Antenna)",
        "FM 96.4 (Antenna)",
        "FM 102.8 (Antenna)",
        "FM 88.9 (Antenna)"
    )

    private val illuminationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action.contains("ILLUM", true) || action.contains("LIGHT", true) || action.contains("HEADLIGHT", true)) {
                val stateInt = intent.getIntExtra("state", intent.getIntExtra("illum", -1))
                val stateBool = intent.getBooleanExtra("enabled", false)
                // Headlight ON = dark mode (true), Headlight OFF = bright mode (false)
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
                }
            }
            lastLocation = location

            if (location.hasBearing() && speedKmh > 1) {
                currentBearing = location.bearing
            }

            updateOpenStreetMapLocation(currentLat, currentLon, currentBearing)
            resolveRoadName(currentLat, currentLon)
            fetchWeather(currentLat, currentLon)
            updateTelemetryUi(speedKmh)
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val clockRunnable = object : Runnable {
        override fun run() {
            val sdfTime = SimpleDateFormat("HH:mm", Locale.ENGLISH)
            val sdfDate = SimpleDateFormat("EEE, dd MMM", Locale.ENGLISH)
            val now = Date()
            binding.tvBigClock.text = sdfTime.format(now)
            val locClean = binding.tvLocationHeader.text.toString().removePrefix("Location: ")
            binding.tvDateLabel.text = "${sdfDate.format(now)} - $locClean"
            binding.analogClockView.invalidate()

            val currentSpeed = binding.tvSpeed.text.toString().toIntOrNull() ?: 0
            if (currentSpeed > 0) {
                movingSeconds += 1
            }
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
        setupOsmWebView()
        requestPermissionsIfNeeded()
        setupControls()
        updateMusicQueueUi()
        updateAutoDayNightTheme()

        handler.postDelayed({
            fetchWeather(currentLat, currentLon)
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

    private fun setupOsmWebView() {
        binding.osmWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        loadOsmHtml(currentLat, currentLon, currentBearing)
    }

    private fun loadOsmHtml(lat: Double, lon: Double, bearing: Float) {
        mapLoadedOnce = true
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"/>
              <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
              <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
              <style>
                html, body, #map { width: 100%; height: 100%; margin: 0; padding: 0; background: #0B1220; }
                .car-arrow {
                  width: 0; height: 0;
                  border-left: 11px solid transparent;
                  border-right: 11px solid transparent;
                  border-bottom: 24px solid #E5C158;
                  filter: drop-shadow(0 0 4px rgba(0,0,0,0.85));
                  transform-origin: center center;
                }
              </style>
            </head>
            <body>
              <div id="map"></div>
              <script>
                var map = L.map('map', { zoomControl: false, attributionControl: false }).setView([$lat, $lon], 16);
                L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19 }).addTo(map);
                var iconHtml = '<div id="carArrow" class="car-arrow" style="transform: rotate(' + $bearing + 'deg);"></div>';
                var carIcon = L.divIcon({ html: iconHtml, className: '', iconSize: [22, 24], iconAnchor: [11, 12] });
                var marker = L.marker([$lat, $lon], { icon: carIcon }).addTo(map);
                window.updateCarPos = function(newLat, newLon, newBearing) {
                  map.panTo([newLat, newLon], { animate: true, duration: 0.5 });
                  marker.setLatLng([newLat, newLon]);
                  var el = document.getElementById('carArrow');
                  if (el) el.style.transform = 'rotate(' + newBearing + 'deg)';
                };
              </script>
            </body>
            </html>
        """.trimIndent()
        binding.osmWebView.loadDataWithBaseURL("https://tile.openstreetmap.org", html, "text/html", "UTF-8", null)
    }

    private fun updateOpenStreetMapLocation(lat: Double, lon: Double, bearing: Float) {
        if (!mapLoadedOnce) {
            loadOsmHtml(lat, lon, bearing)
            return
        }
        binding.osmWebView.evaluateJavascript("if(window.updateCarPos){window.updateCarPos($lat,$lon,$bearing);}", null)
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
            headlightSignalNight == true -> "LAMP ON (DARK)"
            headlightSignalNight == false -> "LAMP OFF (BRIGHT)"
            isNightMode -> "AUTO NIGHT"
            else -> "AUTO DAY"
        }
        val netTag = if (online) "BT/NET OK" else "WAITING BT/NET..."
        binding.tvStatusPill.text = "$modeTag - $netTag"
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
                updateOpenStreetMapLocation(currentLat, currentLon, currentBearing)
                resolveRoadName(currentLat, currentLon)
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
                val geocoder = Geocoder(this, Locale.ENGLISH)
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                val road = addresses?.firstOrNull()?.let { it.thoroughfare ?: it.subLocality ?: it.locality }
                if (!road.isNullOrBlank()) {
                    runOnUiThread {
                        binding.tvLocationHeader.text = "Location: $road"
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
                    val icon = weatherCodeToIcon(code)
                    if (!temp.isNaN()) {
                        runOnUiThread {
                            binding.tvWeatherIcon.text = icon
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

    private fun weatherCodeToIcon(code: Int): String = when (code) {
        0 -> "☀"
        1, 2 -> "⛅"
        3 -> "☁"
        45, 48 -> "🌫"
        51, 53, 55, 61, 63, 65 -> "🌧"
        80, 81, 82 -> "🌦"
        95, 96, 99 -> "⛈"
        else -> "✦"
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

    private fun isDebouncedAction(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastActionTimestampMs < 220L) return true
        lastActionTimestampMs = now
        return false
    }

    private fun currentYtmList(): List<String> = ytmPlaylists[playlistIndex % ytmPlaylists.size]

    private fun setupControls() {
        binding.btnRestoreHome.setOnClickListener {
            safeStartIntent(Intent(Settings.ACTION_HOME_SETTINGS))
        }
        binding.btnApps.setOnClickListener {
            openIconAppsGridDialog()
        }

        binding.btnNextList.setOnClickListener {
            if (isDebouncedAction()) return@setOnClickListener
            advanceNextPlaylist()
        }

        binding.btnPlayPause.setOnClickListener {
            if (isDebouncedAction()) return@setOnClickListener
            togglePlayPauseYtm()
        }

        binding.btnPlayPause.setOnLongClickListener {
            holdPlayYtm()
            true
        }

        binding.btnNextTrack.setOnClickListener {
            if (isDebouncedAction()) return@setOnClickListener
            advanceNextTrack()
        }

        binding.btnSwitchSource.setOnClickListener {
            if (isDebouncedAction()) return@setOnClickListener
            switchToRadioOrYtm()
        }
    }

    private fun safeStartIntent(intent: Intent?) {
        if (intent == null) return
        handler.post {
            try {
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, "Unable to open app", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun switchToRadioOrYtm() {
        if (!isRadioMode) {
            isRadioMode = true
            isPlaying = true
            trackIndex = 0
            updateMusicQueueUi()
            sendMediaKeySafe(KeyEvent.KEYCODE_MEDIA_PAUSE)
            launchCarAntennaRadio()
        } else {
            isRadioMode = false
            isPlaying = true
            updateMusicQueueUi()
            holdPlayYtm()
        }
    }

    private fun launchCarAntennaRadio() {
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val resolved = packageManager.queryIntentActivities(mainIntent, 0)
            val radioApp = resolved.firstOrNull { info ->
                val label = info.loadLabel(packageManager).toString().lowercase(Locale.ENGLISH)
                val pkg = info.activityInfo.packageName.lowercase(Locale.ENGLISH)
                label.contains("radio") || label.contains("fm") || pkg.contains("radio") || pkg.contains("fm")
            }
            if (radioApp != null) {
                val launch = packageManager.getLaunchIntentForPackage(radioApp.activityInfo.packageName)
                if (launch != null) {
                    safeStartIntent(launch)
                    return
                }
            }
            Toast.makeText(this, "Antenna Radio mode active", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Antenna Radio mode active", Toast.LENGTH_SHORT).show()
        }
    }

    private fun holdPlayYtm() {
        isRadioMode = false
        isPlaying = true
        updateMusicQueueUi()
        sendMediaKeySafe(KeyEvent.KEYCODE_MEDIA_PLAY)
        val ytmIntent = packageManager.getLaunchIntentForPackage("com.google.android.apps.youtube.music")
            ?: packageManager.getLaunchIntentForPackage("app.revanced.android.apps.youtube.music")
        if (ytmIntent != null) {
            ytmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            safeStartIntent(ytmIntent)
        }
    }

    private fun togglePlayPauseYtm() {
        isRadioMode = false
        isPlaying = !isPlaying
        updateMusicQueueUi()
        sendMediaKeySafe(if (isPlaying) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    private fun advanceNextPlaylist() {
        isRadioMode = false
        playlistIndex = (playlistIndex + 1) % ytmPlaylists.size
        trackIndex = 0
        isPlaying = true
        updateMusicQueueUi()
        sendMediaKeySafe(KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    private fun advanceNextTrack() {
        if (isRadioMode) {
            trackIndex = (trackIndex + 1) % radioAntennaStations.size
        } else {
            val queue = currentYtmList()
            trackIndex = (trackIndex + 1) % queue.size
            isPlaying = true
            sendMediaKeySafe(KeyEvent.KEYCODE_MEDIA_NEXT)
        }
        updateMusicQueueUi()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                advanceNextTrack()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                val queue = currentYtmList()
                trackIndex = (trackIndex - 1 + queue.size) % queue.size
                isRadioMode = false
                updateMusicQueueUi()
                sendMediaKeySafe(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                togglePlayPauseYtm()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun sendMediaKeySafe(keyCode: Int) {
        handler.post {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            } catch (_: Exception) {
            }
        }
    }

    private fun updateMusicQueueUi() {
        val stateIcon = if (isPlaying) "PLAY" else "PAUSE"
        if (isRadioMode) {
            val cur = radioAntennaStations[trackIndex % radioAntennaStations.size]
            val n1 = radioAntennaStations[(trackIndex + 1) % radioAntennaStations.size]
            val n2 = radioAntennaStations[(trackIndex + 2) % radioAntennaStations.size]
            binding.tvTrack.text = "[$stateIcon] RADIO ANT - $cur"
            binding.tvPlayNextList.text = "Next: 1. $n1  |  2. $n2"
            binding.btnSwitchSource.text = "YTM"
        } else {
            val queue = currentYtmList()
            val cur = queue[trackIndex % queue.size]
            val n1 = queue[(trackIndex + 1) % queue.size]
            val n2 = queue[(trackIndex + 2) % queue.size]
            binding.tvTrack.text = "[$stateIcon] YTM LIST #${(playlistIndex % ytmPlaylists.size) + 1} - $cur"
            binding.tvPlayNextList.text = "Next: 1. $n1  |  2. $n2"
            binding.btnSwitchSource.text = "Radio"
        }
        binding.btnPlayPause.text = if (isPlaying) "Pause" else "Play"
    }

    private fun updateTelemetryUi(speedKmh: Int) {
        val avgKmh = if (movingSeconds > 0) (tripKm / (movingSeconds / 3600.0)) else 0.0
        binding.tvSpeed.text = speedKmh.toString()
        binding.speedGaugeView.speedKmh = speedKmh.toFloat()
        binding.tvMaxSub.text = maxSpeedKmh.toString()
        binding.tvAvgSub.text = String.format(Locale.ENGLISH, "%.1f", avgKmh)
    }

    private fun styleGlassCard(view: View, fillColor: Int, strokeColor: Int, radiusDp: Float = 18f) {
        val gd = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(fillColor)
            setStroke((1.2f * resources.displayMetrics.density).toInt(), strokeColor)
        }
        view.background = gd
    }

    private fun applyTheme() {
        val bgColor = ContextCompat.getColor(this, if (isNightMode) R.color.night_bg else R.color.day_bg)
        val textColor = ContextCompat.getColor(this, if (isNightMode) R.color.night_text else R.color.day_text)
        val subColor = ContextCompat.getColor(this, if (isNightMode) R.color.night_subtext else R.color.day_subtext)
        val accentColor = if (isNightMode) 0xFFE5C158.toInt() else 0xFFB45309.toInt()

        val glassCardFill = if (isNightMode) 0xA6111827.toInt() else 0xD9FFFDF7.toInt()
        val glassBadgeFill = if (isNightMode) 0x991E293B.toInt() else 0xCCFEF3C7.toInt()
        val glassBtnFill = if (isNightMode) 0x99263248.toInt() else 0xCCFDE68A.toInt()
        val glassStroke = if (isNightMode) 0x77E5C158.toInt() else 0x88B45309.toInt()

        binding.rootLayout.setBackgroundColor(bgColor)
        binding.ivIslamicBg.alpha = if (isNightMode) 0.72f else 0.35f

        binding.analogClockView.ringColor = glassStroke
        binding.analogClockView.tickColor = textColor
        binding.analogClockView.handAccentColor = accentColor

        binding.speedGaugeView.trackColor = glassStroke
        binding.speedGaugeView.activeArcColor = accentColor
        binding.speedGaugeView.needleColor = textColor

        styleGlassCard(binding.clockWeatherCard, glassCardFill, glassStroke, 18f)
        styleGlassCard(binding.telemetryCard, glassCardFill, glassStroke, 18f)
        styleGlassCard(binding.mapCard, glassCardFill, glassStroke, 18f)
        styleGlassCard(binding.musicCard, glassCardFill, glassStroke, 18f)

        styleGlassCard(binding.weatherBadgeBox, glassBadgeFill, glassStroke, 16f)
        styleGlassCard(binding.speedGlassBox, glassBadgeFill, glassStroke, 16f)
        styleGlassCard(binding.tvStatusPill, glassBadgeFill, glassStroke, 24f)

        styleGlassCard(binding.btnRestoreHome, glassBtnFill, glassStroke, 12f)
        styleGlassCard(binding.btnApps, glassBtnFill, glassStroke, 12f)
        styleGlassCard(binding.btnNextList, glassBtnFill, glassStroke, 12f)
        styleGlassCard(binding.btnPlayPause, glassBtnFill, glassStroke, 12f)
        styleGlassCard(binding.btnNextTrack, glassBtnFill, glassStroke, 12f)
        styleGlassCard(binding.btnSwitchSource, glassBtnFill, glassStroke, 12f)

        binding.tvBrand.setTextColor(accentColor)
        binding.tvBigClock.setTextColor(textColor)
        binding.tvDateLabel.setTextColor(subColor)
        binding.tvWeatherIcon.setTextColor(accentColor)
        binding.tvWeatherTemp.setTextColor(textColor)
        binding.tvWeatherDesc.setTextColor(subColor)
        binding.tvSpeedLabel.setTextColor(subColor)
        binding.tvSpeed.setTextColor(accentColor)
        binding.tvSpeedUnit.setTextColor(subColor)
        binding.tvMaxLabel.setTextColor(subColor)
        binding.tvMaxSub.setTextColor(textColor)
        binding.tvMaxUnit.setTextColor(subColor)
        binding.tvAvgLabel.setTextColor(subColor)
        binding.tvAvgSub.setTextColor(accentColor)
        binding.tvAvgUnit.setTextColor(subColor)
        binding.tvMapTitle.setTextColor(textColor)
        binding.tvLocationHeader.setTextColor(subColor)
        binding.tvTrack.setTextColor(textColor)
        binding.tvPlayNextList.setTextColor(subColor)
    }

    private fun openIconAppsGridDialog() {
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val appsList = packageManager.queryIntentActivities(mainIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase(Locale.ENGLISH) }

        val recyclerView = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 4)
            setPadding(16, 16, 16, 16)
        }

        var dialogRef: AlertDialog? = null
        recyclerView.adapter = AppGridAdapter(appsList, isNightMode) { info ->
            dialogRef?.dismiss()
            val launchIntent = packageManager.getLaunchIntentForPackage(info.activityInfo.packageName)
            safeStartIntent(launchIntent)
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
        handler.removeCallbacksAndMessages(null)
        executor.shutdown()
    }
}
