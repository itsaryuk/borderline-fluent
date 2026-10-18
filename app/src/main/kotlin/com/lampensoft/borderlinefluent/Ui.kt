package com.lampensoft.borderlinefluent

import android.app.LocaleManager
import android.os.LocaleList
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import android.widget.Toast
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.GenericShape
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object C {
    val bg = Color(0xFF000A24)
    val card = Color(0xFF000E30)
    val line = Color(0x2EF3ECE0)
    val ivory = Color(0xFFF3ECE0)
    val navy = Color(0xFF001448)
    val cyan = Color(0xFF7DF9FF)
    val mic = Color(0xFFF3ECE0)
    val text = Color(0xFFF3ECE0)
    val dim = Color(0xFFA9B0C3)
    val red = Color(0xFFD9443A)
    val green = Color(0xFF3FB56E)
    val amber = Color(0xFFFFC14D)
    val paper = Color(0xFFF3ECE0)
    val ink = Color(0xFF0B1430)
    val inkDim = Color(0xFF5B6070)
}

/** One corner for all chrome: the border terminal is square. */
private val Square = RoundedCornerShape(2.dp)

/** The cyan border line: a 3dp rule at the start edge of a heading or card (a Row, so it mirrors in RTL). */
@Composable private fun BorderLine(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) =
    Row(modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(C.cyan)); Spacer(Modifier.width(10.dp)); content()
    }

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val Oswald = FontFamily(
    Font(R.font.oswald, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.oswald, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

internal fun display(size: Int, spacing: Double = 2.0) =
    TextStyle(fontFamily = Oswald, fontWeight = FontWeight.Bold, fontSize = size.sp, letterSpacing = spacing.sp, color = C.text)

// ---------- art ----------

/** Shared art ships in the app; officers and destinations come from the installed language packs. */
private fun asset(ctx: Context, name: String): android.graphics.Bitmap? =
    assetCache.get(name) ?: decodeAsset(ctx, name)?.also { assetCache.put(name, it) }

/** Scenes remember the same full-screen frame on every step: keep the last few decoded (bitmaps are never mutated). */
private val assetCache = android.util.LruCache<String, android.graphics.Bitmap>(4)

private fun decodeAsset(ctx: Context, name: String): android.graphics.Bitmap? =
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
    Box(Modifier.fillMaxSize().background(if (img == null) Color.Black else C.bg)) {
        img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (img != null) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to tint.copy(alpha = 0.35f), 0.45f to tint.copy(alpha = 0.55f), 1f to tint.copy(alpha = 0.97f))))
        content()
    }
}

// ---------- building blocks ----------

/** The interface language, for country and language names inside UI strings. */
@Composable private fun uiLang() = uiLanguage(LocalContext.current)
/** The implant build replaces the old clears-based rank. */
@Composable private fun rankName(c: Career) = stringResource(R.string.implant_build, c.build)

/** The country's flag emoji, sized from the box height so the glyph fills the box. */
@OptIn(ExperimentalTextApi::class)
@Composable private fun Flag(c: Country, modifier: Modifier) = Flag(c.emoji, modifier)
@OptIn(ExperimentalTextApi::class)
@Composable private fun Flag(emoji: String, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val size = with(LocalDensity.current) { maxHeight.toSp() }
        Text(emoji, fontSize = size, lineHeight = size, maxLines = 1, softWrap = false,
            style = LocalTextStyle.current.copy(platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}

@Composable private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = { uiSfx?.play(R.raw.sfx_tap, Sfx.TAP_GAIN); onClick() }, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).alpha(if (enabled) 1f else 0.5f),
        shape = Square,
        // Outlined, not a solid ivory slab (owner: less white): navy fill, ivory hairline and text.
        border = BorderStroke(1.dp, if (enabled) C.ivory else C.line),
        colors = ButtonDefaults.buttonColors(containerColor = C.card, disabledContainerColor = C.card,
            contentColor = C.ivory, disabledContentColor = C.dim),
    ) { Text(text, style = display(17, 1.5).copy(color = if (enabled) C.ivory else C.dim)) }
}

/** Every toggle: calm. Checked = card track, ivory border, ivory thumb; unchecked = card track, dim line, dim thumb (thumb side + border tell them apart without colour). */
@Composable private fun GameSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?) = Switch(checked, onCheckedChange?.let { f -> { on: Boolean -> uiSfx?.play(R.raw.sfx_tap, Sfx.TAP_GAIN); f(on) } },
    colors = SwitchDefaults.colors(checkedThumbColor = C.ivory, checkedTrackColor = C.card, checkedBorderColor = C.ivory,
        uncheckedThumbColor = C.dim, uncheckedTrackColor = C.card, uncheckedBorderColor = C.line))

@Composable private fun Panel(modifier: Modifier = Modifier, border: Color = C.line, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(Square).background(C.card.copy(alpha = 0.92f))
        .border(1.dp, border, Square).padding(16.dp), content = content)
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
    // Menus fall back to English on a phone set to a language without shipped strings (held-back Arabic, Hebrew, Persian…):
    // lay them out left to right then, not mirrored. Officer lines keep their own direction (TextDirection.Content).
    val ui = uiLanguage(LocalContext.current)
    val direction = if (ui in RTL_UI && Language.isShipped(ui)) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides direction) {
    MaterialTheme(colorScheme = darkColorScheme(background = C.bg, surface = C.card, primary = C.mic, onPrimary = C.navy)) {
        LaunchedEffect(g.screen, g.act1Stage, g.travel, g.queue) { g.soundtrack() }
        Surface(color = C.bg) {
            Column(Modifier.fillMaxSize()) {
                val saveView = savePresentation(g.careerState, g.careerSaving)
                if (saveView == SavePresentation.READ_ERROR || saveView == SavePresentation.PREPARATION_ERROR) {
                    Panel(Modifier.safeDrawingPadding().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        Text(stringResource(if (saveView == SavePresentation.PREPARATION_ERROR)
                            R.string.replay_preparation_unavailable else R.string.replay_read_failed), color = C.text)
                        if (saveView == SavePresentation.PREPARATION_ERROR)
                            PrimaryButton(stringResource(R.string.begin_mission)) { g.beginMission() }
                        else if (g.careerState is CareerSave.Unreadable) PrimaryButton(stringResource(R.string.replay_start_over), enabled = !g.careerSaving) { g.startOver() }
                    }
                }
                if (g.screen == Screen.COVER && g.preparationUnavailable)
                    Column(Modifier.safeDrawingPadding().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) { RecoveryPanel(g) }
                Box(Modifier.weight(1f)) {
                    val screenContext = LocalContext.current
                    if (g.secretEnding && g.screen in setOf(Screen.START, Screen.RESULT, Screen.SUMMARY)) BadTesterScreen(g)
                    else androidx.compose.animation.AnimatedContent(
                        targetState = g.screen to g.queue?.takeIf { g.screen == Screen.COVER },
                        contentKey = { (screen, queue) -> screen to queue?.encounter?.id },
                        transitionSpec = {
                            val duration = if (initialState.second != null && targetState.first == Screen.INTERVIEW &&
                                Motion.allowed()) 300 else 0
                            androidx.compose.animation.ContentTransform(
                                androidx.compose.animation.fadeIn(tween(duration)),
                                androidx.compose.animation.fadeOut(tween(duration)), sizeTransform = null)
                        }, label = "queueToDesk",
                    ) { (screen, queue) -> when (screen) {
                        Screen.UNSUPPORTED -> UnsupportedScreen(g)
                        Screen.INTRO -> IntroScreen(g)
                        Screen.ACT1_CUTSCENE -> Act1Cutscene(g)
                        Screen.TRAINING_SETUP -> {
                            BackHandler { }
                            LaunchedEffect(g.careerReady) { g.resumeFirstTraining() }
                        }
                        Screen.CAMERA_TEST -> CameraTestScreen(g)
                        Screen.FINALE -> FinaleScreen(g)
                        Screen.WHY -> WhyScreen(g, WHY_SCRIPT) { g.finishWhyScene() }
                        Screen.VISIT -> g.visitScript.let { s -> WhyScreen(g, s, g.visitCount) { option -> g.finishVisitScene(option?.opensLanguages == true) } }
                        Screen.THALASSIA -> ThalassiaScreen(g)
                        Screen.LANGUAGES -> LanguagesScreen(g)
                        Screen.DOWNLOAD -> DownloadScreen(g)
                        // First run: no Back arrow (system Back follows the setup policy, MainActivity); from Settings: back there.
                        Screen.PASSPORT -> PassportFlow(g)
                        Screen.INPUT_MODE -> InputModeScreen(g)
                        Screen.START -> if (g.ticketError) DeclinedBooking(g) else g.upgradeDelivery?.let { UpgradeScreen(g, it) } ?: StartScreen(g)
                        Screen.DESTINATION -> DestinationScreen(g)
                        Screen.COVER -> g.newsPending?.let { StoryCaption(if (it.id == NEWS_THALASSIA) "queue_news" else "queue_news_world", stringResource(it.res), g.careerReady, g::finishNews) } ?: g.travel?.let { TravelSequence(g, it) } ?: queue?.let { QueueScreen(g, it) } ?: CoverScreen(g)
                        Screen.INTERVIEW -> if (g.fatherAway) StoryCaption("cut_father_away",
                            stringResource(R.string.father_away), true, g::finishFatherAway) else InterviewScreen(g)
                        Screen.RESULT -> g.city?.let { CityScreen(g, it) } ?: ResultScreen(g)
                        Screen.SUMMARY -> g.city?.let { CityScreen(g, it) } ?: g.homeVignette?.let { HomeVignette(g, it) } ?: SummaryScreen(g)
                        Screen.SETTINGS -> SettingsScreen(g)
                        Screen.LICENSES -> LicensesScreen(g)
                        Screen.MUSIC -> MusicScreen(g)
                    } }
                }
            }
            if (g.screen == Screen.RESULT && g.usaCompletion) AlertDialog(
                onDismissRequest = {},
                text = { Text(stringResource(R.string.usa_completion)) },
                confirmButton = { TextButton({ g.acknowledgeUsaCompletion() }, enabled = g.careerReady) {
                    Text(stringResource(R.string.continue_btn))
                } },
            )
            g.confirmDiscard?.let { discard -> AlertDialog(
                onDismissRequest = { g.confirmDiscard = null },
                shape = Square, containerColor = C.card, titleContentColor = C.text, textContentColor = C.dim,
                title = { Text(stringResource(R.string.discard_title)) },
                confirmButton = { TextButton({ g.confirmDiscard = null; discard() }, enabled = g.careerReady) { Text(stringResource(R.string.discard_confirm), color = C.red) } },
                dismissButton = { TextButton({ g.confirmDiscard = null; g.continueInterrupted() }, enabled = g.careerReady) { Text(stringResource(R.string.discard_continue), color = C.text) } },
            ) }
            CareerSaveBlocker(g)
            SongToast(g)
        }
    }
    }
}

/** UI languages written right to left (Arabic, when it ships again). */
private val RTL_UI = setOf("ar")

// Before the finale the machines are the point; after it, the player knows Free AI.

@Composable private fun StartScreen(g: Game) = Backdrop("start_bg") {
    var keeping by remember { mutableStateOf(false) } // the handshake beat the finale plays before "Keep helping"
    if (keeping) { StoryTurns(g, 100, EPILOGUE_ART[7], FINALE_KEEP_TURNS) { keeping = false; g.keepHelping() }; return@Backdrop }
    // Every few seconds the tagline flickers: scrambled, or for a blink the truth.
    val afterStory = g.act != Act.STORY
    val walkedAway = g.career.snapshot?.walkedAway == true // no Free AI pitch, no upgrade goal
    val base = stringResource(if (afterStory && !walkedAway) R.string.tagline_after else R.string.tagline)
    val truth = stringResource(R.string.tagline_truth)
    var tagline by remember(base) { mutableStateOf(base) }
    val animate = Motion.allowed()
    LaunchedEffect(animate) {
        while (animate) {
            delay(kotlin.random.Random.nextLong(3000, 7000))
            tagline = if (kotlin.random.Random.nextInt(4) == 0) truth else corrupt(base)
            delay(kotlin.random.Random.nextLong(90, 220))
            tagline = base
        }
    }
    Box(Modifier.fillMaxSize().scanlines())
    var picking by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    LaunchedEffect(g.careerReady, g.career.snapshot) { g.assignUpgradeDrops(); g.stampMessages() }
    Gutter(scrollable = afterStory || g.interruptedAttempt != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            Spacer(Modifier.weight(1f))
            PlaneButton(g) { picking = true }
            if (showMusicMenu(g.heardSongs)) IconButton({ g.screen = Screen.MUSIC }, Modifier.clip(Square).background(Color(0x99000A24)).border(1.dp, C.line, Square)) {
                Icon(Icons.Default.MusicNote, stringResource(R.string.music_title), tint = C.text, modifier = Modifier.size(28.dp))
            }
            if (g.messages.isNotEmpty() || walkedAway) MessagesButton(g.unread.size) { reading = true }
            // Owner: the dim gear was hard to find over the menu art; full white on a glass disc.
            IconButton({ g.screen = Screen.SETTINGS }, Modifier.clip(Square).background(Color(0x99000A24)).border(1.dp, C.line, Square)) {
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
        if (g.act == Act.FREE) waitingCourierCountry(g.career.snapshot)?.let { id ->
            Country.find(id)?.let { c ->
                Text(stringResource(R.string.menu_courier_waiting, c.uiName(uiLang())), color = C.green, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }
        }
        Panel {
            BorderLine(Modifier.fillMaxWidth()) { Text(if (afterStory) rankName(g.career)
                else stringResource(R.string.start_year), style = display(16, 2.0), color = C.text,
                modifier = Modifier.weight(1f)) }
            val clean = cleanCareer(g.career.snapshot)
            Text(stringResource(if (g.thalassiaSeen) R.string.start_blurb_home else if (walkedAway) R.string.start_blurb_walked else if (afterStory) when (harvestTier(g.career.snapshot, g.act)) {
                1 -> if (clean) R.string.start_blurb_tier_1_clean else R.string.start_blurb_tier_1
                2 -> if (clean) R.string.start_blurb_tier_2_clean else R.string.start_blurb_tier_2
                3 -> if (clean) R.string.start_blurb_tier_3_clean else R.string.start_blurb_tier_3
                else -> R.string.start_blurb_after } else R.string.start_blurb), color = C.dim,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            Spacer(Modifier.height(16.dp))
            if (g.thalassiaOffered) {
                var warn by remember { mutableStateOf(false) }
                if (warn) AlertDialog(
                    onDismissRequest = { warn = false },
                    shape = Square, containerColor = C.card, textContentColor = C.text,
                    text = { Text(stringResource(R.string.thal_warn)) },
                    confirmButton = { TextButton({ warn = false; g.openThalassia() }, enabled = g.careerReady) { Text(stringResource(R.string.thal_warn_go), color = C.red) } },
                    dismissButton = { TextButton({ warn = false }) { Text(stringResource(R.string.thal_warn_wait), color = C.text) } },
                )
                PrimaryButton(stringResource(R.string.thal_trip), enabled = g.careerReady) { warn = true }
                Spacer(Modifier.height(12.dp))
            }
            PrimaryButton(stringResource(when (g.act) { Act.FREE -> R.string.begin_mission; Act.LEFT -> R.string.start_replay_story; Act.STORY -> R.string.begin }),
                enabled = g.careerReady) { g.beginMission() }
            // After "Leave" the way back into act 2 stays open (PRD §3a).
            if (g.act == Act.LEFT) TextButton({ keeping = true }, Modifier.align(Alignment.CenterHorizontally), enabled = g.careerReady) {
                Text(stringResource(R.string.finale_keep), color = C.dim)
            }
        }
        Spacer(Modifier.height(16.dp))
        RecoveryPanel(g)
        val c = g.career
        // Rank and stats only exist after Act 1 is done.
        if (afterStory) Text(listOfNotNull(stringResource(R.string.machine_progress, c.sessions, c.points), stringResource(R.string.stat_cleared, c.cleared),
                stringResource(R.string.stat_streak, c.streak), c.toNext?.takeUnless { walkedAway }?.let { pluralStringResource(R.plurals.points_to_upgrade, it, it) },
                c.broken.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.stat_broken, it, it) }).joinToString(" · "),
            color = C.dim, fontSize = 12.sp, letterSpacing = 1.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
        g.career.snapshot?.daysAway?.takeIf { it > 0 && g.act == Act.FREE }?.let {
            Text(stringResource(R.string.stat_day, it), color = C.dim, fontSize = 12.sp, letterSpacing = 1.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Feature(stringResource(R.string.feature_voice), Icons.Default.Mic, Icons.Default.Visibility)
            Feature(stringResource(R.string.feature_ai), Icons.Default.Memory)
        }
    }
    if (picking) CountryPicker(g) { picking = false }
    if (!reading) g.unread.firstOrNull()?.let { MessageBanner(g, it) { reading = true } }
    if (reading) MessagesScreen(g) { reading = false }
}

/** The music player: every song in order; heard ones play, the rest show a lock. Back returns to the menu, whose soundtrack takes over. */
@Composable private fun MusicScreen(g: Game) = Box(Modifier.fillMaxSize().background(C.bg)) {
    Gutter {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ g.go(Screen.START) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = C.text) }
            Column {
                Text(stringResource(R.string.music_title), style = display(22, 2.0))
                Text("The Atomic Songbirds", color = C.dim, fontSize = 12.sp, letterSpacing = 1.sp)
            }
        }
        val ctx = LocalContext.current
        val installed = remember { Packs.installedLanguages(ctx) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(12.dp))
            for (song in SONGS) {
                val heard = song.key in g.heardSongs
                // A heard pack song whose pack is gone: its title, greyed, not playable.
                val playable = songPlayable(song, g.heardSongs, installed)
                val hint = songPackHint(song, installed)
                val current = g.playerSong?.key == song.key
                val playing = current && !g.playerPaused
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(Square).background(C.card.copy(alpha = 0.92f))
                    .border(1.dp, if (current) Hud.cyan else C.line, Square)
                    .then(if (playable) Modifier.clickable { g.playSong(song) } else Modifier).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (!heard) Icons.Default.Lock else if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        stringResource(if (!heard) R.string.music_locked else if (playing) R.string.music_pause else R.string.music_play),
                        tint = if (current) Hud.cyan else if (playable) C.text else C.dim)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(if (heard) song.title else "???", color = if (current) Hud.cyan else if (playable) C.text else C.dim, fontSize = 16.sp)
                        if (hint != null) Text(stringResource(R.string.music_pack_hint, Language.of(hint).uiName(uiLanguage(ctx))), color = C.dim, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** The song that just started: a glowing note and its title top-end, ~3 s, over every screen; not interactive. Static under Reduce motion. */
@Composable private fun SongToast(g: Game) {
    val song = g.nowPlaying ?: return
    val motion = Motion.allowed()
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(g.songToken, motion) {
        if (motion) { alpha.snapTo(0f); alpha.animateTo(1f, tween(500)); delay(2000); alpha.animateTo(0f, tween(500)) }
        else { alpha.snapTo(1f); delay(3000); alpha.snapTo(0f) }
    }
    val pulse = if (motion) rememberInfiniteTransition(label = "songGlow")
        .animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "songPulse").value else 0.8f
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), contentAlignment = Alignment.TopEnd) {
        if (alpha.value > 0f) Row(Modifier.graphicsLayer { this.alpha = alpha.value }.clip(Square).background(Hud.glass)
            .border(1.dp, C.line, Square).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).drawBehind {
                drawCircle(Brush.radialGradient(listOf(Hud.cyan.copy(alpha = 0.55f * pulse), Color.Transparent)), radius = size.minDimension)
            }, contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = Hud.cyan, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(8.dp))
            Text(song.title, color = C.text, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

/** The menu's Messages entry: an envelope on a glass disc with an unread count. */
@Composable private fun MessagesButton(unread: Int, onClick: () -> Unit) = Box {
    IconButton(onClick, Modifier.clip(Square).background(Color(0x99000A24)).border(1.dp, C.line, Square)) {
        Icon(Icons.Default.Email, stringResource(R.string.messages), tint = C.text, modifier = Modifier.size(28.dp))
    }
    if (unread > 0) Text(unread.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape).background(C.red).padding(horizontal = 6.dp, vertical = 1.dp))
}

/** The stamp for a message, in the UI language; empty until stamped. */
@Composable private fun messageStamp(m: StoryMessage): String {
    val at = m.at ?: return ""
    val ctx = LocalContext.current
    val loc = java.util.Locale.forLanguageTag(uiLang())
    return messageTime(at, System.currentTimeMillis(), loc, android.text.format.DateFormat.is24HourFormat(ctx),
        stringResource(R.string.msg_yesterday), android.text.format.DateFormat.getBestDateTimePattern(loc, "dMMM"))
}

/** The text with its country (if any) in the UI language, never the English name. */
@Composable private fun messageText(m: StoryMessage): String =
    stringResource(m.text, *listOfNotNull(m.country?.let { Country.find(it)?.uiName(uiLang()) }).toTypedArray())

/** [messageText], damaged after the true ending: redacted words in red monospace. */
@Composable private fun messageContent(m: StoryMessage, damaged: Boolean): androidx.compose.ui.text.AnnotatedString {
    val text = messageText(m)
    if (!damaged) return androidx.compose.ui.text.AnnotatedString(text)
    val marker = stringResource(R.string.msg_redacted)
    return androidx.compose.ui.text.buildAnnotatedString {
        for ((t, r) in redact(text, m.id.hashCode(), marker))
            if (r) { pushStyle(androidx.compose.ui.text.SpanStyle(color = C.red, fontFamily = FontFamily.Monospace)); append(t); pop() } else append(t)
    }
}

/** Slides in from the top once per launch when a text is unread; a tap opens Messages. */
@Composable private fun MessageBanner(g: Game, m: StoryMessage, open: () -> Unit) {
    val view = LocalView.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (g.messageBannerShown) return@LaunchedEffect
        g.messageBannerShown = true
        g.playSfx(R.raw.sfx_message)
        if (g.haptics) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        shown = true
        delay(15000)
        shown = false
    }
    androidx.compose.animation.AnimatedVisibility(shown, enter = androidx.compose.animation.slideInVertically { -it },
        exit = androidx.compose.animation.slideOutVertically { -it }) {
        Column(Modifier.safeDrawingPadding().padding(12.dp).fillMaxWidth().clip(Square)
            .background(C.card).border(1.dp, C.line, Square).clickable { shown = false; open() }.padding(14.dp)) {
            Text(stringResource(R.string.new_message), color = C.dim, fontSize = 11.sp, letterSpacing = 1.sp)
            Text(stringResource(m.sender), color = C.text, fontWeight = FontWeight.Medium)
            Text(messageContent(m, g.thalassiaSeen), color = C.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** SMS-style: conversations by sender; a thread shows its texts as left-aligned bubbles and is read once opened. */
@Composable private fun MessagesScreen(g: Game, close: () -> Unit) {
    var sender by remember { mutableStateOf<Int?>(null) }
    BackHandler { if (sender != null) sender = null else close() }
    val threads = g.messages.groupBy { it.sender }
    Column(Modifier.fillMaxSize().background(C.bg).safeDrawingPadding().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ if (sender != null) sender = null else close() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = C.text)
            }
            Text(sender?.let { stringResource(it) } ?: stringResource(R.string.messages), style = display(22, 1.5))
        }
        Spacer(Modifier.height(12.dp))
        val open = sender
        if (open == null) {
            if (threads.isEmpty()) Text(stringResource(R.string.no_messages), color = C.dim)
            for ((who, texts) in threads.entries.sortedByDescending { e -> e.value.maxOf { it.at ?: Long.MAX_VALUE } }) {
                val unread = texts.count { it.id !in g.readMessages }
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(Square).background(C.card)
                    .clickable { sender = who }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(who), color = C.text, fontWeight = FontWeight.Medium)
                        Text(messageContent(texts.last(), g.thalassiaSeen), color = C.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(messageStamp(texts.last()), color = C.dim, fontSize = 12.sp)
                        if (unread > 0) Text(unread.toString(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp).clip(CircleShape).background(C.red).padding(horizontal = 7.dp, vertical = 2.dp))
                    }
                }
            }
            // Below the threads and behind a confirmation: it was one stray tap away at the top (owner's phone, 2026-10-12).
            if (g.career.snapshot?.walkedAway == true) {
                var confirmWrite by remember { mutableStateOf(false) }
                Spacer(Modifier.height(16.dp))
                PrimaryButton(stringResource(R.string.msg_write_marek), g.careerReady) { confirmWrite = true }
                if (confirmWrite) AlertDialog(
                    onDismissRequest = { confirmWrite = false },
                    shape = Square, containerColor = C.card, titleContentColor = C.text, textContentColor = C.dim,
                    title = { Text(stringResource(R.string.msg_write_marek)) },
                    text = { Text(stringResource(R.string.msg_write_marek_text)) },
                    confirmButton = { TextButton({ confirmWrite = false; g.returnToMarek(); close() }) { Text(stringResource(R.string.msg_write_send), color = C.ivory) } },
                    dismissButton = { TextButton({ confirmWrite = false }) { Text(stringResource(R.string.reset_cancel), color = C.text) } },
                )
            }
        } else {
            val texts = threads[open].orEmpty()
            LaunchedEffect(open) { g.markMessagesRead(texts.map { it.id }) }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                for (m in texts) Column(Modifier.padding(end = 48.dp, bottom = 8.dp).clip(Square)
                        .background(C.card).border(1.dp, C.line, Square).then(if (g.thalassiaSeen && m === texts.last()) Modifier.glitch() else Modifier).padding(14.dp)) {
                    Text(messageContent(m, g.thalassiaSeen), color = C.text)
                    Text(messageStamp(m), color = C.dim, fontSize = 11.sp, modifier = Modifier.align(Alignment.End).padding(top = 4.dp))
                }
            }
        }
    }
}

