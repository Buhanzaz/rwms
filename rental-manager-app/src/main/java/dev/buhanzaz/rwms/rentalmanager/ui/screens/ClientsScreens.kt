package dev.buhanzaz.rwms.rentalmanager.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.buhanzaz.rwms.rentalmanager.network.CreateRentalClientRequest
import dev.buhanzaz.rwms.rentalmanager.network.RentalClientDto
import dev.buhanzaz.rwms.rentalmanager.ui.RentalManagerUiState

@Composable
fun ClientsScreen(
    state: RentalManagerUiState,
    onSearch: (String) -> Unit,
    onLoadMore: () -> Unit,
    onClient: (String) -> Unit,
    onCreate: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(state.clientSearch) }
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) {
                Icon(Icons.Default.Add, contentDescription = "Добавить клиента")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ManagerSearchField(
                    value = query,
                    label = "Имя, телефон или контакт",
                    onValueChange = { query = it },
                    onSearch = { onSearch(query) },
                )
            }
            item { NoticeCard(state.notice, onDismissNotice) }
            if (state.session?.warehouses.isNullOrEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Column {
                                Text("Склады не назначены", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Администратор должен выдать доступ к рабочему складу.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (state.clients.isEmpty() && !state.clientsLoading) {
                item {
                    EmptyState(
                        title = "Клиенты не найдены",
                        description = "Измените запрос или добавьте нового клиента.",
                    )
                }
            } else {
                items(state.clients, key = RentalClientDto::id) { client ->
                    ClientCard(client = client, onClick = { onClient(client.id) })
                }
            }
            if (state.clientsLoading) item { InlineProgress() }
            if (state.clientsHasMore && !state.clientsLoading) {
                item {
                    Button(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                        Text("Показать ещё")
                    }
                }
            }
        }
    }
}

@Composable
private fun ClientCard(client: RentalClientDto, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(client.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append(clientTypeLabel(client.type))
                        client.phone?.let { append(" · ").append(it) }
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                client.contactPerson?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
fun ClientDetailScreen(
    state: RentalManagerUiState,
    onOrder: (String) -> Unit,
    onCreateOrder: () -> Unit,
    onRetry: () -> Unit,
    onLoadMoreOrders: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    val client = state.selectedClient
    if (client == null) {
        if (state.selectedClientLoading) {
            InlineProgress()
        } else {
            Column(modifier = Modifier.padding(16.dp)) {
                NoticeCard(state.notice, onDismissNotice)
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Повторить") }
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { NoticeCard(state.notice, onDismissNotice) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(client.displayName, style = MaterialTheme.typography.headlineSmall)
                    Text(clientTypeLabel(client.type), color = MaterialTheme.colorScheme.primary)
                    client.phone?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    client.contactPerson?.let { Text("Контакт: $it") }
                    client.email?.let { Text(it) }
                    client.responsibleManagerDisplayName?.let { Text("Менеджер: $it") }
                    client.comment?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Button(onClick = onCreateOrder, modifier = Modifier.fillMaxWidth()) {
                        Text("Создать заказ")
                    }
                }
            }
        }
        item { SectionTitle("Заказы клиента") }
        if (state.selectedClientOrders.isEmpty() && !state.selectedClientLoading) {
            item { EmptyState("Заказов пока нет", "Создайте первый заказ для этого клиента.") }
        } else {
            items(state.selectedClientOrders, key = { it.id }) { order ->
                OrderSummaryCard(order = order, onClick = { onOrder(order.id) })
            }
        }
        if (state.selectedClientLoading) item { InlineProgress() }
        if (state.selectedClientOrdersHasMore && !state.selectedClientLoading) {
            item {
                Button(onClick = onLoadMoreOrders, modifier = Modifier.fillMaxWidth()) {
                    Text("Показать ещё")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateClientScreen(
    commandRunning: Boolean,
    notice: dev.buhanzaz.rwms.rentalmanager.ui.RentalManagerNotice?,
    onDismissNotice: () -> Unit,
    onSubmit: (CreateRentalClientRequest) -> Unit,
) {
    var type by rememberSaveable { mutableStateOf("INDIVIDUAL") }
    var typeExpanded by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var contactPerson by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var comment by rememberSaveable { mutableStateOf("") }
    var validationMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val contactRequired = type == "LEGAL_ENTITY" || type == "SOLE_PROPRIETOR"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().then(FormMaxWidth),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NoticeCard(notice, onDismissNotice)
            validationMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            ExposedDropdownMenuBox(
                expanded = typeExpanded,
                onExpandedChange = { typeExpanded = !typeExpanded },
            ) {
                OutlinedTextField(
                    value = clientTypeLabel(type),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Тип клиента") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = typeExpanded,
                    onDismissRequest = { typeExpanded = false },
                ) {
                    listOf("INDIVIDUAL", "SOLE_PROPRIETOR", "LEGAL_ENTITY").forEach { option ->
                        DropdownMenuItem(
                            text = { Text(clientTypeLabel(option)) },
                            onClick = {
                                type = option
                                typeExpanded = false
                            },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (type == "INDIVIDUAL") "ФИО" else "Название") },
                singleLine = true,
            )
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Телефон") },
                singleLine = true,
                keyboardOptions = PhoneKeyboardOptions,
            )
            if (contactRequired) {
                OutlinedTextField(
                    value = contactPerson,
                    onValueChange = { contactPerson = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Контактное лицо") },
                    singleLine = true,
                )
            }
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Электронная почта, необязательно") },
                singleLine = true,
            )
            OutlinedTextField(
                value = comment,
                onValueChange = { comment = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Комментарий, необязательно") },
                minLines = 3,
            )
            Button(
                enabled = !commandRunning,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val validationError = when {
                        name.isBlank() -> "Укажите имя или название клиента."
                        phone.trim().length < 7 -> "Укажите корректный телефон."
                        contactRequired && contactPerson.isBlank() ->
                            "Укажите контактное лицо."
                        else -> null
                    }
                    validationMessage = validationError
                    if (validationError == null) {
                        onSubmit(
                            CreateRentalClientRequest(
                                clientType = type,
                                displayName = name.trim(),
                                phone = phone.trim(),
                                contactPerson = contactPerson.trim().ifEmpty { null },
                                email = email.trim().ifEmpty { null },
                                comment = comment.trim().ifEmpty { null },
                                source = "ANDROID_MANAGER",
                            ),
                        )
                    }
                },
            ) {
                Text(if (commandRunning) "Создаём…" else "Создать клиента")
            }
        }
    }
}
