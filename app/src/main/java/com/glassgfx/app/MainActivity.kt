package com.glassgfx.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

// ---------- Palet ----------
val Ink = Color(0xFF070B14)
val Cyan = Color(0xFF2EC4FF)
val Violet = Color(0xFF7A5CFF)
val Rose = Color(0xFFFF6FA8)
val Dim = Color(0x9EFFFFFF)

// ---------- Logika grafik ----------
enum class Tier(val title: String, val desc: String, val flags: List<String>) {
    Asli("Asli", "Grafik bawaan game, tanpa perubahan", emptyList()),
    Ringan("Ringan", "Tanpa anisotropik, tekstur lebih lembut dan enteng",
        listOf("disableAnisotropicFiltering", "forceTextureLodOffset4")),
    Retro("Retro", "Ringan, ditambah detail jauh yang berganti berundak",
        listOf("disableAnisotropicFiltering", "forceTextureLodOffset4", "forceNearestMipFiltering")),
    Kotak("Kotak", "Tekstur berpetak ala blok",
        listOf("disableAnisotropicFiltering", "forceTextureLodOffset4", "forceNearestMipFiltering", "forceNearestFiltering"))
}

fun buildCmd(pkg: String, tier: Tier, sharp: Boolean, strict: Boolean): String {
    val f = (tier.flags + listOfNotNull(
        if (sharp) "forceNearestFiltering" else null,
        if (strict) "forceNearestMipFiltering" else null
    )).distinct()
    return if (f.isEmpty())
        "settings delete global angle_gl_driver_selection_values; settings delete global angle_gl_driver_selection_pkgs; " +
            "setprop debug.angle.feature_overrides_enabled \"\"; am force-stop $pkg"
    else
        "settings put global angle_gl_driver_selection_pkgs $pkg && settings put global angle_gl_driver_selection_values angle && " +
            "setprop debug.angle.feature_overrides_enabled ${f.joinToString(":")} && am force-stop $pkg"
}

object Sh {
    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).also { it.isAccessible = true }
    }

    fun run(cmd: String): Pair<Int, String> = try {
        val p = newProcess.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor() to (out + err).trim()
    } catch (e: Throwable) {
        -1 to (e.cause?.message ?: e.message ?: "gagal menjalankan perintah")
    }
}

/** 0 = Shizuku belum jalan, 1 = belum diberi izin, 2 = siap */
fun shizukuState(): Int = try {
    if (!Shizuku.pingBinder()) 0
    else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) 2 else 1
} catch (e: Throwable) { 0 }

class Cfg(sp: SharedPreferences) {
    var pkg by mutableStateOf(sp.getString("pkg", "com.dts.freefiremax")!!)
    var tier by mutableStateOf(runCatching { Tier.valueOf(sp.getString("tier", "Asli")!!) }.getOrDefault(Tier.Asli))
    var sharp by mutableStateOf(sp.getBoolean("sharp", false))
    var strict by mutableStateOf(sp.getBoolean("strict", false))
    var sz by mutableIntStateOf(0)
    var busy by mutableStateOf(false)
    var log by mutableStateOf("")
    var angle by mutableStateOf("")
    fun save(sp: SharedPreferences) = sp.edit()
        .putString("pkg", pkg).putString("tier", tier.name)
        .putBoolean("sharp", sharp).putBoolean("strict", strict).apply()
}

class AppItem(val label: String, val pkg: String, val icon: ImageBitmap)

fun loadApps(c: Context): List<AppItem> {
    val pm = c.packageManager
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(i, 0)
        .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm).toBitmap(112, 112).asImageBitmap()) }
        .filter { it.pkg != c.packageName }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

// ---------- Bahan kaca ----------
fun Modifier.glass(shape: Shape, k: Float = 1f): Modifier = this
    .clip(shape)
    .drawBehind {
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(.20f * k), Color.White.copy(.06f * k))))
        drawRect(
            Brush.radialGradient(
                listOf(Color.White.copy(.22f * k), Color.Transparent),
                Offset(size.width * .15f, 0f), size.width * .8f
            )
        )
    }
    .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(.7f), Color.White.copy(.08f), Color.White.copy(.35f))), shape)

@Composable
fun Modifier.press(onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (down) .96f else 1f, spring(dampingRatio = .55f, stiffness = 500f), label = "press")
    return this.scale(s).clickable(interactionSource = src, indication = null, onClick = onClick)
}

