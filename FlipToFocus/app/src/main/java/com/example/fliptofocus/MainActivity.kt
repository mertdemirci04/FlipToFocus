package com.example.fliptofocus

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// --- RENK PALETİ ---
val DarkBackground = Color(0xFF101010)
val SurfaceColor = Color(0xFF1E1E1E)
val NeonGreen = Color(0xFF00FF9D)
val NeonRed = Color(0xFF00FF9D) // Reusing Green for failed state visual if desired, or keep Red
val NeonBlue = Color(0xFF00D2FF)
val NeonPurple = Color(0xFFBC13FE)
val NeonGold = Color(0xFFFFD700)
val TextWhite = Color(0xFFEEEEEE)
val TextGray = Color(0xFFAAAAAA)

enum class AppMode { TIMER, POMODORO, STOPWATCH }
enum class TimerState { IDLE, READY_TO_FLIP, FOCUSING, FAILED, COMPLETED, BREAK }

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var vibrator: Vibrator? = null
    private var notificationManager: NotificationManager? = null
    private lateinit var prefs: SharedPreferences

    private var mediaPlayer: MediaPlayer? = null
    private var feedbackPlayer: MediaPlayer? = null
    private var volumeJob: Job? = null

    private var timerState by mutableStateOf(TimerState.IDLE)
    private var currentAppMode by mutableStateOf(AppMode.TIMER)
    private var isFaceDown = false
    private var pomodoroRound by mutableIntStateOf(1)
    private var currentNoise by mutableStateOf("None")
    private var isDndEnabled by mutableStateOf(true)
    private var completionQuote by mutableStateOf("")

    private var currentSessionStartTime = 0L
    private val dailyStats = mutableStateMapOf<String, Long>()
    private var isStatsVisible by mutableStateOf(false)

    private val motivationalQuotes = listOf(
        "Bugünün galibi sensin.", "Disiplin, özgürlüktür.", "Odaklandın ve başardın!",
        "Geleceğin için büyük bir adım.", "Zihin kasını güçlendirdin.", "Harika bir iş çıkardın.",
        "Şimdi derin bir nefes al.", "Tutarlılık her şeydir.", "Engeller seni durduramaz.",
        "Zirveye bir adım daha yakınsın."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("FlipFocusPrefs", Context.MODE_PRIVATE)
        isDndEnabled = prefs.getBoolean("dnd_enabled", true)
        loadStatsFromPrefs()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= 31) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibrator = vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    FlipToFocusApp(
                        timerState = timerState,
                        appMode = currentAppMode,
                        pomodoroRound = pomodoroRound,
                        selectedNoise = currentNoise,
                        isDndEnabled = isDndEnabled,
                        completionQuote = completionQuote,
                        onAppModeChanged = { currentAppMode = it },
                        onStartRequest = {
                            if (timerState == TimerState.IDLE || timerState == TimerState.BREAK) {
                                timerState = TimerState.READY_TO_FLIP
                                vibratePhone(arrayOf(0L, 20L))
                            }
                        },
                        onReset = {
                            timerState = TimerState.IDLE
                            pomodoroRound = 1
                            stopSound(fade = true)
                        },
                        onComplete = { handleSessionComplete() },
                        onNoiseSelected = { currentNoise = it },
                        onDndToggle = { enabled ->
                            isDndEnabled = enabled
                            prefs.edit().putBoolean("dnd_enabled", enabled).apply()
                        },
                        onShowStats = { isStatsVisible = true }
                    )

                    androidx.compose.animation.AnimatedVisibility(
                        visible = isStatsVisible,
                        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
                    ) {
                        StatsOverlay(
                            statsData = dailyStats,
                            onClose = { isStatsVisible = false },
                            onReset = { resetStats() }
                        )
                    }
                }
            }
        }
    }

    private fun loadStatsFromPrefs() {
        val cal = Calendar.getInstance()
        dailyStats.clear()
        repeat(7) {
            val key = getStatKey(cal)
            dailyStats[key] = prefs.getLong(key, 0L)
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
    }

    private fun getStatKey(cal: Calendar): String {
        return "stats_${cal.get(Calendar.YEAR)}_${cal.get(Calendar.DAY_OF_YEAR)}"
    }

    private fun saveStat(seconds: Long) {
        val cal = Calendar.getInstance()
        val key = getStatKey(cal)
        val current = prefs.getLong(key, 0L)
        val newVal = current + seconds
        prefs.edit().putLong(key, newVal).apply()
        dailyStats[key] = newVal
    }

    private fun resetStats() {
        val editor = prefs.edit()
        prefs.all.keys.forEach { if (it.startsWith("stats_")) editor.remove(it) }
        editor.commit()
        dailyStats.clear()
        loadStatsFromPrefs()
    }

    private fun playFeedbackSound(name: String) {
        try {
            val resId = resources.getIdentifier(name, "raw", packageName)
            if (resId != 0) {
                feedbackPlayer?.release()
                feedbackPlayer = MediaPlayer.create(this, resId)
                feedbackPlayer?.start()
            }
        } catch (e: Exception) { Log.e("FlipSound", "Feedback ses hatası: $name") }
    }

    private fun startSound(type: String) {
        volumeJob?.cancel()
        stopSound(fade = false)
        if (type == "None") return
        val resId = resources.getIdentifier(type.lowercase(), "raw", packageName)
        if (resId != 0) {
            try {
                mediaPlayer = MediaPlayer.create(this, resId)
                mediaPlayer?.isLooping = true
                mediaPlayer?.setVolume(0f, 0f)
                mediaPlayer?.start()
                volumeJob = lifecycleScope.launch {
                    val steps = 20
                    for (i in 1..steps) {
                        val vol = i / steps.toFloat()
                        mediaPlayer?.setVolume(vol, vol)
                        delay(100L)
                    }
                }
            } catch (e: Exception) { Log.e("FlipSound", "Arka plan ses hatası") }
        }
    }

    private fun stopSound(fade: Boolean = true) {
        val player = mediaPlayer ?: return
        volumeJob?.cancel()
        if (fade && player.isPlaying) {
            volumeJob = lifecycleScope.launch {
                val steps = 15
                for (i in steps downTo 0) {
                    val vol = i / steps.toFloat()
                    player.setVolume(vol, vol)
                    delay(50L)
                }
                player.stop()
                player.release()
                mediaPlayer = null
            }
        } else {
            try { if (player.isPlaying) player.stop(); player.release() } catch (e: Exception) {}
            mediaPlayer = null
        }
    }

    private fun toggleDND(enable: Boolean) {
        if (!isDndEnabled && enable) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (notificationManager?.isNotificationPolicyAccessGranted == true) {
                    val filter = if (enable) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
                    notificationManager?.setInterruptionFilter(filter)
                } else if (enable) {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
            }
        } catch (e: Exception) { }
    }

    private fun handleSessionComplete() {
        val sessionDuration = (System.currentTimeMillis() - currentSessionStartTime) / 1000
        if (sessionDuration > 10) {
            saveStat(sessionDuration)
        }
        vibratePhone(arrayOf(0L, 100L, 50L, 100L))
        playFeedbackSound("success")
        completionQuote = motivationalQuotes.random()
        stopSound(fade = true)
        toggleDND(false)
        if (currentAppMode == AppMode.POMODORO) {
            if (timerState == TimerState.FOCUSING) timerState = TimerState.BREAK
            else if (timerState == TimerState.BREAK) { timerState = TimerState.IDLE; pomodoroRound++ }
        } else { timerState = TimerState.COMPLETED }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        stopSound(fade = false)
        toggleDND(false)
        feedbackPlayer?.release()
        feedbackPlayer = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val z = event.values[2]
            val currentlyFaceDown = z < -5.0f
            if (currentlyFaceDown && !isFaceDown) {
                if (timerState == TimerState.READY_TO_FLIP) {
                    timerState = TimerState.FOCUSING
                    currentSessionStartTime = System.currentTimeMillis()
                    vibratePhone(arrayOf(0L, 50L))
                    toggleDND(true)
                    playFeedbackSound("start")
                    lifecycleScope.launch {
                        delay(2000L)
                        if (timerState == TimerState.FOCUSING) {
                            startSound(currentNoise)
                        }
                    }
                }
            } else if (!currentlyFaceDown && isFaceDown) {
                if (timerState == TimerState.FOCUSING) {
                    toggleDND(false)
                    stopSound(fade = true)
                    if (currentAppMode == AppMode.STOPWATCH) handleSessionComplete()
                    else { timerState = TimerState.FAILED; vibratePhone(arrayOf(0L, 500L)) }
                }
            }
            isFaceDown = currentlyFaceDown
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    private fun vibratePhone(pattern: Array<Long>) {
        try {
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= 26) {
                    val primitivePattern = pattern.toLongArray()
                    vibrator?.vibrate(VibrationEffect.createWaveform(primitivePattern, -1))
                } else { @Suppress("DEPRECATION") vibrator?.vibrate(pattern.toLongArray(), -1) }
            }
        } catch (e: Exception) { }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
