package dev.buhanzaz.rwms.rentalmanager.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.buhanzaz.rwms.rentalmanager.ui.screens.ClientDetailScreen
import dev.buhanzaz.rwms.rentalmanager.ui.screens.ChatConversationScreen
import dev.buhanzaz.rwms.rentalmanager.ui.screens.ChatConversationsScreen
import dev.buhanzaz.rwms.rentalmanager.ui.screens.ClientsScreen
import dev.buhanzaz.rwms.rentalmanager.ui.screens.ConnectingScreen
import dev.buhanzaz.rwms.rentalmanager.ui.screens.CreateClientScreen
import dev.buhanzaz.rwms.rentalmanager.ui.screens.CreateOrderScreen
import dev.buhanzaz.rwms.rentalmanager.ui.screens.LoginScreen
import dev.buhanzaz.rwms.rentalmanager.ui.screens.NewConversationScreen
import dev.buhanzaz.rwms.rentalmanager.ui.screens.OrderDetailScreen
import dev.buhanzaz.rwms.rentalmanager.ui.screens.OrdersScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.Serializable

@Serializable
private data object ClientsRoute : NavKey

@Serializable
private data object OrdersRoute : NavKey

@Serializable
private data object ChatRoute : NavKey

@Serializable
private data object CreateClientRoute : NavKey

@Serializable
private data class ClientRoute(val clientId: String) : NavKey

@Serializable
private data class CreateOrderRoute(val clientId: String) : NavKey

@Serializable
private data class OrderRoute(val orderId: String) : NavKey

@Serializable
private data object CreateChatRoute : NavKey

@Serializable
private data class ChatConversationRoute(val conversationId: String) : NavKey

