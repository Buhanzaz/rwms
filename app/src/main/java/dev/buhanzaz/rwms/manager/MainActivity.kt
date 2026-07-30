package dev.buhanzaz.rwms.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.buhanzaz.rwms.manager.navigation.ManagerApp
import dev.buhanzaz.rwms.manager.network.RwmsBackend
import dev.buhanzaz.rwms.manager.ui.ManagerViewModel

class MainActivity : ComponentActivity() {
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
}