/** The booking app title; Act 1 keeps the research group unnamed. */
@Composable private fun BookingBar(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        BorderLine { Text(title, style = display(24, 1.5)) }
        subtitle?.let { Text(it, color = C.dim, fontSize = 12.sp, letterSpacing = 1.5.sp) }
    }
}

/** `HARVEST · TAINTED · 1.2%` (the app's own telemetry on the poison tier); null outside act 2. */
@Composable private fun harvestLine(g: Game): String? {
    if (g.act != Act.FREE) return null
    val s = g.career.snapshot
    val tier = harvestTier(s, g.act)
    val pct = String.format(coverLocale(uiLang()), "%.1f", harvestPercent(s?.doses ?: 0))
    return stringResource(R.string.harvest_line, stringResource(HARVEST_TIERS[tier]), pct)
}
private val HARVEST_LOGS = listOf(R.string.booking_log_tier_1, R.string.booking_log_tier_2, R.string.booking_log_tier_3)
private val HARVEST_TIERS = listOf(R.string.harvest_tier_0, R.string.harvest_tier_1, R.string.harvest_tier_2, R.string.harvest_tier_3)

/** The old menu passport disc, now a plane with the selected country's flag. */
@Composable private fun PlaneButton(g: Game, onClick: () -> Unit) {
    val playable = g.playable()
    val chosen = playable.firstOrNull { it.id == g.destChoice(playable) }
    val label = chosen?.uiName(uiLang()) ?: stringResource(R.string.booking_anywhere)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 160.dp)) {
        IconButton(onClick, Modifier.clip(Square).background(Color(0x99000A24)).border(1.dp, C.line, Square), enabled = g.careerReady) {
            Box(Modifier.fillMaxSize()) {
                Icon(Icons.Default.Flight, label, tint = C.text, modifier = Modifier.align(Alignment.Center).size(26.dp))
                Box(Modifier.align(Alignment.BottomEnd).padding(end = 2.dp, bottom = 4.dp)) {
                    if (chosen != null) Flag(chosen, Modifier.size(width = 18.dp, height = 12.dp))
                    else Text("?", color = C.navy, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(Square).background(C.cyan).padding(horizontal = 3.dp).clearAndSetSemantics {})
                }
            }
        }
        Text(label, color = C.text, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.clearAndSetSemantics {})
    }
}

/** The USA stays visible as a teaser even without its language pack; booking eligibility is separate. */
internal fun countryPickerRows(playable: List<Country>, nationalityId: String?, ui: String,
    snapshot: CareerSnapshot?, story: Boolean): BookingRows = bookingRows(
    (playable + listOfNotNull(Country.of("usa"), snapshot?.lastPassedCountry?.let(Country::find))).distinctBy { it.id },
    nationalityId?.takeIf { it != "usa" || !countryPickerUsaLocked(snapshot, story, nationalityId) }, ui)

internal fun countryPickerUsaLocked(snapshot: CareerSnapshot?, story: Boolean, nationalityId: String? = null) =
    if (story) false
    else snapshot?.act != Act.FREE || snapshot.usaUnlocked != true

internal data class CountryPickerRowState(val here: Boolean, val clears: Int, val enabled: Boolean)

internal fun countryPickerRowState(id: String, currentCountry: String?, clears: Int, bookable: Boolean) =
    CountryPickerRowState(id == currentCountry, clears, bookable && id != currentCountry)

/** Random first, then countries with their PASSED badges; restricted flights stay disabled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CountryPicker(g: Game, onClose: () -> Unit) {
    val ui = uiLang()
    val ctx = LocalContext.current
    val snapshot = g.career.snapshot
    val packArt = remember(g.languages, snapshot) { Packs.art(ctx).keys }
    val countries = remember(g.languages, packArt, ui) {
        playableCountries(g.languages, packArt).sortedBy { it.uiName(ui) }
    }
    val usaLocked = countryPickerUsaLocked(snapshot, g.storyAct, g.identity?.nationality?.id)
    var showUsaNote by rememberSaveable { mutableStateOf(false) }
    val currentCountry = if (g.storyAct) snapshot?.storyLastCountry else snapshot?.lastPassedCountry
    val available = g.identity?.let { missionCountries(snapshot, countries, it, g.storyAct) }.orEmpty()
        .filter { it.id != currentCountry }
    val d = g.destChoice(available)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onClose, sheetState = sheetState, containerColor = C.card,
        modifier = Modifier.statusBarsPadding().fillMaxHeight(0.88f)) {
        Text(stringResource(R.string.picker_title), style = display(18, 1.5), modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
        if (lastFlightWarning(snapshot)) Text(stringResource(R.string.picker_last_flight), color = C.red,
            fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        LazyColumn(Modifier.weight(1f).selectableGroup(), contentPadding = PaddingValues(bottom = 24.dp)) {
            @Composable fun row(id: String, label: String, lead: @Composable () -> Unit, clears: Int = 0) {
                val left = flaggedClears(snapshot, id)
                val closedLeft = closedTripsLeft(snapshot, id, countries)
                val lockedUsa = id == "usa" && usaLocked
                val bonusCountry = Country.find(id)
                val bonusLanguages = bonusCountry?.let {
                    newLanguageBonuses(snapshot, eligibleAirports(it, g.languages, packArt))
                }.orEmpty()
                val bonusLabel = if (bonusLanguages.isEmpty()) null else
                    stringResource(R.string.picker_new_language) + if (bonusCountry!!.airports.map { it.lang }.distinct().size > 1)
                        " · " + bonusLanguages.joinToString(", ") { Language.of(it).uiName(ui) } else ""
                val courierLabel = if (g.act == Act.FREE && snapshot != null && snapshot.upgradeDrops.any { (n, c) -> c == id && n !in snapshot.cutscenesSeen })
                    stringResource(R.string.picker_courier) else null
                val hereLabel = stringResource(R.string.picker_you_are_here)
                val scrambledLabel = if (g.act == Act.FREE && id != "random" &&
                    referralGaugeScrambled(id, ImplantPerks.fromUpgrades(g.career.delivered)))
                    stringResource(R.string.picker_scrambled) else null
                val state = countryPickerRowState(id, currentCountry, clears, g.careerReady &&
                    (if (id == "random") available.isNotEmpty() else available.any { it.id == id }))
                val here = state.here
                val enabled = state.enabled
                val passedLabel = if (state.clears <= 0) null else if (state.clears == 1) stringResource(R.string.picker_passed)
                    else stringResource(R.string.picker_passed_times, state.clears)
                val accessibility = if (here) Modifier.clearAndSetSemantics {
                    contentDescription = listOfNotNull(Country.of(id).uiName(ui), hereLabel, passedLabel, scrambledLabel, bonusLabel).joinToString(", ")
                    disabled()
                } else Modifier
                val interaction = if (lockedUsa && !here && left <= 0) Modifier.clickable {
                    showUsaNote = true
                } else Modifier.selectable(selected = id == d && enabled, enabled = enabled, role = Role.RadioButton) {
                    g.pickDest(id); onClose()
                }
                Row(Modifier.fillMaxWidth().then(accessibility).then(interaction).background(if (id == d && enabled) C.ivory.copy(alpha = 0.16f) else Color.Transparent)
                    .padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 30.dp, height = 20.dp), contentAlignment = Alignment.Center) { lead() }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, color = if (enabled) C.text else C.dim, fontSize = 16.sp)
                        if (closedLeft > 0 && !here) Text(pluralStringResource(R.plurals.booking_closed, closedLeft, closedLeft), color = C.red, fontSize = 12.sp)
                        if (bonusLabel != null) Text(bonusLabel, color = C.green, fontSize = 12.sp)
                        if (courierLabel != null) Text("▣ $courierLabel", color = C.ivory, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    if (scrambledLabel != null) Text("≋", color = C.dim, fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 6.dp).clearAndSetSemantics {
                            contentDescription = scrambledLabel
                        })
                    if (here) {
                        Text(hereLabel, color = C.dim, fontSize = 12.sp)
                        Icon(Icons.Default.Place, null, tint = C.dim,
                            modifier = Modifier.padding(start = 8.dp).size(18.dp))
                    } else {
                        if (left > 0) Text(stringResource(R.string.booking_flagged, left), color = C.red, fontSize = 12.sp)
                        else if (lockedUsa) Text(stringResource(R.string.picker_not_recommended)
                            .uppercase(java.util.Locale.forLanguageTag(ui)), color = C.dim, fontSize = 12.sp)
                        if (!enabled) Icon(Icons.Default.Lock, null, tint = C.dim,
                            modifier = Modifier.padding(start = 8.dp).size(18.dp))
                    }
                    if (passedLabel != null) Text(passedLabel, color = C.green, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 8.dp))
                }
                if (lockedUsa && !here && left <= 0 && showUsaNote) Text(stringResource(R.string.usa_si_note),
                    color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                if (left > 0) Text(stringResource(R.string.booking_flagged_note), color = C.dim, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            item(key = "random") {
                row("random", stringResource(R.string.booking_anywhere), {
                    Text("?", color = C.text, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.clearAndSetSemantics {})
                })
                HorizontalDivider(color = C.line)
            }
            val rows = countryPickerRows(countries, g.identity?.nationality?.id, ui, snapshot, g.storyAct)
            rows.home?.let { c -> item(key = c.id) {
                row(c.id, if (c.id == currentCountry) c.uiName(ui) else stringResource(R.string.booking_coming_home) + " · " + c.uiName(ui),
                    { Flag(c, Modifier.fillMaxSize()) }, passedCount(snapshot, c.id))
            } }
            items(rows.others, key = { it.id }) { c ->
                row(c.id, c.uiName(ui), { Flag(c, Modifier.fillMaxSize()) }, passedCount(snapshot, c.id))
            }
        }
    }
}

/** One itinerary field of the booked trip: its label, the real value, and the slot-machine frames ending on it. */
private class RollSlot(val label: String, val frames: List<String>) { val real get() = frames.last() }

/**
 * The booked flight as a show: request log, ticket printing, then the itinerary dice. Presentation only: the trip was rolled
 * once before opening this screen. Both acts use this sequence; Thalassia training bypasses it.
 * Always plays in full except with animations off (then the end state at once).
 */
