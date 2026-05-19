package com.example.myapplication

// ─── Imports ──────────────────────────────────────────────────────────────────
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

// ─── Constants ────────────────────────────────────────────────────────────────
const val CHANNEL_ID = "habitflow_daily"
const val PREF_NAME  = "habits_prefs"

// ─── Colour palette ───────────────────────────────────────────────────────────
val Purple      = Color(0xFF7C3AED)
val PurpleLight = Color(0xFFDDD6FE)
val Teal        = Color(0xFF0D9488)
val Amber       = Color(0xFFF59E0B)
val Rose        = Color(0xFFE11D48)
val Emerald     = Color(0xFF10B981)
val Sky         = Color(0xFF0EA5E9)


// ─── Category definitions ─────────────────────────────────────────────────────
data class Category(val name: String, val emoji: String, val color: Color)

val CATEGORIES = listOf(
    Category("General",     "📌", Purple),
    Category("Fitness",     "💪", Rose),
    Category("Health",      "🥗", Emerald),
    Category("Study",       "📚", Sky),
    Category("Mindfulness", "🧘", Teal),
    Category("Productivity","⚡", Amber),
)

// ─── Data models ──────────────────────────────────────────────────────────────
data class Habit(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val categoryIndex: Int = 0,
    val completedDates: MutableList<String> = mutableListOf()
)

data class UserProfile(
    val xp: Int = 0,
    val level: Int = 1,
    val badges: MutableList<String> = mutableListOf()
)

// ─── Helpers ──────────────────────────────────────────────────────────────────
fun todayStr(): String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

fun calculateStreak(habit: Habit): Int {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    var streak = 0
    val cal = Calendar.getInstance()
    while (true) {
        if (habit.completedDates.contains(sdf.format(cal.time))) { streak++; cal.add(Calendar.DAY_OF_YEAR, -1) }
        else break
    }
    return streak
}

fun calculateLongestStreak(habit: Habit): Int {
    if (habit.completedDates.isEmpty()) return 0
    val sorted = habit.completedDates.sorted()
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    var longest = 1; var current = 1
    for (i in 1 until sorted.size) {
        val prev = Calendar.getInstance().apply { time = sdf.parse(sorted[i - 1])!! }
        val curr = Calendar.getInstance().apply { time = sdf.parse(sorted[i])!! }
        prev.add(Calendar.DAY_OF_YEAR, 1)
        if (sdf.format(prev.time) == sdf.format(curr.time)) { current++; if (current > longest) longest = current }
        else current = 1
    }
    return longest
}

fun weeklyCompletionPct(habit: Habit): Int {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val cal = Calendar.getInstance()
    var count = 0
    repeat(7) { i ->
        val key = sdf.format(cal.time)
        if (habit.completedDates.contains(key)) count++
        cal.add(Calendar.DAY_OF_YEAR, -1)
    }
    return (count * 100) / 7
}

fun xpForNextLevel(level: Int) = level * 100
fun levelFromXp(xp: Int): Int {
    var lvl = 1; var consumed = 0
    while (true) { consumed += xpForNextLevel(lvl); if (xp < consumed) return lvl; lvl++ }
}
fun xpInLevel(xp: Int): Int {
    val level = levelFromXp(xp); var consumed = 0
    for (l in 1 until level) consumed += xpForNextLevel(l)
    return xp - consumed
}

fun formatTime(s: Long): String {
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%02d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

// ─── Notification ─────────────────────────────────────────────────────────────
fun createNotificationChannel(ctx: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val ch = NotificationChannel(CHANNEL_ID, "Daily Reminder", NotificationManager.IMPORTANCE_DEFAULT)
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }
}

fun scheduleReminder(ctx: Context, hour: Int, minute: Int) {
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
    val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, HabitReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0)
        if (before(Calendar.getInstance())) add(Calendar.DAY_OF_YEAR, 1)
    }
    am.setRepeating(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis,
        android.app.AlarmManager.INTERVAL_DAY, pi)
}

class HabitReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🌱 HabitFlow")
            .setContentText("Time to check off your habits!")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi).setAutoCancel(true).build()
        NotificationManagerCompat.from(ctx).notify(1, n)
    }
}

