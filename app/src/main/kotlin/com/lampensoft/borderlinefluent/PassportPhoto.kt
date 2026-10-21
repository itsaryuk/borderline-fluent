package com.lampensoft.borderlinefluent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The player's own passport photo. It lives only in noBackupFilesDir (Auto Backup is on for filesDir), never in
 * results.log, recordings or logs: nothing here logs a path or a pixel.
 */
object PassportPhoto {
    const val W = 420
    const val H = 540
    fun file(ctx: Context) = File(ctx.noBackupFilesDir, "passport.jpg")
    private fun tmp(ctx: Context) = File(ctx.noBackupFilesDir, "passport.jpg.tmp")
    /** A leftover temp file (a save killed mid-write) is deleted on every look. */
    fun exists(ctx: Context): Boolean { tmp(ctx).delete(); return file(ctx).isFile }
    /** Null when there is no photo or it doesn't decode (corrupt): callers fall back to a sample photo. */
    fun load(ctx: Context): ImageBitmap? { tmp(ctx).delete(); return runCatching { BitmapFactory.decodeFile(file(ctx).path)?.asImageBitmap() }.getOrNull() }
    fun delete(ctx: Context) { file(ctx).delete(); tmp(ctx).delete() }

    /** Write a temp file, sync, rename over the old photo: a failed write leaves the accepted photo intact. */
    internal fun save(ctx: Context, bitmap: Bitmap): Boolean {
        val t = tmp(ctx)
        val ok = runCatching {
            FileOutputStream(t).use { out -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)); out.fd.sync() }
            t.renameTo(file(ctx))
        }.getOrDefault(false)
        if (!ok) t.delete()
        return ok
    }

    /** Sample face [p] (a tile of its 3x2 traveler sheet in the app's assets), developed like a capture. */
    internal fun sample(ctx: Context, p: Person): Bitmap {
        val sheet = ctx.assets.open("art/${p.sheet}.webp").use(BitmapFactory::decodeStream)
        val w = sheet.width / 3; val h = sheet.height / 2
        val tile = Bitmap.createBitmap(sheet, (p.tile % 3) * w, (p.tile / 3) * h, w, h)
        if (tile !== sheet) sheet.recycle()
        return develop(tile, fitCrop(w, h)).also { tile.recycle() }
    }

    /** Crop, scale to [W]×[H] and tone the still in one draw: saturation 0.8, contrast 1.08, a little warmer. */
    internal fun develop(still: Bitmap, crop: IntRect): Bitmap {
        val c = 1.08f; val t = (1 - c) * 128
        val tone = ColorMatrix().apply { setSaturation(0.8f) }
        tone.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t + 6, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t - 6, 0f, 0f, 0f, 1f, 0f)))
        return Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(still, Rect(crop.left, crop.top, crop.right, crop.bottom), Rect(0, 0, W, H),
                Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(tone) })
        }
    }
}

/** One face from the passport detector, in upright pixels; eyes/smile null when ML Kit couldn't classify. */
data class PassportFace(val box: IntRect, val yaw: Float, val pitch: Float, val eyes: Float?, val smile: Float?)

// ponytail: capture thresholds are guesses; tune on the phone.
const val PASSPORT_MAX_ANGLE = 10f   // |yaw|, |pitch| in degrees
const val PASSPORT_CENTRE = 0.12f    // face centre within this fraction of the frame centre
const val PASSPORT_MIN_WIDTH = 0.28f // face width as a fraction of the frame width
const val PASSPORT_MAX_WIDTH = 0.55f
const val PASSPORT_HOLD_MS = 500L    // all gates hold this long before the shutter opens
const val PASSPORT_EYES_HINT = 0.6f  // advisory only (glasses, no classification): never a gate
const val PASSPORT_SMILE_HINT = 0.5f

/** Which checks a frame passes. size: -1 too small, 0 fine, 1 too big. Eyes and smile are hints, not gates. */
data class PassportChecks(val faces: Int, val straight: Boolean = false, val centred: Boolean = false, val size: Int = 0,
                          val fits: Boolean = false, val eyesHint: Boolean = false, val smileHint: Boolean = false) {
    val ok get() = faces == 1 && straight && centred && size == 0 && fits
}

