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
import kotlinx.coroutines.cancelAndJoin
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

internal enum class TranscriptKind { EMPTY, FRAGMENT, ANSWER }
internal fun transcriptKind(text: String): TranscriptKind = when {
    text.isBlank() -> TranscriptKind.EMPTY
    Delivery.fragment(text) -> TranscriptKind.FRAGMENT
    else -> TranscriptKind.ANSWER
}

/** One classified answer, recorded before the assessment advances. Categories may overlap. */
internal data class MachineAnswer(val evasive: Boolean, val odd: Boolean, val feeling: Boolean, val admission: Boolean, val rejected: Boolean = false)
internal data class MachineDebrief(val evasive: Int, val odd: Int, val feeling: Int, val admissions: Int, val doses: Int = 0, val rejected: Int = 0) {
    /** The "samples accepted" (the room's doses) and "samples rejected" (absurd without feeling) lines appear only when there was one. */
    fun text(plural: (Int, Int) -> String): String = (listOf(
        R.plurals.machine_evasive to evasive, R.plurals.machine_odd to odd,
        R.plurals.machine_feeling to feeling, R.plurals.machine_admissions to admissions,
    ) + listOfNotNull((R.plurals.machine_doses to doses).takeIf { doses >= 1 }, (R.plurals.machine_rejects to rejected).takeIf { rejected >= 1 })).joinToString("\n") { (key, count) -> plural(key, count) }
}
internal fun machineAnswer(c: RoomCheck, contradiction: Boolean, admissionQuestion: Boolean, rejected: Boolean = false) = MachineAnswer(
    c.evasive, c.odd || contradiction, c.credited && !contradiction,
    admissionQuestion && !c.trick && c.admit == Admit.YES, rejected,
)
internal fun machineDebrief(record: List<MachineAnswer>, doses: Int = 0) = MachineDebrief(
    record.count { it.evasive }, record.count { it.odd }, record.count { it.feeling }, record.count { it.admission }, doses, record.count { it.rejected },
)
/** Repeat always; the denial (first line) only when the question is an implant probe, where it fits. */
internal fun roomFallback(lang: String, ui: String, probe: Boolean = true): List<Hint> =
    (if (probe) listOf(0, 1) else listOf(1)).map { i ->
        Hint(ROOM_FALLBACK.getValue(lang)[i], if (Language.of(lang).romanize != null) ROOM_FALLBACK_READINGS.getValue(lang)[i] else "",
            (ROOM_FALLBACK[ui] ?: ROOM_FALLBACK.getValue("en"))[i])
    }
internal fun roomFallbackVisible(inRoom: Boolean, jam: Jam?, unaided: Boolean) =
    inRoom && !unaided && jam in setOf(Jam.GARBLE, Jam.DROP)

/** Monotonic interview time: neither thinking nor silence includes time outside the app. */
internal class InterviewClock(private val source: () -> Long = System::nanoTime) {
    private var pausedAt: Long? = null
    private var excluded = 0L
    @Synchronized fun now(): Long = (pausedAt ?: source()) - excluded
    @Synchronized fun pause() { if (pausedAt == null) pausedAt = source() }
    @Synchronized fun resume() { pausedAt?.let { excluded += source() - it }; pausedAt = null }
}

enum class TravelStep { FLIGHT, ARRIVALS }

/** Restored preparation resumes its existing queue/desk path without repeating travel. */
internal fun shouldShowTravel(training: Boolean, firstCareerTraining: Boolean, recovering: Boolean,
    completed: Boolean) = !training && !firstCareerTraining && !recovering && !completed

internal fun travelDurationMillis(step: TravelStep, animate: Boolean): Long =
    if (step == TravelStep.ARRIVALS) 2000L else if (animate) 3000L else 1000L

/** Every results.log write (appends, trim, deletion) runs in order on this one thread, shared by the game and the
 * bench (one process): callers never wait on the disk, and no line is lost to a trim or lands after a clear queued
 * before it. Tests wait on the returned future. */
private val diagWriter = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "diagnostics").apply { isDaemon = true } }
internal fun diagnosticsTask(task: () -> Unit): java.util.concurrent.Future<*> = diagWriter.submit(Runnable { task() })
/** Appends [line] when its turn comes, if [allowed] still holds then (consent can be withdrawn while it waits). */
internal fun appendDiagnostic(dir: File, line: String, allowed: () -> Boolean = { true }) =
    diagnosticsTask { if (allowed()) File(dir, "results.log").appendText(line + "\n") }
/** Recordings are written off the main thread under this lock, re-checking consent inside; [clearDiagnostics] deletes
 * them under it, so none lands after a clear. */
private val diagLock = Any()
/** The game's own recordings in wavs/ (`utterance` and the no-speech report); bench clips share the folder and stay. */
private val ownRecording = Regex("[a-z]+_(rec|nospeech)_\\d+\\.wav")
private fun ownRecordings(dir: File) = File(dir, "wavs").listFiles { f -> ownRecording.matches(f.name) }.orEmpty().toList()

/** "Save recordings and logs" grows without bound: past [logMax] bytes results.log keeps its last [logKeep] (from a
 * line start), and wavs/ keeps the game's newest [wavsKeep] recordings. Queued on the diagnostics writer. */
internal fun trimDiagnostics(dir: File, logMax: Long = 10_000_000, logKeep: Int = 5_000_000, wavsKeep: Int = 200) = diagnosticsTask {
    val log = File(dir, "results.log")
    if (log.length() > logMax) {
        val tail = java.io.RandomAccessFile(log, "r").use { f -> ByteArray(logKeep).also { f.seek(f.length() - logKeep); f.readFully(it) } }
        val from = tail.indexOf('\n'.code.toByte()) + 1
        File(dir, "results.log.tmp").apply { writeBytes(tail.copyOfRange(from, tail.size)) }.renameTo(log)
    }
    ownRecordings(dir).sortedByDescending { it.lastModified() }.drop(wavsKeep).forEach { it.delete() }
}

/** The player turned "Save recordings and logs" off: results.log and the game's own recordings go. Queued on the
 * diagnostics writer, after every line logged before it. */
internal fun clearDiagnostics(dir: File) = diagnosticsTask {
    File(dir, "results.log").delete()
    synchronized(diagLock) { ownRecordings(dir).forEach { it.delete() } }
}

enum class Screen { UNSUPPORTED, INTRO, ACT1_CUTSCENE, TRAINING_SETUP, CAMERA_TEST, FINALE, WHY, VISIT, THALASSIA, LANGUAGES, DOWNLOAD, PASSPORT, INPUT_MODE, START, DESTINATION, COVER, INTERVIEW, RESULT, SUMMARY, SETTINGS, LICENSES, MUSIC }
enum class InputMode { HOLD, AUTO }

/** Legacy setup order for settled careers; [setupForAct1] defers the passport on first run. */
fun nextSetupScreen(hasLanguages: Boolean, downloading: Boolean, passportDone: Boolean, inputDone: Boolean): Screen = when {
    !hasLanguages -> Screen.LANGUAGES
    downloading -> Screen.DOWNLOAD
    !passportDone -> Screen.PASSPORT
    !inputDone -> Screen.INPUT_MODE
    else -> Screen.START
}

/** Stages that cannot run without the passport's identity. */
fun passportBehind(stage: Act1Stage) = stage in setOf(Act1Stage.NEWS, Act1Stage.HOME_TRAINING, Act1Stage.ESCORT, Act1Stage.PLANE, Act1Stage.ARRIVAL)

/** Onboarding defers identity; settled careers retain their existing passport repair gate. */
fun setupForAct1(stage: Act1Stage, hasLanguages: Boolean, downloading: Boolean, hasIdentity: Boolean,
    passportDone: Boolean, inputDone: Boolean, faceDone: Boolean): Screen {
    // A dropped identity (a held-back nationality) behind the passport step sends the player back to roll a new one.
    val setup = nextSetupScreen(hasLanguages, downloading,
        if (stage != Act1Stage.DONE) hasIdentity || !passportBehind(stage) else passportDone && hasIdentity, inputDone)
    if (setup != Screen.START) return setup
    if (!faceDone && !hasIdentity && stage != Act1Stage.DONE) return Screen.INPUT_MODE
    return onboardingScreen(stage)
}

/**
 * Where "Begin mission" goes. Pure (tested): without an identity, the setup's passport step (never the booking); a
 * booked trip resumes at its boarding pass instead of booking again; both acts book the player’s menu selection.
 */
fun missionScreen(hasIdentity: Boolean, setup: Screen, playable: Boolean, booked: Boolean, story: Boolean = false): Screen = when {
    !hasIdentity -> if (setup == Screen.START) Screen.PASSPORT else setup
    !playable -> Screen.LANGUAGES
    else -> Screen.DESTINATION // resume the ticket, or book the menu selection before showing it
}

/** Legacy setup wrapper; act-1 routing uses [setupForAct1]. */
fun beforeStoryScreen(setup: Screen, introSeen: Boolean) = if (setup == Screen.START && !introSeen) Screen.INTRO else setup

/** The passport step is done only with its photo on the phone, not just the pref. Pure (tested). */
fun passportSetupDone(pref: Boolean, photo: Boolean) = pref && photo

/** Result screen: remind to reconnect only after a paranoid-mode mission, and only while the phone is still offline. */
fun remindOffline(playedOffline: Boolean, offlineNow: Boolean) = playedOffline && offlineNow

/** Where the PASSPORT screen returns to ([from]: the screen it was opened from): Settings when opened there, else the setup's next step. */
fun passportExit(from: Screen?, setup: Screen): Screen = if (from == Screen.SETTINGS) Screen.SETTINGS else setup

