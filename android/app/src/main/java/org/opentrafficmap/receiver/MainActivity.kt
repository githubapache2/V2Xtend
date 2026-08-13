package org.opentrafficmap.receiver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.lifecycle.lifecycleScope
import com.google.android.material.R as MaterialR
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.opentrafficmap.receiver.databinding.ActivityMainBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlinx.coroutines.launch
import java.util.LinkedList

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: FrameLogAdapter
    private val reader = FrameReader()
    private var usb: UsbSerialController? = null
    private var bt: BluetoothController? = null
    private val mqttBridges = mutableListOf<MqttBridge>()
    private lateinit var recorder: FrameRecorder
    private lateinit var locationOverlay: MyLocationNewOverlay
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var markers: MarkerLayer
    private lateinit var roadworksLayer: AutobahnPointLayer
    private lateinit var closuresLayer: AutobahnPointLayer
    private lateinit var trafficWarningsLayer: AutobahnPointLayer
    private lateinit var parkingLayer: AutobahnPointLayer
    private lateinit var chargingLayer: AutobahnPointLayer
    private lateinit var dwdWarningsLayer: DwdWarningsLayer
    private lateinit var osmSignalsLayer: OsmTrafficSignalsLayer
    private lateinit var geiger: GeigerCounter
    private lateinit var citsAlertBar: CitsAlertBar
    private lateinit var sessionCounters: SessionCounterRow

    private val mainHandler = Handler(Looper.getMainLooper())
    private val rateWindow = LinkedList<Long>()
    private var totalFrames = 0L
    @Volatile private var lastCycle: BluetoothController.CycleConfig? = null
    @Volatile private var lastSpeedMps: Float = 0f
    @Volatile private var followEnabled: Boolean = false
    @Volatile private var spatLightEnabled: Boolean = true
    private lateinit var sheetBehavior: BottomSheetBehavior<android.widget.LinearLayout>
    private var lastUsbState: UsbSerialController.State = UsbSerialController.State.IDLE
    private var lastUsbInfo: String? = null
    private var lastBtState: BluetoothController.State = BluetoothController.State.IDLE
    private var lastBtInfo: String? = null

    // Button default tints (restored on IDLE)
    private var defaultBtnTintUsb: ColorStateList? = null
    private var defaultBtnTintBt:  ColorStateList? = null

    // Own GPS track
    private val ownTrackPoints = mutableListOf<GeoPoint>()
    private var ownTrackLine: Polyline? = null
    @Volatile private var ownTrackEnabled: Boolean = false

    // Compass / bearing-up mode
    @Volatile private var compassMode: Boolean = false

    // Frame log: buffer fills on the main thread, drains every LOG_REFRESH_MS
    private val pendingLogFrames = mutableListOf<Frame>()

    private data class SpatRsu(
        val lat: Double, val lon: Double,
        val phase: SpatTemParser.Phase, val lastSeenMs: Long
    )
    private val spatRsus = mutableMapOf<Long, SpatRsu>()
    private var lastLocation: Location? = null

    // ---------------------------------------------------------------- launchers

    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { r -> if (r.values.all { it }) startBt()
             else Toast.makeText(this, "BT: permission denied", Toast.LENGTH_SHORT).show() }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startReceiverService() }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) { centreOnMyLocation(); startLocationUpdates() }
        else toast(getString(R.string.loc_perm_denied))
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            lastSpeedMps = if (loc.hasSpeed()) loc.speed else 0f
            lastLocation = loc
            if (followEnabled) followLocation(loc)
            if (ownTrackEnabled && ::binding.isInitialized)
                runOnUiThread { addOwnTrackPoint(GeoPoint(loc.latitude, loc.longitude)) }
            if (::binding.isInitialized) {
                adapter.userLocation = loc          // for distance column
                runOnUiThread { updateSpeedOverlay(loc) }
            }
            if (compassMode && loc.hasBearing() && ::binding.isInitialized)
                runOnUiThread { binding.map.mapOrientation = -loc.bearing }
            updateSpatLight(loc)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs.legalAccepted(this)) { showLegalDialog(); return }
        spatLightEnabled = Prefs.spatLightEnabled(this)
        setupUi()
    }

    private fun showLegalDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.legal_title)
            .setMessage(R.string.legal_body)
            .setCancelable(false)
            .setPositiveButton(R.string.legal_accept) { _, _ -> Prefs.setLegalAccepted(this, true); setupUi() }
            .setNegativeButton(R.string.legal_quit) { _, _ -> finish() }
            .show()
    }

    private fun setupUi() {
        Configuration.getInstance().load(applicationContext, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().tileFileSystemCacheMaxBytes  = 600L * 1024 * 1024
        Configuration.getInstance().tileFileSystemCacheTrimBytes = 500L * 1024 * 1024

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        recorder = FrameRecorder(this)
        fused    = LocationServices.getFusedLocationProviderClient(this)
        followEnabled   = Prefs.followEnabled(this)
        ownTrackEnabled = Prefs.ownTrackEnabled(this)
        compassMode     = Prefs.compassMode(this)
        markers = MarkerLayer(binding.map, this)
        roadworksLayer       = AutobahnPointLayer(binding.map, this, R.drawable.ic_marker_roadwork, ContextCompat.getColor(this, R.color.overlay_roadworks))
        closuresLayer        = AutobahnPointLayer(binding.map, this, R.drawable.ic_marker_roadwork, ContextCompat.getColor(this, R.color.overlay_closures))
        trafficWarningsLayer = AutobahnPointLayer(binding.map, this, R.drawable.ic_marker_dot,      ContextCompat.getColor(this, R.color.overlay_traffic_jam))
        parkingLayer         = AutobahnPointLayer(binding.map, this, R.drawable.ic_marker_dot,      ContextCompat.getColor(this, R.color.overlay_parking))
        chargingLayer        = AutobahnPointLayer(binding.map, this, R.drawable.ic_marker_dot,      ContextCompat.getColor(this, R.color.overlay_charging))
        dwdWarningsLayer = DwdWarningsLayer(binding.map, this)
        osmSignalsLayer = OsmTrafficSignalsLayer(binding.map, this)
        geiger  = GeigerCounter(this)
        if (Prefs.audioFeedback(this)) geiger.start()
        if (Prefs.roadworksEnabled(this)) refreshRoadworks()
        if (Prefs.closuresEnabled(this)) refreshClosures()
        if (Prefs.trafficWarningsEnabled(this)) refreshTrafficWarnings()
        if (Prefs.parkingEnabled(this)) refreshParking()
        if (Prefs.chargingEnabled(this)) refreshCharging()
        if (Prefs.dwdWarningsEnabled(this)) refreshDwdWarnings()
        if (Prefs.osmSignalsEnabled(this)) refreshOsmSignals()

        binding.map.setTileSource(tileSourceForKey(Prefs.mapLayer(this)))
        binding.map.setMultiTouchControls(true)
        binding.map.controller.setZoom(6.0)
        binding.map.controller.setCenter(GeoPoint(51.0, 10.0))
        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), binding.map)
        binding.map.overlays.add(locationOverlay)

        adapter = FrameLogAdapter(this) { f -> FrameDetailSheet.show(this, f) }
        binding.log.layoutManager = LinearLayoutManager(this)
        binding.log.adapter = adapter

        citsAlertBar = CitsAlertBar(
            context = this,
            barContainer = binding.alertBarContainer,
            chipContainer = binding.alertBar,
            emptyText = binding.alertBarEmptyText,
            chevron = binding.alertBarChevron,
            detailsContainer = binding.alertDetails,
        )
        sessionCounters = SessionCounterRow(this, binding.sessionCountersRow)

        // Cache default button tints before any programmatic change
        defaultBtnTintUsb = binding.btnConnect.backgroundTintList
        defaultBtnTintBt  = binding.btnConnectBt.backgroundTintList

        binding.btnConnect.setOnClickListener { toggleUsb() }
        binding.btnConnectBt.setOnClickListener { toggleBt() }
        binding.logSettingsGear.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.fabLocate.setOnClickListener { onLocateClick() }
        binding.fabLayers.setOnClickListener { showLayerPicker() }
        binding.fabCompass.setOnClickListener { toggleCompassMode() }
        binding.fabSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        setupBottomSheet()
        updateConnStatus()

        // Reflect saved compass mode on FAB immediately
        applyCompassFabTint()

        applyKeepScreenOn()
        wireSettingsBus()
        if (Prefs.mqttEnabled(this) && Prefs.mqttBrokerList(this).isNotEmpty()) startMqtt()
        if (followEnabled || ownTrackEnabled || compassMode) ensureLocation()
        mainHandler.post(rateRefresh)
        mainHandler.post(logRefresh)
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        binding.map.onResume()
        applyKeepScreenOn()
        if (followEnabled || ownTrackEnabled || compassMode) ensureLocation()
    }

    override fun onPause() {
        super.onPause()
        if (!::binding.isInitialized) return
        binding.map.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(this, ReceiverForegroundService::class.java))
        usb?.stop(); bt?.stop(); stopMqtt()
        if (::recorder.isInitialized) recorder.stop()
        if (::locationOverlay.isInitialized) locationOverlay.disableMyLocation()
        if (::fused.isInitialized) stopLocationUpdates()
        if (::geiger.isInitialized) geiger.stop()
        SettingsBus.liveRecorder = null
        SettingsBus.liveBtController = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    // ----------------------------------------------------------- Settings bus

    private fun wireSettingsBus() {
        SettingsBus.liveRecorder = recorder
        SettingsBus.onFollowChanged = SettingsBus.OnFollowChanged { on ->
            followEnabled = on
            invalidateOptionsMenu()
            if (on || ownTrackEnabled || compassMode) ensureLocation() else stopLocationUpdates()
        }
        SettingsBus.onMqttToggle = SettingsBus.OnMqttToggle { on ->
            stopMqtt(); if (on) startMqtt()
        }
        SettingsBus.onRecordingToggle = SettingsBus.OnRecordingToggle { toggleRecording() }
        SettingsBus.onMapDownload = SettingsBus.OnMapDownload { downloadVisibleMap() }
        SettingsBus.onAudioChanged = SettingsBus.OnAudioChanged { on ->
            if (on) geiger.start() else geiger.stop()
        }
        SettingsBus.onSpatLightChanged = SettingsBus.OnSpatLightChanged { on ->
            spatLightEnabled = on
            if (!on) binding.spatLight.visibility = View.GONE
            else lastLocation?.let { updateSpatLight(it) }
        }
        SettingsBus.onCycleApply = SettingsBus.OnCycleApply { c ->
            lastCycle = c; SettingsBus.liveCycleConfig = c
            val ok = bt?.writeConfig(c) ?: false
            toast(getString(if (ok) R.string.cycle_saved else R.string.cycle_not_connected))
        }
        SettingsBus.onKeepScreenOnChanged = SettingsBus.OnKeepScreenOnChanged { applyKeepScreenOn() }
        SettingsBus.onDarkModeChanged = SettingsBus.OnDarkModeChanged { on ->
            AppCompatDelegate.setDefaultNightMode(
                if (on) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            )
        }
        SettingsBus.onOwnTrackChanged = SettingsBus.OnOwnTrackChanged { on ->
            ownTrackEnabled = on
            if (!on) {
                ownTrackPoints.clear()
                ownTrackLine?.let { binding.map.overlays.remove(it) }
                ownTrackLine = null
                binding.map.invalidate()
            }
            if (on) ensureLocation()
        }
        SettingsBus.onResetAll = SettingsBus.OnResetAll { resetAll() }
    }

    // ------------------------------------------ Keep screen on

    private fun applyKeepScreenOn() {
        if (!::binding.isInitialized) return
        if (Prefs.keepScreenOn(this)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ------------------------------------------ Reset

    private fun resetAll() {
        pendingLogFrames.clear()
        if (::markers.isInitialized) markers.clear()
        adapter.clear()
        totalFrames = 0
        rateWindow.clear()
        spatRsus.clear()
        ownTrackPoints.clear()
        ownTrackLine?.let { binding.map.overlays.remove(it) }
        ownTrackLine = null
        binding.logStats.text   = getString(R.string.stat_total, 0)
        binding.peekRateText.text = getString(R.string.rate_per_min, 0)
        binding.emptyLog.visibility  = View.VISIBLE
        binding.spatLight.visibility = View.GONE
        binding.ampelCell.setState(AmpelCellView.State.NoReception)
        sessionCounters.reset()
        binding.map.invalidate()
    }

    // ------------------------------------------ Bottom sheet (handoff redesign, step 5)

    /** Three snap points per the handoff spec: COLLAPSED shows only the peek
     *  strip, HALF_EXPANDED reaches ~52% of the screen, EXPANDED reaches
     *  [R.dimen.sheet_expanded_offset] from the top. The FAB column tracks
     *  this sheet's live top via translationY (trackFabColumnToSheet()) and
     *  is hidden here at STATE_EXPANDED so it doesn't end up trapped behind
     *  the fully-drawn sheet. */
    private fun setupBottomSheet() {
        sheetBehavior = BottomSheetBehavior.from(binding.sheet).apply {
            isFitToContents = false
            peekHeight = resources.getDimensionPixelSize(R.dimen.sheet_peek)
            halfExpandedRatio = 0.52f
            expandedOffset = resources.getDimensionPixelSize(R.dimen.sheet_expanded_offset)
            state = BottomSheetBehavior.STATE_COLLAPSED
        }
        // A single post{} here raced BottomSheetBehavior's own initial
        // peek-offset layout pass on a cold start: it could fire before the
        // behavior finished positioning the sheet, leaving fabColumn synced
        // to the sheet's pre-offset (0) top until the user's first drag
        // (which re-syncs via onSlide) — found via a real cold-start check,
        // not just the repeated warm drag-testing that had been masking it.
        // A persistent GlobalLayoutListener re-syncs on every layout pass
        // instead of trusting a single well-timed callback.
        binding.sheet.viewTreeObserver.addOnGlobalLayoutListener { trackFabColumnToSheet() }
        sheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(sheet: View, newState: Int) {
                val expanded = newState == BottomSheetBehavior.STATE_EXPANDED
                binding.fabColumn.animate().alpha(if (expanded) 0f else 1f).setDuration(150).start()
                binding.fabColumn.isClickable = !expanded
                binding.fabColumn.children.forEach { it.isClickable = !expanded }
                // Source row + frame-log header should already look like the
                // full sheet once HALF_EXPANDED is reached — only COLLAPSED
                // (peek strip + counters only) hides them.
                val detailVisibility =
                    if (newState == BottomSheetBehavior.STATE_COLLAPSED) View.GONE else View.VISIBLE
                binding.sourceRow.visibility = detailVisibility
                binding.logHeaderRow.visibility = detailVisibility
            }
            override fun onSlide(sheet: View, slideOffset: Float) = trackFabColumnToSheet()
        })
    }

    /** Keeps fabColumn's bottom edge pinned to the sheet's current (live)
     *  top edge, riding up/down as the sheet is dragged through its three
     *  snap points. Driven manually via translationY rather than
     *  app:layout_anchor — on-device testing found the anchor mechanism
     *  doesn't track a match_parent-height BottomSheetBehavior sheet's
     *  peek-adjusted top correctly (it resolved to the screen bottom
     *  instead), so the FABs ended up sinking behind the sheet. See
     *  CLAUDE.md for the measured before/after. */
    private fun trackFabColumnToSheet() {
        binding.fabColumn.translationY = (binding.sheet.top - binding.root.height).toFloat()
    }

    // ------------------------------------------ Map layers

    private fun showLayerPicker() {
        val styleChoices = listOf(
            LayerPickerSheet.Choice("MAPNIK",     getString(R.string.map_layer_standard)),
            LayerPickerSheet.Choice("DARK",       getString(R.string.map_layer_dark)),
            LayerPickerSheet.Choice("SATELLITE",  getString(R.string.map_layer_satellite)),
            LayerPickerSheet.Choice("TRANSPORT",  getString(R.string.map_layer_transport)),
            LayerPickerSheet.Choice("HOT",        getString(R.string.map_layer_humanitarian)),
        )
        val styleSection = LayerPickerSheet.ChoiceSection(
            title       = getString(R.string.map_layer_section_style),
            choices     = styleChoices,
            selectedKey = Prefs.mapLayer(this),
        ) { key ->
            Prefs.setMapLayer(this, key)
            binding.map.setTileSource(tileSourceForKey(key))
        }

        // Traffic-data overlays (Redesign Phase 2, Punkt 2). Independent
        // on/off toggles, not a radio choice — that's why ChoiceSection alone
        // (the only section kind that existed before roadworks) wasn't
        // enough and ToggleSection was added, see LayerPickerSheet.kt. Every
        // source since (DWD, and now the other four Autobahn-API types)
        // reuses that same ToggleSection/ToggleItem shape unchanged — each
        // is just one more item in this list, no further LayerPickerSheet
        // changes needed since roadworks.
        val overlaysSection = LayerPickerSheet.ToggleSection(
            title = getString(R.string.map_layer_section_overlays),
            items = listOf(
                LayerPickerSheet.ToggleItem(
                    key     = "ROADWORKS",
                    label   = getString(R.string.overlay_roadworks),
                    checked = Prefs.roadworksEnabled(this),
                ) { on ->
                    Prefs.setRoadworksEnabled(this, on)
                    if (on) refreshRoadworks() else roadworksLayer.clear()
                },
                LayerPickerSheet.ToggleItem(
                    key     = "CLOSURES",
                    label   = getString(R.string.overlay_closures),
                    checked = Prefs.closuresEnabled(this),
                ) { on ->
                    Prefs.setClosuresEnabled(this, on)
                    if (on) refreshClosures() else closuresLayer.clear()
                },
                LayerPickerSheet.ToggleItem(
                    key     = "TRAFFIC_WARNINGS",
                    label   = getString(R.string.overlay_traffic_warnings),
                    checked = Prefs.trafficWarningsEnabled(this),
                ) { on ->
                    Prefs.setTrafficWarningsEnabled(this, on)
                    if (on) refreshTrafficWarnings() else trafficWarningsLayer.clear()
                },
                LayerPickerSheet.ToggleItem(
                    key     = "PARKING_LORRY",
                    label   = getString(R.string.overlay_parking_lorry),
                    checked = Prefs.parkingEnabled(this),
                ) { on ->
                    Prefs.setParkingEnabled(this, on)
                    if (on) refreshParking() else parkingLayer.clear()
                },
                LayerPickerSheet.ToggleItem(
                    key     = "CHARGING_STATIONS",
                    label   = getString(R.string.overlay_charging_stations),
                    checked = Prefs.chargingEnabled(this),
                ) { on ->
                    Prefs.setChargingEnabled(this, on)
                    if (on) refreshCharging() else chargingLayer.clear()
                },
                LayerPickerSheet.ToggleItem(
                    key     = "DWD_WARNINGS",
                    label   = getString(R.string.overlay_dwd_warnings),
                    checked = Prefs.dwdWarningsEnabled(this),
                ) { on ->
                    Prefs.setDwdWarningsEnabled(this, on)
                    if (on) refreshDwdWarnings() else dwdWarningsLayer.clear()
                },
                LayerPickerSheet.ToggleItem(
                    key     = "OSM_SIGNALS",
                    label   = getString(R.string.overlay_osm_signals),
                    checked = Prefs.osmSignalsEnabled(this),
                ) { on ->
                    Prefs.setOsmSignalsEnabled(this, on)
                    if (on) refreshOsmSignals() else osmSignalsLayer.clear()
                }
            )
        )

        LayerPickerSheet.show(this, getString(R.string.map_layer_title), listOf(styleSection, overlaysSection))
    }

    /** Fetches roadworks for the curated regional road list (see
     *  ROADWORK_ROAD_IDS) and renders them. No viewport/geo query exists on
     *  the Autobahn API — see AutobahnApi.kt / CLAUDE.md for why this is a
     *  fixed road list rather than "whatever's on screen" for now. */
    private fun refreshRoadworks() {
        lifecycleScope.launch {
            roadworksLayer.show(AutobahnApi.fetchRoadworks(ROADWORK_ROAD_IDS))
        }
    }

    private fun refreshClosures() {
        lifecycleScope.launch {
            closuresLayer.show(AutobahnApi.fetchClosures(ROADWORK_ROAD_IDS))
        }
    }

    private fun refreshTrafficWarnings() {
        lifecycleScope.launch {
            trafficWarningsLayer.show(AutobahnApi.fetchTrafficWarnings(ROADWORK_ROAD_IDS))
        }
    }

    private fun refreshParking() {
        lifecycleScope.launch {
            parkingLayer.show(AutobahnApi.fetchParkingLorry(ROADWORK_ROAD_IDS))
        }
    }

    private fun refreshCharging() {
        lifecycleScope.launch {
            chargingLayer.show(AutobahnApi.fetchChargingStations(ROADWORK_ROAD_IDS))
        }
    }

    /** Unlike refreshRoadworks(), no curated region list — the DWD endpoint
     *  returns the full nationwide warning set in one call, so there's
     *  nothing to curate (see CLAUDE.md for why that's not true of the
     *  Autobahn API). */
    private fun refreshDwdWarnings() {
        lifecycleScope.launch {
            val warnings = DwdWarningsApi.fetchWarnings()
            dwdWarningsLayer.show(warnings)
        }
    }

    /** Fetches the static OSM traffic-signal list (curated bbox, see
     *  OverpassApi.kt) and immediately computes which ones are currently
     *  V2X-active against whatever SPATEM RSUs are live right now. Ongoing
     *  updates as new SPATEM frames arrive / RSUs go stale happen via
     *  refreshOsmSignalActivity(), not by re-fetching Overpass — the signal
     *  list itself is static, only which ones are "active" changes. */
    private fun refreshOsmSignals() {
        lifecycleScope.launch {
            osmSignalsLayer.show(OverpassApi.fetchTrafficSignals())
            refreshOsmSignalActivity()
        }
    }

    /** Recomputes OSM-signal active/inactive icons against the current
     *  spatRsus snapshot. Cheap, called both on every new SPATEM frame
     *  (handleFrames) and periodically (rateRefresh, 1s tick) so a signal
     *  reverts to "inactive" within a few seconds of its RSU going stale,
     *  not just when a new frame happens to arrive. No-op if the overlay
     *  is off (Prefs check at both call sites, not here, so this can stay
     *  a plain "do it" method). */
    private fun refreshOsmSignalActivity() {
        val now = System.currentTimeMillis()
        val livePositions = spatRsus.values
            .filter { now - it.lastSeenMs <= SPAT_RSU_STALE_MS }
            .map { it.lat to it.lon }
        osmSignalsLayer.updateActive(livePositions)
    }

    private fun tileSourceForKey(key: String): ITileSource = when (key) {
        "DARK" -> XYTileSource(
            "CartoDB_DarkMatter", 0, 19, 256, ".png",
            arrayOf(
                "https://a.basemaps.cartocdn.com/dark_all/",
                "https://b.basemaps.cartocdn.com/dark_all/",
                "https://c.basemaps.cartocdn.com/dark_all/",
                "https://d.basemaps.cartocdn.com/dark_all/"
            )
        )
        "SATELLITE" -> object : OnlineTileSourceBase("Esri_WorldImagery", 0, 19, 256, "",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String =
                "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/" +
                "${MapTileIndex.getZoom(pMapTileIndex)}/${MapTileIndex.getY(pMapTileIndex)}/${MapTileIndex.getX(pMapTileIndex)}"
        }
        "TRANSPORT" -> XYTileSource(
            "OPNV_Karte", 0, 18, 256, ".png",
            arrayOf("https://tileserver.memomaps.de/tilegen/")
        )
        "HOT" -> XYTileSource(
            "HOT", 0, 19, 256, ".png",
            arrayOf(
                "https://a.tile.openstreetmap.fr/hot/",
                "https://b.tile.openstreetmap.fr/hot/",
                "https://c.tile.openstreetmap.fr/hot/"
            )
        )
        else -> TileSourceFactory.MAPNIK
    }

    // ------------------------------------------ Compass / bearing-up

    private fun toggleCompassMode() {
        compassMode = !compassMode
        Prefs.setCompassMode(this, compassMode)
        applyCompassFabTint()
        if (!compassMode) {
            binding.map.mapOrientation = 0f  // reset to North-up
        } else {
            ensureLocation()
        }
        toast(getString(if (compassMode) R.string.compass_bearing_up else R.string.compass_north_up))
    }

    private fun applyCompassFabTint() {
        if (!::binding.isInitialized) return
        binding.fabCompass.backgroundTintList = if (compassMode)
            ColorStateList.valueOf(0xFF2196F3.toInt())   // blue = active
        else
            defaultBtnTintUsb  // same neutral tint as other FABs
    }

    // ------------------------------------------ Speed overlay

    private fun updateSpeedOverlay(loc: Location) {
        val speedKph = loc.speed * 3.6f
        val bearingText = if (loc.hasBearing()) formatBearing(loc.bearing) else "—°"
        binding.speedOverlay.text = "$bearingText  ${"%3.0f km/h".format(speedKph)}"
        binding.speedOverlay.visibility = View.VISIBLE

        // Peek strip Col 2 (design-file precision pass, 2026-08-12) — same
        // speed/bearing data as the floating map overlay above, just a
        // second, always-visible compact readout. Deliberately its own
        // German 16-point compass formatter (design example: "NNO 28°"),
        // not a reuse of formatBearing()'s English 8-point one — that one
        // stays as-is since it's the existing, unrelated overlay's format.
        binding.peekSpeedValue.text = "%.0f".format(speedKph)
        binding.peekHeadingText.text = if (loc.hasBearing())
            "${formatBearingDe16(loc.bearing)} %.0f°".format(loc.bearing)
        else "—"
    }

    private fun formatBearing(deg: Float): String {
        val card = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = ((deg / 45f + 0.5f).toInt() % 8).coerceIn(0, 7)
        return "%03.0f° %s".format(deg, card[idx])
    }

    private fun formatBearingDe16(deg: Float): String {
        val card = arrayOf(
            "N", "NNO", "NO", "ONO", "O", "OSO", "SO", "SSO",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
        )
        val idx = ((deg / 22.5f + 0.5f).toInt() % 16).coerceIn(0, 15)
        return card[idx]
    }

    // ------------------------------------------ Own track

    private fun addOwnTrackPoint(pt: GeoPoint) {
        ownTrackPoints.add(pt)
        if (ownTrackPoints.size > OWN_TRACK_MAX) ownTrackPoints.removeAt(0)
        val line = ownTrackLine ?: Polyline().also { poly ->
            poly.outlinePaint.color       = 0xFF2196F3.toInt()
            poly.outlinePaint.strokeWidth = 5f
            poly.outlinePaint.alpha       = 200
            binding.map.overlays.add(0, poly)
            ownTrackLine = poly
        }
        line.setPoints(ownTrackPoints)
        binding.map.invalidate()
    }

    // ------------------------------------------------------- Foreground service

    private fun ensureServiceRunning() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startReceiverService()
        }
    }

    private fun startReceiverService() {
        val intent = Intent(this, ReceiverForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    // -------------------------------------------------------------- USB

    private fun toggleUsb() {
        if (usb == null) {
            ensureServiceRunning()
            usb = UsbSerialController(this, ::onSerialBytes, ::onUsbState).also { it.start() }
        } else {
            usb?.stop(); usb = null
            onUsbState(UsbSerialController.State.IDLE, null)
        }
    }

    // Button text is static — the live connection message (info) only ever
    // shows in sourceStatusText to the left, never inside the pill itself.
    private fun onUsbState(state: UsbSerialController.State, info: String?) = runOnUiThread {
        binding.btnConnect.text = getString(R.string.connect)
        binding.btnConnect.backgroundTintList = when (state) {
            UsbSerialController.State.IDLE       -> ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            UsbSerialController.State.REQUESTING -> ColorStateList.valueOf(0xFFFF9800.toInt())
            UsbSerialController.State.CONNECTED  -> ColorStateList.valueOf(sourcePillActiveColor())
            UsbSerialController.State.ERROR      -> ColorStateList.valueOf(0xFFF44336.toInt())
        }
        binding.btnConnect.setTextColor(sourcePillTextColor(state == UsbSerialController.State.IDLE))
        lastUsbState = state; lastUsbInfo = info
        updateConnStatus()
    }

    // --------------------------------------------------------------- BT

    private fun toggleBt() {
        if (bt == null) {
            val missing = BluetoothController.runtimePermissions().filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) { btPermissionLauncher.launch(missing.toTypedArray()); return }
            startBt()
        } else {
            bt?.stop(); bt = null
            SettingsBus.liveBtController = null
            onBtState(BluetoothController.State.IDLE, null)
        }
    }

    private fun startBt() {
        ensureServiceRunning()
        bt = BluetoothController(
            context  = this,
            onBytes  = ::onSerialBytes,
            onState  = ::onBtState,
            onConfig = { lastCycle = it; SettingsBus.liveCycleConfig = it },
        ).also { it.start(); SettingsBus.liveBtController = it }
    }

    private fun onBtState(state: BluetoothController.State, info: String?) = runOnUiThread {
        binding.btnConnectBt.text = getString(R.string.connect_bt)
        binding.btnConnectBt.backgroundTintList = when (state) {
            BluetoothController.State.IDLE       -> ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            BluetoothController.State.SCANNING,
            BluetoothController.State.CONNECTING -> ColorStateList.valueOf(0xFFFF9800.toInt())
            BluetoothController.State.CONNECTED  -> ColorStateList.valueOf(sourcePillActiveColor())
            BluetoothController.State.ERROR      -> ColorStateList.valueOf(0xFFF44336.toInt())
        }
        binding.btnConnectBt.setTextColor(sourcePillTextColor(state == BluetoothController.State.IDLE))
        lastBtState = state; lastBtInfo = info
        updateConnStatus()
    }

    /** Combines the USB and BT controller states into the single compact
     *  readout shown in the peek strip's SOURCE cell (always visible) and
     *  the source row's larger status line (expanded only) — see handoff
     *  README "Verbindungsstatus". Priority: an actual CONNECTED transport
     *  wins over one that's merely reconnecting/scanning, which in turn
     *  wins over IDLE, so the most actionable state is always what's shown
     *  even though the app tracks two independent transports. */
    private fun updateConnStatus() {
        if (!::binding.isInitialized) return
        data class Status(val text: String, val colorAttr: Int)
        // Some controller info strings already self-identify (e.g.
        // R.string.bt_searching = "BT: searching for %s…"), others don't
        // (R.string.bt_reconnect = "Reconnect #%d (%ds)…") — prefixing
        // unconditionally would double up on the former, so only add the
        // transport tag when it isn't already there.
        fun tag(prefix: String, info: String) =
            if (info.startsWith(prefix, ignoreCase = true)) info else "$prefix $info"
        fun usbText() = when (lastUsbState) {
            UsbSerialController.State.CONNECTED  -> tag("USB", "CONNECTED ${lastUsbInfo ?: ""}".trim())
            UsbSerialController.State.REQUESTING -> tag("USB", lastUsbInfo ?: getString(R.string.usb_connecting))
            UsbSerialController.State.ERROR      -> tag("USB", "ERROR ${lastUsbInfo ?: ""}".trim())
            UsbSerialController.State.IDLE       -> null
        }
        fun btText() = when (lastBtState) {
            BluetoothController.State.CONNECTED  -> tag("BT", "CONNECTED ${lastBtInfo ?: ""}".trim())
            BluetoothController.State.SCANNING,
            BluetoothController.State.CONNECTING -> tag("BT", lastBtInfo ?: getString(R.string.bt_connecting_label))
            BluetoothController.State.ERROR      -> tag("BT", "ERROR ${lastBtInfo ?: ""}".trim())
            BluetoothController.State.IDLE       -> null
        }
        val connected = listOfNotNull(
            if (lastUsbState == UsbSerialController.State.CONNECTED) usbText() else null,
            if (lastBtState  == BluetoothController.State.CONNECTED)  btText()  else null,
        ).firstOrNull()
        val busy = listOfNotNull(usbText(), btText()).firstOrNull()
        val status = when {
            connected != null -> Status(connected, MaterialR.attr.colorTertiary)
            busy != null       -> Status(busy, 0)   // v2x_warn — no theme attr, resolved below
            else               -> Status(getString(R.string.status_offline), MaterialR.attr.colorOnSurfaceVariant)
        }
        val color = if (status.colorAttr == 0) {
            ContextCompat.getColor(this, R.color.v2x_warn)
        } else {
            val tv = android.util.TypedValue()
            theme.resolveAttribute(status.colorAttr, tv, true)
            tv.data
        }
        binding.sourceStatusText.text = status.text
        binding.sourceStatusText.setTextColor(color)

        // Peek strip's compact Col 3 dot+label — design-file precision pass
        // (2026-08-12): just which transport is live, not the full status
        // string (that detail lives only in the expanded Source row above).
        val connectedTag = when {
            lastUsbState == UsbSerialController.State.CONNECTED -> "USB"
            lastBtState  == BluetoothController.State.CONNECTED -> "BT"
            else -> null
        }
        binding.peekSourceLabel.text = connectedTag ?: getString(R.string.peek_source_none)
        binding.peekSourceDotGlow.backgroundTintList = ColorStateList.valueOf(
            if (connectedTag != null) ContextCompat.getColor(this, R.color.ampel_green_active)
            else ContextCompat.getColor(this, R.color.v2x_muted_dark)
        )
    }

    /** USB/BT source-row pill "active" fill — ?attr/colorPrimary, which in
     *  the (so far dark-only precision-passed) night theme resolves to
     *  exactly the design file's #7C6FF7. */
    private fun sourcePillActiveColor(): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(MaterialR.attr.colorPrimary, tv, true)
        return tv.data
    }

    /** USB/BT source-row pill text color: muted while idle/inactive
     *  (?attr/colorOnSurfaceVariant), contrast color otherwise
     *  (?attr/colorOnPrimary — legible over colorPrimary/amber/red fills). */
    private fun sourcePillTextColor(idle: Boolean): Int {
        val attr = if (idle) MaterialR.attr.colorOnSurfaceVariant else MaterialR.attr.colorOnPrimary
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    // ---------------------------------------------------------- Location

    private fun onLocateClick() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION); return
        }
        centreOnMyLocation()
    }

    private fun ensureLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION); return
        }
        startLocationUpdates()
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L).build()
        try { fused.requestLocationUpdates(req, locationCallback, mainLooper) }
        catch (_: SecurityException) {}
    }

    private fun stopLocationUpdates() {
        try { fused.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
    }

    private fun centreOnMyLocation() {
        if (!locationOverlay.isMyLocationEnabled) locationOverlay.enableMyLocation()
        val now = locationOverlay.myLocation
        if (now != null) {
            binding.map.controller.animateTo(now)
            binding.map.controller.setZoom(15.0)
        } else {
            locationOverlay.runOnFirstFix {
                runOnUiThread {
                    binding.map.controller.animateTo(locationOverlay.myLocation)
                    binding.map.controller.setZoom(15.0)
                }
            }
            toast(getString(R.string.loc_unknown))
        }
    }

    /** Pan only — zoom is never changed here. */
    private fun followLocation(loc: Location) {
        binding.map.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
    }

    // -------------------------------------------------------- Recording

    private fun toggleRecording(): String = if (recorder.isRecording) {
        val stopped = recorder.stop()
        getString(R.string.rec_stopped, recorder.frameCount, stopped?.absolutePath ?: "?")
    } else {
        val f = recorder.start()
        if (f != null) getString(R.string.rec_started, f.absolutePath) else "recording start failed"
    }

    // ------------------------------------------------------- Map cache

    private fun downloadVisibleMap() {
        val src = binding.map.tileProvider.tileSource
            as? org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
        if (src == null || !src.tileSourcePolicy.acceptsBulkDownload()) {
            toast(getString(R.string.map_dl_unsupported)); return
        }
        val mgr     = CacheManager(binding.map)
        val box     = binding.map.boundingBox
        val zoomMin = binding.map.zoomLevelDouble.toInt()
        val zoomMax = (zoomMin + 2).coerceAtMost(src.maximumZoomLevel)
        val total   = mgr.possibleTilesInArea(box, zoomMin, zoomMax)
        toast(getString(R.string.map_dl_started))
        try {
            mgr.downloadAreaAsync(this, box, zoomMin, zoomMax, object : CacheManager.CacheManagerCallback {
                override fun onTaskComplete()          = runOnUiThread { toast(getString(R.string.map_dl_done)) }
                override fun onTaskFailed(e: Int)      = runOnUiThread { toast("Map dl error: $e") }
                override fun updateProgress(p: Int, cz: Int, zn: Int, zx: Int) {
                    if (p % 50 == 0) runOnUiThread { toast(getString(R.string.map_dl_progress, p, total)) }
                }
                override fun downloadStarted() {}
                override fun setPossibleTilesInArea(t: Int) {}
            })
        } catch (e: Exception) {
            toast("Map dl: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------ MQTT

    private fun startMqtt() {
        stopMqtt()
        val nodeId = Prefs.nodeId(this).trim().ifEmpty { getString(R.string.default_node_id) }
        Prefs.mqttBrokerList(this).filter { it.isNotBlank() }.forEach { url ->
            MqttBridge(nodeId, url.trim(), clientIdPrefix = "android-bridge").also {
                it.start()
                mqttBridges.add(it)
            }
        }
    }

    private fun stopMqtt() {
        mqttBridges.forEach { it.stop() }
        mqttBridges.clear()
    }

    // ---------------------------------------------------------- Frames

    private fun onSerialBytes(chunk: ByteArray) {
        val frames = synchronized(reader) { reader.feed(chunk) }
        if (frames.isEmpty()) return
        runOnUiThread { handleFrames(frames) }
    }

    private fun handleFrames(frames: List<Frame>) {
        val mqttFilter = Prefs.mqttFilterTypes(this)
        for (f in frames) {
            totalFrames++
            pendingLogFrames.add(f)          // buffered; drains in logRefresh
            markers.add(f)
            geiger.click(f.msgType)
            if (f.msgType.name in mqttFilter) mqttBridges.forEach { it.publish(f.payload) }
            if (recorder.isRecording) recorder.append(f)
            rateWindow.add(System.currentTimeMillis())
            citsAlertBar.onMessage(f.msgType, describeFrame(f))
            sessionCounters.increment(f.msgType)

            val spatLatLon = f.latLon
            if (f.msgType == ItsG5Decoder.MsgType.SPATEM && spatLatLon != null) {
                val (lat, lon) = spatLatLon
                spatRsus[f.stationId ?: 0L] = SpatRsu(lat, lon,
                    f.spatPhase ?: SpatTemParser.Phase.UNKNOWN, System.currentTimeMillis())
                lastLocation?.let { updateSpatLight(it) }
                if (Prefs.osmSignalsEnabled(this)) refreshOsmSignalActivity()
            }
        }
    }

    /** Short, single-line summary for a CitsAlertBar detail row — station
     *  id (the closest thing to a stable per-sender identifier we have) plus
     *  a DENM cause if this frame carried one. */
    private fun describeFrame(f: Frame): String {
        val station = f.stationId?.let { "%08x".format(it) } ?: "—"
        val cause = f.denmCause?.let { " · $it" } ?: ""
        return "$station$cause"
    }

    private fun updateSpatLight(loc: Location) {
        val now = System.currentTimeMillis()
        spatRsus.entries.removeAll { now - it.value.lastSeenMs > SPAT_RSU_STALE_MS }
        if (spatRsus.isEmpty()) {
            if (spatLightEnabled) binding.spatLight.visibility = View.GONE
            binding.ampelCell.setState(AmpelCellView.State.NoReception)
            return
        }

        val results = FloatArray(2)
        val nearest = spatRsus.values.filter { rsu ->
            Location.distanceBetween(loc.latitude, loc.longitude, rsu.lat, rsu.lon, results)
            val dist = results[0]
            if (dist > 400f) return@filter false
            if (loc.hasBearing()) {
                val diff = Math.abs(((loc.bearing - results[1] + 180f + 360f) % 360f) - 180f)
                diff < 70f
            } else dist < 250f
        }.minByOrNull { rsu ->
            Location.distanceBetween(loc.latitude, loc.longitude, rsu.lat, rsu.lon, results)
            results[0]
        }

        if (nearest == null) {
            if (spatLightEnabled) binding.spatLight.visibility = View.GONE
            binding.ampelCell.setState(AmpelCellView.State.NoReception)
            return
        }
        // SpatTemParser doesn't extract a countdown/minEndTime field today,
        // so the peek cell can only ever reach PhaseOnly from real data —
        // PhaseCountdown exists in AmpelCellView for spec-completeness and
        // was verified manually on-device, see CLAUDE.md.
        binding.ampelCell.setState(AmpelCellView.State.PhaseOnly(nearest.phase))
        if (spatLightEnabled) {
            binding.spatLight.visibility = View.VISIBLE
            applyPhaseColors(nearest.phase)
        }
    }

    private fun applyPhaseColors(phase: SpatTemParser.Phase) {
        val DIM = 0x33
        fun tint(active: Boolean, r: Int, g: Int, b: Int): ColorStateList {
            val alpha = if (active) 0xFF else DIM
            return ColorStateList.valueOf((alpha shl 24) or (r shl 16) or (g shl 8) or b)
        }
        binding.lightRed.backgroundTintList    = tint(phase == SpatTemParser.Phase.RED,    0xCC, 0x22, 0x22)
        binding.lightYellow.backgroundTintList = tint(phase == SpatTemParser.Phase.YELLOW, 0xCC, 0xAA, 0x00)
        binding.lightGreen.backgroundTintList  = tint(phase == SpatTemParser.Phase.GREEN,  0x22, 0xCC, 0x22)
    }

    /** Drains frame buffer into the grouped adapter every 300 ms. */
    private val logRefresh = object : Runnable {
        override fun run() {
            if (pendingLogFrames.isNotEmpty()) {
                val batch = pendingLogFrames.toList()
                pendingLogFrames.clear()
                adapter.addFrames(batch)
                if (binding.emptyLog.visibility != View.GONE)
                    binding.emptyLog.visibility = View.GONE
            }
            mainHandler.postDelayed(this, LOG_REFRESH_MS)
        }
    }

    private val rateRefresh = object : Runnable {
        override fun run() {
            val cutoff = System.currentTimeMillis() - 60_000
            while (rateWindow.isNotEmpty() && rateWindow.first() < cutoff) rateWindow.removeFirst()
            val rate = rateWindow.size
            binding.logStats.text = getString(R.string.stat_total, totalFrames.toInt())
            binding.peekRateText.text = getString(R.string.rate_per_min, rate)
            if (::citsAlertBar.isInitialized) citsAlertBar.tick()
            if (::markers.isInitialized) markers.prune()
            // Re-check staleness even without a fresh SPATEM frame, so a
            // signal reverts to "inactive" a few seconds after its RSU goes
            // quiet, not only when the next frame happens to arrive.
            if (::osmSignalsLayer.isInitialized && Prefs.osmSignalsEnabled(this@MainActivity)) refreshOsmSignalActivity()
            ReceiverForegroundService.instance?.updateStats(totalFrames.toInt(), rate)
            mainHandler.postDelayed(this, 1_000)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val OWN_TRACK_MAX  = 2000
        private const val LOG_REFRESH_MS = 300L  // log drain interval

        // Curated regional starter set for the roadworks overlay (Redesign
        // Phase 2, Punkt 2) — Bonn/Cologne area, where field testing has
        // happened so far (see CLAUDE.md). Not a general solution: the
        // Autobahn API has no viewport/geo query, so "roadworks near wherever
        // the map currently shows" would need either a road/region lookup
        // table or picking roads from the user's GPS position — deliberately
        // out of scope for this first end-to-end pass.
        private val ROADWORK_ROAD_IDS = OverlayRegion.ROAD_IDS
        // Kept name for call-site clarity; value lives in shared OverlayRegion.

        // How long a SPATEM RSU is considered "still broadcasting" without a
        // fresh frame — shared between the existing 400m SPAT-light indicator
        // and the OSM-signal V2X-active matching (Redesign Phase 2, Punkt 2).
        private const val SPAT_RSU_STALE_MS = 30_000L
    }
}