@Composable private fun DestinationScreen(g: Game) = Box(Modifier.fillMaxSize().background(C.bg)) {
    if (g.firstCareerTraining) {
        LaunchedEffect(g.careerReady) { g.resumeFirstTraining() }
        return@Box
    }
    val ctx = LocalContext.current
    val ui = uiLang()
    val s = g.story
    val view = LocalView.current
    val animate = Motion.allowed()
    val paid = g.act == Act.FREE && !g.training // the app pays free-play flights, not training
    // A Solvane Collection reservation; hosts' homes and rentals get none, and neither does a flight home (its stay is the
    // trip being returned from, already over; seen on the phone).
    val hotel = paid && isHotelStay(s.stayed) && s.kind != TripKind.HOMECOMING
    val solvane = "Solvane ${s.city}"
    val paidBy = stringResource(R.string.booking_paid_by) // never names Free AI in the UI (owner)
    LaunchedEffect(hotel) { if (hotel) g.noteSolvane() }
    val lines = listOf(stringResource(R.string.booking_log_1)) +
        stringResource(R.string.booking_log_3, coverIsolate(g.airport.uiName(ui), ui)) +
        listOfNotNull(harvestTier(g.career.snapshot, g.act).takeIf { it >= 1 }?.let { stringResource(HARVEST_LOGS[it - 1]) }) +
        stringResource(R.string.booking_log_5) +
        listOfNotNull(stringResource(R.string.booking_log_hotel, solvane).takeIf { hotel }, stringResource(R.string.booking_log_paid).takeIf { paid })
    val full = lines.joinToString("\n")
    val slots = remember(s, ui) {
        val rnd = kotlin.random.Random.Default
        val groups = COVER_VALUE_IDS.values.groupBy { ctx.resources.getResourceEntryName(it).split('_')[2] }
        fun fill(id: Int) = coverIsolate(Assessment.fill(ctx.getString(id), s, ui), ui)
        fun slot(label: Int, group: String, english: String) = RollSlot(ctx.getString(label),
            decoyCycle(coverIsolate(coverValue(english, s, ui, ctx::getString), ui), groups.getValue(group).map(::fill), 5, rnd))
        val nf = java.text.NumberFormat.getIntegerInstance(coverLocale(ui))
        listOfNotNull(
            slot(R.string.booking_f_purpose, "purpose", s.purpose),
            RollSlot(ctx.getString(R.string.booking_f_days), decoyCycle(nf.format(s.days), (2..21).map(nf::format), 5, rnd)),
            slot(R.string.booking_f_stay, "stay", s.stayed),
            if (s.hasHost) slot(R.string.booking_f_host, "host", s.host) else null,
        )
    }
    fun tick() { if (g.haptics) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
    val typed = remember { mutableIntStateOf(if (animate) 0 else full.length) }
    var logDone by remember { mutableStateOf(!animate) }
    val print = remember { androidx.compose.animation.core.Animatable(if (animate) 0f else 1f) }
    val shimmer = remember { androidx.compose.animation.core.Animatable(if (animate) 0f else 1f) }
    var rolling by remember { mutableStateOf(!animate) }
    val locked = remember { mutableIntStateOf(if (animate) 0 else slots.size) }
    val frame = remember { mutableStateOf("") }
    val done by remember { derivedStateOf { locked.intValue >= slots.size } }
    LaunchedEffect(Unit) {
        if (!animate) return@LaunchedEffect
        var start = 0
        for (line in lines) {
            tick()
            for (c in 1..line.length) { typed.intValue = start + c; delay(7) }
            start += line.length + 1
            delay(80)
        }
        logDone = true; tick()
        delay(300)
        g.playSfx(R.raw.sfx_scan, Sfx.SCAN_GAIN)
        print.animateTo(1f, tween(600))
        shimmer.animateTo(1f, tween(900))
        rolling = true
        delay(1100) // the die tumbles
        for ((i, slot) in slots.withIndex()) {
            for (f in slot.frames.dropLast(1)) { frame.value = f; delay(400L / slot.frames.size.coerceAtLeast(2)) }
            locked.intValue = i + 1; tick()
            delay(120)
        }
    }
    Gutter {
        BookingBar(stringResource(R.string.booking_title), harvestLine(g))
        val scroll = rememberScrollState()
        // Each locked row (and the dice) grows the content: follow it so the last row never hides behind CONTINUE.
        LaunchedEffect(locked.intValue, rolling) { delay(60); scroll.animateScrollTo(scroll.maxValue) }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
            val seat = lines.lastOrNull().orEmpty()
            Column(Modifier.fillMaxWidth().clip(Square).background(Color(0xFF000614)).padding(14.dp)
                .clearAndSetSemantics { if (logDone) { contentDescription = seat; liveRegion = LiveRegionMode.Polite } }) {
                BookingLog(full, typed, animate && !logDone)
                Spacer(Modifier.height(10.dp))
                if (logDone) androidx.compose.material3.LinearProgressIndicator({ 1f }, Modifier.fillMaxWidth(), color = BOOKING_CYAN)
                else androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth(), color = BOOKING_CYAN)
            }
            Spacer(Modifier.height(14.dp))
            // The pass slides out of the top edge like paper from a printer, then a hologram sheen crosses it once.
            Box(Modifier.fillMaxWidth().clipToBounds()) {
                Column(Modifier.fillMaxWidth().graphicsLayer { translationY = -size.height * (1f - print.value) }
                    .clip(Square).hologram { shimmer.value }.background(C.paper)) {
                    Box(Modifier.fillMaxWidth().height(5.dp).background(C.red))
                    Column(Modifier.padding(18.dp)) {
                    val dim = C.inkDim
                    Text(stringResource(R.string.booking_pass), color = dim, fontSize = 12.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.booking_from), color = dim, fontSize = 12.sp)
                    // A homecoming flies back from the visited city; a visit leaves from the passport's home town.
                    val from = if (g.story.kind == TripKind.HOMECOMING) "${g.story.city}, ${coverNation(g.story.from, ui)}"
                        else "${departureCity(s.from, g.career.snapshot)}, ${coverNation(s.from, ui)}"
                    Text(coverIsolate(from, ui), color = C.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.booking_to), color = dim, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Flag(g.country, Modifier.size(width = 33.dp, height = 22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(g.country.uiName(ui), color = C.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(g.airport.iata, color = C.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    DottedRule(C.ink.copy(alpha = 0.4f))
                    Text(coverIsolate(g.airport.uiName(ui), ui), color = dim, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    if (paid && !hotel) Text(paidBy, color = dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    if (hotel) {
                        Spacer(Modifier.height(14.dp))
                        Text(stringResource(R.string.booking_hotel_res), color = dim, fontSize = 12.sp, letterSpacing = 2.sp)
                        Text(coverIsolate(solvane, ui), color = C.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                        Text(pluralStringResource(R.plurals.booking_nights, s.days, s.days), color = dim, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                        Text(stringResource(R.string.booking_hotel_code, reservationCode(g.career.snapshot?.nextAttemptId ?: 0L)), color = C.ink,
                            fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                        Text(paidBy, color = dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    }
                }
            }
            if (rolling) {
                // A small die; the line says "rolling", then "ready" (owner).
                TripDie(animate, Modifier.align(Alignment.CenterHorizontally))
                Text(stringResource(if (done) R.string.booking_ready else R.string.booking_rolling), color = C.dim, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                ItineraryRows(slots, locked, frame)
            }
        }
        if (done) PrimaryButton(stringResource(R.string.continue_btn)) { g.screen = Screen.COVER }
        CancelTrip(g)
    }
}

private val BOOKING_CYAN = C.cyan

/** After the true ending Begin mission still runs the request log, but it ends declined: nothing is booked, saved or created. Back or Cancel returns to the menu. */
@Composable private fun DeclinedBooking(g: Game) = Box(Modifier.fillMaxSize().background(C.bg)) {
    val ctx = LocalContext.current
    val ui = uiLang()
    val animate = Motion.allowed()
    val lines = listOf(stringResource(R.string.booking_log_1), stringResource(R.string.booking_log_3, coverIsolate(g.airport.uiName(ui), ui)))
    val declined = stringResource(R.string.booking_declined)
    val full = lines.joinToString("\n") + "\n"
    val typed = remember { mutableIntStateOf(if (animate) 0 else full.length) }
    var logDone by remember { mutableStateOf(!animate) }
    BackHandler { g.ticketError = false }
    LaunchedEffect(Unit) {
        if (!animate) return@LaunchedEffect
        var start = 0
        for (line in lines) {
            for (c in 1..line.length) { typed.intValue = start + c; delay(7) }
            start += line.length + 1
            delay(80)
        }
        delay(900); logDone = true
    }
    Gutter {
        BookingBar(stringResource(R.string.booking_title))
        Column(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().clip(Square).background(Color(0xFF000614)).padding(14.dp)
                .clearAndSetSemantics { if (logDone) { contentDescription = declined; liveRegion = LiveRegionMode.Polite } }) {
                BookingLog(full, typed, animate && !logDone)
                if (logDone) Text(declined, color = C.red, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                else Box(Modifier.fillMaxWidth().padding(top = 10.dp)) { androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth(), color = BOOKING_CYAN) }
            }
        }
        TextButton({ g.ticketError = false }, Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.booking_cancel), color = C.dim) }
    }
}

/** Short, skippable travel; the current step guards timer/tap races. */
@Composable private fun TravelSequence(g: Game, step: TravelStep) {
    val ctx = LocalContext.current
    val animate = Motion.allowed()
    val caption = if (step == TravelStep.FLIGHT) stringResource(R.string.travel_flight)
        else stringResource(R.string.travel_arrivals, coverIsolate(g.airport.uiName(uiLang()), uiLang()))
    val img = rememberAsset(if (step == TravelStep.FLIGHT) "cut_plane_day" else g.airport.art)
    val drift = remember(step, animate) { androidx.compose.animation.core.Animatable(-0.015f) }
    val bob = remember(step, animate) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(step, animate) {
        delay(travelDurationMillis(step, animate))
        g.advanceTravel(step)
    }
    LaunchedEffect(step, animate, drift) {
        if (animate && step == TravelStep.FLIGHT)
            drift.animateTo(0.015f, tween(3000, easing = androidx.compose.animation.core.LinearEasing))
    }
    LaunchedEffect(step, animate, bob) {
        if (animate && step == TravelStep.FLIGHT) {
            bob.animateTo(4f, tween(500))
            while (true) {
                bob.animateTo(-4f, tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                bob.animateTo(4f, tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            }
        }
    }
    BackHandler(enabled = g.screen == Screen.COVER && g.travel != null && !g.navigationBlocked) { g.leaveTravel() }
    Box(Modifier.fillMaxSize().clipToBounds().background(C.bg)
        .clickable(onClickLabel = stringResource(R.string.continue_btn)) { g.advanceTravel(step) }) {
        if (img != null) Image(img, null, Modifier.fillMaxSize().graphicsLayer {
            if (step == TravelStep.FLIGHT && animate) {
                scaleX = 1.08f; scaleY = 1.08f
                translationX = size.width * drift.value
                translationY = bob.value.dp.toPx()
            }
        }, contentScale = ContentScale.Crop)
        Text(caption, color = C.text, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(24.dp)
                .background(C.bg.copy(alpha = 0.8f), Square).padding(12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** The queue narration, choice, and outcome; onboarding saves the choice before showing its outcome. */
@Composable private fun QueueScreen(g: Game, q: QueueScene) {
    val jolt = implantWakeOffset(g)
    Box(Modifier.fillMaxSize().graphicsLayer { translationX = jolt.dp.toPx() }) {
        Backdrop(q.encounter.art) { QueueContent(g, q) }
    }
}

@Composable private fun QueueContent(g: Game, q: QueueScene) {
    val ctx = LocalContext.current
    val ui = uiLang()
    val text = remember(q.encounter, ui) { q.narration(ui) }
    val labels = remember(q.encounter, ui) { q.labels(ui) }
    val fatherQueue = q.encounter.id == Encounters.FATHER
    val animate = Motion.allowed()
    val typed = remember(q.encounter, text, animate) {
        mutableIntStateOf(if (animate && q.selectedChoice == null) 0 else text.length)
    }
    val done by remember(typed, text) { derivedStateOf { typed.intValue >= text.length } }
    LaunchedEffect(typed, text) { while (typed.intValue < text.length) { delay(22); typed.intValue++ } }
    // The father's translation types out only once the implant is calibrated; on resume it is already there.
    val line = if (fatherQueue) stringResource(R.string.father_line) else ""
    val lineTyped = remember(line, animate) { mutableIntStateOf(if (animate && !g.fatherQueueCalibrated) 0 else line.length) }
    val lineDone by remember(lineTyped, line) { derivedStateOf { lineTyped.intValue >= line.length } }
    LaunchedEffect(g.fatherQueueCalibrated, line) {
        if (g.fatherQueueCalibrated) while (lineTyped.intValue < line.length) { delay(22); lineTyped.intValue++ }
    }
    // The outgoing queue remains composed during the fade but must not handle interview Back presses.
    BackHandler(enabled = g.screen == Screen.COVER && g.queue != null && !g.navigationBlocked) { g.leaveQueue() }
    val selected = q.selectedChoice
    val outcome = remember(q.encounter, selected, ui) { selected?.let { q.outcome(it, ui) } ?: "" }
    val outcomeTyped = remember(q.encounter, selected, outcome, animate) {
        mutableIntStateOf(if (animate) 0 else outcome.length)
    }
    // A tap outside the buttons completes whichever text is still typing.
    Box(Modifier.fillMaxSize().pointerInput(text, outcome, typed, outcomeTyped, line, lineTyped) { detectTapGestures {
        if (typed.intValue < text.length) typed.intValue = text.length
        else if (g.fatherQueueCalibrated && lineTyped.intValue < line.length) lineTyped.intValue = line.length
        else if (outcomeTyped.intValue < outcome.length) outcomeTyped.intValue = outcome.length
    } }) {
    Gutter(scrollable = true) {
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth().clip(Square).background(Color(0xE6000614)).padding(16.dp)
            .clearAndSetSemantics { if (done) { contentDescription = text; liveRegion = LiveRegionMode.Polite } }) {
            BookingLog(text, typed, animate && !done, size = 15)
        }
        Spacer(Modifier.height(14.dp))
        if (fatherQueue && done) FatherQueueTranslation(g, q, line, lineTyped, animate && !lineDone)
        if (selected == null) {
            if (done && (!fatherQueue || (g.fatherQueueCalibrated && lineDone))) for ((i, label) in labels.withIndex()) {
                PrimaryButton(label) { g.selectQueueChoice(i) }
                Spacer(Modifier.height(10.dp))
            }
        } else {
            val outcomeDone by remember(outcomeTyped, outcome) {
                derivedStateOf { outcomeTyped.intValue >= outcome.length }
            }
            LaunchedEffect(outcomeTyped, outcome) {
                while (outcomeTyped.intValue < outcome.length) { delay(22); outcomeTyped.intValue++ }
            }
            Column(Modifier.fillMaxWidth().clip(Square).background(Color(0xE6000614)).padding(16.dp)
                .clearAndSetSemantics {
                    if (outcomeDone) { contentDescription = outcome; liveRegion = LiveRegionMode.Polite }
                }) {
                BookingLog(outcome, outcomeTyped, animate && !outcomeDone, size = 15)
            }
            Spacer(Modifier.height(14.dp))
            if (outcomeDone) {
                if (fatherQueue) TrainingDeskEntry(g) { g.chooseInQueue(selected) }
                else PrimaryButton(stringResource(R.string.queue_to_desk)) { g.chooseInQueue(selected) }
            }
        }
    }
    }
}

/** Model and speech recovery controls remain available when the first training skips its cover. */
@Composable private fun TrainingDeskEntry(g: Game, enter: () -> Unit) {
    LaunchedEffect(g.destination.lang, g.earsReady) { g.preloadEars() }
    when {
        g.loadError != null -> PrimaryButton(stringResource(R.string.cover_models_failed)) { g.retryBrain() }
        g.earsNeedWhisper && g.earsReady != true ->
            PrimaryButton(stringResource(R.string.cover_download_speech)) { g.fetchWhisper() }
        else -> PrimaryButton(stringResource(if (g.ready && g.earsReady == true) R.string.queue_to_desk
            else R.string.cover_calibrating), enabled = g.careerReady && g.ready && g.earsReady == true, onClick = enter)
    }
}

/** The father speaks Thalassian with no text; the implant boots after, then the translation types out. */
@Composable private fun FatherQueueTranslation(g: Game, q: QueueScene, line: String, typed: State<Int>, typing: Boolean) {
    LaunchedEffect(Unit) { if (!g.fatherQueueCalibrated) { g.speakFather(); g.beginFatherQueueBoot() } }
    if (g.fatherQueueCalibrated) {
        Column(Modifier.fillMaxWidth().clip(Square).background(Color(0xE6000614)).padding(16.dp)
            .clearAndSetSemantics { if (!typing) { contentDescription = line; liveRegion = LiveRegionMode.Polite } }) {
            BookingLog(line, typed, typing, size = 15)
        }
        Spacer(Modifier.height(14.dp))
    }
    if (g.screen == Screen.COVER && g.queue == q) {
        g.implantBoot?.let { token ->
            val ctx = LocalContext.current
            if (g.watching && g.activeFaceRun == token && ctx.checkSelfPermission(android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)
                CameraFeed(g, Modifier.size(1.dp).clearAndSetSemantics {})
            ImplantBootOverlay(g, token)
        }
    }
}

/** The typed-out request log with a blinking cursor; reads [typed] here so only this small scope recomposes per character. */
@Composable private fun BookingLog(full: String, typed: State<Int>, cursor: Boolean, size: Int = 13) {
    var blink by remember { mutableStateOf(true) }
    LaunchedEffect(cursor) { while (cursor) { delay(500); blink = !blink } }
    // One tick per line as it starts typing (not per character); silent when shown already complete.
    val lineNo = if (cursor && typed.value > 0) full.take(typed.value).count { it == '\n' } + 1 else 0
    LaunchedEffect(lineNo) { if (lineNo > 0) uiSfx?.play(R.raw.sfx_type, 0.25f) }
    // The invisible full text reserves exactly the final height: no jump while typing, no empty box after (owner).
    val style = TextStyle(color = BOOKING_CYAN, fontFamily = FontFamily.Monospace, fontSize = size.sp, lineHeight = (size * 1.45f).sp)
    Box(Modifier.fillMaxWidth()) {
        Text(full + "█", style = style, modifier = Modifier.fillMaxWidth().alpha(0f))
        Text(full.take(typed.value) + if (cursor && blink) "█" else "", style = style, modifier = Modifier.fillMaxWidth())
    }
}

/** A dotted hairline (the perforation on the boarding pass, the rules of the typed form). */
@Composable private fun DottedRule(color: Color, modifier: Modifier = Modifier) =
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())))
    }

/** Rows appear one at a time; the active row flickers through decoys (hidden from screen readers) until it locks on the real value. */
@Composable private fun ItineraryRows(slots: List<RollSlot>, locked: State<Int>, frame: State<String>) {
    val n = locked.value
    // A typed form on the dark card: monospace caps labels, dotted rules (owner: less ivory).
    Column(Modifier.fillMaxWidth().clip(Square).background(C.card).border(1.dp, C.line, Square).padding(vertical = 4.dp)) {
        for (i in 0..minOf(n, slots.size - 1)) {
            val lock = i < n
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).then(if (lock) Modifier else Modifier.clearAndSetSemantics {})) {
                Text(slots[i].label.uppercase(), color = C.dim, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.5.sp,
                    modifier = Modifier.weight(0.38f).padding(top = 4.dp))
                Text(if (lock) slots[i].real else frame.value, color = if (lock) C.text else C.dim, fontSize = 15.sp,
                    fontWeight = if (lock) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(0.62f))
            }
            if (i < slots.size - 1) DottedRule(C.line, Modifier.padding(horizontal = 16.dp))
        }
    }
}

/** Explicit cancellation outside the story; changing the menu destination can also cancel. Back keeps the booking. */
@Composable private fun ColumnScope.CancelTrip(g: Game) {
    if (g.booked && !g.storyAct) TextButton({ g.cancelTrip() }, Modifier.align(Alignment.CenterHorizontally), enabled = g.careerReady) {
        Text(stringResource(R.string.booking_cancel), color = C.dim)
    }
}

/** A die that spins about one second, flickering through faces, then rests; [animate] false (animations off) shows it at rest. */
@Composable private fun TripDie(animate: Boolean, modifier: Modifier) {
    val faces = "\u2680\u2681\u2682\u2683\u2684\u2685"
    var face by remember { mutableStateOf(kotlin.random.Random.nextInt(6)) }
    val turn = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        if (!animate) return@LaunchedEffect
        launch { turn.animateTo(720f, tween(1000)) }
        repeat(10) { face = kotlin.random.Random.nextInt(6); delay(100) }
    }
    Text(faces[face].toString(), color = C.text, fontSize = 48.sp,
        modifier = modifier.graphicsLayer { rotationZ = turn.value }.clearAndSetSemantics {})
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
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(Square).background(C.amber.copy(alpha = 0.14f))
        .border(1.dp, C.amber, Square).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (pct == 0) Icons.Default.VolumeOff else Icons.Default.VolumeDown, null, tint = C.amber, modifier = Modifier.size(22.dp))
        Text(stringResource(if (pct == 0) R.string.volume_off else R.string.volume_low), color = C.amber, fontSize = 13.sp,
            modifier = Modifier.padding(start = 10.dp))
    }
}

internal enum class PrologueSpeaker {
    NARRATOR, STRANGER, VALERY, MAN_STRANGER, MAN_FRIEND, YOU,
    /** Airport staff: a stranger who is not Valery, in the neutral bubble style. */
    STAFF;

    val isValery: Boolean get() = this == STRANGER || this == VALERY

    fun label(stranger: String, you: String, manStranger: String = "A STRANGER", friend: String = "VALERY'S FRIEND", staff: String = "AIRPORT STAFF"): String = when (this) {
        NARRATOR -> ""
        STRANGER -> stranger
        VALERY -> "Valery"
        MAN_STRANGER -> manStranger
        MAN_FRIEND -> friend
        YOU -> you
        STAFF -> staff
    }
}
internal enum class PrologueSide { LEFT, RIGHT }
internal data class PrologueTurn(
    val speaker: PrologueSpeaker, val text: Int,
    val side: PrologueSide = if (speaker == PrologueSpeaker.YOU) PrologueSide.RIGHT else PrologueSide.LEFT,
    /** [text] is a plurals resource filled with the scene's count. */
    val plural: Boolean = false,
    /** [text] takes this span, formatted from its plurals resource, as %1$s. */
    val span: Span? = null,
)

@Composable private fun turnText(turn: PrologueTurn, count: Int) =
    if (turn.plural) pluralStringResource(turn.text, count, count)
    else if (turn.span != null) stringResource(turn.text, pluralStringResource(turn.span.plural, turn.span.n, turn.span.n))
    else stringResource(turn.text)

// Frame order is also the persisted career index. Turns deliberately are not persisted.
internal val PROLOGUE_FRAMES: List<List<PrologueTurn>> = listOf(
    listOf(PrologueTurn(PrologueSpeaker.NARRATOR, R.string.prologue_friend)),
    listOf(PrologueTurn(PrologueSpeaker.NARRATOR, R.string.prologue_holiday)),
    listOf(PrologueTurn(PrologueSpeaker.NARRATOR, R.string.prologue_stolen)),
    listOf(PrologueTurn(PrologueSpeaker.NARRATOR, R.string.prologue_smashed)),
    listOf(PrologueTurn(PrologueSpeaker.NARRATOR, R.string.prologue_embassy)),
    listOf(PrologueTurn(PrologueSpeaker.NARRATOR, R.string.prologue_valery_1),
        PrologueTurn(PrologueSpeaker.STRANGER, R.string.prologue_valery_first),
        PrologueTurn(PrologueSpeaker.YOU, R.string.prologue_valery_last),
        PrologueTurn(PrologueSpeaker.STRANGER, R.string.prologue_valery_angry)),
    listOf(PrologueTurn(PrologueSpeaker.STRANGER, R.string.prologue_valery_subscription_1),
        PrologueTurn(PrologueSpeaker.YOU, R.string.prologue_valery_subscription_2)),
    listOf(PrologueTurn(PrologueSpeaker.STRANGER, R.string.prologue_valery_uses_1),
        PrologueTurn(PrologueSpeaker.YOU, R.string.prologue_valery_uses_2)),
    listOf(PrologueTurn(PrologueSpeaker.STRANGER, R.string.prologue_valery_chip_1),
        PrologueTurn(PrologueSpeaker.YOU, R.string.prologue_valery_chip_2),
        PrologueTurn(PrologueSpeaker.STRANGER, R.string.prologue_valery_chip_3),
        PrologueTurn(PrologueSpeaker.NARRATOR, R.string.prologue_realize)),
    listOf(PrologueTurn(PrologueSpeaker.VALERY, R.string.prologue_valery_2)),
    listOf(PrologueTurn(PrologueSpeaker.YOU, R.string.prologue_valery_home_1), PrologueTurn(PrologueSpeaker.VALERY, R.string.prologue_valery_home_2)),
    listOf(PrologueTurn(PrologueSpeaker.YOU, R.string.prologue_valery_free_1), PrologueTurn(PrologueSpeaker.VALERY, R.string.prologue_valery_free_2), PrologueTurn(PrologueSpeaker.YOU, R.string.prologue_valery_free_3), PrologueTurn(PrologueSpeaker.VALERY, R.string.prologue_valery_free_4)),
    listOf(PrologueTurn(PrologueSpeaker.YOU, R.string.prologue_valery_data_1), PrologueTurn(PrologueSpeaker.VALERY, R.string.prologue_valery_data_2)),
    listOf(PrologueTurn(PrologueSpeaker.YOU, R.string.prologue_valery_chains_1), PrologueTurn(PrologueSpeaker.VALERY, R.string.prologue_valery_chains_2)),
    listOf(PrologueTurn(PrologueSpeaker.NARRATOR, R.string.prologue_implant)),
    listOf(PrologueTurn(PrologueSpeaker.VALERY, R.string.prologue_phone_1, side = PrologueSide.RIGHT)),
    listOf(PrologueTurn(PrologueSpeaker.NARRATOR, R.string.prologue_sameday)),
    listOf(PrologueTurn(PrologueSpeaker.NARRATOR, R.string.prologue_home))
)

/** A tap finishes typing, then reveals a turn, then advances the persisted frame. */
@Composable private fun IntroScreen(g: Game) {
    if (!g.careerReady || !g.inputDone) return
    var replayFrame by rememberSaveable { mutableIntStateOf(0) }
    val frame = (if (g.replayingPrologue) replayFrame else g.career.snapshot?.prologueFrame ?: 0).coerceIn(0, 17)
    val art = when (frame) {
        0 -> "prologue_0_arrival"
        1 -> "prologue_1_square"
        2 -> "prologue_2_crowd"
        3 -> "prologue_2b_smashed"
        4 -> "prologue_3_embassy"
        in 5..13 -> "prologue_4_valery"
        14 -> "prologue_6_trial"
        15 -> "prologue_7_phone"
        16 -> "prologue_5_airport"
        17 -> "prologue_5_airport"
        else -> null
    }
    StoryTurns(g, frame, art, PROLOGUE_FRAMES[frame], { turn -> g.prologueAudio(frame, turn) }) {
        if (frame == 17) g.afterIntro()
        else if (g.replayingPrologue) replayFrame++
        else g.advancePrologue(frame + 1)
    }
}

/** Shared storyboard renderer: frame saves, turn typing, cards, parallax and one TalkBack announcement. */
@Composable private fun StoryTurns(g: Game, frame: Int, art: String?, turns: List<PrologueTurn>,
    onTurn: (Int) -> Unit = {}, friendLabel: Int = R.string.epi_friend, count: Int = 0, onAdvance: () -> Unit) {
    val tilt = rememberParallax()
    var turnIndex by remember(frame) { mutableIntStateOf(0) }
    LaunchedEffect(frame, turnIndex) { onTurn(turnIndex) }
    val current = turns[turnIndex]
    val line = turnText(current, count)
    val you = stringResource(R.string.prologue_you)
    val stranger = stringResource(R.string.prologue_stranger)
    val manStranger = stringResource(R.string.epi_stranger)
    val friend = stringResource(friendLabel)
    val staff = stringResource(R.string.escort_staff)
    val announcement = if (current.speaker == PrologueSpeaker.NARRATOR) line else
        stringResource(R.string.prologue_speaker_line, current.speaker.label(stranger, you, manStranger, friend, staff), line)
    val ctx = LocalContext.current
    val animate = Motion.allowed()
    var typed by remember(frame, turnIndex, line, animate) {
        mutableIntStateOf(if (animate) 0 else line.length)
    }
    LaunchedEffect(frame, turnIndex, line, animate) {
        while (typed < line.length) { delay(9); typed = (typed + 1).coerceAtMost(line.length) }
    }
    val advance: () -> Unit = {
        if (typed < line.length) typed = line.length
        else if (turnIndex < turns.lastIndex) turnIndex++
        else onAdvance()
    }
    BackHandler { g.leaveThalassiaReplay() } // swallowed everywhere, except in a Settings replay of Thalassia (no-op elsewhere)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).clickable(
        enabled = g.careerReady, role = Role.Button,
        onClickLabel = stringResource(R.string.continue_btn), onClick = advance).semantics {
        // One stable full-turn announcement; typing and older cards do not repeat it.
        contentDescription = announcement
        liveRegion = LiveRegionMode.Polite
    }) {
        if (art != null) {
            Crossfade(art, Modifier.fillMaxSize(), tween(500), label = "story-art") { a ->
                val bitmap = rememberAsset(a)
                if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize().parallaxArt(tilt), contentScale = ContentScale.Crop)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0.35f to Color.Transparent, 1f to Color.Black)))
        }
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val contentWidth = (maxWidth - 40.dp).coerceAtLeast(0.dp)
            val scroll = rememberScrollState()
            var viewportHeight by remember { mutableIntStateOf(0) }
            var newestHeight by remember(frame, turnIndex) { mutableIntStateOf(0) }
            // Reserve each full line's size, so typing never shifts the stack.
            // A turn taller than the viewport starts at its first line, not its clipped ending.
            LaunchedEffect(frame, turnIndex, scroll.maxValue, viewportHeight, newestHeight) {
                val overflow = (newestHeight - viewportHeight).coerceAtLeast(0)
                scroll.scrollTo((scroll.maxValue - overflow).coerceAtLeast(0))
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().parallaxCaption(tilt)
                .padding(bottom = maxHeight * 0.22f)
                .heightIn(max = maxHeight * 0.45f).padding(horizontal = 20.dp)
                .onSizeChanged { viewportHeight = it.height }.verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                turns.take(turnIndex + 1).forEachIndexed { index, turn ->
                    key(frame, index) {
                        val fullText = turnText(turn, count)
                        val newest = index == turnIndex
                        val visibleText = if (newest) fullText.take(typed) else fullText
                        if (turn.speaker == PrologueSpeaker.NARRATOR) {
                            NarrationLine(fullText, visibleText, Modifier.align(Alignment.CenterHorizontally)
                                .widthIn(max = contentWidth * 0.88f)
                                .padding(bottom = if (turns.size > 1) 16.dp else 0.dp)
                                .onSizeChanged { if (newest) newestHeight = it.height }
                                .alpha(if (newest) 1f else 0.5f).clearAndSetSemantics { })
                        } else {
                            val entrance = remember(animate) {
                                androidx.compose.animation.core.Animatable(if (animate && newest) 0f else 1f)
                            }
                            LaunchedEffect(animate) {
                                if (animate) entrance.animateTo(1f, tween(150))
                            }
                            val speaker = storyboardSpeaker(turn, complete = !newest || typed == fullText.length)
                            DialogueCard(speaker, turn.side, speaker.label(stranger, you, manStranger, friend, staff), fullText, visibleText,
                                Modifier.align(if (turn.side == PrologueSide.LEFT)
                                    AbsoluteAlignment.Left else AbsoluteAlignment.Right)
                                    .widthIn(max = contentWidth * 0.82f)
                                    .onSizeChanged { if (newest) newestHeight = it.height }
                                    .graphicsLayer {
                                        alpha = if (newest) entrance.value else 0.5f
                                        translationY = if (newest) 4.dp.toPx() * (1f - entrance.value) else 0f
                                    }.clearAndSetSemantics { })
                        }
                    }
                }
            }
        }
    }
}

/** A narration line of the storyboard (also the city scenes): centred, white, with the full text reserving the size while typing. */
@Composable private fun NarrationLine(fullText: String, visibleText: String, modifier: Modifier = Modifier) = Box(modifier) {
    // A soft shadow keeps it legible over light art (the city scenes' counters); invisible on the story's dark frames.
    val style = TextStyle(color = Color.White, fontSize = 22.sp, lineHeight = 30.sp, textAlign = TextAlign.Center,
        shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.85f), blurRadius = 10f))
    Text(fullText, style = style, modifier = Modifier.alpha(0f))
    Text(visibleText, style = style, modifier = Modifier.matchParentSize())
}

/** A speaker's line of the storyboard (also the city scenes); [subtitle] is the gloss in the player's language under a foreign line. */
@Composable private fun DialogueCard(
    speaker: PrologueSpeaker, side: PrologueSide, label: String, fullText: String, visibleText: String, modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    val man = speaker == PrologueSpeaker.MAN_STRANGER || speaker == PrologueSpeaker.MAN_FRIEND || speaker == PrologueSpeaker.STAFF
    val valery = speaker.isValery || man
    val onLeft = side == PrologueSide.LEFT
    val density = LocalDensity.current
    val radius = with(density) { 14.dp.toPx() }
    val tail = with(density) { 7.dp.toPx() }
    val tailTop = with(density) { 12.dp.toPx() }
    val shape = remember(onLeft, radius, tail, tailTop) {
        GenericShape { size, _ ->
            val left = if (onLeft) tail else 0f
            val right = size.width - if (onLeft) 0f else tail
            val bottom = size.height
            moveTo(left + radius, 0f)
            lineTo(right - radius, 0f)
            quadraticBezierTo(right, 0f, right, radius)
            if (!onLeft) {
                lineTo(size.width, tailTop)
                lineTo(right, tailTop + tail)
            }
            lineTo(right, bottom - radius)
            quadraticBezierTo(right, bottom, right - radius, bottom)
            lineTo(left + radius, bottom)
            quadraticBezierTo(left, bottom, left, bottom - radius)
            if (onLeft) {
                lineTo(left, tailTop + tail)
                lineTo(0f, tailTop)
            }
            lineTo(left, radius)
            quadraticBezierTo(left, 0f, left + radius, 0f)
            close()
        }
    }
    val ink = if (valery) Color(0xFF2A2520) else Color.White
    Column(modifier.widthIn(min = 96.dp).background(if (man) Color(0xFFE3E6E8) else if (valery) Color(0xFFF1E7D6).copy(alpha = 0.8f)
            else Color(0xFF101418).copy(alpha = 0.7f), shape)
        .then(if (valery) Modifier else Modifier.border(1.dp, Color.White.copy(alpha = 0.6f), shape))
        .absolutePadding(left = if (onLeft) 21.dp else 14.dp, top = 10.dp,
            right = if (onLeft) 14.dp else 21.dp, bottom = 10.dp)) {
        Text(label.uppercase(java.util.Locale.forLanguageTag(uiLang())), color = if (man) Color(0xFF505B64) else if (valery) Color(0xFF9A4F36) else Color.White,
            fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(4.dp))
        val style = TextStyle(color = ink, fontSize = 18.sp, lineHeight = 25.sp,
            fontStyle = if (valery) FontStyle.Normal else FontStyle.Italic)
        Box {
            Text(fullText, style = style, modifier = Modifier.alpha(0f).clearAndSetSemantics { })
            Text(visibleText, style = style, modifier = Modifier.matchParentSize())
        }
        if (subtitle != null) Text(subtitle, color = ink.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** The airport worker diverts the player after the home desk; the last narration line closes act 1. */
internal val ESCORT_TURNS = listOf(PrologueTurn(PrologueSpeaker.STAFF, R.string.escort_1),
    PrologueTurn(PrologueSpeaker.STAFF, R.string.escort_2), PrologueTurn(PrologueSpeaker.STAFF, R.string.escort_3),
    PrologueTurn(PrologueSpeaker.NARRATOR, R.string.escort_n))

@Composable private fun Act1Cutscene(g: Game) {
    if (g.act1Stage == Act1Stage.ESCORT) {
        if (g.careerReady) StoryTurns(g, 0, "act1_escort", ESCORT_TURNS) { g.advanceAct1Cutscene() }
        return
    }
    val plane = g.act1Stage in setOf(Act1Stage.PLANE, Act1Stage.FLIGHT)
    StoryCaption(if (plane) "cut_plane" else "queue_news",
        stringResource(if (!plane) R.string.act1_arrival else
            act1PlaneCaption(g.career.snapshot)),
        g.careerReady, g::advanceAct1Cutscene)
}

/** All story captions share placement, typing, reduced motion and full-line accessibility. */
@Composable private fun StoryCaption(art: String?, line: String, enabled: Boolean, advance: () -> Unit) {
    val tilt = rememberParallax()
    val ctx = LocalContext.current
    val animate = Motion.allowed()
    var typed by remember(art, line, animate) { mutableIntStateOf(if (animate) 0 else line.length) }
    LaunchedEffect(art, line, animate) {
        while (typed < line.length) { delay(14); typed = (typed + 1).coerceAtMost(line.length) }
    }
    BackHandler { }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(enabled = enabled, role = Role.Button,
        onClickLabel = stringResource(R.string.continue_btn)) {
        if (typed < line.length) typed = line.length else advance()
    }) {
        if (art != null) {
            Crossfade(art, Modifier.fillMaxSize(), tween(500), label = "story-art") { a ->
                val bitmap = rememberAsset(a)
                if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize().parallaxArt(tilt), contentScale = ContentScale.Crop)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0.35f to Color.Transparent, 1f to Color.Black)))
        }
        Text(line.take(typed), color = Color.White, fontSize = 24.sp, lineHeight = 32.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(if (art == null) Alignment.Center else BiasAlignment(0f, 0.5f))
                .parallaxCaption(tilt).safeDrawingPadding().padding(24.dp)
                .clearAndSetSemantics { contentDescription = line })
    }
}