// ─── Persistence ──────────────────────────────────────────────────────────────
fun loadHabits(prefs: android.content.SharedPreferences): List<Habit> {
    val data = prefs.getString("habits", null) ?: return emptyList()
    return try {
        val arr = JSONArray(data)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val dates = mutableListOf<String>()
            val da = o.getJSONArray("completedDates")
            for (j in 0 until da.length()) dates.add(da.getString(j))
            Habit(
                id = if (o.has("id")) o.getString("id") else UUID.randomUUID().toString(),
                name = o.getString("name"),
                categoryIndex = if (o.has("categoryIndex")) o.getInt("categoryIndex") else 0,
                completedDates = dates
            )
        }
    } catch (e: Exception) { emptyList() }
}

fun saveHabits(prefs: android.content.SharedPreferences, habits: List<Habit>) {
    val arr = JSONArray()
    habits.forEach { h ->
        val o = JSONObject()
        o.put("id", h.id); o.put("name", h.name); o.put("categoryIndex", h.categoryIndex)
        o.put("completedDates", JSONArray(h.completedDates))
        arr.put(o)
    }
    prefs.edit().putString("habits", arr.toString()).apply()
}

fun loadProfile(prefs: android.content.SharedPreferences): UserProfile {
    val data = prefs.getString("profile", null) ?: return UserProfile()
    return try {
        val o = JSONObject(data)
        val ba = o.getJSONArray("badges")
        val badges = mutableListOf<String>()
        for (i in 0 until ba.length()) badges.add(ba.getString(i))
        UserProfile(o.getInt("xp"), o.getInt("level"), badges)
    } catch (e: Exception) { UserProfile() }
}

fun saveProfile(prefs: android.content.SharedPreferences, p: UserProfile) {
    val o = JSONObject()
    o.put("xp", p.xp); o.put("level", p.level); o.put("badges", JSONArray(p.badges))
    prefs.edit().putString("profile", o.toString()).apply()
}

// ─── Firebase sync ────────────────────────────────────────────────────────────
suspend fun syncToFirebase(habits: List<Habit>, profile: UserProfile) {
    try {
        val auth = Firebase.auth
        if (auth.currentUser == null) auth.signInAnonymously().await()
        val uid = auth.currentUser?.uid ?: return
        val db = Firebase.firestore
        val habitsData = habits.map { h ->
            mapOf("id" to h.id, "name" to h.name,
                "categoryIndex" to h.categoryIndex,
                "completedDates" to h.completedDates)
        }
        db.collection("users").document(uid)
            .set(mapOf("habits" to habitsData,
                "xp" to profile.xp, "level" to profile.level,
                "badges" to profile.badges, "updatedAt" to System.currentTimeMillis()))
            .await()
    } catch (_: Exception) {}
}

// ─── MainActivity ─────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel(this)
        enableEdgeToEdge()
        setContent { HabitFlowApp() }
    }
}

// ─── Onboarding ───────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pages = listOf(
        Triple("🌱", "Welcome to HabitFlow", "Build powerful habits that stick. Track your progress every day and watch your life transform."),
        Triple("🔥", "Build Streaks", "Consistency is everything. Keep your streak alive to earn XP, unlock badges and level up your profile."),
        Triple("📊", "Track Your Stats", "See weekly completion rates, your longest streak, and a full calendar heatmap for every habit."),
        Triple("🎮", "Earn Rewards", "Complete habits to earn XP and level up. Unlock milestone badges like 🔥 Week Warrior and 💎 Century Club."),
        Triple("🚀", "Let's Start!", "Add your first habit below. We've suggested a few to get you going — or create your own."),
    )
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0F0F23), Color(0xFF1E1B4B))))
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val (emoji, title, body) = pages[page]
            Column(
                modifier = Modifier.fillMaxSize().padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(emoji, fontSize = 80.sp)
                Spacer(Modifier.height(32.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 26.sp,
                    color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Text(body, fontSize = 15.sp, color = Color(0xFFB0B0D0),
                    textAlign = TextAlign.Center, lineHeight = 22.sp)
            }
        }
        // Dots
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            repeat(pages.size) { i ->
                Box(
                    modifier = Modifier
                        .size(if (pagerState.currentPage == i) 24.dp else 8.dp, 8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (pagerState.currentPage == i) Purple else Color(0xFF4B4B7B))
                        .animateContentSize()
                )
            }
        }
        // Button
        Button(
            onClick = {
                if (pagerState.currentPage < pages.size - 1)
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                else onFinish()
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .fillMaxWidth(0.7f)
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Purple),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(if (pagerState.currentPage < pages.size - 1) "Next →" else "Get Started 🚀",
                fontWeight = FontWeight.Bold)
        }
    }
}

