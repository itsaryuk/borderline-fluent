package com.lampensoft.borderlinefluent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.LocaleManager
import android.Manifest
import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.lampensoft.borderlinefluent.Models.ms
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class Screen { UNSUPPORTED, INTRO, CAMERA_TEST, FINALE, LANGUAGES, DOWNLOAD, PASSPORT, INPUT_MODE, START, DESTINATION, COVER, INTERVIEW, RESULT, SUMMARY, SETTINGS, LICENSES }
enum class InputMode { HOLD, AUTO }

/** First-run setup, in order: languages, their download, the passport photo, how to answer, then the menu. Pure (tested). */
fun nextSetupScreen(hasLanguages: Boolean, downloading: Boolean, passportDone: Boolean, inputDone: Boolean): Screen = when {
    !hasLanguages -> Screen.LANGUAGES
    downloading -> Screen.DOWNLOAD
    !passportDone -> Screen.PASSPORT
    !inputDone -> Screen.INPUT_MODE
    else -> Screen.START
}

/** The passport step is done only with its photo on the phone, not just the pref. Pure (tested). */
fun passportSetupDone(pref: Boolean, photo: Boolean) = pref && photo

/** Result screen: remind to reconnect only after a paranoid-mode mission, and only while the phone is still offline. */
fun remindOffline(playedOffline: Boolean, offlineNow: Boolean) = playedOffline && offlineNow

/** Where the PASSPORT screen returns to ([from]: the screen it was opened from): Settings when opened there, else the setup's next step. */
fun passportExit(from: Screen?, setup: Screen): Screen = if (from == Screen.SETTINGS) Screen.SETTINGS else setup

/** Where Back goes from [screen]; null leaves the app. During setup ([setup] != START) it never skips ahead: back to the intro. */
fun backFrom(screen: Screen, setup: Screen, passportFrom: Screen? = null): Screen? = when {
    screen == Screen.START || screen == Screen.UNSUPPORTED -> null
    screen == Screen.PASSPORT && passportFrom == Screen.SETTINGS -> Screen.SETTINGS
    setup != Screen.START -> if (screen == Screen.INTRO) null else Screen.INTRO
    else -> Screen.START
}

/**
 * Where an on-screen arrow or menu button aiming at [to] really goes from [screen]. Setup done ([setup] == START): [to].
 * During setup it never skips ahead (a failed language leaves LANGUAGES with no Settings arrow hidden): on to the setup
 * step, or, on that step already, wherever system Back goes ([backFrom]).
 */
fun arrowTo(to: Screen, screen: Screen, setup: Screen, passportFrom: Screen? = null): Screen = when {
    setup == Screen.START -> to
    setup != screen -> setup
    else -> backFrom(screen, setup, passportFrom) ?: screen
}

/** The country picker's pref `dest`: "random" or a country id. The old `destination` pref held a language code or "random"; "Any <language>" is gone, so both mean random. */
fun migrateDest(@Suppress("UNUSED_PARAMETER") old: String?): String = "random"

/** [dest] if it still names something playable, else "random" (its language pack is gone, or it is a retired "lang:<code>"). */
fun resolveDest(dest: String, playable: List<Country>): String =
    if (playable.any { it.id == dest }) dest else "random"

fun canonicalDest(saved: String?, legacy: String?): String = CountryMigration.countryId(saved ?: migrateDest(legacy))

/** Countries matching an ordinary picker choice; unavailable choices retain the existing random fallback. */
fun destPool(dest: String, playable: List<Country>): List<Country> =
    playable.filter { dest == "random" || it.id == dest }.ifEmpty { playable }.distinctBy { it.id }
/**
 * Level 2 follows a cleared desk, never the training mission (level 1 only, replays included). In the story a broken
 * officer doesn't cut it short: BROKEN enters the room like a PASS. After the story, breaking the desk officer wins outright.
 */
fun entersRoom(d: Decision, wasInRoom: Boolean, training: Boolean, storyMission: Boolean, suspicion: Double, contradictions: Int) =
    !wasInRoom && !training && (storyMission && (d == Decision.PASS || d == Decision.BROKEN) ||
        d == Decision.PASS && (suspicion > ASSESS_SUSPICION || contradictions > 0))

/** After the story a desk PASS goes on to the assessment only if the officer ended suspicious or caught a contradiction. */
const val ASSESS_SUSPICION = 0.25

/** The story room cleared, by a PASS or by breaking the interrogator, leads to the finale. */
fun reachesFinale(d: Decision, storyMission: Boolean, wasInRoom: Boolean) =
    (d == Decision.PASS || d == Decision.BROKEN) && storyMission && wasInRoom

/**
 * The mission's language isn't the interface's: the player is likely speaking one that isn't theirs (display only:
 * a tag and a line on a pass; scoring untouched). Language codes only: pt UI is pt-PT, pt speech pt-BR, same language.
 */
fun foreignTongue(ui: String, missionLang: String) = ui != missionLang

/** [lang]: the language pack this step installs (null for the core models); [noVoice]: it failed only for lack of an offline voice. */
data class PackStep(val title: String, val note: String, val done: Boolean = false, val failed: Boolean = false,
                    val lang: String? = null, val noVoice: Boolean = false)

/** The download steps a retry runs again: every language step that didn't finish (the AI step always runs). Pure (tested). */
fun retrySteps(steps: List<PackStep>): Set<String> = steps.filter { !it.done }.mapNotNull { it.lang }.toSet()

enum class Mic { OFF, OFFICER_SPEAKING, READY, HEARING, PROCESSING }

/**
 * Whole game state for the UI, plus the interview loop: speech -> recognizer (ML Kit, else Whisper) -> answer check
 * (Nano) -> hidden mind -> officer line (Nano) -> voice.
 */
