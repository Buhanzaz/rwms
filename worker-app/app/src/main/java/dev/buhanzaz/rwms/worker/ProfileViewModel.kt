package dev.buhanzaz.rwms.worker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.buhanzaz.rwms.worker.core.database.WorkerGroupEntity
import dev.buhanzaz.rwms.worker.core.database.WorkerLocalStore
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

data class ProfileUiState(
    val login: String? = null,
    val groups: List<WorkerGroupEntity> = emptyList(),
    val currentGroupId: String? = null,
    val currentGroupName: String? = null,
    val operationalAvailability: String = "AVAILABLE",
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModel @Inject constructor(private val localStore: WorkerLocalStore) : ViewModel() {
    private val userId = MutableStateFlow<String?>(null)
    val state: StateFlow<ProfileUiState> = userId.flatMapLatest { id ->
        if (id == null) flowOf(ProfileUiState()) else combine(
            localStore.observeSession(id),
            localStore.observeGroups(id),
        ) { session, groups ->
            ProfileUiState(
                login = session?.login,
                groups = groups,
                currentGroupId = session?.currentGroupId,
                currentGroupName = session?.currentGroupName,
                operationalAvailability = session?.operationalAvailability ?: "AVAILABLE",
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState())

    fun bind(userId: String) { this.userId.value = userId }
}