@Composable
fun FlipToFocusApp(
    timerState: TimerState, appMode: AppMode, pomodoroRound: Int,
    selectedNoise: String, isDndEnabled: Boolean, completionQuote: String,
    onAppModeChanged: (AppMode) -> Unit, onStartRequest: () -> Unit,
    onReset: () -> Unit, onComplete: () -> Unit, onNoiseSelected: (String) -> Unit,
    onDndToggle: (Boolean) -> Unit, onShowStats: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(pagerState.currentPage) {
        val targetMode = when(pagerState.currentPage) { 0 -> AppMode.TIMER; 1 -> AppMode.POMODORO; else -> AppMode.STOPWATCH }
        onAppModeChanged(targetMode)
    }

    var selectedTimerMinutes by remember { mutableIntStateOf(25) }
    var selectedPomodoroMinutes by remember { mutableIntStateOf(25) }
    var timeLeftSeconds by remember { mutableLongStateOf(25 * 60L) }
    var totalTimeSeconds by remember { mutableLongStateOf(25 * 60L) }
    var elapsedTimeSeconds by remember { mutableLongStateOf(0L) }
    val POMODORO_BREAK_SEC = 5 * 60L

    val targetBgColor = remember(timerState, appMode) {
        when (timerState) {
            TimerState.FAILED -> Color(0xFF2D0000); TimerState.FOCUSING -> Color.Black
            TimerState.COMPLETED -> if (appMode == AppMode.TIMER) Color(0xFF003300) else Color(0xFF1E0033)
            TimerState.BREAK -> Color(0xFF001F3F); else -> DarkBackground
        }
    }
    val animatedBgColor by animateColorAsState(targetBgColor, animationSpec = tween(800), label = "bg")

    LaunchedEffect(timerState, appMode, selectedTimerMinutes, selectedPomodoroMinutes) {
        if (timerState == TimerState.IDLE) {
            if (appMode == AppMode.TIMER) {
                timeLeftSeconds = selectedTimerMinutes * 60L
                totalTimeSeconds = timeLeftSeconds
            } else if (appMode == AppMode.POMODORO) {
                timeLeftSeconds = selectedPomodoroMinutes * 60L
                totalTimeSeconds = timeLeftSeconds
            }
            return@LaunchedEffect
        }

        if (timerState == TimerState.FOCUSING || timerState == TimerState.BREAK) {
            timeLeftSeconds = when (appMode) {
                AppMode.POMODORO -> if (timerState == TimerState.FOCUSING) selectedPomodoroMinutes * 60L else POMODORO_BREAK_SEC
                AppMode.TIMER -> selectedTimerMinutes * 60L
                else -> timeLeftSeconds
            }
            totalTimeSeconds = timeLeftSeconds
            while (isActive && timeLeftSeconds > 0 && (timerState == TimerState.FOCUSING || timerState == TimerState.BREAK)) {
                delay(1000)
                timeLeftSeconds--
            }
            if (timeLeftSeconds == 0L) onComplete()
        }
    }

    LaunchedEffect(timerState, appMode) {
        if (appMode == AppMode.STOPWATCH) {
            if (timerState == TimerState.IDLE) elapsedTimeSeconds = 0L
            if (timerState == TimerState.FOCUSING) {
                var startMillis = System.currentTimeMillis() - (elapsedTimeSeconds * 1000)
                while (timerState == TimerState.FOCUSING) {
                    elapsedTimeSeconds = (System.currentTimeMillis() - startMillis) / 1000
                    delay(500L)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(animatedBgColor).padding(top = 24.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
            // Header
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                androidx.compose.animation.AnimatedVisibility(visible = timerState == TimerState.IDLE) {
                    IconButton(onClick = onShowStats, modifier = Modifier.align(Alignment.CenterEnd).clip(CircleShape).background(SurfaceColor).size(40.dp)) {
                        Icon(Icons.Default.List, contentDescription = "Stats", tint = NeonBlue)
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(visible = timerState == TimerState.IDLE) {
                TabRow(selectedTabIndex = pagerState.currentPage, containerColor = Color.Transparent, contentColor = TextWhite, indicator = { tabPositions ->
                    if (pagerState.currentPage < tabPositions.size) {
                        TabRowDefaults.Indicator(Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]), color = when(pagerState.currentPage)
                        { 0 -> NeonGreen;
                            1 -> NeonGold;
                            else -> NeonPurple })
                    }
                }) {
                    Tab(selected = pagerState.currentPage == 0, onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } }, text = { Text("TIMER", fontSize = 11.sp, fontWeight = FontWeight.Bold) })
                    Tab(selected = pagerState.currentPage == 1, onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } }, text = { Text("POMODORO", fontSize = 11.sp, fontWeight = FontWeight.Bold) })
                    Tab(selected = pagerState.currentPage == 2, onClick = { coroutineScope.launch { pagerState.animateScrollToPage(2) } }, text = { Text("KRONO", fontSize = 11.sp, fontWeight = FontWeight.Bold) })
                }
            }



            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                userScrollEnabled = timerState == TimerState.IDLE,
                beyondBoundsPageCount = 2
            ) { page ->
                val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                val absOffset = abs(pageOffset).coerceIn(0f, 1f)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // 3D KÜP VE PARALLAX EFEKTİ
                            val scaleValue = lerp(1f, 0.82f, absOffset)
                            scaleX = scaleValue; scaleY = scaleValue
                            alpha = lerp(1f, 0.3f, absOffset)
                            rotationY = pageOffset * 28f
                            cameraDistance = 12f * density
                            translationX = pageOffset * size.width * 0.2f
                        },
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly // 🔥 Tam Denge
                    ) {
                        // 1. Sayaç Alanı
                        val progress = remember(timeLeftSeconds, totalTimeSeconds) { if (totalTimeSeconds > 0) timeLeftSeconds.toFloat() / totalTimeSeconds.toFloat() else 0f }
                        val animatedProgress by animateFloatAsState(targetValue = progress, animationSpec = tween(600), label = "p")

                        Box(contentAlignment = Alignment.Center) {
                            when(page) {
                                0 -> LiquidTimerDisplay(animatedProgress, timerState, timeLeftSeconds, NeonGreen)
                                1 -> {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("TUR $pomodoroRound", color = NeonGold, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(12.dp))
                                        LiquidTimerDisplay(animatedProgress, timerState, timeLeftSeconds, NeonGold)
                                    }
                                }
                                2 -> LiquidTimerDisplay(1f, timerState, elapsedTimeSeconds, NeonPurple)
                            }
                        }

                        if (page == 0) {
                            Spacer(modifier = Modifier.height(24.dp))
                        }

                        // 🔥 TAM ENTEGRASYON: Tüm Ayarlar Pager'ın İçinde
                        val verticalGap = 20.dp

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(verticalGap)
                        ) {
                            AnimatedContent(targetState = timerState, label = "settings") { state ->
                                if (state == TimerState.IDLE || state == TimerState.BREAK) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(verticalGap)
                                    ) {
                                        if (state == TimerState.IDLE) {
                                            NoiseSelector(selectedNoise, onNoiseSelected)
                                            DndToggle(isDndEnabled, onDndToggle)

                                            when(page) {
                                                0 -> {
                                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(verticalGap)) {
                                                        Box(modifier = Modifier.height(140.dp).fillMaxWidth()) { FancyWheelTimePicker(selectedTimerMinutes, onMinuteSelected = { selectedTimerMinutes = it }) }
                                                        DurationSelector(selectedTimerMinutes, color = NeonGreen) { selectedTimerMinutes = it }
                                                    }
                                                }
                                                1 -> {
                                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                        Text("ODAKLANMA SÜRESİ", color = TextGray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                        DurationSelector(selectedPomodoroMinutes, color = NeonGold) { selectedPomodoroMinutes = it }
                                                    }
                                                }
                                            }
                                        } else if (state == TimerState.BREAK) {
                                            Text("MOLA VAKTİ", color = NeonBlue, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                                        }

                                        Spacer(
                                            modifier = Modifier.height(
                                                if (page == 2) 12.dp else 8.dp
                                            )
                                        )
                                        // 🔥 Birlikte Hareket Eden Buton
                                        ActionButton(
                                            text = if (state == TimerState.BREAK) "MOLAYI BİTİR" else "BAŞLAT",
                                            color = when(page) { 0 -> NeonGreen; 1 -> NeonGold; else -> NeonPurple },
                                            textColor = Color.Black,
                                            onClick = onStartRequest
                                        )
                                    }
                                } else {
                                    StatusMessageDisplay(state, appMode, onReset, completionQuote)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}