@Composable
fun RentalManagerApp(
    state: RentalManagerUiState,
    viewModel: RentalManagerViewModel,
    chatState: RentalManagerChatUiState,
    chatViewModel: RentalManagerChatViewModel,
) {
    when (state.phase) {
        RentalManagerPhase.LOADING -> FullScreenProgress("Проверяем безопасную сессию…")
        RentalManagerPhase.SIGNED_OUT,
        RentalManagerPhase.AUTHENTICATING,
        -> LoginScreen(
            message = state.authMessage,
            isSubmitting = state.phase == RentalManagerPhase.AUTHENTICATING,
            onLogin = viewModel::login,
        )
        RentalManagerPhase.CONNECTING -> ConnectingScreen(
            message = state.authMessage,
            onRetry = viewModel::retrySession,
            onLogout = viewModel::logout,
        )
        RentalManagerPhase.READY -> ManagerNavigation(
            state = state,
            viewModel = viewModel,
            chatState = chatState,
            chatViewModel = chatViewModel,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManagerNavigation(
    state: RentalManagerUiState,
    viewModel: RentalManagerViewModel,
    chatState: RentalManagerChatUiState,
    chatViewModel: RentalManagerChatViewModel,
) {
    val backStack = rememberNavBackStack(ChatRoute)
    val current = backStack.lastOrNull()
    DisposableEffect(current, viewModel) {
        onDispose {
            if (current is OrderRoute) viewModel.closeOrder(current.orderId)
        }
    }
    val selectedTopLevel = when (current) {
        OrdersRoute, is OrderRoute, is CreateOrderRoute -> OrdersRoute
        ChatRoute, CreateChatRoute, is ChatConversationRoute -> ChatRoute
        else -> ClientsRoute
    }

    fun showTopLevel(route: NavKey) {
        backStack.removeAll { true }
        backStack.add(route)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is RentalManagerNavigationEvent.OpenOrders -> {
                    if (backStack.lastOrNull() == OrderRoute(event.orderId)) {
                        showTopLevel(OrdersRoute)
                    }
                }
                is RentalManagerNavigationEvent.OpenClient -> {
                    if (backStack.lastOrNull() == CreateClientRoute) {
                        backStack.removeAll { it is CreateClientRoute || it is ClientRoute }
                        backStack.add(ClientRoute(event.clientId))
                    }
                }
                is RentalManagerNavigationEvent.OpenOrder -> {
                    if (backStack.lastOrNull() is CreateOrderRoute) {
                        backStack.removeAll { it is CreateOrderRoute || it is OrderRoute }
                        backStack.add(OrderRoute(event.orderId))
                    }
                }
            }
        }
    }

    LaunchedEffect(chatViewModel) {
        chatViewModel.events.collectLatest { event ->
            when (event) {
                is RentalManagerChatNavigationEvent.OpenConversation -> {
                    val activeRoute = backStack.lastOrNull()
                    if (activeRoute == ChatRoute || activeRoute is CreateChatRoute) {
                        backStack.removeAll {
                            it is CreateChatRoute || it is ChatConversationRoute
                        }
                        backStack.add(ChatConversationRoute(event.conversationId))
                    }
                }
            }
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            item(
                selected = selectedTopLevel == ChatRoute,
                onClick = { showTopLevel(ChatRoute) },
                icon = { Icon(Icons.Default.QuestionAnswer, contentDescription = null) },
                label = { Text("Чат") },
            )
            item(
                selected = selectedTopLevel == ClientsRoute,
                onClick = { showTopLevel(ClientsRoute) },
                icon = { Icon(Icons.Default.Groups, contentDescription = null) },
                label = { Text("Клиенты") },
            )
            item(
                selected = selectedTopLevel == OrdersRoute,
                onClick = { showTopLevel(OrdersRoute) },
                icon = { Icon(Icons.Default.Description, contentDescription = null) },
                label = { Text("Заказы") },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(routeTitle(current)) },
                    navigationIcon = {
                        if (current != ClientsRoute && current != OrdersRoute && current != ChatRoute) {
                            IconButton(onClick = { backStack.removeLastOrNull() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Назад",
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::logout) {
                            Icon(
                                Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = "Выйти",
                            )
                        }
                    },
                )
            },
        ) { padding ->
            NavDisplay(
                modifier = Modifier.fillMaxSize().padding(padding),
                backStack = backStack,
                onBack = { backStack.removeLastOrNull() },
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
                entryProvider = entryProvider {
                    entry<ClientsRoute> {
                        ClientsScreen(
                            state = state,
                            onSearch = viewModel::searchClients,
                            onLoadMore = viewModel::loadMoreClients,
                            onClient = { backStack.add(ClientRoute(it)) },
                            onCreate = { backStack.add(CreateClientRoute) },
                            onDismissNotice = viewModel::dismissNotice,
                        )
                    }
                    entry<CreateClientRoute> {
                        CreateClientScreen(
                            commandRunning = state.commandRunning,
                            notice = state.notice,
                            onDismissNotice = viewModel::dismissNotice,
                            onSubmit = viewModel::createClient,
                        )
                    }
                    entry<ClientRoute> { route ->
                        LaunchedEffect(route.clientId) { viewModel.openClient(route.clientId) }
                        ClientDetailScreen(
                            state = state,
                            onOrder = { backStack.add(OrderRoute(it)) },
                            onCreateOrder = { backStack.add(CreateOrderRoute(route.clientId)) },
                            onRetry = { viewModel.openClient(route.clientId) },
                            onLoadMoreOrders = viewModel::loadMoreClientOrders,
                            onDismissNotice = viewModel::dismissNotice,
                        )
                    }
                    entry<OrdersRoute> {
                        OrdersScreen(
                            state = state,
                            onSearch = viewModel::searchOrders,
                            onLoadMore = viewModel::loadMoreOrders,
                            onOrder = { backStack.add(OrderRoute(it)) },
                            onDismissNotice = viewModel::dismissNotice,
                        )
                    }
                    entry<CreateOrderRoute> { route ->
                        LaunchedEffect(route.clientId) { viewModel.openClient(route.clientId) }
                        CreateOrderScreen(
                            client = state.selectedClient,
                            loading = state.selectedClientLoading,
                            commandRunning = state.commandRunning,
                            notice = state.notice,
                            onDismissNotice = viewModel::dismissNotice,
                            onRetry = { viewModel.openClient(route.clientId) },
                            onSubmit = { phone, comment ->
                                viewModel.createOrder(route.clientId, phone, comment)
                            },
                        )
                    }
                    entry<OrderRoute> { route ->
                        LaunchedEffect(route.orderId) { viewModel.openOrder(route.orderId) }
                        OrderDetailScreen(
                            state = state.copy(
                                selectedOrder = state.selectedOrder?.takeIf { it.id == route.orderId },
                                selectedOrderPayment = state.selectedOrderPayment?.takeIf {
                                    it.payment.orderId == route.orderId
                                },
                                selectedOrderLoading = state.selectedOrderLoading ||
                                    state.selectedOrderId != route.orderId,
                            ),
                            onRetry = viewModel::refreshCurrentOrder,
                            onUpdate = viewModel::updateOrder,
                            onSave = viewModel::saveOrder,
                            onCancel = viewModel::cancelOrder,
                            onConfirmPayment = viewModel::confirmOrderPayment,
                            assistantBusy = chatState.creatingConversation ||
                                chatState.archivingConversation ||
                                chatState.sending ||
                                chatState.selectionUpdating ||
                                chatState.presentationPublishing,
                            onOpenAssistant = {
                                state.selectedOrder?.let { order ->
                                    showTopLevel(ChatRoute)
                                    chatViewModel.createConversation(
                                        clientId = order.client.id,
                                        rentalOrderId = order.id,
                                    )
                                }
                            },
                            onDismissNotice = viewModel::dismissNotice,
                        )
                    }
                    entry<ChatRoute> {
                        ChatConversationsScreen(
                            state = chatState,
                            onRefresh = chatViewModel::refreshConversations,
                            onConversation = { conversationId ->
                                if (!chatState.creatingConversation &&
                                    !chatState.archivingConversation &&
                                    !chatState.sending &&
                                    !chatState.selectionUpdating &&
                                    !chatState.presentationPublishing
                                ) {
                                    backStack.add(ChatConversationRoute(conversationId))
                                }
                            },
                            onCreate = { backStack.add(CreateChatRoute) },
                            onDismissNotice = chatViewModel::dismissNotice,
                        )
                    }
                    entry<CreateChatRoute> {
                        NewConversationScreen(
                            managerState = state,
                            chatState = chatState,
                            onSearchClients = viewModel::searchClients,
                            onLoadMoreClients = viewModel::loadMoreClients,
                            onCreateConversation = chatViewModel::createConversation,
                            onDismissNotice = chatViewModel::dismissNotice,
                            onDismissManagerNotice = viewModel::dismissNotice,
                        )
                    }
                    entry<ChatConversationRoute> { route ->
                        LaunchedEffect(route.conversationId) {
                            chatViewModel.openConversation(route.conversationId)
                        }
                        ChatConversationScreen(
                            state = chatState,
                            onRetry = {
                                chatViewModel.openConversation(route.conversationId)
                            },
                            onArchive = chatViewModel::archiveSelectedConversation,
                            onSend = chatViewModel::sendMessage,
                            onAnswer = chatViewModel::answerClarification,
                            onSelectionChange = chatViewModel::replaceSelection,
                            onPublishPresentation = chatViewModel::publishPresentation,
                            onRefreshPrices = chatViewModel::refreshRentalPrices,
                            onDismissNotice = chatViewModel::dismissNotice,
                        )
                    }
                },
            )
        }
    }
}

private fun routeTitle(route: NavKey?): String = when (route) {
    ClientsRoute -> "Клиенты"
    OrdersRoute -> "Заказы"
    ChatRoute -> "Чат"
    CreateClientRoute -> "Новый клиент"
    is ClientRoute -> "Карточка клиента"
    is CreateOrderRoute -> "Новый заказ"
    is OrderRoute -> "Заказ"
    CreateChatRoute -> "Новый диалог"
    is ChatConversationRoute -> "Диалог"
    else -> "Менеджер аренды"
}

@Composable
private fun FullScreenProgress(message: String) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text(message, modifier = Modifier.padding(top = 16.dp))
        }
    }
}