/** A home vignette between trips: one first-person caption over the flat; [index] into [HOME_VIGNETTES], the last takes the day count. */
@Composable private fun HomeVignette(g: Game, index: Int) {
    val ctx = LocalContext.current
    val art = remember { homeArt(g.story.nationality.id) { asset(ctx, it) != null } }
    StoryCaption(art, stringResource(HOME_VIGNETTES[index], g.career.snapshot?.daysAway ?: 0), true, g::leaveHome)
}

/** The introduction card changes its tag only once the self-introduction has been delivered. */
internal fun storyboardSpeaker(turn: PrologueTurn, complete: Boolean): PrologueSpeaker =
    if (turn.text == R.string.epi_intro && complete) PrologueSpeaker.MAN_FRIEND else turn.speaker

internal val EPILOGUE_ART = listOf("epi_scanner", "epi_flash", "story_building_empty",
    "story_building", "story_building", "story_building_empty", "epi_after", "epi_after")

/** [doses]: the building test's accepted doses; null = unknown (an older save), which keeps the plain pass/fail line. */
internal fun epilogueFrames(passed: Boolean, doses: Int? = null): List<List<PrologueTurn>> {
    fun n(text: Int) = PrologueTurn(PrologueSpeaker.NARRATOR, text)
    fun m(text: Int) = PrologueTurn(PrologueSpeaker.MAN_FRIEND, text)
    fun y(text: Int) = PrologueTurn(PrologueSpeaker.YOU, text)
    val debrief = when {
        doses == null -> if (passed) R.string.epi_pass else R.string.epi_fail
        passed -> if (doses >= 1) R.string.epi_pass_dosed else R.string.epi_pass_plain
        else -> if (doses >= 1) R.string.epi_fail_dosed else R.string.epi_fail
    }
    return listOf(
        listOf(n(R.string.epi_scan)), listOf(n(R.string.epi_burn)), listOf(n(R.string.epi_wake)),
        listOf(PrologueTurn(PrologueSpeaker.MAN_STRANGER, R.string.epi_intro), y(R.string.epi_what), m(R.string.epi_scanner),
            y(R.string.epi_valery), m(R.string.epi_rollout)),
        listOf(m(R.string.epi_try)), emptyList(), // durable test boundary
        listOf(m(debrief), m(R.string.epi_copy), m(R.string.epi_airports),
            y(R.string.epi_who), m(R.string.epi_free)))
}

internal enum class FinaleQuestion(val question: Int, val answer: Int) {
    BENEFIT(R.string.finale_question_benefit, R.string.finale_answer_benefit),
    WHY_ME(R.string.finale_question_why, R.string.finale_answer_why);

    val turns: List<PrologueTurn> get() = listOf(
        PrologueTurn(PrologueSpeaker.YOU, question), PrologueTurn(PrologueSpeaker.MAN_FRIEND, answer))
}

/** "Keep helping" beat: plays before g.keepHelping(); tapped through like the questions. */
internal val FINALE_KEEP_TURNS: List<PrologueTurn> = listOf(
    PrologueTurn(PrologueSpeaker.MAN_FRIEND, R.string.finale_keep_m1),
    PrologueTurn(PrologueSpeaker.NARRATOR, R.string.finale_keep_n),
    PrologueTurn(PrologueSpeaker.MAN_FRIEND, R.string.finale_keep_m2))

/** Deliberately session-only: questions reappear after process death. */
internal data class FinaleQuestions(
    val asked: Set<FinaleQuestion> = emptySet(), val active: FinaleQuestion? = null,
) {
    val available: List<FinaleQuestion> get() = FinaleQuestion.entries.filter { it !in asked }
    fun ask(question: FinaleQuestion): FinaleQuestions =
        if (active == null && question in available) copy(asked = asked + question, active = question) else this
    fun finish(): FinaleQuestions = copy(active = null)
}

@Composable private fun FinaleScreen(g: Game) {
    var credits by remember { mutableStateOf(false) }
    var chosenChoice by remember { mutableStateOf(false) }
    var questions by remember { mutableStateOf(FinaleQuestions()) }
    var leaving by remember { mutableStateOf(false) }
    var keeping by remember { mutableStateOf(false) }
    BackHandler { }
    if (!g.careerReady) return
    val frame = g.career.snapshot?.epilogueFrame ?: 6
    if (credits) { Credits(Modifier.fillMaxSize()) { g.walkAway() }; return }
    if (frame < 7) {
        if (frame == 5) {
            LaunchedEffect(frame) { g.startEpilogueTest() }
        } else if (frame == 4) {
            // Session-only choice: a restart at frame 4 plays it again from "Will you try?".
            WhyScreen(g, EPILOGUE_TRY_SCRIPT, friendLabel = R.string.epi_friend) { option ->
                if (option?.walksAway == true) credits = true else g.advanceEpilogue(5)
            }
        } else if (frame == 3 && chosenChoice) {
            // Session-only choice: a restart at frame 3 plays the frame again and shows the choice again.
            WhyScreen(g, EPILOGUE_CHOSEN_SCRIPT, friendLabel = R.string.epi_friend) { g.advanceEpilogue(4) }
        } else StoryTurns(g, frame, EPILOGUE_ART[frame], epilogueFrames(g.epiloguePassed, g.epilogueDoses)[frame]) {
            if (frame == 3) chosenChoice = true else g.advanceEpilogue(frame + 1)
        }
        return
    }
    // A question plays full screen like every other story frame (it was nested inside the choice panel: owner saw a
    // picture inside a picture), then the choice returns.
    if (leaving) {
        // Session-only, like frame 4: a restart shows the choice again. Walking away stays reversible.
        WhyScreen(g, EPILOGUE_LEAVE_SCRIPT, friendLabel = R.string.epi_friend) { option -> if (option?.walksAway == true) credits = true else leaving = false }
        return
    }
    if (keeping) {
        StoryTurns(g, 100, EPILOGUE_ART[7], FINALE_KEEP_TURNS) { g.keepHelping() }
        return
    }
    questions.active?.let { active ->
        StoryTurns(g, active.ordinal, EPILOGUE_ART[7], active.turns) { questions = questions.finish() }
        return
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val tilt = rememberParallax()
        rememberAsset(EPILOGUE_ART[7])?.let {
            Image(it, null, Modifier.fillMaxSize().parallaxArt(tilt), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black)))
        Column(Modifier.align(Alignment.Center).fillMaxWidth().safeDrawingPadding().padding(24.dp)
            .verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            run {
                Text(stringResource(R.string.finale_deal), color = C.text, fontSize = 18.sp, lineHeight = 26.sp,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                questions.available.forEach { question ->
                    PrimaryButton(stringResource(question.question), enabled = g.careerReady) { questions = questions.ask(question) }
                    Spacer(Modifier.height(12.dp))
                }
                Spacer(Modifier.height(12.dp))
            }
            PrimaryButton(stringResource(R.string.finale_keep), enabled = g.careerReady) { keeping = true }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(stringResource(R.string.epi_choice_walk), enabled = g.careerReady) { leaving = true }
        }
    }
}

/** Act 2's "why" scene: Marek (the epilogue's man, now named) asks the player why they do this. Choices play as the player's line. */
internal data class WhyOption(val label: Int, val reply: List<PrologueTurn>, val ends: Boolean = false, val opensLanguages: Boolean = false,
    val walksAway: Boolean = false)
internal sealed interface WhyStep { val art: String }
internal data class WhyTurns(override val art: String, val turns: List<PrologueTurn>) : WhyStep
internal data class WhyChoice(override val art: String, val options: List<WhyOption>) : WhyStep

internal val WHY_SCRIPT: List<WhyStep> = run {
    fun n(text: Int) = PrologueTurn(PrologueSpeaker.NARRATOR, text)
    fun m(text: Int) = PrologueTurn(PrologueSpeaker.MAN_FRIEND, text)
    fun y(text: Int) = PrologueTurn(PrologueSpeaker.YOU, text)
    listOf(
        WhyTurns("why_corridor", listOf(n(R.string.why_n_corridor), m(R.string.why_m_insidious), m(R.string.why_m_name))),
        WhyChoice("why_corridor", listOf(
            WhyOption(R.string.why_q_following, listOf(y(R.string.why_q_following), m(R.string.why_m_following), y(R.string.why_q_why))),
            WhyOption(R.string.why_q_why, listOf(y(R.string.why_q_why))))),
        WhyTurns("why_window", listOf(m(R.string.why_m_thalassia), y(R.string.why_y_sleep), m(R.string.why_m_switch),
            m(R.string.why_m_business), y(R.string.why_y_pass),
            m(R.string.why_m_pass), m(R.string.why_m_carries), y(R.string.why_y_implant), m(R.string.why_m_think))),
        WhyChoice("why_window", listOf(
            WhyOption(R.string.why_q_yes, listOf(y(R.string.why_q_yes), m(R.string.why_m_yes))),
            WhyOption(R.string.why_q_no, listOf(y(R.string.why_q_no), n(R.string.why_n_gone)), ends = true))),
        WhyTurns("why_window", listOf(m(R.string.why_m_build), m(R.string.why_m_display), m(R.string.why_m_augmented))),
        WhyChoice("why_gate", listOf(
            WhyOption(R.string.why_q_stop, listOf(y(R.string.why_q_stop), m(R.string.why_m_stop))),
            WhyOption(R.string.why_q_keep, listOf(y(R.string.why_q_keep), m(R.string.why_m_keep))))),
        WhyTurns("why_gate", listOf(n(R.string.why_n_gone))))
}

/** Epilogue frame 3: its turns play in the storyboard (StoryTurns); after the roll-out speech this choice follows: ask "Am I the chosen one?" (Marek answers) or go straight on. */
internal val EPILOGUE_CHOSEN_SCRIPT: List<WhyStep> = run {
    fun m(text: Int) = PrologueTurn(PrologueSpeaker.MAN_FRIEND, text)
    fun y(text: Int) = PrologueTurn(PrologueSpeaker.YOU, text)
    val art = EPILOGUE_ART[3]
    listOf(
        WhyChoice(art, listOf(
            WhyOption(R.string.epi_chosen, listOf(y(R.string.epi_chosen), m(R.string.epi_chosen_no)), ends = true),
            WhyOption(R.string.epi_go_on, listOf(y(R.string.epi_go_on)), ends = true))))
}

/** Epilogue frame 4: "Will you try?" and the way out. Both ending options carry the outcome; only "I'm sure." walks away. */
internal val EPILOGUE_TRY_SCRIPT: List<WhyStep> = run {
    fun m(text: Int) = PrologueTurn(PrologueSpeaker.MAN_FRIEND, text)
    fun y(text: Int) = PrologueTurn(PrologueSpeaker.YOU, text)
    val art = EPILOGUE_ART[4]
    listOf(
        WhyTurns(art, epilogueFrames(true)[4]),
        WhyChoice(art, listOf(
            WhyOption(R.string.epi_choice_try, listOf(y(R.string.epi_choice_try)), ends = true),
            WhyOption(R.string.epi_choice_walk, listOf(y(R.string.epi_choice_walk), m(R.string.epi_walk_sure_q))))),
        WhyChoice(art, listOf(
            WhyOption(R.string.epi_choice_sure, listOf(y(R.string.epi_choice_sure), m(R.string.epi_walk_bye), m(R.string.epi_walk_reach)), ends = true, walksAway = true),
            WhyOption(R.string.epi_choice_back, listOf(y(R.string.epi_choice_back)), ends = true))))
}

/** The way out after the reveal (finale frame 7): the same confirmation as frame 4, over the finale art. */
internal val EPILOGUE_LEAVE_SCRIPT: List<WhyStep> = run {
    val art = EPILOGUE_ART[7]
    val stay = WhyOption(R.string.epi_choice_stay, listOf(PrologueTurn(PrologueSpeaker.YOU, R.string.epi_choice_stay)), ends = true)
    listOf(
        WhyTurns(art, listOf(PrologueTurn(PrologueSpeaker.YOU, R.string.epi_choice_walk), PrologueTurn(PrologueSpeaker.MAN_FRIEND, R.string.epi_walk_sure_q))),
        WhyChoice(art, (EPILOGUE_TRY_SCRIPT[2] as WhyChoice).options.take(1) + stay))
}

/** Marek's second visit (act 2, every installed language trained). Ends in the language download screen or a short goodbye. */
internal fun visitScript(allInstalled: Boolean): List<WhyStep> {
    fun n(text: Int) = PrologueTurn(PrologueSpeaker.NARRATOR, text)
    fun m(text: Int, plural: Boolean = false) = PrologueTurn(PrologueSpeaker.MAN_FRIEND, text, plural = plural)
    fun y(text: Int) = PrologueTurn(PrologueSpeaker.YOU, text)
    val tail = if (allInstalled) listOf(WhyTurns("visit_board", listOf(m(R.string.visit_m_all), m(R.string.visit_m_airports))))
    else listOf(WhyTurns("visit_board", listOf(m(R.string.visit_m_load))),
        WhyChoice("visit_board", listOf(WhyOption(R.string.visit_q_show, emptyList(), ends = true, opensLanguages = true),
            WhyOption(R.string.visit_q_later, listOf(y(R.string.visit_q_later), m(R.string.visit_m_later)), ends = true))))
    return listOf(
        WhyTurns("visit_board", listOf(n(R.string.visit_n_gate), m(R.string.visit_m_chip), m(R.string.visit_m_best))),
        WhyChoice("visit_board", listOf(WhyOption(R.string.visit_q_next, listOf(y(R.string.visit_q_next))),
            WhyOption(R.string.visit_q_over, listOf(y(R.string.visit_q_over), m(R.string.visit_m_over))))),
        WhyTurns("visit_board", listOf(m(R.plurals.visit_m_langs, plural = true), m(R.string.visit_m_borders)))) + tail
}

/** Career doses from which Valery remarks on the queues that laughed for a month. */
internal const val HEAVY_POISONER_DOSES = 10

/** The return to Thalassia after the USA ending: five narrated frames, then Valery in the café's back room. */
internal fun thalassiaScript(heavy: Boolean = false, away: Span = awaySpan(0)): List<WhyStep> = run {
    fun n(text: Int, span: Span? = null) = PrologueTurn(PrologueSpeaker.NARRATOR, text, span = span)
    fun v(text: Int, span: Span? = null) = PrologueTurn(PrologueSpeaker.VALERY, text, span = span)
    fun m(text: Int) = PrologueTurn(PrologueSpeaker.MAN_FRIEND, text)
    fun y(text: Int) = PrologueTurn(PrologueSpeaker.YOU, text)
    val art = "thal_6_valery"
    listOf(
        WhyTurns("thal_1_window", listOf(n(R.string.thal_n_window, away))),
        WhyTurns("thal_2_arrivals", listOf(n(R.string.thal_n_arrivals))),
        WhyTurns("thal_3_lens", listOf(n(R.string.thal_n_lens))),
        WhyTurns("thal_4_square", listOf(n(R.string.thal_n_square))),
        WhyTurns("thal_5_cafe", listOf(n(R.string.thal_n_cafe))),
        WhyTurns(art, listOf(v(R.string.thal_v_came), v(R.string.thal_v_vanished), v(R.string.thal_v_waited, away),
            y(R.string.thal_y_look), v(R.string.thal_v_whose))),
        // Both lead on; the second names him, or Valery's "Marek?" would answer a name nobody said.
        WhyChoice(art, listOf(
            WhyOption(R.string.thal_q_marek, listOf(y(R.string.thal_q_marek))),
            WhyOption(R.string.thal_q_someone, listOf(y(R.string.thal_q_someone), y(R.string.thal_y_name))))),
        WhyTurns(art, listOf(v(R.string.thal_v_marek), y(R.string.thal_y_tweed), v(R.string.thal_v_updates), y(R.string.thal_y_poison),
            v(R.string.thal_v_what), v(R.string.thal_v_outside)) +
            (if (heavy) listOf(v(R.string.thal_v_laughing)) else emptyList()) + v(R.string.thal_v_ring)),
        WhyChoice(art, listOf(
            WhyOption(R.string.thal_q_ring, listOf(y(R.string.thal_q_ring))),
            WhyOption(R.string.thal_q_do, listOf(y(R.string.thal_q_do))))),
        WhyTurns(art, listOf(v(R.string.thal_v_listen))),
        WhyTurns("thal_7_raid", listOf(n(R.string.thal_n_door), n(R.string.thal_n_taken))),
        WhyTurns("thal_8_marek", listOf(n(R.string.thal_n_tweed), m(R.string.thal_m_thanks), m(R.string.thal_m_trazador), m(R.string.thal_m_tech),
            m(R.string.thal_m_ticket), n(R.string.thal_n_surge))))
}

/** Back stays on the scene like every story scene (a Settings replay leaves instead); the trip counts as seen only when the credits end ([Game.finishThalassia]). */
@Composable private fun ThalassiaScreen(g: Game) {
    BackHandler { g.leaveThalassiaReplay() } // no-op on the first viewing (swallowed)
    if (g.thalassiaCredits) Credits(Modifier.fillMaxSize(), trueEnding = true) { g.finishThalassia() }
    else Box(Modifier.fillMaxSize().background(Color.Black)) {
        // The last tap (the song starts with it): the frame fades to black, a deep red glow swells behind the eye, shifts to dim amber and
        // fades out. Smooth tweens of >= 500 ms, muted peak (WCAG 2.3.1: no flash); plain fade to black when animations are off.
        val fall = remember { androidx.compose.animation.core.Animatable(0f) }
        val glow = remember { androidx.compose.animation.core.Animatable(0f) }
        val amber = remember { androidx.compose.animation.core.Animatable(0f) }
        if (g.thalassiaWhiteout) LaunchedEffect(Unit) {
            fall.animateTo(1f, tween(500))
            if (Motion.allowed()) {
                glow.animateTo(0.7f, tween(900)); amber.animateTo(1f, tween(900)); glow.animateTo(0f, tween(1400))
            }
            delay(400); g.thalassiaToCredits()
        }
        // The raid frame: the door breaks (music cut, sfx, a short decaying shake), then 1 s of silence and a 2 s fade-in. Once per scene composition.
        val shake = remember { androidx.compose.animation.core.Animatable(0f) }
        val doorDone = remember { booleanArrayOf(false) }
        val px = LocalDensity.current.density * 6f
        val shakeScope = rememberCoroutineScope()
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = 1f - fall.value
            translationX = kotlin.math.sin(shake.value * 50f) * (1f - shake.value) * px
        }) {
            WhyScreen(g, remember { thalassiaScript((g.career.snapshot?.doses ?: 0) >= HEAVY_POISONER_DOSES, awaySpan(g.career.snapshot?.daysAway ?: 0)) }, backHandled = true, onArt = { art ->
                if (art == "thal_7_raid" && !doorDone[0]) {
                    doorDone[0] = true
                    g.doorBreak()
                    if (Motion.allowed()) shakeScope.launch { shake.snapTo(0f); shake.animateTo(1f, tween(350, easing = androidx.compose.animation.core.LinearEasing)) }
                }
            }) { g.thalassiaToWhiteout() }
        }
        if (g.thalassiaWhiteout) Box(Modifier.fillMaxSize().graphicsLayer { alpha = glow.value }.drawBehind {
            val c = androidx.compose.ui.graphics.lerp(Color(0xFF6E0F0F), Color(0xFF8A6A1E), amber.value)
            drawRect(Brush.radialGradient(listOf(c, Color.Transparent), center, maxOf(size.width, size.height) * 0.8f))
        }.pointerInput(Unit) {})
    }
}

/** [finish] gets the option that ended the script (null when it ran out of steps). */
@Composable private fun WhyScreen(g: Game, script: List<WhyStep>, count: Int = 0, friendLabel: Int = R.string.why_marek,
    backHandled: Boolean = false, onArt: (String) -> Unit = {}, finish: (WhyOption?) -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    if (g.careerReady) LaunchedEffect(script[step].art) { onArt(script[step].art) }
    var reply by remember { mutableStateOf<WhyOption?>(null) }
    if (!backHandled) BackHandler { }
    if (!g.careerReady) return
    val current = script[step]
    val next = { if (step < script.lastIndex) step++ else finish(null) }
    reply?.let { option ->
        StoryTurns(g, step * 2 + 1, current.art, option.reply, friendLabel = friendLabel, count = count) {
            reply = null; if (option.ends) finish(option) else next()
        }
        return
    }
    when (current) {
        is WhyTurns -> StoryTurns(g, step * 2, current.art, current.turns, friendLabel = friendLabel, count = count) { next() }
        is WhyChoice -> Box(Modifier.fillMaxSize().background(Color.Black)) {
            // The same art layer as StoryTurns (parallax zoom, gradient): no jump between a line and its choices.
            val tilt = rememberParallax()
            rememberAsset(current.art)?.let { Image(it, null, Modifier.fillMaxSize().parallaxArt(tilt), contentScale = ContentScale.Crop) }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black)))
            Column(Modifier.align(Alignment.Center).fillMaxWidth().safeDrawingPadding().padding(24.dp)
                .verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Solid buttons like the queue's choices: outlines were unreadable over light art (owner).
                current.options.forEach { option ->
                    PrimaryButton(stringResource(option.label), enabled = g.careerReady) { if (option.opensLanguages) finish(option) else reply = option }
                }
            }
        }
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
    R.string.credits_built_with to 0,
    R.string.credits_tech_minds to R.string.credits_tech_minds_name, R.string.credits_tech_speech to R.string.credits_tech_speech_name,
    R.string.credits_tech_vad to R.string.credits_tech_vad_name, R.string.credits_tech_voices to R.string.credits_tech_voices_name,
    R.string.credits_tech_langid to R.string.credits_tech_langid_name, R.string.credits_tech_face to R.string.credits_tech_face_name,
    R.string.credits_tech_packs to R.string.credits_tech_packs_name, R.string.credits_tech_ui to R.string.credits_tech_ui_name, 0 to 0,
    R.string.credits_music to R.string.credits_music_artist, R.string.credits_song_label to R.string.credits_song, 0 to 0,
    R.string.credits_images to R.string.credits_images_text, 0 to 0,
    R.string.credits_android to R.string.credits_android_text, 0 to 0,
    0 to R.string.credits_fiction, 0 to 0,
    0 to 0, 0 to 0,
    0 to R.string.credits_reached, 0 to R.string.credits_playing,
)

private val CREDITS_ART = listOf("prologue_1_square", "prologue_2b_smashed", "prologue_3_embassy", "prologue_4_valery", "prologue_6_trial",
    "story_building", "upgrade_box_airport", "why_window", "queue_33_flute", "queue_40_paper_boat", "epi_scanner", "thal_2_arrivals",
    "thal_4_square", "thal_6_valery", "thal_8_marek")

/** Behind the true-ending credits: one picture at a time (two while fading), 7 s each with a 1.5 s crossfade, under a dark scrim. */
@Composable private fun CreditsPictures() {
    val ctx = LocalContext.current
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(6000); i = (i + 1) % CREDITS_ART.size } }
    // Full-screen WebPs decode off the main thread; the cache holds the shown picture and the next one (a fading-out one is still held by its Crossfade slot).
    val cache = remember { mutableMapOf<Int, ImageBitmap?>() }
    suspend fun load(n: Int) = cache[n] ?: withContext(Dispatchers.IO) { asset(ctx, CREDITS_ART[n])?.asImageBitmap() }.also { cache[n] = it }
    LaunchedEffect(i) {
        delay(2000) // after the crossfade
        val next = (i + 1) % CREDITS_ART.size
        cache.keys.retainAll(setOf(i))
        load(next)
    }
    Crossfade(i, Modifier.fillMaxSize(), tween(if (Motion.allowed()) 1500 else 500), label = "credits-art") { n ->
        val img by produceState(cache[n], n) { value = load(n) }
        img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.68f)))
}