// ─── Progress Ring ────────────────────────────────────────────────────────────
@Composable
fun ProgressRing(progress: Float, size: Int, strokeWidth: Float, color: Color, label: String) {
    val animProg by animateFloatAsState(targetValue = progress, animationSpec = tween(800), label = "ring")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size.dp)) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(size.dp)) {
            drawArc(color = color.copy(alpha = 0.15f), startAngle = -90f, sweepAngle = 360f, useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
            drawArc(color = color, startAngle = -90f, sweepAngle = 360f * animProg, useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
        }
        Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center, color = color)
    }
}

// ─── Animated Checkbox ────────────────────────────────────────────────────────
@Composable
fun AnimatedCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit, color: Color) {
    val scale by animateFloatAsState(targetValue = if (checked) 1.2f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "cb")
    Box(
        modifier = Modifier
            .size(24.dp)
            .scale(scale)
            .clip(RoundedCornerShape(6.dp))
            .background(if (checked) color else Color.Transparent)
            .border(1.5.dp, if (checked) color else Color.Gray, RoundedCornerShape(6.dp))
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center
    ) {
        if (checked) Text("✓", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

// ─── Habit Card ───────────────────────────────────────────────────────────────
@Composable
fun HabitCard(
    habit: Habit,
    today: String,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onTimer: () -> Unit,
    onHeatmap: () -> Unit
) {
    val completed = habit.completedDates.contains(today)
    val streak    = calculateStreak(habit)
    val cat       = CATEGORIES[habit.categoryIndex.coerceIn(0, CATEGORIES.size - 1)]

    var offsetX by remember { mutableStateOf(0f) }
    val animOffset by animateFloatAsState(targetValue = offsetX, label = "swipe")
    var showDelete by remember { mutableStateOf(false) }

    val cardScale by animateFloatAsState(targetValue = if (completed) 0.98f else 1f, label = "card")

    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
        // Delete hint behind card
        if (showDelete) {
            Box(
                modifier = Modifier.matchParentSize().clip(RoundedCornerShape(14.dp))
                    .background(Rose.copy(alpha = 0.15f)),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text("🗑  Delete", color = Rose, modifier = Modifier.padding(end = 20.dp),
                    fontWeight = FontWeight.Medium)
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .scale(cardScale)
                .offset(x = animOffset.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX < -80f) { onDelete(); offsetX = 0f }
                            else offsetX = 0f
                            showDelete = false
                        },
                        onHorizontalDrag = { _, delta ->
                            offsetX = (offsetX + delta / 3f).coerceIn(-120f, 0f)
                            showDelete = offsetX < -20f
                        }
                    )
                },
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (completed) cat.color.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(if (completed) 0.dp else 2.dp)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Category dot
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(cat.color))

                AnimatedCheckbox(checked = completed, onCheckedChange = { onToggle() }, color = cat.color)

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = habit.name,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            textDecoration = if (completed) TextDecoration.LineThrough else TextDecoration.None,
                            color = if (completed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.onSurface
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${cat.emoji} ${cat.name}", fontSize = 11.sp,
                            color = cat.color.copy(alpha = 0.8f))
                        if (streak > 0)
                            Text("🔥 $streak day streak", fontSize = 11.sp, color = Color(0xFFFF6B35))
                    }
                }

                Row {
                    IconButton(onClick = onHeatmap, modifier = Modifier.size(32.dp)) {
                        Text("📅", fontSize = 14.sp)
                    }
                    IconButton(onClick = onTimer, modifier = Modifier.size(32.dp)) {
                        Text("⏱", fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

// ─── Stats Screen ─────────────────────────────────────────────────────────────
@Composable
fun StatsScreen(habits: List<Habit>) {
    val today = todayStr()
    val totalCompleted = habits.sumOf { it.completedDates.size }
    val todayCount = habits.count { it.completedDates.contains(today) }
    val allStreaks = habits.map { calculateStreak(it) }
    val allLongest = habits.map { calculateLongestStreak(it) }
    val bestStreak = allLongest.maxOrNull() ?: 0
    val weeklyPcts = habits.map { weeklyCompletionPct(it) }
    val avgWeekly = if (weeklyPcts.isEmpty()) 0 else weeklyPcts.average().toInt()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text("📊 Statistics", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(bottom = 4.dp))
        }

        // Summary rings
        item {
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(20.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ProgressRing(
                        progress = if (habits.isEmpty()) 0f else todayCount.toFloat() / habits.size,
                        size = 90, strokeWidth = 10f, color = Purple,
                        label = "$todayCount/${habits.size}\nToday"
                    )
                    ProgressRing(
                        progress = avgWeekly / 100f, size = 90, strokeWidth = 10f,
                        color = Emerald, label = "$avgWeekly%\nWeekly"
                    )
                    ProgressRing(
                        progress = (bestStreak.coerceAtMost(30)) / 30f, size = 90,
                        strokeWidth = 10f, color = Amber, label = "🔥$bestStreak\nBest"
                    )
                }
            }
        }

        // Per-habit breakdown
        item {
            Text("Habit Breakdown", fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                modifier = Modifier.padding(top = 4.dp))
        }

        itemsIndexed(habits) { _, habit ->
            val cat = CATEGORIES[habit.categoryIndex.coerceIn(0, CATEGORIES.size - 1)]
            val streak = calculateStreak(habit)
            val longest = calculateLongestStreak(habit)
            val weekly = weeklyCompletionPct(habit)
            Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(cat.emoji, fontSize = 18.sp)
                        Text(habit.name, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { weekly / 100f },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = cat.color
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        StatChip("This week", "$weekly%", cat.color)
                        StatChip("Streak", "🔥$streak", Color(0xFFFF6B35))
                        StatChip("Best", "$longest days", Amber)
                        StatChip("Total", "${habit.completedDates.size}", Sky)
                    }
                }
            }
        }

        if (habits.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(64.dp), contentAlignment = Alignment.Center) {
                    Text("No habits yet.\nAdd some to see stats.", textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
fun StatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = color)
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}


