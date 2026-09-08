package dev.buhanzaz.rwms.client.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.buhanzaz.rwms.client.BuildConfig
import dev.buhanzaz.rwms.client.data.CustomerEntityType
import dev.buhanzaz.rwms.client.data.CustomerProfile
import dev.buhanzaz.rwms.client.data.CustomerWarehouse

/** Edits the saved registration profile and its rental and delivery document fields. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileFormScreen(
    existing: CustomerProfile,
    busy: Boolean,
    onSave: (CustomerProfile) -> Unit,
    errorMessage: String? = null,
    onAvatarCropped: (ByteArray) -> Unit = {},
    onBack: (() -> Unit)? = null,
    onAvatarEditingChanged: (Boolean) -> Unit = {},
) {
    val type = existing.entityType
    var firstName by rememberSaveable(existing.id, existing.firstName) { mutableStateOf(existing.firstName.orEmpty()) }
    var lastName by rememberSaveable(existing.id, existing.lastName) { mutableStateOf(existing.lastName.orEmpty()) }
    var company by rememberSaveable(existing.id, existing.companyName) { mutableStateOf(existing.companyName.orEmpty()) }
    var phone by rememberSaveable(existing.id, existing.phone) {
        mutableStateOf(existing.phone)
    }
    var email by rememberSaveable(existing.id, existing.email) {
        mutableStateOf(existing.email.orEmpty())
    }
    var info by rememberSaveable(existing.id, existing.additionalInfo) { mutableStateOf(existing.additionalInfo.orEmpty()) }
    var pendingAvatar by rememberSaveable(existing.id) { mutableStateOf<Uri?>(null) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) pendingAvatar = uri
    }
    DisposableEffect(pendingAvatar != null) {
        onAvatarEditingChanged(pendingAvatar != null)
        onDispose { onAvatarEditingChanged(false) }
    }
    pendingAvatar?.let { uri ->
        CustomerAvatarCropScreen(
            uri = uri,
            onCancel = { pendingAvatar = null },
            onConfirm = { jpeg ->
                pendingAvatar = null
                onAvatarCropped(jpeg)
            },
        )
        return
    }
    val draft = CustomerProfile(
        id = existing.id,
        version = existing.version,
        entityType = type,
        firstName = firstName.trim().takeIf { type == CustomerEntityType.INDIVIDUAL },
        lastName = lastName.trim().takeIf { type == CustomerEntityType.INDIVIDUAL },
        companyName = company.trim().takeIf { type == CustomerEntityType.LEGAL },
        phone = phone.trim(),
        email = email.trim().takeIf(String::isNotBlank),
        additionalInfo = info.trim().takeIf(String::isNotBlank),
        avatar = existing.avatar,
    )
    val valid = draft.phone.isNotBlank() && when (type) {
        CustomerEntityType.INDIVIDUAL -> !draft.firstName.isNullOrBlank() && !draft.lastName.isNullOrBlank()
        CustomerEntityType.LEGAL -> !draft.companyName.isNullOrBlank()
    }
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            CustomerTopBar(
                title = "Профиль",
                onBack = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .padding(horizontal = 16.dp).testTag("profile-screen"),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (busy) {
                item { CustomerLoadingLine(tag = "profile-loading") }
            } else {
                errorMessage?.let { message ->
                    item {
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("profile-error"),
                        )
                    }
                }
            }
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
                Text(
                    "Контакты",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp),
                )
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

            item {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { onSave(draft) },
                    modifier = Modifier.fillMaxWidth().testTag("profile-save"),
                    enabled = !busy && valid && draft != existing,
                ) { Text("Сохранить") }
            }
            item { CustomerNotificationPermission() }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag("profile-legal-entity-access"),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(
                            Icons.Outlined.Apartment,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text("Доступ для юрлиц", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (type == CustomerEntityType.LEGAL) {
                                "Вы используете профиль юридического лица. Данные компании и контакты можно изменить выше."
                            } else {
                                "Регистрация юридических лиц пока недоступна. Сейчас вы используете личный профиль для аренды."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.size(96.dp).testTag("profile-avatar-preview"),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant),
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
                Surface(
                    onClick = onPick,
                    enabled = !busy,
                    modifier = Modifier.align(Alignment.BottomEnd).size(48.dp).testTag("profile-avatar-picker"),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = "Изменить фото профиля", modifier = Modifier.size(22.dp))
                    }
                }
            }
            Text(
                if (profile.entityType == CustomerEntityType.LEGAL) profile.companyName.orEmpty()
                else listOfNotNull(profile.firstName, profile.lastName).joinToString(" "),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "Данные для аренды и доставки",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    onMenu: (() -> Unit)?,
    onProfile: () -> Unit,
    avatarUrl: String? = null,
    onBack: (() -> Unit)? = null,
    allowRemember: Boolean = true,
    errorMessage: String? = null,
) {
    var rememberWarehouse by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = { CustomerTopBar("Выбор города", onMenu, onProfile, avatarUrl = avatarUrl, onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).testTag("warehouse-screen"),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) {
                item { CustomerLoadingLine(tag = "warehouse-loading") }
            }
            errorMessage?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("guest-catalog-error")) }
            }
            items(warehouses, key = CustomerWarehouse::id) { warehouse ->
                Surface(
                    onClick = { onSelect(warehouse, allowRemember && rememberWarehouse) },
                    modifier = Modifier.fillMaxWidth().testTag("warehouse-option-${warehouse.id}"),
                    enabled = !busy,
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp).testTag("warehouse-location-icon"),
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(warehouse.customerCityLabel(), style = MaterialTheme.typography.titleMedium)
                            if (warehouses.count { it.customerCityLabel() == warehouse.customerCityLabel() } > 1) {
                                Text(
                                    warehouse.address ?: warehouse.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            if (warehouses.isEmpty() && !busy && errorMessage == null) {
                item {
                    Text(
                        "Нет доступных городов",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (warehouses.isNotEmpty() && allowRemember) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .toggleable(
                                value = rememberWarehouse,
                                enabled = !busy,
                                role = Role.Checkbox,
                                onValueChange = { rememberWarehouse = it },
                            )
                            .padding(vertical = 8.dp)
                            .testTag("remember-warehouse"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = rememberWarehouse, onCheckedChange = null, enabled = !busy)
                        Text(
                            "Запомнить выбранный город",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
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