fun passportChecks(faces: List<PassportFace>, w: Int, h: Int): PassportChecks {
    val f = faces.singleOrNull() ?: return PassportChecks(faces.size)
    val b = f.box
    val width = b.width / w.toFloat()
    return PassportChecks(1,
        straight = abs(f.yaw) < PASSPORT_MAX_ANGLE && abs(f.pitch) < PASSPORT_MAX_ANGLE,
        centred = abs((b.left + b.right) / 2f / w - 0.5f) < PASSPORT_CENTRE && abs((b.top + b.bottom) / 2f / h - 0.5f) < PASSPORT_CENTRE,
        size = if (width < PASSPORT_MIN_WIDTH) -1 else if (width > PASSPORT_MAX_WIDTH) 1 else 0,
        fits = passportCrop(b, w, h) != null,
        eyesHint = (f.eyes ?: 1f) < PASSPORT_EYES_HINT, smileHint = (f.smile ?: 0f) > PASSPORT_SMILE_HINT)
}

/** A saved photo opens on that photo (Keep / Retake); the camera starts only on Retake, or at once if there is none. */
fun cameraAtStart(granted: Boolean, hasPhoto: Boolean) = granted && !hasPhoto

/** When the checks started passing without a break (null = they don't pass now). */
fun passSince(ok: Boolean, since: Long?, now: Long): Long? = if (!ok) null else since ?: now
fun shutterReady(since: Long?, now: Long) = since != null && now - since >= PASSPORT_HOLD_MS

/** The frame size after rotating by [rotation] degrees (what ML Kit's boxes refer to). */
fun uprightSize(w: Int, h: Int, rotation: Int): Pair<Int, Int> = if (rotation % 180 != 0) h to w else w to h

/** The largest centred 35:45 crop of a w×h image. */
fun fitCrop(w: Int, h: Int): IntRect {
    val cw = minOf(w, (h * 35f / 45f).roundToInt()); val ch = minOf(h, (w * 45f / 35f).roundToInt())
    return IntRect((w - cw) / 2, (h - ch) / 2, (w - cw) / 2 + cw, (h - ch) / 2 + ch)
}

/** A 35:45 passport crop around the face box, or null if it would leave the w×h image ("step back"). */
fun passportCrop(box: IntRect, w: Int, h: Int): IntRect? {
    val bh = box.height
    if (bh <= 0) return null
    val ch = (1.9f * bh).roundToInt()
    val cw = (ch * 35f / 45f).roundToInt()
    val top = (box.top - 0.55f * bh).roundToInt()
    val left = ((box.left + box.right) / 2f - cw / 2f).roundToInt()
    return IntRect(left, top, left + cw, top + ch).takeIf { left >= 0 && top >= 0 && it.right <= w && it.bottom <= h }
}

private val Ink = Color(0xFF000A24); private val Card = Color(0xFF000E30); private val Text1 = Color(0xFFF3ECE0)
private val Dim = Color(0xFFA9B0C3); private val Cyan = Color(0xFF7DF9FF); private val Amber = Color(0xFFFFC14D)
private val Red = Color(0xFFD9443A); private val Green = Color(0xFF3FB56E)
/** Square corners like the rest of the UI (Ui.kt's Square); the shutter stays round. */
private val Square = RoundedCornerShape(2.dp)

