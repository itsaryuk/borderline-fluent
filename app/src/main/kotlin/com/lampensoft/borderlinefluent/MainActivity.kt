package com.lampensoft.borderlinefluent

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val game: Game by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light status bar icons always: the game is dark whatever the phone's theme.
        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT).let { enableEdgeToEdge(it, it) }
        setRecentsScreenshotEnabled(false) // the passport photo never lands in the Recents snapshot (screenshots still work)
        // Camera and microphone are asked for in context, never at launch: the mic on the setup's input screen
        // (InputModeScreen), or below when a listen finds it denied (a player who set up before that screen asked).
        setContent {
            // The setup policy (backFrom): null leaves the app (START, UNSUPPORTED, the intro during setup); during
            // setup Back returns to the intro, never past the passport step. Screens with their own Back win over this.
            val back = backFrom(game.screen, game.nextSetupScreen(), game.passportFrom)
            BackHandler(game.navigationBlocked || back != null) {
                if (!game.navigationBlocked) {
                    if (game.screen == Screen.INTERVIEW) game.abort() else back?.let { game.screen = it }
                }
            }
            // Asked again when an answer found the microphone denied (the tip points to Settings if that is final).
            if (game.micDenied) androidx.compose.runtime.LaunchedEffect(Unit) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2) }
            GameApp(game)
        }
    }

    override fun onResume() {
        super.onResume()
        game.hapticForeground(true)
        game.resumeAfterStop()
        game.music.resume()
    }

    override fun onPause() {
        // Keep the ViewModel turn alive; the durable pending attempt still covers process death.
        game.pauseInterview()
        game.music.pause()
        game.hapticForeground(false)
        super.onPause()
    }
}
