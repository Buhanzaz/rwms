package dev.buhanzaz.rwms.rentalmanager.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.buhanzaz.rwms.rentalmanager.network.AssistantAvailableCabin
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSearchGroup
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSearchNotice
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSearchNoticeCode
import dev.buhanzaz.rwms.rentalmanager.network.AssistantCabinSearchResult
import dev.buhanzaz.rwms.rentalmanager.network.AssistantClarificationQuestion
import dev.buhanzaz.rwms.rentalmanager.network.AssistantClarificationStatus
import dev.buhanzaz.rwms.rentalmanager.network.AssistantConversation
import dev.buhanzaz.rwms.rentalmanager.network.AssistantMessage
import dev.buhanzaz.rwms.rentalmanager.network.AssistantMessageRole
import dev.buhanzaz.rwms.rentalmanager.network.RentalClientDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationDto
import dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationMode
import dev.buhanzaz.rwms.rentalmanager.network.RentalPresentationState
import dev.buhanzaz.rwms.rentalmanager.BuildConfig
import dev.buhanzaz.rwms.rentalmanager.ui.RentalManagerChatUiState
import dev.buhanzaz.rwms.rentalmanager.ui.RentalManagerUiState
import java.time.OffsetDateTime
import kotlinx.coroutines.delay

