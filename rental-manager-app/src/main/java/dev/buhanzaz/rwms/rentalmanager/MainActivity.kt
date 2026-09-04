package dev.buhanzaz.rwms.rentalmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import dev.buhanzaz.rwms.rentalmanager.ui.RentalManagerApp
import dev.buhanzaz.rwms.rentalmanager.ui.RentalManagerChatViewModel
import dev.buhanzaz.rwms.rentalmanager.ui.RentalManagerPhase
import dev.buhanzaz.rwms.rentalmanager.ui.RentalManagerViewModel
import dev.buhanzaz.rwms.rentalmanager.ui.theme.RentalManagerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val backend = (application as RentalManagerApplication).backend
        setContent {
            val managerViewModel = viewModel<RentalManagerViewModel>(
                factory = RentalManagerViewModel.factory(backend),
            )
            val state = managerViewModel.state.collectAsStateWithLifecycle().value
            val chatViewModel = viewModel<RentalManagerChatViewModel>(
                factory = RentalManagerChatViewModel.factory(backend),
            )
            val chatState = chatViewModel.state.collectAsStateWithLifecycle().value
            LaunchedEffect(state.phase, state.session?.user?.id) {
                val actorId = state.session?.user?.id
                if (state.phase == RentalManagerPhase.READY && actorId != null) {
                    chatViewModel.activate(actorId)
                } else {
                    chatViewModel.deactivate()
                }
            }
            RentalManagerTheme {
                RentalManagerApp(
                    state = state,
                    viewModel = managerViewModel,
                    chatState = chatState,
                    chatViewModel = chatViewModel,
                )
            }
        }
    }
}
