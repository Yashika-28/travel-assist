package com.example.taskmanager

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.util.Locale

// ──────────────────────────────────────────────────────────────────
//  THEME – all Color objects are compile-time constants (no allocs)
// ──────────────────────────────────────────────────────────────────
private val MidnightBlack = Color(0xFF090B11)
private val DeepSlate     = Color(0xFF131722)
private val CardBg        = Color(0xFF1E2230)
private val BorderBlue    = Color(0xFF2C3246)
private val ElectricPurple = Color(0xFF8B5CF6)
private val CyberPink     = Color(0xFFEC4899)
private val NeonCyan      = Color(0xFF06B6D4)
private val TextWhite     = Color(0xFFF8FAFC)
private val TextGray      = Color(0xFF94A3B8)
private val AlarmRed      = Color(0xFFEF4444)

// Pre-computed Android int colors for map overlays (avoids repeated toArgb calls in draw loop)
private val CIRCLE_FILL_COLOR   = android.graphics.Color.argb(45, 139, 92, 246)
private val CIRCLE_STROKE_COLOR = android.graphics.Color.rgb(139, 92, 246)

// Theme applied once, stored as top-level val to prevent re-creation
private val MetroColorScheme = darkColorScheme(
    primary = ElectricPurple, secondary = NeonCyan, tertiary = CyberPink,
    background = MidnightBlack, surface = DeepSlate,
    onPrimary = Color.White, onSecondary = Color.White,
    onBackground = TextWhite, onSurface = TextWhite
)

// Pre-computed shape objects shared across all composables (avoids repeated allocations)
private val RoundedShape12 = RoundedCornerShape(12.dp)
private val RoundedShape14 = RoundedCornerShape(14.dp)
private val RoundedShape16 = RoundedCornerShape(16.dp)
private val RoundedShape20 = RoundedCornerShape(20.dp)
private val RoundedShape28 = RoundedCornerShape(28.dp)

@Composable
fun MetroNapTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MetroColorScheme, content = content)
}

// ──────────────────────────────────────────────────────
//  DATA MODELS – lightweight, allocation-minimal
// ──────────────────────────────────────────────────────
data class SearchResult(val name: String, val latitude: Double, val longitude: Double)

data class PresetLocation(val name: String, val latitude: Double, val longitude: Double, val description: String)

// Presets stored as a top-level immutable list (never re-created)
private val TransitPresets = listOf(
    PresetLocation("Rajiv Chowk Metro Station", 28.6328, 77.2195, "Delhi Metro Blue/Yellow Line"),
    PresetLocation("Huda City Centre Metro", 28.4593, 77.0725, "Gurugram, Yellow Line"),
    PresetLocation("Times Square-42 St Subway", 40.7548, -73.9853, "New York MTA, USA"),
    PresetLocation("King's Cross St. Pancras", 51.5309, -0.1238, "London Underground, UK"),
    PresetLocation("Châtelet les Halles", 48.8615, 2.3470, "Paris Métro, France"),
    PresetLocation("Shibuya Station", 35.6580, 139.7016, "Tokyo Subway, Japan")
)

// Radius chip values stored as immutable list (never re-created)
private val RadiusPresets = listOf(100f, 200f, 500f, 750f, 1000f)