@Composable
internal fun ChatConversationsScreen(
    state: RentalManagerChatUiState,
    onRefresh: () -> Unit,
    onConversation: (String) -> Unit,
    onCreate: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    var historySelected by rememberSaveable { mutableStateOf(false) }
    val conversations = state.conversations.filter { it.archived == historySelected }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NoticeCard(state.notice, onDismissNotice)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onCreate,
                enabled = state.actorId != null,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Новый диалог")
            }
            IconButton(onClick = onRefresh, enabled = !state.conversationsLoading) {
                Icon(Icons.Default.Refresh, contentDescription = "Обновить диалоги")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = !historySelected,
                onClick = { historySelected = false },
                label = { Text("Активные") },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = historySelected,
                onClick = { historySelected = true },
                label = { Text("История") },
                modifier = Modifier.weight(1f),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                state.conversationsLoading && state.conversations.isEmpty() -> InlineProgress()
                conversations.isEmpty() -> EmptyState(
                    title = if (historySelected) "История пуста" else "Активных диалогов нет",
                    description = if (historySelected) {
                        "Завершённые диалоги появятся здесь."
                    } else {
                        "Создайте диалог и выберите существующего клиента."
                    },
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(conversations, key = AssistantConversation::id) { conversation ->
                        ConversationCard(conversation, onConversation)
                    }
                    if (state.conversationsLoading) item { InlineProgress() }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ConversationCard(
    conversation: AssistantConversation,
    onConversation: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onConversation(conversation.id) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                conversation.clientDisplayName?.takeIf(String::isNotBlank) ?: "Клиент",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            conversation.clientType?.let { type ->
                Text(
                    clientTypeLabel(type),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (conversation.archived) {
                    "Завершён · ${formatUpdatedAt(conversation.updatedAt)}"
                } else {
                    "Обновлён ${formatUpdatedAt(conversation.updatedAt)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun NewConversationScreen(
    managerState: RentalManagerUiState,
    chatState: RentalManagerChatUiState,
    onSearchClients: (String) -> Unit,
    onLoadMoreClients: () -> Unit,
    onCreateConversation: (String) -> Unit,
    onDismissNotice: () -> Unit,
    onDismissManagerNotice: () -> Unit = {},
) {
    var search by rememberSaveable { mutableStateOf(managerState.clientSearch) }
    val notice = chatState.notice ?: managerState.notice
    val dismissNotice = if (chatState.notice != null) onDismissNotice else onDismissManagerNotice

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NoticeCard(notice, dismissNotice)
        Text(
            "Выберите клиента",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Диалог использует существующую карточку клиента и её серверную историю.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ManagerSearchField(
            value = search,
            label = "Имя, название или телефон",
            onValueChange = { search = it },
            onSearch = { onSearchClients(search) },
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                managerState.clientsLoading && managerState.clients.isEmpty() -> InlineProgress()
                managerState.clients.isEmpty() -> EmptyState(
                    title = "Клиенты не найдены",
                    description = "Измените запрос или создайте клиента в разделе «Клиенты».",
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(managerState.clients, key = RentalClientDto::id) { client ->
                        ClientChoiceCard(
                            client = client,
                            creating = chatState.creatingConversation,
                            onCreate = onCreateConversation,
                        )
                    }
                    if (managerState.clientsHasMore) {
                        item {
                            OutlinedButton(
                                onClick = onLoadMoreClients,
                                enabled = !managerState.clientsLoading,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (managerState.clientsLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.height(20.dp).width(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text("Показать ещё")
                            }
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ClientChoiceCard(
    client: RentalClientDto,
    creating: Boolean,
    onCreate: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !creating) { onCreate(client.id) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    client.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    listOfNotNull(clientTypeLabel(client.type), client.phone).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (creating) "Создаём…" else "Выбрать",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
internal fun ChatConversationScreen(
    state: RentalManagerChatUiState,
    onRetry: () -> Unit,
    onArchive: () -> Unit,
    onSend: (String) -> Unit,
    onAnswer: (String, String) -> Unit,
    onSelectionChange: (Set<String>) -> Unit = {},
    onPublishPresentation: () -> Unit = {},
    onDismissNotice: () -> Unit,
) {
    val detail = state.detail
    if (state.detailLoading && detail == null) {
        InlineProgress()
        return
    }
    if (detail == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NoticeCard(state.notice, onDismissNotice)
            EmptyState("Диалог недоступен", "Обновите серверную историю и повторите попытку.")
            Button(onClick = onRetry) { Text("Повторить") }
        }
        return
    }

    val pending = detail.clarifications
        .filter { it.status == AssistantClarificationStatus.PENDING }
        .minByOrNull(AssistantClarificationQuestion::sequenceNumber)
    val selectedIds = detail.currentSelection?.rentalItemIds?.toSet().orEmpty()
    val searchResult = detail.lastSearchResult
    val selectionExpiresAt = detail.currentSelection?.expiresAt
    var selectionExpired by rememberSaveable(selectionExpiresAt) {
        mutableStateOf(selectionExpiresAt?.let(::hasExpired) ?: true)
    }
    LaunchedEffect(selectionExpiresAt) {
        val expiry = selectionExpiresAt ?: return@LaunchedEffect
        val remaining = runCatching {
            OffsetDateTime.parse(expiry).toInstant().toEpochMilli() - System.currentTimeMillis()
        }.getOrDefault(0L)
        if (remaining > 0) delay(remaining)
        selectionExpired = true
    }
    val listState = rememberLazyListState()
    val liveMessageCount = listOfNotNull(
        state.liveUserContent,
        state.liveAssistantContent.takeIf { it.isNotEmpty() || state.sending },
    ).size
    val selectionVisible = searchResult != null && selectedIds.isNotEmpty()
    val presentationVisible = state.presentation != null || state.presentationLoading
    val conversationItemCount =
        (if (detail.messages.isEmpty() && state.liveUserContent == null) 1 else 0) +
            detail.messages.size +
            liveMessageCount +
            (if (state.toolRunning) 1 else 0)
    val scrollTargetItem = when {
        presentationVisible -> conversationItemCount + if (selectionVisible) 1 else 0
        else -> conversationItemCount - 1
    }
    LaunchedEffect(
        scrollTargetItem,
        state.liveAssistantContent,
        selectedIds,
        state.presentation?.revision,
        state.presentationLoading,
    ) {
        if (scrollTargetItem >= 0) listState.animateScrollToItem(scrollTargetItem)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        NoticeCard(state.notice, onDismissNotice)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    detail.conversation.clientDisplayName?.takeIf(String::isNotBlank) ?: "Клиент",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                detail.conversation.clientType?.let { type ->
                    Text(
                        clientTypeLabel(type),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!detail.conversation.archived) {
                OutlinedButton(
                    onClick = onArchive,
                    enabled = !state.archivingConversation && !state.sending &&
                        !state.selectionUpdating && !state.presentationPublishing,
                ) {
                    Text(if (state.archivingConversation) "Переносим…" else "В историю")
                }
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(CHAT_CONVERSATION_CONTENT_TAG),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (detail.messages.isEmpty() && state.liveUserContent == null) {
                item {
                    EmptyState(
                        "Начните диалог",
                        "Опишите, какие бытовки нужны клиенту.",
                    )
                }
            }
            items(detail.messages, key = AssistantMessage::id) { message ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MessageBubble(message.role, message.content)
                    message.searchNotices.distinct().forEach { notice ->
                        SearchNoticeCard(notice)
                    }
                }
            }
            state.liveUserContent?.let { content ->
                item(key = "live-user") { MessageBubble(AssistantMessageRole.USER, content) }
            }
            if (state.liveAssistantContent.isNotEmpty() || state.sending) {
                item(key = "live-assistant") {
                    MessageBubble(
                        role = AssistantMessageRole.ASSISTANT,
                        content = state.liveAssistantContent.ifEmpty { "Готовим ответ…" },
                    )
                }
            }
            if (state.toolRunning) {
                item(key = "tool-progress") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp).width(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Text("Проверяем доступные варианты…")
                    }
                }
            }
            searchResult?.takeIf { selectedIds.isNotEmpty() }?.let { currentSearchResult ->
                item(key = "current-selection") {
                    AssistantSelectionPanel(
                        result = currentSearchResult,
                        selectedIds = selectedIds,
                        expiresAt = selectionExpiresAt,
                        expired = selectionExpired,
                        readOnly = detail.conversation.archived,
                        updating = state.selectionUpdating,
                        publishing = state.presentationPublishing,
                        presentationLoading = state.presentationLoading,
                        publicationBlocked = state.presentation?.let(
                            ::presentationBlocksPublication,
                        ) ?: false,
                        onSelectionChange = onSelectionChange,
                        onPublish = onPublishPresentation,
                    )
                }
            }
            when {
                state.presentationLoading && state.presentation == null -> {
                    item(key = "presentation-loading") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(18.dp).width(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Проверяем ссылку для клиента…")
                        }
                    }
                }
                state.presentation != null -> {
                    item(key = "client-presentation") {
                        ClientPresentationCard(state.presentation)
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        when {
            detail.conversation.archived -> ArchivedConversationNotice()
            pending != null -> ClarificationCard(
                question = pending,
                sending = state.sending || state.selectionUpdating || state.presentationPublishing,
                onAnswer = onAnswer,
            )
            else -> MessageComposer(
                conversationId = detail.conversation.id,
                sending = state.sending || state.selectionUpdating || state.presentationPublishing,
                onSend = onSend,
            )
        }
    }
}

internal const val CHAT_CONVERSATION_CONTENT_TAG = "chat-conversation-content"

@Composable
private fun AssistantSelectionPanel(
    result: AssistantCabinSearchResult,
    selectedIds: Set<String>,
    expiresAt: String?,
    expired: Boolean,
    readOnly: Boolean,
    updating: Boolean,
    publishing: Boolean,
    presentationLoading: Boolean,
    publicationBlocked: Boolean,
    onSelectionChange: (Set<String>) -> Unit,
    onPublish: () -> Unit,
) {
    val groups = result.data.groups.mapIndexedNotNull { index, entry ->
        val cabins = entry.cabins.filter { selectedIds.contains(it.id) }
        if (cabins.isEmpty()) null else Triple(index, entry.group, cabins)
    }
    if (groups.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Подобранные бытовки",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (expired) {
                            "Резерв истёк — выполните новый поиск"
                        } else {
                            expiresAt?.let { "Резерв до ${formatUpdatedAt(it)}" }
                                ?: "Срок резерва недоступен"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (expired) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text(
                    "Выбрано: ${selectedIds.size}",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(groups, key = { it.first }) { (index, group, cabins) ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            assistantSearchGroupLabel(group, index),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        cabins.forEach { cabin ->
                            SelectedCabinRow(
                                cabin = cabin,
                                enabled = !readOnly && !expired && !updating,
                                onRemove = {
                                    onSelectionChange(selectedIds - cabin.id)
                                },
                            )
                        }
                    }
                }
            }
            if (!readOnly) {
                Button(
                    onClick = onPublish,
                    enabled = !expired && !updating && !publishing && !presentationLoading &&
                        !publicationBlocked,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (publishing) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp).width(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (publishing) "Создаём ссылку…" else "Создать ссылку для клиента")
                }
                OutlinedButton(
                    onClick = { onSelectionChange(emptySet()) },
                    enabled = !expired && !updating && !publishing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (updating) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp).width(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (updating) "Обновляем…" else "Освободить всю подборку")
                }
            }
        }
    }
}

@Composable
private fun ClientPresentationCard(presentation: RentalPresentationDto) {
    val context = LocalContext.current
    val publicUrl = BuildConfig.PUBLIC_BASE_URL.trimEnd('/') + presentation.publicPath
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Ссылка для клиента",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                presentationStateLabel(presentation.state),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                publicUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Просмотр доступен до ${formatUpdatedAt(presentation.viewUntil)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (presentation.mode == RentalPresentationMode.REPLACEMENT) {
                Text(
                    "Представление замены доступно здесь только для просмотра.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText("Ссылка для клиента", publicUrl),
                    )
                    Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Копировать ссылку")
            }
            OutlinedButton(
                onClick = {
                    val share = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, publicUrl)
                    context.startActivity(Intent.createChooser(share, "Отправить ссылку клиенту"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Поделиться")
            }
        }
    }
}

private fun presentationBlocksPublication(presentation: RentalPresentationDto): Boolean =
    presentation.mode == RentalPresentationMode.REPLACEMENT ||
        presentation.state == RentalPresentationState.BOOKING_PENDING ||
        presentation.state == RentalPresentationState.BOOKED

private fun presentationStateLabel(state: RentalPresentationState): String = when (state) {
    RentalPresentationState.ACTIVE -> "Ссылка активна"
    RentalPresentationState.BOOKING_PENDING -> "Клиент оформляет заказ"
    RentalPresentationState.BOOKED -> "Заказ клиента создан"
    RentalPresentationState.REVOKED -> "Ссылка отозвана"
}

@Composable
private fun SelectedCabinRow(
    cabin: AssistantAvailableCabin,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(checked = true, onCheckedChange = { onRemove() }, enabled = enabled)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    cabin.number?.takeIf(String::isNotBlank) ?: "Бытовка",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                val details = listOfNotNull(
                    cabin.rentalType?.takeIf(String::isNotBlank),
                    cabin.dimensions?.takeIf(String::isNotBlank),
                    cabin.finishing?.takeIf(String::isNotBlank),
                    cabin.category?.takeIf(String::isNotBlank),
                    cabin.characteristics?.takeIf(String::isNotBlank),
                    cabin.linoleum?.let { if (it) "С линолеумом" else "Без линолеума" },
                )
                if (details.isNotEmpty()) {
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchNoticeCard(notice: AssistantCabinSearchNotice) {
    Card(
        modifier = Modifier.fillMaxWidth(0.88f),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text(
            text = formatSearchNotice(notice),
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun formatSearchNotice(notice: AssistantCabinSearchNotice): String {
    val criteria = notice.groups.mapIndexed { index, group ->
        "«${assistantSearchGroupLabel(group, index)}»"
    }.joinToString(", ")
    val quantity = "Запрошено: ${notice.requestedQuantity}; найдено: ${notice.foundQuantity}."
    return when (notice.code) {
        AssistantCabinSearchNoticeCode.CABINS_NOT_FOUND ->
            "Не найдено: $criteria. $quantity"
        AssistantCabinSearchNoticeCode.CABINS_PARTIALLY_FOUND ->
            "Найдено меньше, чем запрошено: $criteria. $quantity"
    }
}

private fun assistantSearchGroupLabel(group: AssistantCabinSearchGroup, index: Int): String {
    val categories = group.categories?.takeIf(List<String>::isNotEmpty)?.joinToString(" или ")
        ?: group.category
    return listOfNotNull(
        group.cabinType?.displayCabinType(),
        group.finish?.takeIf(String::isNotBlank),
        group.dimensions?.takeIf(String::isNotBlank),
        categories?.takeIf(String::isNotBlank),
        group.characteristics?.takeIf(String::isNotBlank),
        group.linoleum?.let { if (it) "С линолеумом" else "Без линолеума" },
    ).joinToString(" · ").ifEmpty { "Подборка ${index + 1}" }
}

private fun String.displayCabinType(): String? = takeIf(String::isNotBlank)?.let { value ->
    when (value.uppercase()) {
        "LDSP" -> "ЛДСП"
        else -> value
    }
}

private fun hasExpired(value: String): Boolean = runCatching {
    !OffsetDateTime.parse(value).isAfter(OffsetDateTime.now())
}.getOrDefault(true)

@Composable
private fun MessageBubble(role: AssistantMessageRole, content: String) {
    val user = role == AssistantMessageRole.USER
    Column(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .align(if (user) Alignment.End else Alignment.Start),
            colors = CardDefaults.cardColors(
                containerColor = if (user) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            ),
        ) {
            Text(
                content,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ClarificationCard(
    question: AssistantClarificationQuestion,
    sending: Boolean,
    onAnswer: (String, String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Нужно уточнение", style = MaterialTheme.typography.labelLarge)
            Text(question.prompt, style = MaterialTheme.typography.bodyLarge)
            question.options.forEach { option ->
                OutlinedButton(
                    onClick = { onAnswer(question.id, option.id) },
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(option.label)
                }
            }
        }
    }
}

@Composable
private fun ArchivedConversationNotice() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Text(
            "Диалог находится в истории и доступен только для чтения.",
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MessageComposer(
    conversationId: String,
    sending: Boolean,
    onSend: (String) -> Unit,
) {
    var draft by rememberSaveable(conversationId) { mutableStateOf("") }

    fun submit() {
        val normalized = draft.trim()
        if (normalized.isEmpty() || sending) return
        onSend(normalized)
        draft = ""
    }

    OutlinedTextField(
        value = draft,
        onValueChange = { value -> if (value.length <= MAX_MESSAGE_CHARS) draft = value },
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        enabled = !sending,
        label = { Text("Сообщение") },
        maxLines = 5,
        trailingIcon = {
            IconButton(onClick = ::submit, enabled = !sending && draft.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить сообщение")
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { submit() }),
    )
}

private const val MAX_MESSAGE_CHARS = 8_000