/** Where Back goes from [screen]; null leaves the app. During setup ([setup] != START) it exits rather than skipping ahead to the story. */
fun backFrom(screen: Screen, setup: Screen, passportFrom: Screen? = null): Screen? = when {
    screen == Screen.ACT1_CUTSCENE || screen == Screen.TRAINING_SETUP ||
        setup == Screen.TRAINING_SETUP && screen in setOf(Screen.COVER, Screen.INTERVIEW, Screen.DESTINATION) -> screen
    screen == Screen.START || screen == Screen.UNSUPPORTED -> null
    screen == Screen.PASSPORT && passportFrom == Screen.SETTINGS -> Screen.SETTINGS
    setup != Screen.START -> null
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

/** [dest] if it still names something playable, else "random" (its language pack is gone, or it is a retired "lang:<code>"). */
fun resolveDest(dest: String, playable: List<Country>): String =
    if (playable.any { it.id == dest }) dest else "random"

/** The country picker's pref `dest`: "random" or a country id. */
fun canonicalDest(saved: String?): String = CountryMigration.countryId(saved ?: "random")

/** Only an explicit change of country (including Anywhere) gives up the existing ticket. */
fun bookingAfterDestPick(booking: Booking?, pick: String): Booking? = booking?.takeIf { it.countryId == pick }

/** A persisted free-play ticket cannot return to the current country either. */
internal fun bookingForRestore(snapshot: CareerSnapshot?): Booking? = snapshot?.booking?.takeUnless {
    it.countryId == snapshot.lastPassedCountry && act1StageOf(snapshot) == Act1Stage.DONE
}

/** Countries matching an ordinary picker choice; unavailable choices retain the existing random fallback. */
fun destPool(dest: String, playable: List<Country>): List<Country> =
    playable.filter { dest == "random" || it.id == dest }.ifEmpty { playable }.distinctBy { it.id }
/**
 * What the languages in [picked] lack for the first story trips: [lang] is the passport's language (home training never
 * borrows a foreign one); null lang means a second country (the act-1 trip never goes home). Null: the story can start.
 * Home unknown (no region, no passport yet): any two countries.
 */
data class LangGap(val lang: String?)
fun languageGap(picked: Set<String>, home: Nationality?): LangGap? {
    home?.nameLang?.let { if (it !in picked) return LangGap(it) }
    val others = Country.all.count { c -> c.id != home?.id && c.airports.any { it.lang in picked } }
    return if (others < (if (home == null) 2 else 1)) LangGap(null) else null
}

/** The picker and booking share eligibility. */
fun missionCountries(snapshot: CareerSnapshot?, playable: List<Country>, identity: Identity, story: Boolean): List<Country> {
    val current = if (story) snapshot?.storyLastCountry ?: snapshot?.lastPassedCountry else snapshot?.lastPassedCountry
    val pool = if (story) StoryCover.countries(playable, identity, current) else availableFlights(snapshot, playable)
    return pool.filter { it.id != current }
}

/** Story desk passes lead to the building; act-two referrals lead to the associate. Training stays at the desk. */
fun entersRoom(d: Decision, wasInRoom: Boolean, storyMission: Boolean = false, training: Boolean = false) =
    !training && !wasInRoom && if (storyMission) d == Decision.PASS else d == Decision.REFERRAL

/** Either completed building-test verdict leads to the reveal. */
fun reachesFinale(d: Decision, storyMission: Boolean, wasInRoom: Boolean) =
    d in setOf(Decision.RELEASED, Decision.REFUSED) && storyMission && wasInRoom

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
    init { Motion.reduce = prefs.getBoolean("reduceMotion", Motion.defaultReduce()) }
    private val scope = viewModelScope
    private fun str(@StringRes id: Int, vararg a: Any) = ctx.getString(id, *a)

    var destination by mutableStateOf(Country.of("de").primary()); private set
    fun difficultyFor(lang: String) = prefs.getInt("difficulty.$lang", 0).coerceIn(0, 3)
    var difficulty by mutableStateOf(difficultyFor(destination.lang)); private set
    // Free AI difficulty follows the installed upgrade; archived choices are retained only for old saves.
    val canChooseDifficulty get() = false
    fun chooseDifficulty(level: Int) {
        if (!canChooseDifficulty || startingAttempt || navigationBlocked || liveAttempt != null) return
        difficulty = level.coerceIn(0, 3)
        prefs.edit { putInt("difficulty.${destination.lang}", difficulty) }
    }
    val country get() = destination.country
    val airport get() = destination.airport
    private val airportRotation = AirportRotation(Random.Default)

    // Settings
    var officerPersonality by mutableStateOf(prefs.getBoolean("officerPersonality", true)); private set
    fun changeOfficerPersonality(on: Boolean) { officerPersonality = on; prefs.edit { putBoolean("officerPersonality", on) } }
    private var temperament = Temperament.GRUFF
    var inputMode by mutableStateOf(runCatching { InputMode.valueOf(prefs.getString("input", null)!!) }.getOrDefault(InputMode.AUTO)); private set
    var haptics by mutableStateOf(prefs.getBoolean("haptics", true)); private set
    /** The main menu's country pick ([canonicalDest]); read it through [destChoice], which drops a stale one. */
    var dest by mutableStateOf(canonicalDest(prefs.getString("dest", null))); private set
    private var destPicked by mutableStateOf(prefs.contains("dest"))
    fun setInput(m: InputMode) { inputMode = m; prefs.edit { putString("input", m.name) } }
    private val hapticPlayer = Haptics(app, { haptics }, ::dbg)
    fun hapticForeground(on: Boolean) = hapticPlayer.foreground(on)
    fun resultHaptic(view: android.view.View, passed: Boolean) = hapticPlayer.result(view, passed)
    fun setHaptic(on: Boolean) { haptics = on; if (!on) hapticPlayer.cancel(); prefs.edit { putBoolean("haptics", on) } }
    fun pickDest(d: String) {
        if (!careerReady || liveAttempt != null) return
        val applyPick = { dest = d; destPicked = true; prefs.edit { putString("dest", d) } }
        val booking = career.snapshot?.booking
        if (bookingAfterDestPick(booking, d) != booking) clearBooking { applyPick() }
        else applyPick()
    }
    var musicOn by mutableStateOf(prefs.getBoolean("music", true)); private set
    fun setMusic(on: Boolean) { musicOn = on; prefs.edit { putBoolean("music", on) }; music.enabled = on; if (on) soundtrack() }

    // Songs heard on this device (res/raw names; survives new careers). Declared before [music]: its track-start hook uses them.
    var heardSongs by mutableStateOf(decodeHeard(prefs.getString("heardSongs", null))); private set
    /** The song whose note shows top-end; [songToken] bumps on every new track so a repeat re-triggers the fade. */
    var nowPlaying by mutableStateOf<Song?>(null); private set
    var songToken by mutableStateOf(0); private set
    /** The song the music player screen has started (null: the normal soundtrack plays). */
    var playerSong by mutableStateOf<Song?>(null); private set
    var playerPaused by mutableStateOf(false); private set
    private fun songStarted(src: Any) {
        val song = SONGS.firstOrNull { if (src is File) it.lang != null && it.key == src.nameWithoutExtension else it.res == src } ?: return
        if (playerSong != null) return // started from the player: already heard, no note
        if (song.key in heardSongs) return // the note marks a newly unlocked song only (owner)
        heardSongs = heardSongs + song.key; prefs.edit { putString("heardSongs", encodeHeard(heardSongs)) }
        nowPlaying = song; songToken++
    }
    /** Music player: a heard song plays (looping off); tapping the playing one pauses or resumes it. */
    fun playSong(song: Song) {
        if (song.key !in heardSongs || !musicOn) return
        if (playerSong?.key == song.key) { playerPaused = !playerPaused; music.hold(playerPaused); return }
        val file = song.lang?.let { Packs.musicFile(ctx, it, song.key) ?: return } // its pack is gone: greyed in the list
        playerSong = song; playerPaused = false
        music.fadeOut()
        if (file != null) music.play(file, loop = false, onEnd = ::songEnded) else music.play(song.res, loop = false, onEnd = ::songEnded)
    }
    private fun songEnded() {
        val next = playerSong?.let { nextHeard(heardSongs, it, Packs.installedLanguages(ctx)) }
        playerSong = null
        if (next != null) playSong(next) else music.stop()
    }

    val music = Music(app, viewModelScope).also { it.enabled = prefs.getBoolean("music", true); it.onTrackStart = { res -> songStarted(res) } }
    private val sfx = Sfx(app).also { it.enabled = prefs.getBoolean("sfx", true); uiSfx = it }
    var sfxOn by mutableStateOf(prefs.getBoolean("sfx", true)); private set
    fun setSfx(on: Boolean) { sfxOn = on; sfx.enabled = on; prefs.edit { putBoolean("sfx", on) } }
    /** One effect from the game's own pool (screens never build a second SoundPool). */
    fun playSfx(res: Int, gain: Float = Sfx.GAIN) = sfx.play(res, gain)
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
            room != null, machineView, narration != null || ending != null))
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
    fun toggleReduceMotion(on: Boolean) { Motion.reduce = on; prefs.edit { putBoolean("reduceMotion", on) } }
    var diagnostics by mutableStateOf(prefs.getBoolean("diagnostics", false)); private set
    fun toggleDiagnostics(on: Boolean) {
        diagnostics = on; prefs.edit { putBoolean("diagnostics", on) }
        if (!on) clearDiagnostics(ctx.filesDir)
    }

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
    @Volatile private var backgrounded = false
    private val interviewClock = InterviewClock()
    @Volatile private var foreground = CompletableDeferred<Unit>().also { it.complete(Unit) }
    private var resumeListening = false
    private var capturing = false
    private var speechSerial = 0L
    private data class Speech(val run: Int, val text: String, val done: () -> Unit)
    private var pendingSpeech: Speech? = null
    private var silenceDue = 0L

    /** Keep the durable attempt and all turn state; only physical capture/playback is interrupted. */
    @Synchronized fun pauseInterview() {
        if (backgrounded) return
        foreground = CompletableDeferred()
        interviewClock.pause()
        backgrounded = true
        composureHold?.suspendHold(); bluffGesture?.pause()
        resumeListening = resumeListening || mic == Mic.READY || capturing
        listening = false; holding = false
        if (capturing) listenJob?.cancel()
        capturing = false
        silenceJob?.cancel()
        speechSerial++ // reject late callbacks from the stopped playback
        officerMouth.stop()
        speakJob?.cancel(); tts?.stop()
        faceWatch.session.stopCamera()
        activeFaceRun = null
        cityPause()
    }

    private suspend fun awaitForeground() { while (backgrounded) foreground.await() }

    private suspend fun interviewDelay(ms: Long) {
        val until = interviewClock.now() + ms * 1_000_000
        do {
            awaitForeground()
            val left = (until - interviewClock.now()) / 1_000_000
            if (left <= 0) return
            delay(minOf(left, 100L))
        } while (true)
    }
    private var terminalContinuation: (() -> Unit)? = null

    /** Resume the live turn, or a committed transition that finished saving while away. */
    @Synchronized fun resumeAfterStop() {
        if (!backgrounded) return
        interviewClock.resume()
        backgrounded = false
        if (screen == Screen.COVER && implantBoot != null) activeFaceRun = implantBoot
        if (screen == Screen.INTERVIEW && !fatherAway && liveAttempt?.terminal == null && liveAttempt != null) {
            activeFaceRun = run; refreshScanner()
            val speech = pendingSpeech
            if (speech != null) speak(speech.run, speech.text, speech.done)
            else if (resumeListening) {
                resumeListening = false
                awaitAnswer(run, resuming = true)
            }
        }
        cityListen()
        foreground.complete(Unit)
        val after = terminalContinuation
        terminalContinuation = null
        after?.invoke()
        soundtrack()
    }

    @Synchronized private fun whenForeground(after: () -> Unit) {
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

    private fun recoverable(a: Attempt, art: Set<String>) = recoverPreparation(a, playable(art), sheets(art), art, languages)
    /** Fresh, unlike the sticky [preparationUnavailable]: a reinstalled pack makes the trip resumable again. */
    private fun resumable() = interruptedAttempt?.let { recoverable(it, Packs.art(ctx).keys) } != null

    /** Restores preparation only. The next Start gets a fresh durable ID, never a live interview. */
    fun continueInterrupted() {
        if (!careerReady) return
        val a = interruptedAttempt ?: return
        val art = Packs.art(ctx).keys
        val recovery = recoverable(a, art)
        // A held-back language's trip is not a missing pack (none can be installed): it reads as unavailable, Begin discards it.
        recoveryPackMissing = a.preparation != null && Language.isShipped(a.language) &&
            (playable(art).none { it.id == a.countryId } || a.officerId !in sheets(art) ||
                Country.find(a.countryId)?.airports?.find { it.id == a.airportId }?.let { it.art !in art || it.lang !in languages } == true)
        if (recovery == null) {
            preparationUnavailable = true
            return
        }
        destination = recovery.destination; story = recovery.cover; officerId = a.officerId
        difficulty = difficultyFor(destination.lang)
        restoredAttempt = a
        training = a.training
        preparationUnavailable = false
        screen = Screen.COVER
        watchEars(destination.lang)
    }

    var training by mutableStateOf(false); private set
    var tip by mutableStateOf<String?>(null); private set // training-mission coaching, shown over the HUD
    var implantBoot by mutableStateOf<Int?>(null); private set
    var implantWakePulse by mutableStateOf(0); private set
    fun confirmImplantResponse(token: Int) {
        if (implantBoot != token || screen != Screen.COVER || queue?.encounter?.id != Encounters.FATHER) return
        implantWakePulse++
        playSfx(R.raw.sfx_blink)
    }
    var fatherQueueCalibrated by mutableStateOf(false); private set
    fun beginFatherQueueBoot() {
        if (screen != Screen.COVER || queue?.encounter?.id != Encounters.FATHER ||
            fatherQueueCalibrated || implantBoot != null) return
        implantBoot = ++run
        activeFaceRun = run
        faceWatch.gestureWindow(false) // an interview cut mid-gesture can leave frames unretained
        dbg("boot: start, camera=${watching}")
    }
    /** Diagnostics for the implant boot overlay (gated like every [dbg] line). */
    internal fun bootLog(s: String) = dbg("boot: $s")
    fun finishImplantBoot(token: Int) {
        if (implantBoot != token || queue?.encounter?.id != Encounters.FATHER) return
        ctx.getSharedPreferences("career", 0).edit { putBoolean("implant_boot_seen", true) }
        fatherQueueCalibrated = true
        implantBoot = null
        stopFaceRun()
    }
    private var thalassiaDesk: ThalassiaDesk? = null
    var fatherAway by mutableStateOf(false); private set
    private var fatherAwayDone: CompletableDeferred<Unit>? = null
    fun finishFatherAway() { fatherAwayDone?.complete(Unit) }
    val thalassianDesk get() = thalassiaDesk != null
    val officerArt get() = if (thalassianDesk) "officer_story_thalassia_sheet" else "officer_${officerId}_sheet"

    private var introTip: String? = null // the first training tip, while it is still the one showing
    var passportGiven by mutableStateOf(false); private set

    private var prologueCue: PrologueCue? = null
    internal fun prologueAudio(frame: Int, turn: Int) {
        if (screen != Screen.INTRO) return
        prologueCue = cueFor(frame, turn)
        if (!backgrounded) music.prologue(checkNotNull(prologueCue))
    }

    /** Screen audio; the prologue retains its current turn cue across backgrounding and mute. */
    fun soundtrack() {
        if (screen != Screen.INTRO) prologueCue = null
        if (backgrounded) return
        // Leaving the music player: the song fades and the screen's own track comes back below.
        if (screen != Screen.MUSIC && playerSong != null) { playerSong = null; playerPaused = false; music.fadeOut() }
        // City encounter over the summary: the terminal ambience under the kiosk; at the bar the owner's "Walking on the
        // Borderline"; in the taxi the radio plays the language pack's taxi song (the ambience when the pack has none).
        city?.let { c ->
            val radio = if (c.kind == CityKind.TAXI) Packs.musicFile(ctx, c.lang, taxiSong(c.lang, c.country)) else null
            when {
                c.kind == CityKind.BAR -> music.play(R.raw.music_bar, loop = true, volume = Music.BAR)
                // The talking first ride starts the song at the top; a repeat ride drops in 15-30 s along (owner).
                radio != null -> music.play(radio, loop = true, volume = Music.TAXI, startMs = if (c.ride) kotlin.random.Random.nextInt(15_000, 30_001) else 0)
                else -> music.play(R.raw.ambience_airport, loop = true)
            }
            return
        }
        when (screen) {
            Screen.INTRO -> {
                if (careerReady && inputDone) {
                    val frame = if (replayingPrologue) 0 else (career.snapshot?.prologueFrame ?: 0).coerceIn(0, 17)
                    music.prologue(prologueCue ?: cueFor(frame, 0))
                } else music.stop()
            }
            // Under the interview: the terminal (HVAC, a distant crowd, a chime).
            Screen.INTERVIEW -> music.play(R.raw.ambience_airport, loop = true)
            Screen.MUSIC -> Unit // the player screen drives the music
            // Owner's track under every Marek scene, from his first line (epilogue frame 3) through the credits.
            Screen.FINALE -> if ((career.snapshot?.epilogueFrame ?: 6) >= 3)
                music.play(R.raw.music_credits, loop = false)
                else music.stop()
            Screen.WHY, Screen.VISIT -> music.play(R.raw.music_credits, loop = true)
            // The owner's tracks: thalassia_dark from the first Thalassia frame, the true-ending credits song from the last tap.
            Screen.THALASSIA -> if (thalassiaWhiteout || thalassiaCredits) music.play(R.raw.music_true_credits, loop = false)
                else music.play(R.raw.music_thalassia, loop = true)
            Screen.RESULT -> music.play(when { resultBroken -> R.raw.music_broken; resultDecision?.cleared == true -> R.raw.music_win; else -> R.raw.music_over },
                loop = false, volume = Music.STING)
            // "Welcome to the Borderline" waits for the main menu after act 1's onboarding (owner): setup is silent and
            // the Thalassia/home airport screens keep the terminal ambience. After act 1 the menu music runs through
            // the plane picker, the cover card and the flight image, and gives way to the airport ambience when the
            // arrivals image shows (owner), staying through the briefing, the queue scene and the interview (travelCompleted
            // covers the gap after the image, when travel is already null). In act 1 the cover
            // screen is always the airport.
            else -> when {
                screen == Screen.COVER &&
                    (act1Stage != Act1Stage.DONE || travel == TravelStep.ARRIVALS || travelCompleted || queue != null) ->
                    music.play(R.raw.ambience_airport, loop = true)
                // The home arrivals hall after the passport (the news screen) and the escort scene: the terminal again (owner).
                screen == Screen.ACT1_CUTSCENE && act1Stage in setOf(Act1Stage.NEWS, Act1Stage.ESCORT) -> music.play(R.raw.ambience_airport, loop = true)
                act1Stage == Act1Stage.DONE -> music.play(R.raw.music_menu, loop = true)
                else -> music.stop()
            }
        }
    }

    // Language packs. The core models are shared by every language; a pack is its art plus an offline system voice.
    // A held-back language a tester installed before (Language.HELD_BACK) is dropped here, so nothing offers, books or
    // re-installs it; the pref keeps it until the next install writes the set.
    var languages by mutableStateOf(prefs.getStringSet("languages", emptySet())!!.filter(Language::isShipped).toSet()); private set
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
    // Keep initialization dependencies above [screen]: install() invokes its setter during init.
    /** The career's act (PRD §3a); the `finale` pref only places careers saved before acts ([actOf]). */
    val act: Act get() = actOf(career.snapshot?.act, prefs.getBoolean("finale", false))
    /** Story missions: act 1, and its replays after "Leave". */
    val storyAct get() = act != Act.FREE
    /** Null if this phone can run the game, else the reason: the device check now, the Nano probe once it answers. */
    var supported by mutableStateOf(Device.check(app)); private set
    private var shownScreen by mutableStateOf(if (supported != null) Screen.UNSUPPORTED else Screen.INTRO)
    /** The queue scene (PRD §6b), shown over COVER after READY through the outcome and desk handoff. */
    var queue by mutableStateOf<QueueScene?>(null); private set
    var travel by mutableStateOf<TravelStep?>(null); private set
    private var travelCompleted = false

    fun advanceTravel(expected: TravelStep) {
        if (screen != Screen.COVER || travel != expected || navigationBlocked) return
        when (expected) {
            TravelStep.FLIGHT -> travel = TravelStep.ARRIVALS
            TravelStep.ARRIVALS -> {
                travel = null
                travelCompleted = true
                val n = newsDue
                if (n != null) newsPending = n else startInterview()
            }
        }
    }

    fun leaveTravel() {
        if (navigationBlocked) return
        travel = null
        travelCompleted = false
    }
    // Declared before `screen`: its setter clears it, and init runs the setter (a later declaration is still null then).
    var newsPending by mutableStateOf<NewsCaption?>(null); private set
    /** The home vignette on screen ([HOME_VIGNETTES] index) over the summary; in memory only. Declared with the screen state, before init. */
    var homeVignette by mutableStateOf<Int?>(null); private set
    var screen: Screen
        get() = shownScreen
        set(s) {
            if (navigationBlocked) return
            val target = if (s == Screen.START && act1Stage != Act1Stage.DONE) nextSetupScreen() else s
            if (target != Screen.COVER) { queue = null; travel = null; implantBoot = null; newsPending = null }
            if (target == Screen.PASSPORT && shownScreen != Screen.PASSPORT) passportFrom = shownScreen
            if (target != Screen.INTERVIEW) stopFaceRun()
            if (target == Screen.COVER) difficulty = difficultyFor(destination.lang)
            if (target == Screen.LANGUAGES && shownScreen != Screen.LANGUAGES) languagesFrom = shownScreen
            shownScreen = target
        }
    /** The screen LANGUAGES was opened from: after the visit scene its arrow goes to the menu like system Back. */
    var languagesFrom by mutableStateOf<Screen?>(null); private set
    /** Why a missing pack sent the player back to the language screen (shown there); never a silent bounce. */
    var languagesGap by mutableStateOf<LangGap?>(null); private set
    fun clearLanguagesGap() { languagesGap = null }
    private fun needLanguages() {
        val gap = languageGap(languages, (identity?.nationality ?: Nationality.fromLocale(java.util.Locale.getDefault())))
        // No gap: the prefs hold the languages but Play evicted their packs (or the startup install failed): download them again.
        val missing = languages - Packs.installedLanguages(ctx)
        if (gap == null && missing.isNotEmpty()) { install(missing); return }
        languagesGap = gap ?: LangGap(null)
        screen = Screen.LANGUAGES
    }
    /** The screen PASSPORT was opened from: only SETTINGS gets the Back arrow and returns there ([passportExit]). */
    var passportFrom by mutableStateOf<Screen?>(null); private set
    /** The passport step (own photo or a sample face, and the face-reading choice) is done; existing installs see it once. */
    // A restored or transferred install keeps the pref but not the photo (noBackupFilesDir): ask again. A sample face
    // is saved as the photo too, so every finished setup has one; deleting it means the next launch asks again.
    var passportDone by mutableStateOf(passportSetupDone(prefs.getBoolean("setup_passport_done", false), PassportPhoto.exists(ctx))); private set
    /** The input step is done once the player chose (here or, on installs older than the step, in Settings). */
    var inputDone by mutableStateOf(prefs.contains("input")); private set
    private var faceConsentDone by mutableStateOf(prefs.getBoolean("setup_face_done", false) || prefs.getBoolean("setup_passport_done", false))
    /** The player's passport identity (career save); the passport step isn't done without one. */
    val identity get() = career.snapshot?.identity
    var replayingPrologue by mutableStateOf(false); private set
    fun replayPrologue() {
        if (!careerReady || screen != Screen.SETTINGS) return
        replayingPrologue = true; screen = Screen.INTRO
    }
    val act1Stage get() = act1StageOf(career.snapshot, legacyFinale = prefs.getBoolean("finale", false))

    // Story texts: read ids live in the "career" prefs, so a progress reset clears them.
    var readMessages by mutableStateOf(app.getSharedPreferences("career", 0).getStringSet("messages_read", emptySet())!!.toSet()); private set
    /** The incoming-message banner shows once per launch. */
    var messageBannerShown by mutableStateOf(false)
    /** Arrival time per id (epoch ms), first seen as delivered; stored in the "career" prefs as "msg_at_<id>". */
    var messageTimes by mutableStateOf(app.getSharedPreferences("career", 0).all.mapNotNull { (k, v) ->
        if (k.startsWith("msg_at_") && v is Long) k.removePrefix("msg_at_") to v else null }.toMap()); private set
    val messages get() = deliveredMessages(career.snapshot, act1Stage).map { it.copy(at = messageTimes[it.id]) }
        .sortedBy { it.at ?: Long.MAX_VALUE }
    /** Stamps newly delivered messages once; never changes a stored time. */
    fun stampMessages() {
        val new = messages.filter { it.at == null }.map { it.id }
        if (new.isEmpty()) return
        val now = System.currentTimeMillis()
        messageTimes = messageTimes + new.associateWith { now }
        messageBannerShown = false // a newly delivered message gets its banner even after another one showed this launch
        ctx.getSharedPreferences("career", 0).edit { new.forEach { putLong("msg_at_$it", now) } }
    }
    val unread get() = unreadMessages(messages, readMessages)
    fun markMessagesRead(ids: Collection<String>) {
        readMessages = readMessages + ids
        ctx.getSharedPreferences("career", 0).edit { putStringSet("messages_read", readMessages) }
    }
    val departureTraining get() = act1Stage in setOf(Act1Stage.TRAINING, Act1Stage.DEPARTURE)
    val homeArrivalTraining get() = act1Stage == Act1Stage.HOME_TRAINING
    val firstCareerTraining get() = departureTraining || homeArrivalTraining
    fun nextSetupScreen(): Screen = setupForAct1(act1Stage, languages.isNotEmpty(), installing,
        identity != null, passportDone, inputDone, faceConsentDone)
    fun advancePrologue(frame: Int) {
        if (!careerReady || act1Stage != Act1Stage.PROLOGUE || frame !in 1..17) return
        saveCareer({ career.store.setAct1Stage(Act1Stage.PROLOGUE, frame) }) {}
    }
    fun advanceAct1Cutscene() {
        if (!careerReady) return
        val next = when (act1Stage) {
            Act1Stage.FLIGHT -> afterAct1Flight(career.snapshot)
            Act1Stage.NEWS -> Act1Stage.HOME_TRAINING
            Act1Stage.PLANE -> Act1Stage.ARRIVAL
            Act1Stage.ARRIVAL, Act1Stage.ESCORT -> Act1Stage.DONE
            else -> return
        }
        saveCareer({ career.store.setAct1Stage(next) }) {
            training = false
            screen = nextSetupScreen()
        }
    }
    /** Restart interrupted training from its durable preparation, never from the menu. */
    fun resumeFirstTraining() {
        if (!careerReady || navigationBlocked || !firstCareerTraining) return
        training = true
        val stuck = interruptedAttempt
        if (stuck != null && heldBackAttempt(stuck)) {
            // Nothing can recover a held-back language's trip: drop it and restart the step (or the passport, see setupForAct1).
            saveCareer({ career.store.discardPreparation("held-back") }) {
                restoredAttempt = null
                screen = nextSetupScreen()
                resumeFirstTraining()
            }
        } else if (stuck != null) continueInterrupted()
        else if (booked) {
            openBooking()
            if (screen == Screen.DESTINATION) screen = Screen.COVER
        } else if (departureTraining || identity != null) prepareMission(identity) { screen = Screen.COVER }
    }
    /** Saves a (re)rolled identity, then [after]. A failed write shows the career blocker like any other save. */
    fun saveIdentity(id: Identity, after: () -> Unit) = saveCareer({ career.store.setIdentity(id) }) { after() }
    /** An arrow or menu button: [to], gated by the setup like system Back ([arrowTo]). */
    fun go(to: Screen) { if (screen == Screen.SUMMARY && (openCity(false) { go(to) } || openHome { go(to) })) return; screen = arrowTo(to, screen, nextSetupScreen(), passportFrom) }
    /** Waits for [StoryReset] and the career to open: before that the setup would show pre-reset screens. */
    fun afterIntro() {
        if (careerState == null) { introPending = true; return }
        prefs.edit { putBoolean("prologueSeen", true) }
        if (replayingPrologue) { replayingPrologue = false; screen = Screen.START; return }
        if (act1Stage == Act1Stage.PROLOGUE) {
            saveCareer({ career.store.setAct1Stage(Act1Stage.DEPARTURE) }) {
                training = true
                screen = nextSetupScreen()
            }
        } else screen = restoreEnding() ?: nextSetupScreen()
    }
    /**
     * A decided attempt whose ending never showed (the app died during the 2.8 s beat after its receipt committed):
     * its RESULT or FINALE now, from the receipt ([restored]); marked shown so it opens once.
     */
    private fun restoreEnding(): Screen? {
        if (act1Stage != Act1Stage.DONE) return null
        career.snapshot?.epilogueFrame?.let { return Screen.FINALE }
        val r = career.snapshot?.receipt
        val to = pendingEnding(r) ?: return null
        restored = r
        if (!usaCompletionPending(career.snapshot))
            saveCareer({ career.store.endingShown(checkNotNull(r).id) }) { screen = to }
        return to
    }
    private fun endingShown(id: Long) = saveCareer({ career.store.endingShown(id) }) {}
    /** The receipt a restart's RESULT shows ([restoreEnding]); null for a live result. */
    private var restored by mutableStateOf<Receipt?>(null)
    val restoredResult get() = restored != null
    private val restoredEnding get() = restored?.ending
    /** The result screen's facts: the live interview's, or the restored receipt's. */
    val resultDecision get() = restored?.let { r -> r.ending?.decision(r.outcome) } ?: mind.decision
    val resultBroken get() = restoredEnding?.broken ?: mind.crashed
    val resultInRoom get() = restoredEnding?.room ?: wasInRoom
    val resultKind get() = restoredEnding?.let { if (it.home) TripKind.HOMECOMING else TripKind.ARRIVAL } ?: story.kind
    val resultLang get() = restoredEnding?.language ?: destination.lang
    private var introPending = false
    /** PASSPORT finished: from first-run setup on to the next step, from Settings back there. Own photo: [PassportPhoto] only. */
    fun passportFinished() {
        passportDone = true
        prefs.edit { putBoolean("setup_passport_done", true) }
        if (act1Stage == Act1Stage.PASSPORT && passportFrom != Screen.SETTINGS) {
            saveCareer({ career.store.setAct1Stage(Act1Stage.NEWS) }) { screen = nextSetupScreen() }
        } else screen = passportExit(passportFrom, nextSetupScreen())
    }
    /** Re-read what [StoryReset] cleared; a player already past the intro goes back to the passport setup. */
    private fun afterStoryReset() {
        readMessages = emptySet(); messageTimes = emptyMap()
        passportDone = passportSetupDone(prefs.getBoolean("setup_passport_done", false), PassportPhoto.exists(ctx))
        dest = canonicalDest(prefs.getString("dest", null))
        destPicked = prefs.contains("dest")
        difficulty = difficultyFor(destination.lang)
        if (shownScreen != Screen.INTRO && shownScreen != Screen.UNSUPPORTED) screen = nextSetupScreen()
    }
    /** One setup confirmation saves both choices and completion markers in the same preference edit. */
    fun setupFinished(m: InputMode) {
        prefs.edit {
            putString("input", m.name)
            putBoolean("camera", cameraOn)
            putBoolean("setup_face_done", true)
        }
        inputMode = m
        inputDone = true
        faceConsentDone = true
        screen = nextSetupScreen()
    }

    init {
        scope.launch {
            // Before the career opens: a v1 (or older) save starts the story over, as on a fresh install.
            val reset = withContext(Dispatchers.IO) {
                val careerPrefs = app.getSharedPreferences("career", 0)
                StoryReset.runIfNeeded(careerPrefs, prefs).let {
                    if (it == StoryReset.Result.FAILED) StoryReset.runIfNeeded(careerPrefs, prefs) else it // one retry
                }
            }
            if (reset == StoryReset.Result.DONE) afterStoryReset()
            // A reset that still failed never opens the old career as if reset: the read-error state, as for damage.
            completeCareerSave(if (reset == StoryReset.Result.FAILED) CareerSave.Unreadable(CareerRead.Unreadable(null))
                else career.store.open())
            training = firstCareerTraining
            if (introPending) { introPending = false; afterIntro() }
            else if (shownScreen != Screen.UNSUPPORTED) screen = restoreEnding() ?: nextSetupScreen()
        }
        // Chosen languages whose packs aren't on the phone (first launch after an update, or Play evicted them).
        val missing = languages - Packs.installedLanguages(ctx)
        if (supported == null && missing.isNotEmpty()) install(missing)
    }
    var ready by mutableStateOf(false); private set
    /** Can the player's answers be transcribed in [country]'s language? Null while checking. */
    var earsReady by mutableStateOf<Boolean?>(null); private set
    /** No system recognizer for the language at all: only the Whisper download can help. */
    var earsNeedWhisper by mutableStateOf(false); private set
    /** The speech check kept throwing (~30 s): the cover says so and offers Whisper ([earsNeedWhisper] is set too). */
    var earsStuck by mutableStateOf(false); private set
    var micDenied by mutableStateOf(false)
    private var earsJob: Job? = null
    var loadError by mutableStateOf<String?>(null); private set
    var story by mutableStateOf(Backstory.BENCH); private set
    var officerId by mutableStateOf("de_f1"); private set
    var face by mutableStateOf(Face.NEUTRAL); private set
    var officerText by mutableStateOf(""); private set
    var officerEn by mutableStateOf(""); private set
    private var hintList by mutableStateOf(emptyList<Hint>())
    /** The display finished with nothing to show (every option rejected): "No suggestion", not "Translating…". */
    var hintsNone by mutableStateOf(false); private set
    var hints: List<Hint> get() = hintList; private set(v) { hintList = v; hintsNone = false }
    var heard by mutableStateOf(""); private set
    /** The phone translator's switch (plan 2026-10-16-translator): on, the mic hears the player's own language ([translated]). */
    var translatorOn by mutableStateOf(false)
    /** A recognizer for the player's own language is there (checked at the officer's offer); without one the switch never shows. */
    var translatorEars by mutableStateOf(false); private set
    /** What the player said in their own language, shown above its translation ([heard]). */
    var translatorSource by mutableStateOf(""); private set
    /** The switch by the mic: offered at this desk, with ears, while the desk is still deciding. Credits: [OfficerMind.translatorCredits]. */
    val translatorShown get() = translatorEars && mind.translatorOffered && !mind.inRoom && mind.decision == null
    private var micState by mutableStateOf(Mic.OFF)
    /** Every change is logged: the owner saw blue/grey/blue flicker in auto mode (2026-10-12). */
    var mic: Mic get() = micState; private set(v) { if (v != micState) { dbg("mic $micState->$v"); micState = v } }
    private var scannerEnding by mutableStateOf<Decision?>(null)
    var ending: Decision?
        get() = scannerEnding
        private set(value) { scannerEnding = value; refreshScanner() } // the beat between the last words and the result
    /** The Kade-Sorensen test in the story building or at an act-two referral; null at the desk. */
    private var scannerRoom by mutableStateOf<Assessment?>(null)
    var room: Assessment?
        get() = scannerRoom
        private set(value) { scannerRoom = value; refreshScanner() }
    var wasInRoom by mutableStateOf(false); private set
    /** The USA exam swapped in the newer prototype ([Assessment.prototype]): the room shows its art. */
    var prototype by mutableStateOf(false); private set
    /** Act 2's desk PASS: the result says it earned nothing ([tooCleanPass]). */
    val tooClean get() = tooCleanPass(act, storyMission, training, resultInRoom, resultDecision)
    /** A refusal after a contradiction: what the officer heard and the cover fact it was checked against. */
    fun slipLines(): List<String> {
        if (mind.decision != Decision.REFUSED || resultInRoom) return emptyList()
        val (heard, fact) = mind.slip ?: return emptyList()
        val short = heard.trim().let { if (it.length > 80) it.take(79) + "…" else it }
        return listOfNotNull(str(R.string.slip_heard, short),
            fact?.takeIf { it in setOf(FactId.FROM, FactId.PURPOSE, FactId.DAYS, FactId.STAY, FactId.HOST) }?.let { str(R.string.slip_cover, recallFact(it)) })
    }
    private var scannerNarration by mutableStateOf<String?>(null)
    var narration: String?
        get() = scannerNarration
        private set(value) { scannerNarration = value; refreshScanner() } // a scene beat over the dimmed screen
    var officerGlitch by mutableStateOf(false); private set // the portrait stutters (the precautions line)
    private var announced = false
    /** Training, one desk trip, implant overload, then the building test and reveal. */
    var storyMission by mutableStateOf(false); private set
    var contract by mutableStateOf<Contract?>(null); private set
    val coverContract get() = if (restoredAttempt != null) Contract.fromId(restoredAttempt?.contract) else contract
    val liveContract get() = Contract.fromId(liveAttempt?.context?.contract)
    val contractsUnlocked get() = contractsUnlocked(act == Act.FREE, training)
    val canChooseContract get() = screen == Screen.COVER && contractsUnlocked && restoredAttempt == null &&
        !startingAttempt && !navigationBlocked && liveAttempt == null
    fun chooseContract(c: Contract?) { if (canChooseContract) contract = c }
    /** The android's designation for the HUD: country code, sheet, firmware (newer = stricter, see Persona). */
    var unitName by mutableStateOf(""); private set
    /** The desk officer's designation, kept when the associate takes over: the result's desk demeanor names it. */
    var deskUnitName = ""; private set
    /** The perks frozen in this attempt, independent of later passport/settings edits. */
    var implantPerks = ImplantPerks()
    /** This associate turn's jam ([Jam]); null at the desk (desk officers never jam) and on a clean turn. */
    var jam by mutableStateOf<Jam?>(null); private set
    private var machineAnswers = emptyList<MachineAnswer>()
    internal var machineResult by mutableStateOf<MachineDebrief?>(null); private set
    /** This act-2 room's payload motif ([Poison.payload]), shown on the retinal display; null at the desk and in the story. */
    var payload by mutableStateOf<Motif?>(null); private set
    /** The room released a session with a dose naming [payload]: it is planted ([reduceReplayResult] does the same), for the debrief. */
    var payloadPlanted by mutableStateOf(false); private set
    /** The run and the desk's current and previous questions with their cover facts: a late reply may answer the previous one. */
    private var askedNow: Pair<Int, Pair<String, FactId>?> = 0 to null
    private var askedBefore: Pair<Int, Pair<String, FactId>?> = 0 to null
    internal val fallbackHints get() = if (roomFallbackVisible(room != null, jam, liveContract == Contract.UNAIDED))
        roomFallback(destination.lang, uiLanguage(ctx), room?.probing == true) else emptyList()
    internal val buildingResult get() = storyMission && resultInRoom && machineResult != null
    /** Act 2's "why" scene plays after a release from a machine session (restored results and the USA final test skip it). */
    private val whyDue get() = !restoredResult && resultInRoom && machineResult != null && whySceneDue(career.snapshot, act,
        resultDecision, assessmentOffset(act, country.id, storyAct, training) > 0, training, storyMission)
    fun finishWhyScene() {
        if (screen != Screen.WHY || !careerReady) return
        saveCareer({ career.store.whySceneSeen() }) { screen = Screen.SUMMARY }
    }
    /** Marek's second visit ([visitSceneDue]); the installed packs decide which ending it has. */
    private val visitDue get() = !restoredResult && resultInRoom && machineResult != null && visitSceneDue(career.snapshot, act,
        resultDecision, assessmentOffset(act, country.id, storyAct, training) > 0, training, storyMission, languages)
    val visitCount get() = languages.size
    internal val visitScript get() = visitScript(languages.size >= Language.shipped.size)
    /** Saves the visit as seen; "Show me" ([openLanguages]) goes to the language download screen, whose Back is the menu. */
    fun finishVisitScene(openLanguages: Boolean) {
        if (screen != Screen.VISIT || !careerReady) return
        saveCareer({ career.store.visitSceneSeen() }) { screen = if (openLanguages) Screen.LANGUAGES else Screen.SUMMARY }
    }
    /** The return to Thalassia after the USA ending ([thalassiaTripOffered]): offered on the menu until its credits end; replayable from Settings after. */
    val thalassiaOffered get() = thalassiaTripOffered(career.snapshot, act1Stage, readMessages)
    val newsDue get() = dueNews(career.snapshot, act, training || firstCareerTraining)
    /** The news caption plays in the arrivals hall, after landing and before the queue/desk; session-only, so a relaunch cannot strand on it. */
    fun finishNews() {
        val n = newsPending ?: return
        if (!careerReady) return
        saveCareer({ career.store.newsSeen(n.id) }) { newsPending = null; startInterview() }
    }
    /** The booking screen showed a Solvane reservation: after the Free AI reveal this delivers Marek's message once. */
    fun noteSolvane() {
        if (!careerReady || !freeAiNamed(career.snapshot, act) || career.snapshot?.solvaneSeen == true) return
        saveCareer({ career.store.solvaneSeen() }) { }
    }
    val thalassiaSeen get() = career.snapshot?.thalassiaSeen == true
    var thalassiaCredits by mutableStateOf(false); private set
    /** The last tap before the credits: the frame fades through a red-amber glow and the credits song starts. */
    var thalassiaWhiteout by mutableStateOf(false); private set
    /** Opened from Settings after it was seen: Back leaves it, nothing is saved. */
    var thalassiaReplay by mutableStateOf(false); private set
    fun leaveThalassiaReplay() {
        if (screen != Screen.THALASSIA || !thalassiaReplay) return
        thalassiaReplay = false; thalassiaCredits = false; thalassiaWhiteout = false; screen = Screen.SETTINGS; soundtrack()
    }
    fun openThalassia() {
        if (!careerReady || navigationBlocked || liveAttempt != null) return
        if (!(screen == Screen.START && thalassiaOffered || screen == Screen.SETTINGS && thalassiaSeen)) return
        thalassiaReplay = thalassiaSeen; thalassiaCredits = false; thalassiaWhiteout = false; screen = Screen.THALASSIA
    }
    /** The raid frame: the door breaks, the music cuts, 3 s of silence, then it fades back from where it stopped. */
    fun doorBreak() {
        if (screen != Screen.THALASSIA || thalassiaWhiteout || thalassiaCredits) return
        music.cut(); playSfx(R.raw.sfx_door_break); music.fadeIn(2000, silence = 3000)
    }
    fun thalassiaToWhiteout() {
        if (screen != Screen.THALASSIA) return
        thalassiaWhiteout = true; soundtrack()
    }
    fun thalassiaToCredits() {
        if (screen != Screen.THALASSIA) return
        thalassiaCredits = true; soundtrack()
    }
    /** Seen only once the credits end: the app dying anywhere before that offers the trip again. */
    fun finishThalassia() {
        if (screen != Screen.THALASSIA || !careerReady) return
        if (thalassiaReplay) { leaveThalassiaReplay(); return }
        saveCareer({ career.store.thalassiaSeen() }) { thalassiaCredits = false; thalassiaWhiteout = false; screen = Screen.START }
    }
    fun continueMachineResult() {
        if (screen == Screen.RESULT && openCity(true) { continueMachineResult() }) return // the taxi: out of the airport, before the summary
        screen = if (buildingResult) Screen.FINALE else if (whyDue) Screen.WHY else if (visitDue) Screen.VISIT else Screen.SUMMARY
        if (buildingResult) career.snapshot?.receipt?.let { endingShown(it.id) }
    }

    // ---- City encounters (CityEncounters.kt): in memory only, never saved, never points or progress ----
    /** The cleared trip's attempt id when [cityEncounterDue]; set at every terminal save, shown on leaving the pass result screen (a taxi) or the summary (a bar or hotel). */
    private var cityDue: Long? = null
    /** [cityDue] is the career's first trip to that country: a talking taxi ([cityPick]). */
    private var cityFirst = false
    /** The attempt id of a cleared, eligible repeat visit ([cityRideEligible]): a silent taxi ride after the result screen. */
    private var cityRide: Long? = null
    var city by mutableStateOf<CityScene?>(null); private set
    private var cityThen: (() -> Unit)? = null
    private var cityJob: Job? = null
    @Volatile private var cityHolding = false // the mic is open: held (hold mode) or listening by itself (auto mode)
    private var cityIntroSkip = CompletableDeferred<Unit>()
    /** The hotel kiosk didn't like the answer: the next desk starts slightly more suspicious (read and cleared there). */
    var cityWary = false

    /** Opens the due encounter when it is a taxi ([taxi]: from the pass result screen; a repeat visit rides) or not (from the summary); [then] runs when it ends. False: nothing due here. */
    private fun openCity(taxi: Boolean, then: () -> Unit): Boolean {
        if (taxi && (!cityFirst || destination.country.id in SILENT_CABS)) return openRide(then)
        val id = cityDue ?: return false
        val shown = prefs.getInt("cityShown", 0)
        val (kind, next) = cityPick(cityFirst, shown, destination.country.id)
        if ((kind == CityKind.TAXI) != taxi) return false
        cityDue = null
        if (secretEnding || !careerReady) return false
        prefs.edit { putInt("cityShown", next) }
        val c = CityScene(kind, destination.lang, intro = shown, country = destination.country.id, airport = destination.airport.id)
        val line = CITY_LINES[c.lang]?.get(c.kind) ?: return false
        city = c; cityThen = then
        cityIntroSkip = CompletableDeferred()
        soundtrack()
        dbg("city open ${c.kind} attempt=$id | $line")
        cityJob = scope.launch {
            kotlinx.coroutines.withTimeoutOrNull(CITY_INTRO_MS) { cityIntroSkip.await() }
            if (city !== c) return@launch
            val speaking = c.copy(step = CityStep.SPEAKING)
            city = speaking
            speakCity(line)
            if (city === speaking) city = speaking.copy(step = CityStep.READY)
            cityListen()
        }
        return true
    }

    /** A repeat visit's taxi: caption on black, then the cab drives until the player walks out ([leaveCity], also Back), then [then]. No question, no mic, no timer. */
    private fun openRide(then: () -> Unit): Boolean {
        val id = cityRide ?: return false
        cityRide = null
        if (secretEnding || !careerReady) return false
        val shown = prefs.getInt("rideShown", 0)
        prefs.edit { putInt("rideShown", shown + 1) }
        val c = CityScene(CityKind.TAXI, destination.lang, intro = shown, country = destination.country.id, airport = destination.airport.id, ride = true)
        city = c; cityThen = then
        cityIntroSkip = CompletableDeferred()
        soundtrack()
        dbg("city ride attempt=$id")
        cityJob = scope.launch {
            kotlinx.coroutines.withTimeoutOrNull(CITY_INTRO_MS) { cityIntroSkip.await() }
            if (city !== c) return@launch
            city = c.copy(step = CityStep.DONE)
        }
        return true
    }

    /** A tap during the narration caption: on to the scene now. */
    fun skipCityIntro() { if (city?.step == CityStep.INTRO) cityIntroSkip.complete(Unit) }

    /** The machine's line through the officer's voice (Piper, else the system voice); returns when it is done. */
    private suspend fun speakCity(line: String) {
        val done = CompletableDeferred<Unit>()
        val t = tts
        if (piper != null) speakJob = piperSay({ piper }, line, 1f, officerRobot(), piperPitch) { done.complete(Unit) }
        else if (t != null) {
            t.setSpeechRate(1f)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { done.complete(Unit) }
                @Deprecated("") override fun onError(id: String?) { done.complete(Unit) }
            })
            if (t.speak(line, TextToSpeech.QUEUE_FLUSH, null, "city") == TextToSpeech.ERROR) done.complete(Unit)
        } else done.complete(Unit)
        kotlinx.coroutines.withTimeoutOrNull(15_000) { done.await() }
    }

    /** Hold mode: hold to speak, one answer, judged only for adequacy (the small-talk ODD read). */
    fun cityPress() {
        val c = city ?: return
        if (c.step != CityStep.READY || backgrounded || inputMode != InputMode.HOLD) return
        cityHolding = true
        city = c.copy(step = CityStep.HEARING)
        cityJob = scope.launch(Dispatchers.Default) {
            val pcm = vadLock.withLock { record { cityHolding } }
            cityHeard(c, pcm)
        }
    }
    fun cityRelease() { cityHolding = false }

    /** Auto mode: the mic opens by itself once the machine has spoken, with the desk's VAD ([listenAuto]). */
    @Synchronized private fun cityListen() {
        val c = city?.takeIf { it.step == CityStep.READY } ?: return
        if (inputMode != InputMode.AUTO || backgrounded || cityHolding) return
        cityHolding = true
        cityJob = scope.launch(Dispatchers.Default) {
            val captureContext = currentCoroutineContext()
            val pcm = recordVad(0L, skipMs = ECHO_SKIP_MS, onOpen = {}, onSpeech = {
                synchronized(this@Game) { captureContext.ensureActive(); if (city === c) city = c.copy(step = CityStep.HEARING) }
            }, on = { cityHolding })
            synchronized(this@Game) { captureContext.ensureActive(); cityHolding = false }
            cityHeard(c, pcm)
        }
    }

    /** Backgrounded: the open mic stops, as at the desk; [cityListen] opens it again on resume. */
    private fun cityPause() {
        if (!cityHolding) return
        cityHolding = false
        cityJob?.cancel()
        city?.takeIf { it.step == CityStep.HEARING }?.let { city = it.copy(step = CityStep.READY) }
    }

    private suspend fun cityHeard(c: CityScene, pcm: FloatArray?) {
        currentCoroutineContext().ensureActive() // stopped by cityPause or Skip
        // Too short or nothing said: auto mode listens again (null: the mic is denied or busy, as at the desk).
        val again = { if (city?.kind == c.kind) { city = c.copy(step = CityStep.READY); if (pcm != null) cityListen() } }
        if (pcm == null || pcm.size < 8000) return again()
        city = c.copy(step = CityStep.CHECKING)
        if (diagnostics) runCatching { synchronized(diagLock) { if (diagnostics) Models.writeWav(File(File(ctx.filesDir, "wavs").apply { mkdirs() }, "${c.lang}_city_${System.currentTimeMillis()}.wav"), pcm) } }
        val text = runCatching {
            asr?.takeIf { it.config.modelConfig.whisper.language == c.lang }?.let { Models.transcribe(it, pcm).text }
                ?: Nano.transcribe(c.lang, pcm, ::dbg, advanced = false)?.first
        }.getOrNull().orEmpty()
        currentCoroutineContext().ensureActive()
        if (transcriptKind(text) == TranscriptKind.EMPTY) { dbg("city heard nothing"); return again() }
        cityJudge(c, text)
    }

    /** Reads the answer for adequacy and closes the encounter (a rumour at the bar, wariness at the hotel). */
    private suspend fun cityJudge(c: CityScene, text: String) {
        val line = CITY_LINES.getValue(c.lang).getValue(c.kind)
        val b = brain
        // An order from the bar's menu is an order: adequate without the read. Unavailable Nano skips the read, as small talk does.
        val drink = if (c.kind == CityKind.BAR) orderOf(regionOf(c.lang, c.country), text) else null
        val odd = drink == null && b != null && runCatching {
            Check.parseClassify(nanoLock.withLock {
                Nano.oneShot(b)(smallTalkOddPrompt(destination), "Officer asked: \"$line\"\nTraveler answered: \"${unfield(text)}\"", 12)
            }).second.odd
        }.getOrDefault(false)
        currentCoroutineContext().ensureActive()
        if (c.kind == CityKind.HOTEL && odd) cityWary = true
        val s = career.snapshot
        val recent = prefs.getString("cityRumours", "").orEmpty().split(',').mapNotNull(String::toIntOrNull)
        val rumour = if (c.kind == CityKind.BAR && !odd)
            pickRumour(s, act, recent, Random.Default) else null
        rumour?.let { r -> prefs.edit { putString("cityRumours", rememberRumour(recent, r).joinToString(",")) } }
        dbg("city ${c.kind} odd=$odd drink=$drink rumour=$rumour | $text")
        if (city?.kind == c.kind) city = c.copy(step = CityStep.DONE, adequate = !odd, rumour = rumour, drink = drink, heard = text.trim())
    }

    // ---- Home vignettes (DaysAway.kt): a caption over the flat on leaving the summary, in memory only ----
    private var homeDue: Long? = null
    private var homeThen: (() -> Unit)? = null

    /** Opens the due vignette instead of leaving the summary; [then] runs when it ends. False: nothing due. */
    private fun openHome(then: () -> Unit): Boolean {
        val id = homeDue ?: return false
        homeDue = null
        if (secretEnding || !careerReady) return false
        val recent = prefs.getString("homeVignettes", "").orEmpty().split(',').mapNotNull(String::toIntOrNull)
        val pick = pickVignette(recent, Random(id))
        prefs.edit { putString("homeVignettes", rememberVignette(recent, pick).joinToString(",")) }
        homeVignette = pick; homeThen = then
        dbg("home vignette $pick attempt=$id")
        return true
    }

    fun leaveHome() {
        if (homeVignette == null) return
        homeVignette = null
        val then = homeThen; homeThen = null
        then?.invoke()
    }

    /** Skip, Back or Continue: the encounter ends and the summary's button goes on. */
    fun leaveCity() {
        if (city == null) return
        cityJob?.cancel(); cityJob = null; cityHolding = false
        speakJob?.cancel(); tts?.stop()
        city = null
        val then = cityThen; cityThen = null
        soundtrack()
        then?.invoke()
    }
    var booting by mutableStateOf(false); private set // the implant's start-up line over the first seconds
    private var scannerMachineView by mutableStateOf(false)
    var machineView: Boolean
        get() = scannerMachineView
        private set(value) { scannerMachineView = value; refreshScanner() } // the T-N test: the eye machine instead of the officer
    internal var overloadCue by mutableStateOf(false); private set
    internal var overloadWarning by mutableStateOf(false); private set
    internal var overloadPractice by mutableStateOf(false); private set
    internal var overloadPips by mutableStateOf(0); private set
    internal var scannerInterrupted by mutableStateOf(false); private set
    private var overloadCount = 0
    @Volatile private var gesture: FaceGestures? = null
    private fun overloadTick() {
        overloadPips = gesture?.count ?: 0
        sfx.play(R.raw.sfx_blink)
        hapticPlayer.play(HapticEvent.OVERLOAD_TICK)
    }
    private suspend fun overloadWindow(myRun: Int, a: Assessment) {
        if (!faceAvailable() || liveContract == Contract.UNAIDED || overloadCount >= 2 || a.confirming || a.reasking ||
            a.asked < overloadCount + 1) return
        overloadPractice = storyMission && overloadCount == 0
        overloadCount++
        faceWatch.gestureWindow(true)
        overloadWarning = true
        implant = null
        // Diagnostics only: reduced numbers per window, so a missed blink can be told from a missed face.
        var outcome = "cancelled"; var ticks = 0; var frames = 0; var faces = 0; var lastT = Long.MIN_VALUE
        val eyes = ArrayList<Float>()
        val pairs = ArrayList<Pair<Float?, Float?>>() // per-frame left/right eye, for the trace
        try {
            interviewDelay(if (implantPerks.antiJam) 1800 else 600)
            if (myRun != run || !faceAvailable()) return
            overloadWarning = false
            overloadPips = 0
            gesture = FaceGestures(faceWatch.now(), if (overloadPractice) Long.MAX_VALUE else if (implantPerks.antiJam) 4500 else 3000)
            overloadCue = true
            val practiceStart = faceWatch.now()
            var lastFaceAt = practiceStart
            while (myRun == run) {
                awaitForeground()
                if (myRun != run) return
                val g = gesture ?: return
                val now = faceWatch.now()
                if (faceAvailable()) lastFaceAt = now
                if (!g.active(now) || !watching || now - lastFaceAt > 1500) {
                    outcome = if (g.complete) "complete" else if (!watching || now - lastFaceAt > 1500) "face lost" else "timeout"
                    break
                }
                if (overloadPractice && practiceExpired(practiceStart, now)) { outcome = "timeout"; break } // the practice never fails the interview
                if (watching) faceWatch.latest?.let {
                    if (it.t != lastT) {
                        lastT = it.t; frames++
                        if (it.face) { faces++; eyes += it.eyesOpen; pairs += it.leftEye to it.rightEye }
                    }
                    if (g.sample(it, now)) { ticks++; overloadTick() }
                }
                delay(30)
            }
            if (myRun != run) return
            if (gesture?.complete == true) {
                if (!overloadPractice) a.overload()
                scannerInterrupted = true
                interviewDelay(450)
            }
        } finally {
            // Per-frame left/right eye as LL/RR, 0-99 ("--" when missing), in order, last 60 frames (the missed blink is
            // usually at the end).
            fun pct(x: Float?) = x?.let { "%02d".format(Locale.ROOT, (it * 100).toInt().coerceIn(0, 99)) } ?: "--"
            val trace = pairs.takeLast(60).joinToString(",") { (l, r) -> "${pct(l)}/${pct(r)}" }
            eyes.sort()
            fun f(x: Float?) = x?.let { "%.2f".format(Locale.ROOT, it) } ?: "-"
            dbg("overload: practice=$overloadPractice $outcome ticks=$ticks frames=$frames " +
                "face=${if (frames == 0) 0 else faces * 100 / frames}% " +
                "eyes=${f(eyes.firstOrNull())}/${f(eyes.getOrNull(eyes.size / 2))}/${f(eyes.lastOrNull())} trace=$trace")
            if (myRun == run) {
                overloadCue = false; overloadWarning = false; scannerInterrupted = false
                gesture = null; faceWatch.gestureWindow(false)
            }
        }
    }
    internal var composureCue by mutableStateOf(false); private set
    internal var composureProgress by mutableStateOf(0f); private set
    internal var composurePaused by mutableStateOf(false); private set
    private var composureCount = 0
    @Volatile private var composureHold: ComposureHold? = null
    private suspend fun composureWindow(myRun: Int, a: Assessment) {
        if (!faceAvailable() || liveContract == Contract.UNAIDED || composureCount >= 3 || a.confirming || a.reasking || a.caught) return
        val neutral = profiler.neutralFace() ?: return
        composureCount++
        val hold = ComposureHold(neutral)
        composureHold = hold; composureProgress = 0f; composurePaused = false
        composureCue = true; implant = null
        faceWatch.gestureWindow(true)
        val until = interviewClock.now() + 8_000_000_000L
        var lastFaceAt = faceWatch.now()
        try {
            while (myRun == run && interviewClock.now() < until && !hold.complete && !hold.failed) {
                if (backgrounded) { hold.suspendHold() }
                awaitForeground()
                if (myRun != run) return
                val now = faceWatch.now()
                if (faceAvailable()) lastFaceAt = now
                if (!watching || now - lastFaceAt > 1500) break
                val sample = faceWatch.latest
                composurePaused = (sample == null ||
                    !NeutralFace.valid(sample) || now - sample.t !in 0..250)
                hold.update(sample, now)
                composureProgress = hold.progress
                delay(30)
            }
            if (myRun == run && hold.complete && a.composure()) {
                sfx.play(R.raw.sfx_blink); hapticPlayer.play(HapticEvent.OVERLOAD_TICK)
                interviewDelay(600)
            }
        } finally {
            if (myRun == run) {
                composureCue = false; composureHold = null; faceWatch.gestureWindow(false)
            }
        }
    }

    /** A fresh detector frame is required: permission alone does not mean a camera is running. */
    private fun faceAvailable(maxAge: Long = 250): Boolean = watching && faceWatch.latest?.let {
        it.face && faceWatch.now() - it.t in 0..maxAge
    } == true
    private var bluffAnnounced = false
    internal var bluffArmed by mutableStateOf(false); private set
    internal var bluffHighlight by mutableStateOf(false); private set
    internal var bluffReason by mutableStateOf(false); private set
    @Volatile private var bluffGesture: DeskBluffGesture? = null
    private val canBluff get() = faceAvailable() && profiler.neutralFace() != null && !bluffAnnounced && act == Act.FREE && !training && room == null && !machineView &&
        liveContract != Contract.UNAIDED && !mind.bluffUsed && mind.decision == null &&
        passportGiven && heldHandover == null && mic == Mic.READY && !bluffArmed && fragment == null
    @Synchronized private fun announceBluff() {
        if (backgrounded || !canBluff) return
        bluffAnnounced = true
        val myRun = run
        val started = interviewClock.now()
        val gesture = DeskBluffGesture(profiler.neutralFace())
        bluffGesture = gesture
        bluffArmed = true; bluffReason = false; implant = null
        silenceJob?.cancel(); listening = false; resumeListening = false
        val old = listenJob?.apply { cancel() }
        capturing = false; mic = Mic.OFF
        faceWatch.gestureWindow(true)
        scope.launch(Dispatchers.Default) {
            try {
                old?.join()
                if (myRun != run) return@launch
                val until = interviewClock.now() + 4_000_000_000L
                var lastFaceAt = faceWatch.now()
                while (myRun == run && !gesture.complete && interviewClock.now() < until) {
                    awaitForeground()
                    if (myRun != run) return@launch
                    val now = faceWatch.now()
                    if (faceAvailable()) lastFaceAt = now
                    if (!watching || now - lastFaceAt > 1500) break
                    faceWatch.latest?.let { gesture.sample(it, faceWatch.now()) }
                    bluffHighlight = gesture.expressive
                    delay(30)
                }
                if (myRun == run && gesture.complete && mind.deskBluff()) {
                    bluffReason = true
                    sfx.play(R.raw.sfx_blink); hapticPlayer.play(HapticEvent.OVERLOAD_TICK)
                    scope.launch { interviewDelay(4000); if (myRun == run) bluffReason = false }
                }
            } finally {
                if (myRun == run) {
                    val elapsed = interviewClock.now() - started
                    if (clockAt != 0L) clockAt += elapsed
                    if (spokenAt != 0L) spokenAt += elapsed
                    if (silenceDue != 0L) silenceDue += elapsed
                    answerAt = faceWatch.now()
                    bluffArmed = false; bluffHighlight = false; bluffGesture = null
                    faceWatch.gestureWindow(false)
                    awaitAnswer(myRun, resuming = true)
                }
            }
        }
    }
    private val coachedWindow get() = overloadCue || overloadWarning || composureCue || bluffArmed
    private var roomSay = emptyList<String>() // Assessment.questions in the country's language, same order
    private var roomSub = emptyList<String>() // the same in the subtitle language
    /** The subtitle language this interview (officerEn, Hint.english): the UI language, see [subtitleLang]. */
    private var sub = "en"
    /** The subtitle language is the desk's own (English UI at an English desk): a second line would only paraphrase the first.
     * Never at the Thalassian desk: it borrows a language pack's desk, but the officer speaks Thalassian. */
    val subtitleRedundant get() = !thalassianDesk && sub == destination.lang
    var mind by mutableStateOf(OfficerMind(Persona.random(Random.Default))); private set

    private var asr: OfflineRecognizer? = null // Whisper, only for languages without system speech recognition
    private var retryAsr: OfflineRecognizer? = null // Whisper for a second try when the system recognizer hears nothing
    private var brain: GenerativeModel? = null // Gemini Nano: hints and answer checks
    private var tts: TextToSpeech? = null
    // Piper (decision: vetted voices per language and gender); the system TTS speaks when there is none.
    private var piper: com.k2fsa.sherpa.onnx.OfflineTts? = null
    private var piperVoice = ""
    private var piperPitch = 1f // < 1: the other gender's voice played slower and lower (the associate without a male voice)
    internal val officerMouth = OfficerMouth()
    private var speakJob: Job? = null
    private val ttsReady = scope.async(Dispatchers.Default) { Models.tts(ctx).also { tts = it } }
    private var desk = Desk()
    private var deskSpoken = false
    private var roomDesk = Desk()
    private val history = mutableListOf<String>()
    private val covered = mutableSetOf<FactId>() // cover facts the answers were about (Verdict.fact)
    private var analyst: Analyst? = null
    // The room's retinal display (phase 4 finding 3): its own Analyst, so the desk's "earlier suggestions" are not in its
    // prompt, and the transcript from the associate's greeting on ([roomFrom]). [analyst] keeps judging with the desk's statements.
    private var roomAnalyst: Analyst? = null
    private var roomFrom = 0
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
    private var askedEn: Pair<String, String>? = null // the desk move's subtitle and its question part alone (no acknowledgement)
    private fun logBehavior(tell: Tell?, z: Double) {
        behavior += BehaviorEvent(tell, z, (askedEn?.takeIf { it.first == officerEn }?.second ?: officerEn.ifEmpty { question }).trim().let { if (it.length > 70) it.take(69).substringBeforeLast(' ', it.take(69)).trimEnd(',', ';', ' ') + "…" else it })
    }
    /** The debrief for the result screens: null when the camera was off or the baseline was never set. */
    fun behaviorDebrief(): String? = if (watching && profiler.calibrated) debrief(behavior).joinToString(" ") { it.text(ctx) } else null
    private val turnLock = Mutex()
    private val nanoLock = Mutex()
    private val voiceLock = Mutex() // every use of [piper], see piperSay
    private var pendingRetina: Deferred<Retina?>? = null
    /** Set when the player's answer arrives, cleared as each suggestion call starts: [Analyst.suggest] then skips its retry. */
    @Volatile private var playerAnswered = false
    private var retinaShow: Job? = null // the room's (possibly delay-jammed) hints display, after pendingRetina
    private var pendingSmallTalkOdd: Deferred<Unit>? = null
    private var silenceJob: Job? = null
    private var listenJob: Job? = null
    // The answer clock (invisible timer and measured thinking time) starts only once the officer has finished
    // speaking AND suggestions are ready (or the Unaided contract has switched the display off).
    @Volatile private var spoken = false
    @Volatile private var shown = false
    @Volatile private var clockAt = 0L
    @Volatile private var spokenAt = 0L // when the officer finished the question (room think time, Assessment.thinkMs)
    @Volatile private var holding = false
    @Volatile private var listening = false
    @Volatile private var lookAsked = false // "please look at me": once per question
    @Volatile private var run = 0 // bumps on every new interview, so stale callbacks from the last one do nothing

    init {
        trimDiagnostics(ctx.filesDir)
        if (supported == null) scope.launch(Dispatchers.Default) {
            // No AICore at all: checkStatus would throw forever (NANO_UNKNOWN, an endless "Try again"), so no Nano.
            val aicore = runCatching { ctx.packageManager.getPackageInfo("com.google.android.aicore", 0) }.isSuccess
            val st = if (!aicore) FeatureStatus.UNAVAILABLE.also { dbg("system AI: no AICore package") }
                else probeNano(log = ::dbg) { Generation.getClient().let { m -> try { m.checkStatus() } finally { m.close() } } }
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
            basicAsr = probeAsr(Language.shipped.map { it.code })
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
        faceReceiver?.let(ctx::unregisterReceiver); simJob?.cancel()
        brain?.close(); asr?.release(); retryAsr?.release(); tts?.shutdown(); music.stop(); sfx.release()
        CoroutineScope(Dispatchers.Default).launch {
            vadLock.withLock { vadClosed = true; sileroVad?.release(); sileroVad = null }
        }
        // viewModelScope is gone; wait for a running synthesis on a scope of its own before freeing the voices.
        CoroutineScope(Dispatchers.Default).launch { voiceLock.withLock { piper?.release() } }
    }

    // ---------- flow ----------

    /** Officer sheet ids in the installed packs. */
    private fun sheets(packArt: Set<String>) =
        packArt.filter { it.startsWith("officer_") }.map { it.removePrefix("officer_").removeSuffix("_sheet") }.toSet()

    /** Countries a mission can go to: an installed language and a drawn officer (a Japanese desk never gets a borrowed face). */
    fun playable(packArt: Set<String> = Packs.art(ctx).keys): List<Country> {
        return if (act == Act.FREE) unlockedCountries(career.snapshot, playableCountries(languages, packArt)) else playableCountries(languages, packArt)
    }

    /** The booked country, otherwise an explicit eligible pick or Anywhere. */
    fun destChoice(playable: List<Country> = playable()): String {
        career.snapshot?.booking?.let { return it.countryId }
        return resolveDest(dest.takeIf { destPicked } ?: "random",
            if (training) playable else identity?.let { missionCountries(career.snapshot, playable, it, storyAct) } ?: emptyList())
    }

    /** The "discard your unfinished trip?" dialog: what the Discard button repeats (Begin or Keep helping tapped with a resumable trip). */
    var ticketError by mutableStateOf(false)
    var confirmDiscard by mutableStateOf<(() -> Unit)?>(null)

    /** Books the menu selection once, or resumes the existing ticket. */
    fun beginMission(confirmed: Boolean = false) {
        if (screen == Screen.SUMMARY && (openCity(false) { beginMission(confirmed) } || openHome { beginMission(confirmed) })) return
        if (ticketDeclined(career.snapshot)) { ticketError = true; return }
        if (firstCareerTraining) { resumeFirstTraining(); return }
        if (navigationBlocked || liveAttempt != null || secretEnding) return
        // The building test keeps its passed desk and scenes: the main button continues it like the panel does.
        if (career.snapshot?.epilogueFrame == 5 && resumable()) { continueInterrupted(); return }
        // Throwing an unfinished trip away takes a second tap (the recovery panel's own Begin has no other way forward).
        if (!confirmed && resumable()) { confirmDiscard = { beginMission(true) }; return }
        val bad = (careerState as? CareerSave.Unreadable)?.read
        if (bad?.preparationOnly == true || interruptedAttempt != null) {
            saveCareer({ career.store.discardPreparation("new-mission") }) {
                restoredAttempt = null
                openBooking()
            }
            return
        }
        if (!careerReady) return
        openBooking()
    }

    /** Departure training can restore its generic booking before identity creation. */
    private fun openBooking() {
        val art = Packs.art(ctx).keys
        val booking = career.snapshot?.booking
        val next = missionScreen(identity != null || departureTraining, nextSetupScreen(), playable(art).isNotEmpty(), booking != null, storyAct)
        if (next == Screen.LANGUAGES && languages.isNotEmpty()) { needLanguages(); return }
        if (next != Screen.DESTINATION) { screen = next; return }
        if (booking == null) { prepareMission(identity); return }
        val trip = bookingForRestore(career.snapshot)?.restore(playable(art), sheets(art), art, languages)
        // The ticket is ineligible or its pack/art is gone: drop it and book again.
        if (trip == null) { saveCareer({ career.store.setBooking(null) }) { openBooking() }; return }
        restoredAttempt = null; contract = null; preparationUnavailable = false
        travelCompleted = true // A restored ticket skips travel, including after process death.
        destination = trip.destination; story = trip.cover.copy(homeArrivalTraining = homeArrivalTraining); officerId = booking.officerId
        difficulty = difficultyFor(destination.lang)
        screen = if (firstCareerTraining) Screen.COVER else Screen.DESTINATION
        if (departureTraining) openFatherQueue()
        watchEars(destination.lang)
    }

    /** "Keep helping" keeps the passport and discards any interrupted story before entering act 2.
     * [walkedAway] (the epilogue's "I'm sure") enters act 2 the same way, marked walked-away in the same write (reversible: [returnToMarek]). */
    fun keepHelping(walkedAway: Boolean = false, confirmed: Boolean = false) {
        if (navigationBlocked || liveAttempt != null || act == Act.FREE) return
        // The building test (frame 5) is the story's own end of act 1: only an ordinary unfinished trip needs asking.
        if (!confirmed && !walkedAway && career.snapshot?.epilogueFrame != 5 && resumable()) { confirmDiscard = { keepHelping(false, true) }; return }
        val bad = (careerState as? CareerSave.Unreadable)?.read
        if (bad?.preparationOnly == true || interruptedAttempt != null) {
            saveCareer({ career.store.discardPreparation("keep-helping") }) {
                restoredAttempt = null
                keepHelping(walkedAway, true)
            }
            return
        }
        if (!careerReady) return
        saveCareer({ career.store.setAct(Act.FREE, walkedAway) }) { screen = nextSetupScreen() }
    }

    /** The walked-away menu's "Write to Marek": Marek's help resumes (levels earned meanwhile, his message, couriers, scenes). */
    fun returnToMarek() {
        if (navigationBlocked || liveAttempt != null || !careerReady || career.snapshot?.walkedAway != true) return
        saveCareer({ career.store.returnToMarek() }) { }
    }

    /** The epilogue's "I'm sure": the desk attempt kept live for the building test ends unplayed, then act 2 starts walked away (reversible by [returnToMarek]). */
    fun walkAway() {
        if (liveAttempt != null) abortAttempt(AbortCause.ABANDONED) { keepHelping(walkedAway = true) }
        else keepHelping(walkedAway = true)
    }

    val usaCompletion get() = usaCompletionPending(career.snapshot)
    fun acknowledgeUsaCompletion() {
        if (screen != Screen.RESULT || !usaCompletion || !careerReady) return
        career.snapshot?.receipt?.let { endingShown(it.id) }
    }

    val secretEnding get() = badTester(career.snapshot)
    /** The upgrade a courier hands over on the menu after a crossing at its destination. */
    val upgradeDelivery get() = if (act == Act.FREE) courierDelivery(career.snapshot) else null
    /** Picks courier destinations for granted levels that have none (also old saves), among the countries bookable now. */
    fun assignUpgradeDrops() {
        val id = identity ?: return
        val s = career.snapshot ?: return
        if (act != Act.FREE || !careerReady || screen != Screen.START ||
            (1..s.upgradesGranted).none { it !in s.upgradeDrops && it !in s.cutscenesSeen }) return
        // Only the new drops come back: comparing their count with the saved ones skipped every level after the first.
        val drops = chooseUpgradeDrops(s, missionCountries(s, playable(), id, false))
        if (drops.isEmpty()) return
        saveCareer({ career.store.addUpgradeDrops(drops) }) {}
    }
    fun upgradeSeen(upgrade: Int) {
        if (screen == Screen.START && careerReady) saveCareer({ career.store.upgradeSeen(upgrade) }) {}
    }
    /** Settings and career prefs a story restart clears; the career snapshot is replaced by the store first. */
    private fun clearStoryPrefs(extraSettings: Set<String> = emptySet()) {
        // Career first: a failed write must never discard settings or the visible old ending.
        val editor = prefs.edit()
        prefs.all.keys.filter { StoryReset.clears(it) || it in extraSettings }.forEach { editor.remove(it) }
        editor.commit()
        val careerPrefs = ctx.getSharedPreferences("career", 0)
        careerPrefs.edit(commit = true) {
            careerPrefs.all.keys.filter { it == "messages_read" || it == "implant_boot_seen" || it.startsWith("msg_at_") }.forEach { remove(it) }
        }
    }

    /** Settings > Reset progress. The app restarts after the commit so every derived var is rebuilt from disk. */
    val canResetProgress get() = !navigationBlocked && liveAttempt == null && careerReady
    fun resetProgress() {
        if (!canResetProgress) return
        saveCareer({ career.store.resetProgress() }, ::restartApp)
    }

    /** READ_ERROR's "Start over": the unreadable save is copied aside first (CareerStore.startOver), then the app restarts fresh. */
    fun startOver() {
        if (careerSaving || (careerState as? CareerSave.Unreadable)?.read?.preparationOnly != false) return
        saveCareer({ career.store.startOver() }, ::restartApp)
    }

    private fun restartApp(@Suppress("UNUSED_PARAMETER") saved: CareerSave.Saved) {
        clearStoryPrefs(setOf("prologueSeen"))
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch != null) ctx.startActivity(launch)
        Runtime.getRuntime().exit(0)
    }

    fun restartFree() {
        if (!secretEnding || navigationBlocked || liveAttempt != null || !careerReady) return
        saveCareer({ career.store.restartFree() }) {
            clearStoryPrefs()
            readMessages = emptySet(); messageTimes = emptyMap()
            restored = null; restoredAttempt = null; contract = null; queue = null
            passportDone = false; dest = "random"; destPicked = false; training = false
            screen = nextSetupScreen()
        }
    }

    /** A trip is booked and kept until its interview starts or [cancelTrip]. */
    val booked get() = career.snapshot?.booking != null

    /** The boarding pass / cover screen's explicit cancellation; Back keeps the booking. */
    fun cancelTrip() {
        if (firstCareerTraining || navigationBlocked || liveAttempt != null || !careerReady || !booked) return
        if (screen != Screen.DESTINATION && screen != Screen.COVER) return
        clearBooking { screen = Screen.START }
    }

    /** Shared by Cancel trip and a changed destination; all other career state stays intact. */
    private fun clearBooking(after: () -> Unit) = saveCareer({ career.store.setBooking(null) }) { after() }

    private fun prepareMission(id: Identity?, after: () -> Unit = { screen = Screen.DESTINATION }) {
        travelCompleted = false
        restoredAttempt = null
        contract = null
        preparationUnavailable = false
        val r = Random.Default
        val packArt = Packs.art(ctx).keys
        val sheets = sheets(packArt)
        val playable = playable(packArt)
        if (playable.isEmpty()) { needLanguages(); return } // no language pack on the phone (yet)
        val choice = destChoice(playable)
        val pool = if (firstCareerTraining) playable else missionCountries(career.snapshot, playable, checkNotNull(id), storyAct)
        val selected = if (homeArrivalTraining) {
            val home = checkNotNull(id)
            // Never substitute a foreign country/language when the home pack is missing.
            selectDestination(pool.filter { it.id == home.nationality.id }, languages, packArt,
                home.nationality.languages.first(), r, airportRotation)
        } else selectDestination(if (firstCareerTraining) pool else destPool(choice, pool), languages, packArt,
            if (departureTraining) id?.let { trainingLanguage(it, languages) }
                ?: uiLanguage(ctx).takeIf { it in languages } ?: languages.firstOrNull() else null, r, airportRotation)
        if (selected == null) { needLanguages(); return }
        destination = selected
        difficulty = difficultyFor(destination.lang)
        story = if (departureTraining) StoryCover.departure(destination)
            else if (homeArrivalTraining) StoryCover.homeArrival(destination, checkNotNull(id))
            else if (storyAct) StoryCover.backstory(destination, checkNotNull(id), career.snapshot?.storyLastCountry ?: career.snapshot?.lastPassedCountry)
            else Backstory.random(r, destination, checkNotNull(id), freeDeparture(career.snapshot, id)).let { if (act == Act.FREE && !training) it.withReservation() else it } // the app pays: a hotel stay becomes the reserved hotel (booking card)
        // Both acts roll queue scenes (harvest scenes first, poison echoes by tier in act 2); training has no queue.
        story = story.copy(encounter = Encounters.forTrip(training, r,
            career.snapshot?.encountersSeen.orEmpty(), Encounters.tier(career.snapshot, act))?.let { QueueEncounter(it.id) })
        officerId = airport.officers.filter(sheets::contains).random(r)
        // The persona is a placeholder: the real one is rolled when the interview starts (difficulty may change on COVER).
        val booking = Booking(country.id, airport.id, officerId, story.preparation(Persona.TRAINING))
        saveCareer({ career.store.setBooking(booking) }) {
            // A pick is for one trip: the ticket now holds it, and the next trip starts from Anywhere (owner).
            dest = "random"; destPicked = false; prefs.edit { remove("dest") }
            after()
            if (firstCareerTraining) { screen = Screen.COVER; if (departureTraining) openFatherQueue() }
            watchEars(destination.lang)
        }
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

    @Synchronized fun startInterview(fatherBriefed: Boolean = false) {
        val recovering = restoredAttempt
        if (screen != Screen.COVER || newsPending != null || !canStartAttempt(careerReady, brain != null, startingAttempt,
                liveAttempt != null || career.snapshot?.pendingAttempt != null && recovering == null)) return
        val art = Packs.art(ctx).keys
        // No pending attempt (a fresh booking, e.g. the first training cover): the recovery panel has nothing to offer,
        // so a pack Play evicted is fetched again (or the language screen says what is missing).
        if (recovering == null && !destinationAvailable(destination, officerId, languages, art)) { needLanguages(); return }
        if (!destinationAvailable(destination, officerId, languages, art) ||
            recovering != null && recoverPreparation(recovering, playable(art), sheets(art), art, languages) == null) {
            preparationUnavailable = true
            recoveryPackMissing = true
            return
        }
        if (travel != null) return
        if (shouldShowTravel(training, firstCareerTraining, recovering != null, travelCompleted)) {
            travel = TravelStep.FLIGHT
            return
        }
        if (departureTraining && recovering == null && !fatherBriefed) {
            openFatherQueue()
            return
        }
        val pending = story.encounter?.takeIf { it.choice == null }?.encounter
        if (pending != null && recovering == null) {
            // The ambient line is seeded by the booking (scene + officer), so a resumed queue reads the same.
            queue = QueueScene(pending, story.gender, ambient = Encounters.ambient(pending,
                Encounters.tier(career.snapshot, act), "${pending.id}/$officerId".hashCode(), career.snapshot?.planted.orEmpty()))
            return
        }
        val persona = recovering?.preparation?.persona ?: if (training) Persona.TRAINING else Persona.random(
            Random.Default, if (act == Act.FREE) career.level else effectiveLevel(training, false, career.level, difficulty))
        val context = recovering?.copy(id = 0) ?: Attempt(0, country.id, officerId, destination.lang, training,
            story = storyAct, replayEligible = !storyAct && !training, effectiveLevel = persona.level,
            preparation = if (firstCareerTraining) career.snapshot?.booking?.preparation ?: story.preparation(persona)
                else story.preparation(persona), contentVersion = 2, airportId = airport.id,
            contract = if (contractsUnlocked) contract?.id else null,
            nationalityLanguages = story.nationality.languages.toSet(),
            upgradesHeld = if (act == Act.FREE && !training) career.delivered else 0,
            progressEligible = act == Act.FREE && !training)
        startingAttempt = true; interruptStart = false
        fun persistStart() {
            saveCareer({ career.store.start(context, "interview") }) { saved ->
                startingAttempt = false
                restoredAttempt = null
                val a = checkNotNull(saved.snapshot.pendingAttempt)
                if (!launchAfterStart(interruptStart, brain != null)) {
                    liveAttempt = LiveAttempt(run, a)
                    abortAttempt(AbortCause.INTERRUPTED) { screen = Screen.START }
                } else whenForeground { launchInterview(a) }
            }
        }
        if (recovering != null && career.snapshot?.epilogueFrame != 5)
            saveCareer({ career.store.discardPreparation("restart") }) { persistStart() }
        else persistStart()
    }

    private fun openFatherQueue() {
        fatherQueueCalibrated = ctx.getSharedPreferences("career", 0).getBoolean("implant_boot_seen", false)
        queue = QueueScene(Encounters.fatherQueue(uiLanguage(ctx)) { str(it) }, story.gender,
            career.snapshot?.booking?.preparation?.fatherChoice)
    }

    /**
     * The father's Thalassian line aloud, no text: the Greek system voice at a low pitch, else the Italian male Piper.
     * Returns at the end of the speech, or after a short pause when nothing can speak. The desk setup resets voice and pitch.
     */
    suspend fun speakFather() {
        val done = CompletableDeferred<Unit>()
        var job: Job? = null
        var system: TextToSpeech? = null
        try {
            val t = runCatching { ttsReady.await() }.getOrNull()
            if (t != null && !backgrounded) {
                val greek = java.util.Locale.forLanguageTag("el-GR")
                val voice = Voices.offline(t, "el").firstOrNull { it.locale.country == "GR" }
                if (voice != null && t.isLanguageAvailable(greek) >= TextToSpeech.LANG_AVAILABLE && t.setVoice(voice) == TextToSpeech.SUCCESS) {
                    system = t
                    t.setPitch(0.8f)
                    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { done.complete(Unit) }
                        @Deprecated("") override fun onError(id: String?) { done.complete(Unit) }
                    })
                    if (t.speak(ThalassiaDesk.FATHER, TextToSpeech.QUEUE_FLUSH, null, "father") == TextToSpeech.ERROR) done.complete(Unit)
                } else {
                    withContext(Dispatchers.Default) { loadPiper(false, lang = "it") } // native model load: not on Main
                    if (piper != null) job = piperSay({ piper }, ThalassiaDesk.FATHER, 1.1f, 0f) { done.complete(Unit) }
                }
            }
            if (system == null && job == null) delay(2500) // no voice or sound: go on after a pause
            else kotlinx.coroutines.withTimeoutOrNull(30_000) { done.await() }
        } finally { system?.apply { stop(); setPitch(1f) }; job?.cancel() }
    }

    /** Random choices stay in memory; onboarding also saves its choice for process-death recovery. */
    fun selectQueueChoice(i: Int) {
        val q = queue ?: return
        if (i !in 0..1 || q.selectedChoice != null) return
        if (q.encounter.id == Encounters.FATHER) {
            if (!fatherQueueCalibrated) return
            if (!careerReady) return
            val booking = career.snapshot?.booking ?: return
            val chosen = booking.preparation.withFatherChoice(i)
            saveCareer({ career.store.setBooking(booking.copy(preparation = chosen)) }) {
                queue = q.copy(selectedChoice = i)
            }
            return
        }
        queue = q.copy(selectedChoice = i)
    }

    /** Commit at the desk handoff; keep the scene visible until the screen setter opens the interview. */
    fun chooseInQueue(i: Int) {
        val q = queue ?: return
        if (i != q.selectedChoice || startingAttempt || !careerReady) return
        if (q.encounter.id == Encounters.FATHER) {
            if (!fatherQueueCalibrated) return
            startInterview(fatherBriefed = true)
            return
        }
        // A failed save can be retried without drawing the officer's question again.
        if (story.encounter?.choice == null) story = story.copy(encounter = q.choose(i, Random.Default))
        startInterview()
    }

    /** Back on the queue screen: the cover again; READY shows the same scene. */
    fun leaveQueue() {
        if (navigationBlocked || firstCareerTraining) return
        implantBoot = null
        stopFaceRun()
        queue = null
    }

    @Synchronized private fun launchInterview(context: Attempt) {
        val b = brain ?: return
        playedOffline = ctx.getSystemService(android.net.ConnectivityManager::class.java)
            .let { cm -> cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } == null }
        stopInterview("new run")
        val myRun = ++run
        liveAttempt = LiveAttempt(myRun, context)
        temperament = Temperament.seeded(context.officerId, context.id)
        training = context.training
        story = story.copy(homeArrivalTraining = homeArrivalTraining)
        fatherAway = false
        fatherAwayDone?.cancel(); fatherAwayDone = null
        thalassiaDesk = if (usesThalassiaDesk(training, departureTraining)) ThalassiaDesk(context.preparation?.fatherChoice == 0) else null
        screen = Screen.INTERVIEW
        sub = subtitleLang(uiLanguage(ctx), destination.lang)
        implantPerks = ImplantPerks.fromUpgrades(if (!context.story && !context.training) context.upgradesHeld else 0)
        mind = OfficerMind(checkNotNull(context.preparation).persona, implantPerks,
            gentle = gentleDesk(context.language, career.snapshot?.trainedLanguages, context.training)).also {
            it.training = context.training; it.storyMission = context.story
            it.startDesk(country.id)
            if (cityWary) { it.wary(); cityWary = false }
            if (career.snapshot?.let { s -> s.translatorUnlocked || s.translatorUsed } == true) it.unlockTranslator() // used implies offered (careers from before the flag)
            it.homecoming = story.kind == TripKind.HOMECOMING
            it.implantWeight = implantWeight(passedCount(career.snapshot, country.id))
            story.encounter?.chosen?.let { c -> it.onEncounter(c.meter, c.patience) }
        }
        val rng = Random(Random.nextLong())
        // A homecoming starts with where they return from, a visit with why they came.
        val arrival = story.kind == TripKind.ARRIVAL
        val first = if (arrival) "purpose" else "from"
        val topics = (listOf(first) + (OFFICER_TOPICS - first).shuffled(rng)).filter { topicFact(it) in story.factIds() }
        desk = Desk(rng, topics, arrival, howlang = !homeArrivalTraining && asksHowLang(story.kind, destination.lang, story.nationality), tone = implantTone(mind.p),
            sawHelp = story.encounter?.helped == true && rng.nextDouble() < Encounters.SAW_CHANCE,
            homeArrival = homeArrivalTraining,
            taint = if (context.story || context.training) null
                else Poison.deskTaint(harvestTier(career.snapshot, act), career.snapshot?.planted.orEmpty(), context.id))
        // The queue question, once, after the second cover answer at the earliest (the language question takes the first).
        if (!homeArrivalTraining && FactId.ENCOUNTER in story.factIds()) desk.oneShot(ENCOUNTER_TOPIC, 2..Int.MAX_VALUE)
        deskSpoken = false
        face = Face.NEUTRAL; officerText = ""; officerEn = ""; hints = emptyList(); heard = ""; ending = null
        translatorOn = false; translatorEars = false; translatorSource = ""
        passportGiven = false; passportAsked = !thalassianDesk
        introTip = if (thalassianDesk) str(R.string.thalassia_hud) else if (training) str(R.string.tip_intro, destination.languageName(uiLanguage(ctx))) else null
        tip = introTip
        history.clear(); question = ""; covered.clear()
        val recentTalk = prefs.getString("recentTalk", "").orEmpty().split(',').mapNotNull(String::toIntOrNull)
        talks = listOf(pickSmallTalk(SMALL_TALK.getValue("en").size, recentTalk, Random.Default)); talkAt = -1; heldHandover = null
        prefs.edit { putString("recentTalk", rememberTalk(recentTalk, talks.first()).joinToString(",")) }
        talkLines.clear(); talkThink.clear()
        machineAnswers = emptyList(); machineResult = null; payload = null; payloadPlanted = false
        composureCount = 0; composureCue = false; composureHold = null
        bluffAnnounced = false; bluffArmed = false; bluffHighlight = false; bluffReason = false; bluffGesture = null
        overloadCount = 0; overloadCue = false; overloadWarning = false; scannerInterrupted = false
        gesture = null; faceWatch.gestureWindow(false)
        room = null; wasInRoom = false; narration = null; announced = false; jam = null; prototype = false
        // A resumed building test goes straight to the room: show the machine while the models load, not the passed desk's officer.
        machineView = context.story && !context.training && career.snapshot?.epilogueFrame in 0..5
        activeFaceRun = myRun; refreshScanner()
        storyMission = context.story
        unitName = unit(); deskUnitName = unitName
        scope.launch { booting = true; delay(3000); booting = false }
        behavior.clear(); profiler = Profiler(); interviewAt = faceWatch.now(); implant = null; hintUntil = 0
        implantJob?.cancel()
        if (watching && !thalassianDesk) implantJob = scope.launch(Dispatchers.Default) { coachLoop(myRun) }
        if (!thalassianDesk && slowLang(destination.lang)) implantNote(myRun, str(R.string.implant_slow, destination.languageName(uiLanguage(ctx))))
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
                if (thalassianDesk) {
                    val t = ttsReady.await()
                    val greek = java.util.Locale.forLanguageTag("el-GR")
                    val greekVoice = Voices.offline(t, "el").firstOrNull { it.locale.country == "GR" }
                    val greekReady = greekVoice != null && t.isLanguageAvailable(greek) >= TextToSpeech.LANG_AVAILABLE &&
                        t.setVoice(greekVoice) == TextToSpeech.SUCCESS
                    t.setPitch(1f)
                    if (greekReady) voiceLock.withLock {
                        piper?.release(); piper = null; piperVoice = ""; piperPitch = 1f
                    } else {
                        Voices.apply(ctx, t, "it", true, "story_thalassia")
                        loadPiper(true, lang = "it")
                    }
                    analyst = null
                    return@withLock
                }
                val female = Country.isFemale(officerId)
                tts?.let { Voices.survey(ctx, it, destination.lang); Voices.apply(ctx, it, destination.lang, female, officerId); it.setSpeechRate(1.1f) }
                loadPiper(female)
                analyst = Analyst(b, destination, story, sub)
            }
            if (myRun != run) return@guarded
            if (context.story && !context.training && career.snapshot?.epilogueFrame in 0..5) {
                enterRoom(myRun)
                return@guarded
            }
            if (mind.translatorOffered) translatorEars = ownEars() // unlocked career: the switch is there from the first line
            tip = introTip
            officerTurn(myRun, "[The next traveler steps up to your desk. Greet them and ask for their passport.]", answer = false)
        } }
    }

    private fun unit() = "Unit ${officerId.uppercase().replace('_', '-')} fw %.1f".format(1.0 + 1.5 * mind.p.strictness)

    /**
     * The officer's Piper voice for [female], swapped under [voiceLock] so no synthesis runs on the one released.
     * [pitch] < 1 (the associate): without a voice of that gender, the other one played slower and lower at [pitch].
     */
    private suspend fun loadPiper(female: Boolean, pitch: Float = 1f, lang: String = destination.lang) {
        val own = Piper.find(ctx, lang, female)
        val v = own ?: Piper.find(ctx, lang, !female).takeIf { pitch < 1f }
        piperPitch = if (own == null && v != null) pitch else 1f
        if (v?.first?.path.orEmpty() != piperVoice) voiceLock.withLock {
            piper?.release(); piper = v?.let { runCatching { Piper.load(it.first, it.second) }.getOrNull() }
            piperVoice = if (piper != null) v!!.first.path else ""
        }
        dbg("voice ${if (piper != null) "piper ${v?.first?.name} pitch=$piperPitch" else "system"}")
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

    @Synchronized fun stopInterview(reason: String = "ui") {
        trimDiagnostics(ctx.filesDir)
        fatherAwayDone?.cancel(); fatherAwayDone = null; fatherAway = false
        officerMouth.stop()
        pendingSpeech = null; speechSerial++; resumeListening = false; capturing = false
        silenceDue = 0L
        stopFaceRun()
        dbg("stop reason=$reason")
        run++
        composureCue = false; composureHold = null
        bluffAnnounced = false; bluffArmed = false; bluffHighlight = false; bluffReason = false; bluffGesture = null
        overloadCue = false; overloadWarning = false; scannerInterrupted = false
        gesture = null; faceWatch.gestureWindow(false)
        implantJob?.cancel(); implant = null
        pendingRetina?.cancel(); pendingRetina = null
        retinaShow?.cancel(); retinaShow = null
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
        languagesGap = null
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

    /** Back during an interview: interrupted like a kill (Continue on the start screen resumes), never abandoned. No pauseInterview(): nothing but onResume un-pauses it. */
    fun abort() {
        // A decided verdict (the mind's, before the desk captures it after the last line) plays out; the terminal save follows it.
        if (!wasInRoom && mind.decision != null || backIgnored(liveAttempt, wasInRoom)) return
        abortAttempt(AbortCause.INTERRUPTED) { screen = Screen.START }
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
        val finished = finishAttempt(withSeeds(live, live.run, askedSeeds()), live.run, abortKind(cause))
        liveAttempt = finished
        stopInterview("abort $cause")
        val event = finished.terminal ?: return
        saveCareer({ career.record(event, "start") }) {
            liveAttempt = null
            narration = null; ending = null
            after()
        }
    }

    @Synchronized private fun captureAttemptDecision(myRun: Int, decision: Decision) {
        liveAttempt = liveAttempt?.let { captureDecision(it, myRun, decision, wasInRoom, room != null, mind.crashed, room?.doses ?: 0, room?.payloadNamed == true,
            askedSeeds(), mentioned = mind.implantPassing + mind.implantDetailed > 0 || (room?.implantAsks ?: 0) > 0, flags = mind.flagCount) }
    }

    private fun askedSeeds() = room?.let { it.seeds.take(it.asked) }.orEmpty()

    /** Reserve the terminal event before dispatching IO; speech cancellation cannot cancel this save. */
    @Synchronized private fun saveTerminal(myRun: Int, after: () -> Unit) {
        val live = liveAttempt ?: return
        if (live.terminal != null || myRun != run) return
        val finished = finishAttempt(live, myRun, TerminalKind.DECIDED)
        val event = finished.terminal ?: return
        liveAttempt = finished
        terminalTransition = true
        val c = live.context; val cleared = mind.decision?.cleared == true
        // career.snapshot is still the career before this attempt: career.record below adds the country to its countries.
        val before = career.snapshot
        val first = firstVisit(c.countryId, before)
        homeDue = c.id.takeIf { homeVignetteDue(act, c.story || storyMission, c.training, c.preparation?.kind == TripKind.HOMECOMING, cleared, before) }
        cityDue = c.id.takeIf { cityEncounterDue(it, act, c.story || storyMission, c.training, c.countryId, cleared, before) &&
            keepsEncounter(homeDue != null, c.countryId, first) }
        cityFirst = first
        cityRide = c.id.takeIf { cityRideEligible(act, c.story || storyMission, c.training, c.countryId, cleared, before) }
        stopInterview("terminal save")
        saveCareer({ career.record(event, "terminal") }) {
            training = firstCareerTraining
            whenForeground(after)
        }
    }

    private fun finishTerminal(to: Screen) = whenForeground {
        stopInterview("terminal saved")
        liveAttempt = null; terminalTransition = false
        narration = null; restored = null
        screen = if (act1Stage != Act1Stage.DONE) nextSetupScreen()
            else if (career.snapshot?.receipt?.ending == null && story.homeArrivalTraining) Screen.START else to
        // Keep the reveal pending while the new building debrief is open, including across process death.
        if (!(to == Screen.RESULT && (buildingResult || usaCompletion)))
            career.snapshot?.receipt?.let { endingShown(it.id) }
    }

    fun replay() {
        // Not during a scene beat, the walk to the room, or the last words (officerFinished would act on them again).
        if (coachedWindow || officerText.isEmpty() || narration != null || (room == null && wasInRoom) || mind.decision != null) return
        speak(run, officerText)
    }

    /** Recall is plain information in the UI language, using the same translated values as the itinerary. */
    private fun recallFact(fact: FactId): String {
        val ui = uiLanguage(ctx)
        fun value(english: String) = coverValue(english, story, ui) { str(it) }
        val (label, text) = when (fact) {
            FactId.FROM -> R.string.booking_from to coverNation(story.from, ui)
            FactId.PURPOSE -> R.string.booking_f_purpose to value(story.purpose)
            FactId.DAYS -> R.string.booking_f_days to java.text.NumberFormat.getIntegerInstance(coverLocale(ui)).format(story.days)
            FactId.STAY -> R.string.booking_f_stay to value(story.stayed)
            FactId.HOST -> R.string.booking_f_host to value(story.host)
            else -> error("Not an itinerary fact: $fact")
        }
        return "${str(label)}: ${coverIsolate(text, ui)}"
    }

    // ---------- the officer ----------

    private suspend fun officerTurn(myRun: Int, said: String, answer: Boolean = true) = guarded(myRun) { lockedTurn(myRun, said, answer) }

    /** One officer reply. Serialized: a silence timeout and a spoken answer must not both talk to the model. */
    private suspend fun lockedTurn(myRun: Int, input: String, isAnswer: Boolean) = turnLock.withLock {
        awaitForeground()
        if (myRun != run) return@withLock
        thalassiaDesk?.let { script ->
            if (deskSpoken) {
                script.answer(if (isAnswer) input else "")
                tip = null
                // Speaking past "your passport, please?" counts as handing it over: no stray button later.
                if (script.turn > 0 || script.passed) passportGiven = true
            }
            if (script.fatherAway) {
                spoken = false; clockAt = 0L; silenceDue = 0L; resumeListening = false
                silenceJob?.cancel(); hints = emptyList()
                val done = CompletableDeferred<Unit>()
                fatherAwayDone = done; fatherAway = true
                try { done.await(); awaitForeground() } finally {
                    fatherAway = false; fatherAwayDone = null
                }
                if (myRun != run) return@withLock
                script.finishFatherAway()
            }
            deskSpoken = true
            spoken = false; shown = true; clockAt = 0L; silenceDue = 0L; lookAsked = false
            officerText = script.line; officerEn = str(script.subtitle); question = script.line
            hints = script.suggestions.take(2).map { Hint(it, "", "") }
            face = if (script.passed) Face.APPROVING else Face.NEUTRAL
            if (script.passed) mind.settle(Decision.PASS)
            speak(myRun, script.line)
            return@withLock
        }
        if (passportGiven && passportAsked && room == null && !input.startsWith(HANDOVER)) {
            dbg("turn skipped: handover pending")
            return@withLock
        }
        playerAnswered = true // before the small-talk waits below: a late suggestion retry must not delay the turn
        val wasSmallTalk = heldHandover != null
        val said = smallTalk(myRun, input, isAnswer) ?: return@withLock // a small-talk question was asked instead
        awaitSmallTalkOdd(myRun, "interview")
        awaitForeground()
        if (myRun != run) return@withLock
        val answer = judgeInterviewAnswer(isAnswer, wasSmallTalk, said.startsWith(HANDOVER))
        history += "Traveler: $said"
        if (said.startsWith(HANDOVER)) passportAsked = false
        room?.let { roomTurn(myRun, it, said, answer); return@withLock }
        pendingRetina?.await() // the hints call must finish before the model takes the next one
        awaitForeground()
        if (myRun != run) return@withLock
        pendingRetina = null
        mic = Mic.PROCESSING
        // Judge this answer before the officer replies, so a slip is pressed on at once ("Two weeks? You said five
        // days."). Costs ~1.6-2 s, which reads as the officer weighing the answer.
        val a = analyst
        var reasking = false // "Say that again?": the check flagged this answer for the first time at this desk
        val offeredBefore = mind.translatorOffered
        val askedFact = desk.topic?.let {
            when (it) { ENCOUNTER_TOPIC, "arrivalnews" -> FactId.ENCOUNTER; in OFFICER_TOPICS -> topicFact(it); else -> null }
        }
        if (answer && a != null && question.isNotEmpty()) a.judge(question, said, askedFact,
            noFact = desk.topic != null && desk.topic != ENCOUNTER_TOPIC && desk.topic != "arrivalnews" && desk.topic !in OFFICER_TOPICS,
            previous = askedBefore.takeIf { it.first == myRun }?.second).let { v ->
            awaitForeground()
            if (myRun != run) return@withLock
            val before = mind.integrity
            if (mind.reask(v.check)) { reasking = true; dbg("reask: ${v.check}"); return@let }
            mind.onCheck(v.check, v.fact, said)
            integrityNote(myRun, before)
            if (v.check.answered && !v.check.contradiction && !v.check.odd && v.check.inLanguage && v.check.clarity > 0)
                v.fact?.let { if (it != FactId.NAME_BORN || desk.topic == "born") covered += it }
            dbg("judge ${v.ms}ms fact=${v.fact} ${v.check} raw=\"${v.raw}\" step2=\"${v.step2}\" step3=\"${v.step3}\" -> ${mind.state()}")
            dbg("mind ${mind.delta}")
        } else if (answer) mind.onCheck(Check.UNCLEAR)
        if (!answer && mind.emptyPressed && question.isNotEmpty() && mind.decision == null &&
            desk.lastQuestion?.startsWith("exact.") != true) {
            fun press(l: String) = renderLine("dodge.curt.1", l, story.gender, story.days, "").orEmpty()
            officerText = "${press(destination.lang)} $question"
            officerEn = "${press(sub)} $officerEn"
            spoken = false; shown = true; clockAt = 0L; lookAsked = false
            history += "Officer: $question"
            speak(myRun, officerText)
            startClock(myRun)
            return@withLock
        }
        if (!offeredBefore && mind.translatorOffered && mind.decision == null) {
            // The second answer it couldn't use: the officer points at the phone instead of asking again; the question stands.
            mind.takeCues()
            val own = ownLanguage()
            translatorEars = ownEars()
            translatorOffered(myRun)
            awaitForeground()
            if (myRun != run) return@withLock
            val (line, english) = fixedLine("translator.offer.1")
            face = mind.face()
            officerText = line; officerEn = english
            spoken = false; shown = true; clockAt = 0L; lookAsked = false
            history += "Officer: $line"
            dbg("translator offered own=$own ears=$translatorEars ${mind.state()}")
            speak(myRun, line)
            startClock(myRun)
            return@withLock
        }
        if (reasking) {
            val (line, english) = fixedLine("reask.1")
            officerText = line; officerEn = english
            spoken = false; shown = true; clockAt = 0L; lookAsked = false
            history += "Officer: $line"
            speak(myRun, line)
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
            if (mind.crashed) tts?.run { setPitch(0.55f); setSpeechRate(0.7f) } // winding down
            history += "Officer: $line"
            dbg("ending $d | $line")
            speak(myRun, line)
            return@withLock
        }
        val cues = mind.takeCues() + if (!answer && mind.emptyPressed) listOf(Cue.UNCLEAR) else emptyList()
        val m = desk.next(cues, mind.cueFact, covered, passportPending = deskSpoken && !passportGiven, first = !deskSpoken,
            handover = said.startsWith(HANDOVER), curt = mind.patience < 0.35)
        fun one(it: String, l: String) = if (it == "ask.$ENCOUNTER_TOPIC" && !homeArrivalTraining) story.encounter?.question(l, story.gender).orEmpty() else renderLine(it, l, story.gender, story.days, LANGUAGE_NAMES[l]?.get(destination.lang).orEmpty(),
                country = story.earlierStamps.firstOrNull()?.let { coverNation(it.substringBeforeLast(' '), l) }.orEmpty()).orEmpty()
        fun render(l: String, parts: List<String> = listOfNotNull(m.prefix, m.question)) = parts.joinToString(if (l == "ja" || l == "zh") "" else " ") { one(it, l) }
        val line = render(destination.lang); val subtitle = render(sub)
        // What the judge, the previous-question check and the hint history get: never a taint statement (judgedParts).
        val judged = render(destination.lang, judgedParts(m))
        val source = if (training && !deskSpoken && introTip != null) OfficerVoiceSource.TRAINING_TIP else OfficerVoiceSource.DESK
        val voice = brain?.let { OfficerVoice(Nano.oneShot(it)).rewrite(line, render("en"), destination.lang,
            temperament, enabled = officerPersonality, source = source, act1 = storyAct,
            playerNames = listOfNotNull(identity?.name, identity?.latin).flatMap { listOf(it) + it.split(' ') }) }
        awaitForeground()
        if (myRun != run) return@withLock
        val spokenLine = if (officerPersonality) voice?.line ?: line else line
        voice?.let { dbg("officer voice temperament=$temperament reason=${it.reason} latency=${it.ms}ms") }
        deskSpoken = true
        if (!m.reask) mind.onQuestion(if (desk.topic == "arrivalnews") ENCOUNTER_TOPIC else desk.topic)
        if (homeArrivalTraining && desk.topic == "phone" && !m.reask)
            tip = str(R.string.tip_mention_implant)
        val fact = desk.topic?.takeIf { it in OFFICER_TOPICS }?.let(::topicFact)
        if (fact != null && (askedNow.first != myRun || askedNow.second?.second != fact)) { askedBefore = askedNow; askedNow = myRun to (judged to fact) }
        question = judged
        asked = isQuestion(question, destination.lang)
        officerText = spokenLine; officerEn = subtitle; askedEn = subtitle to one(m.question, sub)
        speak(myRun, spokenLine)
        history += "Officer: $question"
        dbg("officer move=${m.prefix ?: "-"}+${m.question} cues=$cues face=$face ${mind.state()} | $line | $subtitle")
        if (liveContract == Contract.UNAIDED) {
            shown = true; startClock(myRun)
            return@withLock
        }
        if (a == null || mind.decision != null) return@withLock
        val trap = !training && Random.nextDouble() < trapChance(mind.p.level)
        playerAnswered = false
        pendingRetina = scope.async(Dispatchers.Default) {
            val ep = a.suggest(history, trap, remember = story.askedEncounter?.truth?.takeIf { desk.topic == ENCOUNTER_TOPIC && !trap }, answered = { playerAnswered })
            if (myRun == run) {
                hints = recallHints(ep.options.map(Models::option), implantPerks, desk.topic, story, destination.lang, ::recallFact)
                hintsNone = hints.isEmpty()
                shown = true; startClock(myRun)
                // Training order: the officer's first line asks for the passport, so that comes before speaking.
                if (training && introTip != null && tip == introTip)
                    tip = if (!passportGiven) str(R.string.tip_passport) else speakTip()
            }
            dbg("retinal display ${ep.turn.totalMs}ms trap=$trap")
            dbg("hints | " + ep.options.map(Models::option).joinToString(" | ") { "${it.target} / ${it.english}" })
            if (ep.log.isNotEmpty()) dbg("hints rejected | " + ep.log)
            ep
        }
    }

    /**
     * Small talk, under [turnLock]: one fixed baseline question before the trip facts, within the desk
     * answer cap. Silence re-asks; the answer releases the held handover to start the trip questions.
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
        awaitForeground()
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
        if (liveContract == Contract.UNAIDED) {
            shown = true; startClock(myRun)
            return
        }
        val a = analyst ?: return
        val seen = history + talkLines // never in [history]: the interview's prompts stay as they were
        playerAnswered = false
        pendingRetina = scope.async(Dispatchers.Default) {
            val ep = nanoLock.withLock { a.suggest(seen, smallTalk = true, answered = { playerAnswered }) }
            if (myRun == run) { hints = ep.options.map(Models::option); hintsNone = hints.isEmpty(); shown = true; startClock(myRun) }
            dbg("retinal display ${ep.turn.totalMs}ms smalltalk")
            dbg("hints | " + ep.options.map(Models::option).joinToString(" | ") { "${it.target} / ${it.english}" })
            if (ep.log.isNotEmpty()) dbg("hints rejected | " + ep.log)
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

    private fun officerRobot() = if (act == Act.FREE) Piper.ANDROID_V2 else Piper.ANDROID // the finale mission's own interrogator keeps the old unit

    @Synchronized private fun speak(myRun: Int, text: String, onDone: () -> Unit = { officerFinished(myRun) }) {
        if (myRun != run) return // a streamed line from an interview that was already stopped
        pendingSpeech = Speech(myRun, text, onDone)
        resumeListening = false
        val serial = ++speechSerial
        val mouthToken = officerMouth.begin()
        val animate = Motion.allowed()
        mic = Mic.OFFICER_SPEAKING
        if (backgrounded) return
        val finished = { finishSpeech(myRun, serial, onDone) }
        listening = false
        silenceJob?.cancel()
        speakJob?.cancel()
        if (piper != null) {
            val broken = mind.crashed && !wasInRoom // winding down: slower, ring-modulated (never the associate)
            speakJob = piperSay({ piper.takeIf { myRun == run } }, text, if (broken) 0.7f else if (wasInRoom) 1f else 1.1f, if (broken) Piper.CRASH else officerRobot(), piperPitch, if (animate) { level -> officerMouth.level(mouthToken, level) } else null, finished)
            return
        }
        val t = tts ?: return finished() // no voice at all: the text is on screen, go on without the wait
        t.setSpeechRate(if (mind.crashed && !wasInRoom) 0.7f else if (wasInRoom) 1f else 1.1f)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { if (animate && id == serial.toString()) officerMouth.systemStart(mouthToken, scope, text.length) }
            override fun onDone(id: String?) { if (id == serial.toString()) finished() }
            override fun onStop(id: String?, interrupted: Boolean) { if (id == serial.toString()) officerMouth.end(mouthToken) }
            @Deprecated("") override fun onError(id: String?) { if (id == serial.toString()) finished() }
        })
        if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, serial.toString()) == TextToSpeech.ERROR) finished()
    }

    @Synchronized private fun finishSpeech(myRun: Int, serial: Long, done: () -> Unit) {
        if (backgrounded || myRun != run || serial != speechSerial) return
        fatherAwayDone?.cancel(); fatherAwayDone = null; fatherAway = false
        officerMouth.stop()
        pendingSpeech = null
        done()
    }

    /**
     * One Piper synthesis under [voiceLock]: sherpa-onnx must never generate twice at once on an instance or be released
     * mid-generate. Cancelling the job only mutes the rest; the lock is held until the native call returns.
     */
    private fun piperSay(pick: () -> com.k2fsa.sherpa.onnx.OfflineTts?, text: String, speed: Float, robot: Float, pitch: Float = 1f, onLevel: ((Float) -> Unit)? = null, onDone: () -> Unit) =
        scope.launch(Dispatchers.Default) {
            voiceLock.withLock {
                val p = pick() ?: return@withLock
                val j = Piper.speak(this@launch, p, text, speed, robot, pitch, onLevel, onDone)
                withContext(NonCancellable) { j.join() }
            }
        }

    @Synchronized private fun officerFinished(myRun: Int) {
        if (myRun != run) return
        if (liveAttempt?.terminal != null) return
        mind.decision?.let { d ->
            // Assessment decisions are captured by roomTurn, not by a late desk speech callback.
            if (!wasInRoom) captureAttemptDecision(myRun, d)
            // A story pass leads to the building test; an act-two referral brings the associate.
            // A refused desk is a failed mission.
            if (!wasInRoom) // the referral meter at the desk's end, for tuning (results.log, plan phase 3 task 4)
                dbg("desk $d: meter=%.2f suspicion=%.2f contradictions=%d implant=%d/%d crashed=${mind.crashed}"
                    .format(mind.meter, mind.suspicion, mind.contradictions, mind.implantPassing, mind.implantDetailed))
            if (entersRoom(d, wasInRoom, storyMission, liveAttempt?.context?.training == true))
                return enterRoom(myRun)
            if (wasInRoom && room == null) return // the desk's PASS while the room is still being set up
            val last = finalLine(d).first
            if (officerText != last) { // safety net: the decision landed after a question was already spoken
                scope.launch(Dispatchers.Default) { officerTurn(myRun, "[decision]", answer = false) }
                return
            }
            saveTerminal(myRun) {
                val finale = pendingEnding(career.snapshot?.receipt) == Screen.FINALE
                ending = d; tip = null
                val crashed = mind.crashed
                hapticPlayer.play(when { crashed -> HapticEvent.BREAK; d.cleared -> HapticEvent.STAMP_PASS; else -> HapticEvent.STAMP_DETAIN })
                if (!crashed && !d.cleared) { sfx.play(R.raw.sfx_fail); scope.launch { delay(250); sfx.play(R.raw.sfx_door) } }
                else if (crashed) sfx.play(R.raw.sfx_scan, Sfx.SCAN_GAIN) else sfx.play(R.raw.sfx_stamp)
                // Lifecycle stop may invalidate speech's run while the durable result still owns navigation.
                scope.launch { delay(2800); finishTerminal(if (finale) Screen.FINALE else Screen.RESULT) }
            }
            return
        }
        awaitAnswer(myRun)
    }

    /** The officer has spoken and waits for the answer: the timers and the mic. */
    @Synchronized private fun awaitAnswer(myRun: Int, resuming: Boolean = false) {
        if (myRun != run || coachedWindow) return
        if (backgrounded) { resumeListening = true; return }
        if (forced != null) return settleForced(myRun)
        mic = if (fragment != null && inputMode == InputMode.AUTO) Mic.HEARING else Mic.READY
        if (!resuming && canBluff) { announceBluff(); return }
        if (!spoken) spokenAt = interviewClock.now()
        spoken = true
        if (clockAt != 0L) armSilence(myRun, preserveDeadline = resuming) // a replay: the officer repeated the question, so the wait restarts
        else startClock(myRun)
        if (inputMode == InputMode.AUTO) listenAuto(myRun, follow = fragment != null)
    }

    @Synchronized private fun startClock(myRun: Int) {
        if (backgrounded || coachedWindow || !spoken || !shown || clockAt != 0L || myRun != run || mind.decision != null) return
        clockAt = interviewClock.now()
        answerAt = faceWatch.now() // the officer watches from the moment the traveler could start answering
        armSilence(myRun)
    }

    /** Thinking time so far; zero if the player answers before the suggestions even appeared. */
    private fun thinkMs(at: Long = interviewClock.now()) =
        if (room != null) Assessment.thinkMs(if (spoken) spokenAt else 0L, clockAt, at) // a jam's delay never counts
        else if (clockAt == 0L) 0L else ((at - clockAt) / 1_000_000).coerceAtLeast(0)

    /** The invisible answer timer. No UI: the officer just speaks up when their patience for silence runs out. */
    @Synchronized private fun armSilence(myRun: Int, preserveDeadline: Boolean = false) {
        silenceJob?.cancel()
        if (backgrounded || (training && !passportGiven)) return // don't nag while the trainee looks for the passport button
        val window = room?.answerMs ?: mind.silenceDeadlineMs()
        if (!preserveDeadline || silenceDue == 0L) silenceDue = interviewClock.now() + window * 1_000_000
        silenceJob = scope.launch(Dispatchers.Default) {
            delay(((silenceDue - interviewClock.now()) / 1_000_000).coerceAtLeast(0))
            if (backgrounded || holding || mic == Mic.HEARING || myRun != run) return@launch
            listening = false
            val scriptTurn = thalassiaDesk?.turn
            val turns = mind.turns + talkThink.size
            listenJob?.join()
            // The phrase had started: recordVad returned it and it was answered (or is still being heard).
            if (backgrounded || myRun != run || mic == Mic.HEARING || mind.turns + talkThink.size != turns) return@launch
            if (thalassiaDesk != null) {
                if (thalassiaDesk?.turn != scriptTurn || mind.decision != null) return@launch
                scope.launch(Dispatchers.Default) { officerTurn(myRun, "", answer = false) }
                return@launch
            }
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

    /** The player slides the passport across. The officer checks its holder. */
    fun handOverPassport() {
        if (passportGiven || mind.decision != null || mic == Mic.HEARING) return
        passportGiven = true
        dbg("passport handed think=${thinkMs()}ms")
        // The Thalassian officer asked for the passport (owner: the button didn't work there): handing it over is the answer.
        if (thalassiaDesk != null) {
            silenceJob?.cancel(); listening = false
            sfx.play(R.raw.sfx_scan, Sfx.SCAN_GAIN); hapticPlayer.play(HapticEvent.SCAN)
            val myRun = run
            scope.launch(Dispatchers.Default) { listenJob?.join(); officerTurn(myRun, "[passport]") }
            return
        }
        // The cancel stops a silence timer still in delay() or listenJob.join(), so it can't reach onPause/onSilence for
        // the passport request; only a handler already past its last suspension point (microseconds) still runs.
        silenceJob?.cancel(); listening = false
        sfx.play(R.raw.sfx_scan, Sfx.SCAN_GAIN)
        hapticPlayer.play(HapticEvent.SCAN)
        if (training) tip = speakTip()
        val myRun = run
        scope.launch(Dispatchers.Default) {
            listenJob?.join()
            val holder = if (story.kind == TripKind.ARRIVAL) "a ${story.nationality.name} passport, its holder arriving for a visit"
                else "a ${story.country.nation} passport, its holder coming home"
            officerTurn(myRun, "$HANDOVER You scan it: $holder.]", answer = false)
        }
    }

    @Synchronized fun press() {
        if (backgrounded || inputMode != InputMode.HOLD || mic != Mic.READY) return
        holding = true
        silenceJob?.cancel()
        val myRun = run
        val thinkMs = thinkMs()
        mic = Mic.HEARING
        capturing = true
        listenJob = scope.launch(Dispatchers.Default) {
            val pcm = vadLock.withLock { currentCoroutineContext().ensureActive(); record { holding } }
            val captureContext = currentCoroutineContext()
            synchronized(this@Game) {
                captureContext.ensureActive()
                capturing = false
                if (pcm != null) mic = Mic.PROCESSING
            }
            if (pcm == null) { awaitForeground(); mic = Mic.READY; if (clockAt != 0L) armSilence(myRun) } else utterance(myRun, pcm, thinkMs)
        }
    }

    fun release() { holding = false }

    /** [follow]: a [fragment] is waiting for the rest of the phrase; nothing within [FOLLOW_MS] sends it as it is. */
    @Synchronized private fun listenAuto(myRun: Int, follow: Boolean = false) {
        if (myRun != run) return
        if (backgrounded) { resumeListening = true; return }
        capturing = true
        val listenAt = System.nanoTime()
        listening = true
        listenJob = scope.launch(Dispatchers.Default) {
            if (!listening || myRun != run) return@launch
            var speechAt = 0L
            var openAt = 0L
            var speechAfterMs = 0L
            val captureContext = currentCoroutineContext()
            val pcm = recordVad(if (follow) FOLLOW_MS else 0L, skipMs = if (follow) 0L else ECHO_SKIP_MS, onOpen = {
                synchronized(this@Game) {
                    captureContext.ensureActive()
                    openAt = System.nanoTime()
                    dbg("mic open ${ms(listenAt)}ms after listen")
                    if (myRun == run && mic != Mic.HEARING) mic = Mic.READY
                }
            }, onSpeech = {
                synchronized(this@Game) {
                    captureContext.ensureActive()
                    speechAt = interviewClock.now()
                    speechAfterMs = (System.nanoTime() - openAt) / 1_000_000
                    silenceJob?.cancel()
                    mic = Mic.HEARING
                }
            })
            synchronized(this@Game) {
                captureContext.ensureActive()
                capturing = false
                // A clip too short to transcribe is dropped in utterance(): keep the button as it was, no "…" blink.
                if (pcm != null && pcm.size >= 8000 || follow && fragment != null) mic = Mic.PROCESSING
            }
            if (pcm == null) {
                if (openAt == 0L && listening && myRun == run) mic = Mic.READY // mic denied or busy: show the tip
                if (follow) flushFragment(myRun)
                return@launch
            }
            utterance(myRun, pcm, thinkMs(speechAt), speechAfterMs)
        }
    }

    private val retryLock = Mutex()
    /**
     * Whisper for the retry: preloaded at each interview start (stopInterview releases it, ~1 GB off the menu) and
     * shared with a retry that comes first; null if [myRun] ended, before or during the load, or if the load failed.
     */
    private suspend fun retryRecognizer(myRun: Int): OfflineRecognizer? = retryLock.withLock {
        if (myRun != run) return@withLock null
        retryAsr?.takeIf { it.config.modelConfig.whisper.language == destination.lang }?.let { return@withLock it }
        val w = loadWhisper(destination.lang) ?: return@withLock null
        if (myRun != run) { w.release(); return@withLock null } // stopped during the load: don't keep it
        retryAsr?.release(); retryAsr = w
        dbg("retry whisper loaded")
        w
    }

    /**
     * Whisper as the primary recognizer for [lang] (no system one), kept between interviews. Under [retryLock] so the
     * cover preload and startInterview never load it twice; null (and released) if the destination changed meanwhile,
     * null if the load failed (the no-Whisper path: the system recognizer, or nothing heard).
     */
    private suspend fun primaryRecognizer(lang: String): OfflineRecognizer? = retryLock.withLock {
        if (destination.lang != lang) return@withLock null
        asr?.takeIf { it.config.modelConfig.whisper.language == lang }?.let { return@withLock it }
        asr?.release(); asr = null
        val w = loadWhisper(lang) ?: return@withLock null
        if (destination.lang != lang) { w.release(); return@withLock null } // another mission during the load: don't keep it
        dbg("whisper loaded ($lang)")
        w.also { asr = it }
    }

    /** Every Whisper load runs in launches outside [guarded]: a corrupt model (the constructor throws) is logged, never a crash. */
    private fun loadWhisper(lang: String): OfflineRecognizer? =
        try { Models.asr(ctx, "turbo", lang) } catch (e: Exception) { dbg("whisper load failed ($lang): $e"); null }

    /** The cover card of a slow language: load its Whisper now; a cold load at the first answer stalled it ~10 s. */
    fun preloadEars() {
        val lang = destination.lang
        if (!slowLang(lang) || !Models.has(ctx, Models.WHISPER)) return
        scope.launch(Dispatchers.Default) { runCatching { primaryRecognizer(lang) }.onFailure { dbg("whisper preload failed: $it") } }
    }

    /** An implant line the coach loop leaves alone for [ms]; cleared then unless a newer note took over. */
    private fun implantNote(myRun: Int, text: String, ms: Long = 6000) {
        implant = text
        hintUntil = interviewClock.now() / 1_000_000 + ms
        scope.launch { interviewDelay(ms); if (myRun == run && interviewClock.now() / 1_000_000 >= hintUntil) implant = null }
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
        if (pcm.size < 8000) { dbg("heard nothing: clip too short (${pcm.size / 16} ms)"); if (fragment != null) return flushFragment(myRun); mic = Mic.READY; if (clockAt != 0L) armSilence(myRun); if (inputMode == InputMode.AUTO) listenAuto(myRun); return }
        mic = Mic.PROCESSING
        if (translatorOn && translatorShown && mind.translatorCredits > 0 && fragment == null) return translated(myRun, pcm, thinkMs)
        val t0 = System.nanoTime()
        // Under the diagnostics lock: a recording never lands after the player turned the setting off and it was cleared.
        if (diagnostics) runCatching { synchronized(diagLock) { if (diagnostics) Models.writeWav(File(File(ctx.filesDir, "wavs").apply { mkdirs() }, "${destination.lang}_rec_${System.currentTimeMillis()}.wav"), pcm) } } // disk full: no recording, no crash
        // System recognizer when the language has one (~0.3 s after the clip), else Whisper (~1.5-3 s).
        var src = if (asr != null) "whisper" else "system"
        var text = asr?.let { Models.transcribe(it, pcm).text }
            ?: runCatching { Nano.transcribe(destination.lang, pcm, ::dbg, advanced = false)?.first }.getOrNull().orEmpty()
        if (text.isBlank() && asr == null && Models.has(ctx, Models.WHISPER) && myRun == run) {
            // The system recognizer sometimes returns nothing for real speech: one more try with Whisper.
            val w = retryRecognizer(myRun)
            if (w != null) {
                text = Models.transcribe(w, pcm).text; src = "retry"
                dbg("system recognizer heard nothing; Whisper retry: \"$text\"")
            }
            if (myRun != run) return // stopped during the (blocking) load or transcription
            // w == null with the run alive: Whisper failed to load; the empty transcript below hands the mic back.
        }
        awaitForeground()
        currentCoroutineContext().ensureActive() // cancelled mid-transcription (stopInterview, a debug [inject]): no answer
        if (transcriptKind(text) == TranscriptKind.EMPTY) {
            dbg("heard nothing: empty transcript (${pcm.size / 16} ms clip)")
            if (fragment != null) return flushFragment(myRun)
            mic = Mic.READY
            if (clockAt != 0L) armSilence(myRun, preserveDeadline = true)
            if (inputMode == InputMode.AUTO) listenAuto(myRun)
            return
        }
        if (isEcho(text, question, speechAfterMs)) {
            dbg("echo ignored (${speechAfterMs}ms after the mic opened): $text")
            if (fragment != null) return flushFragment(myRun)
            mic = Mic.READY
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
        if (inputMode == InputMode.AUTO && transcriptKind(said) == TranscriptKind.FRAGMENT && all.size < FRAGMENT_MAX_S * 16000) {
            dbg("fragment, listening on: \"$said\"")
            fragment = Fragment(all, said, think)
            mic = Mic.HEARING // holds the officer's silence timer; FOLLOW_MS of nothing sends it
            listenAuto(myRun, follow = true)
            return
        }
        answer(myRun, all, said, think)
    }

    private suspend fun answer(myRun: Int, pcm: FloatArray, text: String, thinkMs: Long, source: String = "") {
        awaitForeground()
        if (myRun != run) return
        heard = text; translatorSource = source
        if (thalassiaDesk != null) return officerTurn(myRun, text)
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
            // "Here it is." a beat after the passport button answers the passport, not the small talk: never ODD.
            val late = isHandoverReply(text, destination.lang)
            if (late) dbg("smalltalk odd skipped: late passport line")
            if (text.any(Char::isLetter) && b != null && mind.decision == null && forced == null && !late) {
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
                        Nano.oneShot(b)(smallTalkOddPrompt(destination0), "Officer asked: \"$q\"\nTraveler answered: \"${unfield(text)}\"", 12)
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
            mind.onSmallTalkAnswer()
            return officerTurn(myRun, text, answer = text.any(Char::isLetter))
        }
        val words = text.split(Regex("\\s+")).count { it.any(Char::isLetter) }
        val a = room
        // Neither stage judges an answer nobody heard: one pressed re-ask, then the silence path.
        // In the room the fixed "could you repeat that" line takes the same path, unscored and not pressed.
        val repeat = a != null && isRepeatRequest(text, destination.lang)
        if (words == 0 || repeat) {
            val again = if (a != null) a.onEmpty(press = !repeat) else mind.onEmpty()
            if (a != null && !again) mind.onSilence()
            dbg("${if (a != null) "room" else "desk"} ${if (repeat) "repeat request" else "heard nothing"} -> ${if (again) "re-ask" else "silence"}")
            return officerTurn(myRun, "[The traveler says nothing.]", answer = false)
        }
        // Whatever Whisper heard goes to the officer as-is; mangled speech is the player's problem.
        mind.onAnswer(Delivery(thinkMs, pcm.size / 16000.0, text))
        dbg("mind ${mind.delta}")
        a?.onAnswer(thinkMs, words, mind.pace)
        if (watching) profile(myRun)
        if (training) tip = if (!passportGiven) str(R.string.tip_passport)
            else if (chatbotTipDue(thalassianDesk, mind.goodAnswers, prefs.getBoolean("tipChatbot", false))) {
                prefs.edit { putBoolean("tipChatbot", true) }; str(R.string.tip_chatbot)
            } else str(R.string.tip_consistent)
        dbg("you think=${thinkMs}ms | $text")
        officerTurn(myRun, text)
    }

    /** The player's own language for the phone translator: the UI language, English when the game has no such language. */
    private fun ownLanguage() = uiLanguage(ctx).let { l -> if (Language.all.any { it.code == l }) l else "en" }

    /** The translator's mic: the clip in the player's own language, through the system recognizer (as the city scenes hear). */
    private suspend fun translated(myRun: Int, pcm: FloatArray, thinkMs: Long) {
        val own = ownLanguage()
        if (diagnostics) runCatching { synchronized(diagLock) { if (diagnostics) Models.writeWav(File(File(ctx.filesDir, "wavs").apply { mkdirs() }, "${own}_translate_${System.currentTimeMillis()}.wav"), pcm) } }
        val text = runCatching { Nano.transcribe(own, pcm, ::dbg, advanced = false)?.first }.getOrNull().orEmpty()
        currentCoroutineContext().ensureActive()
        translate(myRun, pcm, text, thinkMs)
    }

    /**
     * Nano translates [text] into the officer's language, the phone reads it out, and it goes to the officer as a spoken
     * answer would (judged the same, owner). Spends a credit; nothing heard or nothing translated spends none.
     */
    private suspend fun translate(myRun: Int, pcm: FloatArray, text: String, thinkMs: Long) {
        val b = brain
        playerAnswered = true // the hints call skips its retry
        pendingRetina?.await() // the hints call must finish before the model takes the next one
        val t0 = System.nanoTime()
        val raw = if (b == null || transcriptKind(text) == TranscriptKind.EMPTY) "" else runCatching {
            nanoLock.withLock { Nano.oneShot(b)(translatorPrompt(ownLanguage(), destination.lang), unfield(text), 120) }
        }.getOrDefault("")
        currentCoroutineContext().ensureActive()
        awaitForeground()
        if (myRun != run) return
        val line = translatorLine(raw, destination.lang)
        if (line.isEmpty() || !mind.useTranslator()) {
            dbg("translator: nothing to say ${(System.nanoTime() - t0) / 1_000_000}ms | $text | raw=\"$raw\"")
            mic = Mic.READY
            if (clockAt != 0L) armSilence(myRun, preserveDeadline = true)
            if (inputMode == InputMode.AUTO) listenAuto(myRun)
            return
        }
        translatorUsed(myRun)
        if (mind.translatorCredits == 0) translatorOn = false
        dbg("translator ${(System.nanoTime() - t0) / 1_000_000}ms credits=${mind.translatorCredits} | $text -> $line")
        translatorSource = text; heard = line
        speakPhone(line)
        awaitForeground()
        if (myRun != run) return
        answer(myRun, pcm, line, thinkMs, source = text)
    }

    /** A recognizer for the player's own language is there, and the model is loaded: the translator's switch can show. */
    private suspend fun ownEars() = ownLanguage().let { own ->
        brain != null && own != destination.lang && runCatching { Nano.basicAsr(own) == FeatureStatus.AVAILABLE }.getOrDefault(false)
    }

    @Synchronized private fun translatorOffered(myRun: Int) {
        liveAttempt = liveAttempt?.takeIf { it.run == myRun && it.terminal == null }?.copy(translatorOffered = true) ?: liveAttempt
    }

    @Synchronized private fun translatorUsed(myRun: Int) {
        liveAttempt = liveAttempt?.takeIf { it.run == myRun && it.terminal == null }?.copy(translatorUsed = true) ?: liveAttempt
    }

    /**
     * The phone reads its translation in the officer's language: the system voice, another one than the officer's where
     * there is one, higher and faster (flat, synthetic). The desk voice is put back after; [speak] sets its own listener.
     */
    private suspend fun speakPhone(line: String) {
        val t = tts ?: return
        val done = CompletableDeferred<Unit>()
        try {
            Voices.offline(t, destination.lang).firstOrNull { it != t.voice }?.let { t.voice = it }
            t.setPitch(PHONE_PITCH); t.setSpeechRate(PHONE_RATE)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { done.complete(Unit) }
                @Deprecated("") override fun onError(id: String?) { done.complete(Unit) }
            })
            if (t.speak(line, TextToSpeech.QUEUE_FLUSH, null, "phone") == TextToSpeech.ERROR) done.complete(Unit)
            kotlinx.coroutines.withTimeoutOrNull(15_000) { done.await() }
        } finally {
            if (!done.isCompleted) t.stop()
            Voices.apply(ctx, t, destination.lang, Country.isFemale(officerId), officerId)
        }
    }

    /** A started recorder, or null (and a tip) when the microphone is denied or busy. */
    @Synchronized private fun newRecorder(): Pair<AudioRecord, Int>? {
        if (backgrounded) return null
        micDenied = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (micDenied) { tip = str(R.string.tip_mic); return null }
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        var ar: AudioRecord? = null
        try {
            @Suppress("MissingPermission")
            ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT, max(min, 480 * 4) * 4)
            check(ar.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
            hapticPlayer.capture(true)
            ar.startRecording()
            if (tip == str(R.string.tip_mic)) tip = null
            return ar to min
        } catch (e: Exception) { // IllegalStateException, SecurityException, IllegalArgumentException
            dbg("mic failed: $e")
            try { ar?.release() } finally { hapticPlayer.capture(false) }
            tip = str(R.string.tip_mic)
            return null
        }
    }

    private suspend fun record(keepGoing: () -> Boolean): FloatArray? {
        val recordingContext = currentCoroutineContext()
        recordingContext.ensureActive()
        val (ar, min) = newRecorder() ?: return null
        val out = ArrayList<FloatArray>()
        val buf = FloatArray(min)
        val gain = MicGain()
        try {
            while (keepGoing()) {
                recordingContext.ensureActive()
                val n = ar.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                recordingContext.ensureActive()
                if (n > 0) out += gain.apply(buf.copyOf(n))
            }
            dbg("mic: ${gain.range()}")
            return concat(out)
        } finally { closeRecorder(ar) }
    }

    private fun closeRecorder(ar: AudioRecord) {
        try { ar.stop() } finally {
            try { ar.release() } finally { hapticPlayer.capture(false) }
        }
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
            if (diagnostics) { ring.addLast(f.copyOf()); if (ring.size > cap) ring.removeFirst() } // raw: the caller boosts f next
        }
        fun report(gain: MicGain): String {
            val wav = if (!diagnostics) null else runCatching {
                synchronized(diagLock) { if (!diagnostics) null else File(File(ctx.filesDir, "wavs").apply { mkdirs() }, "${destination.lang}_nospeech_${System.currentTimeMillis()}.wav")
                    .also { Models.writeWav(it, concat(ring)) }.name }
            }.getOrNull()
            return "frames=%d peak=%.4f rms=%.4f maxFrameRms=%.4f gain=%.1f silenced=%s/%s".format(frames, peak,
                if (samples > 0) sqrt(sumSq / samples) else 0.0, maxFrameRms, gain.gain, silenced1, silenced()) + (wav?.let { " wav=$it" } ?: "")
        }
    }

    /**
     * Auto mode: listen until a phrase starts and then ends (Silero VAD, the energy VAD if it can't load). Returns null
     * if listening was cancelled first. Reuse one lazy Vad, resetting its state and queued segments for each listen.
     * [on]: keep listening (the desk's [listening]; the city scenes pass their own flag).
     */
    private suspend fun recordVad(startWithinMs: Long, skipMs: Long, onOpen: () -> Unit, onSpeech: () -> Unit, on: () -> Boolean = { listening }): FloatArray? = vadLock.withLock {
        val recordingContext = currentCoroutineContext()
        recordingContext.ensureActive()
        if (vadClosed || !on()) return@withLock null
        val vad = if (sileroFailed) null else try {
            sileroVad ?: Vad(ctx.assets, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(model = "models/silero_vad.int8.onnx",
                minSilenceDuration = 1.4f, minSpeechDuration = 0.25f, maxSpeechDuration = 15f), sampleRate = 16000, numThreads = 1)).also { sileroVad = it }
        } catch (e: Throwable) { sileroFailed = true; dbg("vad: Silero failed, energy VAD instead: $e"); null }
        if (vad == null) return@withLock recordEnergyVad(startWithinMs, skipMs, onOpen, onSpeech, on)
        vad.reset()
        vad.clear() // discard any completed segment left by the previous listen
        recordingContext.ensureActive()
        val (ar, _) = newRecorder() ?: return@withLock null
        val frame = FloatArray(512) // 32 ms, Silero's window
        val preRoll = ArrayDeque<FloatArray>()
        val out = ArrayList<FloatArray>()
        val gain = MicGain()
        var started = false
        var t0 = System.nanoTime()
        try {
            // audio, not time: a stalled thread still drops the queued tail
            var skipped = 0L
            while (on() && skipped < skipMs * 16) {
                recordingContext.ensureActive()
                val n = ar.read(frame, 0, minOf(frame.size.toLong(), skipMs * 16 - skipped).toInt(), AudioRecord.READ_BLOCKING)
                recordingContext.ensureActive()
                if (n > 0) skipped += n
            }
            if (!on()) return@withLock null
            recordingContext.ensureActive()
            t0 = System.nanoTime()
            onOpen()
            val heard = Heard(ar, frame.size, t0)
            while (on() && (started || startWithinMs == 0L || ms(t0) < startWithinMs)) {
                recordingContext.ensureActive()
                val n = ar.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                recordingContext.ensureActive()
                if (n <= 0) continue
                val f = frame.copyOf(n)
                if (!started) heard.add(f) // raw levels, comparable across builds
                noteVoice(f)
                vad.acceptWaveform(gain.apply(f)) // the boosted frame is also what pre-roll and the clip keep
                if (!started) {
                    // Silero confirms speech only after minSpeechDuration: 0.8 s of pre-roll keeps the first syllable.
                    preRoll.addLast(f); if (preRoll.size > 25) preRoll.removeFirst()
                    if (vad.isSpeechDetected() || !vad.empty()) { started = true; out.addAll(preRoll); onSpeech() }
                } else {
                    out += f
                    if (!vad.empty() || out.size > 500) { dbg("mic: ${gain.range()}"); return@withLock concat(out) } // a finished segment: the phrase ended (16 s cap)
                }
            }
            if (started) { dbg("vad: listening stopped mid-phrase, ${gain.range()}"); return@withLock concat(out) } // the phrase still gets answered
            dbg("vad: no speech start in ${ms(t0)} ms ${heard.report(gain)}")
            return@withLock null
        } finally { closeRecorder(ar) }
    }

    /** When the open recorder last heard voice-like energy (nanoTime, raw frame): lookCheck never talks over a player starting to speak. */
    @Volatile private var voiceAt = 0L
    private fun noteVoice(f: FloatArray) {
        var sq = 0.0
        for (x in f) sq += x * x
        if (sqrt(sq / f.size) > VOICE_RMS) voiceAt = System.nanoTime()
    }

    /** The fallback: energy VAD with an adaptive noise floor. */
    private suspend fun recordEnergyVad(startWithinMs: Long, skipMs: Long, onOpen: () -> Unit, onSpeech: () -> Unit, on: () -> Boolean): FloatArray? {
        val recordingContext = currentCoroutineContext()
        recordingContext.ensureActive()
        val (ar, _) = newRecorder() ?: return null
        val frame = FloatArray(480) // 30 ms
        val preRoll = ArrayDeque<FloatArray>()
        val out = ArrayList<FloatArray>()
        val gain = MicGain()
        var floor = 0.004
        var loud = 0
        var started = false
        var quietMs = 0
        var t0 = System.nanoTime()
        try {
            var skipped = 0L
            while (on() && skipped < skipMs * 16) {
                recordingContext.ensureActive()
                val n = ar.read(frame, 0, minOf(frame.size.toLong(), skipMs * 16 - skipped).toInt(), AudioRecord.READ_BLOCKING)
                recordingContext.ensureActive()
                if (n > 0) skipped += n
            }
            if (!on()) return null
            recordingContext.ensureActive()
            t0 = System.nanoTime()
            onOpen()
            val heard = Heard(ar, frame.size, t0)
            while (on() && (started || startWithinMs == 0L || ms(t0) < startWithinMs)) {
                recordingContext.ensureActive()
                val n = ar.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                recordingContext.ensureActive()
                if (n <= 0) continue
                val f = frame.copyOf(n)
                // Decide on the raw frame: a floor ratio is SNR, which a gain can't change, but a gain that sits at max
                // in silence and drops on a loud onset would shrink it.
                val rms = sqrt(f.sumOf { (it * it).toDouble() } / n)
                if (rms > VOICE_RMS) voiceAt = System.nanoTime()
                if (!started) heard.add(f)
                gain.apply(f) // pre-roll and the clip carry the boosted audio
                if (!started) {
                    preRoll.addLast(f); if (preRoll.size > 10) preRoll.removeFirst()
                    if (rms > max(floor * VAD_START, VAD_MIN)) loud++ else { loud = 0; floor = 0.95 * floor + 0.05 * rms }
                    if (loud >= 5) { started = true; out.addAll(preRoll); onSpeech() }
                } else {
                    out += f
                    quietMs = if (rms > max(floor * VAD_KEEP, VAD_MIN * 0.75)) 0 else quietMs + 30
                    if (quietMs >= VAD_END_MS || out.size * 30 > 15_000) { dbg("mic: ${gain.range()}"); return concat(out) }
                }
            }
            if (started) { dbg("vad: listening stopped mid-phrase, ${gain.range()}"); return concat(out) }
            dbg("vad: no speech start (energy, floor=%.4f) in %d ms ".format(floor, ms(t0)) + heard.report(gain))
            return null
        } finally { closeRecorder(ar) }
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
     * `#pass` / `#fail` settle the current level: desk PASS (or REFERRAL, [refers]) / REFUSED, room RELEASED / REFUSED.
     * `adb shell am broadcast -a com.lampensoft.borderlinefluent.SAY -p com.lampensoft.borderlinefluent --es text "…"`
     */
    private val sayReceiver = if (!BuildConfig.DEBUG) null else object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { i.getStringExtra("text")?.let(::inject) }
    }.also { ctx.registerReceiver(it, IntentFilter("com.lampensoft.borderlinefluent.SAY"), Context.RECEIVER_EXPORTED) }

    private var simJob: Job? = null

    /**
     * Debug builds only: a synthetic face for testing face gameplay without one. `--es state` is off | neutral | blink |
     * smile | shake | nod | away | noface; real camera frames are dropped while a state is set. The camera setting must
     * be on (`watching`) and, for the implant head-shake, the CAMERA permission granted; the preview need not show.
     * `adb shell am broadcast -a com.lampensoft.borderlinefluent.FACE -p com.lampensoft.borderlinefluent --es state shake`
     */
    private val faceReceiver = if (!BuildConfig.DEBUG) null else object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val name = i.getStringExtra("state")
            val state = SimFace.of(name)
            simJob?.cancel(); simJob = null
            faceWatch.simulating = state != null
            dbg("face sim: ${if (state == null) "off ($name)" else state.name.lowercase()}")
            if (state != null) simJob = scope.launch(Dispatchers.Default) {
                while (true) { faceWatch.feed(simSample(state, faceWatch.now())); delay(66) }
            }
        }
    }.also { ctx.registerReceiver(it, IntentFilter("com.lampensoft.borderlinefluent.FACE"), Context.RECEIVER_EXPORTED) }

    /** Retinal-display taps share the adb SAY answer path, including its timing and readiness checks. */
    fun debugSay(text: String) { if (BuildConfig.DEBUG) inject(text) }

    @Synchronized private fun inject(raw: String) {
        if (!BuildConfig.DEBUG) return
        city?.takeIf { it.step == CityStep.READY || it.step == CityStep.HEARING }?.let { c ->
            dbg("say (city): $raw")
            cityJob?.cancel(); cityHolding = false // auto mode's open mic
            city = c.copy(step = CityStep.CHECKING)
            cityJob = scope.launch(Dispatchers.Default) { cityJudge(c, raw) }
            return
        }
        if (liveAttempt?.terminal != null || screen != Screen.INTERVIEW) return
        if (raw == "#pass" || raw == "#fail") {
            if (mind.decision != null || !deskSpoken && room == null) return dbg("say ignored: mic=$mic decision=${mind.decision} room=${room != null}")
            dbg("say: $raw")
            liveAttempt = liveAttempt?.let { forceAttempt(it, run) }
            forced = if (raw == "#pass") Decision.PASS else Decision.REFUSED
            // Waiting for an answer: settle now. Otherwise the line or turn under way finishes first ([awaitAnswer]).
            if (mic == Mic.READY || mic == Mic.HEARING) settleForced(run)
            return
        }
        // Waiting for an answer: the officer has finished (READY), or auto mode hears a phrase or holds a fragment.
        // The USA test skips the desk: the room alone has spoken.
        if ((mic != Mic.READY && mic != Mic.HEARING) || mind.decision != null || !deskSpoken && room == null)
            return dbg("say ignored: mic=$mic decision=${mind.decision} room=${room != null}")
        val text = if (raw == "#hint") hints.firstOrNull()?.target ?: return dbg("say ignored: no hint") else raw
        val translate = raw.startsWith("#translate ") // typed text in the player's own language, through the phone translator
        val myRun = run
        val think = thinkMs()
        silenceJob?.cancel(); listening = false; holding = false; fragment = null
        val old = listenJob?.apply { cancel() }
        capturing = false // the cancelled capture never clears it: pauseInterview would then cancel this turn's job
        mic = Mic.PROCESSING
        dbg("say: $text")
        listenJob = scope.launch(Dispatchers.Default) {
            old?.join()
            val pcm = FloatArray(max(16000, text.length * 70 * 16))
            if (myRun == run) if (translate) translate(myRun, pcm, text.removePrefix("#translate ").trim(), think) else answer(myRun, pcm, text, think)
        }
    }

    @Volatile private var forced: Decision? = null // debug `#pass` / `#fail`, applied once the officer waits

    /** Ends the level through the normal decided turn: the desk's last words, or the room's verdict. */
    private fun settleForced(myRun: Int) {
        val d = forced ?: return
        forced = null
        if (myRun != run || mind.decision != null) return
        silenceJob?.cancel(); listening = false; holding = false; fragment = null
        listenJob?.cancel(); capturing = false
        mic = Mic.PROCESSING
        room?.force(if (d == Decision.PASS) Decision.RELEASED else d) ?: mind.settle(d)
        scope.launch(Dispatchers.Default) { officerTurn(myRun, "[decision]", answer = false) }
    }

    private fun dbg(s: String) {
        if (diagnostics || debuggable) Log.i(TAG, s) // transcripts: not in a release build's logcat unless opted in
        // Debug builds always keep results.log: logcat overflows with AppOps noise from the camera and mic in minutes.
        // Queued, never blocking the caller (often the UI thread); consent is checked again when the line is written.
        if (diagnostics || debuggable) appendDiagnostic(ctx.filesDir, s) { diagnostics || debuggable }
    }

    /**
     * The officer's fixed last words (a referral hands over to the associate); the associate has its own release,
     * refusal (entry denied, flight home) and caught lines (the implant's data kept, escorted out). Never violent.
     */
    private fun finalLine(d: Decision): Pair<String, String> {
        val r = ROOM.getValue(destination.lang); val en = ROOM.getValue(sub)
        return when {
            thalassiaDesk != null -> thalassiaDesk!!.line to str(thalassiaDesk!!.subtitle)
            !wasInRoom && country.id == "usa" && d == Decision.REFERRAL -> fixedLine("usa.greet.1")
            !wasInRoom && d == Decision.REFERRAL && !mind.crashed -> fixedLine("refer.${1 + Math.floorMod(run, 2)}")
            !wasInRoom -> Language.of(destination.lang).lastWords(d, sub, story.kind, mind.crashed)
            storyMission -> storyTestVerdict(d, destination.lang) to storyTestVerdict(d, sub)
            d.cleared -> r.release to en.release
            d == Decision.CAUGHT -> fixedLine("associate.caught.1")
            story.kind == TripKind.HOMECOMING -> Language.of(destination.lang).lastWords(d, sub, story.kind) // home: no flight home
            else -> r.refuse to en.refuse
        }
    }

    /** A fixed [OFFICER_LINES] line in the desk's language and the subtitle language. */
    private fun fixedLine(id: String) =
        renderLine(id, destination.lang, story.gender, story.days, "").orEmpty() to renderLine(id, sub, story.gender, story.days, "").orEmpty()

    /** Live continuation for E5; process-death recovery uses the durable attempt preparation. */
    private var epilogueTestRun: Int? = null
    val epilogueDoses get() = career.snapshot?.epilogueDoses
    val epiloguePassed get() = career.snapshot?.epiloguePassed
        ?: (career.snapshot?.receipt?.outcome == ReplayOutcome.CLEARED)
    fun advanceEpilogue(frame: Int) {
        if (!careerReady || screen != Screen.FINALE) return
        saveCareer({ career.store.setEpilogueFrame(frame) }) {
            soundtrack()
            if (frame == 5) startEpilogueTest()
        }
    }
    fun startEpilogueTest() {
        if (!careerReady || screen != Screen.FINALE) return
        val token = epilogueTestRun
        if (token != null && token == run && liveAttempt != null) {
            epilogueTestRun = null
            screen = Screen.INTERVIEW
            activeFaceRun = token; refreshScanner()
            enterRoom(token, epilogueReady = true)
        } else {
            // Restore the frozen preparation and restart only the test, never the passed desk.
            screen = Screen.COVER // also exposes the existing missing-pack recovery if restoration fails
            continueInterrupted()
            if (screen == Screen.COVER && restoredAttempt != null) scope.launch {
                while (screen == Screen.COVER && brain == null) delay(100)
                if (screen == Screen.COVER && careerReady) startInterview()
            }
        }
    }

    private fun enterRoom(myRun: Int, epilogueReady: Boolean = false) {
        if (storyMission && !epilogueReady && career.snapshot?.epilogueFrame != 5) {
            captureAttemptDecision(myRun, Decision.PASS)
            silenceJob?.cancel(); listening = false; mic = Mic.OFF; hints = emptyList(); tip = null
            epilogueTestRun = myRun
            saveCareer({ career.store.setEpilogueFrame(career.snapshot?.epilogueFrame?.takeIf { it < 5 } ?: 0) }) {
                screen = Screen.FINALE
            }
            return
        }
        if (storyMission) captureAttemptDecision(myRun, Decision.PASS)
        mind.decision?.let { captureAttemptDecision(myRun, it) }
        // A crashed desk officer: undo the winding-down voice (the associate's own voice replaces it when its art ships).
        if (mind.crashed) tts?.let { Voices.apply(ctx, it, destination.lang, Country.isFemale(officerId), officerId); it.setSpeechRate(1f) }
        wasInRoom = true
        if (storyMission) machineView = true
        silenceJob?.cancel(); listening = false; mic = Mic.OFF; hints = emptyList(); tip = null; forced = null; heard = ""; translatorSource = ""; translatorOn = false // the desk's last answer must not linger in the test
        // Probes from the desk's referral meter, which already holds the light face tells (camera off: same table).
        // The story (act 1 and its replays) never names Free AI (PRD §3a).
        // Act 2's payload (phase 2): one motif per session, and from tier 1 the latest planted one may contaminate a seed.
        val poisonRoom = liveAttempt?.context?.takeIf { it.progressEligible && !it.story && !it.training }
        val planted = career.snapshot?.planted.orEmpty()
        payload = poisonRoom?.let { Poison.payload(it.id, planted) }
        payloadPlanted = false
        val tn = Assessment(mind.suspicion, story, tellWeight = mind.p.tellWeight, evidence = mind.meter,
            named = !storyMission, storyTest = storyMission,
            difficultyOffset = assessmentOffset(act, country.id, storyMission, training), coached = liveContract != Contract.UNAIDED,
            contaminated = poisonRoom?.let { Poison.contamination(harvestTier(career.snapshot, act), planted, it.id) },
            recent = career.snapshot?.recentSeeds.orEmpty().toSet(),
            exam = assessmentOffset(act, country.id, storyMission, training) > 0)
        dbg("payload ${payload?.id ?: "-"} planted=$planted contaminated=${tn.seeds.firstNotNullOfOrNull(Motif::bySeed)?.id ?: "-"}")
        dbg("associate evidence meter=%.2f probes=%d".format(mind.meter, tn.seeds.count(::isProbe)))
        scope.launch(Dispatchers.Default) { guarded(myRun) {
            awaitForeground()
            // The room speaks fixed translations of the T-N seeds (TN_LOCAL): the officer model drifted back to desk
            // questions when asked to phrase them, and Nano asked to translate them often answered them instead.
            // Then the same into the subtitle language.
            val translated = async(Dispatchers.Default) { translateRoom(tn.seeds, destination.lang) to translateRoom(tn.seeds, sub) }
            if (!storyMission) interviewDelay(1200)
            if (myRun != run) return@guarded // a stale room must not touch the next interview
            val (line, lineEn) = if (storyMission) {
                ROOM.getValue(destination.lang).machine to ROOM.getValue(sub).machine
            } else {
                val other = associate(myRun) // a new unit: warn again if the language is slow
                if (myRun != run) return@guarded
                if (other && slowLang(destination.lang)) implantNote(myRun, str(R.string.implant_slow, destination.languageName(uiLanguage(ctx))))
                // The associate's greeting, then the eye machine, which then fills the view.
                val join = { l: String -> if (l == "ja" || l == "zh") "" else " " }
                // associate.greet.1 ("I already know a great deal about you.") only once the phone has translated (owner); else associate.greet.2.
                val (greet, greetEn) = fixedLine(associateGreet(career.snapshot?.translatorUsed == true || liveAttempt?.translatorUsed == true))
                (greet + join(destination.lang) + ROOM.getValue(destination.lang).machine) to
                    (greetEn + join(sub) + ROOM.getValue(sub).machine)
            }
            narration = null; face = Face.NEUTRAL
            roomFrom = history.size
            officerText = line; officerEn = lineEn; history += "Officer: $line"
            val said = CompletableDeferred<Unit>()
            speak(myRun, line) { said.complete(Unit) }
            val timeout = launch { interviewDelay(20_000); said.complete(Unit) }
            try { said.await() } finally { timeout.cancel() }
            awaitForeground()
            if (myRun != run) return@guarded
            implantNote(myRun, str(R.string.room_implant_warning))
            machineView = true
            translated.await().let { (say, subs) -> roomSay = say; roomSub = subs }
            turnLock.withLock {
                if (myRun != run) return@guarded
                roomDesk = Desk()
                roomAnalyst = brain?.takeIf { analyst != null }?.let { Analyst(it, destination, story, sub) }
                room = tn
                mind.enterRoom()
            }
            question = ""; face = Face.NEUTRAL; narration = null
            dbg("room ${room?.state()} | ${room?.questions?.joinToString(" / ")}")
            officerTurn(myRun, "[Run the Kade-Sorensen assessment.]", answer = false)
        } }
    }

    /**
     * The associate is the AI company's machine, not a border officer (PRD §6a): one look at every border (base app
     * art `story_associate`, no insignia), designation `unit_associate`, and one voice per language: the male Piper
     * voice (else the female one, slower and lower), or the system voice picked by the fixed seed "story_associate" at [ASSOCIATE_PITCH]. No memory of the desk.
     * False if the sheet doesn't ship (then the desk officer stays).
     */
    private suspend fun associate(myRun: Int): Boolean {
        val id = "story_associate"
        if ("officer_${id}_sheet.webp" !in ctx.assets.list("art").orEmpty()) return false
        // Under turnLock with a run check: a stale room must not swap the officer of a newer interview.
        turnLock.withLock {
            if (myRun != run) return false
            officerId = id; unitName = str(R.string.unit_associate)
            tts?.let { Voices.apply(ctx, it, destination.lang, female = false, seed = id, pitch = ASSOCIATE_PITCH); it.setSpeechRate(1f) }
            loadPiper(female = false, pitch = ASSOCIATE_PITCH) // the room's lines are fixed, so no new officer conversation
        }
        dbg("room officer $id")
        return true
    }

    /**
     * One room turn: read the answer (feeling, evasion, implant talk, tricks; cover questions also through the
     * contradiction check), then the next question as translated in [enterRoom] (no model phrasing), or the verdict.
     * The associate jams the display on some turns ([Jam], rate from [implantPerks]).
     */
    private suspend fun roomTurn(myRun: Int, a: Assessment, said: String, answer: Boolean) {
        val lines = ROOM.getValue(destination.lang)
        // An answer during a delay jam drops the held-back hints at once: wait for the suggest call only, never the delay.
        retinaShow?.cancelAndJoin(); retinaShow = null
        pendingRetina?.await(); pendingRetina = null
        silenceJob?.cancel(); silenceDue = 0L; resumeListening = false
        mic = Mic.PROCESSING; hints = emptyList(); jam = null
        spoken = false; shown = false; clockAt = 0L; lookAsked = false
        var prefix = ""; var prefixEn = ""
        var reaction: RoomCheck? = null
        var dosed = false // this answer was an accepted poison sample 
        val pressureAnswered = answer && question.isNotEmpty() && a.pressureQuestion
        if (answer && question.isNotEmpty()) {
            val b = brain ?: return
            val lang = scope.async { inLanguage(said, destination.lang, MlLangId) } // beside the Nano call, not after it
            val t0 = System.nanoTime()
            val (read, raw) = roomRead(Nano.oneShot(b), destination, question, said) // + the narrow ABSURD call on credited answers
            dbg("tn read ${(System.nanoTime() - t0) / 1_000_000}ms raw=\"$raw\"")
            val rc = read.let { base -> base.copy(inLanguage = languageVerdict(said, lang.await(), saysLanguageYes(raw), destination.lang)) } // ID handles names; Nano can rescue short answers
            if (myRun != run) return
            awaitForeground()
            if (myRun != run) return
            reaction = rc
            val mentioned = a.mentions(rc) // before onCheck/next: a denied probe is not implant talk
            val admissionQuestion = a.probing || a.confirming
            val dosesBefore = a.doses; val rejectsBefore = a.rejects
            a.onCheck(rc, payload?.let { namesMotif(said, it, destination.lang) } == true) // tricks too: the associate can't be crashed, a trick raises its index
            dosed = a.doses > dosesBefore
            var contradiction = false
            if (!rc.trick && a.coverFact != null)
                analyst?.judge(question, said)?.let {
                    contradiction = it.check.contradiction && it.fact == a.coverFact
                    a.onContradiction(it.fact, it.check.contradiction)
                }
            if (myRun != run) return
            machineAnswers = machineAnswers + machineAnswer(rc, contradiction, admissionQuestion, a.rejects > rejectsBefore)
            if (mentioned) {
                prefix = lines.precaution; prefixEn = ROOM.getValue(sub).precaution
                scope.launch { officerGlitch = true; delay(1500); officerGlitch = false }
            }
            if (dosed) { // the poison visibly lands: the same 1.5 s glitch on the machine, and a tick
                hapticPlayer.play(HapticEvent.IMPLANT_GLITCH)
                scope.launch { officerGlitch = true; delay(1500); officerGlitch = false }
            }
            a.next()
            if (a.prototype && !prototype) { // the USA exam's newer unit takes over (once): the art switches, the machine stutters
                prototype = true
                hapticPlayer.play(HapticEvent.IMPLANT_GLITCH)
                scope.launch { officerGlitch = true; delay(1500); officerGlitch = false }
            }
            dbg("tn $rc -> ${a.state()} ${mind.state()}")
        } else if (!announced) {
            announced = true
            prefix = lines.announce; prefixEn = ROOM.getValue(sub).announce
        }
        if ((a.pressed || a.reasking) && prefix.isEmpty()) { // the re-ask after an empty or unclear answer: "Answer the question." and the same question
            fun press(l: String) = renderLine("dodge.curt.1", l, story.gender, story.days, "").orEmpty()
            prefix = press(destination.lang); prefixEn = press(sub)
        }
        face = when { prefix == lines.precaution -> Face.CONFUSED; a.index > 60 -> Face.SUSPICIOUS; a.index > 40 -> Face.CONCERNED; else -> Face.NEUTRAL }
        if (pressureAnswered) composureWindow(myRun, a)
        if (myRun != run) return
        val d = mind.decision ?: a.verdict() // null in the room: the associate never crashes (debug `#pass` / `#fail` force the Assessment)
        if (d != null) {
            machineResult = machineDebrief(machineAnswers, a.doses)
            payloadPlanted = payload != null && d == Decision.RELEASED && a.payloadNamed && liveAttempt?.debugForced != true
            captureAttemptDecision(myRun, d)
            mind.settle(d)
            if (!d.cleared) face = Face.STERN // refused or caught: calm and cold, the same ending in the story
            val (line, english) = finalLine(d)
            officerText = line; officerEn = english
            history += "Officer: $line"
            dbg("room ending $d ${a.state()}")
            speak(myRun, line)
            return
        }
        overloadWindow(myRun, a)
        if (myRun != run) return
        // A suspected admission: the fixed confirmation before anything else, past the last seed too (Assessment.confirming).
        val confirm = if (a.confirming) fixedLine("associate.confirm.${1 + Math.floorMod(myRun, 2)}") else null
        val seed = confirm?.second ?: a.current ?: return
        // From the 2nd question on, react to the check with a rotating fixed statement.
        if (confirm == null && answer && prefix.isEmpty() && question.isNotEmpty()) {
            val rc = checkNotNull(reaction)
            val id = roomDesk.pick(when { rc.evasive -> "room.evasive"; dosed -> "room.dose"; rc.feeling -> "room.feeling"; else -> "room.cold" })
            prefix = renderLine(id, destination.lang, story.gender, story.days, "").orEmpty()
            prefixEn = renderLine(id, sub, story.gender, story.days, "").orEmpty()
        }
        // A first silence leaves a.asked unchanged, so the same question is repeated; a second moved on (Assessment.onSilence).
        question = confirm?.first ?: roomSay.getOrNull(a.asked) ?: seed
        officerText = listOf(prefix, question).filter { it.isNotEmpty() }.joinToString(if (destination.lang == "ja" || destination.lang == "zh") "" else " ")
        val seedEn = if (confirm != null) seed else roomSub.getOrNull(a.asked) ?: seed
        officerEn = listOf(prefixEn, seedEn).filter { it.isNotEmpty() }.joinToString(if (sub == "ja" || sub == "zh") "" else " ")
        askedEn = officerEn to seedEn // the behaviour profile quotes the seed question, not the reaction
        // The building test is the poison tutorial: one coaching line on the implant before question 2.
        if (storyMission && confirm == null && a.asked == 1 && !a.pressed && !a.reasking && liveContract != Contract.UNAIDED)
            implantNote(myRun, str(R.string.room_poison_coach), 12_000) // with its example sentence: longer to read
        speak(myRun, officerText)
        history += "Officer: $question"
        dbg("room officer | $officerText")
        if (liveContract == Contract.UNAIDED) {
            shown = true; startClock(myRun)
            return
        }
        val an = roomAnalyst ?: return
        val turnJam = Jam.roll(implantPerks.jamRate)
        jam = turnJam // Fixed fallbacks are available even while the suggestion model is working.
        playerAnswered = false
        val seen = history.drop(roomFrom)
        val suggest = scope.async(Dispatchers.Default) { an.suggest(seen, answered = { playerAnswered }) }
        pendingRetina = suggest
        // The building test's bird question (tutorial): the benched example dose leads the generated hints.
        val example = storyMission && confirm == null && seed == Assessment.BIRD_SEED
        retinaShow = scope.launch(Dispatchers.Default) { guarded(myRun) { // a Nano error here (quota, backgrounded) must not kill the process
            val ep = suggest.await()
            // A delay jam holds the hints back; the answer clock starts only when they show (startClock), so it never counts.
            if (turnJam == Jam.DELAY) interviewDelay(Jam.delayMs())
            if (myRun == run) {
                // Preserve the jammed model output; the HUD substitutes fixed fallbacks for GARBLE and DROP.
                jam = turnJam
                val generated = jammed(ep.options.map(Models::option), turnJam)
                hints = if (example) withBirdExample(generated, destination.lang, uiLanguage(ctx)) else generated
                hintsNone = hints.isEmpty()
                shown = true; startClock(myRun)
            }
            if (ep.log.isNotEmpty()) dbg("hints rejected | " + ep.log)
            dbg("hints | " + ep.options.map(Models::option).joinToString(" | ") { "${it.target} / ${it.english}" } +
                " | shown: " + hints.joinToString(" | ") { "${it.target} / ${it.english}" })
        } }
    }

    /** Room questions and subtitles use only the same fixed seed table. */
    private fun translateRoom(seeds: List<String>, lang: String): List<String> = seeds.map { seed ->
        requireNotNull(Assessment.local(seed, story, lang)) { "room translation missing $lang: $seed" }
    }.also { dbg("room questions $lang ${it.joinToString(" / ")}") }

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
            if (backgrounded) continue
            if (coachedWindow) { blinks = faceWatch.blinkCount; continue }
            // The eye machine beeps on every blink and chirps (at most every 2 s) on a new top tell.
            if (machineView && faceWatch.blinkCount != blinks) sfx.play(R.raw.sfx_blink)
            blinks = faceWatch.blinkCount
            lookCheck()
            val faceEpoch = faceWatch.session.tellEpoch(faceRun)
            val top = if (profiler.calibrated && mind.decision == null)
                profiler.tells(faceWatch.last(8000)).firstOrNull()?.first else null
            if (top != null && faceEpoch != null) faceWatch.session.tell(faceRun, top, faceWatch.now(), faceEpoch)
            if (interviewClock.now() / 1_000_000 < hintUntil) continue
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
        if (thalassianDesk || coachedWindow) return
        if (!watching || lookAsked || mic != Mic.READY || narration != null || mind.decision != null || ending != null) return
        // speak() ends the listen: on device it cut off a quiet start Silero hadn't confirmed yet (phone held low, face missed 89%)
        if (ms(voiceAt) < 1500) return
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
        /** The associate's HUD designation, same everywhere (no company name) and its system-voice pitch. */
        private const val ASSOCIATE_PITCH = 0.85f
        /** The phone translator's voice ([speakPhone]): higher and faster than any officer. Tuning knobs. */
        private const val PHONE_PITCH = 1.6f
        private const val PHONE_RATE = 1.25f
        /** The room classify line's token budget (Game.roomTurn and the judge bench); ADMIT, then ABSURD made the line longer. */
        const val ROOM_TOKENS = 56
        // Auto-mode energy VAD knobs (RMS of raw float PCM, before MicGain). Tune on device; a real airport is louder
        // than a living room. VAD_MIN under the quiet owner's speech (frames 0.008-0.019 at arm's length), above silence.
        private const val VAD_START = 3.0
        private const val VAD_KEEP = 2.0
        private const val VAD_MIN = 0.005
        /** Tuning knob: raw frame RMS that counts as voice for lookCheck. Device: quiet-room silence 0.0003-0.001 (noise
         *  to 0.005), quiet speech at arm's length 0.008+. */
        private const val VOICE_RMS = 0.006
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

