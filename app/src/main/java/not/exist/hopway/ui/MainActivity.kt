package not.exist.hopway.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import not.exist.hopway.container
import not.exist.hopway.data.AppSettings
import not.exist.hopway.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    /** 由快速設定磚或捷徑要求「開啟後立即連線」。 */
    private var connectRequest by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(AppSettings())
            AppTheme(settings.theme, settings.dynamicColor) {
                AppNavigation(connectRequest = connectRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_CONNECT) connectRequest++
    }

    companion object {
        const val ACTION_CONNECT = "not.exist.hopway.CONNECT"
    }
}
