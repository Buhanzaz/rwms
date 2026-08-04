package dev.buhanzaz.rwms.manager

import android.app.Application
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.work.Configuration
import dev.buhanzaz.rwms.manager.navigation.ManagerApp
import dev.buhanzaz.rwms.manager.network.RwmsBackend
import dev.buhanzaz.rwms.manager.ui.ManagerViewModel

/**
 * WorkManager is intentionally initialized on demand.  Its library manifest initializer is
 * removed because it blocks every cold launch before MainActivity; implementing the provider
 * preserves process-death recovery when Android starts a queued worker in a fresh process.
 */
class ManagerApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}

class MainActivity : ComponentActivity() {
    private var volumeShutterHandler: (() -> Unit)? = null

    private val managerViewModel by viewModels<ManagerViewModel> {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ManagerViewModel::class.java))
                return ManagerViewModel(
                    application = application,
                    backend = RwmsBackend(
                        context = applicationContext,
                        publicBaseUrl = BuildConfig.PUBLIC_BASE_URL,
                    ),
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ManagerApp(viewModel = managerViewModel)
        }
    }

    internal fun setVolumeShutterHandler(handler: (() -> Unit)?) {
        volumeShutterHandler = handler
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val handler = volumeShutterHandler
        val isVolumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (handler != null && isVolumeKey) {
            if (event.repeatCount == 0) handler()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val isVolumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (volumeShutterHandler != null && isVolumeKey) return true
        return super.onKeyUp(keyCode, event)
    }
}