/** [trueEnding]: the Thalassia credits open with the owner's two lines in a title size; the walk-away credits don't. */
@Composable private fun Credits(modifier: Modifier, trueEnding: Boolean = false, onDone: () -> Unit) = Box(modifier) {
    if (trueEnding) CreditsPictures()
    val y = remember { androidx.compose.animation.core.Animatable(Float.MAX_VALUE) }
    var colH by remember { mutableStateOf(0) }
    var thanksH by remember { mutableStateOf(0) }
    val lastHalf = if (trueEnding) thanksH / 2f else with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.toPx() } // half the closing lines
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screen = constraints.maxHeight.toFloat(); val screenDp = maxHeight
        // Scroll from just below the screen until the closing lines are centered and stay (the true ending closes on its thank-you, alone on screen; owner), 2 screens in 92 s.
        LaunchedEffect(colH, thanksH, screen) {
            if (colH == 0 || trueEnding && thanksH == 0) return@LaunchedEffect
            val end = screen / 2 - colH + lastHalf
            y.snapTo(screen)
            y.animateTo(end, tween((92_000 * (screen - end) / (2 * screen)).toInt(), easing = androidx.compose.animation.core.LinearEasing))
        }
        Column(Modifier.fillMaxWidth().offset { androidx.compose.ui.unit.IntOffset(0, y.value.coerceAtMost(screen).toInt()) }
            .wrapContentHeight(Alignment.Top, unbounded = true).onSizeChanged { colH = it.height }.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            val uri = androidx.compose.ui.platform.LocalUriHandler.current
            (if (trueEnding) CREDITS.dropLast(2) else CREDITS).forEach { (ar, br) -> // the true ending closes on its own thank-you
                val a = if (ar != 0) stringResource(ar) else ""
                val b = if (br == R.string.credits_blurb) stringResource(br, Language.shipped.size) else if (br != 0) stringResource(br) else ""
                if (a.isNotEmpty()) Text(a, color = C.dim, fontSize = 13.sp, letterSpacing = 2.sp, textAlign = TextAlign.Center)
                if (b.isNotEmpty()) Text(b, color = C.text, fontSize = if (a.isEmpty()) 16.sp else 18.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 4.dp))
                if (br == R.string.credits_music_artist) Text("theatomicsongbirds.com", color = Hud.cyan, fontSize = 15.sp,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    modifier = Modifier.padding(bottom = 4.dp).clickable { runCatching { uri.openUri("https://theatomicsongbirds.com") } })
                if (a.isEmpty() && b.isEmpty()) Spacer(Modifier.height(28.dp))
            }
            if (trueEnding) {
                Spacer(Modifier.height(screenDp * 0.6f)) // the credits leave the screen before the thank-you settles
                Column(Modifier.onSizeChanged { thanksH = it.height }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.credits_thanks_end), style = display(26, 1.0), lineHeight = 36.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(28.dp))
                    Text(stringResource(R.string.credits_end_or_beginning), style = display(26, 1.0), lineHeight = 36.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(36.dp))
                    Text(stringResource(R.string.credits_more), color = C.dim, fontSize = 14.sp, textAlign = TextAlign.Center)
                    Text("lampensoft.com", color = Hud.cyan, fontSize = 16.sp, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        modifier = Modifier.padding(top = 4.dp).clickable { runCatching { uri.openUri("https://lampensoft.com") } })
                }
            }
        }
    }
    if (trueEnding) { // rises out of the black of the fall
        val dark = remember { androidx.compose.animation.core.Animatable(1f) }
        LaunchedEffect(Unit) { dark.animateTo(0f, tween(1500)) }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = dark.value }.background(Color.Black))
    }
    TextButton(onDone, Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(12.dp)) { Text(stringResource(R.string.continue_btn), color = C.dim) }
}

@Composable private fun Feature(label: String, vararg icons: ImageVector) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Row { icons.forEach { Icon(it, null, tint = C.dim, modifier = Modifier.size(20.dp)) } }
    Text(label, color = C.dim, fontSize = 12.sp)
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
    if (g.departureTraining) {
        BackHandler { }
        LaunchedEffect(g.careerReady, g.ready, g.earsReady) {
            if (g.ready && g.earsReady == true) g.startInterview()
        }
        Gutter {
            Text(stringResource(R.string.training_departures), style = display(24, 2.0))
            TrainingDeskEntry(g) { g.startInterview() }
        }
        return@Box
    }
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
        Text(stringResource(if (g.homeArrivalTraining) R.string.training_home_arrivals else if (g.departureTraining) R.string.training_departures else R.string.cover_title), style = display(24, 2.0), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (!g.departureTraining) Text(stringResource(R.string.cover_memorize), color = C.dim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        val scroll = rememberScrollState()
        // The cover and difficulty scroll together; READY stays outside the scrolling content.
        Box(Modifier.weight(1f).fillMaxWidth()) {
        Column(Modifier.verticalScroll(scroll)) {
        Box(Modifier.fillMaxWidth().clip(Square).background(C.paper)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row {
                Box(Modifier.size(width = 80.dp, height = 100.dp).clip(Square).background(Color(0xFFC9C5BC))) {
                    if (photo != null) Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(s.name, style = LocalTextStyle.current.copy(textDirection = TextDirection.Content), color = C.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (s.latin != s.name) Text(s.latin, style = LocalTextStyle.current.copy(textDirection = TextDirection.Content), color = C.ink, fontSize = 14.sp)
                    val dim = Color(0xFF6B6F78)
                    Text(stringResource(R.string.cover_citizen, s.nationality.uiName(uiLang())), color = dim, fontSize = 13.sp)
                    Text(stringResource(R.string.cover_passport_no, s.passportNo), color = dim, fontSize = 13.sp)
                    Text(stringResource(R.string.cover_born, coverBirthDate(s.born, ui)), color = dim, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            // Resources are presentation only: Backstory and every prompt keep their original English facts.
            if (!g.departureTraining) {
                val story = coverLines(s, ui, ctx::getString).map { line ->
                    fillBold(stringResource(line.template), *line.values.toTypedArray())
                }.joined()
                Text(remember(story) { story.annotated() }, color = C.ink, fontSize = 16.sp, lineHeight = 24.sp)
            }
        }
        }
        Spacer(Modifier.height(12.dp))
        if (!g.departureTraining && foreignTongue(uiLang(), g.destination.lang)) ForeignTongueTag(Modifier.padding(bottom = 8.dp))
        if (!g.departureTraining) Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = C.ivory)
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
                val choices = listOf(null) + Contract.entries
                Text(stringResource(R.string.contract_title), color = C.dim, fontSize = 12.sp, letterSpacing = 1.sp)
                Spacer(Modifier.height(10.dp))
                Chips(listOf(stringResource(R.string.contract_none)) + Contract.entries.map { stringResource(it.nameRes) }, choices.indexOf(contract),
                    enabled = g.canChooseContract) { g.chooseContract(choices[it]) }
                contract?.let {
                    Text(stringResource(when (it) {
                        Contract.QUIET -> R.string.contract_quiet
                        Contract.UNAIDED -> R.string.contract_unaided
                        Contract.UNMENTIONED -> R.string.contract_unmentioned
                        Contract.SAMPLES -> R.string.contract_samples
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
        CancelTrip(g)
    }
}

/** The officer's speech bubble: glass, not paper (owner). [onReplay] null = no replay icon; [inner] sits inside the border (report gestures). */
@Composable private fun SpeechBubble(text: String, subtitle: String?, tense: Boolean, modifier: Modifier, onReplay: (() -> Unit)?, inner: Modifier = Modifier) {
    Row(modifier.clip(Square).background(Hud.glass)
        .border(1.dp, if (tense) C.red.copy(alpha = 0.8f) else Hud.edge, Square)
        .then(inner)
        .padding(14.dp)) {
        if (onReplay != null) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.hud_replay), tint = if (tense) C.red else Hud.cyan,
                modifier = Modifier.size(22.dp).clickable { onReplay() })
            Spacer(Modifier.width(10.dp))
        }
        Column {
            Text(text, style = LocalTextStyle.current.copy(textDirection = TextDirection.Content), color = if (tense) Color(0xFFFFD9D5) else Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, style = LocalTextStyle.current.copy(textDirection = TextDirection.Content), color = if (tense) Color(0xFFE79A92) else C.dim, fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private object Hud {
    val glass = Color(0x99000A24)
    val edge = Color(0x40FFFFFF)
    val cyan = C.cyan
    val soft = Color(0xCCB9E8FF)
}

/** Keep the ordinary Image draw at rest, including its exact crop/filtering and crossfade layer. */
@Composable private fun OfficerPortrait(tile: ImageBitmap, key: String, mouth: OfficerMouth) {
    val ctx = LocalContext.current
    fun motionEnabled() = Motion.systemAnimates()
    var sysAnimate by remember { mutableStateOf(motionEnabled()) }
    val animate = sysAnimate && !Motion.reduce
    DisposableEffect(ctx) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { sysAnimate = motionEnabled() }
        }
        ctx.contentResolver.registerContentObserver(android.provider.Settings.Global.getUriFor(
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        sysAnimate = motionEnabled()
        onDispose { ctx.contentResolver.unregisterContentObserver(observer) }
    }
    var region by remember(key, tile) { mutableStateOf<LipRegion?>(null) }
    LaunchedEffect(key, tile, animate) { region = if (animate) OfficerLips.region(key, tile.asAndroidBitmap()) else null }
    val lips = region
    Image(tile, null, Modifier.fillMaxWidth().aspectRatio(1f).drawWithCache {
        val bitmap = tile.asAndroidBitmap()
        val n = OfficerLips.MESH
        val base = FloatArray((n + 1) * (n + 1) * 2)
        val weights = FloatArray((n + 1) * (n + 1))
        for (row in 0..n) for (col in 0..n) {
            val i = row * (n + 1) + col
            val x = col * bitmap.width.toFloat() / n
            val y = row * bitmap.height.toFloat() / n
            base[2 * i] = x; base[2 * i + 1] = y
            weights[i] = lips?.displacement(x, y, 1f) ?: 0f
        }
        val vertices = base.copyOf()
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        val shadow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.RadialGradient(0f, 0f, 1f,
                intArrayOf(0xFF120909.toInt(), 0x00120909), floatArrayOf(0f, 1f), android.graphics.Shader.TileMode.CLAMP)
        }
        val scale = maxOf(size.width / bitmap.width, size.height / bitmap.height)
        val dx = (size.width - bitmap.width * scale) / 2f
        val dy = (size.height - bitmap.height * scale) / 2f
        onDrawWithContent {
            // This is the only reader of the per-frame state: invalidate drawing, not InterviewScreen composition.
            val open = if (animate) mouth.open.floatValue else 0f
            if (lips == null || open <= 0f) drawContent()
            else {
                for (i in weights.indices) vertices[2 * i + 1] = base[2 * i + 1] + weights[i] * open
                val canvas = drawContext.canvas.nativeCanvas
                val save = canvas.save()
                try {
                    canvas.clipRect(0f, 0f, size.width, size.height)
                    canvas.translate(dx, dy); canvas.scale(scale, scale)
                    canvas.drawBitmapMesh(bitmap, n, n, vertices, 0, null, 0, paint)
                    shadow.alpha = (open * 100).toInt()
                    canvas.translate(lips.cx, lips.mouthY + lips.maxOpen * open * 0.4f)
                    canvas.scale(lips.halfWidth * 0.7f, maxOf(1f, lips.maxOpen * open * 0.65f))
                    canvas.drawCircle(0f, 0f, 1f, shadow)
                } finally { canvas.restoreToCount(save) }
            }
        }
    }, contentScale = ContentScale.Crop)
}

/**
 * The officer fills the screen; everything you read floats over them as see-through HUD panels, like smart glasses.
 * The portrait never depends on the text below it, so it can't jump between turns.
 */
@Composable private fun InterviewScreen(g: Game) = BoxWithConstraints(Modifier.fillMaxSize().background(C.bg)) {
    if (g.firstCareerTraining) BackHandler { }
    val ctx = LocalContext.current
    var report by remember { mutableStateOf<SuggestionReport?>(null) }
    val density = LocalDensity.current
    var headerTextBottomPx by remember { mutableFloatStateOf(0f) }
    val artTopPadding = if (headerTextBottomPx > 0f) Modifier.padding(top = with(density) { headerTextBottomPx.toDp() } + 4.dp)
        else Modifier.statusBarsPadding().padding(top = 44.dp)
    val faces = remember(g.officerArt) { tiles(ctx, g.officerArt, 4, 2) }
    val tense = g.face in setOf(Face.SUSPICIOUS, Face.STERN, Face.IMPATIENT)
    val hudMax = maxHeight * 0.62f
    // Backdrop: the same tile, blurred and dimmed, so the area under the square portrait isn't a flat void.
    // The T-N test: the eye machine the traveler looks into replaces the officer (story room or airport booth).
    // The USA exam's newer prototype ([Game.prototype]); the usual machine while its art is missing.
    val machine = ((if (g.prototype) rememberAsset("eye_machine_prototype") else null)
        ?: rememberAsset(if (g.storyMission) "eye_machine_story" else "eye_machine_desk")).takeIf { g.machineView }
    // The art and scanner share one crop container; authorization remains assessment-only.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val faceRun = g.activeFaceRun
    val permission = ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val visibleScanner = machine != null && permission && g.watching && g.room != null &&
        g.narration == null && g.ending == null
    DisposableEffect(lifecycle, faceRun, visibleScanner) {
        fun publish() = g.scannerPresentation(faceRun,
            visibleScanner && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> publish() }
        lifecycle.addObserver(observer)
        publish()
        onDispose { lifecycle.removeObserver(observer); g.scannerPresentation(faceRun, false) }
    }
    faces.getOrNull(g.face.ordinal)?.let {
        Image(it, null, Modifier.fillMaxSize().blur(28.dp).alpha(0.45f), contentScale = ContentScale.Crop)
    }
    // Act 1 test: the machine's own picture, blurred, fills around the square (a sharp second scene behind it read
    // as picture-in-picture, owner).
    if (g.storyMission && g.wasInRoom) (machine ?: rememberAsset("eye_machine_story"))?.let {
        Image(it, null, Modifier.fillMaxSize().blur(28.dp).alpha(0.6f), contentScale = ContentScale.Crop)
    }
    // The machine stutters like the portrait: an implant mention, or an accepted poison sample.
    if (machine != null) Box(Modifier.fillMaxWidth().then(artTopPadding).aspectRatio(1f)
        .then(if (g.officerGlitch) Modifier.glitch(idleMs = 80L..400L, strength = 2.5f) else Modifier)) {
        Image(machine, null, Modifier.matchParentSize().alpha(if (g.scannerInterrupted) .35f else 1f), contentScale = ContentScale.Crop)
        FaceScanner(g, FaceMesh.Asset.DESK,
            visibleScanner && !g.scannerInterrupted, Modifier.matchParentSize())
    }
    else {
        // Use the booking screen's reduced-motion check; re-read when the face changes.
        val animate = Motion.allowed()
        // Androids twitch now and then, more often and harder as tricks wear them down; a broken one never stops.
        val dmg = (1 - g.mind.integrity).coerceIn(0.0, 1.0).toFloat()
        val glitch = if (g.mind.crashed || g.officerGlitch) Modifier.glitch(idleMs = 80L..400L, strength = 2.5f)
            else Modifier.glitch(idleMs = (7000 * (1 - 0.7f * dmg)).toLong()..(15000 * (1 - 0.7f * dmg)).toLong(), strength = 0.6f + 1.4f * dmg)
        val portrait: @Composable (Face) -> Unit = { face ->
            val tile = faces.getOrNull(face.ordinal)
            if (tile != null) OfficerPortrait(tile, "${g.officerArt}:${face.ordinal}", g.officerMouth)
            else Box(Modifier.fillMaxWidth().aspectRatio(1f).background(C.card))
        }
        // Glitch the blended portrait as one image, keeping the effect above both expression tiles.
        Box(Modifier.fillMaxWidth().then(artTopPadding).then(glitch)) {
            if (animate) Crossfade(g.face, animationSpec = tween(250), label = "face") { portrait(it) }
            else portrait(g.face)
        }
    }
    // Readability scrim: clear over the face, darker toward the HUD.
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
        0f to Color(0x99000A24), 0.12f to Color.Transparent, 0.45f to Color.Transparent, 1f to Color(0xE6000A24))))

    // Gauges ride the art's left and right edges.
    if (g.machineView || g.room != null) Box(Modifier.fillMaxWidth().then(artTopPadding).aspectRatio(1f).padding(8.dp)) { MachineSuspicion(g) }
    else if (g.mind.decision == null && referralGaugeVisible(g.act, false) && g.liveContract != Contract.UNAIDED)
        ReferralGauge(g.mind, g.country.id, g.implantPerks, bluffReason = g.bluffReason,
            modifier = Modifier.fillMaxWidth().then(artTopPadding).aspectRatio(1f).padding(8.dp))

    Box(Modifier.fillMaxWidth().safeDrawingPadding().padding(vertical = 4.dp)) {
        Column(Modifier.align(Alignment.Center).onGloballyPositioned { headerTextBottomPx = it.boundsInRoot().bottom },
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (!g.machineView && g.room == null) {
                Text(stringResource(R.string.hud_passport_control), color = C.text, fontWeight = FontWeight.Medium)
                if (!g.thalassianDesk) Text(stringResource(R.string.hud_speak_in, g.destination.languageName(uiLang()), g.unitName), color = C.dim, fontSize = 12.sp)
            } else {
                Text(stringResource(R.string.hud_tn_assessment), color = Color(0xFFF2B8B2), fontWeight = FontWeight.Medium)
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

    // The panel hangs from the art's bottom edge (overlapping its lower 48dp, under the gauges) instead of sitting on the
    // screen bottom, so a short panel leaves no dead band under the portrait. The test's panel is longer (payload, long
    // questions): it overlaps 144dp, up to just under the eye ring (owner: bottom too crowded). Before the header is measured: bottom.
    val panelTop = if (headerTextBottomPx > 0f)
        (with(density) { headerTextBottomPx.toDp() } + 4.dp + maxWidth - (if (g.room != null || g.machineView) 144.dp else 48.dp) - with(density) { WindowInsets.safeDrawing.getTop(density).toDp() }).coerceAtLeast(0.dp)
        else 0.dp
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(top = panelTop).padding(horizontal = 12.dp),
        verticalArrangement = if (headerTextBottomPx > 0f) Arrangement.Top else Arrangement.Bottom) {
        g.tip?.let {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(Square).background(Color(0xCC06303D))
                .border(1.dp, Hud.cyan.copy(alpha = 0.7f), Square).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (g.homeArrivalTraining) R.string.training_home_arrivals else if (g.departureTraining) R.string.training_departures else R.string.hud_training), color = Hud.cyan, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text(it, color = Color.White, fontSize = 13.sp)
            }
        }
        if (g.booting) Text(stringResource(R.string.hud_online), color = Hud.cyan, fontSize = 11.sp, letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp).glitch(idleMs = 200L..700L, strength = 1.5f))
        g.implant?.let {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(Square).background(Color(0xCC3A2A06))
                .border(1.dp, Color(0xFFFFC14D), Square).padding(12.dp).glitch(idleMs = 3000L..6000L, strength = 0.5f),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.hud_implant), color = Color(0xFFFFC14D), fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text(it, color = Color.White, fontSize = 13.sp)
            }
        }
        // The composure hold sits where the implant banner was (it clears it), next to the line being answered.
        if (g.composureCue) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(Square).background(Color(0xCC06303D))
            .border(1.dp, Hud.cyan.copy(alpha = 0.7f), Square).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(32.dp).semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(g.composureProgress, 0f..1f)
            }) {
                drawArc(Hud.cyan, -90f, 360f * g.composureProgress, false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx()))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.composure_cue), color = Hud.cyan, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (g.storyMission) Text(stringResource(R.string.composure_teach), color = Color.White, fontSize = 12.sp)
                if (g.composurePaused) Text(stringResource(R.string.composure_paused), color = C.dim, fontSize = 12.sp)
            }
        }
        // Overload cues (blink twice) are instructions: a banner like the composure cue, never part of the title.
        if (g.overloadWarning || g.overloadCue || g.scannerInterrupted) MachineCues(g)
        Column(Modifier.heightIn(max = hudMax).verticalScroll(rememberScrollState())) {
            val officerReportLabel = stringResource(R.string.report_title)
            val reportOfficer = { report = SuggestionReport(Hint(g.officerText, "", g.officerEn), g.officerText, g.destination.lang) }
            if (g.officerText.isNotEmpty()) SpeechBubble(g.officerText, g.officerEn.takeIf { it.isNotEmpty() && !g.subtitleRedundant }, tense,
                Modifier.fillMaxWidth(), { g.replay() },
                Modifier.pointerInput(g.officerText, g.officerEn) { detectTapGestures(onLongPress = { reportOfficer() }) }
                    .semantics { customActions = listOf(CustomAccessibilityAction(officerReportLabel) { reportOfficer(); true }) })
            if (g.mind.decision == null) {
                Row(Modifier.padding(top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Visibility, null, tint = Hud.cyan, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.hud_retinal_display), color = Hud.cyan, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
                    if (g.room != null && g.liveContract != Contract.UNAIDED) {
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.hud_signal_dampened), color = C.red, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.glitch(idleMs = 300L..1200L, strength = 2f))
                    }
                }
                // The room's payload (phase 2): the motif in the officer's language, glossed in the UI language.
                if (g.room != null) g.payload?.let { m ->
                    val firstRoom = g.career.snapshot?.machineSessions == 0 // the first machine session: say what the payload is for
                    Text(stringResource(R.string.room_payload, listOf(m.word(g.destination.lang), m.word(uiLang())).distinct().joinToString(" · ")),
                        style = LocalTextStyle.current.copy(textDirection = TextDirection.Content), color = Color(0xFFFFC14D),
                        fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = if (firstRoom) 2.dp else 6.dp))
                    if (firstRoom) Text(stringResource(R.string.room_payload_hint), color = C.dim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
                }
                if (g.bluffReason && referralGaugeVisible(g.act, g.room != null) && g.liveContract != Contract.UNAIDED)
                    Text(stringResource(R.string.bluff_reason), color = Hud.cyan, fontSize = 11.sp)
                if (g.bluffArmed) {
                    Text(stringResource(R.string.bluff_cue), color = if (g.bluffHighlight) Color.White else Hud.cyan,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                val fallback = if (g.thalassianDesk) emptyList() else g.fallbackHints
                val displayHints = debugRetinalHints(fallback.ifEmpty { g.hints }, BuildConfig.DEBUG,
                    g.thalassianDesk, g.officerText)
                if (fallback.isNotEmpty()) Text(stringResource(R.string.machine_fallback), color = Hud.cyan,
                    fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
                if (g.liveContract == Contract.UNAIDED || displayHints.isEmpty() && !g.thalassianDesk) Text(stringResource(when {
                    g.liveContract == Contract.UNAIDED -> R.string.hud_retinal_off
                    g.room != null -> R.string.hud_signal_lost
                    g.hintsNone -> R.string.hud_no_suggestion
                    else -> R.string.hud_translating
                }), color = Hud.soft, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                val reportLabel = stringResource(R.string.report_title)
                // An English reader gets a respelling where the model gives no reading (ru/uk/ja have their own).
                val respellLang = g.destination.lang.takeIf { !g.thalassianDesk && it in respelledLanguages && uiLang() != it && subtitleLang(uiLang(), it) == "en" }
                // One suggestion: screen space (owner).
                if (g.liveContract != Contract.UNAIDED) displayHints.take(1).forEach { (target, given, english) ->
                    val reading = given.ifEmpty { respellLang?.let { respell(target, it) } ?: "" }
                    val openReport = { report = SuggestionReport(Hint(target, reading, english), g.officerText, g.destination.lang) }
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).height(IntrinsicSize.Min)
                        .jamFlicker(g.room != null && g.jam == Jam.FLICKER)
                        .clip(Square)
                        .background(Color(0x99000A24)).border(1.dp, C.cyan.copy(alpha = 0.22f), Square)
                        .pointerInput(target, reading, english) {
                            detectTapGestures(
                                onTap = { if (BuildConfig.DEBUG) g.debugSay(target) },
                                onLongPress = { openReport() })
                        }
                        .semantics(mergeDescendants = true) {
                            customActions = listOf(CustomAccessibilityAction(reportLabel) { openReport(); true })
                        }
                        ) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(C.cyan)) // the border line marks the implant
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 7.dp)) {
                        Text(target, style = LocalTextStyle.current.copy(textDirection = TextDirection.Content), color = Color.White, fontSize = 15.sp)
                        if (reading.isNotEmpty() || english.isNotEmpty() && !g.subtitleRedundant) Text(androidx.compose.ui.text.buildAnnotatedString {
                            if (reading.isNotEmpty()) {
                                pushStyle(androidx.compose.ui.text.SpanStyle(color = Hud.cyan, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
                                append(reading); pop()
                                if (english.isNotEmpty() && !g.subtitleRedundant) append("  ·  ")
                            }
                            if (!g.subtitleRedundant) append(english)
                        }, style = LocalTextStyle.current.copy(textDirection = TextDirection.Content), color = Hud.soft, fontSize = 12.sp, maxLines = 2)
                        }
                    }
                }
            }
            if (g.translatorSource.isNotEmpty()) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PhoneAndroid, stringResource(R.string.translator_phone), tint = Hud.soft, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(g.translatorSource, style = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                    color = Hud.soft, fontSize = 12.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, modifier = Modifier.weight(1f))
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
            if (!g.passportGiven && g.officerText.isNotEmpty() && g.mind.decision == null && g.room == null && !g.machineView) // no passport for a machine
                Column(Modifier.align(Alignment.CenterStart).padding(start = 8.dp).clip(Square)
                    .background(Hud.glass).border(1.dp, Hud.cyan.copy(alpha = 0.6f), Square)
                    .clickable { g.handOverPassport() }.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Book, stringResource(R.string.hud_hand_over_passport), tint = Hud.cyan)
                    Text(stringResource(R.string.hud_passport), color = Hud.soft, fontSize = 11.sp)
                }
            // The phone translator the officer pointed at: the phone's look (ivory on the card, not the implant's cyan).
            if (g.translatorShown) {
                val left = g.mind.translatorCredits
                if (left > 0) Column(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).clip(Square)
                    .background(if (g.translatorOn) C.ivory.copy(alpha = 0.18f) else C.card)
                    .border(1.dp, if (g.translatorOn) C.ivory else C.line, Square)
                    .toggleable(g.translatorOn, role = Role.Switch) { uiSfx?.play(R.raw.sfx_tap, Sfx.TAP_GAIN); g.translatorOn = it }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.PhoneAndroid, null, tint = if (g.translatorOn) C.ivory else C.dim)
                    Text(stringResource(R.string.translator_switch, left, TRANSLATOR_CREDITS), color = if (g.translatorOn) C.ivory else C.dim, fontSize = 11.sp)
                }
                else Text(stringResource(R.string.translator_out), color = C.dim, fontSize = 11.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.CenterEnd).widthIn(max = 120.dp).padding(end = 8.dp))
            }
        }
    }

    report?.let { ReportSuggestionDialog(it) { report = null } }

    // The beat before the result screen: the scene dims and one line of narration says what happens.
    androidx.compose.animation.AnimatedVisibility(g.ending != null || g.narration != null, enter = androidx.compose.animation.fadeIn(tween(900))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Text(g.narration ?: if (g.storyMission && g.wasInRoom && g.ending != null) storyTestVerdict(g.ending!!, uiLanguage(ctx)) else stringResource(if (g.mind.crashed) R.string.ending_broken else endingText(g.ending, g.wasInRoom, g.story.kind)),
                style = display(22, 1.5).copy(color = if (g.narration != null) Color(0xFFF2B8B2) else when {
                    g.mind.crashed -> Hud.cyan; g.ending?.cleared == true -> Color(0xFFB8F0CC); else -> Color(0xFFF2B8B2) }),
                textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
        }
    }

}

