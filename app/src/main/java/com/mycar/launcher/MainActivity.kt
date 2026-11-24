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
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
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

    private data class RadioChannel(val label: String, val streamUrl: String)

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var lastLuxValue: Float? = null
    private var headlightSignalNight: Boolean? = null

    private var mediaPlayer: MediaPlayer? = null
    private var mapLoadedOnce = false

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
            "Pantai Timur Acoustic",
            "Midnight Express",
            "Smooth Lounge",
            "City Lights Mix"
        )
    )

    private val radioPlaylists = listOf(
        listOf(
            RadioChannel("Sinar FM (Malaysia)", "https://playerservices.streamtheworld.com/api/livestream-redirect/SINAR_FMAAC.aac"),
            RadioChannel("ERA FM (Malaysia)", "https://playerservices.streamtheworld.com/api/livestream-redirect/ERA_FMAAC.aac"),
            RadioChannel("HOT FM (Malaysia)", "https://playerservices.streamtheworld.com/api/livestream-redirect/HOTFMAAC.aac"),
            RadioChannel("Kool 101 (Malaysia)", "https://playerservices.streamtheworld.com/api/livestream-redirect/KOOLFMAAC.aac")
        ),
        listOf(
            RadioChannel("Hitz FM (Malaysia)", "https://playerservices.streamtheworld.com/api/livestream-redirect/HITZ_FMAAC.aac"),
            RadioChannel("Mix FM (Malaysia)", "https://playerservices.streamtheworld.com/api/livestream-redirect/MIX_FMAAC.aac"),
            RadioChannel("Lite FM (Malaysia)", "https://playerservices.streamtheworld.com/api/livestream-redirect/LITE_FMAAC.aac"),
            RadioChannel("Suria FM (Malaysia)", "https://playerservices.streamtheworld.com/api/livestream-redirect/SURIA_FMAAC.aac")
        )
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
            val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault())
            val sdfDate = SimpleDateFormat("EEE, dd MMM", Locale.getDefault())
            val now = Date()
            binding.tvBigClock.text = sdfTime.format(now)
            binding.tvDateLabel.text = "${sdfDate.format(now)} - ${binding.tvLocationHeader.text.removePrefix("Lokasi: ")}"

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
                  border-bottom: 24px solid #38BDF8;
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
            headlightSignalNight == true -> "LAMP NIGHT"
            headlightSignalNight == false -> "LAMP DAY"
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
                val geocoder = Geocoder(this, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                val road = addresses?.firstOrNull()?.let { it.thoroughfare ?: it.subLocality ?: it.locality }
                if (!road.isNullOrBlank()) {
                    runOnUiThread {
                        binding.tvLocationHeader.text = "Lokasi: $road"
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

    private fun currentYtmList(): List<String> = ytmPlaylists[playlistIndex % ytmPlaylists.size]

    private fun currentRadioList(): List<RadioChannel> = radioPlaylists[playlistIndex % radioPlaylists.size]

    private fun setupControls() {
        binding.btnRestoreHome.setOnClickListener { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        binding.btnApps.setOnClickListener { openIconAppsGridDialog() }

        binding.btnNextList.setOnClickListener {
            advanceNextPlaylist()
        }

        binding.btnPlayPause.setOnClickListener {
            isPlaying = !isPlaying
            applyPlaybackState()
            updateMusicQueueUi()
        }

        binding.btnNextTrack.setOnClickListener {
            advanceNextTrack()
        }

        binding.btnSwitchSource.setOnClickListener {
            isRadioMode = !isRadioMode
            playlistIndex = 0
            trackIndex = 0
            applyPlaybackState()
            updateMusicQueueUi()
        }

        binding.btnRadioApp.setOnClickListener {
            launchInstalledMalaysiaRadioApp()
        }
    }

    private fun applyPlaybackState() {
        if (isRadioMode) {
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            if (isPlaying) {
                playCurrentRadioStream()
            } else {
                stopRadioStream()
            }
        } else {
            stopRadioStream()
            sendMediaKey(if (isPlaying) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE)
        }
    }

    private fun playCurrentRadioStream() {
        val list = currentRadioList()
        val station = list[trackIndex % list.size]
        stopRadioStream()
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(station.streamUrl)
                setOnPreparedListener { start() }
                setOnErrorListener { _, _, _ ->
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Stream radio gagal: ${station.label}", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                prepareAsync()
            }
        } catch (_: Exception) {
        }
    }

    private fun stopRadioStream() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
    }

    private fun launchInstalledMalaysiaRadioApp() {
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolved = packageManager.queryIntentActivities(mainIntent, 0)
        val radioApp = resolved.firstOrNull { info ->
            val label = info.loadLabel(packageManager).toString().lowercase()
            val pkg = info.activityInfo.packageName.lowercase()
            label.contains("syok") || label.contains("radio") || label.contains("fm") ||
                pkg.contains("syok") || pkg.contains("radio")
        }
        if (radioApp != null) {
            stopRadioStream()
            isPlaying = false
            updateMusicQueueUi()
            val launch = packageManager.getLaunchIntentForPackage(radioApp.activityInfo.packageName)
            if (launch != null) {
                startActivity(launch)
                return
            }
        }
        Toast.makeText(this, "App Radio Malaysia tidak dijumpai di senarai app", Toast.LENGTH_SHORT).show()
    }

    private fun advanceNextPlaylist() {
        val count = if (isRadioMode) radioPlaylists.size else ytmPlaylists.size
        playlistIndex = (playlistIndex + 1) % count
        trackIndex = 0
        isPlaying = true
        applyPlaybackState()
        updateMusicQueueUi()
    }

    private fun advanceNextTrack() {
        val size = if (isRadioMode) currentRadioList().size else currentYtmList().size
        if (size > 0) {
            trackIndex = (trackIndex + 1) % size
        }
        isPlaying = true
        applyPlaybackState()
        updateMusicQueueUi()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                advanceNextTrack()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                val size = if (isRadioMode) currentRadioList().size else currentYtmList().size
                if (size > 0) {
                    trackIndex = (trackIndex - 1 + size) % size
                }
                applyPlaybackState()
                updateMusicQueueUi()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                isPlaying = !isPlaying
                applyPlaybackState()
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
        val stateIcon = if (isPlaying) "PLAY" else "PAUSE"
        if (isRadioMode) {
            val queue = currentRadioList()
            val cur = queue[trackIndex % queue.size].label
            val n1 = queue[(trackIndex + 1) % queue.size].label
            val n2 = queue[(trackIndex + 2) % queue.size].label
            binding.tvTrack.text = "[$stateIcon] RADIO LIST #${(playlistIndex % radioPlaylists.size) + 1} - $cur"
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
        binding.tvMaxSub.text = maxSpeedKmh.toString()
        binding.tvAvgSub.text = String.format(Locale.getDefault(), "%.1f", avgKmh)
    }

    private fun styleCard(view: View, cardColor: Int, borderColor: Int) {
        val gd = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * resources.displayMetrics.density
            setColor(cardColor)
            setStroke((1f * resources.displayMetrics.density).toInt(), borderColor)
        }
        view.background = gd
    }

    private fun applyTheme() {
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
        binding.tvCardClockTitle.setTextColor(accentColor)
        binding.tvBigClock.setTextColor(textColor)
        binding.tvDateLabel.setTextColor(subColor)
        binding.tvWeatherIcon.setTextColor(subColor)
        binding.tvWeatherTemp.setTextColor(accentColor)
        binding.tvWeatherDesc.setTextColor(textColor)
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
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }

        val recyclerView = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 4)
            setPadding(16, 16, 16, 16)
        }

        var dialogRef: AlertDialog? = null
        recyclerView.adapter = AppGridAdapter(appsList, isNightMode) { info ->
            dialogRef?.dismiss()
            val launchIntent = packageManager.getLaunchIntentForPackage(info.activityInfo.packageName)
            if (launchIntent != null) startActivity(launchIntent)
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
        stopRadioStream()
        handler.removeCallbacks(clockRunnable)
        executor.shutdown()
    }
}