/**
 * The interface language: the per-app locale if set (the application context's configuration may not follow it), else
 * the system's. A held-back language ([Language.HELD_BACK]) has no strings in the bundle, so the menus are English: so
 * are subtitles and names.
 */
fun uiLanguage(ctx: Context): String =
    (ctx.getSystemService(LocaleManager::class.java).applicationLocales.takeIf { !it.isEmpty }?.get(0)?.language
        ?: ctx.resources.configuration.locales[0].language).let { if (it in Language.HELD_BACK) "en" else it }

/** [probe]: the start-up system-ASR probe (lang → has a system recognizer). Unknown or still probing: not slow. */
fun slowLang(probe: Map<String, Boolean?>?, lang: String) = probe?.get(lang) == false

/**
 * A late "here is my passport" ([lang]) said to the small-talk question that follows the passport button (phase 4
 * finding 2): the whole reply, after case, accent and punctuation folding, is a run of handover formulas ("Here it is,
 * my passport.", "Вот, пожалуйста."). Its small-talk ODD read is skipped; anything else ("I killed for this passport")
 * is still read. ja/zh are matched with spaces removed.
 */
internal fun isHandoverReply(text: String, lang: String): Boolean {
    val cjk = lang == "ja" || lang == "zh"
    fun fold(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "").replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim().let { if (cjk) it.replace(" ", "") else it }
    val formulas = HANDOVER_FORMULAS[lang].orEmpty().map(::fold)
    fun run(t: String): Boolean = t.isEmpty() || formulas.any { f ->
        t.startsWith(f) && (cjk || t.length == f.length || t[f.length] == ' ') && run(t.substring(f.length).trim())
    }
    val t = fold(text)
    return t.isNotEmpty() && run(t)
}