/**
 * The narration of a refusal or a catch (PRD §6a): the desk sends you home, the company denies entry, or catches. Never
 * violent. A citizen refused at home is held, as the officer said ("Please wait over there"): no flight home.
 */
internal fun endingText(d: Decision?, wasInRoom: Boolean, kind: TripKind) = when {
    d?.cleared == true -> R.string.ending_pass
    d == Decision.CAUGHT -> R.string.ending_caught
    kind == TripKind.HOMECOMING -> R.string.ending_refused_home
    wasInRoom -> R.string.ending_refused_company
    else -> R.string.ending_refused
}

/** The scripted child question deliberately has no release hints; debug walkthroughs need a spoken reply. */
internal fun debugRetinalHints(hints: List<Hint>, debug: Boolean, thalassianDesk: Boolean, officerText: String): List<Hint> =
    if (debug && thalassianDesk && officerText == ThalassiaDesk.CHILD && hints.isEmpty())
        listOf(Hint("Yes.", "", "")) else hints

/** Snapshot only the displayed suggestion and officer context, never the player's speech or logs. */
private data class SuggestionReport(val hint: Hint, val officer: String, val language: String)

@Composable private fun ReportSuggestionDialog(report: SuggestionReport, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var reason by remember(report) { mutableIntStateOf(0) }
    val reasons = listOf(R.string.report_reason_offensive, R.string.report_reason_wrong, R.string.report_reason_other)
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = Square,
        containerColor = C.card,
        titleContentColor = C.text,
        textContentColor = C.dim,
        title = { Text(stringResource(R.string.report_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(listOf(report.hint.target, report.hint.reading, report.hint.english)
                    .filter { it.isNotEmpty() }.joinToString("\n"), color = C.text,
                    style = LocalTextStyle.current.copy(textDirection = TextDirection.Content))
                // The officer's line is sent too (Nano-made, it may echo the player's answer): shown, never sent unseen.
                // Reporting the officer's line itself, it is the text above already.
                if (report.officer.isNotEmpty() && report.officer != report.hint.target) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.report_officer_line), color = C.dim, fontSize = 12.sp)
                    Text(report.officer.take(400), color = C.text, style = LocalTextStyle.current.copy(textDirection = TextDirection.Content))
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.report_only_this), color = C.dim, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.selectableGroup()) {
                    reasons.forEachIndexed { index, label ->
                        Row(Modifier.fillMaxWidth().selectable(reason == index, role = Role.RadioButton) { reason = index }
                            .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = reason == index, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(label))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Only the displayed suggestion and its context: no device id, speech, photo or logs.
                val fields = listOf(
                    Triple("r", "Reason", listOf("offensive", "wrong", "other")[reason]),
                    Triple("t", "Suggestion (target)", report.hint.target.take(400)),
                    Triple("rd", "Suggestion (reading)", report.hint.reading.take(400)),
                    Triple("en", "Suggestion (English)", report.hint.english.take(400)),
                    Triple("o", "Officer's last line", report.officer.take(400)),
                    Triple("l", "Destination language", report.language),
                    Triple("v", "App version", BuildConfig.VERSION_NAME),
                )
                if (BuildConfig.REPORT_URL.isNotEmpty()) {
                    // Play build: an anonymous web report. The open-source build has no URL and only copies.
                    val uri = Uri.parse(BuildConfig.REPORT_URL).buildUpon()
                        .apply { fields.forEach { (key, _, value) -> appendQueryParameter(key, value) } }.build()
                    try {
                        // A Custom Tab stays over our task (Play: report without leaving the app). Its intent is a plain
                        // ACTION_VIEW with extras, so a browser without Custom Tabs just opens the page.
                        CustomTabsIntent.Builder().build().launchUrl(ctx, uri)
                        onDismiss()
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(ctx, R.string.report_no_browser, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val text = fields.joinToString("\n") { (_, label, value) -> "$label: $value" }
                    ctx.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Borderline Fluent suggestion report", text))
                    onDismiss()
                }
            }) { Text(stringResource(if (BuildConfig.REPORT_URL.isNotEmpty()) R.string.report_send else R.string.report_copy), color = C.text) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel), color = C.dim) }
        },
    )
}

/** The officer's view of you: a small live self-view, ringed red when your face is out of sight. */
@Composable private fun OfficerEye(g: Game, modifier: Modifier) {
    var seen by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { seen = g.faceWatch.seen; delay(250) } }
    CameraFeed(g, modifier.size(46.dp).clip(Square).border(2.dp, if (seen) Hud.cyan else C.red, Square))
}

/** Front camera preview with face detection running on every frame (FaceWatch). */
@Composable private fun CameraFeed(g: Game, modifier: Modifier) {
    val ctx = LocalContext.current
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val view = remember { androidx.camera.view.PreviewView(ctx).apply { scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(owner, g.faceWatch, g.activeFaceRun) {
        var camera: FaceCamera? = null
        fun refresh() {
            if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                if (camera == null) camera = FaceCamera(ctx, owner, g.faceWatch, view.surfaceProvider)
            } else { camera?.dispose(); camera = null }
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> refresh() }
        owner.lifecycle.addObserver(observer)
        refresh()
        onDispose { owner.lifecycle.removeObserver(observer); camera?.dispose() }
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
        CameraFeed(g, Modifier.fillMaxWidth().aspectRatio(0.75f).clip(Square)
            .border(2.dp, if (live?.face == true) Hud.cyan else C.red, Square))
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
    Box(Modifier.fillMaxWidth().height(6.dp).clip(Square).background(C.line)) {
        Box(Modifier.fillMaxWidth(v ?: 0f).fillMaxHeight().background(Hud.cyan))
    }
}

/** Machine estimate and tracking are independent: touch play always retains the meter. */
@Composable private fun MachineSuspicion(g: Game) {
    val ctx = LocalContext.current
    val cameraAllowed = ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var value by remember { mutableStateOf(0.0) }
    var reason by remember { mutableStateOf(R.string.machine_reason_answer) }
    var event by remember { mutableStateOf<Int?>(null) }
    var tracking by remember { mutableStateOf(false) }
    LaunchedEffect(g.room) {
        while (true) {
            g.room?.let { value = it.index; reason = it.reason; event = it.event }
            tracking = g.faceWatch.latest?.let { it.face && g.faceWatch.now() - it.t in 0..500 } == true
            delay(100)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.TopStart).fillMaxHeight(0.55f).width(56.dp).vGaugeBacking(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.machine_suspicion), color = C.text, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                lineHeight = 10.sp, modifier = Modifier.clearAndSetSemantics {})
            val release = stringResource(R.string.machine_release)
            Row(Modifier.padding(top = 3.dp).weight(1f).semantics(mergeDescendants = true) { contentDescription = release }) {
                Canvas(Modifier.width(12.dp).fillMaxHeight().semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..100f)
                }) { vBar((value / 100).toFloat(), androidx.compose.ui.graphics.lerp(Hud.cyan, C.red, (value / 100).toFloat()), 0.5f) }
                Box(Modifier.fillMaxHeight().padding(start = 4.dp)) {
                    Text("50", color = Color.White, fontSize = 9.sp, modifier = Modifier.align(Alignment.Center).clearAndSetSemantics {})
                }
            }
        }
        Column(Modifier.align(Alignment.TopEnd).widthIn(max = 130.dp).vGaugeBacking(), horizontalAlignment = Alignment.End) {
            Text(stringResource(if (g.room == null) R.string.machine_preparing else reason), color = C.text, fontSize = 11.sp, lineHeight = 13.sp)
            event?.takeIf { g.room != null }?.let { Text(stringResource(it), color = C.dim, fontSize = 11.sp, lineHeight = 13.sp) } // face/composure: never over the verdict
            Text(stringResource(if (!g.watching || !cameraAllowed) R.string.machine_face_off else if (tracking) R.string.machine_tracking else R.string.machine_no_signal),
                color = C.dim, fontSize = 11.sp)
        }
    }
}

/** Overload cues: a banner (styled like the composure cue) above the officer's line, shown only while a cue is active. */
@Composable private fun MachineCues(g: Game) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(Square).background(Color(0xCC06303D))
        .border(1.dp, Hud.cyan.copy(alpha = 0.7f), Square).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (g.overloadWarning) Text(stringResource(R.string.overload_warning), color = Hud.cyan, fontSize = 12.sp)
        if (g.overloadCue) {
            Text(stringResource(R.string.overload_now), color = Hud.cyan,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            Text(if (g.overloadPips == 0) "○ ○" else if (g.overloadPips == 1) "● ○" else "● ●", color = Hud.cyan,
                modifier = Modifier.clearAndSetSemantics { contentDescription = "${g.overloadPips} / 2" })
            if (g.overloadPractice) Text(stringResource(R.string.overload_practice), color = C.text, fontSize = 12.sp)
        }
        if (g.scannerInterrupted) Text(stringResource(R.string.overload_success), color = Hud.cyan)
    }
}

@Composable private fun MicButton(g: Game) = MicButton(g.mic, g.inputMode, g.micDenied, g::press, g::release)

/** The desk's mic, also the city scenes' ([CityScreen]): [press]/[release] on each touch, whatever the mode. */
@Composable private fun MicButton(mic: Mic, inputMode: InputMode, micDenied: Boolean, press: () -> Unit, release: () -> Unit) {
    val active = mic == Mic.HEARING
    val usable = mic == Mic.READY || active
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(1f, 1.12f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    // Frame breathes while the mic is open; still when the system animations are off.
    val animateMic = Motion.allowed()
    val frameAlpha = if (animateMic && usable) 0.6f + 0.4f * (pulse - 1f) / 0.12f else 1f
    // Auto mode: one look for the whole time the mic is open. VAD's "speech started" lags the voice by ~0.3 s,
    // so a separate "speaking" look read as the mic reacting late (owner).
    val label = when (mic) {
        Mic.HEARING -> stringResource(if (inputMode == InputMode.HOLD) R.string.mic_release else R.string.mic_listening)
        Mic.READY -> if (micDenied) "" else stringResource(if (inputMode == InputMode.HOLD) R.string.mic_hold else R.string.mic_listening)
        Mic.OFFICER_SPEAKING -> stringResource(R.string.mic_officer_speaking)
        Mic.PROCESSING -> "…"
        Mic.OFF -> ""
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp)
            .scale(if (active || (inputMode == InputMode.AUTO && usable)) pulse else 1f)
            .clip(Square).background(C.card)
            .border(3.dp, if (usable) C.ivory.copy(alpha = frameAlpha) else C.line, Square)
            .alpha(if (usable) 1f else 0.6f)
            .pointerInput(inputMode) {
                awaitEachGesture {
                    awaitFirstDown()
                    press()
                    waitForUpOrCancellation()
                    release()
                }
            },
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Mic, null, tint = if (usable) C.ivory else C.dim, modifier = Modifier.size(40.dp))
        }
        Text(label, color = C.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        if (micDenied) {
            val ctx = LocalContext.current
            OutlinedButton({
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }, shape = Square, border = BorderStroke(1.dp, C.line)) { Text(stringResource(R.string.mic_open_settings), color = C.text) }
        }
    }
}

@Composable private fun ResultScreen(g: Game) {
    BackHandler(enabled = g.buildingResult) { g.continueMachineResult() }
    val broken = g.resultBroken
    val decision = g.resultDecision
    val passed = decision?.cleared != false
    val lang = Language.of(g.resultLang)
    val view = LocalView.current
    LaunchedEffect(Unit) { g.resultHaptic(view, passed) }
    val accent = if (broken) Hud.cyan else if (passed) C.green else C.red
    Backdrop(if (passed) "success_bg" else "fail_bg", tint = if (passed) Color(0xFF07140D) else Color(0xFF16070A)) {
        Gutter(scrollable = true) {
            Spacer(Modifier.weight(1f))
            if (!g.buildingResult && decision != Decision.CAUGHT) { // caught: no stamp, the implant's data is gone
                Text(if (broken) stringResource(R.string.result_unit_unresponsive) else if (passed) lang.stampPass else lang.stampFail, style = display(30, 3.0).copy(color = accent)
                    .let { if (broken) it else it.copy(textDirection = TextDirection.Content) },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.CenterHorizontally).then(if (broken) Modifier.glitch(idleMs = 600L..1800L) else Modifier).rotate(-8f)
                        .border(4.dp, accent, Square).padding(horizontal = 18.dp, vertical = 8.dp))
                Spacer(Modifier.height(36.dp))
            }
            Text(stringResource(if (g.buildingResult) R.string.machine_debrief else if (broken) R.string.result_unit_offline else if (passed) R.string.result_crossing_logged else R.string.result_mission_failed), style = display(40, 3.0).copy(color = if (passed) C.text else Color(0xFFF2B8B2)),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(if (g.buildingResult) storyTestVerdict(decision ?: Decision.REFUSED, uiLang()) else stringResource(when {
                broken -> R.string.result_msg_broken
                passed && g.resultInRoom -> R.string.result_msg_released
                passed -> R.string.result_msg_passed
                else -> endingText(decision, g.resultInRoom, g.resultKind)
            }),
                color = C.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
            if (!g.buildingResult && foreignTongue(uiLang(), g.resultLang)) {
                ForeignTongueTag()
                // A story pass goes to the finale and never reaches this line.
                if (passed) Text(stringResource(R.string.result_foreign_cleared), color = C.dim,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            Spacer(Modifier.weight(1f))
            if (!g.restoredResult && !g.resultInRoom) Panel(border = if (passed) C.line else accent.copy(alpha = 0.6f)) { // the officer's log died with the app
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (broken) Icons.Default.Memory else if (passed) Icons.Default.Flight else Icons.Default.Search, null, tint = accent)
                    Spacer(Modifier.width(12.dp))
                    Text(if (broken) pluralStringResource(R.plurals.result_broken_log, g.mind.tricks, displayUnit(g.officerId), g.mind.tricks)
                        else if (passed && g.tooClean) stringResource(R.string.result_too_clean)
                        else if (passed) pluralStringResource(R.plurals.result_passed_log, g.mind.turns, g.mind.turns) else stringResource(g.mind.verdict()),
                        color = C.text, fontSize = 14.sp)
                }
                // What tipped a refusal: the words heard and the cover fact they were checked against.
                if (!passed) g.slipLines().forEach { Text(it, color = C.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
            g.machineResult?.takeIf { g.resultInRoom && !g.restoredResult }?.let { record ->
                val resources = LocalContext.current.resources
                Panel {
                    Text(stringResource(R.string.machine_debrief), color = C.dim, fontSize = 13.sp)
                    Text(record.text { key, count -> resources.getQuantityString(key, count, count) },
                        color = C.text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                    if (passed) harvestLine(g)?.let { Text(it, color = C.dim, fontSize = 12.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 6.dp)) }
                    if (passed && g.payloadPlanted) g.payload?.let { Text(stringResource(R.string.room_payload_planted, it.word(uiLang())),
                        color = C.dim, fontSize = 12.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 2.dp)) }
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
            if (passed || g.buildingResult) PrimaryButton(stringResource(R.string.continue_btn)) { g.continueMachineResult() }
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

/** Act-2 city encounter (a taxi over the pass result screen, a bar or hotel over the summary) (CityEncounters.kt): the machine's line, one held answer or Skip, the outcome. */
@Composable private fun CityScreen(g: Game, c: CityScene) {
    BackHandler { g.leaveCity() }
    val ui = uiLang()
    val subtitle = when (c.kind) { CityKind.TAXI -> R.string.city_taxi_line; CityKind.HOTEL -> R.string.city_hotel_line; CityKind.BAR -> R.string.city_bar_line }
    val intro = c.step == CityStep.INTRO
    val fades = Motion.allowed()
    var lit by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { lit = true }
    val sceneAlpha by animateFloatAsState(if (intro) 0f else 1f, tween(if (fades) 700 else 0), label = "cityScene")
    val captionAlpha by animateFloatAsState(if (intro && lit) 1f else 0f, tween(if (fades && intro) 900 else 0), label = "cityCaption")
    Box(Modifier.fillMaxSize().background(Color.Black)) {
    val scene = c.kind.name.lowercase()
    val region = regionOf(c.lang, c.country)
    // A region's own foreground (its classic cab: fg_taxi_<region>, in the language pack) before the shared one.
    // Most specific first: the airport's own city view (taxi), the country's room and robot, then the language region's, then shared.
    val bg = (if (c.kind == CityKind.TAXI) rememberAsset("bg_taxi_${c.airport}") else null)
        ?: rememberAsset("bg_${scene}_${c.country}") ?: rememberAsset("bg_${scene}_$region")
    val own = rememberAsset("fg_${scene}_${c.country}") ?: rememberAsset("fg_${scene}_$region"); val fg = own ?: rememberAsset("fg_$scene")
    // The bar's foreground is two layers: the bartender (fg above) and the counter in front of him (absent -> the single foreground, as before).
    val counter = if (c.kind != CityKind.BAR) null else rememberAsset("fg_bar_counter_${c.country}") ?: rememberAsset("fg_bar_counter")
    // The taxi's driving state (robot facing forward), shown once the answer is judged; absent -> the standing car stays.
    val drive = if (c.kind != CityKind.TAXI) null else rememberAsset("fg_taxi_${c.country}_drive") ?: rememberAsset("fg_taxi_${region}_drive")
    val drinkArt = rememberAsset(c.drink?.let { if (it == Drink.SPECIAL) "drink_$region" else "drink_${it.name.lowercase()}" }.orEmpty())
    // The tallest bottom block seen (mic, or the outcome): the layered art is sized so the counter stays above it.
    var panelsPx by remember { mutableIntStateOf(0) }
    // A served drink ends the bar in two beats on one quiet Continue (a tap leaves at any time): the dialogue, then, CITY_MOVE_ON_MS later by itself,
    // the bartender moves on and the drink stands alone. The effect dies with the composition, so a left scene never fires into the next one.
    val served = c.kind == CityKind.BAR && c.step == CityStep.DONE && c.drink != null
    var moved by remember(c.kind) { mutableStateOf(false) }
    LaunchedEffect(served) { if (served) { delay(CITY_MOVE_ON_MS); moved = true } }
    val last = served && moved
    val content: @Composable BoxScope.() -> Unit = {
        if (c.ride) Gutter {
            // A ride has no panels and no timer: the player rides as long as they like, then walks out.
            Spacer(Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.city_ride_walk)) { g.leaveCity() }
        } else Gutter(scrollable = true) {
            // Top end, clear of the bartender's head; gone once the order is in (seen on the phone: it covered the face).
            if (c.kind == CityKind.BAR && c.step >= CityStep.READY && c.step != CityStep.DONE)
                // Below the bartender's head (the head fills the top ~30% of the screen), on the open right side.
                Box(Modifier.fillMaxWidth().padding(top = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.28f),
                    contentAlignment = Alignment.TopEnd) { BarMenuCard(c, region) }
            Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth().onSizeChanged { panelsPx = maxOf(panelsPx, it.height) }) {
                // The same dialogue cards as the story scenes: the character on the left, the player on the right, narration centred.
                androidx.compose.animation.AnimatedVisibility(!last, exit = androidx.compose.animation.fadeOut(tween(if (fades) 500 else 0))) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val cardMax = maxWidth * 0.82f; val narrMax = maxWidth * 0.88f
                    val line = CITY_LINES[c.lang]?.get(c.kind).orEmpty()
                    val who = stringResource(when (c.kind) { CityKind.TAXI -> R.string.city_speaker_driver; CityKind.HOTEL -> R.string.city_speaker_kiosk; CityKind.BAR -> R.string.city_bar_bartender })
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DialogueCard(PrologueSpeaker.STAFF, PrologueSide.LEFT, who, line, line,
                            Modifier.align(AbsoluteAlignment.Left).widthIn(max = cardMax), if (ui != c.lang) stringResource(subtitle) else null)
                        if (c.step == CityStep.DONE) Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val ok = c.adequate == true
                            if (c.heard.isNotBlank()) DialogueCard(PrologueSpeaker.YOU, PrologueSide.RIGHT, stringResource(R.string.prologue_you), c.heard, c.heard,
                                Modifier.align(AbsoluteAlignment.Right).widthIn(max = cardMax))
                            val rumour = if (c.kind == CityKind.BAR && ok) c.rumour?.let { CITY_RUMOURS[it] } else null
                            val rumourText = rumour?.let { stringResource(it.res) }
                            if (rumour != null && !rumour.tv) DialogueCard(PrologueSpeaker.STAFF, PrologueSide.LEFT, who, rumourText!!, rumourText,
                                Modifier.align(AbsoluteAlignment.Left).widthIn(max = cardMax))
                            val outcome = stringResource(when (c.kind) {
                                CityKind.TAXI -> if (ok) R.string.city_taxi_ok else R.string.city_taxi_no
                                CityKind.HOTEL -> if (ok) R.string.city_hotel_ok else R.string.city_hotel_no
                                CityKind.BAR -> if (ok) R.string.city_bar_ok else R.string.city_bar_no
                            })
                            // A served drink's outcome ("the bartender moves on") comes on its own after the pause, below.
                            listOfNotNull(if (rumour?.tv == true) stringResource(R.string.city_bar_tv, rumourText!!) else null, if (served) null else outcome).forEach {
                                NarrationLine(it, it, Modifier.align(Alignment.CenterHorizontally).widthIn(max = narrMax))
                            }
                        }
                    }
                }
                }
                androidx.compose.animation.AnimatedVisibility(last, enter = androidx.compose.animation.fadeIn(tween(if (fades) 500 else 0))) {
                    val t = stringResource(R.string.city_bar_ok)
                    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) { NarrationLine(t, t) }
                }
                Spacer(Modifier.height(16.dp))
                if (c.step == CityStep.DONE) {
                    if (c.kind == CityKind.BAR) QuietContinue { g.leaveCity() } else PrimaryButton(stringResource(R.string.continue_btn)) { g.leaveCity() }
                } else {
                    val mic = when (c.step) { CityStep.READY -> Mic.READY; CityStep.HEARING -> Mic.HEARING; CityStep.CHECKING -> Mic.PROCESSING; else -> Mic.OFF }
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        MicButton(mic, g.inputMode, g.micDenied, g::cityPress, g::cityRelease)
                        TextButton({ g.leaveCity() }) { Text(stringResource(R.string.city_skip), color = C.text) }
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().alpha(sceneAlpha)) {
        if (bg != null && fg != null) LayeredScene(c, bg, fg, counter, last, drive, drinkArt, panelsPx, content)
        else Backdrop(c.kind.art, content = content)
    }
    if (intro) Box(Modifier.fillMaxSize().background(Color.Black).clickable(role = Role.Button,
        onClickLabel = stringResource(R.string.continue_btn)) { g.skipCityIntro() }, contentAlignment = Alignment.Center) {
        Text(stringResource(if (c.ride) cityRideRes(c.intro) else cityIntroRes(c.kind, c.intro)), color = C.ivory, fontSize = 18.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp).alpha(captionAlpha).semantics { liveRegion = LiveRegionMode.Polite })
    }
    }
}

/** A low-emphasis Continue (the bar's one button): small dim text, bottom centre, a 48 dp touch target. */
@Composable private fun QuietContinue(onClick: () -> Unit) = Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)
    .clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
    Text(stringResource(R.string.continue_btn), color = C.dim, fontSize = 14.sp, letterSpacing = 2.sp, textAlign = TextAlign.Center)
}

/** A served drink stands in the foreground, on the front of the counter, close to the player (fractions of the art's height and width). The bartender art is ~8 px per cm at 1376 px tall (head ~148 px); the drink is drawn ×1.5 that. */
private const val DRINK_BASE_SERVED = 0.80f // above the "moves on" line and the quiet Continue (phone check)
private const val DRINK_PER_CM_SERVED = 0.0120f
private const val DRINK_X_SERVED = 0.62f

/**
 * Blurred backdrop, then the sharp foreground (same box, so they line up), a light tint, the drink on the counter and
 * [content]. The art fills the screen, bottom-anchored and centred. Only for a served drink (in the foreground) does it grow
 * until the front of the counter ([DRINK_BASE_SERVED]) clears the Continue (the last beat); the dialogue before it needs no zoom.
 */
@Composable private fun LayeredScene(c: CityScene, bg: ImageBitmap, fg: ImageBitmap, counter: ImageBitmap?, last: Boolean, drive: ImageBitmap?, drink: ImageBitmap?, panelsPx: Int, content: @Composable BoxScope.() -> Unit) {
    val density = LocalDensity.current
    val fades = Motion.allowed()
    val tilt = rememberParallax() // zero under Reduce motion; read only in the layers below
    val driving = cityDriving(c.kind, c.step)
    val driveAlpha by animateFloatAsState(if (driving && drive != null) 1f else 0f, tween(if (fades && !c.ride) 400 else 0), label = "cityDrive") // a ride is already driving as the scene fades in
    // Driving: a slow push-in on the backdrop (held once there; a ride has no end, so it breathes in and out forever) and a gentle ~1.25 Hz road sway on the foreground.
    val push = remember { Animatable(1f) }
    val road = remember { Animatable(0f) }
    LaunchedEffect(driving && fades) {
        if (!(driving && fades)) { push.snapTo(1f); road.snapTo(0f); return@LaunchedEffect }
        val ease = androidx.compose.animation.core.FastOutSlowInEasing
        if (c.ride) launch { while (true) { push.animateTo(1.10f, tween(14000, easing = ease)); push.animateTo(1f, tween(14000, easing = ease)) } }
        else launch { push.animateTo(1.12f, tween(6000, easing = ease)) }
        while (true) { road.snapTo(0f); road.animateTo(2 * Math.PI.toFloat(), tween(800, easing = androidx.compose.animation.core.LinearEasing)) }
    }
    val blur = when (c.kind) { CityKind.BAR -> 4.dp; CityKind.HOTEL -> 4.dp; CityKind.TAXI -> 2.dp }
    val shown = last // the bar's last beat: the drink alone on the counter
    // The bartender (middle layer) walks off when the last beat starts; without a separate counter layer he is the whole foreground and stays.
    val bartenderAlpha by animateFloatAsState(if (shown && counter != null) 0f else 1f, tween(if (fades) 1200 else 0), label = "cityBartender")
    val drinkAlpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (fades) 400 else 0), label = "cityDrink")
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).clipToBounds()) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        val ratio = bg.width.toFloat() / bg.height
        // 48 dp: the gutter's bottom padding and the gap above the panels.
        val reserved = if (panelsPx == 0 && !shown) 0f else (if (shown) 0 else panelsPx) + with(density) { 48.dp.toPx() + WindowInsets.safeDrawing.getBottom(density) }
        // Only the bar keeps its counter clear of the panels; the taxi and the hotel just fill the screen (no zoom).
        val bar = c.kind == CityKind.BAR
        // The bar zooms only for the served drink (its counter must clear the hint); while ordering it fills the screen.
        val target = maxOf(h, w / ratio, if (bar && shown) reserved / (1f - DRINK_BASE_SERVED) else 0f)
        val artH by animateFloatAsState(target, tween(if (fades) 300 else 0), label = "cityArt")
        val artW = artH * ratio
        Box(Modifier.fillMaxSize().layout { m, cons ->
            val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(artW.roundToInt(), artH.roundToInt()))
            layout(cons.maxWidth, cons.maxHeight) { p.place((cons.maxWidth - p.width) / 2, cons.maxHeight - p.height) }
        }) {
            Image(bg, null, Modifier.fillMaxSize().graphicsLayer {
                val shift = CITY_BACK_DP.dp.toPx()
                // The taxi's street view is enlarged from the bottom edge so its horizon (the end of the street) rises into the
                // windshield instead of hiding behind the dashboard (owner: the cab showed sky).
                val taxi = c.kind == CityKind.TAXI
                val sc = cityBackdropScale(size.width, shift) * push.value * (if (taxi) TAXI_VIEW_RAISE else 1f)
                if (taxi) transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                scaleX = sc; scaleY = sc
                translationX = tilt.value.x * shift; translationY = tilt.value.y * shift
            }.blur(blur), contentScale = ContentScale.FillBounds)
            // The bartender: a slight middle layer.
            if (counter != null && bartenderAlpha > 0f) Box(Modifier.fillMaxSize().graphicsLayer {
                val shift = CITY_MID_DP.dp.toPx()
                scaleX = 1.03f; scaleY = 1.03f
                translationX = -tilt.value.x * shift; translationY = -tilt.value.y * shift
            }) { Image(fg, null, Modifier.fillMaxSize().alpha(bartenderAlpha), contentScale = ContentScale.FillBounds) }
            // Foreground (the counter, when it is its own layer) and drink move together, against the backdrop.
            Box(Modifier.fillMaxSize().graphicsLayer {
                val shift = CITY_FRONT_DP.dp.toPx()
                scaleX = 1.03f; scaleY = 1.03f // an edge-to-edge foreground (the cab interior) never shows the backdrop at its sides
                translationX = -tilt.value.x * shift + cos(road.value) * 0.6.dp.toPx()
                translationY = -tilt.value.y * shift + sin(road.value) * 1.5.dp.toPx()
            }) {
                if (drive == null || driveAlpha < 1f) Image(counter ?: fg, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                if (drive != null && driveAlpha > 0f) Image(drive, null, Modifier.fillMaxSize().alpha(driveAlpha), contentScale = ContentScale.FillBounds)
                if (drink != null && drinkAlpha > 0f) {
                    val dh = artH * DRINK_PER_CM_SERVED * drinkCm(c.drink, regionOf(c.lang, c.country)); val dw = dh * drink.width / drink.height
                    Image(drink, null, Modifier.size(with(density) { dw.toDp() }, with(density) { dh.toDp() })
                        .offset { IntOffset((artW * DRINK_X_SERVED - dw / 2).roundToInt(), (artH * DRINK_BASE_SERVED - dh).roundToInt()) }
                        .alpha(drinkAlpha), contentScale = ContentScale.FillBounds)
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Color(0xFF0A1530).copy(alpha = 0.1f)))
        // A soft scrim under the mic, which sits straight on the art.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.5f))))
        content()
    }
}