class Game(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val prefs = app.getSharedPreferences("settings", 0)
    private val scope = viewModelScope
    private fun str(@StringRes id: Int, vararg a: Any) = ctx.getString(id, *a)

    var destination by mutableStateOf(Country.of("de").primary()); private set
    fun difficultyFor(lang: String) = prefs.getInt("difficulty.$lang", 0).coerceIn(0, 3)
    var difficulty by mutableStateOf(difficultyFor(destination.lang)); private set
    val canChooseDifficulty get() = screen == Screen.COVER && finaleSeen && !training && restoredAttempt == null
    fun chooseDifficulty(level: Int) {
        if (!canChooseDifficulty || startingAttempt || navigationBlocked || liveAttempt != null) return
        difficulty = level.coerceIn(0, 3)
        prefs.edit { putInt("difficulty.${destination.lang}", difficulty) }
    }
    val country get() = destination.country
    val airport get() = destination.airport
    private val airportRotation = AirportRotation(Random.Default)

    // Settings
    var inputMode by mutableStateOf(InputMode.valueOf(prefs.getString("input", InputMode.AUTO.name)!!)); private set
    var haptics by mutableStateOf(prefs.getBoolean("haptics", true)); private set
    /** The main menu's country pick ([migrateDest]); read it through [destChoice], which drops a stale one. */
    var dest by mutableStateOf(canonicalDest(prefs.getString("dest", null), prefs.getString("destination", null))
        .also { d -> if (prefs.getString("dest", null) != d || prefs.contains("destination"))
            prefs.edit { putString("dest", d); remove("destination") } }); private set
    fun setInput(m: InputMode) { inputMode = m; prefs.edit { putString("input", m.name) } }
    private val hapticPlayer = Haptics(app, { haptics }, ::dbg)
    fun hapticForeground(on: Boolean) = hapticPlayer.foreground(on)
    fun resultHaptic(view: android.view.View, passed: Boolean) = hapticPlayer.result(view, passed)
    fun setHaptic(on: Boolean) { haptics = on; if (!on) hapticPlayer.cancel(); prefs.edit { putBoolean("haptics", on) } }
    fun pickDest(d: String) { dest = d; prefs.edit { putString("dest", d) } }
    var musicOn by mutableStateOf(prefs.getBoolean("music", true)); private set
    fun setMusic(on: Boolean) { musicOn = on; prefs.edit { putBoolean("music", on) }; music.enabled = on; if (on) soundtrack() }

    val music = Music(app, viewModelScope).also { it.enabled = prefs.getBoolean("music", true) }
    private val sfx = Sfx(app).also { it.enabled = prefs.getBoolean("sfx", true) }
    var sfxOn by mutableStateOf(prefs.getBoolean("sfx", true)); private set
    fun setSfx(on: Boolean) { sfxOn = on; sfx.enabled = on; prefs.edit { putBoolean("sfx", on) } }
    // Off by default: answers are the player's voice. On only for tuning; then recordings and logs stay on the phone.
    // The officer watches the traveler through the front camera (face detection only; frames never leave the analyzer).
    var cameraOn by mutableStateOf(prefs.getBoolean("camera", true)); private set
    fun setCamera(on: Boolean) { cameraOn = on; cameraAsked = false; cameraRefused = false; if (!on) faceWatch.session.stopCamera(); refreshScanner(); prefs.edit { putBoolean("camera", on) } }
    /** This session's answer to the in-context camera ask (face reading on, permission not granted): refused = off till restart. */
    private var cameraAsked = false
    private var cameraRefused by mutableStateOf(false)
    /** Face reading for this interview: the setting, unless the camera was refused this session. */
    val watching get() = cameraOn && !cameraRefused
    /** Ask for the camera now (the interview is starting)? At most once a session; never at launch. */
    fun shouldAskCamera(): Boolean {
        val granted = ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!cameraOn || granted || cameraAsked || cameraRefused) return false
        cameraAsked = true
        return true
    }
    fun cameraAnswered(granted: Boolean) { if (!granted) { cameraRefused = true; faceWatch.session.stopCamera(); refreshScanner() } }
    val faceWatch = FaceWatch()
    // These must precede screen and the install() init block. Navigation only touches these early fields.
    var activeFaceRun by mutableStateOf<Int?>(null); private set
    @Synchronized private fun stopFaceRun() {
        activeFaceRun = null
        faceWatch.session.game(FaceSession.Gate(null, false, false, false, false, true))
        faceWatch.session.presentation(false)
        faceWatch.session.stopCamera()
    }
    @Synchronized private fun refreshScanner() {
        val active = activeFaceRun ?: return // do not read later-declared state during install()/screen init
        faceWatch.session.game(FaceSession.Gate(active, screen == Screen.INTERVIEW, watching,
            room != null, machineView, blackout || flash || narration != null || ending != null || sceneArt != null))
    }
    @Synchronized internal fun scannerPresentation(forRun: Int?, visible: Boolean) {
        if (forRun == activeFaceRun) faceWatch.session.presentation(visible)
    }
    internal fun scannerState(reducedMotion: Boolean = false) = faceWatch.scannerState(reducedMotion)
    internal fun scannerDiagnostics() { faceWatch.session.takeReport()?.let(::dbg) }
    private var profiler = Profiler()
    private var interviewAt = 0L // FaceWatch clock: the relaxed opening (greeting, passport, small talk) is the baseline
    private var answerAt = 0L
    /** The retinal implant's live coaching from the profiler: blink slower, stop smiling, eyes on the officer. */
    var implant by mutableStateOf<String?>(null); private set
    private var implantJob: Job? = null
    private var hintUntil = 0L // the T-N warning stays up until then; the coach loop leaves `implant` alone
    var diagnostics by mutableStateOf(prefs.getBoolean("diagnostics", false)); private set
    fun toggleDiagnostics(on: Boolean) { diagnostics = on; prefs.edit { putBoolean("diagnostics", on) } }

    val career = Career(app)
    var careerState by mutableStateOf<CareerSave?>(null); private set
    var careerSaving by mutableStateOf(false); private set
    private var terminalTransition by mutableStateOf(false)
    val navigationBlocked get() = careerSaving || careerState is CareerSave.WriteFailed || terminalTransition
    val careerReady get() = careerState is CareerSave.Saved && !navigationBlocked
    val interruptedAttempt get() = career.snapshot?.pendingAttempt?.takeIf { it.status == AttemptStatus.INTERRUPTED }
    var recoveryPackMissing by mutableStateOf(false); private set
    var preparationUnavailable by mutableStateOf(false); private set
    private var afterCareerSave: ((CareerSave.Saved) -> Unit)? = null
    @Volatile private var liveAttempt: LiveAttempt? = null
    private var startingAttempt = false
    private var interruptStart = false
    private var restoredAttempt: Attempt? = null
    private var backgrounded = false
    private var terminalContinuation: (() -> Unit)? = null

    /** A committed ending may finish saving while stopped; play its scene only after returning. */
    fun resumeAfterStop() {
        backgrounded = false
        val after = terminalContinuation
        terminalContinuation = null
        after?.invoke()
        soundtrack()
    }

    private fun whenForeground(after: () -> Unit) {
        if (backgrounded) terminalContinuation = after else after()
    }

    @Synchronized private fun saveCareer(action: suspend () -> CareerSave, after: (CareerSave.Saved) -> Unit) {
        if (careerSaving || careerState is CareerSave.WriteFailed) return
        careerSaving = true
        afterCareerSave = after
        scope.launch { completeCareerSave(action()) }
    }

    @Synchronized private fun completeCareerSave(result: CareerSave) {
        career.publish()
        careerState = result
        careerSaving = false
        if (result is CareerSave.Saved) {
            val after = afterCareerSave
            afterCareerSave = null // consume before a continuation can stage another write
            after?.invoke(result)
        }
    }

    @Synchronized fun retryCareerSave() {
        if (careerSaving || careerState !is CareerSave.WriteFailed) return
        careerSaving = true
        scope.launch { completeCareerSave(career.store.retry()) }
    }

    /** Restores preparation only. The next Start gets a fresh durable ID, never a live interview. */
    fun continueInterrupted() {
        if (!careerReady) return
        val a = interruptedAttempt ?: return
        val art = Packs.art(ctx).keys
        val cover = recoverPreparation(a, playable(art), sheets(art), art, languages)
        recoveryPackMissing = a.contentVersion == 2 && a.preparation != null &&
            (playable(art).none { it.id == a.countryId } || a.officerId !in sheets(art) ||
                Country.find(a.countryId)?.airports?.find { it.id == a.airportId }?.let { it.art !in art || it.lang !in languages } == true)
        if (cover == null) {
            preparationUnavailable = true
            return
        }
        destination = Destination(cover.country, cover.country.airports.first { it.id == a.airportId }); story = cover; officerId = a.officerId
        difficulty = difficultyFor(destination.lang)
        restoredAttempt = a
        training = a.training
        preparationUnavailable = false
        screen = Screen.COVER
        watchEars(destination.lang)
    }

    var training by mutableStateOf(!prefs.getBoolean("trained", false)); private set
    fun replayTraining() { if (!careerReady || liveAttempt != null) return; training = true; prefs.edit { putBoolean("trained", false) } }
    var tip by mutableStateOf<String?>(null); private set // training-mission coaching, shown over the HUD
    private var introTip: String? = null // the first training tip, while it is still the one showing
    var passportGiven by mutableStateOf(false); private set

    /** Menu loop everywhere outside the interview; silence while talking; a sting on the result screen. */
    fun soundtrack() {
        if (backgrounded) return
        when (screen) {
            // Under the interview: the terminal (HVAC, a distant crowd, a chime), or the story room's hum.
            Screen.INTERVIEW -> music.play(if (inBackRoom) R.raw.ambience_room else R.raw.ambience_airport, loop = true)
            Screen.FINALE -> music.play(R.raw.music_credits, loop = false) // owner's credits track, under the car scene too
            Screen.RESULT -> music.play(when (mind.decision) { Decision.BROKEN -> R.raw.music_broken; Decision.PASS -> R.raw.music_win; else -> R.raw.music_over },
                loop = false, volume = Music.STING)
            else -> music.play(R.raw.music_menu, loop = true)
        }
    }

    // Language packs. The core models are shared by every language; a pack is its art plus an offline system voice.
    var languages by mutableStateOf(prefs.getStringSet("languages", emptySet())!!.toSet()); private set
    var steps by mutableStateOf(emptyList<PackStep>()); private set
    var installing by mutableStateOf(false); private set
    var voiceMissing by mutableStateOf(emptyList<String>()); private set
    var needsConfirm by mutableStateOf(false); private set // Play wants the player's OK (big download, mobile data)
    fun confirmDownload(activity: android.app.Activity) { needsConfirm = false; Packs.confirm(activity) }
    // What this phone has built in, probed at every start (never cached): Gemini Nano (AICore; without it the game
    // can't run) and ML Kit's basic speech recognition per language. Null = still checking. Languages without system
    // speech recognition fall back to the Whisper download.
    var nanoStatus by mutableStateOf<Int?>(null); private set
    /** Per language: true = system recognizer, false = none (Whisper), null = the check kept throwing ([asrKnown]). */
    var basicAsr by mutableStateOf<Map<String, Boolean?>?>(null); private set
    private fun whisperFor(lang: String) = slowLang(basicAsr, lang) // only a returned UNAVAILABLE, never unknown
    /** Speech runs on Whisper on this phone (~1.5-5 s per answer instead of ~0.5-1 s): the picker and the implant warn. */
    fun slowLang(lang: String) = slowLang(basicAsr, lang)

    /** Whisper, if [langs] (plus the installed languages) include one without system speech recognition and it isn't here yet. */
    fun corePacks(langs: Set<String>): List<String> =
        if ((languages + langs).any(::whisperFor) && !Models.has(ctx, Models.WHISPER)) Packs.WHISPER else emptyList()

    /** The download size line on the language screen. */
    fun downloadNote(adding: Set<String>): String {
        if (nanoStatus == null || basicAsr == null) return str(R.string.note_checking)
        val core = corePacks(adding)
        val gb = if ("core_whisper" in core) 1.0 else 0.0
        val speech = adding.count { basicAsr?.get(it) == true } * 110
        val parts = listOfNotNull(
            str(R.string.note_ai_models, gb).takeIf { gb > 0 },
            str(R.string.note_speech, speech).takeIf { speech > 0 },
            ctx.resources.getQuantityString(R.plurals.note_packs, adding.size, adding.size),
        ).joinToString(" + ")
        return str(if (gb > 0) R.string.note_total_wifi else R.string.note_total, parts)
    }

    // Screen state
    /** Keep initialization dependencies above [screen]: install() invokes its setter during init. */
    var finaleSeen by mutableStateOf(prefs.getBoolean("finale", false)); private set
    /** Null if this phone can run the game, else the reason: the device check now, the Nano probe once it answers. */
    var supported by mutableStateOf(Device.check(app)); private set
    private var shownScreen by mutableStateOf(if (supported != null) Screen.UNSUPPORTED else Screen.INTRO)
    var screen: Screen
        get() = shownScreen
        set(s) { if (navigationBlocked) return; if (s == Screen.PASSPORT && shownScreen != Screen.PASSPORT) passportFrom = shownScreen; if (s != Screen.INTERVIEW) stopFaceRun(); if (s == Screen.COVER) difficulty = difficultyFor(destination.lang); shownScreen = s }
    /** The screen PASSPORT was opened from: only SETTINGS gets the Back arrow and returns there ([passportExit]). */
    var passportFrom by mutableStateOf<Screen?>(null); private set
    /** The passport step (own photo or a sample face, and the face-reading choice) is done; existing installs see it once. */
    // A restored or transferred install keeps the pref but not the photo (noBackupFilesDir): ask again. A sample face
    // is saved as the photo too, so every finished setup has one; deleting it means the next launch asks again.
    var passportDone by mutableStateOf(passportSetupDone(prefs.getBoolean("setup_passport_done", false), PassportPhoto.exists(ctx))); private set
    /** The input step is done once the player chose (here or, on installs older than the step, in Settings). */
    var inputDone by mutableStateOf(prefs.contains("input")); private set
    fun nextSetupScreen() = nextSetupScreen(languages.isNotEmpty(), installing, passportDone, inputDone)
    /** An arrow or menu button: [to], gated by the setup like system Back ([arrowTo]). */
    fun go(to: Screen) { screen = arrowTo(to, screen, nextSetupScreen(), passportFrom) }
    fun afterIntro() { screen = nextSetupScreen() }
    /** PASSPORT finished: from first-run setup on to the next step, from Settings back there. Own photo: [PassportPhoto] only. */
    fun passportFinished() {
        passportDone = true
        prefs.edit { putBoolean("setup_passport_done", true) }
        screen = passportExit(passportFrom, nextSetupScreen())
    }
    /** INPUT_MODE finished: saving the choice ("input") marks the step done. */
    fun inputChosen(m: InputMode) { setInput(m); inputDone = true; screen = nextSetupScreen() }

    init {
        scope.launch { completeCareerSave(career.store.open()) }
        // Chosen languages whose packs aren't on the phone (first launch after an update, or Play evicted them).
        val missing = languages - Packs.installedLanguages(app)
        if (supported == null && missing.isNotEmpty()) install(missing)
    }
    var ready by mutableStateOf(false); private set
    /** Can the player's answers be transcribed in [country]'s language? Null while checking. */
    var earsReady by mutableStateOf<Boolean?>(null); private set
    /** No system recognizer for the language at all: only the Whisper download can help. */
    var earsNeedWhisper by mutableStateOf(false); private set
    /** The speech check kept throwing (~30 s): the cover says so and offers Whisper ([earsNeedWhisper] is set too). */
    var earsStuck by mutableStateOf(false); private set
    var micDenied by mutableStateOf(false); private set
    private var earsJob: Job? = null
    var loadError by mutableStateOf<String?>(null); private set
    var story by mutableStateOf(Backstory.BENCH); private set
    var officerId by mutableStateOf("de_f1"); private set
    var face by mutableStateOf(Face.NEUTRAL); private set
    var officerText by mutableStateOf(""); private set
    var officerEn by mutableStateOf(""); private set
    var hints by mutableStateOf(emptyList<Hint>()); private set
    var heard by mutableStateOf(""); private set
    var mic by mutableStateOf(Mic.OFF); private set
    private var scannerEnding by mutableStateOf<Decision?>(null)
    var ending: Decision?
        get() = scannerEnding
        private set(value) { scannerEnding = value; refreshScanner() } // the beat between the last words and the result
    /** Level 2: the Kade-Sorensen room after a failed desk interview; null at the desk. */
    private var scannerRoom by mutableStateOf<Assessment?>(null)
    var room: Assessment?
        get() = scannerRoom
        private set(value) { scannerRoom = value; refreshScanner() }
    var wasInRoom by mutableStateOf(false); private set
    private var scannerNarration by mutableStateOf<String?>(null)
    var narration: String?
        get() = scannerNarration
        private set(value) { scannerNarration = value; refreshScanner() } // a scene beat over the dimmed screen
    var officerGlitch by mutableStateOf(false); private set // the portrait stutters (the precautions line)
    private var announced = false
    /**
     * The first playthrough is the story: clear the desk, a taser in the arrivals hall, wake up in an unknown room
     * with the Kade-Sorensen test; pass it and the finale plays (a car, a man with the same implant glint, "welcome
     * to the resistance", credits). After that every mission is two levels at the airport: the desk, then the
     * same android runs the T-N test right there; passing both raises the rank. Failing the desk fails the mission
     * (owner, 2026-10-04: no second level after a failure, not even in the story).
     */
    var storyMission by mutableStateOf(false); private set
    var contract by mutableStateOf<Contract?>(null); private set
    val coverContract get() = if (restoredAttempt != null) Contract.fromId(restoredAttempt?.contract) else contract
    val liveContract get() = Contract.fromId(liveAttempt?.context?.contract)
    val contractsUnlocked get() = contractsUnlocked(finaleSeen, training, career.level)
    val canChooseContract get() = screen == Screen.COVER && contractsUnlocked && restoredAttempt == null &&
        !startingAttempt && !navigationBlocked && liveAttempt == null
    fun chooseContract(c: Contract?) { if (canChooseContract) contract = c }
    /** The android's designation for the HUD: country code, sheet, firmware (newer = stricter, see Persona). */
    var unitName by mutableStateOf(""); private set
    var booting by mutableStateOf(false); private set // the implant's start-up line over the first seconds
    private enum class Entry { AT_DESK, TASER }
    /** The story's unknown room (after the taser), as opposed to the test at the desk. */
    var inBackRoom by mutableStateOf(false); private set
    private var scannerFlash by mutableStateOf(false)
    var flash: Boolean
        get() = scannerFlash
        private set(value) { scannerFlash = value; refreshScanner() } // the taser
    private var scannerMachineView by mutableStateOf(false)
    var machineView: Boolean
        get() = scannerMachineView
        private set(value) { scannerMachineView = value; refreshScanner() } // the story room: the eye machine instead of the officer
    private var scannerBlackout by mutableStateOf(false)
    var blackout: Boolean
        get() = scannerBlackout
        private set(value) { scannerBlackout = value; refreshScanner() }
    private var scannerSceneArt by mutableStateOf<String?>(null)
    var sceneArt: String?
        get() = scannerSceneArt
        private set(value) { scannerSceneArt = value; refreshScanner() } // a full-bleed scene under the narration (story arrest)
    private var roomSay = emptyList<String>() // Assessment.questions in the country's language, same order
    private var roomSub = emptyList<String>() // the same in the subtitle language
    /** The subtitle language this interview (officerEn, Hint.english): the UI language, see [subtitleLang]. */
    private var sub = "en"
    var mind by mutableStateOf(OfficerMind(Persona.random(Random.Default))); private set

    private var asr: OfflineRecognizer? = null // Whisper, only for languages without system speech recognition
    private var retryAsr: OfflineRecognizer? = null // Whisper for a second try when the system recognizer hears nothing
    private var brain: GenerativeModel? = null // Gemini Nano: hints and answer checks
    private var tts: TextToSpeech? = null
    // Piper (decision: vetted voices per language and gender); the system TTS speaks when there is none.
    private var piper: com.k2fsa.sherpa.onnx.OfflineTts? = null
    private var piperVoice = ""
    private var speakJob: Job? = null
    private val ttsReady = scope.async(Dispatchers.Default) { Models.tts(ctx).also { tts = it } }
    private var desk = Desk()
    private var deskSpoken = false
    private var roomDesk = Desk()
    private val history = mutableListOf<String>()
    private val covered = mutableSetOf<Int>() // cover facts the answers were about (Verdict.fact), see TOPIC_FACTS
    private var analyst: Analyst? = null
    private var question = "" // the officer's last line: what the next answer responds to
    @Volatile private var passportAsked = true // until the handover turn: what's said goes with the passport
    @Volatile private var asked = true // [question] asked something (isQuestion); silence after a statement costs nothing
    // Small talk after the passport scan: the run's SMALL_TALK seeds, the one being asked, the handover turn
    // held back until it is over (non-null = small talk under way), its lines for the hints, the answers' think times.
    private var talks = emptyList<Int>()
    private var talkAt = -1
    @Volatile private var heldHandover: String? = null
    private val talkLines = mutableListOf<String>()
    private val talkThink = mutableListOf<Long>()
    private val behavior = mutableListOf<BehaviorEvent>() // every tell and look-away this interview, for the debrief
    private fun logBehavior(tell: Tell?, z: Double) {
        behavior += BehaviorEvent(tell, z, (officerEn.ifEmpty { question }).trim().let { if (it.length > 40) it.take(39).trimEnd() + "…" else it })
    }
    /** The debrief for the result screens: null when the camera was off or the baseline was never set. */
    fun behaviorDebrief(): String? = if (watching && profiler.calibrated) debrief(behavior).joinToString(" ") { it.text(ctx) } else null
    private val turnLock = Mutex()
    private val nanoLock = Mutex()
    private val voiceLock = Mutex() // every use of [piper] and [driver], see piperSay
    private var pendingRetina: Deferred<Retina?>? = null
    private var pendingSmallTalkOdd: Deferred<Unit>? = null
    private var silenceJob: Job? = null
    private var listenJob: Job? = null
    // The answer clock (invisible timer and measured thinking time) starts only once the officer has finished
    // speaking AND the retinal display shows its suggestions: before that the player has nothing to read.
    @Volatile private var spoken = false
    @Volatile private var shown = false
    @Volatile private var clockAt = 0L
    @Volatile private var holding = false
    @Volatile private var listening = false
    @Volatile private var lookAsked = false // "please look at me": once per question
    @Volatile private var run = 0 // bumps on every new interview, so stale callbacks from the last one do nothing

    init {
        if (supported == null) scope.launch(Dispatchers.Default) {
            val st = probeNano(log = ::dbg) { Generation.getClient().let { m -> try { m.checkStatus() } finally { m.close() } } }
            // Only a returned UNAVAILABLE means no Nano (a probe that throws is NANO_UNKNOWN: AICore busy, updating,
            // not bound yet after boot): nothing to download that could help. [supported] first, so a waiting install() sees it.
            if (st == FeatureStatus.UNAVAILABLE) {
                supported = str(R.string.unsupported_nano, str(R.string.app_name))
                screen = Screen.UNSUPPORTED
            }
            nanoStatus = st
            if (supported != null) { dbg("system AI: nano unavailable"); return@launch }
            // The model warmup is the slow part of start-up and needs no speech probe: start it first, beside them.
            // An interview probes its own language's recognizer, so a ready brain before [basicAsr] is safe.
            if (st == FeatureStatus.AVAILABLE) launch { loadBrain() }
            basicAsr = probeAsr(Language.all.map { it.code })
            dbg("system AI: nano=$nanoStatus basicAsr=${basicAsr?.filterValues { it == true }?.keys} unknown=${basicAsr?.filterValues { it == null }?.keys}")
            // Nano still to download: a returning player has no missing pack, so run the "AI models" step. Unknown (the
            // probe kept throwing): don't pull the player to the download screen; the cover offers the retry.
            when {
                st == FeatureStatus.AVAILABLE -> {} // loading since the probe returned
                st == NANO_UNKNOWN -> loadError = str(R.string.step_nano_failed)
                languages.isNotEmpty() -> install(emptySet())
            }
        }
    }

    /** The officer's model, once Nano is ready (an AICore download first, see [install]). Re-run after installs. */
    private suspend fun loadBrain() {
        if (brain != null || nanoStatus != FeatureStatus.AVAILABLE) return
        try {
            ttsReady.await()
            brain = Nano.model(::dbg) ?: run { loadError = "Gemini Nano unavailable"; return }
            loadError = null
            ready = true
        } catch (t: Throwable) {
            Log.e(TAG, "model load failed", t)
            loadError = "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /** Basic speech recognition for [langs], in parallel, each retried like the Nano probe; a check that keeps throwing is null. */
    private suspend fun probeAsr(langs: Collection<String>): Map<String, Boolean?> = coroutineScope {
        val gate = Semaphore(4) // 13 languages at once is a herd on a just-booted AICore
        langs.map { l -> async { gate.withPermit { l to asrKnown(probeNano(name = "asr $l", log = ::dbg) { Nano.basicAsr(l) }) } } }.awaitAll().toMap()
    }

    /**
     * The cover's retry: Nano not ready yet (a failed install step) re-runs the install's Nano step on the download
     * screen; a failed load (warmup BUSY, quota) just loads again.
     */
    fun retryBrain() {
        loadError = null
        if (nanoStatus != FeatureStatus.AVAILABLE) install(emptySet()) else scope.launch(Dispatchers.Default) { loadBrain() }
    }

    /**
     * The download screen's "Try again" after a failed step: every language step that didn't finish, including packs
     * Play evicted that are still in [languages] (a failed AI step + an evicted pack restores both).
     */
    fun retryInstall() = install(retrySteps(steps))

    override fun onCleared() {
        stopInterview("cleared")
        faceWatch.dispose()
        sayReceiver?.let(ctx::unregisterReceiver)
        brain?.close(); asr?.release(); retryAsr?.release(); tts?.shutdown(); music.stop(); sfx.release()
        CoroutineScope(Dispatchers.Default).launch {
            vadLock.withLock { vadClosed = true; sileroVad?.release(); sileroVad = null }
        }
        // viewModelScope is gone; wait for a running synthesis on a scope of its own before freeing the voices.
        CoroutineScope(Dispatchers.Default).launch { voiceLock.withLock { piper?.release(); driver?.release() } }
    }

    // ---------- flow ----------

    /** Officer sheet ids in the installed packs. */
    private fun sheets(packArt: Set<String>) =
        packArt.filter { it.startsWith("officer_") }.map { it.removePrefix("officer_").removeSuffix("_sheet") }.toSet()

    /** Countries a mission can go to: an installed language and a drawn officer (a Japanese desk never gets a borrowed face). */
    fun playable(packArt: Set<String> = Packs.art(ctx).keys): List<Country> {
        return playableCountries(languages, packArt)
    }

    /** [dest], or "random" when its pack is gone. */
    fun destChoice(playable: List<Country> = playable()): String {
        return resolveDest(dest, playable)
    }

    fun beginMission() {
        if (navigationBlocked || liveAttempt != null) return
        val bad = (careerState as? CareerSave.Unreadable)?.read
        if (bad?.preparationOnly == true || interruptedAttempt != null) {
            saveCareer({ career.store.discardPreparation("new-mission") }) {
                restoredAttempt = null
                prepareMission()
            }
            return
        }
        if (!careerReady) return
        prepareMission()
    }

    private fun prepareMission() {
        restoredAttempt = null
        contract = null
        preparationUnavailable = false
        val r = Random.Default
        val packArt = Packs.art(ctx).keys
        val sheets = sheets(packArt)
        val playable = playable(packArt)
        if (playable.isEmpty()) { screen = Screen.LANGUAGES; return } // no language pack on the phone (yet)
        val choice = destChoice(playable)
        val selected = selectDestination(destPool(choice, playable), languages, packArt, null, r, airportRotation)
        if (selected == null) { screen = Screen.LANGUAGES; return }
        destination = selected
        difficulty = difficultyFor(destination.lang)
        story = Backstory.random(r, destination) // a citizen coming home
        officerId = airport.officers.filter(sheets::contains).random(r)
        screen = Screen.DESTINATION
        watchEars(destination.lang)
    }

    // Without system speech or Whisper every answer transcribes as empty and the run is unwinnable, so gate the start.
    private fun watchEars(lang: String) {
        earsJob?.cancel(); earsReady = null; earsNeedWhisper = false; earsStuck = false
        earsJob = scope.launch(Dispatchers.Default) {
            var download: Job? = null
            var unknown = 0 // checks in a row that threw
            var retryAt = 0L; var backoff = 5000L // a failed speech-model download is asked for again, less and less often
            while (screen == Screen.DESTINATION || screen == Screen.COVER) {
                // A throw is not "needs Whisper": ask again. Only after ~30 s of throws may the player choose Whisper.
                val st = try { Nano.basicAsr(lang) } catch (e: CancellationException) { throw e } catch (e: Exception) { NANO_UNKNOWN }
                unknown = if (st == NANO_UNKNOWN) unknown + 1 else 0
                earsReady = st == FeatureStatus.AVAILABLE || Models.has(ctx, Models.WHISPER)
                earsStuck = earsReady == false && unknown >= 6
                earsNeedWhisper = earsReady == false && (st == FeatureStatus.UNAVAILABLE || earsStuck)
                if (earsReady == true) return@launch
                // Idempotent, so a failed or deferred download is simply asked for again (backing off up to 2 min).
                if (st == FeatureStatus.DOWNLOADABLE && download?.isActive != true && System.currentTimeMillis() >= retryAt) download = launch {
                    val why = try { Nano.downloadAsr(lang) { _, _ -> } } catch (e: CancellationException) { throw e } catch (e: Exception) { e.toString() }
                    if (why != null) { dbg("ears download: $why"); retryAt = System.currentTimeMillis() + backoff; backoff = (backoff * 2).coerceAtMost(120_000) }
                }
                delay(5000)
            }
        }
    }

    /** The language has no system recognizer after all: fetch Whisper (and the small language pack again). */
    fun fetchWhisper() {
        basicAsr = basicAsr?.plus(destination.lang to false)
        install(setOf(destination.lang))
    }

    /** Paranoid mode: the phone had no network when this interview started (result screen reminds to reconnect). */
    var playedOffline = false; private set

    @Synchronized fun startInterview() {
        val recovering = restoredAttempt
        if (screen != Screen.COVER || !canStartAttempt(careerReady, brain != null, startingAttempt,
                liveAttempt != null || career.snapshot?.pendingAttempt != null && recovering == null)) return
        val art = Packs.art(ctx).keys
        if (!destinationAvailable(destination, officerId, languages, art) ||
            recovering != null && recoverPreparation(recovering, playable(art), sheets(art), art, languages) == null) {
            preparationUnavailable = true
            recoveryPackMissing = true
            return
        }
        val persona = recovering?.preparation?.persona ?: if (training) Persona.TRAINING else Persona.random(
            Random.Default, effectiveLevel(training, finaleSeen, career.level, difficulty))
        val context = recovering?.copy(id = 0) ?: Attempt(0, country.id, officerId, destination.lang, training,
            story = !finaleSeen, replayEligible = finaleSeen && !training, effectiveLevel = persona.level,
            preparation = story.preparation(persona), contentVersion = 2, airportId = airport.id,
            contract = if (contractsUnlocked) contract?.id else null)
        startingAttempt = true; interruptStart = false
        fun persistStart() {
            saveCareer({ career.store.start(context, "interview") }) { saved ->
                startingAttempt = false
                restoredAttempt = null
                val a = checkNotNull(saved.snapshot.pendingAttempt)
                if (!launchAfterStart(interruptStart, brain != null)) {
                    liveAttempt = LiveAttempt(run, a)
                    abortAttempt(AbortCause.INTERRUPTED) { screen = Screen.START }
                } else launchInterview(a)
            }
        }
        if (recovering != null) saveCareer({ career.store.discardPreparation("restart") }) { persistStart() }
        else persistStart()
    }

    @Synchronized private fun launchInterview(context: Attempt) {
        val b = brain ?: return
        playedOffline = ctx.getSystemService(android.net.ConnectivityManager::class.java)
            .let { cm -> cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } == null }
        stopInterview("new run")
        val myRun = ++run
        liveAttempt = LiveAttempt(myRun, context)
        training = context.training
        screen = Screen.INTERVIEW
        sub = subtitleLang(uiLanguage(ctx), destination.lang)
        mind = OfficerMind(checkNotNull(context.preparation).persona)
        val rng = Random(Random.nextLong())
        val topics = listOf("from") + (OFFICER_TOPICS - "from" -
            (if (story.stamps.size < 2) setOf("stamps") else emptySet())).shuffled(rng)
        desk = Desk(rng, topics); deskSpoken = false
        face = Face.NEUTRAL; officerText = ""; officerEn = ""; hints = emptyList(); heard = ""; ending = null
        passportGiven = false; passportAsked = true
        introTip = if (training) str(R.string.tip_intro, destination.languageName(uiLanguage(ctx))) else null
        tip = introTip
        history.clear(); question = ""; covered.clear()
        talks = SMALL_TALK.getValue("en").indices.shuffled().take(SMALL_TALK_ASKED); talkAt = -1; heldHandover = null
        talkLines.clear(); talkThink.clear()
        room = null; wasInRoom = false; inBackRoom = false; narration = null; announced = false; flash = false; blackout = false; machineView = false; sceneArt = null
        activeFaceRun = myRun; refreshScanner()
        storyMission = context.story
        unitName = unit()
        scope.launch { booting = true; delay(3000); booting = false }
        behavior.clear(); profiler = Profiler(); interviewAt = faceWatch.now(); implant = null; hintUntil = 0
        implantJob?.cancel()
        if (watching) implantJob = scope.launch(Dispatchers.Default) { coachLoop(myRun) }
        if (slowLang(destination.lang)) implantNote(myRun, str(R.string.implant_slow, destination.languageName(uiLanguage(ctx))))
        dbg("run $myRun ${country.id} officer=$officerId ver=${version()} difficulty=${mind.p.level} rank=${career.level} slow=${slowLang(destination.lang)} mind ${mind.p.describe()} wanted=${mind.wanted} silence=${mind.silenceDeadlineMs()}ms contract=${context.contract ?: "none"}")
        dbg("cover ${story.facts().joinToString(" | ")}")
        scope.launch(Dispatchers.Default) { guarded(myRun) {
            turnLock.withLock {
                // Whisper is forced to the target language: what the player says in any other language is their problem.
                // Not preloaded when the system recognizer covers the language: [retryAsr] loads it only if that hears nothing.
                val systemEars = runCatching { Nano.basicAsr(destination.lang) == FeatureStatus.AVAILABLE }.getOrDefault(false)
                if (systemEars || !Models.has(ctx, Models.WHISPER)) retryLock.withLock { asr?.release(); asr = null }
                else primaryRecognizer(destination.lang) // already loaded by the cover's preloadEars, or waits for it
                if (retryAsr?.config?.modelConfig?.whisper?.language != destination.lang) { retryAsr?.release(); retryAsr = null }
                // Loaded in the background now: a cold load at the first empty transcript stalled the answer ~10 s.
                if (systemEars && Models.has(ctx, Models.WHISPER)) scope.launch(Dispatchers.Default) { retryRecognizer(myRun) }
                val female = Country.isFemale(officerId)
                tts?.let { Voices.survey(ctx, it, destination.lang); Voices.apply(ctx, it, destination.lang, female, officerId); it.setSpeechRate(1f) }
                loadPiper(female)
                analyst = Analyst(b, destination, story, sub)
            }
            officerTurn(myRun, "[The next traveler steps up to your desk. Greet them and ask for their passport.]", answer = false)
        } }
    }

    private fun unit() = "Unit ${officerId.uppercase().replace('_', '-')} fw %.1f".format(1.0 + 1.5 * mind.p.strictness)

    /** The officer's Piper voice for [female], swapped under [voiceLock] so no synthesis runs on the one released. */
    private suspend fun loadPiper(female: Boolean) {
        val v = Piper.find(ctx, destination.lang, female)
        if (v?.first?.path.orEmpty() != piperVoice) voiceLock.withLock {
            piper?.release(); piper = v?.let { runCatching { Piper.load(it.first, it.second) }.getOrNull() }
            piperVoice = if (piper != null) v!!.first.path else ""
        }
        dbg("voice ${if (piper != null) "piper ${v?.first?.name}" else "system"}")
    }

    /** A model error mid-interview (AICore quota, BUSY after retries, a model failure) ends it cleanly instead of crashing. */
    private suspend fun guarded(myRun: Int, block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            dbg("model error: $e")
            modelInterrupted(myRun)
        }
    }

    @Synchronized private fun modelInterrupted(myRun: Int) {
        if (myRun != run) return
        abortAttempt(AbortCause.INTERRUPTED) {
            narration = str(R.string.link_lost)
            val stoppedRun = run
            scope.launch {
                delay(3500)
                if (stoppedRun == run && screen == Screen.INTERVIEW) {
                    narration = null
                    continueInterrupted()
                }
            }
        }
    }

    fun stopInterview(reason: String = "ui") {
        stopFaceRun()
        dbg("stop reason=$reason")
        run++
        implantJob?.cancel(); implant = null
        pendingRetina?.cancel(); pendingRetina = null
        pendingSmallTalkOdd?.cancel(); pendingSmallTalkOdd = null
        speakJob?.cancel()
        silenceJob?.cancel(); listenJob?.cancel()
        listening = false; holding = false; forced = null
        fragment = null
        tts?.stop()
        mic = Mic.OFF
        // ~1 GB back between interviews; released after the listen job, which may still be inside a transcription.
        retryAsr?.let { w -> retryAsr = null; val j = listenJob; CoroutineScope(Dispatchers.Default).launch { j?.join(); w.release() } }
    }

    /**
     * Downloads only what this phone lacks. Officer AI: the system's Gemini Nano (an AICore download at most); a phone
     * without it downloads nothing. Speech: the system recognizer's language pack (~110 MB) per language, else Whisper
     * from Play (1 GB, once). Then each language's art pack, and its offline voices are checked and measured.
     */
    fun install(langs: Set<String>) {
        if (supported != null) { screen = Screen.UNSUPPORTED; return }
        screen = Screen.DOWNLOAD
        if (installing) return // one at a time: the download screen already shows the running one
        installing = true
        scope.launch(Dispatchers.Default) {
            val core = PackStep(str(R.string.step_ai_models), str(R.string.step_ai_models_note))
            steps = listOf(core) + langs.map { PackStep(str(R.string.step_pack, Language.of(it).uiName(uiLanguage(ctx))), str(R.string.step_pack_note), lang = it) }
            fun update(i: Int, f: (PackStep) -> PackStep) { steps = steps.toMutableList().also { it[i] = f(it[i]) } }
            fun fail(i: Int, why: String) { update(i) { it.copy(note = why, failed = true) }; installing = false }
            fun mb(b: Long) = str(R.string.size_mb, b / 1e6)
            var at = 0 // the step an unexpected error fails
            // A throw (Play, AICore, TTS) must not leave installing stuck at true.
            try {
                while (supported == null && (nanoStatus == null || basicAsr == null)) delay(100) // the start-up probe
                if (supported != null) return@launch // no Nano: the probe already showed the unsupported screen
                if (nanoStatus != FeatureStatus.AVAILABLE) { // DOWNLOADABLE, DOWNLOADING or NANO_UNKNOWN: Nano.model re-checks
                    update(0) { it.copy(note = str(R.string.step_getting_nano)) }
                    var shown = -1L // whole MB on screen: progress arrives far faster than that changes
                    val m = runCatching {
                        Nano.model(::dbg, progress = { done, total ->
                            if (done / 1_000_000 != shown) { shown = done / 1_000_000; update(0) { it.copy(note = str(R.string.step_downloading, mb(done), mb(total))) } }
                        }, waiting = { update(0) { it.copy(note = str(R.string.step_nano_waiting)) } })
                    }.onFailure { dbg("nano download failed: $it") }.getOrNull()
                        ?: run { loadError = str(R.string.step_nano_failed); return@launch fail(0, str(R.string.step_nano_failed)) } // the cover offers the retry
                    m.close(); nanoStatus = FeatureStatus.AVAILABLE // loadBrain below opens its own
                }
                // A probe that threw is unknown, not "no recognizer": ask again before fetching 1 GB of Whisper for it.
                (languages + langs).filter { basicAsr?.get(it) == null }.takeIf { it.isNotEmpty() }?.let { u ->
                    basicAsr = basicAsr.orEmpty() + probeAsr(u).also { dbg("asr re-probe $it") }
                }
                val packs = corePacks(langs)
                if (packs.isNotEmpty()) {
                    val need = Packs.freeNeeded(ctx, packs)
                    if (Packs.freeBytes(ctx) < need)
                        return@launch fail(0, str(R.string.step_needs_free, need / 1e9, mb(Packs.freeBytes(ctx))))
                    Packs.fetch(ctx, packs, onProgress = { st ->
                        val done = st.values.sumOf { it.done }; val total = st.values.sumOf { it.total }.coerceAtLeast(1)
                        update(0) { it.copy(note = str(R.string.step_downloading, mb(done), mb(total))) }
                    }, onConfirm = { needsConfirm = true })?.let { return@launch fail(0, it) }
                    update(0) { it.copy(note = str(R.string.step_unpacking)) }
                    val missing = runCatching { Packs.installCore(ctx, packs) }
                        .getOrElse { dbg("unpack failed: $it"); return@launch fail(0, str(R.string.step_unpack_failed, it.message.orEmpty())) }
                    // ponytail: dev bundles ship placeholder core packs; their models come from scripts/push.sh.
                    if (missing.isNotEmpty()) return@launch fail(0, str(R.string.step_no_models, missing.joinToString()))
                }
                loadBrain()
                update(0) { it.copy(done = true, note = str(R.string.step_nano_builtin)) }

                val t = ttsReady.await()
                val noVoice = mutableListOf<String>()
                langs.forEachIndexed { i, lang ->
                    at = i + 1
                    Packs.fetch(ctx, listOf(Packs.lang(lang)), onProgress = { st ->
                        st.values.firstOrNull()?.let { p -> update(i + 1) { it.copy(note = str(R.string.step_downloading, mb(p.done), mb(p.total))) } }
                    }, onConfirm = { needsConfirm = true })?.let { return@launch fail(i + 1, it) }
                    // Android may defer this download ("will be retried automatically"): not fatal, startInterview
                    // checks again and falls back to Whisper until the system recognizer is ready.
                    val speech = if (basicAsr?.get(lang) == true) Nano.downloadAsr(lang) { done, total ->
                        update(i + 1) { it.copy(note = str(R.string.step_speech_model, mb(done), mb(total))) }
                    }?.also { dbg("speech pack $lang deferred: $it") } else null
                    val n = Voices.survey(ctx, t, lang) { k, total -> update(i + 1) { it.copy(note = str(R.string.step_tuning_voices, k + 1, total)) } }
                    if (n == 0) noVoice += lang
                    val parts = if (n == 0) listOf(str(R.string.step_no_voice)) else {
                        val dest = airportCount(lang)
                        listOf(ctx.resources.getQuantityString(R.plurals.step_destinations, dest, dest),
                            ctx.resources.getQuantityString(R.plurals.step_voices, n, n))
                    } + listOfNotNull(speech?.let { str(R.string.step_speech_finishing) })
                    update(i + 1) { it.copy(done = true, failed = n == 0, noVoice = n == 0, note = parts.joinToString(" · ")) }
                    languages = languages + lang
                    prefs.edit { putStringSet("languages", languages) }
                }
                voiceMissing = noVoice
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                dbg("install failed: $e"); fail(at, str(R.string.step_failed, e.message ?: e.javaClass.simpleName))
            } finally { installing = false }
        }
    }

    /** After installing voice data in the system settings: probe those languages again. */
    fun recheckVoices() = scope.launch(Dispatchers.Default) {
        val t = ttsReady.await()
        voiceMissing = voiceMissing.filter { lang -> Voices.forgetFailures(ctx, lang); Voices.survey(ctx, t, lang) == 0 }
        steps = steps.map { st ->
            val lang = st.lang
            if (lang != null && st.failed && lang !in voiceMissing) st.copy(failed = false, noVoice = false, note = str(R.string.step_voice_ready)) else st
        }
    }

    fun abort(cause: AbortCause = AbortCause.ABANDONED) {
        if (cause == AbortCause.INTERRUPTED) backgrounded = true
        abortAttempt(cause) { screen = Screen.START }
    }

    @Synchronized private fun abortAttempt(cause: AbortCause, after: () -> Unit) {
        if (startingAttempt) { interruptStart = true; stopInterview("start interrupted"); return }
        val live = liveAttempt
        // Stop audio even while a terminal commit is in flight, but never replace that candidate.
        if (live?.terminal != null || terminalTransition) {
            stopInterview("terminal background")
            return
        }
        if (live == null) {
            if (screen == Screen.INTERVIEW && !navigationBlocked) { stopInterview("abort idle"); screen = Screen.START }
            return
        }
        val finished = finishAttempt(live, live.run, abortKind(cause))
        liveAttempt = finished
        stopInterview("abort $cause")
        val event = finished.terminal ?: return
        saveCareer({ career.record(event, "start") }) {
            liveAttempt = null
            narration = null; sceneArt = null; blackout = false; flash = false; ending = null
            after()
        }
    }

    @Synchronized private fun captureAttemptDecision(myRun: Int, decision: Decision) {
        liveAttempt = liveAttempt?.let { captureDecision(it, myRun, decision, wasInRoom, room != null) }
    }

    /** Reserve the terminal event before dispatching IO; speech cancellation cannot cancel this save. */
    @Synchronized private fun saveTerminal(myRun: Int, after: () -> Unit) {
        val live = liveAttempt ?: return
        if (live.terminal != null || myRun != run) return
        val finished = finishAttempt(live, myRun, TerminalKind.DECIDED)
        val event = finished.terminal ?: return
        liveAttempt = finished
        terminalTransition = true
        stopInterview("terminal save")
        saveCareer({ career.record(event, "terminal") }) {
            if (event.context.training) { training = false; prefs.edit { putBoolean("trained", true) } }
            whenForeground(after)
        }
    }

    private fun finishTerminal(to: Screen) = whenForeground {
        stopInterview("terminal saved")
        liveAttempt = null; terminalTransition = false
        narration = null; sceneArt = null; blackout = false; flash = false
        screen = to
    }

    private var driver: com.k2fsa.sherpa.onnx.OfflineTts? = null

    /** The finale's driver: a male English Piper voice if installed, else the system voice a little lower. */
    fun say(text: String) {
        val v = Piper.find(ctx, "en", false)
        if (v != null) {
            piperSay({ driver ?: runCatching { Piper.load(v.first, v.second) }.getOrNull()?.also { driver = it } }, text, 0.9f, 0f) {} // the driver is human: clean voice
            return
        }
        val t = tts ?: return
        t.language = Locale.US; t.setPitch(0.85f); t.setSpeechRate(0.9f)
        t.setOnUtteranceProgressListener(null)
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "finale")
    }

    fun replay() {
        // Not during a scene beat, the walk to the room, or the last words (officerFinished would act on them again).
        if (officerText.isEmpty() || narration != null || (room == null && wasInRoom) || mind.decision != null) return
        speak(run, officerText)
    }

    // ---------- the officer ----------

    private suspend fun officerTurn(myRun: Int, said: String, answer: Boolean = true) = guarded(myRun) { lockedTurn(myRun, said, answer) }

    /** One officer reply. Serialized: a silence timeout and a spoken answer must not both talk to the model. */
    private suspend fun lockedTurn(myRun: Int, input: String, isAnswer: Boolean) = turnLock.withLock {
        if (myRun != run) return@withLock
        if (passportGiven && passportAsked && room == null && !input.startsWith(HANDOVER)) {
            dbg("turn skipped: handover pending")
            return@withLock
        }
        val wasSmallTalk = heldHandover != null
        val said = smallTalk(myRun, input, isAnswer) ?: return@withLock // a small-talk question was asked instead
        awaitSmallTalkOdd(myRun, "interview")
        if (myRun != run) return@withLock
        val answer = judgeInterviewAnswer(isAnswer, wasSmallTalk, said.startsWith(HANDOVER))
        history += "Traveler: $said"
        if (said.startsWith(HANDOVER)) passportAsked = false
        room?.let { roomTurn(myRun, it, said, answer); return@withLock }
        pendingRetina?.await() // the hints call must finish before the model takes the next one
        if (myRun != run) return@withLock
        pendingRetina = null
        mic = Mic.PROCESSING
        // Judge this answer before the officer replies, so a slip is pressed on at once ("Two weeks? You said five
        // days."). Costs ~1.6-2 s, which reads as the officer weighing the answer.
        val a = analyst
        if (answer && a != null && question.isNotEmpty()) a.judge(question, said).let { v ->
            if (myRun != run) return@withLock
            val before = mind.integrity
            mind.onCheck(v.check, v.fact)
            integrityNote(myRun, before)
            if (v.fact > 0 && (v.fact != 1 || desk.topic == "born")) covered += v.fact
            dbg("judge ${v.ms}ms fact=${v.fact} ${v.check} raw=\"${v.raw}\" step2=\"${v.step2}\" step3=\"${v.step3}\" -> ${mind.state()}")
            dbg("mind ${mind.delta}")
        }
        if (!answer && mind.emptyPressed && question.isNotEmpty() && mind.decision == null) {
            fun press(l: String) = renderLine("dodge.curt.1", l, story.female, story.days, "").orEmpty()
            officerText = "${press(destination.lang)} $question"
            officerEn = "${press(sub)} $officerEn"
            spoken = false; shown = true; clockAt = 0L; lookAsked = false
            history += "Officer: $question"
            speak(myRun, officerText)
            startClock(myRun)
            return@withLock
        }
        face = mind.face()
        hints = emptyList()
        spoken = false; shown = false; clockAt = 0L; lookAsked = false
        mind.decision?.let { d ->
            mind.takeCues()
            val (line, english) = finalLine(d)
            officerText = line; officerEn = english
            if (d == Decision.BROKEN) tts?.run { setPitch(0.55f); setSpeechRate(0.7f) } // winding down
            history += "Officer: $line"
            dbg("ending $d | $line")
            speak(myRun, line)
            return@withLock
        }
        val cues = mind.takeCues()
        val m = desk.next(cues, mind.cueFact, covered, passportPending = deskSpoken && !passportGiven, first = !deskSpoken,
            handover = said.startsWith(HANDOVER), curt = mind.patience < 0.35)
        fun render(l: String) = listOfNotNull(m.prefix, m.question).joinToString(if (l == "ja" || l == "zh") "" else " ") {
            renderLine(it, l, story.female, story.days, LANGUAGE_NAMES[l]?.get(destination.lang).orEmpty(),
                country = if (story.stamps.size >= 2) coverNation(story.stamps[1].substringBeforeLast(' '), l) else "").orEmpty() }
        val line = render(destination.lang); val subtitle = render(sub)
        deskSpoken = true
        if (!m.reask) mind.onQuestion()
        question = line
        asked = isQuestion(question, destination.lang)
        officerText = line; officerEn = subtitle
        speak(myRun, line)
        history += "Officer: $question"
        dbg("officer move=${m.prefix ?: "-"}+${m.question} cues=$cues face=$face ${mind.state()} | $line | $subtitle")
        if (a == null || mind.decision != null) return@withLock
        val trap = !training && Random.nextDouble() < trapChance(mind.p.level)
        pendingRetina = scope.async(Dispatchers.Default) {
            val ep = a.suggest(history, trap)
            if (myRun == run) {
                hints = ep.options.map(Models::option); shown = true; startClock(myRun)
                // Training order: the officer's first line asks for the passport, so that comes before speaking.
                if (training && introTip != null && tip == introTip)
                    tip = if (!passportGiven) str(R.string.tip_passport) else speakTip()
            }
            dbg("retinal display ${ep.turn.totalMs}ms trap=$trap")
            dbg("hints | " + ep.options.map(Models::option).joinToString(" | ") { "${it.target} / ${it.english}" })
            ep
        }
    }

    /**
     * Small talk, under [turnLock]: the handover turn is held back and [SMALL_TALK_ASKED] fixed questions are
     * asked instead; each answer moves on (silence re-asks), and after the last the held handover starts the desk questions.
     * Returns the input for the interview turn, or null when a small-talk question was asked.
     */
    private suspend fun smallTalk(myRun: Int, input: String, isAnswer: Boolean): String? {
        if (input.startsWith(HANDOVER) && room == null && talkAt < 0 && talks.isNotEmpty() && mind.decision == null) {
            heldHandover = input; passportAsked = false
            mind.onSmallTalk() // small talk starts clean: pauses at the passport don't carry into it
            askSmallTalk(myRun, 0)
            return null
        }
        val held = heldHandover ?: return input
        if (mind.decision != null) { heldHandover = null; closeBaseline(); return held } // ODD, debug #pass/#fail, or a third pause
        talkLines += "Traveler: $input"
        mind.dropCuesExcept(Cue.ODD) // only odd small talk carries into the interview turn
        nextSmallTalk(talkAt, talks.size, isAnswer)?.let { askSmallTalk(myRun, it); return null }
        heldHandover = null
        closeBaseline()
        return held
    }

    /** One fixed small-talk question, spoken verbatim; the retinal display suggests replies as for any question. */
    private suspend fun askSmallTalk(myRun: Int, i: Int) {
        pendingRetina?.await(); pendingRetina = null // the hints call must finish before the next one
        if (myRun != run) return
        talkAt = i
        val line = SMALL_TALK.getValue(destination.lang)[talks[i]]
        face = mind.face(); hints = emptyList()
        spoken = false; shown = false; clockAt = 0L; lookAsked = false
        question = line; asked = true
        officerText = line; officerEn = SMALL_TALK.getValue(sub)[talks[i]]
        talkLines += "Officer: $line"
        dbg("smalltalk ask q=${talks[i]} | $line")
        speak(myRun, line)
        awaitSmallTalkOdd(myRun, "smalltalk") // TTS is already playing while Nano finishes the ODD read
        if (myRun != run) return
        val a = analyst ?: return
        val seen = history + talkLines // never in [history]: the interview's prompts stay as they were
        pendingRetina = scope.async(Dispatchers.Default) {
            val ep = nanoLock.withLock { a.suggest(seen, smallTalk = true) }
            if (myRun == run) { hints = ep.options.map(Models::option); shown = true; startClock(myRun) }
            dbg("retinal display ${ep.turn.totalMs}ms smalltalk")
            dbg("hints | " + ep.options.map(Models::option).joinToString(" | ") { "${it.target} / ${it.english}" })
            ep
        }
    }

    private suspend fun awaitSmallTalkOdd(myRun: Int, next: String) {
        val pending = pendingSmallTalkOdd ?: return
        val t0 = System.nanoTime()
        pending.await()
        if (myRun != run) return
        if (pendingSmallTalkOdd === pending) pendingSmallTalkOdd = null
        dbg("smalltalk odd wait=${(System.nanoTime() - t0) / 1_000_000}ms before=$next")
    }

    /** End of small talk: the face baseline (greeting, passport, small talk) and the think-time one. */
    private fun closeBaseline() {
        mind.pace = Pace.of(talkThink)
        val face = if (!watching) "off" else if (profiler.calibrate(faceWatch.since(interviewAt))) "ok" else "too little face"
        dbg("baseline closed face=$face think=${mind.pace.baseMs ?: "none"}")
    }

    private fun officerRobot() = if (finaleSeen && !storyMission) Piper.ANDROID_V2 else Piper.ANDROID // the finale mission's own interrogator keeps the old unit

    private fun speak(myRun: Int, text: String, onDone: () -> Unit = { officerFinished(myRun) }) {
        if (myRun != run) return // a streamed line from an interview that was already stopped
        mic = Mic.OFFICER_SPEAKING
        listening = false
        silenceJob?.cancel()
        speakJob?.cancel()
        if (piper != null) {
            val broken = mind.decision == Decision.BROKEN // winding down: slower, ring-modulated
            speakJob = piperSay({ piper.takeIf { myRun == run } }, text, if (broken) 0.7f else 1f, if (broken) Piper.CRASH else officerRobot(), onDone)
            return
        }
        val t = tts ?: return
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = onDone()
            @Deprecated("") override fun onError(id: String?) = onDone()
        })
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "officer")
    }

    /**
     * One Piper synthesis under [voiceLock]: sherpa-onnx must never generate twice at once on an instance or be released
     * mid-generate. Cancelling the job only mutes the rest; the lock is held until the native call returns.
     */
    private fun piperSay(pick: () -> com.k2fsa.sherpa.onnx.OfflineTts?, text: String, speed: Float, robot: Float, onDone: () -> Unit) =
        scope.launch(Dispatchers.Default) {
            voiceLock.withLock {
                val p = pick() ?: return@withLock
                val j = Piper.speak(this@launch, p, text, speed, robot, onDone)
                withContext(NonCancellable) { j.join() }
            }
        }

    @Synchronized private fun officerFinished(myRun: Int) {
        if (myRun != run) return
        if (liveAttempt?.terminal != null) return
        mind.decision?.let { d ->
            // Assessment decisions are captured by roomTurn, not by a late desk speech callback.
            if (!wasInRoom) captureAttemptDecision(myRun, d)
            // Level 2 follows a cleared desk (in the story a broken one too), but never training (level 1 only): the
            // taser and the unknown room in the story, the test at the desk afterwards. A failed desk is a failed mission.
            val enters = entersRoom(d, wasInRoom, training, storyMission, mind.suspicion, mind.contradictions)
            if (d == Decision.PASS && !wasInRoom && !training && !storyMission) {
                dbg("assessment ${if (enters) "required" else "skipped"}: suspicion=%.2f contradictions=%d".format(mind.suspicion, mind.contradictions))
                if (!enters) liveAttempt = liveAttempt?.let { skipAssessment(it, myRun) }
            }
            if (enters)
                return enterRoom(myRun, if (storyMission) Entry.TASER else Entry.AT_DESK)
            if (wasInRoom && room == null) return // the desk's PASS while the room is still being set up
            val last = finalLine(d).first
            if (officerText != last) { // safety net: the decision landed after a question was already spoken
                scope.launch(Dispatchers.Default) { officerTurn(myRun, "[decision]", answer = false) }
                return
            }
            val finale = reachesFinale(d, storyMission, wasInRoom)
            saveTerminal(myRun) {
                ending = d; tip = null
                if (!finale || d == Decision.BROKEN) hapticPlayer.play(when (d) { Decision.PASS -> HapticEvent.STAMP_PASS; Decision.SECONDARY -> HapticEvent.STAMP_DETAIN; Decision.BROKEN -> HapticEvent.BREAK })
                if (!finale) sfx.play(when (d) { Decision.PASS -> R.raw.sfx_stamp; Decision.SECONDARY -> R.raw.sfx_door; Decision.BROKEN -> R.raw.sfx_scan })
                if (finale) {
                    prefs.edit { putBoolean("finale", true) }; finaleSeen = true
                    scope.launch {
                        // Lifecycle stop may invalidate speech's run while the durable result still owns navigation.
                        if (d == Decision.BROKEN) delay(650) // let the crash decay before the separate taser
                        sting(run)
                        finishTerminal(Screen.FINALE)
                    }
                } else scope.launch { delay(2800); finishTerminal(Screen.RESULT) }
            }
            return
        }
        awaitAnswer(myRun)
    }

    /** The officer has spoken and waits for the answer: the timers and the mic. */
    private fun awaitAnswer(myRun: Int) {
        if (forced != null) return settleForced(myRun)
        if (inputMode == InputMode.HOLD) mic = Mic.READY
        spoken = true
        if (clockAt != 0L) armSilence(myRun) // a replay: the officer repeated the question, so the wait restarts
        else startClock(myRun)
        if (inputMode == InputMode.AUTO) listenAuto(myRun)
    }

    @Synchronized private fun startClock(myRun: Int) {
        if (!spoken || !shown || clockAt != 0L || myRun != run || mind.decision != null) return
        clockAt = System.nanoTime()
        answerAt = faceWatch.now() // the officer watches from the moment the traveler could start answering
        armSilence(myRun)
    }

    /** Thinking time so far; zero if the player answers before the suggestions even appeared. */
    private fun thinkMs(at: Long = System.nanoTime()) = if (clockAt == 0L) 0L else ((at - clockAt) / 1_000_000).coerceAtLeast(0)

    /** The invisible answer timer. No UI: the officer just speaks up when their patience for silence runs out. */
    private fun armSilence(myRun: Int) {
        silenceJob?.cancel()
        if (training && !passportGiven) return // don't nag while the trainee looks for the passport button
        val window = room?.answerMs ?: mind.silenceDeadlineMs()
        silenceJob = scope.launch(Dispatchers.Default) {
            delay(window)
            if (holding || mic == Mic.HEARING || myRun != run) return@launch
            listening = false
            val turns = mind.turns + talkThink.size
            listenJob?.join()
            // The phrase had started: recordVad returned it and it was answered (or is still being heard).
            if (myRun != run || mic == Mic.HEARING || mind.turns + talkThink.size != turns) return@launch
            // After a statement ("Ten days. Fine.") or while the passport is still asked for, waiting is not a failure:
            // the officer just goes on, at no cost. Small talk too.
            val free = room == null && (!asked || !passportGiven || heldHandover != null)
            if (free) mind.onPause(passportPending = !passportGiven) else { mind.onSilence(); room?.onSilence() }
            dbg("silence after ${thinkMs()}ms asked=$asked passport=$passportGiven window=${window}ms free=$free -> ${mind.state()}")
            dbg("mind ${mind.delta}")
            // Not inside this job: speak() cancels the silence timer, which would kill the officer's turn mid-reply.
            scope.launch(Dispatchers.Default) { officerTurn(myRun, "[The traveler says nothing.]", answer = false) }
        }
    }

    // ---------- the player ----------

    /** The player slides the passport across. The officer sees the stamps, which can start a line of questions. */
    fun handOverPassport() {
        if (passportGiven || mind.decision != null || mic == Mic.HEARING) return
        passportGiven = true
        dbg("passport handed think=${thinkMs()}ms")
        // The cancel stops a silence timer still in delay() or listenJob.join(), so it can't reach onPause/onSilence for
        // the passport request; only a handler already past its last suspension point (microseconds) still runs.
        silenceJob?.cancel(); listening = false
        sfx.play(R.raw.sfx_scan)
        hapticPlayer.play(HapticEvent.SCAN)
        if (training) tip = speakTip()
        val myRun = run
        val stamps = story.stamps.joinToString()
        scope.launch(Dispatchers.Default) {
            listenJob?.join()
            officerTurn(myRun, "$HANDOVER You scan it: a ${story.country.nation} passport, its holder coming home. Stamps: $stamps.]", answer = false)
        }
    }

    fun press() {
        if (inputMode != InputMode.HOLD || mic != Mic.READY) return
        holding = true
        silenceJob?.cancel()
        val myRun = run
        val thinkMs = thinkMs()
        mic = Mic.HEARING
        listenJob = scope.launch(Dispatchers.Default) {
            val pcm = record { holding }
            if (pcm == null) { mic = Mic.READY; if (clockAt != 0L) armSilence(myRun) } else utterance(myRun, pcm, thinkMs)
        }
    }

    fun release() { holding = false }

    /** [follow]: a [fragment] is waiting for the rest of the phrase; nothing within [FOLLOW_MS] sends it as it is. */
    private fun listenAuto(myRun: Int, follow: Boolean = false) {
        val listenAt = System.nanoTime()
        listening = true
        listenJob = scope.launch(Dispatchers.Default) {
            if (!listening || myRun != run) return@launch
            var speechAt = 0L
            var openAt = 0L
            val pcm = recordVad(if (follow) FOLLOW_MS else 0L, skipMs = if (follow) 0L else ECHO_SKIP_MS, onOpen = {
                openAt = System.nanoTime()
                dbg("mic open ${ms(listenAt)}ms after listen")
                if (myRun == run && mic != Mic.HEARING) mic = Mic.READY
            }, onSpeech = {
                speechAt = System.nanoTime()
                silenceJob?.cancel()
                mic = Mic.HEARING
            })
            if (pcm == null) {
                if (openAt == 0L && listening && myRun == run) mic = Mic.READY // mic denied or busy: show the tip
                if (follow) flushFragment(myRun)
                return@launch
            }
            utterance(myRun, pcm, thinkMs(speechAt), (speechAt - openAt) / 1_000_000)
        }
    }

    private val retryLock = Mutex()
    /**
     * Whisper for the retry: preloaded at each interview start (stopInterview releases it, ~1 GB off the menu) and
     * shared with a retry that comes first; null if [myRun] ended, before or during the load.
     */
    private suspend fun retryRecognizer(myRun: Int): OfflineRecognizer? = retryLock.withLock {
        if (myRun != run) return@withLock null
        retryAsr?.takeIf { it.config.modelConfig.whisper.language == destination.lang }?.let { return@withLock it }
        val w = Models.asr(ctx, "turbo", destination.lang)
        if (myRun != run) { w.release(); return@withLock null } // stopped during the load: don't keep it
        retryAsr?.release(); retryAsr = w
        dbg("retry whisper loaded")
        w
    }

    /**
     * Whisper as the primary recognizer for [lang] (no system one), kept between interviews. Under [retryLock] so the
     * cover preload and startInterview never load it twice; null (and released) if the destination changed meanwhile.
     */
    private suspend fun primaryRecognizer(lang: String): OfflineRecognizer? = retryLock.withLock {
        if (destination.lang != lang) return@withLock null
        asr?.takeIf { it.config.modelConfig.whisper.language == lang }?.let { return@withLock it }
        asr?.release(); asr = null
        val w = Models.asr(ctx, "turbo", lang)
        if (destination.lang != lang) { w.release(); return@withLock null } // another mission during the load: don't keep it
        dbg("whisper loaded ($lang)")
        w.also { asr = it }
    }

    /** The cover card of a slow language: load its Whisper now; a cold load at the first answer stalled it ~10 s. */
    fun preloadEars() {
        val lang = destination.lang
        if (!slowLang(lang) || !Models.has(ctx, Models.WHISPER)) return
        scope.launch(Dispatchers.Default) { runCatching { primaryRecognizer(lang) }.onFailure { dbg("whisper preload failed: $it") } }
    }

    /** An implant line the coach loop leaves alone for [ms]; cleared then unless a newer note took over. */
    private fun implantNote(myRun: Int, text: String, ms: Long = 6000) {
        implant = text
        hintUntil = System.currentTimeMillis() + ms
        scope.launch { delay(ms); if (myRun == run && System.currentTimeMillis() >= hintUntil) implant = null }
    }

    /** After a trick: the implant eggs the traveler on while the unit slips (once per level); silent once it decided. */
    private fun integrityNote(myRun: Int, before: Double) {
        val over = mind.decision != null // crashed, or the trick tipped the desk into a verdict: nothing to push
        if (over) { implant = null; hintUntil = 0 }
        integrityHint(before, mind.integrity, over)?.let {
            implantNote(myRun, str(it))
            hapticPlayer.play(if (it == R.string.implant_unit_stutter) HapticEvent.IMPLANT_STUTTER else HapticEvent.IMPLANT_GLITCH)
            dbg("implant ${ctx.resources.getResourceEntryName(it)} integrity=$before->${mind.integrity} | $implant")
        }
    }

    private fun version() = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).let { "${it.versionName}/${it.longVersionCode}" }
    }.getOrDefault("?")

    /** The start of a phrase ("I…") heard in auto mode, held until the rest comes (see [Delivery.fragment]). */
    private class Fragment(val pcm: FloatArray, val text: String, val thinkMs: Long)
    @Volatile private var fragment: Fragment? = null

    /** Nothing more came: the held fragment is the answer. */
    private suspend fun flushFragment(myRun: Int) {
        val f = fragment ?: return
        fragment = null
        if (myRun != run) return
        mic = Mic.PROCESSING
        answer(myRun, f.pcm, f.text, f.thinkMs)
    }

    /** [speechAfterMs]: how long after the mic opened the phrase started (0 in hold mode: unknown), see [isEcho]. */
    private suspend fun utterance(myRun: Int, pcm: FloatArray, thinkMs: Long, speechAfterMs: Long = 0) {
        if (myRun != run) return
        if (pcm.size < 8000) { dbg("heard nothing: clip too short (${pcm.size / 16} ms)"); if (fragment != null) return flushFragment(myRun); mic = if (inputMode == InputMode.HOLD) Mic.READY else Mic.PROCESSING; if (clockAt != 0L) armSilence(myRun); if (inputMode == InputMode.AUTO) listenAuto(myRun); return }
        mic = Mic.PROCESSING
        val t0 = System.nanoTime()
        if (diagnostics) Models.writeWav(File(File(ctx.filesDir, "wavs").apply { mkdirs() }, "${destination.lang}_rec_${System.currentTimeMillis()}.wav"), pcm)
        // System recognizer when the language has one (~0.3 s after the clip), else Whisper (~1.5-3 s).
        var src = if (asr != null) "whisper" else "system"
        var text = asr?.let { Models.transcribe(it, pcm).text }
            ?: runCatching { Nano.transcribe(destination.lang, pcm, ::dbg, advanced = false)?.first }.getOrNull().orEmpty()
        if (text.isBlank() && asr == null && Models.has(ctx, Models.WHISPER) && myRun == run) {
            // The system recognizer sometimes returns nothing for real speech: one more try with Whisper.
            val w = retryRecognizer(myRun) ?: return
            text = Models.transcribe(w, pcm).text; src = "retry"
            dbg("system recognizer heard nothing; Whisper retry: \"$text\"")
            if (myRun != run) return // stopped during the (blocking) load or transcription
        }
        currentCoroutineContext().ensureActive() // cancelled mid-transcription (stopInterview, a debug [inject]): no answer
        if (text.isBlank()) dbg("heard nothing: empty transcript (${pcm.size / 16} ms clip)")
        if (isEcho(text, question, speechAfterMs)) {
            dbg("echo ignored (${speechAfterMs}ms after the mic opened): $text")
            if (fragment != null) return flushFragment(myRun)
            if (inputMode == InputMode.HOLD) mic = Mic.READY
            if (clockAt != 0L) armSilence(myRun)
            if (inputMode == InputMode.AUTO) listenAuto(myRun)
            return
        }
        dbg("asr=${ms(t0)}ms src=$src")
        // Auto mode: "I…" and a pause is not the answer yet. Hold it and keep listening (HOLD: the player ends it).
        val f = fragment; fragment = null
        val all = if (f == null) pcm else f.pcm + pcm
        val said = if (f == null) text else "${f.text} $text".trim()
        val think = f?.thinkMs ?: thinkMs
        if (inputMode == InputMode.AUTO && Delivery.fragment(said) && all.size < FRAGMENT_MAX_S * 16000) {
            dbg("fragment, listening on: \"$said\"")
            fragment = Fragment(all, said, think)
            mic = Mic.HEARING // holds the officer's silence timer; FOLLOW_MS of nothing sends it
            listenAuto(myRun, follow = true)
            return
        }
        answer(myRun, all, said, think)
    }

    private suspend fun answer(myRun: Int, pcm: FloatArray, text: String, thinkMs: Long) {
        if (myRun != run) return
        heard = text
        if (implant == str(R.string.implant_slow, destination.languageName(uiLanguage(ctx)))) { implant = null; hintUntil = 0 } // up until the first answer
        // Whatever is said while the officer asks for the passport goes with handing it over: not a cover answer.
        // Routed on the line being answered, not on passportGiven: the button can flip that mid-phrase, and the phrase
        // would then be judged against the passport request. After a button handover this speech is dropped.
        if (passportAsked && room == null) {
            dbg("you think=${thinkMs}ms (with the passport) | $text")
            return handOverPassport()
        }
        // Small talk: baseline plus ODD only, without interview answer bookkeeping.
        if (heldHandover != null && room == null) {
            dbg("smalltalk q=${talks.getOrNull(talkAt)} think=$thinkMs | $text")
            val b = brain
            if (text.any(Char::isLetter) && b != null && mind.decision == null && forced == null) {
                val q = question
                val destination0 = destination
                val mind0 = mind
                val retina = pendingRetina
                val previous = pendingSmallTalkOdd
                pendingSmallTalkOdd = scope.async(Dispatchers.Default) {
                    previous?.await()
                    retina?.await() // an early answer must not overlap the previous hints call
                    if (myRun != run || mind0.decision != null || forced != null) return@async
                    val t0 = System.nanoTime()
                    val raw = nanoLock.withLock {
                        Nano.oneShot(b)(smallTalkOddPrompt(destination0), "Officer asked: \"$q\"\nTraveler answered: \"$text\"", 12)
                    }
                    currentCoroutineContext().ensureActive()
                    if (myRun != run || mind0.decision != null || forced != null) return@async
                    val odd = Check.parseClassify(raw).second.odd
                    if (odd) mind0.onSmallTalkOdd()
                    face = mind0.face()
                    dbg("smalltalk odd=${if (odd) "yes" else "no"} ${(System.nanoTime() - t0) / 1_000_000}ms raw=\"$raw\" -> ${mind0.state()}")
                    if (odd) dbg("mind ${mind0.delta}")
                }
            }
            talkThink += thinkMs
            mind.onSmallTalk()
            return officerTurn(myRun, text, answer = text.any(Char::isLetter))
        }
        val words = text.split(Regex("\\s+")).count { it.any(Char::isLetter) }
        val a = room
        // Neither stage judges an answer nobody heard: one pressed re-ask, then the silence path.
        if (words == 0) {
            val again = if (a != null) a.onEmpty() else mind.onEmpty()
            if (a != null && !again) mind.onSilence()
            dbg("${if (a != null) "room" else "desk"} heard nothing -> ${if (again) "re-ask, pressed" else "silence"}")
            return officerTurn(myRun, "[The traveler says nothing.]", answer = false)
        }
        // Whatever Whisper heard goes to the officer as-is; mangled speech is the player's problem.
        mind.onAnswer(Delivery(thinkMs, pcm.size / 16000.0, text))
        dbg("mind ${mind.delta}")
        a?.onAnswer(thinkMs, words, mind.pace)
        if (watching) profile(myRun)
        if (training) tip = if (!passportGiven) str(R.string.tip_passport) else str(R.string.tip_consistent)
        dbg("you think=${thinkMs}ms | $text")
        officerTurn(myRun, text)
    }

    /** A started recorder, or null (and a tip) when the microphone is denied or busy. */
    private fun newRecorder(): Pair<AudioRecord, Int>? {
        micDenied = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (micDenied) { tip = str(R.string.tip_mic); return null }
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        var ar: AudioRecord? = null
        try {
            @Suppress("MissingPermission")
            ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT, max(min, 480 * 4) * 4)
            check(ar.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
            ar.startRecording()
            if (tip == str(R.string.tip_mic)) tip = null
            return ar to min
        } catch (e: Exception) { // IllegalStateException, SecurityException, IllegalArgumentException
            dbg("mic failed: $e"); ar?.release(); tip = str(R.string.tip_mic)
            return null
        }
    }

    private fun record(keepGoing: () -> Boolean): FloatArray? {
        val (ar, min) = newRecorder() ?: return null
        val out = ArrayList<FloatArray>()
        val buf = FloatArray(min)
        while (keepGoing()) {
            val n = ar.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
            if (n > 0) out += buf.copyOf(n)
        }
        ar.stop(); ar.release()
        return concat(out)
    }

    private val vadLock = Mutex() // recording and native release; cancelling a listen does not join its blocking read
    private var sileroVad: Vad? = null // lazy, one per Game; accessed only under vadLock
    private var vadClosed = false
    @Volatile private var sileroFailed = false

    /** Diagnostics for a listen that heard nothing: levels, client silencing, and (diagnostics on) the last ~15 s. */
    private inner class Heard(val ar: AudioRecord, frameSize: Int, val t0: Long) {
        var frames = 0; var peak = 0f; var sumSq = 0.0; var samples = 0L; var maxFrameRms = 0.0
        var silenced1: Boolean? = null
        val ring = ArrayDeque<FloatArray>(); val cap = 15 * 16000 / frameSize
        fun silenced() = ar.activeRecordingConfiguration?.isClientSilenced
        fun add(f: FloatArray) {
            frames++
            var sq = 0.0
            for (x in f) { sq += x * x; if (abs(x) > peak) peak = abs(x) }
            sumSq += sq; samples += f.size
            maxFrameRms = max(maxFrameRms, sqrt(sq / f.size))
            if (silenced1 == null && ms(t0) >= 1000) silenced1 = silenced()
            if (diagnostics) { ring.addLast(f); if (ring.size > cap) ring.removeFirst() }
        }
        fun report(): String {
            val wav = if (!diagnostics) null else runCatching {
                File(File(ctx.filesDir, "wavs").apply { mkdirs() }, "${destination.lang}_nospeech_${System.currentTimeMillis()}.wav")
                    .also { Models.writeWav(it, concat(ring)) }.name
            }.getOrNull()
            return "frames=%d peak=%.4f rms=%.4f maxFrameRms=%.4f silenced=%s/%s".format(frames, peak,
                if (samples > 0) sqrt(sumSq / samples) else 0.0, maxFrameRms, silenced1, silenced()) + (wav?.let { " wav=$it" } ?: "")
        }
    }

    /**
     * Auto mode: listen until a phrase starts and then ends (Silero VAD, the energy VAD if it can't load). Returns null
     * if listening was cancelled first. Reuse one lazy Vad, resetting its state and queued segments for each listen.
     */
    private suspend fun recordVad(startWithinMs: Long, skipMs: Long, onOpen: () -> Unit, onSpeech: () -> Unit): FloatArray? = vadLock.withLock {
        val recordingContext = currentCoroutineContext()
        recordingContext.ensureActive()
        if (vadClosed || !listening) return@withLock null
        val vad = if (sileroFailed) null else try {
            sileroVad ?: Vad(ctx.assets, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(model = "models/silero_vad.int8.onnx",
                minSilenceDuration = 1.4f, minSpeechDuration = 0.25f, maxSpeechDuration = 15f), sampleRate = 16000, numThreads = 1)).also { sileroVad = it }
        } catch (e: Throwable) { sileroFailed = true; dbg("vad: Silero failed, energy VAD instead: $e"); null }
        if (vad == null) return@withLock recordEnergyVad(startWithinMs, skipMs, onOpen, onSpeech)
        vad.reset()
        vad.clear() // discard any completed segment left by the previous listen
        recordingContext.ensureActive()
        val (ar, _) = newRecorder() ?: return@withLock null
        val frame = FloatArray(512) // 32 ms, Silero's window
        val preRoll = ArrayDeque<FloatArray>()
        val out = ArrayList<FloatArray>()
        var started = false
        var t0 = System.nanoTime()
        try {
            // audio, not time: a stalled thread still drops the queued tail
            var skipped = 0L
            while (listening && skipped < skipMs * 16) {
                recordingContext.ensureActive()
                val n = ar.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                recordingContext.ensureActive()
                if (n > 0) skipped += n
            }
            if (!listening) return@withLock null
            recordingContext.ensureActive()
            t0 = System.nanoTime()
            onOpen()
            val heard = Heard(ar, frame.size, t0)
            while (listening && (started || startWithinMs == 0L || ms(t0) < startWithinMs)) {
                recordingContext.ensureActive()
                val n = ar.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                recordingContext.ensureActive()
                if (n <= 0) continue
                val f = frame.copyOf(n)
                if (!started) heard.add(f)
                vad.acceptWaveform(f)
                if (!started) {
                    // Silero confirms speech only after minSpeechDuration: 0.8 s of pre-roll keeps the first syllable.
                    preRoll.addLast(f); if (preRoll.size > 25) preRoll.removeFirst()
                    if (vad.isSpeechDetected() || !vad.empty()) { started = true; out.addAll(preRoll); onSpeech() }
                } else {
                    out += f
                    if (!vad.empty() || out.size > 500) return@withLock concat(out) // a finished segment: the phrase ended (16 s cap)
                }
            }
            if (started) { dbg("vad: listening stopped mid-phrase"); return@withLock concat(out) } // the phrase still gets answered
            dbg("vad: no speech start in ${ms(t0)} ms ${heard.report()}")
            return@withLock null
        } finally { ar.stop(); ar.release() }
    }

    /** The fallback: energy VAD with an adaptive noise floor. */
    private suspend fun recordEnergyVad(startWithinMs: Long, skipMs: Long, onOpen: () -> Unit, onSpeech: () -> Unit): FloatArray? {
        val recordingContext = currentCoroutineContext()
        recordingContext.ensureActive()
        val (ar, _) = newRecorder() ?: return null
        val frame = FloatArray(480) // 30 ms
        val preRoll = ArrayDeque<FloatArray>()
        val out = ArrayList<FloatArray>()
        var floor = 0.004
        var loud = 0
        var started = false
        var quietMs = 0
        var t0 = System.nanoTime()
        try {
            var skipped = 0L
            while (listening && skipped < skipMs * 16) {
                recordingContext.ensureActive()
                val n = ar.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                recordingContext.ensureActive()
                if (n > 0) skipped += n
            }
            if (!listening) return null
            recordingContext.ensureActive()
            t0 = System.nanoTime()
            onOpen()
            val heard = Heard(ar, frame.size, t0)
            while (listening && (started || startWithinMs == 0L || ms(t0) < startWithinMs)) {
                recordingContext.ensureActive()
                val n = ar.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                recordingContext.ensureActive()
                if (n <= 0) continue
                val f = frame.copyOf(n)
                val rms = sqrt(f.sumOf { (it * it).toDouble() } / n)
                if (!started) {
                    heard.add(f)
                    preRoll.addLast(f); if (preRoll.size > 10) preRoll.removeFirst()
                    if (rms > max(floor * VAD_START, VAD_MIN)) loud++ else { loud = 0; floor = 0.95 * floor + 0.05 * rms }
                    if (loud >= 5) { started = true; out.addAll(preRoll); onSpeech() }
                } else {
                    out += f
                    quietMs = if (rms > max(floor * VAD_KEEP, VAD_MIN * 0.75)) 0 else quietMs + 30
                    if (quietMs >= VAD_END_MS || out.size * 30 > 15_000) return concat(out)
                }
            }
            if (started) { dbg("vad: listening stopped mid-phrase"); return concat(out) }
            dbg("vad: no speech start (energy, floor=%.4f) in %d ms ".format(floor, ms(t0)) + heard.report())
            return null
        } finally { ar.stop(); ar.release() }
    }

    private fun concat(chunks: List<FloatArray>): FloatArray {
        val all = FloatArray(chunks.sumOf { it.size })
        var i = 0
        chunks.forEach { it.copyInto(all, i); i += it.size }
        return all
    }

    private val debuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Debug builds only: answer without speaking, for walkthroughs over adb. `#hint` says the first suggestion;
     * `#pass` / `#fail` settle the current level (desk or room) as PASS / SECONDARY.
     * `adb shell am broadcast -a com.lampensoft.borderlinefluent.SAY -p com.lampensoft.borderlinefluent --es text "…"`
     */
    private val sayReceiver = if (!debuggable) null else object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { i.getStringExtra("text")?.let(::inject) }
    }.also { ctx.registerReceiver(it, IntentFilter("com.lampensoft.borderlinefluent.SAY"), Context.RECEIVER_EXPORTED) }

    @Synchronized private fun inject(raw: String) {
        if (liveAttempt?.terminal != null || screen != Screen.INTERVIEW) return
        if (raw == "#pass" || raw == "#fail") {
            if (mind.decision != null || !deskSpoken) return dbg("say ignored: mic=$mic decision=${mind.decision} room=${room != null}")
            dbg("say: $raw")
            liveAttempt = liveAttempt?.let { forceAttempt(it, run) }
            forced = if (raw == "#pass") Decision.PASS else Decision.SECONDARY
            // Waiting for an answer: settle now. Otherwise the line or turn under way finishes first ([awaitAnswer]).
            if (mic == Mic.READY || mic == Mic.HEARING) settleForced(run)
            return
        }
        // Waiting for an answer: the officer has finished (READY), or auto mode hears a phrase or holds a fragment.
        if ((mic != Mic.READY && mic != Mic.HEARING) || mind.decision != null || !deskSpoken)
            return dbg("say ignored: mic=$mic decision=${mind.decision} room=${room != null}")
        val text = if (raw == "#hint") hints.firstOrNull()?.target ?: return dbg("say ignored: no hint") else raw
        val myRun = run
        val think = thinkMs()
        silenceJob?.cancel(); listening = false; holding = false; fragment = null
        val old = listenJob?.apply { cancel() }
        mic = Mic.PROCESSING
        dbg("say: $text")
        listenJob = scope.launch(Dispatchers.Default) {
            old?.join()
            if (myRun == run) answer(myRun, FloatArray(max(16000, text.length * 70 * 16)), text, think)
        }
    }

    @Volatile private var forced: Decision? = null // debug `#pass` / `#fail`, applied once the officer waits

    /** Ends the level through the normal decided turn: the desk's last words, or the room's verdict. */
    private fun settleForced(myRun: Int) {
        val d = forced ?: return
        forced = null
        if (myRun != run || mind.decision != null) return
        silenceJob?.cancel(); listening = false; holding = false; fragment = null
        listenJob?.cancel()
        mic = Mic.PROCESSING
        room?.force(d) ?: mind.settle(d)
        scope.launch(Dispatchers.Default) { officerTurn(myRun, "[decision]", answer = false) }
    }

    private fun dbg(s: String) {
        if (diagnostics || debuggable) Log.i(TAG, s) // transcripts: not in a release build's logcat unless opted in
        // Debug builds always keep results.log: logcat overflows with AppOps noise from the camera and mic in minutes.
        if (diagnostics || debuggable) runCatching { File(ctx.filesDir, "results.log").appendText(s + "\n") }
    }

    /** The officer's fixed last words: the room has its own release and detention lines. */
    private fun finalLine(d: Decision): Pair<String, String> {
        val r = ROOM.getValue(destination.lang); val en = ROOM.getValue(sub)
        return when {
            !wasInRoom || d == Decision.BROKEN -> Language.of(destination.lang).lastWords(d, sub)
            d == Decision.PASS -> r.release to en.release
            else -> r.detain to en.detain
        }
    }

    /** The sting in the neck: flash, then black. Leaves [blackout] on; false if a newer run took over. */
    private suspend fun sting(myRun: Int): Boolean {
        narration = str(R.string.narr_sting)
        flash = true; sfx.play(R.raw.sfx_scan); hapticPlayer.play(HapticEvent.TASER); delay(350); flash = false
        if (myRun != run) return false
        blackout = true; delay(900); narration = null
        return myRun == run
    }

    /** Level 2, the T-N test: at the desk, or (story) in an unknown room after the taser. */
    private fun enterRoom(myRun: Int, how: Entry) {
        mind.decision?.let { captureAttemptDecision(myRun, it) }
        val taser = how == Entry.TASER
        // A story desk BROKEN: undo the winding-down voice before the room (Piper follows mind.decision, cleared below).
        if (mind.decision == Decision.BROKEN) tts?.let { Voices.apply(ctx, it, destination.lang, Country.isFemale(officerId), officerId); it.setSpeechRate(1f) }
        inBackRoom = taser
        wasInRoom = true
        silenceJob?.cancel(); listening = false; mic = Mic.OFF; hints = emptyList(); tip = null; forced = null
        val tn = Assessment(mind.suspicion, story, tellWeight = mind.p.tellWeight)
        scope.launch(Dispatchers.Default) { guarded(myRun) {
            // The room speaks fixed translations of the T-N seeds (TN_LOCAL): the officer model drifted back to desk
            // questions when asked to phrase them, and Nano asked to translate them often answered them instead.
            // Then the same into the subtitle language.
            val translated = async(Dispatchers.Default) { translateRoom(tn.seeds, destination.lang) to translateRoom(tn.seeds, sub) }
            var other = false // a new unit: warn again if the language is slow
            if (taser) {
                sfx.play(R.raw.sfx_stamp)
                hapticPlayer.play(if (mind.decision == Decision.BROKEN) HapticEvent.BREAK else HapticEvent.STAMP_PASS)
                narration = str(R.string.narr_stamp)
                delay(3500)
                if (myRun != run) return@guarded // a stale room must not touch the next interview
                if (!sting(myRun)) return@guarded
                other = newOfficer(myRun)
                delay(2200)
                if (myRun != run) return@guarded
                narration = str(R.string.narr_wake)
                delay(3500)
                if (myRun != run) return@guarded
                narration = str(if (other) R.string.narr_other_android else R.string.narr_same_officer)
                delay(3000)
            } else {
                narration = str(R.string.narr_one_more_thing)
                delay(3200)
            }
            if (myRun != run) return@guarded
            blackout = false
            if (other && slowLang(destination.lang)) implantNote(myRun, str(R.string.implant_slow, destination.languageName(uiLanguage(ctx))))
            // The officer's first words point the traveler at the eye machine, which then fills the view.
            narration = null; face = Face.NEUTRAL
            val line = ROOM.getValue(destination.lang).machine
            officerText = line; officerEn = ROOM.getValue(sub).machine; history += "Officer: $line"
            val said = CompletableDeferred<Unit>()
            speak(myRun, line) { said.complete(Unit) }
            withTimeoutOrNull(10_000) { said.await() } // no voice at all: don't hang the room
            if (myRun != run) return@guarded
            implantNote(myRun, str(R.string.room_implant_warning))
            machineView = true
            translated.await().let { (say, subs) -> roomSay = say; roomSub = subs }
            turnLock.withLock {
                if (myRun != run) return@guarded
                roomDesk = Desk()
            }
            room = tn
            mind.enterRoom()
            question = ""; face = Face.NEUTRAL; narration = null
            dbg("room ${room?.state()} | ${room?.questions?.joinToString(" / ")}")
            officerTurn(myRun, when (how) {
                Entry.TASER -> "[The traveler was brought in unconscious and wakes up in the interview room, groggy. You have never met them before.]"
                Entry.AT_DESK -> "[The traveler answered your questions. Now, right here at your desk, you run the mandatory Kade-Sorensen assessment.]"
            }, answer = false)
        } }
    }

    /**
     * The story room's android is not a border officer: one of the two T-N interrogators (base app art, story_m /
     * story_f), with no memory of the desk: new sheet, designation, voice. False if neither sheet ships (then the desk one stays).
     */
    private suspend fun newOfficer(myRun: Int): Boolean {
        val art = ctx.assets.list("art").orEmpty().toSet()
        val id = listOf("story_m", "story_f").filter { "officer_${it}_sheet.webp" in art }.randomOrNull() ?: return false
        // Under turnLock with a run check: a stale room must not swap the officer of a newer interview.
        turnLock.withLock {
            if (myRun != run) return false
            officerId = id; unitName = "Unit K-S"
            val female = Country.isFemale(id)
            tts?.let { Voices.apply(ctx, it, destination.lang, female, id) }
            loadPiper(female) // the room's lines are fixed, so no new officer conversation
        }
        dbg("room officer $id")
        return true
    }

    /**
     * One room turn: read the answer (feeling, evasion, implant talk, tricks; cover questions also through the
     * contradiction check), then the next question as translated in [enterRoom] (no model phrasing), or the verdict.
     * The display is jammed: one scrambled suggestion.
     */
    private suspend fun roomTurn(myRun: Int, a: Assessment, said: String, answer: Boolean) {
        val lines = ROOM.getValue(destination.lang)
        pendingRetina?.await(); pendingRetina = null
        mic = Mic.PROCESSING; hints = emptyList()
        spoken = false; shown = false; clockAt = 0L; lookAsked = false
        var prefix = ""; var prefixEn = ""
        var reaction: RoomCheck? = null
        if (answer && question.isNotEmpty()) {
            val b = brain ?: return
            val lang = scope.async { inLanguage(said, destination.lang, MlLangId) } // beside the Nano call, not after it
            val raw = Nano.oneShot(b)(roomPrompt(destination), "Officer asked: \"$question\"\nTraveler answered: \"$said\"", 40)
            val rc = RoomCheck.parse(raw).let { base -> base.copy(inLanguage = languageVerdict(said, lang.await(), saysLanguageYes(raw), destination.lang)) } // ID handles names; Nano can rescue short answers
            if (myRun != run) return
            reaction = rc
            if (rc.trick) {
                val before = mind.integrity
                mind.onCheck(Check(answered = false, contradiction = false, clarity = 2, inLanguage = true, trick = rc.trick))
                integrityNote(myRun, before)
            } else {
                a.onCheck(rc)
                if (a.coverFact != 0)
                    analyst?.judge(question, said)?.let { a.onContradiction(it.fact, it.check.contradiction) }
            }
            if (rc.implant) {
                prefix = lines.precaution; prefixEn = ROOM.getValue(sub).precaution
                scope.launch { officerGlitch = true; delay(1500); officerGlitch = false }
            }
            a.next()
            dbg("tn $rc -> ${a.state()} ${mind.state()}")
        } else if (!announced) {
            announced = true
            prefix = lines.announce; prefixEn = ROOM.getValue(sub).announce
        }
        if (a.pressed && prefix.isEmpty()) { // the re-ask after an empty answer: "Answer the question." and the same question
            fun press(l: String) = renderLine("dodge.curt.1", l, story.female, story.days, "").orEmpty()
            prefix = press(destination.lang); prefixEn = press(sub)
        }
        face = when { prefix == lines.precaution -> Face.CONFUSED; a.index > 60 -> Face.SUSPICIOUS; a.index > 40 -> Face.CONCERNED; else -> Face.NEUTRAL }
        val d = if (mind.decision == Decision.BROKEN) Decision.BROKEN else a.verdict()
        if (d != null) {
            captureAttemptDecision(myRun, d)
            mind.settle(d)
            if (d == Decision.SECONDARY && storyMission) { dbg("room ending $d ${a.state()} (arrest)"); scope.launch { storyArrest(myRun) }; return }
            val (line, english) = finalLine(d)
            officerText = line; officerEn = english
            if (d == Decision.BROKEN) tts?.run { setPitch(0.55f); setSpeechRate(0.7f) }
            history += "Officer: $line"
            dbg("room ending $d ${a.state()}")
            speak(myRun, line)
            return
        }
        val seed = a.current ?: return
        // From the 2nd question on, react to the check with a rotating fixed statement.
        if (answer && prefix.isEmpty() && question.isNotEmpty()) {
            val rc = checkNotNull(reaction)
            val id = roomDesk.pick(when { rc.evasive -> "room.evasive"; rc.feeling -> "room.feeling"; else -> "room.cold" })
            prefix = renderLine(id, destination.lang, story.female, story.days, "").orEmpty()
            prefixEn = renderLine(id, sub, story.female, story.days, "").orEmpty()
        }
        // A first silence leaves a.asked unchanged, so the same question is repeated; a second moved on (Assessment.onSilence).
        question = roomSay.getOrNull(a.asked) ?: seed
        officerText = listOf(prefix, question).filter { it.isNotEmpty() }.joinToString(if (destination.lang == "ja" || destination.lang == "zh") "" else " ")
        officerEn = listOf(prefixEn, roomSub.getOrNull(a.asked) ?: seed).filter { it.isNotEmpty() }.joinToString(if (sub == "ja" || sub == "zh") "" else " ")
        speak(myRun, officerText)
        history += "Officer: $question"
        dbg("room officer | $officerText")
        val an = analyst ?: return
        pendingRetina = scope.async(Dispatchers.Default) {
            val ep = an.suggest(history)
            if (myRun == run) {
                // The dampener: one suggestion, shuffled and corrupted. Reading it aloud sounds like a broken machine.
                hints = ep.options.take(1).map(Models::option).map { Hint(scramble(it.target), "", corrupt(it.english, 0.5f)) }
                shown = true; startClock(myRun)
            }
            dbg("hints | " + ep.options.map(Models::option).joinToString(" | ") { "${it.target} / ${it.english}" } +
                " | shown: " + hints.joinToString(" | ") { "${it.target} / ${it.english}" })
            ep
        }
    }

    /** Room questions and subtitles use only the same fixed seed table. */
    private fun translateRoom(seeds: List<String>, lang: String): List<String> = seeds.map { seed ->
        requireNotNull(Assessment.local(seed, story, lang)) { "room translation missing $lang: $seed" }
    }.also { dbg("room questions $lang ${it.joinToString(" / ")}") }

    /**
     * Story failure (first playthrough only): no detain line. Another shock, sirens, the arrest scene, then a failed
     * mission; the story stays unfinished, so the next mission is the story again.
     */
    private suspend fun storyArrest(myRun: Int) {
        if (myRun != run) return
        mic = Mic.OFF
        narration = str(R.string.narr_wrong_answers)
        flash = true; sfx.play(R.raw.sfx_scan); hapticPlayer.play(HapticEvent.TASER); delay(350); flash = false
        blackout = true; delay(3000)
        if (myRun != run) return
        narration = str(R.string.narr_sirens); sfx.play(R.raw.sfx_siren); delay(3000)
        if (myRun != run) return
        sceneArt = "story_arrest" // the overlay skips it if the art is missing
        narration = str(R.string.narr_arrest); delay(5000)
        if (myRun != run) return
        captureAttemptDecision(myRun, Decision.SECONDARY)
        saveTerminal(myRun) { finishTerminal(Screen.RESULT) }
    }

    /**
     * After an answer: read against the baseline closed at the end of small talk ([closeBaseline]), and the officer
     * reacts to what deviates. If small talk showed too little face, this answer closes it instead.
     */
    private fun profile(faceRun: Int) {
        val faceEpoch = faceWatch.session.tellEpoch(faceRun)
        if (!profiler.calibrated) {
            val ok = profiler.calibrate(faceWatch.since(interviewAt))
            dbg("baseline ${if (ok) "set" else "too little face"}")
            return
        }
        val window = faceWatch.since(answerAt)
        if (window.isEmpty()) return // no frames at all (camera not running): nothing seen, nothing to judge or reward
        val absent = window.count { !it.face }.toDouble() / window.size
        val t = profiler.tells(window)
        if (absent <= 0.6 && faceEpoch != null) t.take(2).forEach { faceWatch.session.tell(faceRun, it.first, faceWatch.now(), faceEpoch) }
        room?.onTells(t, absent) ?: mind.onTells(t, absent).also { dbg("mind ${mind.delta}") }
        if (absent <= 0.6) t.take(2).forEach { logBehavior(it.first, it.second) } // same cut as onTells
        dbg("tells ${t.joinToString { "${it.first}=%.1f".format(it.second) }} absent=%.2f".format(absent))
    }

    /** The implant watches the last 8 s while the traveler is on and nags about the strongest deviation. */
    private suspend fun coachLoop(faceRun: Int) {
        var shownAt = 0L
        var blinks = faceWatch.blinkCount; var lastTop: Tell? = null; var chirpAt = 0L
        while (true) {
            delay(400)
            // The eye machine beeps on every blink and chirps (at most every 2 s) on a new top tell.
            if (machineView && faceWatch.blinkCount != blinks) sfx.play(R.raw.sfx_blink)
            blinks = faceWatch.blinkCount
            lookCheck()
            val faceEpoch = faceWatch.session.tellEpoch(faceRun)
            val top = if (profiler.calibrated && mind.decision == null)
                profiler.tells(faceWatch.last(8000)).firstOrNull()?.first else null
            if (top != null && faceEpoch != null) faceWatch.session.tell(faceRun, top, faceWatch.now(), faceEpoch)
            if (System.currentTimeMillis() < hintUntil) continue
            if (!profiler.calibrated || mind.decision != null) { implant = null; lastTop = null; continue }
            val now = faceWatch.now()
            if (machineView && top != null && top != lastTop && now - chirpAt > 2000) { sfx.play(R.raw.sfx_tell); chirpAt = now }
            if (top != null && top != lastTop) hapticPlayer.play(HapticEvent.IMPLANT_GLITCH)
            lastTop = top
            if (top != null) { implant = str(top.implant); shownAt = now }
            else if (now - shownAt > 2500) implant = null // keep a warning up long enough to read
        }
    }

    /** The face has been out of view ~3 s while the officer waits for an answer: they ask once per question. */
    private fun lookCheck() {
        if (!watching || lookAsked || mic != Mic.READY || narration != null || mind.decision != null || ending != null) return
        val w = faceWatch.last(3000)
        if (w.size < 5 || w.any { it.face } || faceWatch.now() - w.first().t < 2500) return
        lookAsked = true
        if (heldHandover == null) logBehavior(null, 0.0) // small talk is the baseline: not in the debrief
        val myRun = run
        room.let { if (it != null) it.onLookAway() else if (heldHandover == null) mind.lookedAway() } // small talk: not held against them
        val l = LOOK.getValue(destination.lang)
        dbg("look at me: face away 3 s -> ${room?.state() ?: mind.state()}")
        // Not a new question: back to waiting, without officerFinished's decision handling.
        val oldText = officerText; val oldSub = officerEn
        officerText = if (machineView) l.keepLooking else l.lookAtMe
        val translated = LOOK.getValue(sub)
        officerEn = if (machineView) translated.keepLooking else translated.lookAtMe
        speak(myRun, officerText) {
            if (myRun == run && mind.decision == null && mic == Mic.OFFICER_SPEAKING) {
                officerText = oldText; officerEn = oldSub
                awaitAnswer(myRun)
            } // not under a turn that started meanwhile
        }
    }

    private fun speakTip() = str(if (inputMode == InputMode.HOLD) R.string.tip_speak_hold else R.string.tip_speak_auto)

    companion object {
        const val TAG = "BORDER"
        private const val HANDOVER = "[The traveler hands you the passport."
        // Auto-mode VAD knobs (RMS of float PCM). Tune on device; a real airport is louder than a living room.
        private const val VAD_START = 3.0
        private const val VAD_KEEP = 2.0
        private const val VAD_MIN = 0.008
        private const val VAD_END_MS = 1400 // a pause mid-sentence ("I… am here for work") is not the end
        private const val FOLLOW_MS = 3000L // after a fragment: how long to wait for the rest
        private const val FRAGMENT_MAX_S = 6
        // TTS reports "done" while the phrase's tail is still in the speaker: read and discard the first 150 ms
        // after the mic opens, without feeding the VAD, pre-roll, or heard diagnostics.
        private const val ECHO_SKIP_MS = 150L

        /**
         * The speaker bleeding into the mic: the phrase started within [ECHO_START_MS] of the mic opening and is a
         * contiguous run of at least two words (or one token containing Han, Hiragana, or Katakana) from the
         * officer's last [ECHO_TAIL] words. An answer that reuses the question's words
         * ("Туристична поїздка." to "…туристична чи робоча поїздка?") is not one.
         */
        fun isEcho(heard: String, officer: String, speechAfterMs: Long): Boolean {
            // Words, not a substring: the recording's cut-off tail ("S'") breaks an exact match. One-letter scraps don't count.
            fun words(x: String) = x.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length >= 2 }
            val h = words(heard); val tail = words(officer).takeLast(ECHO_TAIL)
            return speechAfterMs < ECHO_START_MS &&
                (h.size >= 2 || h.size == 1 && h[0].any { Character.UnicodeScript.of(it.code) in CJK }) &&
                java.util.Collections.indexOfSubList(tail, h) >= 0
        }
        private const val ECHO_TAIL = 6
        private val CJK = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
        private const val ECHO_START_MS = 1500L
    }
}

/** The interface language: the per-app locale if set (the application context's configuration may not follow it), else the system's. */
fun uiLanguage(ctx: Context): String =
    ctx.getSystemService(LocaleManager::class.java).applicationLocales.takeIf { !it.isEmpty }?.get(0)?.language
        ?: ctx.resources.configuration.locales[0].language

/** [probe]: the start-up system-ASR probe (lang → has a system recognizer). Unknown or still probing: not slow. */
fun slowLang(probe: Map<String, Boolean?>?, lang: String) = probe?.get(lang) == false
