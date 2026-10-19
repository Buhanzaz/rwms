package dev.buhanzaz.rwms.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.yandex.mapkit.MapKitFactory
import dagger.hilt.android.AndroidEntryPoint
import dev.buhanzaz.rwms.client.ui.CustomerApp

/** Hosts the edge-to-edge CustomerApp Compose hierarchy on Android 11 and newer. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapKitFactory.initialize(this)
        enableEdgeToEdge()
        setContent { CustomerApp() }
    }
}