/** The bar's menu card (cream paper, double border): a stamped alcohol-free sign, then water, coffee and the region's special, each with a gloss in the player's language. */
@Composable private fun BarMenuCard(c: CityScene, region: String) {
    val items = BAR_MENU[region] ?: return
    val ui = uiLang()
    val foreign = ui != c.lang
    // Ink on cream: 12:1 (ink), 7:1 (soft ink), 9:1 (stamp red); all above 4.5:1.
    val paper = Color(0xFFF6EBD0); val ink = Color(0xFF2B1D12); val soft = Color(0xFF5A4630); val stamp = Color(0xFF8A1C1C)
    Column(Modifier.width(IntrinsicSize.Max).widthIn(max = 170.dp).clip(Square).background(paper).border(1.5.dp, soft, Square).padding(3.dp).border(0.75.dp, soft, Square).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.graphicsLayer { rotationZ = -3f }.border(1.5.dp, stamp, Square).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.NoDrinks, null, tint = stamp, modifier = Modifier.size(20.dp))
            Column(Modifier.padding(start = 6.dp)) {
                Text(BAR_SIGN[region].orEmpty(), color = stamp, fontFamily = FontFamily.Serif, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (foreign) Text(stringResource(R.string.city_no_alcohol), color = stamp, fontFamily = FontFamily.Serif, fontSize = 11.sp)
            }
        }
        Text(stringResource(R.string.city_menu_header), color = ink, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 15.sp,
            letterSpacing = 4.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        items.forEach { item ->
            val gloss = when (item.drink) {
                Drink.WATER -> stringResource(R.string.city_drink_water)
                Drink.COFFEE -> stringResource(R.string.city_drink_coffee)
                Drink.SPECIAL -> item.gloss.orEmpty()
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Bottom) {
                Text(item.native, color = ink, fontFamily = FontFamily.Serif, fontSize = 16.sp)
                if (foreign && !gloss.equals(item.native, ignoreCase = true)) {
                    // Dotted leader between the name and its gloss.
                    Box(Modifier.weight(1f).widthIn(min = 12.dp).height(1.dp).padding(horizontal = 4.dp).offset(y = (-4).dp).drawBehind {
                        drawLine(soft, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 3.dp.toPx())))
                    })
                    Text(gloss, color = soft, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable private fun SummaryScreen(g: Game) = Box(Modifier.fillMaxSize().background(C.bg)) {
    val m = g.mind
    Gutter(scrollable = true) { // a machine-room run adds a row: Main menu fell off a phone screen
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.summary_title), style = display(26, 2.0), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
        Panel {
            Stat(Icons.Default.Flight, stringResource(R.string.summary_destination), "${g.country.uiName(uiLang())} · ${coverIsolate(g.airport.uiName(uiLang()), uiLang())}")
            Stat(Icons.Default.Description, stringResource(R.string.summary_answers), "${m.turns}")
            Stat(Icons.Default.Schedule, stringResource(R.string.summary_result), stringResource(when {
                m.crashed -> R.string.summary_unit_offline; m.decision?.cleared == true -> R.string.summary_cleared; else -> R.string.summary_secondary }),
                when { m.crashed -> Hud.cyan; m.decision?.cleared == true -> C.green; else -> C.red })
            Stat(Icons.Default.BarChart, stringResource(R.string.summary_behaviour, g.deskUnitName), stringResource(m.demeanor()))
            g.room?.let { Stat(Icons.Default.Psychology, stringResource(R.string.summary_tn_index), pluralStringResource(R.plurals.summary_tn_value, it.asked, it.index, it.asked),
                if (it.index <= 50) C.green else C.red) }
            val cr = g.career
            Stat(Icons.Default.MilitaryTech, stringResource(R.string.summary_rank), listOfNotNull(rankName(cr),
                stringResource(R.string.machine_progress, cr.sessions, cr.points), stringResource(R.string.stat_cleared, cr.cleared), stringResource(R.string.summary_best_streak, cr.best),
                cr.rooms.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.summary_rooms, it, it) },
                cr.broken.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.stat_broken, it, it) }).joinToString(" · "))
        }
        Spacer(Modifier.height(16.dp))
        g.behaviorDebrief()?.let { BehaviorPanel(it); Spacer(Modifier.height(16.dp)) }
        Panel {
            Text("“${stringResource(m.verdict())}”", color = C.text, fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            g.slipLines().forEach { Text(it, color = C.dim, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) }
        }
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
                        Text(uiLang().let { ui -> Language.shipped.filter { it.code in g.languages }.joinToString { it.uiName(ui) } }.replaceFirstChar { it.titlecase() }, color = C.dim, fontSize = 12.sp)
                        Language.shipped.filter { it.code in g.languages && g.slowLang(it.code) }.takeIf { it.isNotEmpty() }?.let { slow ->
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
                val uiCodes = listOf("") + Language.shipped.map { it.code }
                Text(stringResource(R.string.settings_ui_language), color = C.text, fontSize = 16.sp)
                Text(stringResource(R.string.settings_ui_language_help), color = C.dim, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Chips(listOf(stringResource(R.string.settings_system_default)) + Language.shipped.map { it.native },
                    uiCodes.indexOf(lm.applicationLocales.takeIf { !it.isEmpty }?.get(0)?.language ?: "").coerceAtLeast(0)) {
                    lm.applicationLocales = LocaleList.forLanguageTags(uiCodes[it]) // recreates the activity; Game (ViewModel) survives
                }
                HorizontalDivider(Modifier.padding(vertical = 14.dp), color = C.line)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MusicNote, null, tint = C.text); Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.settings_music), color = C.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    GameSwitch(g.musicOn, g::setMusic)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Face, null, tint = C.text); Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.settings_officer_personality), color = C.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    GameSwitch(g.officerPersonality, g::changeOfficerPersonality)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.GraphicEq, null, tint = C.text); Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.settings_sfx), color = C.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    GameSwitch(g.sfxOn, g::setSfx)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Visibility, null, tint = C.text); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_camera), color = C.text, fontSize = 16.sp)
                        Text(stringResource(R.string.settings_camera_help), color = C.dim, fontSize = 12.sp)
                    }
                    GameSwitch(g.cameraOn, g::setCamera)
                }
                TextButton({ g.screen = Screen.CAMERA_TEST }, Modifier.padding(start = 30.dp)) { Text(stringResource(R.string.settings_camera_test), color = Hud.cyan) }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Vibration, null, tint = C.text); Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.settings_haptics), color = C.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    GameSwitch(g.haptics, g::setHaptic)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MotionPhotosOff, null, tint = C.text); Spacer(Modifier.width(14.dp))
                    ReduceMotionRow(g, Modifier.weight(1f))
                }
                HorizontalDivider(Modifier.padding(vertical = 14.dp), color = C.line)
                Row(Modifier.fillMaxWidth().clickable { g.replayPrologue() }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlayArrow, null, tint = C.text); Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.prologue), color = C.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.dim)
                }
                if (g.thalassiaSeen) {
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth().clickable { g.openThalassia() }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayArrow, null, tint = C.text); Spacer(Modifier.width(14.dp))
                        Text(stringResource(R.string.thal_replay), color = C.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.dim)
                    }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BugReport, null, tint = C.text); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_diagnostics), color = C.text, fontSize = 16.sp)
                        Text(stringResource(R.string.settings_diagnostics_help), color = C.dim, fontSize = 12.sp)
                    }
                    GameSwitch(g.diagnostics, g::toggleDiagnostics)
                }
            }
            Spacer(Modifier.height(12.dp))
            var confirmReset by remember { mutableStateOf(false) }
            val canReset = g.canResetProgress
            OutlinedButton({ confirmReset = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = canReset, shape = Square,
                border = BorderStroke(1.dp, if (canReset) C.red else C.line)) {
                Text(stringResource(R.string.settings_reset), color = if (canReset) C.red else C.dim, fontSize = 16.sp)
            }
            if (confirmReset) AlertDialog(
                onDismissRequest = { confirmReset = false },
                shape = Square, containerColor = C.card, titleContentColor = C.text, textContentColor = C.dim,
                title = { Text(stringResource(R.string.reset_title)) },
                text = { Text(stringResource(R.string.reset_text)) },
                confirmButton = { TextButton({ confirmReset = false; g.resetProgress() }) { Text(stringResource(R.string.reset_confirm), color = C.red) } },
                dismissButton = { TextButton({ confirmReset = false }) { Text(stringResource(R.string.reset_cancel), color = C.text) } },
            )
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.settings_about), style = display(15, 2.0).copy(color = C.dim))
            Spacer(Modifier.height(8.dp))
            Panel {
                About(Icons.Default.PhoneAndroid, stringResource(R.string.about_on_phone))
                About(Icons.Default.WifiOff, stringResource(R.string.about_offline))
                About(Icons.Default.Place, pluralStringResource(R.plurals.about_destinations, Country.shipped.size, Country.shipped.size, Language.shipped.size))
                About(Icons.Default.Person, stringResource(R.string.about_new_officer))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.credits_blurb, Language.shipped.size), color = C.dim, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.disclaimer_fiction), color = C.dim, fontSize = 13.sp)
            }
            Spacer(Modifier.height(28.dp))
            Text(stringResource(R.string.settings_footer), style = display(20, 3.0).copy(color = C.line.copy(alpha = 1f), fontWeight = FontWeight.Medium),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable private fun Choice(options: List<String>, selected: Int, onSelect: (Int) -> Unit) =
    Row(Modifier.fillMaxWidth().clip(Square).border(1.dp, C.line, Square)) {
        options.forEachIndexed { i, o ->
            Box(Modifier.weight(1f).background(if (i == selected) C.ivory else Color.Transparent).clickable { onSelect(i) }
                .padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(o, color = if (i == selected) C.navy else C.dim, fontSize = 14.sp)
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
            Box(Modifier.clip(Square).background(if (i == selected) C.ivory else Color.Transparent)
                .border(1.dp, if (i == selected) C.ivory else C.line, Square)
                .selectable(i == selected, enabled, Role.RadioButton) { onSelect(i) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(o, color = if (i == selected) C.navy else C.dim, fontSize = 14.sp)
            }
        }
    }

/** First run picks languages before anything downloads; later visits add packs. Installed packs stay ticked. */
@Composable private fun LanguagesScreen(g: Game) = Box(Modifier.fillMaxSize().background(C.bg)) {
    val home = remember { Nationality.fromLocale(java.util.Locale.getDefault()) }
    var picked by remember { mutableStateOf(g.languages.ifEmpty { setOfNotNull(home?.nameLang) }) }
    val firstRun = g.languages.isEmpty()
    val adding = picked - g.languages
    DisposableEffect(Unit) { onDispose { g.clearLanguagesGap() } }
    // First run: the story needs the passport's language (the phone's region) and one more country, so Continue waits for both.
    val gap = if (firstRun) languageGap(picked, home) else g.languagesGap
    Gutter {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!firstRun) IconButton({ g.go(if (g.languagesFrom == Screen.VISIT) Screen.START else Screen.SETTINGS) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = C.text) }
            Text(stringResource(R.string.languages_title), style = display(24, 2.0))
        }
        Text(stringResource(if (firstRun) R.string.languages_first_run else R.string.languages_add),
            color = C.dim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Language.shipped.forEach { l ->
                val installed = l.code in g.languages
                val on = l.code in picked
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(Square)
                    .background(if (on) C.card else Color.Transparent).border(1.dp, if (on) C.ivory else C.line, Square)
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
        gap?.let { Text(if (it.lang != null) stringResource(R.string.languages_need_home, Language.of(it.lang).uiName(uiLang())) else stringResource(R.string.languages_need_more),
            color = C.amber, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) }
        PrimaryButton(stringResource(if (firstRun) R.string.languages_download else R.string.languages_add_btn), enabled = adding.isNotEmpty() && !(firstRun && gap != null)) { g.install(adding) }
    }
}

/** First-run input and face-reading choices, before the prologue. */
@Composable private fun InputModeScreen(g: Game) = Box(Modifier.fillMaxSize().background(C.bg)) {
    var pick by rememberSaveable { mutableStateOf(g.inputMode) }
    val ctx = LocalContext.current
    val askCamera = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        g.setCamera(granted)
        g.setupFinished(pick)
    }
    fun finish() {
        if (g.cameraOn && ctx.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            askCamera.launch(android.Manifest.permission.CAMERA)
        else g.setupFinished(pick)
    }
    val askMic = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        g.micDenied = !granted
        finish()
    }
    Gutter {
        Text(stringResource(R.string.setup_input_title), style = display(24, 2.0))
        Text(stringResource(R.string.setup_input_note), color = C.dim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            listOf(InputMode.AUTO to (R.string.setup_input_auto to R.string.settings_auto_help),
                InputMode.HOLD to (R.string.settings_hold_to_speak to R.string.settings_hold_help)).forEach { (m, text) ->
                val on = m == pick
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(Square)
                    .background(if (on) C.card else Color.Transparent).border(1.dp, if (on) C.ivory else C.line, Square)
                    .selectable(on, role = Role.RadioButton) { pick = m }
                    .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(text.first), color = C.text, fontSize = 17.sp)
                        Text(stringResource(text.second), color = C.dim, fontSize = 12.sp)
                    }
                    RadioButton(on, null)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.passport_face_title), color = C.text, fontSize = 17.sp)
            Text(stringResource(R.string.passport_face_body), color = C.dim, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.passport_face_switch), color = C.text, modifier = Modifier.weight(1f))
                GameSwitch(g.cameraOn, g::setCamera)
            }
            Text(stringResource(R.string.setup_face_disclosure), color = C.dim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(stringResource(R.string.continue_btn)) {
            if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                askMic.launch(android.Manifest.permission.RECORD_AUDIO)
            else { g.micDenied = false; finish() }
        }
    }
}

/** The full identity editor in both acts, followed by photo and face-reading consent. */
@Composable private fun PassportFlow(g: Game) {
    var introSeen by rememberSaveable { mutableStateOf(false) }
    if (g.act1Stage == Act1Stage.PASSPORT && g.passportFrom != Screen.SETTINGS && !introSeen) {
        StoryCaption(null, stringResource(R.string.act1_passport), g.careerReady) { introSeen = true }
        return
    }
    var editing by remember { mutableStateOf(false) }
    val id = g.identity
    val leave: (() -> Unit)? = if (g.passportFrom == Screen.SETTINGS) ({ g.go(Screen.SETTINGS) }) else null
    val back = if (id != null) ({ editing = false }) else leave
    if (id == null || editing) {
        IdentityScreen(g, id, back) { g.saveIdentity(it) { editing = false } }
    } else PassportScreen(g.faceWatch, g.cameraOn, onReading = g::setCamera, onDone = g::passportFinished, onBack = leave,
        card = { photo -> IdentityCard(id, photo) },
        extra = { TextButton({ editing = true }) { Text(stringResource(R.string.identity_change), color = C.ivory) } })
}

private fun flagOf(n: Nationality) = n.iso.map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")

