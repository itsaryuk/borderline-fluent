package com.lampensoft.borderlinefluent

/** Pure, serialized ownership boundary. The detector adapter calls completion exactly once, even on failure.
 * Submission and invalidation share this monitor: after stop returns no new reader can start.
 * No Task/image/SDK object is retained by the renderer state.
 */
internal class FaceSession<I, F>(
    private val clock: () -> Long,
    private val detect: (I, (Result<F?>) -> Unit) -> Unit,
    private val snapshotOf: (F, Frame, Long) -> FaceMesh.Snapshot?,
    private val sample: (F?) -> Unit,
    private val drained: () -> Unit,
) {
    data class Frame(val width: Int, val height: Int, val rotation: Int, val capturedAt: Long)
    data class State(val epoch: Long, val face: FaceMesh.Smoothed?, val highlights: Map<FaceMesh.Region, Double>)
    data class Gate(val run: Int?, val interview: Boolean, val watching: Boolean, val assessment: Boolean,
                    val machine: Boolean, val obscured: Boolean) {
        val eligible get() = run != null && interview && watching && assessment && machine && !obscured
    }
    private var camera = 0L
    private var attached = false
    private var disposed = false
    private var readers = 0
    private var epoch = 0L
    private var gate = Gate(null, false, false, false, false, true)
    private var presented = false
    private var enabled = false
    private var finished = false
    private var face: FaceMesh.Smoothed? = null
    private val smoother = FaceMesh.Smoother()
    private val highlights = FaceMesh.Highlights()
    private val scheduler = FaceMesh.Scheduler(clock)
    private val budget = FaceBudget()
    private var report: String? = null
    @Synchronized fun takeReport(): String? = report.also { report = null }
    private fun measureWindow() { val stats = budget.take(clock()) ?: return; if (enabled) report = stats.line() }

    @Synchronized fun startCamera(): Long {
        if (disposed) return -1
        camera++; attached = true; changeEpoch(); budget.reset(clock()); return camera
    }
    @Synchronized fun stopCamera(token: Long) {
        if (camera != token) return
        attached = false; camera++; changeEpoch()
    }
    @Synchronized fun stopCamera() { attached = false; camera++; changeEpoch() }
    @Synchronized fun game(value: Gate) {
        if (value == gate) return
        val changedRun = value.run != gate.run
        gate = value
        if (changedRun || enabled != allowed()) changeEpoch()
    }
    @Synchronized fun presentation(visible: Boolean) {
        if (presented == visible) return
        presented = visible
        if (enabled != allowed()) changeEpoch()
    }
    private fun allowed() = !disposed && attached && presented && gate.eligible
    private fun changeEpoch() {
        epoch++; enabled = allowed(); clear(); scheduler.invalidate(); highlights.reset(epoch)
        budget.reset(clock()); report = null
        if (enabled) scheduler.start(epoch)
    }
    private fun clear() { face = null; smoother.clear(); highlights.clear() }
    /** Pull API evaluates freshness/expiry at the renderer's monotonic time; no per-frame main queue. */
    @Synchronized fun state(reducedMotion: Boolean = false): State {
        if (!enabled || !FaceMesh.fresh(face?.source, clock(), epoch)) clear()
        return State(epoch, face, highlights.active(clock(), epoch, face != null, reducedMotion))
    }
    @Synchronized fun tellEpoch(run: Int): Long? = epoch.takeIf { enabled && gate.run == run }
    @Synchronized fun tell(run: Int, tell: Tell, at: Long, eventEpoch: Long) {
        if (!enabled || gate.run != run || eventEpoch != epoch || !FaceMesh.fresh(face?.source, clock(), epoch)) return
        highlights.add(tell, at, epoch)
    }
    /** Also counts passport readers, so final shutdown never closes the detector beneath a still/frame. */
    @Synchronized fun retain(): Boolean { if (disposed) return false; readers++; return true }
    @Synchronized fun release() { readers--; finishIfDrained() }
    private fun finishIfDrained() {
        if (disposed && readers == 0 && !finished) { finished = true; drained() }
    }
    @Synchronized fun dispose() {
        if (disposed) return
        disposed = true; attached = false; camera++; changeEpoch(); finishIfDrained()
    }
    @Synchronized fun ownsCamera(token: Long) = current(token)
    private fun current(token: Long) = !disposed && attached && camera == token

    @Synchronized fun frame(token: Long, input: I?, info: Frame, close: () -> Unit) {
        if (!current(token)) { close(); return }
        if (input == null) { clear(); close(); return }
        readers++
        val capturedEpoch = epoch
        var closed = false
        fun finish() { if (!closed) { closed = true; try { close() } finally { release() } } }
        var detectionDone = false
        try {
            detect(input) { result -> synchronized(this) {
                if (detectionDone) return@synchronized
                detectionDone = true
                try {
                    if (!current(token)) return@synchronized
                    if (capturedEpoch == epoch) { budget.detected(); measureWindow() }
                    val detected = result.getOrNull()
                    // Detector failure preserves historical gameplay behavior: no invented absent Sample.
                    if (result.isSuccess) try { sample(detected) } catch (_: Exception) { return@synchronized }
                    if (capturedEpoch != epoch) return@synchronized
                    if (result.isFailure || detected == null ||
                        minOf(info.width, info.height) < 360 || maxOf(info.width, info.height) < 480) { clear(); return@synchronized }
                    if (!enabled) return@synchronized
                    // Same frame, same inference: the contours are already in the detector's result.
                    val snapshot = try { snapshotOf(detected, info, capturedEpoch) } catch (_: Exception) { null }
                    if (snapshot == null) clear()
                    else if (scheduler.publish(snapshot)) {
                        val old = face?.source
                        if (old != null && !FaceMesh.continuous(old, snapshot)) clear()
                        face = smoother.update(snapshot)
                        budget.published()
                    }
                } finally { finish() }
            } }
        } catch (_: Exception) { if (!detectionDone) { detectionDone = true; if (capturedEpoch == epoch) clear(); finish() } }
    }
}

/** Provider completion/disposal are serialized on main. Only an arrived provider is ever unbound. */
internal class FaceBinding(private val invalidate: () -> Unit, private val clearAnalyzer: () -> Unit) {
    private var disposed = false
    private var cleanup: (() -> Unit)? = null
    fun arrived(bind: () -> Unit, unbind: () -> Unit) {
        if (disposed) return
        cleanup = unbind
        try { bind() } catch (_: Exception) { dispose() }
    }
    fun dispose() {
        if (disposed) return
        disposed = true
        invalidate(); clearAnalyzer(); cleanup?.invoke(); cleanup = null
    }
}