/**
 * The player's own passport photo. The camera is asked for here, in context, only for this photo; live
 * observation during interviews stays its own setting. A capture is a draft in memory until "Use this photo".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PassportScreen(faceWatch: FaceWatch, faceReading: Boolean, onReading: (Boolean) -> Unit, onDone: () -> Unit, onBack: (() -> Unit)?,
                   card: @Composable (photo: @Composable (Modifier) -> Unit) -> Unit, extra: @Composable () -> Unit = {}) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var hasPhoto by remember { mutableStateOf(PassportPhoto.exists(ctx)) }
    val photo = remember(hasPhoto) { if (hasPhoto) PassportPhoto.load(ctx) else null }
    fun granted() = ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var cameraOn by remember { mutableStateOf(cameraAtStart(granted(), hasPhoto)) }
    var noCamera by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var checks by remember { mutableStateOf<PassportChecks?>(null) }
    var since by remember { mutableStateOf<Long?>(null) }
    var ready by remember { mutableStateOf(false) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var sample by remember { mutableStateOf(false) } // the draft is a sample face, not a capture
    val deck = remember { ArrayDeque<Person>() } // shuffled sample faces: none repeats until all were shown
    fun showSample() {
        if (busy) return
        if (deck.isEmpty()) deck.addAll(Backstory.people.shuffled())
        val p = deck.removeFirst()
        busy = true; message = null
        scope.launch {
            val b = withContext(Dispatchers.IO) { runCatching { PassportPhoto.sample(ctx, p) }.getOrNull() }
            busy = false
            if (b != null) { draft = b; sample = true } else message = R.string.passport_capture_failed
        }
    }

    // Interview face reading: one explicit switch, saved as it flips; turned on, it asks for the same camera permission.
    var reading by rememberSaveable { mutableStateOf(faceReading) }
    val askReading = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) { reading = false; onReading(false) } // denied (or permanently): play without face reading
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { cameraOn = true; noCamera = false } else { noCamera = true; showSample() } // denied, or permanently denied
    }
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) { since = null; ready = false; checks = null } // the hold restarts after backgrounding
            if (e == Lifecycle.Event.ON_RESUME && cameraOn && !granted()) cameraOn = false // revoked from Settings
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    BackHandler(draft != null) { draft = null; sample = false; checks = null }
    BackHandler(draft == null && onBack != null) { onBack?.invoke() }

    fun startCamera() { message = null; if (granted()) { cameraOn = true; noCamera = false } else ask.launch(Manifest.permission.CAMERA) }
    fun shoot() {
        val cap = capture ?: return
        busy = true; message = null; checks = null
        scope.launch {
            val (shot, why) = runCatching { takePassportPhoto(cap, faceWatch) }.getOrElse { null to R.string.passport_capture_failed }
            busy = false; since = null; ready = false; checks = null
            if (shot != null) { draft = shot; sample = false } else message = why
        }
    }

    val d = draft
    // What to fix now (live checks), or what went wrong with the last capture.
    val c = checks
    val status = message ?: if (d == null && cameraOn && !noCamera && c != null) when {
        c.faces == 0 -> R.string.passport_check_no_face
        c.faces > 1 -> R.string.passport_check_one_face
        !c.straight -> R.string.passport_check_straight
        !c.centred -> R.string.passport_check_centre
        c.size < 0 -> R.string.passport_check_closer
        c.size > 0 || !c.fits -> R.string.passport_check_farther
        ready -> R.string.passport_check_ready
        else -> R.string.passport_check_hold
    } else null

    Column(Modifier.fillMaxSize().background(Ink).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Text1) }
            Text(stringResource(R.string.passport_title), style = display(24, 2.0), color = Text1, // the game's screen-title style
                modifier = Modifier.padding(vertical = 4.dp))
        }
        // The identity card (Ui.kt) with the photo slot on its leading side: it hosts the camera, the draft or the saved photo.
        card { slot ->
            when {
                d != null -> Image(d.asImageBitmap(), null, slot, contentScale = ContentScale.Crop)
                cameraOn && !noCamera -> Box(slot) {
                    PassportCamera(faceWatch, Modifier.fillMaxSize(),
                        onFrame = { faces, w, h ->
                            val c = passportChecks(faces, w, h); val now = SystemClock.elapsedRealtime()
                            checks = c; since = passSince(c.ok, since, now); ready = shutterReady(since, now)
                        },
                        onReady = { capture = it }, onFail = { noCamera = true; cameraOn = false; capture = null; showSample() })
                    Canvas(Modifier.fillMaxSize()) { // the oval guide, centred where passportChecks wants the face
                        val ow = size.width * 0.5f; val oh = ow * 1.3f
                        drawOval(if (checks?.ok == true) Green else Color.White.copy(alpha = 0.8f),
                            Offset((size.width - ow) / 2, (size.height - oh) / 2), Size(ow, oh), style = Stroke(3.dp.toPx()))
                    }
                    if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Cyan)
                }
                busy -> Box(slot, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Cyan) }
                photo != null -> Image(photo, null, slot, contentScale = ContentScale.Crop)
                else -> Box(slot, contentAlignment = Alignment.Center) { Icon(Icons.Default.PhotoCamera, null, tint = Dim, modifier = Modifier.size(36.dp)) }
            }
        }
        Spacer(Modifier.height(6.dp))

        status?.let { Text(stringResource(it), color = if (message != null) Amber else Text1, fontSize = 15.sp, textAlign = TextAlign.Center) }
        if (d == null && cameraOn && c?.ok == true) {
            if (c.eyesHint) Text(stringResource(R.string.passport_hint_eyes), color = Dim, fontSize = 14.sp)
            if (c.smileHint) Text(stringResource(R.string.passport_hint_smile), color = Dim, fontSize = 14.sp)
        }
        if (d != null && !sample) Text(stringResource(R.string.passport_unmirrored), color = Dim, fontSize = 13.sp)
        if (noCamera) Text(stringResource(R.string.passport_no_camera), color = Amber, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))

        when {
            d != null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (sample) OutlinedButton(::showSample, enabled = !busy, shape = Square) { Text(stringResource(R.string.passport_sample_another), color = Text1) }
                else OutlinedButton({ draft = null; checks = null }, shape = Square) { Text(stringResource(R.string.passport_retake), color = Text1) }
                Button({
                    busy = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { PassportPhoto.save(ctx, d) }
                        busy = false
                        if (ok) { hasPhoto = true; draft = null; sample = false; cameraOn = false; onDone() } else message = R.string.passport_save_failed
                    }
                }, enabled = !busy, shape = Square) { Text(stringResource(R.string.passport_use)) }
            }
            cameraOn && !noCamera -> IconButton(::shoot, Modifier.size(64.dp).clip(CircleShape)
                .background(if (ready && !busy) Color.White else Color.White.copy(alpha = 0.25f)).border(3.dp, Cyan, CircleShape),
                enabled = ready && !busy && capture != null) {
                Icon(Icons.Default.PhotoCamera, stringResource(R.string.passport_shutter), tint = Ink)
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(::startCamera, shape = Square) { Text(stringResource(if (hasPhoto) R.string.passport_retake else R.string.passport_take), color = Text1) }
                if (photo != null) Button(onDone, shape = Square) { Text(stringResource(R.string.passport_keep)) }
            }
        }
        // A sample face is always on offer; accepted, it becomes the passport photo just like a capture.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
            if (sample) TextButton({ draft = null; sample = false; checks = null; startCamera() }, enabled = !busy) {
                Text(stringResource(R.string.passport_back_to_camera), color = Cyan)
            } else TextButton(::showSample, enabled = !busy) {
                Text(stringResource(R.string.passport_use_sample), color = Cyan)
            }
            if (hasPhoto) TextButton({ PassportPhoto.delete(ctx); hasPhoto = false }, enabled = !busy) {
                Text(stringResource(R.string.passport_delete), color = Red)
            }
            extra()
        }

        // Face reading during interviews is its own choice, kept compact under the controls.
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(Square).background(Card).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.passport_face_title), color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.passport_face_switch), color = Text1, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
                Switch(reading, { on -> reading = on; onReading(on); if (on && !granted()) askReading.launch(Manifest.permission.CAMERA) })
            }
            Text(stringResource(R.string.passport_face_body), color = Dim, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Text(stringResource(R.string.passport_disclosure), color = Dim, fontSize = 12.sp, lineHeight = 14.sp, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }
}

/** The saved passport photo, or a camera-icon placeholder; the photo slot of the identity card outside the camera step. */
@Composable fun SavedPhotoSlot(modifier: Modifier) {
    val ctx = LocalContext.current
    val photo = produceState<ImageBitmap?>(null) { value = withContext(Dispatchers.IO) { if (PassportPhoto.exists(ctx)) PassportPhoto.load(ctx) else null } }.value
    if (photo != null) Image(photo, null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier, contentAlignment = Alignment.Center) { Icon(Icons.Default.PhotoCamera, null, tint = Dim, modifier = Modifier.size(36.dp)) }
}