@Composable
fun Backdrop() {
    val t = rememberInfiniteTransition(label = "bg")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Canvas(Modifier.fillMaxSize().background(Ink)) {
        val w = size.width
        val h = size.height
        fun blob(c: Color, x: Float, y: Float, r: Float) =
            drawCircle(Brush.radialGradient(listOf(c.copy(.55f), Color.Transparent), Offset(x, y), r), r, Offset(x, y))
        blob(Cyan, w * (.15f + .35f * a), h * (.18f + .10f * a), w * .75f)
        blob(Violet, w * (.90f - .40f * a), h * (.50f + .10f * a), w * .85f)
        blob(Rose, w * (.20f + .20f * a), h * (.92f - .20f * a), w * .70f)
    }
}

@Composable
fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(26.dp)).padding(18.dp), content = content)
}

@Composable
fun GlassButton(text: String, enabled: Boolean = true, click: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().press { if (enabled) click() }.alpha(if (enabled) 1f else .45f)
            .glass(CircleShape, 1.6f).background(Cyan.copy(.35f)).padding(vertical = 18.dp),
        Alignment.Center
    ) { Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
}

// ---------- Layar ----------
@Composable
fun Welcome(onDone: () -> Unit) {
    var go by remember { mutableStateOf(false) }
    val p by animateFloatAsState(if (go) 1f else 0f, tween(1400, easing = FastOutSlowInEasing), label = "w")
    LaunchedEffect(Unit) { go = true; delay(2600); onDone() }
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(
            Modifier.graphicsLayer { alpha = p; scaleX = .92f + .08f * p; scaleY = .92f + .08f * p }
                .glass(RoundedCornerShape(40.dp), 1.3f).padding(horizontal = 40.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("WELCOME", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Thin, letterSpacing = (12f - 6f * p).sp)
            Text("Atur grafik game dengan ANGLE", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
fun AppCell(a: AppItem, on: Boolean, click: () -> Unit) {
    Column(Modifier.press(click), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).then(if (on) Modifier.glass(RoundedCornerShape(20.dp), 1.6f) else Modifier), Alignment.Center) {
            Image(a.icon, a.label, Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)))
        }
        Text(
            a.label, color = if (on) Color.White else Dim, fontSize = 11.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
fun Home(apps: List<AppItem>, sel: String, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = remember(apps, q) { apps.filter { it.label.contains(q, ignoreCase = true) } }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        Text("Pilih game atau aplikasi", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 24.dp))
        Text("Setelan grafik berlaku untuk pilihanmu", color = Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        Row(Modifier.fillMaxWidth().glass(CircleShape).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = Dim, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Box {
                if (q.isEmpty()) Text("Cari aplikasi", color = Dim, fontSize = 16.sp)
                BasicTextField(q, { q = it }, singleLine = true, cursorBrush = SolidColor(Color.White),
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp), modifier = Modifier.fillMaxWidth())
            }
        }
        if (list.isEmpty()) Text("Tidak ada aplikasi ditemukan", color = Dim, modifier = Modifier.padding(top = 32.dp))
        LazyVerticalGrid(
            GridCells.Fixed(4), contentPadding = PaddingValues(top = 18.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) { items(list, key = { it.pkg }) { a -> AppCell(a, a.pkg == sel) { onPick(a.pkg) } } }
    }
}

@Composable
fun TierRow(t: Tier, on: Boolean, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().press(click).glass(RoundedCornerShape(22.dp), if (on) 1.7f else .7f).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(t.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text(t.desc, color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Box(
            Modifier.size(22.dp).border(1.5.dp, Color.White.copy(if (on) 1f else .4f), CircleShape).padding(5.dp)
                .then(if (on) Modifier.background(Color.White, CircleShape) else Modifier)
        )
    }
}

@Composable
fun Toggle(title: String, sub: String, on: Boolean, set: (Boolean) -> Unit) {
    val x by animateDpAsState(if (on) 22.dp else 2.dp, spring(dampingRatio = .6f, stiffness = 500f), label = "knob")
    Row(
        Modifier.fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { set(!on) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = Dim, fontSize = 13.sp)
        }
        Box(
            Modifier.size(52.dp, 30.dp).glass(CircleShape).background(if (on) Cyan.copy(.5f) else Color.Transparent),
            contentAlignment = Alignment.CenterStart
        ) { Box(Modifier.offset(x).size(26.dp).background(Color.White, CircleShape)) }
    }
}

@Composable
fun Settings(c: Cfg, appName: String, ask: () -> Unit, open: () -> Unit, apply: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(top = 24.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Grafik", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Light)
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dot = when (c.sz) { 2 -> Color(0xFF5BE49B); 1 -> Color(0xFFFFC857); else -> Color(0xFFFF6B81) }
                Box(Modifier.size(10.dp).background(dot, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(
                    when (c.sz) { 2 -> "Shizuku siap"; 1 -> "Shizuku butuh izin"; else -> "Shizuku belum berjalan" },
                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium
                )
            }
            if (c.sz == 2 && c.angle.isNotEmpty()) Text(c.angle, color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            if (c.sz != 2) {
                Spacer(Modifier.height(14.dp))
                GlassButton(if (c.sz == 1) "Beri izin" else "Buka Shizuku") { if (c.sz == 1) ask() else open() }
            }
        }
        Panel {
            Text("Target", color = Dim, fontSize = 13.sp)
            Text(appName, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(c.pkg, color = Dim, fontSize = 12.sp)
        }
        Text("Tahap grafik", color = Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Tier.values().forEach { t -> TierRow(t, t == c.tier) { c.tier = t } }
        Panel {
            Toggle("Piksel tajam", "Tekstur tanpa penghalusan", c.sharp) { c.sharp = it }
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(Color.White.copy(.12f)))
            Toggle("Batas jarak tegas", "Detail jauh berganti mendadak", c.strict) { c.strict = it }
        }
        GlassButton(if (c.busy) "Menerapkan..." else "Terapkan", enabled = c.sz == 2 && !c.busy, click = apply)
        if (c.log.isNotEmpty()) Text(c.log, color = Dim, fontSize = 13.sp)
    }
}

@Composable
fun TabItem(icon: ImageVector, label: String, on: Boolean, click: () -> Unit) {
    Row(
        Modifier.press(click).then(if (on) Modifier.glass(CircleShape, 1.8f) else Modifier)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color.White.copy(if (on) 1f else .6f), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White.copy(if (on) 1f else .6f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("glassgfx", Context.MODE_PRIVATE) }
    val c = remember { Cfg(sp) }
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf(emptyList<AppItem>()) }

    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadApps(ctx) } }
    LaunchedEffect(c.pkg, c.tier, c.sharp, c.strict) { c.save(sp) }
    LaunchedEffect(c.sz) {
        if (c.sz == 2) {
            val r = withContext(Dispatchers.IO) { Sh.run("ls -d /system/*/*angle* /vendor/*/*angle* 2>/dev/null").second }
            c.angle = if (r.isBlank()) "Pustaka ANGLE tidak ditemukan di sistem, efek mungkin tidak muncul" else "ANGLE tersedia di sistem"
        }
    }
    DisposableEffect(Unit) {
        val a = Shizuku.OnBinderReceivedListener { c.sz = shizukuState() }
        val b = Shizuku.OnBinderDeadListener { c.sz = 0 }
        val r = Shizuku.OnRequestPermissionResultListener { _, _ -> c.sz = shizukuState() }
        Shizuku.addBinderReceivedListenerSticky(a)
        Shizuku.addBinderDeadListener(b)
        Shizuku.addRequestPermissionResultListener(r)
        c.sz = shizukuState()
        onDispose {
            Shizuku.removeBinderReceivedListener(a)
            Shizuku.removeBinderDeadListener(b)
            Shizuku.removeRequestPermissionResultListener(r)
        }
    }

    val appName = apps.firstOrNull { it.pkg == c.pkg }?.label ?: c.pkg
    val ask: () -> Unit = { try { Shizuku.requestPermission(100) } catch (e: Throwable) { c.log = "Gagal meminta izin: ${e.message}" } }
    val open: () -> Unit = {
        val i = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (i != null) ctx.startActivity(i) else c.log = "Shizuku belum terpasang. Pasang dulu dari GitHub atau Play Store."
    }
    val apply: () -> Unit = {
        c.busy = true
        c.log = ""
        scope.launch {
            val (code, out) = withContext(Dispatchers.IO) { Sh.run(buildCmd(c.pkg, c.tier, c.sharp, c.strict)) }
            c.busy = false
            c.log = if (code == 0) "Diterapkan. $appName dihentikan, buka lagi untuk melihat hasilnya." else "Gagal ($code): $out"
        }
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop()
        Crossfade(targetState = screen, animationSpec = tween(450), label = "screen") { s ->
            when (s) {
                0 -> Welcome { screen = 1 }
                1 -> Home(apps, c.pkg) { c.pkg = it }
                else -> Settings(c, appName, ask, open, apply)
            }
        }
        if (screen != 0) {
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp)
                    .clip(CircleShape).background(Ink.copy(.55f)).glass(CircleShape, 1.3f).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TabItem(Icons.Rounded.Home, "Pilih", screen == 1) { screen = 1 }
                TabItem(Icons.Rounded.Settings, "Setelan", screen == 2) { screen = 2 }
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(t), navigationBarStyle = SystemBarStyle.dark(t))
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App() } }
    }
}
