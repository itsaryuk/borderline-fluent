package com.lampensoft.borderlinefluent

import android.app.LocaleManager
import android.os.LocaleList
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private object C {
    val bg = Color(0xFF0A111C)
    val card = Color(0xFF121B2A)
    val line = Color(0xFF25344C)
    val blue = Color(0xFF1E4A7C)
    val blueEdge = Color(0xFF4A86CC)
    val mic = Color(0xFF2F7DF0)
    val text = Color(0xFFE8ECF2)
    val dim = Color(0xFF94A0B4)
    val red = Color(0xFFD9443A)
    val green = Color(0xFF3FB56E)
    val amber = Color(0xFFFFC14D)
    val paper = Color(0xFFE9E6DF)
    val ink = Color(0xFF1C2230)
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val Oswald = FontFamily(
    Font(R.font.oswald, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.oswald, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private fun display(size: Int, spacing: Double = 2.0) =
    TextStyle(fontFamily = Oswald, fontWeight = FontWeight.Bold, fontSize = size.sp, letterSpacing = spacing.sp, color = C.text)

// ---------- art ----------

/** Shared art ships in the app; officers and destinations come from the installed language packs. */
private fun asset(ctx: Context, name: String): android.graphics.Bitmap? =
    runCatching { ctx.assets.open("art/$name.webp").use(BitmapFactory::decodeStream) }.getOrNull()
        ?: Packs.art(ctx)[name]?.let { BitmapFactory.decodeFile(it.path) }

/** Slices a cols x rows sheet into tiles (officer expressions 4x2, traveler photos 3x2). Empty if the art is missing. */
private fun tiles(ctx: Context, name: String, cols: Int, rows: Int): List<ImageBitmap> {
    val b = asset(ctx, name) ?: return emptyList()
    val w = b.width / cols; val h = b.height / rows
    return (0 until rows * cols).map { i ->
        android.graphics.Bitmap.createBitmap(b, (i % cols) * w, (i / cols) * h, w, h).asImageBitmap()
    }
}

@Composable private fun rememberAsset(name: String): ImageBitmap? {
    val ctx = LocalContext.current
    return remember(name) { asset(ctx, name)?.asImageBitmap() }
}

@Composable private fun Backdrop(name: String, tint: Color = C.bg, fallback: String? = null, content: @Composable BoxScope.() -> Unit) {
    val img = rememberAsset(name) ?: fallback?.let { rememberAsset(it) }
    Box(Modifier.fillMaxSize().background(C.bg)) {
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to tint.copy(alpha = 0.35f), 0.45f to tint.copy(alpha = 0.55f), 1f to tint.copy(alpha = 0.97f))))
        content()
    }
}

// ---------- building blocks ----------

/** The interface language, for country and language names inside UI strings. */
@Composable private fun uiLang() = uiLanguage(LocalContext.current)
/** The rank name, with the "Elite" title once the agent has crashed an officer. */
@Composable private fun rankName(c: Career) = stringResource(c.rank).let { if (c.broken > 0) stringResource(R.string.rank_elite, it) else it }

/** The country's flag emoji, sized from the box height so the glyph fills the box. */
@OptIn(ExperimentalTextApi::class)
@Composable private fun Flag(c: Country, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val size = with(LocalDensity.current) { maxHeight.toSp() }
        Text(c.emoji, fontSize = size, lineHeight = size, maxLines = 1, softWrap = false,
            style = LocalTextStyle.current.copy(platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}

@Composable private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (enabled) C.blueEdge else C.line),
        colors = ButtonDefaults.buttonColors(containerColor = C.blue, disabledContainerColor = C.card,
            contentColor = C.text, disabledContentColor = C.dim),
    ) { Text(text, style = display(17, 1.5)) }
}

@Composable private fun Panel(modifier: Modifier = Modifier, border: Color = C.line, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.card.copy(alpha = 0.92f))
        .border(1.dp, border, RoundedCornerShape(14.dp)).padding(16.dp), content = content)
}

@Composable private fun Gutter(scrollable: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val gutter = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 16.dp)
    if (scrollable) BoxWithConstraints(gutter) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight), content = content)
    } else Column(gutter, content = content)
}

// ---------- screens ----------

@Composable
fun GameApp(g: Game) {
    MaterialTheme(colorScheme = darkColorScheme(background = C.bg, surface = C.card, primary = C.mic)) {
        LaunchedEffect(g.screen, g.inBackRoom) { g.soundtrack() }
        Surface(color = C.bg) {
            Column(Modifier.fillMaxSize()) {
                val saveView = savePresentation(g.careerState, g.careerSaving)
                if (saveView == SavePresentation.READ_ERROR || saveView == SavePresentation.PREPARATION_ERROR) {
                    Panel(Modifier.safeDrawingPadding().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        Text(stringResource(if (saveView == SavePresentation.PREPARATION_ERROR)
                            R.string.replay_preparation_unavailable else R.string.replay_read_failed), color = C.text)
                        if (saveView == SavePresentation.PREPARATION_ERROR)
                            PrimaryButton(stringResource(R.string.begin_mission)) { g.beginMission() }
                    }
                }
                if (g.screen == Screen.COVER && g.preparationUnavailable)
                    Column(Modifier.safeDrawingPadding().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) { RecoveryPanel(g) }
                Box(Modifier.weight(1f)) {
                    when (g.screen) {
                        Screen.UNSUPPORTED -> UnsupportedScreen(g)
                        Screen.INTRO -> IntroScreen(g)
                        Screen.CAMERA_TEST -> CameraTestScreen(g)
                        Screen.FINALE -> FinaleScreen(g)
                        Screen.LANGUAGES -> LanguagesScreen(g)
                        Screen.DOWNLOAD -> DownloadScreen(g)
                        // First run: no Back arrow (system Back follows the setup policy, MainActivity); from Settings: back there.
                        Screen.PASSPORT -> PassportScreen(g.faceWatch, g.cameraOn, onReading = g::setCamera, onDone = g::passportFinished,
                            onBack = if (g.passportFrom == Screen.SETTINGS) ({ g.go(Screen.SETTINGS) }) else null)
                        Screen.INPUT_MODE -> InputModeScreen(g)
                        Screen.START -> StartScreen(g)
                        Screen.DESTINATION -> DestinationScreen(g)
                        Screen.COVER -> CoverScreen(g)
                        Screen.INTERVIEW -> InterviewScreen(g)
                        Screen.RESULT -> ResultScreen(g)
                        Screen.SUMMARY -> SummaryScreen(g)
                        Screen.SETTINGS -> SettingsScreen(g)
                        Screen.LICENSES -> LicensesScreen(g)
                    }
                }
            }
            CareerSaveBlocker(g)
        }
    }
}

// Before the finale the machines are the point; after it, the player is one of the resistance (owner, 2026-10-04).

@Composable private fun StartScreen(g: Game) = Backdrop("start_bg") {
    // Every few seconds the tagline flickers: scrambled, or for a blink the truth.
    val base = stringResource(if (g.finaleSeen) R.string.tagline_after else R.string.tagline)
    val truth = stringResource(R.string.tagline_truth)
    var tagline by remember(base) { mutableStateOf(base) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(kotlin.random.Random.nextLong(3000, 7000))
            tagline = if (kotlin.random.Random.nextInt(4) == 0) truth else corrupt(base)
            delay(kotlin.random.Random.nextLong(90, 220))
            tagline = base
        }
    }
    Box(Modifier.fillMaxSize().scanlines())
    var picking by remember { mutableStateOf(false) }
    Gutter(scrollable = g.finaleSeen || g.interruptedAttempt != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            PassportButton(g) { picking = true }
            // Owner: the dim gear was hard to find over the menu art; full white on a glass disc.
            IconButton({ g.screen = Screen.SETTINGS }, Modifier.clip(CircleShape).background(Color(0x99000000))) {
                Icon(Icons.Default.Settings, stringResource(R.string.settings_cd), tint = C.text, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.title_two_line), style = display(54, 4.0), textAlign = TextAlign.Center, lineHeight = 64.sp,
            modifier = Modifier.fillMaxWidth().glitchOnBeat(g.music::position))
        Box(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 14.dp).width(150.dp).height(2.dp).background(C.red)
            .glitchOnBeat(g.music::position, strength = 3f))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            for (variant in listOf(base, truth)) {
                Text(variant, style = display(15, 3.0).copy(color = C.dim, fontWeight = FontWeight.Medium),
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().alpha(0f).clearAndSetSemantics {})
            }
            Text(tagline, style = display(15, 3.0).copy(color = if (tagline == truth) C.red else C.dim, fontWeight = FontWeight.Medium),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.weight(1f))
        VolumeWarning()
        Panel {
            Text(if (g.finaleSeen) stringResource(R.string.start_year_after, rankName(g.career))
                else stringResource(R.string.start_year), color = C.text, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(stringResource(if (g.finaleSeen) R.string.start_blurb_after else R.string.start_blurb), color = C.dim,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            Spacer(Modifier.height(16.dp))
            PrimaryButton(stringResource(if (g.finaleSeen) R.string.begin_mission else R.string.begin), enabled = g.careerReady) { g.beginMission() }
        }
        Spacer(Modifier.height(16.dp))
        RecoveryPanel(g)
        val c = g.career
        // Rank and stats only exist after the story is done (owner): before that, just the training hint.
        if (g.training || g.finaleSeen) Text(if (g.training) stringResource(R.string.start_training_hint)
            else listOfNotNull(rankName(c).uppercase(), stringResource(R.string.stat_cleared, c.cleared),
                stringResource(R.string.stat_streak, c.streak), c.toNext?.let { stringResource(R.string.stat_to_next, it) },
                c.broken.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.stat_broken, it, it) }).joinToString(" · "),
            color = C.dim, fontSize = 12.sp, letterSpacing = 1.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Feature(stringResource(R.string.feature_voice), Icons.Default.Mic, Icons.Default.Visibility)
            Feature(stringResource(R.string.feature_ai), Icons.Default.Memory)
        }
    }
    if (picking) CountryPicker(g) { picking = false }
}