// ──────────────────────────────────────────────────────
//  ACTIVITY
// ──────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OSMDroid configuration — done once before any MapView is inflated
        Configuration.getInstance().apply {
            userAgentValue = "MetroNapTransitAlarm/1.0 (contact: metronap_app@outlook.com)"
            load(applicationContext, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = "MetroNapTransitAlarm/1.0 (contact: metronap_app@outlook.com)"
            // Tile cache optimizations
            tileFileSystemCacheMaxBytes = 50L * 1024 * 1024 // 50 MB disk cache
            tileFileSystemCacheTrimBytes = 40L * 1024 * 1024 // trim at 40 MB
            tileDownloadThreads = 4.toShort() // parallel tile downloads
        }

        enableEdgeToEdge()
        setContent {
            MetroNapTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MetroNapApp(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────
//  MAIN COMPOSABLE
// ──────────────────────────────────────────────────────
@Composable
fun MetroNapApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // --- Reactive service state (zero-copy flow collectors) ---
    val isTracking      by LocationAlarmService.isTracking.collectAsState()
    val alarmTriggered   by LocationAlarmService.alarmTriggered.collectAsState()
    val currentDistance  by LocationAlarmService.currentDistance.collectAsState()
    val trackingName     by LocationAlarmService.destinationName.collectAsState()

    // --- Local UI state ---
    var searchFinished      by remember { mutableStateOf(false) }
    var query               by remember { mutableStateOf("") }
    var searchResults       by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isSearching         by remember { mutableStateOf(false) }
    var selectedDestination by remember { mutableStateOf<SearchResult?>(null) }
    var alarmRadius         by remember { mutableFloatStateOf(500f) }

    var mapViewRef          by remember { mutableStateOf<MapView?>(null) }
    var userLocationState   by remember { mutableStateOf<GeoPoint?>(null) }

    val keyboardController = LocalSoftwareKeyboardController.current

    // ── OPTIMIZATION: Cache marker drawables (generated exactly once) ──
    val (userMarkerIcon, destMarkerIcon) = remember {
        val density = context.resources.displayMetrics.density
        fun makeIcon(color: Color, sizeDp: Int): android.graphics.drawable.BitmapDrawable {
            val px = (sizeDp * density).toInt()
            val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            p.style = android.graphics.Paint.Style.FILL
            p.color = color.copy(alpha = 0.35f).toArgb()
            c.drawCircle(px / 2f, px / 2f, px / 2f, p)
            p.color = color.toArgb()
            c.drawCircle(px / 2f, px / 2f, px / 3f, p)
            p.style = android.graphics.Paint.Style.STROKE
            p.color = android.graphics.Color.WHITE
            p.strokeWidth = 2 * density
            c.drawCircle(px / 2f, px / 2f, px / 3f, p)
            return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
        }
        Pair(makeIcon(ElectricPurple, 24), makeIcon(CyberPink, 28))
    }

    // ── OPTIMIZATION: Cache circle geometry (only recomputed when dest/radius changes) ──
    val cachedCirclePoints = remember(selectedDestination, alarmRadius) {
        selectedDestination?.let { Polygon.pointsAsCircle(GeoPoint(it.latitude, it.longitude), alarmRadius.toDouble()) }
    }

    // ── Permissions ──
    var hasLocationPermission by remember {
        mutableStateOf(context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            else true
        )
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasLocationPermission = (perms[android.Manifest.permission.ACCESS_FINE_LOCATION] == true)
                || (perms[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true)
        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms[android.Manifest.permission.POST_NOTIFICATIONS] == true else true

        if (hasLocationPermission) {
            try {
                LocationServices.getFusedLocationProviderClient(context)
                    .lastLocation
                    .addOnSuccessListener { loc: Location? ->
                        loc?.let {
                            val gp = GeoPoint(it.latitude, it.longitude)
                            userLocationState = gp
                            mapViewRef?.controller?.animateTo(gp)
                            mapViewRef?.controller?.setZoom(15.0)
                        }
                    }
            } catch (_: SecurityException) {}
        }
    }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionsLauncher.launch(perms.toTypedArray())
    }

    // ── Search helper (consolidates duplicated search logic) ──
    fun doSearch(q: String, fromDebounce: Boolean = false) {
        if (q.isBlank()) return
        if (!fromDebounce) keyboardController?.hide()
        isSearching = true; searchFinished = false

        coroutineScope.launch(Dispatchers.IO) {
            val results = try {
                val geocoder = Geocoder(context, Locale.getDefault())
                val addrs = geocoder.getFromLocationName(q, 5)
                if (!addrs.isNullOrEmpty()) {
                    addrs.map { a ->
                        SearchResult(
                            name = a.getAddressLine(0) ?: a.featureName ?: q,
                            latitude = a.latitude, longitude = a.longitude
                        )
                    }
                } else searchNominatim(q)
            } catch (e: Exception) {
                Log.e("MetroNap", "Geocoder failed: ${e.message}")
                try { searchNominatim(q) } catch (_: Exception) { emptyList() }
            }
            withContext(Dispatchers.Main) {
                searchResults = results
                isSearching = false
                searchFinished = true
            }
        }
    }

    // ── Debounced live search ──
    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (selectedDestination != null && trimmed == selectedDestination!!.name) return@LaunchedEffect
        if (trimmed.length < 3) { searchResults = emptyList(); searchFinished = false; return@LaunchedEffect }
        delay(500L)
        doSearch(trimmed, fromDebounce = true)
    }

    // ── Alarm pulse animation (only runs when alarm is active) ──
    val pulseAlpha = if (alarmTriggered) {
        val transition = rememberInfiniteTransition(label = "pulse")
        transition.animateFloat(
            initialValue = 0.2f, targetValue = 0.85f,
            animationSpec = infiniteRepeatable(tween(850, easing = LinearEasing), RepeatMode.Reverse),
            label = "pulseAlpha"
        ).value
    } else 0f

    // ════════════════════════════════════════════════════════
    //  LAYOUT
    // ════════════════════════════════════════════════════════
    Box(modifier = modifier.fillMaxSize().background(MidnightBlack)) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── HEADER ──
            HeaderSection()

            // ── MAP CARD (visually isolated with border + padding) ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .shadow(4.dp, shape = RoundedShape20)
                    .clip(RoundedShape20)
                    .border(1.dp, BorderBlue, RoundedShape20)
            ) {
                AndroidView(
                    factory = { ctx ->
                        MapView(ctx).apply {
                            setTileSource(org.osmdroid.tileprovider.tilesource.XYTileSource(
                                "CartoDarkMatter", 0, 20, 256, ".png",
                                arrayOf(
                                    "https://a.basemaps.cartocdn.com/dark_all/",
                                    "https://b.basemaps.cartocdn.com/dark_all/",
                                    "https://c.basemaps.cartocdn.com/dark_all/",
                                    "https://d.basemaps.cartocdn.com/dark_all/"
                                ),
                                "© OpenStreetMap contributors, © CARTO"
                            ))
                            setMultiTouchControls(true)
                            zoomController.setVisibility(
                                org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
                            )
                            // Reduce overdraw: disable unneeded map features
                            isTilesScaledToDpi = true
                            isHorizontalMapRepetitionEnabled = false
                            isVerticalMapRepetitionEnabled = false
                            controller.setZoom(14.5)
                            mapViewRef = this
                        }
                    },
                    update = { mapView ->
                        mapView.overlays.clear()

                        userLocationState?.let { pos ->
                            mapView.overlays.add(Marker(mapView).apply {
                                position = pos; title = "My Location"
                                icon = userMarkerIcon
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            })
                        }

                        selectedDestination?.let { dest ->
                            cachedCirclePoints?.let { pts ->
                                mapView.overlays.add(Polygon().apply {
                                    points = pts
                                    fillPaint.color = CIRCLE_FILL_COLOR
                                    outlinePaint.color = CIRCLE_STROKE_COLOR
                                    outlinePaint.strokeWidth = 3f
                                })
                            }
                            mapView.overlays.add(Marker(mapView).apply {
                                position = GeoPoint(dest.latitude, dest.longitude)
                                title = dest.name; icon = destMarkerIcon
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            })
                        }

                        mapView.invalidate()
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Floating map controls
                Column(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FloatingActionButton(
                        onClick = { mapViewRef?.controller?.zoomIn() },
                        containerColor = CardBg.copy(alpha = 0.85f),
                        contentColor = TextWhite, shape = CircleShape,
                        modifier = Modifier.size(38.dp)
                    ) { Icon(Icons.Filled.Add, "Zoom In", Modifier.size(18.dp)) }

                    FloatingActionButton(
                        onClick = { mapViewRef?.controller?.zoomOut() },
                        containerColor = CardBg.copy(alpha = 0.85f),
                        contentColor = TextWhite, shape = CircleShape,
                        modifier = Modifier.size(38.dp)
                    ) { Icon(Icons.Filled.Remove, "Zoom Out", Modifier.size(18.dp)) }

                    selectedDestination?.let { dest ->
                        FloatingActionButton(
                            onClick = {
                                mapViewRef?.controller?.animateTo(GeoPoint(dest.latitude, dest.longitude))
                                mapViewRef?.controller?.setZoom(16.0)
                            },
                            containerColor = CyberPink.copy(alpha = 0.9f),
                            contentColor = TextWhite, shape = CircleShape,
                            modifier = Modifier.size(38.dp)
                        ) { Icon(Icons.Filled.Place, "Destination", Modifier.size(18.dp)) }
                    }

                    FloatingActionButton(
                        onClick = {
                            userLocationState?.let {
                                mapViewRef?.controller?.animateTo(it)
                                mapViewRef?.controller?.setZoom(15.5)
                            }
                        },
                        containerColor = ElectricPurple, contentColor = TextWhite,
                        shape = CircleShape, modifier = Modifier.size(38.dp)
                    ) { Icon(Icons.Filled.MyLocation, "My Location", Modifier.size(18.dp)) }
                }
            }

            // Divider between map and scrollable content
            HorizontalDivider(
                color = BorderBlue.copy(alpha = 0.6f), thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )

            // ── SCROLLABLE DASHBOARD ──
            Column(
                modifier = Modifier
                    .fillMaxWidth().weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                // ▸ Permissions banner
                if (!hasLocationPermission || !hasNotificationPermission) {
                    PermissionsBanner(permissionsLauncher)
                }

                // ▸ Active tracking card
                if (isTracking) {
                    TrackingCard(
                        alarmTriggered = alarmTriggered,
                        trackingName = trackingName,
                        currentDistance = currentDistance,
                        alarmRadius = alarmRadius,
                        context = context
                    )
                }

                // ▸ Destination configurator
                if (!isTracking) {
                    DestinationConfigCard(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = { doSearch(query) },
                        isSearching = isSearching,
                        searchResults = searchResults,
                        searchFinished = searchFinished,
                        selectedDestination = selectedDestination,
                        alarmRadius = alarmRadius,
                        onSelectResult = { res ->
                            selectedDestination = res
                            searchResults = emptyList()
                            searchFinished = false
                            query = res.name
                            mapViewRef?.let { map ->
                                map.controller.animateTo(GeoPoint(res.latitude, res.longitude))
                                map.controller.setZoom(16.0)
                            }
                        },
                        onRadiusChange = { alarmRadius = it },
                        onActivate = {
                            val dest = selectedDestination ?: run {
                                selectedDestination = SearchResult(
                                    TransitPresets[0].name,
                                    TransitPresets[0].latitude,
                                    TransitPresets[0].longitude
                                ); return@DestinationConfigCard
                            }
                            val intent = Intent(context, LocationAlarmService::class.java).apply {
                                action = LocationAlarmService.ACTION_START_TRACKING
                                putExtra(LocationAlarmService.EXTRA_DEST_LAT, dest.latitude)
                                putExtra(LocationAlarmService.EXTRA_DEST_LON, dest.longitude)
                                putExtra(LocationAlarmService.EXTRA_DEST_NAME, dest.name)
                                putExtra(LocationAlarmService.EXTRA_DEST_RADIUS, alarmRadius)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                        }
                    )
                }

                // ▸ Preset stations
                if (!isTracking) {
                    PresetsCard(
                        selectedDestination = selectedDestination,
                        onSelectPreset = { preset ->
                            selectedDestination = SearchResult(preset.name, preset.latitude, preset.longitude)
                            query = preset.name
                            searchResults = emptyList()
                            searchFinished = false
                            mapViewRef?.let { map ->
                                map.controller.animateTo(GeoPoint(preset.latitude, preset.longitude))
                                map.controller.setZoom(16.0)
                            }
                        }
                    )
                }
            }
        }

        // ── FULL SCREEN ALARM OVERLAY ──
        if (alarmTriggered) {
            AlarmOverlay(
                pulseAlpha = pulseAlpha,
                trackingName = trackingName,
                currentDistance = currentDistance,
                context = context
            )
        }
    }
}

// ════════════════════════════════════════════════════════════
//  EXTRACTED COMPOSABLES (smaller recomposition scopes)
// ════════════════════════════════════════════════════════════

@Composable
private fun HeaderSection() {
    Box(
        modifier = Modifier.fillMaxWidth().background(MidnightBlack)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.DirectionsSubway, "Metro", tint = ElectricPurple, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("MetroNap", fontSize = 22.sp, fontWeight = FontWeight.Black, color = TextWhite, letterSpacing = (-0.5).sp)
            }
            Text("Sleep soundly on the transit; we will wake you up.", fontSize = 11.sp, color = TextGray)
        }
    }
}