// ─── Calendar Heatmap ─────────────────────────────────────────────────────────
@Composable
fun HeatmapDialog(habit: Habit, today: String, onDismiss: () -> Unit) {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val days = (48 downTo 0).map { offset ->
        val cal = Calendar.getInstance().apply { time = sdf.parse(today)!!; add(Calendar.DAY_OF_YEAR, -offset) }
        sdf.format(cal.time)
    }
    val cat = CATEGORIES[habit.categoryIndex.coerceIn(0, CATEGORIES.size - 1)]
    val streak = calculateStreak(habit)
    val longest = calculateLongestStreak(habit)

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${cat.emoji} ${habit.name}", fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("🔥 Streak: $streak", fontSize = 13.sp, color = Color(0xFFFF6B35))
                    Text("🏆 Best: $longest", fontSize = 13.sp, color = Amber)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("S","M","T","W","T","F","S").forEach {
                        Text(it, fontSize = 9.sp, color = Color.Gray,
                            modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    }
                }
                Spacer(Modifier.height(4.dp))
                days.chunked(7).forEach { week ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(vertical = 2.dp)) {
                        week.forEach { dateStr ->
                            val done = habit.completedDates.contains(dateStr)
                            val isToday = dateStr == today
                            Box(
                                modifier = Modifier.weight(1f).aspectRatio(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        when { done && isToday -> cat.color
                                            done -> cat.color.copy(alpha = 0.45f)
                                            else -> MaterialTheme.colorScheme.surfaceVariant }
                                    )
                                    .then(if (isToday) Modifier.border(1.5.dp, cat.color, RoundedCornerShape(4.dp)) else Modifier)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LegendDot(MaterialTheme.colorScheme.surfaceVariant, "Missed")
                    LegendDot(cat.color.copy(alpha = 0.45f), "Done")
                    LegendDot(cat.color, "Today")
                }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
            }
        }
    }
}

@Composable
fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(label, fontSize = 10.sp, color = Color.Gray)
    }
}