/** The menu's passport: a glass disc like the gear, badged with the picked country's flag, its language code, or "?". */
@Composable private fun PassportButton(g: Game, onClick: () -> Unit) {
    val playable = g.playable()
    val d = g.destChoice(playable)
    IconButton(onClick, Modifier.clip(CircleShape).background(Color(0x99000000))) { Box(Modifier.fillMaxSize()) {
        Icon(Icons.Default.Book, stringResource(R.string.picker_cd), tint = C.text, modifier = Modifier.align(Alignment.Center).size(26.dp))
        val chosen = playable.firstOrNull { it.id == d }
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 2.dp, bottom = 4.dp)) {
            if (chosen != null) Flag(chosen, Modifier.size(width = 18.dp, height = 12.dp))
            else Text("?", color = C.text, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(C.blue).padding(horizontal = 3.dp))
        }
    } }
}

/** Where the next mission goes: random, any country of one installed language, or one country ([Game.dest]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CountryPicker(g: Game, onClose: () -> Unit) {
    val picker = destinationPicker(g.languages, Packs.art(LocalContext.current).keys, passedCountries(g.career.snapshot))
    val d = g.destChoice(picker.countries.map { it.country })
    ModalBottomSheet(onClose, containerColor = C.card) {
        @Composable fun row(id: String, label: String, lead: @Composable () -> Unit, clears: Int = 0) =
            Row(Modifier.fillMaxWidth().clickable { g.pickDest(id); onClose() }.background(if (id == d) C.blue else Color.Transparent)
                .padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 30.dp, height = 20.dp), contentAlignment = Alignment.Center) { lead() }
                Spacer(Modifier.width(14.dp))
                Text(label, color = C.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Column(Modifier.padding(start = 8.dp), horizontalAlignment = Alignment.End) {
                    if (clears > 0) Text(if (clears == 1) stringResource(R.string.picker_passed)
                        else stringResource(R.string.picker_passed_times, clears), color = C.green, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
        Text(stringResource(R.string.picker_title), style = display(18, 1.5), modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            row("random", stringResource(R.string.picker_random), { Text("?", color = C.text, fontWeight = FontWeight.Bold, fontSize = 18.sp) })
            HorizontalDivider(color = C.line)
            picker.countries.forEach { entry ->
                val c = entry.country
                row(c.id, c.uiName(uiLang()), { Flag(c, Modifier.fillMaxSize()) }, clears = passedCount(g.career.snapshot, c.id))
            }
        }
    }
}

/** Officers and music all play on STREAM_MUSIC (USAGE_MEDIA); warn when it is muted or under 25%. Polled: simplest robust option. */
@Composable private fun VolumeWarning() {
    val am = LocalContext.current.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    var pct by remember { mutableStateOf(100) }
    LaunchedEffect(Unit) {
        while (true) {
            pct = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100 / am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            delay(1000)
        }
    }
    if (pct >= 25) return
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(10.dp)).background(C.amber.copy(alpha = 0.14f))
        .border(1.dp, C.amber, RoundedCornerShape(10.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (pct == 0) Icons.Default.VolumeOff else Icons.Default.VolumeDown, null, tint = C.amber, modifier = Modifier.size(22.dp))
        Text(stringResource(if (pct == 0) R.string.volume_off else R.string.volume_low), color = C.amber, fontSize = 13.sp,
            modifier = Modifier.padding(start = 10.dp))
    }
}

/** The voice-over script: keep it identical to the recorded audio. */
private val INTRO = listOf(R.string.intro_1, R.string.intro_2, R.string.intro_3, R.string.intro_4, R.string.intro_5, R.string.intro_6, R.string.intro_7)

@Composable private fun IntroScreen(g: Game) = Box(Modifier.fillMaxSize().background(C.bg).scanlines()) {
    // The owner's voice-over, once recorded: drop it in as res/raw/intro_vo.(mp3|ogg) and it plays here.
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val id = ctx.resources.getIdentifier("intro_vo", "raw", ctx.packageName)
        val vo = if (id != 0) android.media.MediaPlayer.create(ctx, id)?.apply { start() } else null
        onDispose { vo?.release() }
    }
    Gutter {
        Text(stringResource(if (g.finaleSeen) R.string.intro_header_after else R.string.intro_header), color = C.red, fontSize = 12.sp, letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.glitch(idleMs = 2000L..5000L, strength = 2f))
        Spacer(Modifier.height(16.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            INTRO.forEachIndexed { i, p ->
                Text(stringResource(p), color = if (i == 0) C.text else C.dim, fontSize = if (i == 0) 20.sp else 16.sp,
                    fontWeight = if (i == 0) FontWeight.Medium else FontWeight.Normal, lineHeight = 23.sp,
                    modifier = Modifier.padding(bottom = 14.dp))
            }
            Text(stringResource(R.string.intro_next), style = display(24, 2.0), modifier = Modifier.padding(top = 8.dp).glitch())
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(stringResource(R.string.continue_btn)) { g.afterIntro() }
    }
}

/**
 * The end of the story: a car at the curb, the driver's implant glints, then a silent telepathic transmission
 * (each thought flies into frame, holds, drifts out), credits.
 */
@Composable private fun FinaleScreen(g: Game) = Box(Modifier.fillMaxSize().background(Color.Black)) {
    var step by remember { mutableStateOf(0) } // 1 car, 2 glint, 3 thoughts, 4 credits
    var thought by remember { mutableStateOf(-1) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(1200); step = 1; delay(3500); step = 2; delay(3800); step = 3
        for (i in THOUGHTS.indices) { thought = i; shown = true; delay(2900); shown = false; delay(700) }
        step = 4
    }
    val car = rememberAsset("finale_car")
    val fade by androidx.compose.animation.core.animateFloatAsState(if (step >= 1) 1f else 0f, tween(2500), label = "car")
    if (car != null) Image(car, null, Modifier.fillMaxSize().alpha(fade * if (step >= 4) 0.35f else 1f), contentScale = ContentScale.Crop)
    if (car != null && step in 2..3) Glint(car.width, car.height, EYE)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xEE000000))))
    Column(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(24.dp)) {
        if (step in 1..3) Text(stringResource(R.string.finale_car), color = C.dim, fontSize = 16.sp)
        if (step in 2..3) Text(stringResource(R.string.finale_glint), color = C.dim, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
    }
    if (step == 3 && thought >= 0) Thought(stringResource(THOUGHTS[thought]), shown)
    var replay by remember { mutableStateOf(false) }
    if (step >= 4 && !replay) Credits(Modifier.fillMaxSize()) { replay = true }
    if (replay) Box(Modifier.fillMaxSize().background(Color(0xEE000000)).safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.finale_bulletin), color = C.red, fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold, modifier = Modifier.glitch())
            Text(stringResource(R.string.finale_bulletin_text), style = display(22, 1.5), textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 16.dp))
            Text(stringResource(R.string.finale_play_again), color = C.dim, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            PrimaryButton(stringResource(R.string.begin_mission), enabled = g.careerReady) { g.beginMission() }
            TextButton({ g.go(Screen.START) }) { Text(stringResource(R.string.main_menu), color = C.dim) }
        }
    }
}

private val THOUGHTS = listOf(R.string.finale_t1, R.string.finale_t2, R.string.finale_t3, R.string.finale_t3b, R.string.finale_t3c, R.string.finale_t4, R.string.finale_t5)

/** One telepathic line: flies in from a slight offset and scale, glitching, and drifts out when [visible] ends. */
@Composable private fun BoxScope.Thought(text: String, visible: Boolean) {
    val t by androidx.compose.animation.core.animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 900 else 700), label = "thought")
    Text(text, style = display(28, 1.5).copy(color = Hud.cyan), textAlign = TextAlign.Center,
        modifier = Modifier.align(androidx.compose.ui.BiasAlignment(0f, 0.45f)).padding(horizontal = 32.dp).glitch(idleMs = 800L..2000L)
            .graphicsLayer { alpha = t; scaleX = 0.9f + 0.1f * t; scaleY = scaleX; translationY = (1f - t) * (if (visible) 40.dp else -40.dp).toPx() })
}

/** The driver's left eye in finale_car (fraction of the image), where the implant catches the light. */
private val EYE = androidx.compose.ui.geometry.Offset(0.476f, 0.254f)

