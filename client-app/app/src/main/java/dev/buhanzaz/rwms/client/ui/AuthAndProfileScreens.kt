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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.buhanzaz.rwms.client.BuildConfig
import dev.buhanzaz.rwms.client.auth.RegistrationValidator
import dev.buhanzaz.rwms.client.data.CustomerEntityType
import dev.buhanzaz.rwms.client.data.CustomerProfile
import dev.buhanzaz.rwms.client.data.CustomerWarehouse

/** Shared centered authentication frame that remains readable on tablets and with the IME open. */
@Composable
private fun AuthFrame(title: String, message: String?, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().imePadding().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 460.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Default.Warehouse, contentDescription = null, modifier = Modifier.size(48.dp))
            Text("RWMS Клиент", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            content()
        }
    }
}

/** Native username/password entry; credentials are handed directly to the ephemeral PKCE exchange. */
@Composable
fun LoginScreen(
    message: String?,
    submitting: Boolean,
    onLogin: (String, String) -> Unit,
    onRegister: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AuthFrame("Вход", message) {
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Логин") },
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
            singleLine = true,
            enabled = !submitting,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Пароль") },
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = !submitting,
        )
        Button(
            onClick = { onLogin(username, password) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = !submitting && username.isNotBlank() && password.isNotBlank(),
        ) {
            if (submitting) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Войти")
        }
        OutlinedButton(onClick = onRegister, modifier = Modifier.fillMaxWidth(), enabled = !submitting) {
            Text("Регистрация")
        }
    }
}

/** Native customer registration with local confirmation and server-side validation. */
@Composable
fun RegistrationScreen(
    message: String?,
    submitting: Boolean,
    onSubmit: (String, String, String) -> Unit,
    onBack: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val validation = RegistrationValidator.validate(username, password, confirmation)
    AuthFrame("Регистрация", message) {
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Логин") },
            singleLine = true,
            enabled = !submitting,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Пароль") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = !submitting,
        )
        OutlinedTextField(
            value = confirmation,
            onValueChange = { confirmation = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Повторите пароль") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = !submitting,
            supportingText = { if (username.isNotEmpty() || password.isNotEmpty()) Text(validation.orEmpty()) },
            isError = validation != null && confirmation.isNotEmpty(),
        )
        Button(
            onClick = { onSubmit(username, password, confirmation) },
            modifier = Modifier.fillMaxWidth(),
            enabled = validation == null && !submitting,
        ) {
            if (submitting) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Зарегистрироваться")
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth(), enabled = !submitting) {
            Text("Уже есть аккаунт")
        }
    }
}

/** Creates a customer identity or edits its mutable rental and delivery document fields. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileFormScreen(
    existing: CustomerProfile?,
    busy: Boolean,
    onSave: (CustomerProfile) -> Unit,
    selectedWarehouse: CustomerWarehouse? = null,
    onAvatarSelected: (Uri) -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    var type by remember(existing) { mutableStateOf(existing?.entityType ?: CustomerEntityType.INDIVIDUAL) }
    var firstName by remember(existing) { mutableStateOf(existing?.firstName.orEmpty()) }
    var lastName by remember(existing) { mutableStateOf(existing?.lastName.orEmpty()) }
    var company by remember(existing) { mutableStateOf(existing?.companyName.orEmpty()) }
    var phone by remember(existing) { mutableStateOf(existing?.phone.orEmpty()) }
    var email by remember(existing) { mutableStateOf(existing?.email.orEmpty()) }
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
                        warehouseSelected = selectedWarehouse != null,
                        onPick = {
                            avatarPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    )
                }
            }
            item {
                Text("Кто арендует бытовки?", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == CustomerEntityType.INDIVIDUAL,
                        onClick = { type = CustomerEntityType.INDIVIDUAL },
                        enabled = existing == null,
                        label = { Text("Физлицо") },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    )
                    FilterChip(
                        selected = type == CustomerEntityType.LEGAL,
                        onClick = { type = CustomerEntityType.LEGAL },
                        enabled = existing == null,
                        label = { Text("Юрлицо") },
                        leadingIcon = { Icon(Icons.Default.Apartment, contentDescription = null) },
                    )
                }
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
    warehouseSelected: Boolean,
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
            enabled = !busy && warehouseSelected,
            modifier = Modifier.testTag("profile-avatar-picker"),
        ) {
            Text(if (profile.avatar == null) "Загрузить аватар" else "Изменить аватар")
        }
        if (!warehouseSelected) {
            Text(
                "Чтобы загрузить аватар, сначала выберите склад.",
                style = MaterialTheme.typography.bodySmall,
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
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().then(
            if (testTag == null) Modifier else Modifier.testTag(testTag),
        ),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        enabled = enabled,
    )
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