//Burada kaldık
@Composable
fun StatsOverlay(statsData: Map<String, Long>, onClose: () -> Unit, onReset: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(DarkBackground.copy(alpha = 0.95f)).clickable { onClose() }) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp).clickable(enabled = false) {}, horizontalAlignment = Alignment.CenterHorizontally) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("ANALİZ", color = NeonBlue, fontSize = 24.sp, fontWeight = FontWeight.Black)
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close", tint = TextGray) }
            }
            Spacer(modifier = Modifier.height(32.dp))

            val cal = Calendar.getInstance()
            val todayKey = "stats_${cal.get(Calendar.YEAR)}_${cal.get(Calendar.DAY_OF_YEAR)}"
            val totalToday = statsData[todayKey] ?: 0L
            val totalWeek = statsData.values.sum()

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatCard("BUGÜN", formatDuration(totalToday), NeonBlue, Modifier.weight(1f))
                StatCard("BU HAFTA", formatDuration(totalWeek), NeonPurple, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(32.dp))
            Text("SON 7 GÜN", color = TextGray, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start)); Spacer(modifier = Modifier.height(16.dp))
            Box(modifier = Modifier.fillMaxWidth().height(160.dp).background(SurfaceColor, RoundedCornerShape(24.dp)).padding(20.dp)) {
                Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    val maxVal = statsData.values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
                    val keys = (0..6).map {
                        val c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, -it)
                        "stats_${c.get(Calendar.YEAR)}_${c.get(Calendar.DAY_OF_YEAR)}"
                    }.reversed()
                    keys.forEach { key ->
                        val value = statsData[key] ?: 0L
                        val heightFactor = (value.toFloat() / maxVal.toFloat()).coerceIn(0.1f, 1f)
                        val isToday = key == todayKey
                        Box(modifier = Modifier.width(18.dp).fillMaxHeight(heightFactor).clip(RoundedCornerShape(6.dp)).background(if (isToday) NeonBlue else TextGray.copy(alpha = 0.2f)))
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            ActionButton("VERİLERİ SIFIRLA", NeonRed.copy(alpha = 0.1f), NeonRed, onReset)
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun StatCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier.background(SurfaceColor, RoundedCornerShape(20.dp)).padding(16.dp)) {
        Text(label, color = TextGray, fontSize = 11.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.height(8.dp))
        Text(value, color = color, fontSize = 22.sp, fontWeight = FontWeight.Black)
    }
}

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600; val m = (seconds % 3600) / 60
    return if (h > 0) "${h}sa ${m}dk" else "${m}dk"
}