/** A cyan lens glint at [at] (a fraction of a [w]x[h] image drawn with ContentScale.Crop). */
@Composable private fun Glint(w: Int, h: Int, at: androidx.compose.ui.geometry.Offset) {
    val pulse by rememberInfiniteTransition(label = "glint").animateFloat(0f, 1f,
        infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "glint")
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val scale = maxOf(size.width / w, size.height / h)
        val c = androidx.compose.ui.geometry.Offset((size.width - w * scale) / 2 + at.x * w * scale, (size.height - h * scale) / 2 + at.y * h * scale)
        val r = 5.dp.toPx() + 4.dp.toPx() * pulse
        drawCircle(Brush.radialGradient(listOf(Hud.cyan.copy(alpha = 0.9f * pulse), Color.Transparent), c, r * 3), r * 3, c)
        val arm = r * 2.2f // a four-point star, like a lens flare
        drawLine(Color.White.copy(alpha = 0.8f * pulse), c.copy(x = c.x - arm), c.copy(x = c.x + arm), 1.5.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.8f * pulse), c.copy(y = c.y - arm), c.copy(y = c.y + arm), 1.5.dp.toPx())
    }
}

/** Label/value string resources; 0 = none. A zero label with a value is a paragraph, both zero a gap. */
private val CREDITS = listOf(
    R.string.credits_title to 0, 0 to 0,
    0 to R.string.credits_blurb, 0 to 0,
    R.string.credits_author to R.string.credits_creator_name,
    R.string.credits_developer to R.string.credits_studio_name,
    R.string.credits_publisher to R.string.credits_studio_name, 0 to 0,
    R.string.credits_testers to R.string.credits_testers_names, 0 to 0,
    R.string.credits_behavior to R.string.credits_behavior_text, 0 to 0,
    R.string.credits_tn to R.string.credits_tn_text, 0 to 0,
    R.string.credits_built_with to R.string.credits_built_with_list, 0 to 0,
    R.string.credits_music to R.string.credits_music_artist, R.string.credits_song_label to R.string.credits_song, 0 to 0,
    R.string.credits_images to R.string.credits_images_text, 0 to 0,
    R.string.credits_android to R.string.credits_android_text, 0 to 0,
    0 to R.string.credits_fiction, 0 to 0,
    R.string.credits_thanks to R.string.credits_thanks_text, 0 to 0, 0 to 0,
    0 to R.string.credits_reached, 0 to R.string.credits_playing,
)

@Composable private fun Credits(modifier: Modifier, onDone: () -> Unit) = Box(modifier) {
    val y = remember { androidx.compose.animation.core.Animatable(Float.MAX_VALUE) }
    var colH by remember { mutableStateOf(0) }
    val lastHalf = with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.toPx() } // half the closing two lines
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screen = constraints.maxHeight.toFloat()
        // Scroll from just below the screen until the last two lines are centered, at the old speed (2 screens in 120 s).
        LaunchedEffect(colH, screen) {
            if (colH == 0) return@LaunchedEffect
            val end = screen / 2 - colH + lastHalf
            y.snapTo(screen)
            y.animateTo(end, tween((120_000 * (screen - end) / (2 * screen)).toInt(), easing = androidx.compose.animation.core.LinearEasing))
        }
        Column(Modifier.fillMaxWidth().offset { androidx.compose.ui.unit.IntOffset(0, y.value.coerceAtMost(screen).toInt()) }
            .wrapContentHeight(Alignment.Top, unbounded = true).onSizeChanged { colH = it.height }.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            CREDITS.forEach { (ar, br) ->
                val a = if (ar != 0) stringResource(ar) else ""; val b = if (br != 0) stringResource(br) else ""
                if (a.isNotEmpty()) Text(a, color = C.dim, fontSize = 13.sp, letterSpacing = 2.sp, textAlign = TextAlign.Center)
                if (b.isNotEmpty()) Text(b, color = C.text, fontSize = if (a.isEmpty()) 16.sp else 18.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 4.dp))
                if (a.isEmpty() && b.isEmpty()) Spacer(Modifier.height(28.dp))
            }
        }
    }
    TextButton(onDone, Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(12.dp)) { Text(stringResource(R.string.continue_btn), color = C.dim) }
}

@Composable private fun Feature(label: String, vararg icons: ImageVector) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Row { icons.forEach { Icon(it, null, tint = C.dim, modifier = Modifier.size(20.dp)) } }
    Text(label, color = C.dim, fontSize = 12.sp)
}

@Composable private fun DestinationScreen(g: Game) = Backdrop(g.airport.art) {
    Gutter {
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.destination), style = display(18, 4.0).copy(color = C.dim, fontWeight = FontWeight.Medium),
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(g.country.uiName(uiLang()).uppercase(), style = display(54, 3.0), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(coverIsolate(g.airport.name, uiLang()), color = C.text, fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Flag(g.country, Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp).size(width = 66.dp, height = 44.dp))
        Spacer(Modifier.weight(1f))
        if (g.finaleSeen) Text(stringResource(R.string.destination_after), color = Color(0xFFF2B8B2),
            fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp))
        Text(stringResource(R.string.destination_ready), color = C.text, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))
        PrimaryButton(stringResource(R.string.continue_btn)) { g.screen = Screen.COVER }
    }
}

/** A filled-in template: [bold] are the ranges of [text] that came from arguments. */
data class Bolded(val text: String, val bold: List<IntRange>)

/**
 * Fills a raw string-resource [template] (`%1$s`, `%2$d`, `%s`, `%%`) and records where each argument landed, so the
 * facts can be bold while the translatable words stay plain. A [Bolded] argument keeps its own bold ranges. Pure (tested).
 */
fun fillBold(template: String, vararg args: Any): Bolded {
    val out = StringBuilder(); val bold = mutableListOf<IntRange>()
    var next = 0; var at = 0
    Regex("%(?:(\\d+)\\$)?([sdf%])").findAll(template).forEach { m ->
        out.append(template, at, m.range.first); at = m.range.last + 1
        val conv = m.groupValues[2]
        if (conv == "%") { out.append('%'); return@forEach }
        val arg = args[m.groupValues[1].toIntOrNull()?.minus(1) ?: next++]
        val start = out.length
        if (arg is Bolded) { out.append(arg.text); arg.bold.forEach { bold += (it.first + start)..(it.last + start) } }
        else { out.append(String.format("%$conv", arg)); if (out.length > start) bold += start until out.length }
    }
    out.append(template, at, template.length)
    return Bolded(out.toString(), bold)
}

private fun Bolded.annotated() = androidx.compose.ui.text.buildAnnotatedString {
    append(text)
    bold.forEach { addStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold), it.first, it.last + 1) }
}

/** Joins cover sections with newlines, shifting their bold ranges. */
private fun List<Bolded>.joined(): Bolded = fold(Bolded("", emptyList())) { acc, b ->
    val sep = if (acc.text.isEmpty()) "" else "\n"
    val off = acc.text.length + sep.length
    Bolded(acc.text + sep + b.text, acc.bold + b.bold.map { (it.first + off)..(it.last + off) })
}

