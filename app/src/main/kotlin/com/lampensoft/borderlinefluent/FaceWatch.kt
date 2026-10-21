package com.lampensoft.borderlinefluent

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import androidx.compose.ui.unit.IntRect
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.min

/**
 * The officer's eyes: ML Kit face detection on the front camera, entirely on the phone. Frames are never stored;
 * each becomes a [Sample] (smile, eyes, head angles, lip and brow geometry), kept two minutes for the [Profiler].
 */
class FaceWatch {
    private val detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL) // lips, brows and the scanner; contours cover the largest face only
        .build())
    private val worker = Executors.newSingleThreadExecutor()
    private val samples = ArrayDeque<Sample>()
    private val frameTimes = ArrayDeque<Long>()
    @Volatile private var frameSize = ""
    /** Debug builds only: the hand-gesture spike (logs, no gameplay), fed the desk's frames with the last face box. */
    @Volatile private var hands: HandProbe? = null
    @Volatile private var faceBox: android.graphics.Rect? = null

    /** For the HUD: is a face in view right now. */
    @Volatile var seen = false; private set

    /** The latest frame's readings, for the camera test screen. */
    data class Live(val face: Boolean, val smile: Float?, val leftEye: Float?, val rightEye: Float?, val yaw: Float, val pitch: Float,
                    val blinksPerMin: Int, val fps: Int, val facePx: Int = 0, val frame: String = "", val lips: Float = 0f, val brow: Float = 0f)
    @Volatile var live: Live? = null; private set
    /** Closed-eye onsets so far (as [Profiler.blinks] counts them), for the eye machine's beep. */
    @Volatile var blinkCount = 0; private set
    private var eyesClosed = false

    @Volatile internal var latest: Sample? = null; private set
    private var gestureOnly = false
    /** Do not retain commanded gestures in profiling/calibration history. */
    @Synchronized internal fun gestureWindow(on: Boolean) {
        gestureOnly = on
        samples.clear(); latest = null
    }
    private fun retain(s: Sample) { latest = s; if (!gestureOnly) samples.addLast(s) }

    fun now() = System.nanoTime() / 1_000_000
    /** Samples from the last [ms] milliseconds. */
    @Synchronized fun last(ms: Long): List<Sample> { val from = now() - ms; return samples.filter { it.t >= from } }
    /** Samples since the clock reading [fromMs] (see [now]). */
    @Synchronized fun since(fromMs: Long): List<Sample> = samples.filter { it.t >= fromMs }

    /** Debug FACE hook: while set, real frames are dropped and [feed] supplies the samples. */
    @Volatile internal var simulating = false

    /** A synthetic frame through the same bookkeeping as a real one (debug only). */
    @Synchronized internal fun feed(s: Sample) {
        val t = s.t
        frameTimes.addLast(t); while (t - frameTimes.first() > 1000) frameTimes.removeFirst()
        while (samples.isNotEmpty() && t - samples.first().t > 120_000) samples.removeFirst()
        seen = s.face
        val blinks = Profiler.blinksPerMin(samples.filter { it.t >= t - 60_000 }).toInt()
        retain(s)
        if (!s.face) { live = Live(false, null, null, null, 0f, 0f, blinks, frameTimes.size, frame = "sim"); return }
        val closed = s.eyesOpen < 0.3f; if (closed && !eyesClosed) blinkCount++; eyesClosed = closed
        live = Live(true, s.smile, s.leftEye, s.rightEye, s.yaw, s.pitch, blinks, frameTimes.size, 200, "sim", s.lips, s.brow)
    }

    @Synchronized private fun update(face: Face?) {
        if (simulating) return
        val t = now()
        frameTimes.addLast(t); while (t - frameTimes.first() > 1000) frameTimes.removeFirst()
        while (samples.isNotEmpty() && t - samples.first().t > 120_000) samples.removeFirst()
        seen = face != null
        if (hands != null) faceBox = face?.boundingBox
        val blinks = Profiler.blinksPerMin(samples.filter { it.t >= t - 60_000 }).toInt()
        if (face == null) {
            retain(Sample(t, false))
            live = Live(false, null, null, null, 0f, 0f, blinks, frameTimes.size, frame = frameSize)
            return
        }
        // The front camera is mirrored: ML Kit's "left" eye is the one on the player's right (camera test, 2026-10-04).
        val left = face.rightEyeOpenProbability; val right = face.leftEyeOpenProbability
        val h = face.boundingBox.height().coerceAtLeast(1).toFloat()
        fun y(c: Int) = face.getContour(c)?.points?.takeIf { it.isNotEmpty() }?.map { it.y }?.average()?.toFloat()
        val ut = y(FaceContour.UPPER_LIP_TOP); val ub = y(FaceContour.UPPER_LIP_BOTTOM)
        val lt = y(FaceContour.LOWER_LIP_TOP); val lb = y(FaceContour.LOWER_LIP_BOTTOM)
        // Lip thickness only (the gap between the lips is speech, not compression).
        val lips = if (ut == null || ub == null || lt == null || lb == null) 0f else ((ub - ut) + (lb - lt)) / h
        val eyes = listOfNotNull(y(FaceContour.LEFT_EYE), y(FaceContour.RIGHT_EYE))
        val brows = listOfNotNull(y(FaceContour.LEFT_EYEBROW_TOP), y(FaceContour.RIGHT_EYEBROW_TOP))
        val brow = if (eyes.isEmpty() || brows.isEmpty()) 0f else (eyes.average() - brows.average()).toFloat() / h
        val s = Sample(t, true, face.smilingProbability ?: 0f, min(left ?: 1f, right ?: 1f),
            face.headEulerAngleY, face.headEulerAngleX, lips, brow, left, right)
        retain(s)
        val closed = s.eyesOpen < 0.3f; if (closed && !eyesClosed) blinkCount++; eyesClosed = closed
        live = Live(true, face.smilingProbability, left, right, face.headEulerAngleY, face.headEulerAngleX,
            blinks, frameTimes.size, face.boundingBox.width(), frameSize, lips, brow)
    }

    internal val session = FaceSession<InputImage, Face>(::now,
        detect = { image, done ->
            detector.process(image).addOnCompleteListener(worker) { task ->
                done(if (task.isSuccessful) Result.success(task.result.maxByOrNull { it.boundingBox.width() })
                    else Result.failure(task.exception ?: IllegalStateException("face task cancelled")))
            }
        },
        snapshotOf = { face, frame, epoch -> adapt(face, frame, epoch) },
        sample = ::update,
        drained = {
            runCatching { detector.close() }
            if (passportClient.isInitialized()) runCatching { passportClient.value.close() }
            hands?.close()
            worker.shutdown()
        })

    internal fun scannerState(reducedMotion: Boolean = false) = session.state(reducedMotion)
    private var interviewAnalyzer: ImageAnalysis? = null
    private var analyzedAt = 0L // worker thread only
    private var passportAnalyzer: ImageAnalysis? = null
    internal fun clearAnalysis(analysis: ImageAnalysis) {
        analysis.clearAnalyzer()
        if (interviewAnalyzer === analysis) interviewAnalyzer = null
    }
    fun dispose() {
        session.stopCamera()
        interviewAnalyzer?.clearAnalyzer(); interviewAnalyzer = null
        passportAnalyzer?.clearAnalyzer(); passportAnalyzer = null
        session.dispose()
    }

    /** Copy the detector's contour polylines (upright frame pixels) and keep no SDK object. */
    private fun adapt(face: Face, frame: FaceSession.Frame, epoch: Long): FaceMesh.Snapshot? {
        val contours = CONTOURS.mapNotNull { (name, id) ->
            face.getContour(id)?.points?.takeIf { it.isNotEmpty() }?.let { pts ->
                name to pts.map { FaceMesh.Point(it.x.toDouble(), it.y.toDouble()) } }
        }.toMap()
        return FaceMesh.snapshot(contours, frame.capturedAt, frame.width, frame.height, epoch, frame.rotation)
    }

    @SuppressLint("UnsafeOptInUsageError")
    internal fun analysis(token: Long, context: android.content.Context): ImageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(ANALYSIS, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
        .build().also { a ->
            if (BuildConfig.DEBUG && hands == null) hands = HandProbe.load(context)
            interviewAnalyzer = a
            a.setAnalyzer(worker) { proxy ->
                // Thermal cap (~15 fps) for 20-minute sessions. A frame is closed only after its detection, so at the
                // detector's own ~10 fps the next one always arrives past the gate; 60 ms, not 66, so a 30 fps camera's
                // jitter can't skip two frames in a row.
                val at = now()
                if (at - analyzedAt < 60) { proxy.close(); return@setAnalyzer }
                analyzedAt = at
                val rotation = proxy.imageInfo.rotationDegrees
                val (w, h) = uprightSize(proxy.width, proxy.height, rotation)
                val image = try { proxy.image?.let { InputImage.fromMediaImage(it, rotation) } }
                    catch (_: Exception) { null }
                frameSize = "${proxy.width}×${proxy.height}"
                hands?.let { probe -> if (session.desk(token)) probe.offer(proxy, faceBox?.let { b ->
                    HandGestures.Box(b.left / w.toFloat(), b.top / h.toFloat(), b.right / w.toFloat(), b.bottom / h.toFloat()) }) }
                session.frame(token, image, FaceSession.Frame(w, h, rotation, now()), proxy::close)
            }
        }

    /**
     * The passport photo's own detector: no contours, because with contours on ML Kit reports only the largest face
     * and "exactly one face" could pass with two people in the frame. Created on first use; the live interview
     * detector above is untouched. Frames and stills are only read, never kept.
     */
    private val passportClient = lazy { FaceDetection.getClient(FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .setMinFaceSize(0.08f) // ponytail: catch a second face in the background, not just the subject's
        .build()) }

    private val passportDetector get() = passportClient.value

    private fun faces(list: List<Face>) = list.map { f -> val b = f.boundingBox
        PassportFace(IntRect(b.left, b.top, b.right, b.bottom), f.headEulerAngleY, f.headEulerAngleX,
            listOfNotNull(f.leftEyeOpenProbability, f.rightEyeOpenProbability).minOrNull(), f.smilingProbability) }

    /** Camera frames for the passport screen: every face, in upright pixels, with the upright frame size. */
    @SuppressLint("UnsafeOptInUsageError")
    fun passportAnalysis(onFrame: (faces: List<PassportFace>, w: Int, h: Int) -> Unit): ImageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(PASSPORT_ANALYSIS, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
        .build().also { a ->
            passportAnalyzer = a
            a.setAnalyzer(worker) { proxy ->
                val img = proxy.image ?: return@setAnalyzer proxy.close()
                val rot = proxy.imageInfo.rotationDegrees
                val (w, h) = uprightSize(proxy.width, proxy.height, rot)
                if (!session.retain()) { proxy.close(); return@setAnalyzer }
                try {
                    passportDetector.process(InputImage.fromMediaImage(img, rot))
                        .addOnSuccessListener { onFrame(faces(it), w, h) } // main thread, as before
                        .addOnCompleteListener { try { proxy.close() } finally { session.release() } }
                } catch (_: Exception) { try { proxy.close() } finally { session.release() } }
            }
        }

    /** The same detector on an upright bitmap (the captured still, or a preview snapshot). */
    suspend fun passportFaces(bitmap: android.graphics.Bitmap): List<PassportFace> = suspendCancellableCoroutine { c ->
        if (!session.retain()) { c.resumeWithException(IllegalStateException("face watch disposed")); return@suspendCancellableCoroutine }
        try {
            passportDetector.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { c.resume(faces(it)) }
                .addOnFailureListener { c.resumeWithException(it) }
                .addOnCompleteListener { session.release() }
        } catch (e: Exception) { session.release(); c.resumeWithException(e) }
    }

    companion object {
        /** ponytail: 640x480 keeps a face at 28% of the frame near 180 px; raise if background faces go unseen. */
        val PASSPORT_ANALYSIS = android.util.Size(640, 480)

        /** One resolution from desk baseline through assessment; camera test uses the same path. */
        val ANALYSIS = android.util.Size(640, 480)
        private val CONTOURS = mapOf(
            FaceMesh.Contour.FACE_OVAL to FaceContour.FACE,
            FaceMesh.Contour.LEFT_EYE to FaceContour.LEFT_EYE,
            FaceMesh.Contour.RIGHT_EYE to FaceContour.RIGHT_EYE,
            FaceMesh.Contour.LEFT_EYEBROW_TOP to FaceContour.LEFT_EYEBROW_TOP,
            FaceMesh.Contour.LEFT_EYEBROW_BOTTOM to FaceContour.LEFT_EYEBROW_BOTTOM,
            FaceMesh.Contour.RIGHT_EYEBROW_TOP to FaceContour.RIGHT_EYEBROW_TOP,
            FaceMesh.Contour.RIGHT_EYEBROW_BOTTOM to FaceContour.RIGHT_EYEBROW_BOTTOM,
            FaceMesh.Contour.UPPER_LIP_TOP to FaceContour.UPPER_LIP_TOP,
            FaceMesh.Contour.UPPER_LIP_BOTTOM to FaceContour.UPPER_LIP_BOTTOM,
            FaceMesh.Contour.LOWER_LIP_TOP to FaceContour.LOWER_LIP_TOP,
            FaceMesh.Contour.LOWER_LIP_BOTTOM to FaceContour.LOWER_LIP_BOTTOM,
            FaceMesh.Contour.NOSE_BRIDGE to FaceContour.NOSE_BRIDGE,
            FaceMesh.Contour.NOSE_BOTTOM to FaceContour.NOSE_BOTTOM,
            FaceMesh.Contour.LEFT_CHEEK to FaceContour.LEFT_CHEEK,
            FaceMesh.Contour.RIGHT_CHEEK to FaceContour.RIGHT_CHEEK,
        )
    }
}
