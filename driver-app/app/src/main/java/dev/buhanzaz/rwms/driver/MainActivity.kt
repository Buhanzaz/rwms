package dev.buhanzaz.rwms.driver

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import com.yandex.mapkit.MapKitFactory
import dagger.hilt.android.AndroidEntryPoint
import dev.buhanzaz.rwms.driver.feature.camera.VolumeShutterHost

@AndroidEntryPoint
/**
 * Defines driver application UI or lifecycle state; it does not decide a server task transition.
 */
class MainActivity : ComponentActivity(), VolumeShutterHost {
    private var volumeShutterHandler: (() -> Unit)? = null
    private val appViewModel: DriverAppViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Data-only sync remains available when notifications are declined. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        MapKitFactory.initialize(this)
        requestNotificationsIfNeeded()
        setContent {
            DriverApp(appViewModel = appViewModel)
        }
    }

    override fun onStart() {
        super.onStart()
        MapKitFactory.getInstance().onStart()
    }

    override fun onStop() {
        MapKitFactory.getInstance().onStop()
        super.onStop()
    }

    private fun requestNotificationsIfNeeded() {
        if (DriverNotifications.isFirebaseConfigured(this) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun setVolumeShutterHandler(handler: (() -> Unit)?) {
        volumeShutterHandler = handler
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val handler = volumeShutterHandler
        val volumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (handler != null && volumeKey) {
            if (event.repeatCount == 0) handler()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val volumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (volumeShutterHandler != null && volumeKey) return true
        return super.onKeyUp(keyCode, event)
    }
}