@Composable private fun CoverScreen(g: Game) = Box(Modifier.fillMaxSize().background(C.bg)) {
    val ctx = LocalContext.current
    val s = g.story
    val ui = uiLanguage(ctx)
    // The player's own photo when there is one; missing or corrupt means the sample face T1 chose (gender-matched).
    // The sample sheet is decoded only then.
    val photo = remember(s) { PassportPhoto.load(ctx) ?: tiles(ctx, s.photo.sheet, 3, 2).getOrNull(s.photo.tile) }
    // Face reading on without the camera permission (the player picked a sample photo): ask now, in context, once a session.
    val askCamera = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok -> g.cameraAnswered(ok); g.startInterview() }
    LaunchedEffect(g.destination.lang, g.earsReady) { g.preloadEars() } // again once a Whisper download lands
    Gutter {
        Text(stringResource(R.string.cover_title), style = display(24, 2.0), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.cover_memorize), color = C.dim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        val scroll = rememberScrollState()
        // The cover and difficulty scroll together; READY stays outside the scrolling content.
        Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.verticalScroll(scroll)) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.paper)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row {
                Box(Modifier.size(width = 80.dp, height = 100.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFC9C5BC))) {
                    if (photo != null) Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(s.name, style = LocalTextStyle.current.copy(textDirection = TextDirection.Content), color = C.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (s.latin != s.name) Text(s.latin, style = LocalTextStyle.current.copy(textDirection = TextDirection.Content), color = C.ink, fontSize = 14.sp)
                    val dim = Color(0xFF6B6F78)
                    Text(stringResource(R.string.cover_citizen, s.country.uiName(uiLang())), color = dim, fontSize = 13.sp)
                    Text(stringResource(R.string.cover_passport_no, s.passportNo), color = dim, fontSize = 13.sp)
                    Text(stringResource(R.string.cover_born, coverBirthDate(s.born, ui)), color = dim, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            // Resources are presentation only: Backstory and every prompt keep their original English facts.
            val story = coverLines(s, ui, ctx::getString).map { line ->
                fillBold(stringResource(line.template), *line.values.toTypedArray())
            }.joined()
            Text(remember(story) { story.annotated() }, color = C.ink, fontSize = 16.sp, lineHeight = 24.sp)
        }
        }
        Spacer(Modifier.height(12.dp))
        if (foreignTongue(uiLang(), g.destination.lang)) ForeignTongueTag(Modifier.padding(bottom = 8.dp))
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = C.blueEdge)
                Spacer(Modifier.width(12.dp))
                Text(if (g.training) stringResource(R.string.cover_tip_training)
                    else stringResource(R.string.cover_tip, g.destination.languageName(uiLang())),
                    color = C.text, fontSize = 14.sp)
            }
        }
        if (g.canChooseDifficulty) {
            Spacer(Modifier.height(12.dp))
            Panel {
                Text(stringResource(R.string.difficulty_title, g.destination.languageName(uiLang())),
                    color = C.dim, fontSize = 12.sp, letterSpacing = 1.sp)
                Spacer(Modifier.height(10.dp))
                Chips(listOf(stringResource(R.string.difficulty_relaxed), stringResource(R.string.difficulty_standard),
                    stringResource(R.string.difficulty_alert), stringResource(R.string.difficulty_severe)),
                    g.difficulty, onSelect = g::chooseDifficulty)
                Text(stringResource(R.string.difficulty_note), color = C.dim, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (g.contractsUnlocked || g.coverContract != null) {
            Spacer(Modifier.height(12.dp))
            Panel {
                val contract = g.coverContract
                val choices = listOf(null, Contract.QUIET, Contract.DISABLE)
                Text(stringResource(R.string.contract_title), color = C.dim, fontSize = 12.sp, letterSpacing = 1.sp)
                Spacer(Modifier.height(10.dp))
                Chips(listOf(stringResource(R.string.contract_none), stringResource(R.string.contract_quiet_name),
                    stringResource(R.string.contract_disable_name)), choices.indexOf(contract),
                    enabled = g.canChooseContract) { g.chooseContract(choices[it]) }
                contract?.let {
                    Text(stringResource(when (it) {
                        Contract.QUIET -> R.string.contract_quiet
                        Contract.DISABLE -> R.string.contract_disable
                    }), color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        }
        if (scroll.canScrollForward) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, C.bg))))
        }
        ParanoidMode(Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(6.dp))
        val earsWait = g.earsReady != true // null: still checking, not ready
        val label = stringResource(when { g.loadError != null -> R.string.cover_models_failed; !g.ready -> R.string.cover_calibrating
            g.earsReady == null -> R.string.cover_checking_speech
            earsWait && g.earsNeedWhisper -> R.string.cover_no_speech_model; earsWait -> R.string.cover_speech_downloading; else -> R.string.cover_ready })
        if (earsWait && g.earsNeedWhisper) {
            Text(if (g.earsStuck) stringResource(R.string.cover_asr_not_responding) else stringResource(R.string.cover_no_recognition, g.destination.languageName(uiLang())),
                color = C.dim, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            PrimaryButton(stringResource(R.string.cover_download_speech)) { g.fetchWhisper() }
        } else PrimaryButton(label, enabled = g.careerReady && ((g.ready && !earsWait) || g.loadError != null)) {
            when {
                g.loadError != null -> g.retryBrain()
                g.shouldAskCamera() -> askCamera.launch(android.Manifest.permission.CAMERA)
                else -> g.startInterview()
            }
        }
        if (g.earsReady == false && !g.earsNeedWhisper) Text(stringResource(R.string.cover_installing_recognition, g.destination.languageName(uiLang())),
            color = C.dim, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

private object Hud {
    val glass = Color(0x990A111C)
    val edge = Color(0x40FFFFFF)
    val cyan = Color(0xFF8FE3FF)
    val soft = Color(0xCCB9E8FF)
}

/**
 * The officer fills the screen; everything you read floats over them as see-through HUD panels, like smart glasses.
 * The portrait never depends on the text below it, so it can't jump between turns.
 */
@Composable private fun InterviewScreen(g: Game) = BoxWithConstraints(Modifier.fillMaxSize().background(C.bg)) {
    val ctx = LocalContext.current
    val faces = remember(g.officerId) { tiles(ctx, "officer_${g.officerId}_sheet", 4, 2) }
    val tense = g.face in setOf(Face.SUSPICIOUS, Face.STERN, Face.IMPATIENT)
    val hudMax = maxHeight * 0.62f
    // Backdrop: the same tile, blurred and dimmed, so the area under the square portrait isn't a flat void.
    val roomBg = rememberAsset("room_bg")
    // The T-N test: the eye machine the traveler looks into replaces the officer (story room or airport booth).
    val machine = rememberAsset(if (g.inBackRoom) "eye_machine" else "eye_machine_desk").takeIf { g.machineView }
    // The art and scanner share one crop container; authorization remains assessment-only.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val faceRun = g.activeFaceRun
    val permission = ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val visibleScanner = machine != null && permission && g.watching && g.room != null &&
        !g.blackout && !g.flash && g.narration == null && g.ending == null && g.sceneArt == null
    DisposableEffect(lifecycle, faceRun, visibleScanner) {
        fun publish() = g.scannerPresentation(faceRun,
            visibleScanner && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> publish() }
        lifecycle.addObserver(observer)
        publish()
        onDispose { lifecycle.removeObserver(observer); g.scannerPresentation(faceRun, false) }
    }
    if (g.inBackRoom && roomBg != null) Image(roomBg, null, Modifier.fillMaxSize().alpha(0.9f), contentScale = ContentScale.Crop)
    else faces.getOrNull(g.face.ordinal)?.let {
        Image(it, null, Modifier.fillMaxSize().blur(28.dp).alpha(0.45f), contentScale = ContentScale.Crop)
    }
    if (machine != null) Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 44.dp).aspectRatio(1f)) {
        Image(machine, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        FaceScanner(g, if (g.inBackRoom) FaceMesh.Asset.ROOM else FaceMesh.Asset.DESK,
            visibleScanner, Modifier.matchParentSize())
    }
    else Crossfade(g.face, animationSpec = tween(180), label = "face", modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(top = 44.dp)) { f ->
        val tile = faces.getOrNull(f.ordinal)
        // Androids twitch now and then, more often and harder as tricks wear them down; a broken one never stops.
        val dmg = (1 - g.mind.integrity).coerceIn(0.0, 1.0).toFloat()
        val glitch = if (g.mind.decision == Decision.BROKEN || g.officerGlitch) Modifier.glitch(idleMs = 80L..400L, strength = 2.5f)
            else Modifier.glitch(idleMs = (7000 * (1 - 0.7f * dmg)).toLong()..(15000 * (1 - 0.7f * dmg)).toLong(), strength = 0.6f + 1.4f * dmg)
        // The story's unknown room: same android, harder light (darker, colder) instead of new art.
        if (tile != null) Image(tile, null, Modifier.fillMaxWidth().aspectRatio(1f).then(glitch), contentScale = ContentScale.Crop,
            colorFilter = if (g.inBackRoom) androidx.compose.ui.graphics.ColorFilter.tint(Color(0x99061020), androidx.compose.ui.graphics.BlendMode.Multiply) else null)
        else Box(Modifier.fillMaxWidth().aspectRatio(1f).background(C.card))
    }
    // Readability scrim: clear over the face, darker toward the HUD.
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
        0f to Color(0x990A111C), 0.12f to Color.Transparent, 0.45f to Color.Transparent, 1f to Color(0xE60A111C))))

    Box(Modifier.fillMaxWidth().safeDrawingPadding().padding(vertical = 4.dp)) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            if (g.room == null) {
                Text(stringResource(R.string.hud_passport_control), color = C.text, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.hud_speak_in, g.destination.languageName(uiLang()), g.unitName), color = C.dim, fontSize = 12.sp)
            } else {
                Text(stringResource(R.string.hud_tn_assessment), color = Color(0xFFF2B8B2), fontWeight = FontWeight.Medium)
                TnLens(g.room!!.index)
            }
            g.liveContract?.let {
                Text(stringResource(R.string.contract_hud, stringResource(it.nameRes)), color = C.dim, fontSize = 12.sp)
            }
        }
        IconButton(g::abort, Modifier.align(Alignment.CenterEnd)) { Icon(Icons.Default.Close, stringResource(R.string.hud_leave), tint = C.dim) }
        val ctx = LocalContext.current
        if (g.watching && g.activeFaceRun != null && ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            OfficerEye(g, Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp), verticalArrangement = Arrangement.Bottom) {
        g.tip?.let {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xCC06303D))
                .border(1.dp, Hud.cyan.copy(alpha = 0.7f), RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.hud_training), color = Hud.cyan, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text(it, color = Color.White, fontSize = 13.sp)
            }
        }
        if (g.booting) Text(stringResource(R.string.hud_online), color = Hud.cyan, fontSize = 11.sp, letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp).glitch(idleMs = 200L..700L, strength = 1.5f))
        g.implant?.let {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xCC3A2A06))
                .border(1.dp, Color(0xFFFFC14D), RoundedCornerShape(12.dp)).padding(12.dp).glitch(idleMs = 3000L..6000L, strength = 0.5f),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.hud_implant), color = Color(0xFFFFC14D), fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text(it, color = Color.White, fontSize = 13.sp)
            }
        }
        Column(Modifier.heightIn(max = hudMax).verticalScroll(rememberScrollState())) {
            if (g.officerText.isNotEmpty()) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Hud.glass)
                .border(1.dp, if (tense) C.red.copy(alpha = 0.8f) else Hud.edge, RoundedCornerShape(14.dp)).padding(14.dp)) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.hud_replay), tint = if (tense) C.red else Hud.cyan,
                    modifier = Modifier.size(22.dp).clickable { g.replay() })
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(g.officerText, style = LocalTextStyle.current.copy(textDirection = TextDirection.Content), color = if (tense) Color(0xFFFFD9D5) else Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    if (g.officerEn.isNotEmpty()) Text(g.officerEn, style = LocalTextStyle.current.copy(textDirection = TextDirection.Content), color = if (tense) Color(0xFFE79A92) else C.dim, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (g.mind.decision == null) {
                Row(Modifier.padding(top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Visibility, null, tint = Hud.cyan, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.hud_retinal_display), color = Hud.cyan, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
                    if (g.room != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.hud_signal_dampened), color = C.red, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.glitch(idleMs = 300L..1200L, strength = 2f))
                    }
                }
                if (g.hints.isEmpty()) Text(stringResource(if (g.room != null) R.string.hud_signal_lost else R.string.hud_translating), color = Hud.soft, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                g.hints.forEach { (target, reading, english) ->
                    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)
                        .then(if (g.room != null) Modifier.glitch(idleMs = 200L..900L, strength = 2.5f) else Modifier)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x66081420)).border(1.dp, Color(0x4D8FE3FF), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp)) {
                        Text(target, style = LocalTextStyle.current.copy(textDirection = TextDirection.Content), color = Color.White, fontSize = 15.sp)
                        Text(androidx.compose.ui.text.buildAnnotatedString {
                            if (reading.isNotEmpty()) {
                                pushStyle(androidx.compose.ui.text.SpanStyle(color = Hud.cyan, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
                                append(reading); pop()
                                if (english.isNotEmpty()) append("  ·  ")
                            }
                            append(english)
                        }, style = LocalTextStyle.current.copy(textDirection = TextDirection.Content), color = Hud.soft, fontSize = 12.sp, maxLines = 2)
                    }
                }
            }
            if (g.heard.isNotEmpty()) Row(Modifier.padding(top = 2.dp)) {
                Text(stringResource(R.string.hud_you, "").trimEnd(), color = Hud.soft, fontSize = 13.sp)
                Spacer(Modifier.width(4.dp))
                Text(g.heard, style = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                    color = Hud.soft, fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
        }
        Box(Modifier.fillMaxWidth()) {
            MicButton(g)
            if (!g.passportGiven && g.officerText.isNotEmpty() && g.mind.decision == null)
                Column(Modifier.align(Alignment.CenterStart).padding(start = 8.dp).clip(RoundedCornerShape(12.dp))
                    .background(Hud.glass).border(1.dp, Hud.cyan.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .clickable { g.handOverPassport() }.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Book, stringResource(R.string.hud_hand_over_passport), tint = Hud.cyan)
                    Text(stringResource(R.string.hud_passport), color = Hud.soft, fontSize = 11.sp)
                }
        }
    }

    // The story mission's taser: a white flash, then black until the room.
    if (g.blackout) Box(Modifier.fillMaxSize().background(Color.Black))
    if (g.flash) Box(Modifier.fillMaxSize().background(Color.White))
    // The beat before the result screen: the scene dims and one line of narration says what happens.
    androidx.compose.animation.AnimatedVisibility(g.ending != null || g.narration != null, enter = androidx.compose.animation.fadeIn(tween(900))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            // A full-bleed scene (the story's arrest) under the narration; skipped if the art is missing.
            g.sceneArt?.let { rememberAsset(it) }?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Text(g.narration ?: stringResource(when (g.ending) { Decision.PASS -> R.string.ending_pass; Decision.BROKEN -> R.string.ending_broken
                    else -> R.string.ending_secondary }),
                style = display(22, 1.5).copy(color = if (g.narration != null) Color(0xFFF2B8B2) else when (g.ending) { Decision.PASS -> Color(0xFFB8F0CC); Decision.BROKEN -> Hud.cyan; else -> Color(0xFFF2B8B2) }),
                textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
        }
    }
}

