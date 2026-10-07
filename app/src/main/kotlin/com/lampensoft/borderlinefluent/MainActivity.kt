package com.lampensoft.borderlinefluent

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val game: Game by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setRecentsScreenshotEnabled(false) // the passport photo never lands in the Recents snapshot (screenshots still work)
        // The camera is asked for in context, on the passport photo screen (PassportScreen), not at launch.
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        setContent {
            // The setup policy (backFrom): null leaves the app (START, UNSUPPORTED, the intro during setup); during
            // setup Back returns to the intro, never past the passport step. Screens with their own Back win over this.
            val back = backFrom(game.screen, game.nextSetupScreen(), game.passportFrom)
            BackHandler(game.navigationBlocked || back != null) {
                if (!game.navigationBlocked) {
                    if (game.screen == Screen.INTERVIEW) game.abort(AbortCause.ABANDONED) else back?.let { game.screen = it }
                }
            }
            // Asked again when an answer found the microphone denied (the tip points to Settings if that is final).
            if (game.micDenied) androidx.compose.runtime.LaunchedEffect(Unit) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2) }
            GameApp(game)
        }
    }

    override fun onResume() { super.onResume(); game.hapticForeground(true) }
    override fun onPause() { game.hapticForeground(false); super.onPause() }

    override fun onStart() { super.onStart(); game.resumeAfterStop(); game.music.resume() }

    override fun onStop() {
        // Backgrounding is interruption, including while the durable Start is still saving.
        game.abort(AbortCause.INTERRUPTED)
        game.music.pause()
        super.onStop()
    }
}