// ─── Timer Dialog ─────────────────────────────────────────────────────────────
@Composable
fun TimerDialog(habitName: String, onDismiss: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var cdMins by remember { mutableStateOf("5") }
    var cdLeft by remember { mutableStateOf(0L) }
    var cdRunning by remember { mutableStateOf(false) }
    var cdDone by remember { mutableStateOf(false) }
    var elSecs by remember { mutableStateOf(0L) }
    var elRunning by remember { mutableStateOf(false) }

    LaunchedEffect(cdRunning) {
        while (cdRunning && cdLeft > 0) { delay(1000L); cdLeft--
            if (cdLeft == 0L) { cdRunning = false; cdDone = true } }
    }
    LaunchedEffect(elRunning) { while (elRunning) { delay(1000L); elSecs++ } }

    val phases = listOf(25*60L to "🍅 Focus", 5*60L to "☕ Break", 15*60L to "🌿 Long Break")
    var phaseIdx by remember { mutableStateOf(0) }
    var pomLeft by remember { mutableStateOf(phases[0].first) }
    var pomRunning by remember { mutableStateOf(false) }
    var pomRound by remember { mutableStateOf(1) }
    LaunchedEffect(pomRunning) {
        while (pomRunning && pomLeft > 0) { delay(1000L); pomLeft--
            if (pomLeft == 0L) { pomRunning = false
                val next = (phaseIdx + 1) % phases.size
                if (next == 0) pomRound++; phaseIdx = next; pomLeft = phases[next].first } }
    }

    Dialog(onDismissRequest = { cdRunning = false; elRunning = false; pomRunning = false; onDismiss() }) {
        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("⏱ $habitName", fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                TabRow(selectedTabIndex = tab) {
                    listOf("Countdown", "Elapsed", "Pomodoro").forEachIndexed { i, label ->
                        Tab(selected = tab == i, onClick = {
                            cdRunning = false; elRunning = false; pomRunning = false; tab = i
                        }, text = { Text(label, fontSize = 12.sp) })
                    }
                }
                Spacer(Modifier.height(20.dp))
                when (tab) {
                    0 -> {
                        if (!cdRunning && cdLeft == 0L && !cdDone) {
                            OutlinedTextField(value = cdMins, onValueChange = { cdMins = it.filter(Char::isDigit) },
                                label = { Text("Minutes") }, singleLine = true, modifier = Modifier.width(130.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = { val m = cdMins.toLongOrNull() ?: 0L
                                if (m > 0) { cdLeft = m * 60; cdDone = false; cdRunning = true } },
                                modifier = Modifier.fillMaxWidth()) { Text("Start") }
                        } else {
                            Text(if (cdDone) "✅ Done!" else formatTime(cdLeft),
                                fontSize = 56.sp, fontWeight = FontWeight.Bold,
                                color = if (!cdDone && cdLeft <= 10) Rose else Purple)
                            Spacer(Modifier.height(14.dp))
                            if (!cdDone) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { cdRunning = !cdRunning }, Modifier.weight(1f)) {
                                    Text(if (cdRunning) "Pause" else "Resume") }
                                OutlinedButton(onClick = { cdRunning = false; cdLeft = 0L; cdDone = false }, Modifier.weight(1f)) { Text("Reset") }
                            } else Button(onClick = { cdLeft = 0L; cdDone = false }, Modifier.fillMaxWidth()) { Text("New Timer") }
                        }
                    }
                    1 -> {
                        Text(formatTime(elSecs), fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Purple)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { elRunning = !elRunning }, Modifier.weight(1f)) {
                                Text(if (elRunning) "Pause" else if (elSecs == 0L) "Start" else "Resume") }
                            OutlinedButton(onClick = { elRunning = false; elSecs = 0L }, Modifier.weight(1f)) { Text("Reset") }
                        }
                    }
                    2 -> {
                        Text(phases[phaseIdx].second, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Purple)
                        Text("Round $pomRound", fontSize = 12.sp, color = Color.Gray)
                        Spacer(Modifier.height(8.dp))
                        Text(formatTime(pomLeft), fontSize = 56.sp, fontWeight = FontWeight.Bold,
                            color = if (pomLeft <= 10) Rose else Purple)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { pomRunning = !pomRunning }, Modifier.weight(1f)) {
                                Text(if (pomRunning) "Pause" else "Start") }
                            OutlinedButton(onClick = { pomRunning = false; phaseIdx = 0; pomLeft = phases[0].first; pomRound = 1 }, Modifier.weight(1f)) { Text("Reset") }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { cdRunning = false; elRunning = false; pomRunning = false; onDismiss() },
                    modifier = Modifier.align(Alignment.End)) { Text("Close") }
            }
        }
    }
}