/** The officer's view of you: a small live self-view, ringed red when your face is out of sight. */
@Composable private fun OfficerEye(g: Game, modifier: Modifier) {
    var seen by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { seen = g.faceWatch.seen; delay(250) } }
    CameraFeed(g, modifier.size(46.dp).clip(CircleShape).border(2.dp, if (seen) Hud.cyan else C.red, CircleShape))
}

/** Front camera preview with face detection running on every frame (FaceWatch). */
@Composable private fun CameraFeed(g: Game, modifier: Modifier) {
    val ctx = LocalContext.current
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val view = remember { androidx.camera.view.PreviewView(ctx).apply { scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(owner, g.faceWatch, g.activeFaceRun) {
        val camera = FaceCamera(ctx, owner, g.faceWatch, view.surfaceProvider)
        onDispose { camera.dispose() }
    }
    Box(modifier) { androidx.compose.ui.viewinterop.AndroidView({ view }, Modifier.fillMaxSize()) }
}

/** Settings → Camera test: what the officer's eyes read from your face, live. */
@Composable private fun CameraTestScreen(g: Game) = Box(Modifier.fillMaxSize().background(C.bg)) {
    var live by remember { mutableStateOf<FaceWatch.Live?>(null) }
    val profiler = remember { Profiler() }
    var calibrating by remember { mutableStateOf(false) }
    var calibrated by remember { mutableStateOf(false) }
    var tells by remember { mutableStateOf(emptyList<Pair<Tell, Double>>()) }
    LaunchedEffect(Unit) { while (true) { live = g.faceWatch.live; if (calibrated) tells = profiler.tells(g.faceWatch.last(8000)); delay(100) } }
    LaunchedEffect(calibrating) {
        if (!calibrating) return@LaunchedEffect
        val from = g.faceWatch.now(); delay(10_000)
        calibrated = profiler.calibrate(g.faceWatch.since(from)); calibrating = false
    }
    Gutter {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ g.go(Screen.SETTINGS) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = C.text) }
            Text(stringResource(R.string.camtest_title), style = display(24, 2.0))
        }
        val ok = LocalContext.current.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!ok) { Text(stringResource(R.string.camtest_permission), color = C.red); return@Gutter }
        CameraFeed(g, Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(14.dp))
            .border(2.dp, if (live?.face == true) Hud.cyan else C.red, RoundedCornerShape(14.dp)))
        Spacer(Modifier.height(12.dp))
        val l = live
        Panel {
            if (l == null || !l.face) Text(stringResource(if (l == null) R.string.camtest_starting else R.string.camtest_no_face), color = C.red, fontSize = 18.sp)
            else {
                Meter(stringResource(R.string.camtest_smile), l.smile)
                Meter(stringResource(R.string.camtest_left_eye), l.leftEye)
                Meter(stringResource(R.string.camtest_right_eye), l.rightEye)
                Text(stringResource(R.string.camtest_blinks, l.blinksPerMin), color = C.text, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
                Text(stringResource(R.string.camtest_head, l.yaw, l.pitch), color = C.text, fontSize = 16.sp)
                Text(stringResource(R.string.camtest_face_geometry, l.lips, l.brow), color = C.dim, fontSize = 13.sp)
            }
            Text(stringResource(R.string.camtest_stats, l?.fps ?: 0, l?.frame ?: "—", l?.facePx ?: 0),
                color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(10.dp))
        Panel {
            Text(stringResource(R.string.profile_title), color = Hud.cyan, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            when {
                calibrating -> Text(stringResource(R.string.profile_calibrating), color = C.text)
                !calibrated -> TextButton({ calibrating = true }) { Text(stringResource(R.string.profile_take_baseline), color = Hud.cyan) }
                tells.isEmpty() -> Text(stringResource(R.string.profile_no_deviation), color = C.green)
                else -> tells.forEach { (t, z) -> Text(stringResource(R.string.profile_tell, stringResource(t.label), z, stringResource(t.implant)), color = Color(0xFFFFC14D), fontSize = 14.sp) }
            }
        }
    }
}

@Composable private fun Meter(label: String, v: Float?) = Column(Modifier.padding(vertical = 4.dp)) {
    Row { Text(label, color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f)); Text(v?.let { "${(it * 100).toInt()}%" } ?: "—", color = Hud.cyan, fontSize = 15.sp) }
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.line)) {
        Box(Modifier.fillMaxWidth(v ?: 0f).fillMaxHeight().background(Hud.cyan))
    }
}

/** The T-N scanner's lens: a pulsing ring that reddens as the hidden index rises. Never a number. */
@Composable private fun TnLens(index: Double) {
    val pulse by rememberInfiniteTransition(label = "lens").animateFloat(0.6f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "lens")
    val heat = (index / 100).toFloat().coerceIn(0f, 1f)
    Box(Modifier.padding(top = 4.dp).size(14.dp).alpha(pulse).clip(CircleShape)
        .background(androidx.compose.ui.graphics.lerp(Hud.cyan, C.red, heat)))
}

