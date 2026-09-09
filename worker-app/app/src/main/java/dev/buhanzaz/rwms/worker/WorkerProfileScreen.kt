package dev.buhanzaz.rwms.worker

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.buhanzaz.rwms.worker.core.ui.WorkerButton
import dev.buhanzaz.rwms.worker.core.ui.WorkerGlassBorder
import dev.buhanzaz.rwms.worker.core.ui.WorkerGlassSurface
import dev.buhanzaz.rwms.worker.core.ui.WorkerOutlinedButton
import dev.buhanzaz.rwms.worker.core.ui.WorkerScreenScaffold

/** Server-backed profile with one avatar picker and all current worker roles and groups. */
@Composable
fun ProfileScreen(
    fallbackDisplayName: String,
    state: ProfileUiState,
    onBack: () -> Unit,
    onAvatarSelected: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit,
    onLogout: () -> Unit,
) {
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let {
            onDismissError()
            onAvatarSelected(it.toString())
        }
    }
    val displayName = state.displayName?.takeIf(String::isNotBlank) ?: fallbackDisplayName
    WorkerScreenScaffold(title = "Профиль", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(132.dp)
                            .clickable {
                                avatarPicker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                    ) {
                        Surface(
                            modifier = Modifier.size(124.dp).align(Alignment.TopCenter),
                            shape = CircleShape,
                            color = WorkerGlassSurface,
                            border = androidx.compose.foundation.BorderStroke(2.dp, WorkerGlassBorder),
                        ) {
                            if (state.avatar != null) {
                                Image(
                                    bitmap = state.avatar.asImageBitmap(),
                                    contentDescription = "Фото профиля",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        displayName.trim().firstOrNull()?.uppercase() ?: "А",
                                        style = MaterialTheme.typography.displaySmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                        Surface(
                            modifier = Modifier.size(42.dp).align(Alignment.BottomEnd),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.88f),
                            contentColor = Color.White,
                            border = androidx.compose.foundation.BorderStroke(2.dp, Color.White.copy(alpha = 0.88f)),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.CameraAlt, contentDescription = "Изменить фото")
                            }
                        }
                    }
                    Text(
                        displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "Нажмите на фото, чтобы изменить",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                ProfileReadOnlyField("Логин", state.login ?: "Не указан")
            }
            item {
                ProfileReadOnlyField(
                    "Текущая группа",
                    state.currentGroupName ?: "Не выбрана руководителем",
                )
            }
            if (state.operationalAvailability == "DISABLED") {
                item {
                    Text(
                        "Группа временно недоступна",
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            item { ProfileSectionTitle("Специальности") }
            if (state.qualifications.isEmpty()) {
                item { ProfileEmptyCard("Специальности пока не назначены") }
            } else {
                items(state.qualifications, key = { "qualification-$it" }) { qualification ->
                    ProfileValueCard(qualification)
                }
            }
            item { ProfileSectionTitle("Бригады") }
            if (state.groups.isEmpty()) {
                item { ProfileEmptyCard("Бригады пока не назначены") }
            } else {
                items(state.groups, key = ProfileGroupUi::id) { group ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (group.id == state.currentGroupId) "✓" else "•",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(group.name, style = MaterialTheme.typography.titleMedium)
                                if (group.workerClassName.isNotBlank()) {
                                    Text(
                                        group.workerClassName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            state.avatarError?.let { message ->
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        WorkerOutlinedButton(onClick = onRefresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Text("Повторить")
                        }
                    }
                }
            }
            item {
                WorkerButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                    Text("Выйти")
                }
            }
        }
    }
}

@Composable
private fun ProfileReadOnlyField(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(WorkerGlassSurface, RoundedCornerShape(16.dp))
            .border(1.dp, WorkerGlassBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProfileSectionTitle(title: String) {
    Text(
        title,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ProfileValueCard(value: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(value, modifier = Modifier.fillMaxWidth().padding(16.dp))
    }
}

@Composable
private fun ProfileEmptyCard(value: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(
            value,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
