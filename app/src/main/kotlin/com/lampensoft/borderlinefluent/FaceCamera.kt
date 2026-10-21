package com.lampensoft.borderlinefluent

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/** One screen's preview and analyzer. All binding operations run on main; never owns passport use cases. */
internal class FaceCamera(context: Context, owner: LifecycleOwner, watch: FaceWatch,
                          surface: Preview.SurfaceProvider) {
    private val token = watch.session.startCamera()
    private val analysis = watch.analysis(token, context)
    private val preview = Preview.Builder().setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
        ResolutionStrategy(FaceWatch.ANALYSIS, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
        .build().also { it.setSurfaceProvider(surface) }
    private val binding = FaceBinding({ watch.session.stopCamera(token) }, { watch.clearAnalysis(analysis) })
    init {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            // get() only in the completion listener, never in dispose().
            try {
                val provider = future.get()
                if (!watch.session.ownsCamera(token)) { binding.dispose(); return@addListener }
                binding.arrived({ provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis) },
                    { runCatching { provider.unbind(preview, analysis) } })
            } catch (_: Exception) { binding.dispose() }
        }, ContextCompat.getMainExecutor(context))
    }
    fun dispose() = binding.dispose()
}