@Composable private fun MicButton(g: Game) {
    val active = g.mic == Mic.HEARING
    val usable = g.mic == Mic.READY || active
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(1f, 1.12f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    val label = when (g.mic) {
        Mic.HEARING -> stringResource(if (g.inputMode == InputMode.HOLD) R.string.mic_release else R.string.mic_listening)
        Mic.READY -> stringResource(if (g.inputMode == InputMode.HOLD) R.string.mic_hold else R.string.mic_speak_now)
        Mic.OFFICER_SPEAKING -> stringResource(R.string.mic_officer_speaking)
        Mic.PROCESSING -> "…"
        Mic.OFF -> ""
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp)
            .scale(if (active || (g.inputMode == InputMode.AUTO && g.mic == Mic.READY)) pulse else 1f)
            .clip(CircleShape).background(if (usable) C.mic else Hud.glass)
            .border(3.dp, if (usable) Color(0xFF9CC4FF) else C.line, CircleShape)
            .alpha(if (usable) 1f else 0.6f)
            .pointerInput(g.inputMode) {
                awaitEachGesture {
                    awaitFirstDown()
                    g.press()
                    waitForUpOrCancellation()
                    g.release()
                }
            },
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Mic, null, tint = Color.White, modifier = Modifier.size(38.dp))
        }
        Text(label, color = C.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable private fun ResultScreen(g: Game) {
    val broken = g.mind.decision == Decision.BROKEN
    val passed = g.mind.decision != Decision.SECONDARY
    val view = LocalView.current
    LaunchedEffect(Unit) { g.resultHaptic(view, passed) }
    val accent = if (broken) Hud.cyan else if (passed) C.green else C.red
    Backdrop(if (passed) "success_bg" else "fail_bg", tint = if (passed) Color(0xFF07140D) else Color(0xFF16070A)) {
        Gutter(scrollable = true) {
            Spacer(Modifier.weight(1f))
            if (broken || passed || !g.wasInRoom) {
                Text(if (broken) stringResource(R.string.result_unit_unresponsive) else if (passed) g.destination.stampPass else g.destination.stampFail, style = display(30, 3.0).copy(color = accent)
                    .let { if (broken) it else it.copy(textDirection = TextDirection.Content) },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.CenterHorizontally).then(if (broken) Modifier.glitch(idleMs = 600L..1800L) else Modifier).rotate(-8f)
                        .border(4.dp, accent, RoundedCornerShape(10.dp)).padding(horizontal = 18.dp, vertical = 8.dp))
                Spacer(Modifier.height(36.dp))
            }
            Text(stringResource(if (broken) R.string.result_unit_offline else if (passed) R.string.result_crossing_logged else R.string.result_mission_failed), style = display(40, 3.0).copy(color = if (passed) C.text else Color(0xFFF2B8B2)),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(stringResource(when {
                broken -> R.string.result_msg_broken
                passed && g.wasInRoom -> R.string.result_msg_released
                passed -> R.string.result_msg_passed
                g.wasInRoom -> R.string.result_msg_detained
                else -> R.string.result_msg_secondary
            }),
                color = C.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
            if (foreignTongue(uiLang(), g.destination.lang)) {
                ForeignTongueTag()
                // A story pass goes to the finale, a story fail to the arrest: neither reaches this line.
                if (passed) Text(stringResource(R.string.result_foreign_cleared), color = C.dim,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            Spacer(Modifier.weight(1f))
            Panel(border = if (passed) C.line else accent.copy(alpha = 0.6f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (broken) Icons.Default.Memory else if (passed) Icons.Default.Flight else Icons.Default.Search, null, tint = accent)
                    Spacer(Modifier.width(12.dp))
                    Text(if (broken) pluralStringResource(R.plurals.result_broken_log, g.mind.tricks, displayUnit(g.officerId), g.mind.tricks)
                        else if (passed) pluralStringResource(R.plurals.result_passed_log, g.mind.turns, g.mind.turns) else stringResource(g.mind.verdict()),
                        color = C.text, fontSize = 14.sp)
                }
            }
            contractResult(g.careerState, g.careerSaving)?.let { (contract, done) ->
                Text(stringResource(R.string.replay_mark_detail,
                    stringResource(if (done) R.string.contract_complete else R.string.contract_missed),
                    stringResource(contract.nameRes)), color = if (done) C.green else C.dim,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            }
            CommittedMarks(g)
            g.behaviorDebrief()?.let { Spacer(Modifier.height(10.dp)); BehaviorPanel(it) }
            Spacer(Modifier.height(16.dp))
            OfflineReminder(g)
            if (passed) PrimaryButton(stringResource(R.string.continue_btn)) { g.screen = Screen.SUMMARY }
            else {
                PrimaryButton(stringResource(R.string.result_try_again), enabled = g.careerReady) { g.beginMission() }
                TextButton({ g.screen = Screen.SUMMARY }, Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.result_run_summary), color = C.dim) }
                TextButton({ g.go(Screen.START) }, Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.main_menu), color = C.text) }
            }
        }
    }
}

/** Cover and result: this mission isn't in the player's own (UI) language. Display only. */
@Composable private fun ForeignTongueTag(modifier: Modifier = Modifier) = Text(stringResource(R.string.foreign_tongue_tag),
    color = Color(0xFFFFC14D), fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold,
    textAlign = TextAlign.Center, modifier = modifier.fillMaxWidth())

@Composable private fun BehaviorPanel(text: String) = Panel {
    Text(stringResource(R.string.profile_title), color = C.dim, fontSize = 13.sp, letterSpacing = 2.sp)
    Text(text, color = C.text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable private fun SummaryScreen(g: Game) = Box(Modifier.fillMaxSize().background(C.bg)) {
    val m = g.mind
    Gutter {
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.summary_title), style = display(26, 2.0), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
        Panel {
            Stat(Icons.Default.Flight, stringResource(R.string.summary_destination), "${g.country.uiName(uiLang())} (${coverIsolate(g.airport.name, uiLang())})")
            Stat(Icons.Default.Description, stringResource(R.string.summary_answers), "${m.turns}")
            Stat(Icons.Default.Schedule, stringResource(R.string.summary_result), stringResource(when (m.decision) { Decision.PASS -> R.string.summary_cleared; Decision.BROKEN -> R.string.summary_unit_offline; else -> R.string.summary_secondary }),
                when (m.decision) { Decision.PASS -> C.green; Decision.BROKEN -> Hud.cyan; else -> C.red })
            Stat(Icons.Default.BarChart, stringResource(R.string.summary_behaviour, g.unitName), stringResource(m.demeanor()))
            g.room?.let { Stat(Icons.Default.Psychology, stringResource(R.string.summary_tn_index), pluralStringResource(R.plurals.summary_tn_value, it.asked, it.index, it.asked),
                if (it.index <= 50) C.green else C.red) }
            val cr = g.career
            Stat(Icons.Default.MilitaryTech, stringResource(R.string.summary_rank), listOfNotNull(rankName(cr),
                stringResource(R.string.stat_cleared, cr.cleared), stringResource(R.string.summary_best_streak, cr.best),
                cr.rooms.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.summary_rooms, it, it) },
                cr.broken.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.stat_broken, it, it) }).joinToString(" · "))
        }
        Spacer(Modifier.height(16.dp))
        g.behaviorDebrief()?.let { BehaviorPanel(it); Spacer(Modifier.height(16.dp)) }
        Panel { Text("“${stringResource(m.verdict())}”", color = C.text, fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        Spacer(Modifier.weight(1f))
        PrimaryButton(stringResource(R.string.summary_new_mission), enabled = g.careerReady) { g.beginMission() }
        TextButton({ g.go(Screen.START) }, Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.main_menu), color = C.text) }
    }
}

@Composable private fun Stat(icon: ImageVector, label: String, value: String, color: Color = C.text) =
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = C.text, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(18.dp))
        Column { Text(label, color = C.dim, fontSize = 13.sp); Text(value, color = color, fontSize = 17.sp) }
    }