/**
 * Front camera: Preview + ImageAnalysis + ImageCapture; if the phone can't bind all three, Preview + ImageCapture
 * with the checks run on preview snapshots at ~2 fps. Bound to the lifecycle (stops in the background), unbound on
 * dispose; frames are only read.
 */
@Composable private fun PassportCamera(faceWatch: FaceWatch, modifier: Modifier, onFrame: (List<PassportFace>, Int, Int) -> Unit,
                                       onReady: (ImageCapture) -> Unit, onFail: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val frame by rememberUpdatedState(onFrame)
    var snapshots by remember { mutableStateOf(false) }
    val fail by rememberUpdatedState(onFail)
    DisposableEffect(owner) {
        var disposed = false // the provider can arrive after onDispose: binding then would leave the camera on
        var provider: ProcessCameraProvider? = null
        var bound = emptyArray<UseCase>()
        var analysis: ImageAnalysis? = null
        var state: LiveData<CameraState>? = null
        var stale: CameraState? = null // LiveData re-delivers the last state on observe; that error is already handled
        var watch = Observer<CameraState> {}
        val front = CameraSelector.DEFAULT_FRONT_CAMERA
        fun release() {
            state?.removeObserver(watch); state = null
            analysis?.clearAnalyzer(); analysis = null
            provider?.let { p -> runCatching { p.unbind(*bound) } }; bound = emptyArray()
        }
        /** Preview + (analysis) + capture; false if the phone refuses the combination. */
        fun bind(withAnalysis: Boolean): Boolean {
            val p = provider ?: return false
            release()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(
                    android.util.Size(1600, 1200), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()).build()
            val a = if (withAnalysis) faceWatch.passportAnalysis { faces, w, h -> if (!disposed) frame(faces, w, h) } else null
            val useCases = listOfNotNull(preview, a, capture).toTypedArray()
            val camera = runCatching { p.bindToLifecycle(owner, front, *useCases) }.getOrNull()
                ?: run { a?.clearAnalyzer(); runCatching { p.unbind(*useCases) }; return false }
            bound = useCases; analysis = a; snapshots = a == null
            state = camera.cameraInfo.cameraState.also { stale = it.value; it.observe(owner, watch) }
            onReady(capture)
            return true
        }
        fun giveUp() { release(); fail() }
        // Failures after a successful bind: the three-use-case stream can still be refused when the session opens.
        watch = Observer { s ->
            val e = s.error
            if (disposed || e == null || s === stale) return@Observer
            when {
                e.code == CameraState.ERROR_STREAM_CONFIG && analysis != null -> if (!bind(withAnalysis = false)) giveUp()
                e.type == CameraState.ErrorType.CRITICAL || e.code == CameraState.ERROR_CAMERA_IN_USE ||
                    e.code == CameraState.ERROR_MAX_CAMERAS_IN_USE -> giveUp()
            }
        }
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            if (disposed) return@addListener
            provider = runCatching { future.get() }.getOrNull()
            if (provider == null || !runCatching { provider!!.hasCamera(front) }.getOrDefault(false)) return@addListener fail()
            if (!bind(withAnalysis = true) && !bind(withAnalysis = false)) giveUp()
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { disposed = true; release() }
    }
    if (snapshots) LaunchedEffect(Unit) {
        while (true) {
            delay(500) // ponytail: ~2 fps is enough to hold the gates; the still is re-checked anyway
            val bm = view.bitmap ?: continue // mirrored like the screen; the checks are symmetric
            val faces = runCatching { faceWatch.passportFaces(bm) }.getOrNull()
            if (faces != null) frame(faces, bm.width, bm.height)
            bm.recycle()
        }
    }
    AndroidView({ view }, modifier)
}