// ─── Profile Dialog ───────────────────────────────────────────────────────────
@Composable
fun ProfileDialog(profile: UserProfile, onDismiss: () -> Unit) {
    val level = levelFromXp(profile.xp)
    val xpIn  = xpInLevel(profile.xp)
    val xpNeed = xpForNextLevel(level)
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🎮 Your Profile", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(14.dp))
                ProgressRing(progress = xpIn.toFloat() / xpNeed, size = 110, strokeWidth = 12f,
                    color = Purple, label = "Lv.$level")
                Spacer(Modifier.height(6.dp))
                Text("${profile.xp} total XP", fontSize = 13.sp, color = Color.Gray)
                Text("$xpIn / $xpNeed XP to Level ${level + 1}", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(20.dp))
                Text("🏅 Badges", fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Start))
                Spacer(Modifier.height(8.dp))
                if (profile.badges.isEmpty())
                    Text("No badges yet. Keep completing habits!", fontSize = 13.sp, color = Color.Gray)
                else profile.badges.forEach { badge ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        colors = CardDefaults.cardColors(containerColor = Purple.copy(alpha = 0.1f))) {
                        Text(badge, modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onDismiss, Modifier.align(Alignment.End)) { Text("Close") }
            }
        }
    }
}

// ─── Add Habit Dialog ─────────────────────────────────────────────────────────
@Composable
fun AddHabitDialog(onAdd: (String, Int) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var selectedCat by remember { mutableStateOf(0) }
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.padding(22.dp)) {
                Text("Add New Habit", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("Habit name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
                Spacer(Modifier.height(14.dp))
                Text("Category", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                // Category grid
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CATEGORIES.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { cat ->
                                val idx = CATEGORIES.indexOf(cat)
                                val selected = selectedCat == idx
                                FilterChip(
                                    selected = selected,
                                    onClick = { selectedCat = idx },
                                    label = { Text("${cat.emoji} ${cat.name}", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = cat.color.copy(alpha = 0.2f),
                                        selectedLabelColor = cat.color
                                    )
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(onClick = { if (name.isNotBlank()) { onAdd(name.trim(), selectedCat); onDismiss() } },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = CATEGORIES[selectedCat].color)) {
                        Text("Add")
                    }
                }
            }
        }
    }
}

// ─── Notification Dialog ──────────────────────────────────────────────────────
@Composable
fun NotificationDialog(context: Context, onDismiss: () -> Unit) {
    var hour by remember { mutableStateOf(8) }
    var minute by remember { mutableStateOf(0) }
    var scheduled by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🔔 Daily Reminder", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.height(14.dp))
                if (!scheduled) {
                    Text("Pick a daily time to be reminded.", textAlign = TextAlign.Center,
                        color = Color.Gray, fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = hour.toString().padStart(2,'0'),
                            onValueChange = { hour = it.toIntOrNull()?.coerceIn(0,23) ?: hour },
                            label = { Text("Hour") }, singleLine = true, modifier = Modifier.width(88.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        Text(":", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        OutlinedTextField(value = minute.toString().padStart(2,'0'),
                            onValueChange = { minute = it.toIntOrNull()?.coerceIn(0,59) ?: minute },
                            label = { Text("Min") }, singleLine = true, modifier = Modifier.width(88.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { scheduleReminder(context, hour, minute); scheduled = true },
                        modifier = Modifier.fillMaxWidth()) { Text("Set Reminder") }
                } else {
                    Text("✅ Reminder set for ${hour.toString().padStart(2,'0')}:${minute.toString().padStart(2,'0')} daily!",
                        textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onDismiss, Modifier.align(Alignment.End)) { Text("Cancel") }
            }
        }
    }
}

// ─── Settings Screen ──────────────────────────────────────────────────────────
@Composable
fun SettingsScreen(
    profile: UserProfile,
    isDarkMode: Boolean,
    onDarkModeToggle: (Boolean) -> Unit,
    onShowProfile: () -> Unit,
    onShowNotif: () -> Unit
) {
    val level = levelFromXp(profile.xp)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text("⚙️ Settings", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(bottom = 4.dp))
        }

        // Profile Section
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = onShowProfile
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(50.dp).clip(CircleShape).background(Purple),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🎮", fontSize = 24.sp)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("User Profile", fontWeight = FontWeight.Bold)
                        Text("Level $level • ${profile.xp} XP", fontSize = 12.sp, color = Color.Gray)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }

        // Appearance Section
        item {
            Text("Appearance", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🌙 Dark Mode", modifier = Modifier.weight(1f))
                    Switch(checked = isDarkMode, onCheckedChange = onDarkModeToggle)
                }
            }
        }

        // Notifications Section
        item {
            Text("General", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = onShowNotif
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔔 Daily Reminder", modifier = Modifier.weight(1f))
                    Icon(Icons.Default.Notifications, contentDescription = null, tint = Purple)
                }
            }
        }

        item {
            Spacer(Modifier.height(80.dp))
        }
    }
}