@Composable private fun SettingsScreen(g: Game) = Box(Modifier.fillMaxSize().background(C.bg)) {
    Gutter {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ g.go(Screen.START) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = C.text) }
            Text(stringResource(R.string.settings_title), style = display(22, 2.0))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(12.dp))
            Panel {
                Text(stringResource(R.string.settings_voice_input), color = C.text, fontSize = 16.sp)
                Text(stringResource(R.string.settings_on_device_speech), color = C.dim, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Choice(listOf(stringResource(R.string.settings_hold_to_speak), stringResource(R.string.settings_auto)), if (g.inputMode == InputMode.HOLD) 0 else 1) {
                    g.setInput(if (it == 0) InputMode.HOLD else InputMode.AUTO)
                }
                Text(stringResource(if (g.inputMode == InputMode.HOLD) R.string.settings_hold_help else R.string.settings_auto_help),
                    color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                HorizontalDivider(Modifier.padding(vertical = 14.dp), color = C.line)
                Row(Modifier.fillMaxWidth().clickable { g.screen = Screen.LANGUAGES }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Translate, null, tint = C.text); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_languages), color = C.text, fontSize = 16.sp)
                        Text(uiLang().let { ui -> Language.all.filter { it.code in g.languages }.joinToString { it.uiName(ui) } }.replaceFirstChar { it.titlecase() }, color = C.dim, fontSize = 12.sp)
                        Language.all.filter { it.code in g.languages && g.slowLang(it.code) }.takeIf { it.isNotEmpty() }?.let { slow ->
                            Text("${uiLang().let { ui -> slow.joinToString { it.uiName(ui) } }.replaceFirstChar { it.titlecase() }} · ${stringResource(R.string.lang_slow_tag)}", color = C.amber, fontSize = 12.sp)
                        }
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.dim)
                }
                HorizontalDivider(Modifier.padding(vertical = 14.dp), color = C.line)
                Row(Modifier.fillMaxWidth().clickable { g.screen = Screen.PASSPORT }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Book, null, tint = C.text); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_passport), color = C.text, fontSize = 16.sp)
                        Text(stringResource(R.string.settings_passport_help), color = C.dim, fontSize = 12.sp)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.dim)
                }
                HorizontalDivider(Modifier.padding(vertical = 14.dp), color = C.line)
                val lm = LocalContext.current.getSystemService(LocaleManager::class.java)
                val uiCodes = listOf("") + Language.all.map { it.code }
                Text(stringResource(R.string.settings_ui_language), color = C.text, fontSize = 16.sp)
                Text(stringResource(R.string.settings_ui_language_help), color = C.dim, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Chips(listOf(stringResource(R.string.settings_system_default)) + Language.all.map { it.native },
                    uiCodes.indexOf(lm.applicationLocales.takeIf { !it.isEmpty }?.get(0)?.language ?: "").coerceAtLeast(0)) {
                    lm.applicationLocales = LocaleList.forLanguageTags(uiCodes[it]) // recreates the activity; Game (ViewModel) survives
                }
                HorizontalDivider(Modifier.padding(vertical = 14.dp), color = C.line)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MusicNote, null, tint = C.text); Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.settings_music), color = C.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(g.musicOn, g::setMusic)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.GraphicEq, null, tint = C.text); Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.settings_sfx), color = C.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(g.sfxOn, g::setSfx)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Visibility, null, tint = C.text); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_camera), color = C.text, fontSize = 16.sp)
                        Text(stringResource(R.string.settings_camera_help), color = C.dim, fontSize = 12.sp)
                    }
                    Switch(g.cameraOn, g::setCamera)
                }
                TextButton({ g.screen = Screen.CAMERA_TEST }, Modifier.padding(start = 30.dp)) { Text(stringResource(R.string.settings_camera_test), color = Hud.cyan) }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Vibration, null, tint = C.text); Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.settings_haptics), color = C.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(g.haptics, g::setHaptic)
                }
                HorizontalDivider(Modifier.padding(vertical = 14.dp), color = C.line)
                Row(Modifier.fillMaxWidth().clickable { g.screen = Screen.LICENSES }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = C.text); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_licenses), color = C.text, fontSize = 16.sp)
                        Text(stringResource(R.string.genai_note), color = C.dim, fontSize = 12.sp)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.dim)
                }
            }
            Spacer(Modifier.height(12.dp))
            Panel {
                Row(Modifier.fillMaxWidth().clickable { g.replayTraining(); g.go(Screen.START) }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.School, null, tint = C.text); Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.settings_replay_training), color = C.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.dim)
                }
                HorizontalDivider(Modifier.padding(vertical = 14.dp), color = C.line)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BugReport, null, tint = C.text); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_diagnostics), color = C.text, fontSize = 16.sp)
                        Text(stringResource(R.string.settings_diagnostics_help), color = C.dim, fontSize = 12.sp)
                    }
                    Switch(g.diagnostics, g::toggleDiagnostics)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.settings_about), style = display(15, 2.0).copy(color = C.dim))
            Spacer(Modifier.height(8.dp))
            Panel {
                About(Icons.Default.PhoneAndroid, stringResource(R.string.about_on_phone))
                About(Icons.Default.WifiOff, stringResource(R.string.about_offline))
                About(Icons.Default.Place, pluralStringResource(R.plurals.about_destinations, Country.all.size, Country.all.size, Language.all.size))
                About(Icons.Default.Person, stringResource(R.string.about_new_officer))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.credits_blurb), color = C.dim, fontSize = 13.sp)
            }
            Spacer(Modifier.height(28.dp))
            Text(stringResource(R.string.settings_footer), style = display(20, 3.0).copy(color = C.line.copy(alpha = 1f), fontWeight = FontWeight.Medium),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable private fun Choice(options: List<String>, selected: Int, onSelect: (Int) -> Unit) =
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, C.line, RoundedCornerShape(10.dp))) {
        options.forEachIndexed { i, o ->
            Box(Modifier.weight(1f).background(if (i == selected) C.blue else Color.Transparent).clickable { onSelect(i) }
                .padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(o, color = if (i == selected) C.text else C.dim, fontSize = 14.sp)
            }
        }
    }

@Composable private fun About(icon: ImageVector, text: String) = Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, tint = C.dim, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(14.dp)); Text(text, color = C.text, fontSize = 14.sp)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun Chips(options: List<String>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, o ->
            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (i == selected) C.blue else Color.Transparent)
                .border(1.dp, if (i == selected) C.blueEdge else C.line, RoundedCornerShape(10.dp))
                .clickable(enabled = enabled) { onSelect(i) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(o, color = if (i == selected) C.text else C.dim, fontSize = 14.sp)
            }
        }
    }

/** First run picks languages before anything downloads; later visits add packs. Installed packs stay ticked. */
@Composable private fun LanguagesScreen(g: Game) = Box(Modifier.fillMaxSize().background(C.bg)) {
    var picked by remember { mutableStateOf(g.languages) }
    val firstRun = g.languages.isEmpty()
    val adding = picked - g.languages
    Gutter {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!firstRun) IconButton({ g.go(Screen.SETTINGS) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = C.text) }
            Text(stringResource(R.string.languages_title), style = display(24, 2.0))
        }
        Text(stringResource(if (firstRun) R.string.languages_first_run else R.string.languages_add),
            color = C.dim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Language.all.forEach { l ->
                val installed = l.code in g.languages
                val on = l.code in picked
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (on) C.card else Color.Transparent).border(1.dp, if (on) C.blueEdge else C.line, RoundedCornerShape(12.dp))
                    .clickable(enabled = !installed) { picked = if (on) picked - l.code else picked + l.code }
                    .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.native, color = C.text, fontSize = 17.sp)
                        Text(airportCount(l.code).let { pluralStringResource(R.plurals.languages_destinations, it, l.uiName(uiLang()).replaceFirstChar { it.titlecase() }, it) }, color = C.dim, fontSize = 12.sp)
                        if (g.slowLang(l.code)) Text(stringResource(R.string.lang_slow_tag), color = C.amber, fontSize = 12.sp)
                    }
                    if (installed) Text(stringResource(R.string.languages_installed), color = C.green, fontSize = 13.sp)
                    else Checkbox(on, { picked = if (on) picked - l.code else picked + l.code })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(if (adding.isEmpty()) stringResource(R.string.languages_nothing_new) else g.downloadNote(adding), color = C.dim, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp))
        PrimaryButton(stringResource(if (firstRun) R.string.languages_download else R.string.languages_add_btn), enabled = adding.isNotEmpty()) { g.install(adding) }
    }
}

/** First-run step after the passport: how the player answers. Voice-activated is pre-selected; Settings can change it. */
@Composable private fun InputModeScreen(g: Game) = Box(Modifier.fillMaxSize().background(C.bg)) {
    var pick by remember { mutableStateOf(g.inputMode) }
    Gutter {
        Text(stringResource(R.string.setup_input_title), style = display(24, 2.0))
        Text(stringResource(R.string.setup_input_note), color = C.dim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            listOf(InputMode.AUTO to (R.string.setup_input_auto to R.string.settings_auto_help),
                InputMode.HOLD to (R.string.settings_hold_to_speak to R.string.settings_hold_help)).forEach { (m, text) ->
                val on = m == pick
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (on) C.card else Color.Transparent).border(1.dp, if (on) C.blueEdge else C.line, RoundedCornerShape(12.dp))
                    .selectable(on, role = Role.RadioButton) { pick = m }
                    .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(text.first), color = C.text, fontSize = 17.sp)
                        Text(stringResource(text.second), color = C.dim, fontSize = 12.sp)
                    }
                    RadioButton(on, null)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(stringResource(R.string.continue_btn)) { g.inputChosen(pick) }
    }
}

/** The default network, tracked live while [on]: 0 Wi-Fi/unmetered, 1 metered, 2 offline (airplane mode lands here too). */
@Composable private fun rememberNet(on: Boolean = true): Int {
    val ctx = LocalContext.current
    var net by remember { mutableIntStateOf(0) }
    DisposableEffect(on) {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val kind = { c: android.net.NetworkCapabilities? -> if (c == null) 2 else if (c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) 0 else 1 }
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: android.net.Network, c: android.net.NetworkCapabilities) { net = kind(c) }
            override fun onLost(n: android.net.Network) { net = 2 }
        }
        if (on) {
            net = kind(cm.activeNetwork?.let { cm.getNetworkCapabilities(it) })
            cm.registerDefaultNetworkCallback(cb)
        }
        onDispose { if (on) cm.unregisterNetworkCallback(cb) }
    }
    return net
}

/** The system's internet panel (airplane mode, Wi-Fi, mobile data); the app can't toggle them itself. */
private fun openInternetPanel(ctx: android.content.Context) {
    // try/catch, not resolveActivity: package visibility (API 30+) can hide Settings from resolveActivity
    try { ctx.startActivity(android.content.Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) }
    catch (_: android.content.ActivityNotFoundException) {
        runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS)) }
    }
}

/** Cover: a switch that mirrors the phone being offline (it flips on by itself); tapping opens the internet panel. Display only. */
@Composable private fun ParanoidMode(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val offline = rememberNet() == 2
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .toggleable(offline, role = Role.Switch) { openInternetPanel(ctx) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(if (offline) R.string.paranoid_offline else R.string.paranoid_mode),
                color = if (offline) Hud.cyan else C.dim, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            if (!offline) Text(stringResource(R.string.paranoid_hint), color = C.dim, fontSize = 12.sp)
        }
        Switch(offline, null)
    }
}