/** Handover formulas, passport phrases and polite fillers per language; a reply must be made of these alone. */
private val HANDOVER_FORMULAS = mapOf(
    "en" to listOf("here it is", "here you go", "here you are", "there you go", "here", "here is", "here's", "this is",
        "my passport", "the passport", "passport", "please", "yes", "sure", "of course", "thank you", "thanks"),
    "de" to listOf("hier", "hier ist", "hier bitte", "hier ist er", "bitte", "bitte schön", "bitteschön", "bitte sehr",
        "mein pass", "mein reisepass", "der pass", "pass", "reisepass", "ja", "danke"),
    "fr" to listOf("voilà", "voici", "tenez", "le voici", "le voilà", "mon passeport", "le passeport", "passeport",
        "s'il vous plaît", "oui", "bien sûr", "merci"),
    "es" to listOf("aquí tiene", "aquí está", "aquí lo tiene", "aquí", "tenga", "mi pasaporte", "el pasaporte",
        "pasaporte", "por favor", "sí", "claro", "gracias"),
    "it" to listOf("ecco", "ecco a lei", "eccolo", "tenga", "il mio passaporto", "il passaporto", "passaporto", "prego",
        "sì", "certo", "grazie"),
    "pt" to listOf("aqui está", "aqui tem", "aqui", "tome", "o meu passaporte", "meu passaporte", "o passaporte",
        "passaporte", "por favor", "sim", "claro", "obrigado", "obrigada"),
    "pl" to listOf("proszę", "proszę bardzo", "oto", "mój paszport", "paszport", "tak", "dziękuję"),
    "ru" to listOf("вот", "вот он", "пожалуйста", "держите", "мой паспорт", "паспорт", "да", "конечно", "спасибо"),
    "uk" to listOf("ось", "ось він", "будь ласка", "тримайте", "прошу", "мій паспорт", "паспорт", "так", "звичайно", "дякую"),
    "tr" to listOf("buyrun", "buyurun", "işte", "pasaportum", "pasaport", "burada", "evet", "tabii", "teşekkürler"),
    "ar" to listOf("تفضل", "تفضلي", "ها هو", "هذا", "جوازي", "جواز سفري", "الجواز", "نعم", "من فضلك", "شكرا"),
    "ja" to listOf("はい", "どうぞ", "これが", "こちらが", "こちらです", "私の", "パスポートです", "パスポート", "旅券", "お願いします"),
    "zh" to listOf("这是我的护照", "这是护照", "我的护照", "护照", "给您", "给你", "好的", "请", "在这里", "谢谢", "是的"),
)

/** The chatbot trick's tip, once per install, in training after a first good answer at an ordinary desk. */
internal fun chatbotTipDue(thalassian: Boolean, goodAnswers: Int, shown: Boolean) = !thalassian && goodAnswers >= 1 && !shown