@Composable
private fun PermissionsBanner(
    launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AlarmRed.copy(alpha = 0.15f)),
        shape = RoundedShape16,
        modifier = Modifier.fillMaxWidth().border(1.dp, AlarmRed.copy(alpha = 0.4f), RoundedShape16)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, "Warning", tint = AlarmRed, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Permissions Required", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextWhite)
                Text("Location and notification permissions are needed for alarms.", fontSize = 12.sp, color = TextGray)
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val list = mutableListOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        list.add(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    launcher.launch(list.toTypedArray())
                },
                colors = ButtonDefaults.buttonColors(containerColor = AlarmRed)
            ) { Text("Grant", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun TrackingCard(
    alarmTriggered: Boolean,
    trackingName: String,
    currentDistance: Float?,
    alarmRadius: Float,
    context: Context
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (alarmTriggered) AlarmRed.copy(alpha = 0.35f) else CardBg
        ),
        shape = RoundedShape20,
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, if (alarmTriggered) AlarmRed else ElectricPurple.copy(alpha = 0.6f), RoundedShape20)
            .shadow(if (alarmTriggered) 12.dp else 4.dp, spotColor = if (alarmTriggered) AlarmRed else ElectricPurple)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "MONITORING TRANSIT ALARM", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = if (alarmTriggered) Color.White else NeonCyan, letterSpacing = 1.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                trackingName, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextWhite,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(16.dp))

            if (currentDistance != null) {
                Text(
                    text = formatDistanceUI(currentDistance),
                    fontSize = 46.sp, fontWeight = FontWeight.Black,
                    color = if (alarmTriggered) Color.White else ElectricPurple,
                    letterSpacing = (-1).sp
                )
                Spacer(Modifier.height(4.dp))
                Text("to destination (Alarm radius: ${alarmRadius.toInt()}m)", fontSize = 12.sp, color = TextGray)
            } else {
                Text("Calculating...", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = TextGray)
            }

            Spacer(Modifier.height(20.dp))

            if (alarmTriggered) {
                Button(
                    onClick = {
                        context.startService(Intent(context, LocationAlarmService::class.java).apply {
                            action = LocationAlarmService.ACTION_DISMISS_ALARM
                        })
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedShape12
                ) {
                    Icon(Icons.Filled.VolumeMute, "Mute", tint = AlarmRed)
                    Spacer(Modifier.width(6.dp))
                    Text("Dismiss Alarm", color = AlarmRed, fontWeight = FontWeight.Bold)
                }
            } else {
                OutlinedButton(
                    onClick = {
                        context.startService(Intent(context, LocationAlarmService::class.java).apply {
                            action = LocationAlarmService.ACTION_STOP_TRACKING
                        })
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AlarmRed),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AlarmRed),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedShape12
                ) {
                    Icon(Icons.Filled.Cancel, "Stop", tint = AlarmRed)
                    Spacer(Modifier.width(6.dp))
                    Text("Stop Alarm", color = AlarmRed, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DestinationConfigCard(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    isSearching: Boolean,
    searchResults: List<SearchResult>,
    searchFinished: Boolean,
    selectedDestination: SearchResult?,
    alarmRadius: Float,
    onSelectResult: (SearchResult) -> Unit,
    onRadiusChange: (Float) -> Unit,
    onActivate: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedShape20,
        modifier = Modifier.fillMaxWidth().border(1.dp, BorderBlue, RoundedShape20)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("SET TARGET DESTINATION", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ElectricPurple, letterSpacing = 1.sp)

            // Search input
            OutlinedTextField(
                value = query, onValueChange = onQueryChange,
                label = { Text("Search location / metro station") },
                trailingIcon = {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Rounded.Search, "Search", tint = ElectricPurple)
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricPurple, unfocusedBorderColor = BorderBlue,
                    focusedTextColor = TextWhite, unfocusedTextColor = TextWhite,
                    focusedLabelColor = ElectricPurple, unfocusedLabelColor = TextGray
                ),
                singleLine = true, shape = RoundedShape12, modifier = Modifier.fillMaxWidth()
            )

            // Search results
            if (isSearching) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = ElectricPurple, trackColor = BorderBlue)
            } else if (searchResults.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedShape12)
                        .background(MidnightBlack).border(1.dp, BorderBlue, RoundedShape12).padding(4.dp)
                ) {
                    Text("Search Results:", fontSize = 11.sp, color = TextGray, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    searchResults.take(4).forEach { res ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onSelectResult(res) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.LocationOn, null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(res.name, color = TextWhite, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else if (searchFinished) {
                Text("No locations found. Check spelling or try a preset.", color = AlarmRed, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp))
            }

            // Selected destination info
            selectedDestination?.let { dest ->
                Box(
                    modifier = Modifier.fillMaxWidth().clip(RoundedShape14)
                        .background(MidnightBlack).border(1.dp, ElectricPurple.copy(alpha = 0.4f), RoundedShape14).padding(14.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.DirectionsTransit, "Target", tint = CyberPink, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Selected Destination:", fontSize = 12.sp, color = TextGray, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(dest.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextWhite, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(2.dp))
                        Text("Coordinates: ${"%.5f".format(dest.latitude)}, ${"%.5f".format(dest.longitude)}", fontSize = 11.sp, color = NeonCyan)
                    }
                }
            }

            // Radius configurator
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Alarm Trigger Radius: ${alarmRadius.toInt()} meters", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextWhite)

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    items(RadiusPresets) { rad ->
                        val sel = alarmRadius == rad
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                .background(if (sel) ElectricPurple else BorderBlue)
                                .clickable { onRadiusChange(rad) }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text("${rad.toInt()}m", color = if (sel) Color.White else TextGray,
                                fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium)
                        }
                    }
                }

                Slider(
                    value = alarmRadius, onValueChange = onRadiusChange,
                    valueRange = 50f..2000f,
                    colors = SliderDefaults.colors(thumbColor = ElectricPurple, activeTrackColor = ElectricPurple, inactiveTrackColor = BorderBlue),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Activate button
            Button(
                onClick = onActivate, enabled = selectedDestination != null,
                modifier = Modifier.fillMaxWidth().height(50.dp).shadow(8.dp, shape = RoundedShape12),
                colors = ButtonDefaults.buttonColors(containerColor = ElectricPurple, disabledContainerColor = BorderBlue),
                shape = RoundedShape12
            ) {
                Icon(Icons.Filled.NotificationsActive, null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("Activate Location Alarm", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

@Composable
private fun PresetsCard(
    selectedDestination: SearchResult?,
    onSelectPreset: (PresetLocation) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedShape20,
        modifier = Modifier.fillMaxWidth().border(1.dp, BorderBlue, RoundedShape20)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("QUICK METRO STATION PRESETS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = NeonCyan, letterSpacing = 1.sp)

            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                items(TransitPresets) { preset ->
                    val sel = selectedDestination?.latitude == preset.latitude
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (sel) ElectricPurple.copy(alpha = 0.2f) else MidnightBlack
                        ),
                        shape = RoundedShape12,
                        modifier = Modifier.width(160.dp)
                            .border(1.dp, if (sel) ElectricPurple else BorderBlue, RoundedShape12)
                            .clickable { onSelectPreset(preset) }
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(preset.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextWhite, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(preset.description, fontSize = 10.sp, color = TextGray, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlarmOverlay(
    pulseAlpha: Float,
    trackingName: String,
    currentDistance: Float?,
    context: Context
) {
    Box(
        modifier = Modifier.fillMaxSize().background(AlarmRed.copy(alpha = pulseAlpha))
            .clickable { /* Block touches */ },
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MidnightBlack.copy(alpha = 0.95f)),
            shape = RoundedShape28,
            modifier = Modifier.fillMaxWidth(0.85f).border(2.dp, AlarmRed, RoundedShape28)
                .shadow(24.dp, spotColor = AlarmRed)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier.size(80.dp).clip(CircleShape)
                        .background(AlarmRed.copy(alpha = 0.25f)).border(2.dp, AlarmRed, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.NotificationsActive, "Alarm", tint = AlarmRed, modifier = Modifier.size(46.dp))
                }

                Text("WAKE UP!", fontSize = 32.sp, fontWeight = FontWeight.Black, color = AlarmRed, letterSpacing = 1.sp)
                Text("You are about to reach:", fontSize = 14.sp, color = TextGray)
                Text(trackingName, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextWhite,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)

                currentDistance?.let {
                    Text("Current Distance: ${formatDistanceUI(it)}", fontSize = 16.sp,
                        fontWeight = FontWeight.Bold, color = NeonCyan)
                }

                Spacer(Modifier.height(10.dp))

                Button(
                    onClick = {
                        context.startService(Intent(context, LocationAlarmService::class.java).apply {
                            action = LocationAlarmService.ACTION_DISMISS_ALARM
                        })
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlarmRed),
                    modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedShape16
                ) {
                    Icon(Icons.Filled.VolumeMute, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("DISMISS ALARM", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
//  UTILITY FUNCTIONS
// ════════════════════════════════════════════════════════════

/** Fast distance formatting without String.format (avoids Locale/regex overhead) */
private fun formatDistanceUI(distance: Float): String {
    return if (distance >= 1000f) {
        val km = (distance / 10f).toInt() / 100f // two decimal places
        "$km km"
    } else {
        "${distance.toInt()} m"
    }
}

/** Nominatim HTTP fallback search */
private fun searchNominatim(query: String): List<SearchResult> {
    val encoded = java.net.URLEncoder.encode(query, "UTF-8")
    val conn = (java.net.URL("https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=5")
        .openConnection() as java.net.HttpURLConnection).apply {
        setRequestProperty("User-Agent", "MetroNapTransitAlarm/1.0 (contact: metronap_app@outlook.com)")
        connectTimeout = 5000; readTimeout = 5000
    }
    val json = conn.inputStream.bufferedReader().use { it.readText() }
    val arr = org.json.JSONArray(json)
    return (0 until arr.length()).map { i ->
        val obj = arr.getJSONObject(i)
        SearchResult(obj.getString("display_name"), obj.getDouble("lat"), obj.getDouble("lon"))
    }
}