/** Result: after a paranoid-mode mission, while still offline, a nudge back to the internet panel. */
@Composable private fun OfflineReminder(g: Game) {
    val ctx = LocalContext.current
    if (remindOffline(g.playedOffline, rememberNet() == 2))
        TextButton({ openInternetPanel(ctx) }, Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.paranoid_still_on), color = Hud.cyan, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
}

@Composable private fun DownloadScreen(g: Game) = Box(Modifier.fillMaxSize().background(C.bg)) {
    val ctx = LocalContext.current
    val net = rememberNet(g.installing)
    Gutter {
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.download_title), style = display(28, 3.0), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
        Panel(Modifier.weight(3f, fill = false).verticalScroll(rememberScrollState())) {
            g.steps.forEach { st ->
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                        when {
                            st.failed -> Icon(Icons.Default.ErrorOutline, null, tint = C.red)
                            st.done -> Icon(Icons.Default.CheckCircle, null, tint = C.green)
                            else -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = C.blueEdge)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column { Text(st.title, color = C.text, fontSize = 16.sp); Text(st.note, color = C.dim, fontSize = 12.sp) }
                }
            }
        }
        if (!g.installing && g.steps.firstOrNull()?.failed == true) { // the AI step: Nano not ready, Whisper failed
            Spacer(Modifier.height(12.dp))
            TextButton({ g.retryInstall() }) { Text(stringResource(R.string.download_try_again), color = C.blueEdge) }
        }
        if (g.needsConfirm) {
            Spacer(Modifier.height(12.dp))
            val activity = LocalContext.current as android.app.Activity
            PrimaryButton(stringResource(R.string.download_confirm)) { g.confirmDownload(activity) }
        }
        if (g.installing && net != 0) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(if (net == 2) R.string.download_offline else R.string.download_metered), color = C.dim, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        if (g.voiceMissing.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Panel(border = C.red.copy(alpha = 0.6f)) {
                Text(stringResource(R.string.download_no_voice, uiLang().let { ui -> g.voiceMissing.joinToString { Language.of(it).uiName(ui) } }),
                    color = C.text, fontSize = 14.sp)
                TextButton({ ctx.startActivity(android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text(stringResource(R.string.download_install_voices), color = C.blueEdge) }
                TextButton({ g.recheckVoices() }) { Text(stringResource(R.string.download_check_again), color = C.blueEdge) }
            }
        }
        Spacer(Modifier.weight(1f))
        val failed = g.steps.any { it.failed && !it.noVoice }
        PrimaryButton(stringResource(when { g.installing -> R.string.download_wait; failed -> R.string.download_back; else -> R.string.continue_btn }),
            enabled = !g.installing && (failed || g.languages.isNotEmpty())) {
            g.screen = if (failed) Screen.LANGUAGES else g.nextSetupScreen()
        }
    }
}

@Composable private fun UnsupportedScreen(g: Game) = Backdrop("start_bg") {
    Gutter {
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.title_two_line), style = display(44, 4.0), textAlign = TextAlign.Center, lineHeight = 52.sp, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
        Panel(border = C.red.copy(alpha = 0.6f)) {
            Text(stringResource(R.string.unsupported_title), color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text(g.supported ?: "", color = C.dim, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.weight(1f))
    }
}

/** name, license, copyright (or ""), asset text file (or ""), link */
private val LICENSES = listOf(
    listOf("ML Kit GenAI, Face Detection and Language ID", "Google ML Kit terms", "", "", "https://developers.google.com/ml-kit/terms"),
    listOf("CameraX, Jetpack Compose, kotlinx-coroutines, Play Asset Delivery", "Apache-2.0", "", "apache-2.0.txt", ""),
    listOf("sherpa-onnx", "Apache-2.0", "", "apache-2.0.txt", "https://github.com/k2-fsa/sherpa-onnx"),
    listOf("eSpeak NG (inside sherpa-onnx)", "GPL-3.0", "", "gpl-3.0.txt", "https://github.com/espeak-ng/espeak-ng"),
    listOf("Whisper", "MIT", "Copyright (c) 2022 OpenAI", "mit-whisper.txt", "https://github.com/openai/whisper"),
    listOf("Silero VAD", "MIT", "Copyright (c) 2020-present Silero Team", "mit-silero.txt", "https://github.com/snakers4/silero-vad"),
    listOf("Oswald font", "SIL OFL 1.1", "Copyright 2016 The Oswald Project Authors", "ofl-oswald.txt", "https://github.com/googlefonts/OswaldFont"),
)

@Composable private fun LicensesScreen(g: Game) = Box(Modifier.fillMaxSize().background(C.bg)) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    var open by remember { mutableStateOf<String?>(null) } // asset file whose full text is showing
    Gutter {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ if (open != null) open = null else g.go(Screen.SETTINGS) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = C.text) }
            Text(stringResource(R.string.licenses_title), style = display(22, 2.0))
        }
        val file = open
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(12.dp))
            if (file != null) {
                val unavailable = stringResource(R.string.licenses_unavailable)
                val text = remember(file) { runCatching { ctx.assets.open("licenses/$file").bufferedReader().readText() }.getOrDefault(unavailable) }
                Text(text, color = C.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            } else {
                @Composable fun entry(name: String, license: String, copyright: String, text: String, link: String) = Panel(Modifier.padding(bottom = 10.dp)) {
                    Text(name, color = C.text, fontSize = 16.sp)
                    Text(license, color = C.dim, fontSize = 12.sp)
                    if (copyright.isNotEmpty()) Text(copyright, color = C.dim, fontSize = 12.sp)
                    if (text.isNotEmpty()) TextButton({ open = text }) { Text(stringResource(R.string.licenses_full_text), color = Hud.cyan) }
                    if (link.isNotEmpty()) TextButton({ uri.openUri(link) }) { Text(link, color = Hud.cyan, fontSize = 12.sp) }
                }
                entry("Borderline Fluent", "GPL-3.0. Copyright (C) 2026 Fluffy Pants Studio LLC.", "", "gpl-3.0.txt", "https://github.com/itsaryuk/borderline-fluent")
                entry(stringResource(R.string.licenses_art), stringResource(R.string.licenses_art_rights),
                    stringResource(R.string.licenses_art_copyright), "", "")
                Panel(Modifier.padding(bottom = 10.dp)) { Text(stringResource(R.string.genai_note), color = C.text, fontSize = 13.sp) }
                LICENSES.forEach { entry(it[0], it[1], it[2], it[3], it[4]) }
                entry(stringResource(R.string.licenses_mlkit_terms), "Google", "", "", "https://developers.google.com/ml-kit/genai-terms")
                entry("Piper voices", stringResource(R.string.licenses_piper_note), "", "", "")
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

// ---------- career save and recovery ----------

@Composable private fun MissingPackAction(g: Game) {
    Text(stringResource(R.string.replay_pack_missing), color = C.text, modifier = Modifier.padding(vertical = 12.dp))
    PrimaryButton(stringResource(R.string.settings_languages)) { g.go(Screen.LANGUAGES) }
}

@Composable private fun RecoveryPanel(g: Game) {
    if (g.interruptedAttempt == null) return
    Panel {
        Text(stringResource(if (g.preparationUnavailable && !g.recoveryPackMissing)
            R.string.replay_preparation_unavailable else R.string.replay_interrupted), color = C.text)
        if (g.recoveryPackMissing) MissingPackAction(g)
        if (!g.preparationUnavailable || g.recoveryPackMissing) PrimaryButton(stringResource(R.string.continue_btn), g.careerReady) { g.continueInterrupted() }
        if (g.preparationUnavailable) PrimaryButton(stringResource(R.string.begin_mission), g.careerReady) { g.beginMission() }
    }
}

@Composable private fun CareerSaveBlocker(g: Game) {
    val state = savePresentation(g.careerState, g.careerSaving)
    // The acknowledged ending still owns navigation, but its scene must remain visible.
    if (state != SavePresentation.SAVING && state != SavePresentation.RETRY) return
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        BackHandler { /* A staged write must be acknowledged before any navigation. */ }
        Panel(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            if (state == SavePresentation.RETRY) {
                Text(stringResource(R.string.replay_save_failed), color = C.text)
                Spacer(Modifier.height(16.dp))
                PrimaryButton(stringResource(R.string.replay_save_retry)) { g.retryCareerSave() }
            } else CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

private val Contract.nameRes get() = when (this) {
    Contract.QUIET -> R.string.contract_quiet_name
    Contract.DISABLE -> R.string.contract_disable_name
}

@Composable private fun CommittedMarks(g: Game) {
    val marks = committedMarks(g.careerState, g.careerSaving)
    for (mark in marks) {
        val label = when (mark.kind) {
            "infiltration" -> stringResource(R.string.album_infiltration)
            "sabotage" -> stringResource(R.string.album_sabotage)
            "unit" -> stringResource(R.string.replay_mark_detail, stringResource(R.string.album_sabotage),
                stringResource(R.string.album_unit, coverIsolate(displayUnit(mark.officerId ?: continue), uiLang())))
            "contract" -> stringResource(R.string.replay_mark_detail, stringResource(R.string.contract_title),
                stringResource(markContract(mark)?.nameRes ?: continue))
            else -> continue // Preserve unknown evidence without inventing a label for future content.
        }
        Text(stringResource(R.string.replay_new_mark, label), color = C.green, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
    }
}