/** Capture → re-detect on the still → crop → develop. Returns the draft, or the message to show instead. */
private suspend fun takePassportPhoto(capture: ImageCapture, faceWatch: FaceWatch): Pair<Bitmap?, Int?> {
    val still = capture.still()
    try {
        val faces = faceWatch.passportFaces(still)
        val f = faces.singleOrNull() ?: return null to (if (faces.isEmpty()) R.string.passport_check_no_face else R.string.passport_check_one_face)
        val crop = passportCrop(f.box, still.width, still.height) ?: return null to R.string.passport_check_farther
        return withContext(Dispatchers.Default) { PassportPhoto.develop(still, crop) } to null
    } finally { still.recycle() }
}

/** An in-memory still, upright and not mirrored (CameraX mirrors only saved files' metadata, not ImageProxy). */
private suspend fun ImageCapture.still(): Bitmap = suspendCancellableCoroutine { c ->
    takePicture(Dispatchers.Default.asExecutor(), object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            runCatching { image.use { upright(it.toBitmap(), it.imageInfo.rotationDegrees) } }
                .fold({ c.resume(it) { _, b, _ -> b.recycle() } }, { c.resumeWithException(it) })
        }
        override fun onError(e: ImageCaptureException) { c.resumeWithException(e) }
    })
}

private fun upright(b: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return b
    return Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        .also { if (it !== b) b.recycle() }
}
