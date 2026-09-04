package dev.buhanzaz.rwms.client.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.buhanzaz.rwms.client.BuildConfig
import dev.buhanzaz.rwms.client.data.CustomerEntityType
import dev.buhanzaz.rwms.client.data.CustomerProfile
import dev.buhanzaz.rwms.client.data.CustomerWarehouse

/** Creates a customer identity or edits its mutable rental and delivery document fields. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileFormScreen(
    existing: CustomerProfile?,
    busy: Boolean,
    onSave: (CustomerProfile) -> Unit,
    errorMessage: String? = null,
    registrationDraft: CustomerRegistrationProfileDraft? = null,
    onAvatarSelected: (Uri) -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val type = existing?.entityType ?: CustomerEntityType.INDIVIDUAL
    var firstName by remember(existing) { mutableStateOf(existing?.firstName.orEmpty()) }
    var lastName by remember(existing) { mutableStateOf(existing?.lastName.orEmpty()) }
    var company by remember(existing) { mutableStateOf(existing?.companyName.orEmpty()) }
    var phone by remember(existing, registrationDraft) {
        mutableStateOf(existing?.phone ?: registrationDraft?.phone.orEmpty())
    }
    var email by remember(existing, registrationDraft) {
        mutableStateOf(existing?.email ?: registrationDraft?.email.orEmpty())
    }
    var info by remember(existing) { mutableStateOf(existing?.additionalInfo.orEmpty()) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onAvatarSelected)
    }
    val draft = CustomerProfile(
        id = existing?.id,
        version = existing?.version,
        entityType = type,
        firstName = firstName.trim().takeIf { type == CustomerEntityType.INDIVIDUAL },
        lastName = lastName.trim().takeIf { type == CustomerEntityType.INDIVIDUAL },
        companyName = company.trim().takeIf { type == CustomerEntityType.LEGAL },
        phone = phone.trim(),
        email = email.trim().takeIf(String::isNotBlank),
        additionalInfo = info.trim().takeIf(String::isNotBlank),
        avatar = existing?.avatar,
    )
    val valid = draft.phone.isNotBlank() && when (type) {
        CustomerEntityType.INDIVIDUAL -> !draft.firstName.isNullOrBlank() && !draft.lastName.isNullOrBlank()
        CustomerEntityType.LEGAL -> !draft.companyName.isNullOrBlank()
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(if (existing == null) "Данные клиента" else "Профиль") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).testTag("profile-screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (existing != null) {
                item {
                    ProfileAvatar(
                        profile = existing,
                        busy = busy,
                        onPick = {
                            avatarPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    )
                }
            }
            item {
                Text(
                    text = if (type == CustomerEntityType.INDIVIDUAL) {
                        "Физическое лицо"
                    } else {
                        "Юридическое лицо"
                    },
                    color = CustomerStoreNavy,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("profile-entity-type"),
                )
            }
            if (type == CustomerEntityType.INDIVIDUAL) {
                item {
                    CustomerTextField(
                        firstName,
                        { firstName = it },
                        "Имя",
                        enabled = !busy,
                        testTag = "profile-first-name",
                    )
                }
                item {
                    CustomerTextField(
                        lastName,
                        { lastName = it },
                        "Фамилия",
                        enabled = !busy,
                        testTag = "profile-last-name",
                    )
                }
            } else {
                item {
                    CustomerTextField(
                        company,
                        { company = it },
                        "Компания",
                        enabled = !busy,
                        testTag = "profile-company",
                    )
                }
            }
            item {
                CustomerTextField(
                    phone,
                    { phone = it },
                    "Телефон",
                    KeyboardType.Phone,
                    enabled = !busy,
                    testTag = "profile-phone",
                )
            }
            item {
                CustomerTextField(
                    email,
                    { email = it },
                    "Электронная почта",
                    KeyboardType.Email,
                    enabled = !busy,
                    testTag = "profile-email",
                )
            }
            item {
                CustomerTextField(
                    info,
                    { info = it },
                    "Дополнительная информация",
                    singleLine = false,
                    enabled = !busy,
                    testTag = "profile-additional-info",
                )
            }
            errorMessage?.let { message ->
                item {
                    Text(
                        text = message,
                        color = Color(0xFF8E1C1C),
                        fontSize = 12.sp,
                        modifier = Modifier.testTag("profile-error"),
                    )
                }
            }
            item {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { onSave(draft) },
                    modifier = Modifier.fillMaxWidth().testTag("profile-save"),
                    enabled = !busy && valid && (existing == null || draft != existing),
                ) { Text(if (existing == null) "Продолжить" else "Сохранить") }
                onBack?.let { back ->
                    OutlinedButton(onClick = back, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                        Text("Назад")
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/** Circular authenticated avatar preview and system photo-picker action for an existing profile. */
@Composable
private fun ProfileAvatar(
    profile: CustomerProfile,
    busy: Boolean,
    onPick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.size(112.dp).testTag("profile-avatar-preview"),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    profile.avatarInitials(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                profile.avatar?.thumbnailUrl?.takeIf(String::isNotBlank)?.let { path ->
                    AsyncImage(
                        model = customerProfileMediaUrl(path),
                        contentDescription = "Фото профиля",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        OutlinedButton(
            onClick = onPick,
            enabled = !busy,
            modifier = Modifier.testTag("profile-avatar-picker"),
        ) {
            Text(if (profile.avatar == null) "Загрузить аватар" else "Изменить аватар")
        }
    }
}

/** Lists server-approved warehouses and forwards an explicit app-local remember preference. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarehouseScreen(
    warehouses: List<CustomerWarehouse>,
    busy: Boolean,
    onSelect: (CustomerWarehouse, Boolean) -> Unit,
    onLogout: () -> Unit,
) {
    var rememberWarehouse by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Выберите склад") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp).testTag("warehouse-screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Показываем склады, которые принимают клиентские бронирования.") }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = rememberWarehouse,
                            enabled = !busy,
                            role = Role.Checkbox,
                            onValueChange = { rememberWarehouse = it },
                        )
                        .padding(vertical = 4.dp)
                        .testTag("remember-warehouse"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = rememberWarehouse,
                        onCheckedChange = null,
                        enabled = !busy,
                    )
                    Text("Запомнить выбранный склад")
                }
            }
            items(warehouses, key = CustomerWarehouse::id) { warehouse ->
                OutlinedButton(
                    onClick = { onSelect(warehouse, rememberWarehouse) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(warehouse.name, fontWeight = FontWeight.SemiBold)
                        warehouse.address?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            if (warehouses.isEmpty()) {
                item { Text("Нет доступных складов", color = MaterialTheme.colorScheme.error) }
            }
            item {
                OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("Выйти") }
            }
        }
    }
}

@Composable
private fun CustomerTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            color = CustomerStoreNavy,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        CustomerStoreInputField(
            value = value,
            onValueChange = onValueChange,
            placeholder = label,
            enabled = enabled,
            keyboardType = keyboardType,
            imeAction = ImeAction.Done,
            height = if (singleLine) 56.dp else 112.dp,
            singleLine = singleLine,
            modifier = Modifier.then(
                if (testTag == null) Modifier else Modifier.testTag(testTag),
            ),
        )
    }
}

private fun CustomerProfile.avatarInitials(): String {
    val parts = if (entityType == CustomerEntityType.LEGAL) {
        companyName.orEmpty().split(Regex("\\s+"))
    } else {
        listOf(firstName.orEmpty(), lastName.orEmpty())
    }
    return parts.filter(String::isNotBlank).take(2).mapNotNull(String::firstOrNull).joinToString("")
        .uppercase().ifBlank { "?" }
}

private fun customerProfileMediaUrl(path: String): String = when {
    path.startsWith("/api/media/v1/") -> "${BuildConfig.PUBLIC_BASE_URL}$path"
    path.startsWith("${BuildConfig.PUBLIC_BASE_URL}/api/media/v1/") -> path
    else -> ""
}