/** The rolled identity as a passport data page: paper, ink, the nationality's flag. */
@Composable private fun IdentityCard(id: Identity, photo: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(Square).background(C.paper)
        .passportGuilloche().padding(12.dp)) {
        // Passport style: the photo on the leading side (35:45), the data to its right.
        Box(Modifier.weight(0.38f).aspectRatio(35f / 45f)) {
            photo(Modifier.matchParentSize().clip(Square).background(C.card)
                .border(1.dp, C.ink, Square))
            PassportHologram(Modifier.align(Alignment.BottomEnd).offset(x = 5.dp, y = 5.dp).size(26.dp, 30.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(0.62f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Flag(flagOf(id.nationality), Modifier.size(width = 24.dp, height = 16.dp)); Spacer(Modifier.width(8.dp))
                Text(id.nationality.uiName(uiLang()).uppercase(java.util.Locale.forLanguageTag(uiLang())), color = C.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Box(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(id.passportNo, color = C.ink, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                // Match-parent overlay preserves the number line's height. Canvas has no content description.
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                    Canvas(Modifier.size(22.dp, 14.dp).clearAndSetSemantics {}) {
                        val stroke = 1.4.dp.toPx()
                        val inset = stroke / 2
                        val radius = size.height * .29f
                        drawRoundRect(C.ink, Offset(inset, inset), Size(size.width - stroke, size.height - stroke),
                            CornerRadius(1.5.dp.toPx()), style = Stroke(stroke))
                        drawCircle(C.ink, radius, center, style = Stroke(stroke))
                        drawLine(C.ink, Offset(inset, center.y), Offset(center.x - radius, center.y), stroke)
                        drawLine(C.ink, Offset(center.x + radius, center.y), Offset(size.width - inset, center.y), stroke)
                    }
                }
            }
            Text(id.name, color = C.ink, fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp, modifier = Modifier.padding(top = 6.dp))
            if (id.latin != id.name) Text(id.latin, color = C.ink, fontSize = 13.sp)
            @Composable fun field(label: Int, value: String) = Column(Modifier.padding(top = 5.dp)) {
                Text(stringResource(label), color = C.ink.copy(alpha = 0.6f), fontSize = 11.sp)
                Text(value, color = C.ink, fontSize = 13.sp, lineHeight = 15.sp)
            }
            field(R.string.identity_born, id.born); field(R.string.identity_home, id.home)
        }
    }
}

/** Parallel engraved waves: cached geometry, ten-percent ink, always beneath the printed data. */
private fun Modifier.passportGuilloche(): Modifier = drawWithCache {
    val waves = Path()
    val spacing = 7.dp.toPx()
    val amplitude = 5.dp.toPx()
    val wavelength = 90.dp.toPx()
    var y = -amplitude
    while (y < size.height + amplitude) {
        var x = 0f
        while (x <= size.width + 2.dp.toPx()) {
            val waveY = y + amplitude * kotlin.math.sin(x / wavelength * 2f * kotlin.math.PI.toFloat())
            if (x == 0f) waves.moveTo(x, waveY) else waves.lineTo(x, waveY)
            x += 2.dp.toPx()
        }
        y += spacing
    }
    val rainbow = Brush.sweepGradient(listOf(Color(0xFF376A99), Color(0xFF9A528C), Color(0xFFB48A37),
        Color(0xFF438578), Color(0xFF376A99)), center = Offset(size.width / 2f, size.height / 2f))
    val stroke = Stroke(.55.dp.toPx())
    onDrawBehind { drawPath(waves, rainbow, alpha = .10f, style = stroke) }
}

/** One arrival sweep, then a small, slow sheen shift; animation state is read only by drawing lambdas. */
@Composable private fun PassportHologram(modifier: Modifier) {
    val ctx = LocalContext.current
    val animate = Motion.allowed()
    val sweep = remember { androidx.compose.animation.core.Animatable(if (animate) 0f else 1f) }
    val idle = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        sweep.animateTo(1f, tween(1200))
        idle.animateTo(1f, infiniteRepeatable(tween(18000, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Reverse))
    }
    Box(modifier.clip(Square).clearAndSetSemantics {}
        .background(Brush.linearGradient(listOf(Color(0x5563BFC5), Color(0x558C82BA), Color(0x55D6C788))))
        .border(.6.dp, C.ink.copy(alpha = .2f), Square)) {
        Box(Modifier.matchParentSize().graphicsLayer { alpha = .7f }.hologram { sweep.value })
        Box(Modifier.matchParentSize().graphicsLayer { alpha = .18f * sweep.value }
            .hologram { .45f + .10f * idle.value })
        Canvas(Modifier.matchParentSize()) {
            drawOval(C.ink.copy(alpha = .16f), Offset(size.width * .2f, size.height * .16f),
                Size(size.width * .6f, size.height * .68f), style = Stroke(.6.dp.toPx()))
            drawCircle(C.ink.copy(alpha = .16f), size.width * .18f, style = Stroke(.6.dp.toPx()))
        }
    }
}

/** The Reduce motion switch, on the passport screen and in Settings. */
@Composable private fun ReduceMotionRow(g: Game, modifier: Modifier = Modifier) = Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
        Text(stringResource(R.string.settings_reduce_motion), color = C.text, fontSize = 16.sp)
        Text(stringResource(R.string.settings_reduce_motion_help), color = C.dim, fontSize = 12.sp)
    }
    GameSwitch(Motion.reduce, g::toggleReduceMotion)
}

/** Nationality (the phone's region preselected), gender, the name list for non-binary/unsaid, then the dice. */
@Composable private fun IdentityScreen(g: Game, old: Identity?, onBack: (() -> Unit)?, onConfirm: (Identity) -> Unit) = Box(Modifier.fillMaxSize().background(C.bg)) {
    val ui = uiLang()
    val nations = remember(ui) { Nationality.shipped.sortedBy { it.uiName(ui) } }
    var nation by remember { mutableStateOf(old?.nationality ?: Nationality.fromLocale(java.util.Locale.getDefault())) }
    var gender by remember { mutableStateOf(old?.gender) }
    var list by remember { mutableStateOf(old?.nameList ?: NameList.FEMALE) }
    var rolled by remember { mutableStateOf<Identity?>(null) }
    var open by remember { mutableStateOf(false) }
    BackHandler(onBack != null) { onBack?.invoke() }
    Gutter {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = C.text) }
            Text(stringResource(R.string.identity_title), style = display(24, 2.0))
        }
        Text(stringResource(R.string.identity_note), color = C.dim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp))
        Text(stringResource(R.string.report_note), color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        Text(stringResource(R.string.motion_warning), color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        ReduceMotionRow(g, Modifier.padding(bottom = 10.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.identity_nationality), color = C.text, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Box {
                Row(Modifier.fillMaxWidth().clip(Square).border(1.dp, if (nation != null) C.ivory else C.line, Square)
                    .clickable(role = Role.DropdownList) { open = true }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    nation?.let { Flag(flagOf(it), Modifier.size(width = 27.dp, height = 18.dp)); Spacer(Modifier.width(12.dp)) }
                    Text(nation?.uiName(ui) ?: stringResource(R.string.identity_pick_nationality), color = if (nation != null) C.text else C.dim,
                        fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ArrowDropDown, null, tint = C.dim)
                }
                DropdownMenu(open, { open = false }, Modifier.background(C.card).heightIn(max = 420.dp)) {
                    nations.forEach { n ->
                        DropdownMenuItem({ Text(n.uiName(ui), color = C.text) }, { nation = n; rolled = null; open = false },
                            leadingIcon = { Flag(flagOf(n), Modifier.size(width = 24.dp, height = 16.dp).clearAndSetSemantics {}) })
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.identity_gender), color = C.text, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            val genders = Gender.entries
            Chips(listOf(R.string.gender_woman, R.string.gender_man, R.string.gender_nonbinary, R.string.gender_unsaid).map { stringResource(it) },
                gender?.ordinal ?: -1) { gender = genders[it]; rolled = null }
            if (gender == Gender.NONBINARY || gender == Gender.UNSAID) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.identity_names_from), color = C.text, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                Chips(listOf(stringResource(R.string.identity_names_female), stringResource(R.string.identity_names_male)), list.ordinal) {
                    list = NameList.entries[it]; rolled = null
                }
            }
            Spacer(Modifier.height(16.dp))
            val n = nation; val gd = gender
            if (n != null && gd != null) {
                rolled?.let { IdentityCard(it) { m -> SavedPhotoSlot(m) } }
                OutlinedButton({ rolled = Identity.roll(kotlin.random.Random.Default, n, gd, list) }, Modifier.fillMaxWidth(), shape = Square,
                    border = BorderStroke(1.dp, C.line)) {
                    Icon(Icons.Default.Casino, null, tint = C.text); Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (rolled == null) R.string.identity_roll else R.string.identity_reroll), color = C.text)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (rolled == null) Text(stringResource(if (nation == null || gender == null) R.string.identity_pick_gender else R.string.identity_roll_first), color = C.dim, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), textAlign = TextAlign.Center)
        PrimaryButton(stringResource(R.string.identity_confirm), enabled = rolled != null) { rolled?.let(onConfirm) }
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
    Row(modifier.fillMaxWidth().clip(Square)
        .toggleable(offline, role = Role.Switch) { openInternetPanel(ctx) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(if (offline) R.string.paranoid_offline else R.string.paranoid_mode),
                color = if (offline) Hud.cyan else C.dim, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            if (!offline) Text(stringResource(R.string.paranoid_hint), color = C.dim, fontSize = 12.sp)
        }
        GameSwitch(offline, null)
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
                            else -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = C.ivory)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column { Text(st.title, color = C.text, fontSize = 16.sp); Text(st.note, color = C.dim, fontSize = 12.sp) }
                }
            }
        }
        if (!g.installing && g.steps.firstOrNull()?.failed == true) { // the AI step: Nano not ready, Whisper failed
            Spacer(Modifier.height(12.dp))
            TextButton({ g.retryInstall() }) { Text(stringResource(R.string.download_try_again), color = C.ivory) }
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
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text(stringResource(R.string.download_install_voices), color = C.ivory) }
                TextButton({ g.recheckVoices() }) { Text(stringResource(R.string.download_check_again), color = C.ivory) }
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

/** The shipped Piper voices (scripts/fetch-models.sh), same columns; NOTICE.md has the full table. */
private const val INT8 = "Converted to int8 ONNX for sherpa-onnx."
private val VOICE_LICENSES = listOf(
    listOf("ar_JO-SA_dii-high", "CC BY-NC-SA 4.0", "Synthetic dii dataset (TigreGotico/tts-train-synthetic-dii_ar-diacritics). $INT8", "cc-by-nc-sa-4.0.txt", "https://huggingface.co/csukuangfj/vits-piper-ar_JO-SA_dii-high"),
    listOf("de_DE-thorsten-medium", "CC0 1.0", "Thorsten-Voice dataset. $INT8", "cc0-1.0.txt", "https://github.com/thorstenMueller/Thorsten-Voice"),
    listOf("de_DE-kerstin-low", "CC0 1.0", "Kerstin dataset. $INT8", "cc0-1.0.txt", "https://github.com/rhasspy/dataset-voice-kerstin"),
    listOf("en_US-ryan-medium", "CC BY-NC-SA 4.0", "RyanSpeech: Zandie, Mahoor, Madsen, Emamian, RyanSpeech: A Corpus for Conversational Text-to-Speech Synthesis, Interspeech 2021. $INT8", "cc-by-nc-sa-4.0.txt", "https://huggingface.co/rhasspy/piper-voices/blob/main/en/en_US/ryan/medium/MODEL_CARD"),
    listOf("es_ES-davefx-medium", "CC0 1.0", "davefx dataset. $INT8", "cc0-1.0.txt", "https://github.com/OHF-Voice/voice-datasets"),
    listOf("es_ES-sharvard-medium", "CC BY 3.0", "Aubanel, Vincent; Garcia Lecumberri, Maria Luisa; Cooke, Martin. (2014). Sharvard_IJA [sound]. LISTA Consortium. $INT8", "cc-by-3.0.txt", "https://doi.org/10.7488/ds/75"),
    listOf("fr_FR-siwis-medium", "CC BY 4.0", "Yamagishi, Junichi; Honnet, Pierre-Edouard; Garner, Philip; Lazaridis, Alexandros. (2017). The SIWIS French Speech Synthesis Database, 2016. University of Edinburgh. $INT8", "cc-by-4.0.txt", "https://doi.org/10.7488/ds/1705"),
    listOf("fr_FR-tom-medium", "AGPL-3.0", "Tom voice model by Tjiho. $INT8", "agpl-3.0.txt", "https://git.bksp.space/Tjiho/French-tts-model-piper"),
    listOf("it_IT-paola-medium", "CC0 1.0", "Paola dataset. $INT8", "cc0-1.0.txt", "https://huggingface.co/datasets/paolapersico1/Voice-Dataset-Italian"),
    listOf("it_IT-riccardo-x_low", "M-AILABS dataset license (not verified)", "M-AILABS Speech Dataset. $INT8", "", ""),
    listOf("pl_PL-darkman-medium", "CC0 1.0", "darkman dataset. $INT8", "cc0-1.0.txt", "https://github.com/OHF-Voice/voice-datasets"),
    listOf("pl_PL-gosia-medium", "CC0 1.0", "gosia dataset. $INT8", "cc0-1.0.txt", "https://github.com/OHF-Voice/voice-datasets"),
    listOf("pt_BR-faber-medium", "CC0 1.0", "faber dataset. $INT8", "cc0-1.0.txt", "https://github.com/OHF-Voice/voice-datasets"),
    listOf("pt_PT-dii-high", "CC BY-NC-SA 4.0", "Synthetic dii dataset (Jarbas/tts-train-synthetic-dii_pt-PT). $INT8", "cc-by-nc-sa-4.0.txt", "https://huggingface.co/csukuangfj/vits-piper-pt_PT-dii-high"),
    listOf("tr_TR-dfki-medium", "CC BY-NC-SA 4.0", "DFKI Turkish dataset. $INT8", "cc-by-nc-sa-4.0.txt", "https://github.com/marytts/dfki-ot-data"),
    listOf("tr_TR-fettah-medium", "CC0 1.0", "fettah dataset. $INT8", "cc0-1.0.txt", "https://github.com/NabuCasa/voice-datasets"),
    listOf("uk_UA-ukrainian_tts-medium", "CC0 1.0", "ukrainian_tts dataset. $INT8", "cc0-1.0.txt", "https://github.com/OHF-Voice/voice-datasets"),
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
                entry("Piper voices", stringResource(R.string.licenses_piper_note), "", "", "https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models")
                VOICE_LICENSES.forEach { entry(it[0], it[1], it[2], it[3], it[4]) }
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
    if (g.interruptedAttempt == null || g.thalassiaSeen) return
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
    // Most saves finish in a few ms: a spinner flashed between story frames ("a strange / in the centre", owner).
    // Input is already blocked by careerReady/navigationBlocked, so only a slow save needs the dialog.
    val slow by produceState(state == SavePresentation.RETRY, state) { if (!value) { delay(500); value = true } }
    if (!slow) return
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
    Contract.UNAIDED -> R.string.contract_unaided_name
    Contract.UNMENTIONED -> R.string.contract_unmentioned_name
    Contract.SAMPLES -> R.string.contract_samples_name
}

@Composable private fun CommittedMarks(g: Game) {
    val marks = committedMarks(g.careerState, g.careerSaving)
    for (mark in marks) {
        val label = when (mark.kind) {
            "infiltration" -> stringResource(R.string.replay_mark_detail, stringResource(R.string.album_infiltration),
                Country.find(mark.countryId)?.uiName(uiLang()) ?: continue)
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


internal fun upgradeDeliveryLines(upgrade: Int): List<Int> =
    listOf(R.string.upgrade_courier) + (if (upgrade == 2) listOf(R.string.upgrade_courier_eye) else emptyList()) +
        listOf(R.string.upgrade_unpack, R.string.upgrade_flash, R.string.upgrade_destroy) +
        when (upgrade) {
            1 -> listOf(R.string.upgrade_recall)
            2 -> listOf(R.string.upgrade_accent)
            3 -> listOf(R.string.upgrade_clean, R.string.upgrade_clear_gauge, R.string.upgrade_usa_open, R.string.usa_si_note)
            else -> emptyList()
        }

/** The courier's own picture (one per upgrade) while the courier lines play; the device lines share one picture. */
internal fun upgradeArt(upgrade: Int, step: Int): String =
    if (step < upgradeDeliveryLines(upgrade).indexOf(R.string.upgrade_unpack)) (if (upgrade in 2..3) "upgrade_courier_$upgrade" else "upgrade_courier")
    else "upgrade_box_airport"

@Composable private fun UpgradeScreen(g: Game, upgrade: Int) {
    var step by remember(upgrade) { mutableIntStateOf(0) }
    Backdrop(upgradeArt(upgrade, step), fallback = "upgrade_courier") { UpgradeLines(g, upgrade, step) { step++ } }
}

@Composable private fun UpgradeLines(g: Game, upgrade: Int, step: Int, next: () -> Unit) {
    val lines = upgradeDeliveryLines(upgrade)
    LaunchedEffect(upgrade, step) { if (lines[step] == R.string.upgrade_flash) g.playSfx(R.raw.sfx_scan, Sfx.SCAN_GAIN) else g.playSfx(R.raw.sfx_blink) }
    Gutter {
        Text(stringResource(R.string.implant_build, implantBuild(if (step >= lines.indexOf(R.string.upgrade_flash)) upgrade else upgrade - 1)), style = display(24, 1.5))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Bottom) {
            Panel { Text(stringResource(lines[step]), color = C.text, fontSize = 20.sp) }
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(stringResource(R.string.continue_btn), enabled = g.careerReady) {
            if (step < lines.lastIndex) next() else g.upgradeSeen(upgrade)
        }
    }
}

@Composable private fun BadTesterScreen(g: Game) = Box(Modifier.fillMaxSize().background(C.bg)) {
    Gutter {
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.bad_tester), style = display(30, 1.5), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
        PrimaryButton(stringResource(R.string.restart_free), enabled = g.careerReady) { g.restartFree() }
    }
}

/** Shared pulse moves the backdrop and the separate dialog window together. */
@Composable private fun implantWakeOffset(g: Game): Float {
    val ctx = LocalContext.current
    val animate = Motion.allowed()
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    var handledPulse by remember { mutableIntStateOf(g.implantWakePulse) }
    LaunchedEffect(g.implantWakePulse, animate) {
        offset.snapTo(0f)
        val newPulse = g.implantWakePulse != handledPulse
        handledPulse = g.implantWakePulse
        if (newPulse && animate) {
            offset.snapTo(8f)
            for (target in listOf(-8f, 6f, -4f, 2f, 0f)) offset.animateTo(target, tween(70))
        }
    }
    return if (animate) offset.value else 0f
}

/** Modal semantics keep the desk controls out of TalkBack's focus order during calibration. */
@Composable private fun ImplantBootOverlay(g: Game, token: Int) {
    val ctx = LocalContext.current
    val animate = Motion.allowed()
    val lines = listOf(R.string.implant_boot_online, R.string.implant_boot_hello,
        R.string.implant_boot_shake).map { stringResource(it) }
    val calibrated = stringResource(R.string.implant_boot_calibrated)
    val jolt = implantWakeOffset(g)
    val view = LocalView.current
    LaunchedEffect(token) { g.playSfx(R.raw.sfx_boot) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
    }
    var waiting by remember(token) { mutableStateOf(false) }
    val full = lines.joinToString("\n")
    val typed = remember(token, full) { mutableIntStateOf(0) }
    var spoken by remember(token, full) { mutableStateOf("") }
    var success by remember(token) { mutableStateOf(false) }
    var done by remember(token) { mutableStateOf(false) }
    val accPeak = remember(token) { doubleArrayOf(0.0) } // main thread only: sensor callbacks and the listen loop
    var confirmedBy by remember(token) { mutableStateOf("") }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(token, waiting, success, lifecycle) {
        val manager = ctx.getSystemService(android.hardware.SensorManager::class.java)
        var state = PhoneShakeState()
        val listener = object : android.hardware.SensorEventListener {
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                if (!waiting || success || g.implantBoot != token || g.screen != Screen.COVER ||
                    !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
                state = phoneShake(state, event.timestamp / 1_000_000, event.values[0], event.values[1], event.values[2])
                accPeak[0] = maxOf(accPeak[0], state.level)
                if (state.detected) { confirmedBy = "phone"; success = true; manager?.unregisterListener(this) }
            }
        }
        fun refresh() {
            manager?.unregisterListener(listener)
            state = PhoneShakeState()
            if (waiting && !success && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                manager?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)?.let {
                    manager.registerListener(listener, it, android.hardware.SensorManager.SENSOR_DELAY_GAME)
                }
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> refresh() }
        lifecycle.addObserver(observer)
        refresh()
        onDispose { manager?.unregisterListener(listener); lifecycle.removeObserver(observer) }
    }
    val accessibility = ctx.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
    var touchExploration by remember { mutableStateOf(accessibility?.isTouchExplorationEnabled == true) }
    DisposableEffect(accessibility) {
        val listener = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener {
            touchExploration = it
        }
        accessibility?.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility?.removeTouchExplorationStateChangeListener(listener) }
    }
    LaunchedEffect(token, full, animate) {
        typed.intValue = 0
        // Listen from the first typed character; the window closes 15 s (resumed time) after the last line.
        waiting = true
        var typingDone = false
        val listen = launch {
            var remaining = 15_000L
            var detector = ImplantShakeDetector()
            var last = g.faceWatch.now() - 1
            var frames = 0; var faces = 0; var minYaw = Float.NaN; var maxYaw = Float.NaN
            var reportAt = 0L; var lastFrames = 0
            val trace = ArrayDeque<String>() // the frames behind a camera confirmation, for diagnostics
            fun report(what: String) {
                g.bootLog("$what frames=$frames (+${frames - lastFrames}) face=${if (frames == 0) 0 else faces * 100 / frames}% " +
                    "yaw=$minYaw..$maxYaw acc=${"%.1f".format(java.util.Locale.ROOT, accPeak[0])} typing=${!typingDone} left=${remaining}ms")
                lastFrames = frames
            }
            while (remaining > 0 && !success) {
                if (!resumed) {
                    detector = ImplantShakeDetector()
                    last = g.faceWatch.now()
                    delay(50)
                    continue
                }
                val permission = ctx.checkSelfPermission(android.Manifest.permission.CAMERA) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (g.watching && permission) {
                    for (frame in g.faceWatch.since(last + 1)) {
                        last = frame.t
                        frames++
                        if (frame.face) {
                            faces++
                            minYaw = if (minYaw.isNaN()) frame.yaw else minOf(minYaw, frame.yaw)
                            maxYaw = if (maxYaw.isNaN()) frame.yaw else maxOf(maxYaw, frame.yaw)
                        }
                        trace.addLast(if (frame.face) "%d:%.0f".format(java.util.Locale.ROOT, frame.t % 100_000, frame.yaw) else "${frame.t % 100_000}:-")
                        if (trace.size > 40) trace.removeFirst()
                        if (detector.add(frame.t, frame.yaw, frame.face)) {
                            confirmedBy = "camera"; success = true; g.bootLog("frames " + trace.joinToString(" ")); break
                        }
                    }
                }
                if (!success) {
                    delay(50)
                    if (typingDone) remaining -= 50
                    reportAt += 50
                    if (reportAt % 1000 == 0L) report("listening")
                }
            }
            report(if (success) "confirmed by $confirmedBy" else "timeout")
            waiting = false
            if (success) {
                g.confirmImplantResponse(token)
                if (g.haptics) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        for ((index, line) in lines.withIndex()) {
            val start = lines.take(index).sumOf { it.length + 1 }
            if (animate) for (c in 1..line.length) { typed.intValue = start + c; delay(14) }
            else typed.intValue = start + line.length
            spoken = lines.take(index + 1).joinToString("\n")
            if (animate) delay(150)
        }
        typingDone = true
        listen.join()
        if (success) {
            if (animate) for (c in 1..calibrated.length) {
                typed.intValue = full.length + 1 + c
                delay(14)
            } else typed.intValue = full.length + 1 + calibrated.length
            spoken = full + "\n" + calibrated
            // Leave the confirmation available to assistive technology until Done is activated.
            if (accessibility?.isTouchExplorationEnabled != true) {
                delay(1200)
                g.finishImplantBoot(token)
            }
        }
        done = true
    }
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false,
        dismissOnClickOutside = false)) {
        Column(Modifier.fillMaxWidth().graphicsLayer { translationX = jolt.dp.toPx() }
            .clip(Square).background(C.bg).padding(24.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .clearAndSetSemantics { liveRegion = LiveRegionMode.Polite; contentDescription = spoken }) {
                val log = if (success) full + "\n" + calibrated else full
                BookingLog(log, typed, animate && typed.intValue < log.length)
            }
            // TalkBack can always bypass the gesture, including while the log is typing.
            if (done || touchExploration)
                TextButton({ g.finishImplantBoot(token) }) { Text(stringResource(R.string.implant_boot_done)) }
        }
    }
}