@Composable
fun NoiseSelector(selected: String, onSelect: (String) -> Unit) {
    val sounds = remember { listOf("None", "Rain", "Waves", "Zen") }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        sounds.forEach { name ->
            val isSelected = selected == name
            Box(modifier = Modifier.clip(CircleShape).background(if (isSelected) NeonBlue.copy(alpha = 0.2f) else SurfaceColor).border(1.dp, if (isSelected) NeonBlue else Color.Transparent, CircleShape).clickable { onSelect(name) }.padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text(name, color = if (isSelected) NeonBlue else TextGray, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun DndToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(SurfaceColor).clickable { onToggle(!enabled) }.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(if (enabled) NeonBlue else TextGray))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = if (enabled) "Rahatsız Etme: AÇIK" else "Rahatsız Etme: KAPALI", color = if (enabled) NeonBlue else TextGray, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun StatusMessageDisplay(state: TimerState, mode: AppMode, onReset: () -> Unit, quote: String) {
    val themeColor = remember(mode) { when(mode) { AppMode.TIMER -> NeonGreen; AppMode.POMODORO -> NeonGold; else -> NeonPurple } }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when (state) {
            TimerState.READY_TO_FLIP -> { Text("TELEFONU TERS ÇEVİR", color = NeonBlue, fontWeight = FontWeight.Bold); TextButton(onClick = onReset) { Text("İPTAL", color = TextGray) } }
            TimerState.FOCUSING -> { Text("ODAKLANILIYOR...", color = themeColor, fontWeight = FontWeight.Bold); Text("Odak Modu Aktif", color = TextGray, fontSize = 12.sp) }
            TimerState.FAILED -> { Text("ODAK BOZULDU!", color = NeonRed, fontSize = 24.sp, fontWeight = FontWeight.Black); Spacer(modifier = Modifier.height(24.dp)); ActionButton("TEKRAR DENE", SurfaceColor, TextWhite, onReset) }
            TimerState.COMPLETED -> { Text("TEBRİKLER!", color = themeColor, fontSize = 24.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.height(8.dp)); Text(quote, color = TextWhite, textAlign = TextAlign.Center, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 32.dp)); Spacer(modifier = Modifier.height(24.dp)); ActionButton("YENİ OTURUM", themeColor, Color.Black, onReset) }
            else -> {}
        }
    }
}

@Composable
fun LiquidTimerDisplay(progress: Float, state: TimerState, seconds: Long, color: Color) {
    val displayColor = remember(state, color) { if (state == TimerState.FAILED) NeonRed else color }
    val infiniteTransition = rememberInfiniteTransition(label = "l")
    val wavePhase by infiniteTransition.animateFloat(initialValue = 0f, targetValue = 2 * PI.toFloat(), animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(260.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2 - 10.dp.toPx()
            val center = Offset(size.width / 2, size.height / 2)
            drawCircle(color = SurfaceColor.copy(alpha = 0.5f), radius = radius, style = Stroke(width = 12.dp.toPx()))
            if (progress > 0) {
                val path = Path()
                val sweep = 360f * progress
                val start = -90f
                val wf = 12f
                val waveIntensity = if (state != TimerState.FOCUSING) 10f else 0f
                for (a in 0..sweep.toInt() step 2) {
                    val rad = ((start + a) * PI / 180).toFloat()
                    val w = sin((a * PI / 180 * wf) + wavePhase).toFloat() * waveIntensity
                    val r = radius + w
                    val x = center.x + r * cos(rad).toFloat()
                    val y = center.y + r * sin(rad).toFloat()
                    if (a == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                if (progress >= 0.99f) path.close()
                drawPath(path, displayColor, style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        OdometerText(seconds, TextWhite)
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun OdometerText(seconds: Long, color: Color) {
    val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
    val text = if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    Row(verticalAlignment = Alignment.CenterVertically) {
        text.forEach { char ->
            if (char.isDigit()) {
                AnimatedContent(targetState = char, transitionSpec = { slideInVertically { it } + fadeIn() togetherWith slideOutVertically { -it } + fadeOut() }, label = "d") { Text(it.toString(), color = color, fontSize = 50.sp, fontWeight = FontWeight.Bold) }
            } else { Text(char.toString(), color = color, fontSize = 50.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FancyWheelTimePicker(initialMinute: Int, onMinuteSelected: (Int) -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (Int.MAX_VALUE / 2) - ((Int.MAX_VALUE / 2) % 180) + (initialMinute - 1))
    val fling = rememberSnapFlingBehavior(listState)
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val viewportHeightPx = with(density) { 140.dp.toPx() }
    val itemHeightPx = with(density) { 40.dp.toPx() }
    val centerPx = viewportHeightPx / 2f
    val paddingOffsetPx = with(density) { 50.dp.toPx() }

    val currentMinute by remember { derivedStateOf { (listState.firstVisibleItemIndex % 180) + 1 } }
    var init by remember { mutableStateOf(true) }

    LaunchedEffect(currentMinute) {
        onMinuteSelected(currentMinute)
        if (!init && listState.isScrollInProgress) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        init = false
    }

    Box(modifier = Modifier.fillMaxWidth().height(140.dp)) {
        LazyColumn(state = listState, flingBehavior = fling, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 50.dp)) {
            items(Int.MAX_VALUE) { i ->
                Box(modifier = Modifier.fillMaxWidth().height(40.dp).graphicsLayer {
                    val indexOffset = (i - listState.firstVisibleItemIndex)
                    val itemTop = indexOffset * itemHeightPx - listState.firstVisibleItemScrollOffset
                    val itemCenter = itemTop + (itemHeightPx / 2f) + paddingOffsetPx
                    val dist = abs(centerPx - itemCenter)
                    val sc = (1.3f - (dist / (viewportHeightPx * 0.7f))).coerceIn(0.6f, 1.3f)
                    scaleX = sc
                    scaleY = sc
                    alpha = (1f - (dist / (viewportHeightPx * 0.6f))).coerceIn(0.1f, 1f)
                    rotationX = (itemCenter - centerPx) / 6f
                }, contentAlignment = Alignment.Center) {
                    Text(text = ((i % 180) + 1).toString(), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = NeonGreen)
                }
            }
        }
        Box(modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.4f).height(48.dp).background(NeonGreen.copy(alpha = 0.05f), RoundedCornerShape(12.dp))) {
            val indicatorBrush = remember { Brush.horizontalGradient(listOf(Color.Transparent, NeonGreen, Color.Transparent)) }
            Box(Modifier.fillMaxWidth().height(1.5.dp).background(indicatorBrush).align(Alignment.TopCenter))
            Box(Modifier.fillMaxWidth().height(1.5.dp).background(indicatorBrush).align(Alignment.BottomCenter))
        }
        val maskBrushTop = remember { Brush.verticalGradient(listOf(DarkBackground, Color.Transparent)) }
        val maskBrushBottom = remember { Brush.verticalGradient(listOf(Color.Transparent, DarkBackground)) }
        Box(modifier = Modifier.fillMaxWidth().height(45.dp).align(Alignment.TopCenter).background(maskBrushTop))
        Box(modifier = Modifier.fillMaxWidth().height(45.dp).align(Alignment.BottomCenter).background(maskBrushBottom))
    }
}

@Composable
fun DurationSelector(selected: Int, color: Color, onSelect: (Int) -> Unit) {
    val options = remember { listOf(15, 25, 45, 60) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { m ->
            Surface(modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSelect(m) }, color = if (selected == m) color.copy(alpha = 0.2f) else SurfaceColor, border = if (selected == m) androidx.compose.foundation.BorderStroke(1.dp, color) else null) { Text("${m}m", color = if (selected == m) color else TextGray, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
        }
    }
}

@Composable
fun ActionButton(text: String, color: Color, textColor: Color, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = color), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(text, color = textColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}