// ─── Main App ─────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitFlowApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs   = remember { context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) }
    val scope   = rememberCoroutineScope()

    // Theme state
    var isDarkMode by remember {
        mutableStateOf(prefs.getBoolean("dark_mode", false))
    }

    MyApplicationTheme(darkTheme = isDarkMode) {
        // Onboarding
        var showOnboarding by remember {
            mutableStateOf(!prefs.getBoolean("onboarding_done", false))
        }

        var habits  by remember { mutableStateOf(loadHabits(prefs)) }
        var profile by remember { mutableStateOf(loadProfile(prefs)) }

        // Nav: 0=Habits, 1=Stats, 2=Settings
        var navIndex by remember { mutableStateOf(0) }

        // Dialog states
        var showAddDialog     by remember { mutableStateOf(false) }
        var timerIndex        by remember { mutableStateOf<Int?>(null) }
        var heatmapIndex      by remember { mutableStateOf<Int?>(null) }
        var showNotifDialog   by remember { mutableStateOf(false) }
        var showProfileDialog by remember { mutableStateOf(false) }
        var filterCategory    by remember { mutableStateOf<Int?>(null) }

        val today = todayStr()

        fun refreshProfileAndSync(newHabits: List<Habit>) {
            habits = newHabits
            saveHabits(prefs, habits)
            val completedCount = habits.count { it.completedDates.contains(today) }
            val newXp = completedCount * 10
            val newBadges = profile.badges.toMutableList()
            habits.forEach { h ->
                val s = calculateStreak(h)
                if (s >= 7   && "🔥 Week Warrior"   !in newBadges) newBadges.add("🔥 Week Warrior")
                if (s >= 30  && "🌙 Month Master"    !in newBadges) newBadges.add("🌙 Month Master")
                if (s >= 100 && "💎 Century Club"    !in newBadges) newBadges.add("💎 Century Club")
            }
            if (habits.size >= 5 && "📋 Habit Builder" !in newBadges) newBadges.add("📋 Habit Builder")
            profile = profile.copy(xp = newXp, level = levelFromXp(newXp), badges = newBadges)
            saveProfile(prefs, profile)
            scope.launch { syncToFirebase(habits, profile) }
        }

        // Onboarding gate
        if (showOnboarding) {
            OnboardingScreen(onFinish = {
                prefs.edit().putBoolean("onboarding_done", true).apply()
                showOnboarding = false
            })
        } else {
            // Dialogs
            timerIndex?.let { if (it in habits.indices) TimerDialog(habits[it].name) { timerIndex = null } }
            heatmapIndex?.let { if (it in habits.indices) HeatmapDialog(habits[it], today) { heatmapIndex = null } }
            if (showNotifDialog)   NotificationDialog(context)   { showNotifDialog = false }
            if (showProfileDialog) ProfileDialog(profile)         { showProfileDialog = false }
            if (showAddDialog)     AddHabitDialog(
                onAdd = { name, catIdx ->
                    refreshProfileAndSync(habits + Habit(name = name, categoryIndex = catIdx))
                }, onDismiss = { showAddDialog = false }
            )

            Scaffold(
                topBar = {
                    CenterAlignedTopAppBar(
                        title = {
                            Text("🌱 HabitFlow", fontFamily = null,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                        },
                        actions = {
                            IconButton(onClick = { showProfileDialog = true }) {
                                Text("🎮", fontSize = 18.sp)
                            }
                            IconButton(onClick = { showNotifDialog = true }) {
                                Icon(Icons.Default.Notifications, contentDescription = "Reminder")
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                },
                floatingActionButton = {
                    if (navIndex == 0) {
                        FloatingActionButton(
                            onClick = { showAddDialog = true },
                            containerColor = Purple, contentColor = Color.White,
                            shape = RoundedCornerShape(16.dp)
                        ) { Icon(Icons.Default.Add, contentDescription = "Add") }
                    }
                },
                bottomBar = {
                    NavigationBar {
                        listOf("Habits" to "🏠", "Stats" to "📊", "Settings" to "⚙️").forEachIndexed { i, (label, icon) ->
                            NavigationBarItem(
                                selected = navIndex == i,
                                onClick  = { navIndex = i },
                                icon     = { Text(icon, fontSize = 20.sp) },
                                label    = { Text(label) }
                            )
                        }
                    }
                }
            ) { padding ->
                when (navIndex) {
                    // ── Habits tab ──────────────────────────────────────────────────
                    0 -> {
                        val filtered = if (filterCategory == null) habits
                        else habits.filter { it.categoryIndex == filterCategory }

                        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

                            // XP Banner
                            val level  = levelFromXp(profile.xp)
                            val xpIn   = xpInLevel(profile.xp)
                            val xpNeed = xpForNextLevel(level)
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                colors   = CardDefaults.cardColors(containerColor = Purple.copy(alpha = 0.12f)),
                                shape    = RoundedCornerShape(14.dp),
                                onClick  = { showProfileDialog = true }
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("🎮 Lv.$level", fontWeight = FontWeight.Bold,
                                        color = Purple, modifier = Modifier.width(68.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        LinearProgressIndicator(
                                            progress = { xpIn.toFloat() / xpNeed },
                                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                            color = Purple
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text("$xpIn / $xpNeed XP", fontSize = 10.sp, color = Color.Gray)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text("${profile.badges.size} 🏅", fontSize = 12.sp, color = Color.Gray)
                                }
                            }

                            // Summary
                            val done = habits.count { it.completedDates.contains(today) }
                            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                shape = RoundedCornerShape(12.dp),
                                elevation = CardDefaults.cardElevation(1.dp)) {
                                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("$done / ${habits.size} completed today",
                                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    LinearProgressIndicator(
                                        progress = { if (habits.isEmpty()) 0f else done.toFloat() / habits.size },
                                        modifier = Modifier.width(80.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                                        color = Emerald
                                    )
                                }
                            }

                            // Category filter chips
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterChip(selected = filterCategory == null, onClick = { filterCategory = null },
                                    label = { Text("All", fontSize = 12.sp) })
                                CATEGORIES.forEachIndexed { i, cat ->
                                    FilterChip(
                                        selected = filterCategory == i, onClick = { filterCategory = if (filterCategory == i) null else i },
                                        label = { Text("${cat.emoji} ${cat.name}", fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = cat.color.copy(alpha = 0.2f),
                                            selectedLabelColor = cat.color
                                        )
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))

                            if (habits.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("◎", fontSize = 48.sp, color = Color.Gray.copy(alpha = 0.3f))
                                        Spacer(Modifier.height(12.dp))
                                        Text("No habits yet.\nTap + to add your first habit.",
                                            textAlign = TextAlign.Center,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                    }
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    itemsIndexed(filtered) { index, habit ->
                                        val realIndex = habits.indexOf(habit)
                                        HabitCard(
                                            habit    = habit,
                                            today    = today,
                                            onToggle = {
                                                val updated = habits.toMutableList()
                                                val h = updated[realIndex].copy(
                                                    completedDates = updated[realIndex].completedDates.toMutableList())
                                                if (h.completedDates.contains(today)) h.completedDates.remove(today)
                                                else h.completedDates.add(today)
                                                updated[realIndex] = h
                                                refreshProfileAndSync(updated)
                                            },
                                            onDelete = {
                                                refreshProfileAndSync(habits.toMutableList().apply { removeAt(realIndex) })
                                            },
                                            onTimer   = { timerIndex = realIndex },
                                            onHeatmap = { heatmapIndex = realIndex }
                                        )
                                    }
                                    item { Spacer(Modifier.height(80.dp)) }
                                }
                            }
                        }
                    }
                    1 -> Box(Modifier.padding(padding)) { StatsScreen(habits) }
                    2 -> Box(Modifier.padding(padding)) {
                        SettingsScreen(
                            profile = profile,
                            isDarkMode = isDarkMode,
                            onDarkModeToggle = {
                                isDarkMode = it
                                prefs.edit().putBoolean("dark_mode", it).apply()
                            },
                            onShowProfile = { showProfileDialog = true },
                            onShowNotif = { showNotifDialog = true }
                        )
                    }
                }
            }
        }
    }
}
