package dev.buhanzaz.rwms.manager.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.buhanzaz.rwms.manager.auth.ManagerAuthState
import dev.buhanzaz.rwms.manager.ui.MaintenanceEditorMode
import dev.buhanzaz.rwms.manager.ui.ManagerUiState
import dev.buhanzaz.rwms.manager.ui.ManagerViewModel
import dev.buhanzaz.rwms.manager.ui.inventoryFurnitureCatalog
import dev.buhanzaz.rwms.manager.ui.components.BusyOverlay
import dev.buhanzaz.rwms.manager.ui.components.EmptyState
import dev.buhanzaz.rwms.manager.ui.components.ManagerPhotoCaptureScreen
import dev.buhanzaz.rwms.manager.ui.components.LocalManagerHeaderState
import dev.buhanzaz.rwms.manager.ui.components.ManagerHeaderState
import dev.buhanzaz.rwms.manager.ui.screens.BackgroundUploadsScreen
import dev.buhanzaz.rwms.manager.ui.screens.InventoryDashboardScreen
import dev.buhanzaz.rwms.manager.ui.screens.InventoryEditorScreen
import dev.buhanzaz.rwms.manager.ui.screens.InventoryFurnitureDecisionScreen
import dev.buhanzaz.rwms.manager.ui.screens.InventoryFurnitureScreen
import dev.buhanzaz.rwms.manager.ui.screens.InventoryPhotosScreen
import dev.buhanzaz.rwms.manager.ui.screens.INVENTORY_PHOTOS_TITLE
import dev.buhanzaz.rwms.manager.ui.screens.InventoryConfirmationScreen
import dev.buhanzaz.rwms.manager.ui.screens.InventoryCatalogScreen
import dev.buhanzaz.rwms.manager.ui.screens.LogisticsMenuScreen
import dev.buhanzaz.rwms.manager.ui.screens.MaintenanceEditorScreen
import dev.buhanzaz.rwms.manager.ui.screens.MaintenanceFurnitureScreen
import dev.buhanzaz.rwms.manager.ui.screens.MaintenanceMenuScreen
import dev.buhanzaz.rwms.manager.ui.screens.MaintenanceAcceptanceScreen
import dev.buhanzaz.rwms.manager.ui.screens.ManagerLoginScreen
import dev.buhanzaz.rwms.manager.ui.screens.ManagerMainMenuScreen
import dev.buhanzaz.rwms.manager.ui.screens.EstimatesListScreen
import dev.buhanzaz.rwms.manager.ui.screens.RepairsListScreen
import dev.buhanzaz.rwms.manager.ui.screens.RepairQueueScreen
import dev.buhanzaz.rwms.manager.ui.screens.ReturnInspectionScreen
import dev.buhanzaz.rwms.manager.ui.screens.ReturnsListScreen
import dev.buhanzaz.rwms.manager.ui.screens.ShipmentDetailScreen
import dev.buhanzaz.rwms.manager.ui.screens.ShipmentsListScreen
import dev.buhanzaz.rwms.manager.ui.screens.TransferCreateScreen
import dev.buhanzaz.rwms.manager.ui.screens.TransferDetailScreen
import dev.buhanzaz.rwms.manager.ui.screens.TransfersListScreen
import dev.buhanzaz.rwms.manager.ui.theme.ManagerTheme

/**
 * Root composable for the manager application. MainActivity only needs to
 * create the ManagerViewModel and call this function from setContent.
 */
@Composable
fun ManagerApp(viewModel: ManagerViewModel) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkMaintenanceCatalogOnResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(lifecycleOwner, viewModel) {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.checkMaintenanceCatalogOnResume()
        }
    }
    ManagerTheme {
        ManagerNavGraph(viewModel = viewModel, uiState = uiState)
    }
}

@Composable
fun ManagerNavGraph(
    viewModel: ManagerViewModel,
    uiState: ManagerUiState,
    navController: NavHostController = rememberNavController(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = uiState.message
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.dismissMessage()
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        when (uiState.authState) {
            ManagerAuthState.Loading -> ManagerLoadingScreen()
            ManagerAuthState.SignedOut,
            is ManagerAuthState.Authenticating,
            is ManagerAuthState.Failure -> ManagerLoginScreen(
                authState = uiState.authState,
                onLogin = viewModel::login,
            )
            ManagerAuthState.SignedIn -> AuthenticatedManagerNavGraph(
                navController = navController,
                uiState = uiState,
                viewModel = viewModel,
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
        BusyOverlay(
            visible = uiState.busy && uiState.authState == ManagerAuthState.SignedIn,
        )
    }
}

@Composable
private fun ManagerLoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text("Проверяем сессию", modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun AuthenticatedManagerNavGraph(
    navController: NavHostController,
    uiState: ManagerUiState,
    viewModel: ManagerViewModel,
) {
    val uploadOperations by viewModel.uploadOperations.collectAsStateWithLifecycle()
    CompositionLocalProvider(
        LocalManagerHeaderState provides ManagerHeaderState(
            warehouses = uiState.warehouses,
            selectedWarehouseId = uiState.selectedWarehouseId,
            warehouseSelectionLocked = uiState.warehouseSelectionLocked,
            serverReachable = uiState.serverReachable,
            onSelectWarehouse = viewModel::selectWarehouse,
            onCheckServer = viewModel::checkServerConnection,
        ),
    ) {
    NavHost(navController = navController, startDestination = ManagerRoute.Home.route) {
        composable(ManagerRoute.Home.route) {
            ManagerMainMenuScreen(
                onLogout = viewModel::logout,
                pendingUploadCount = uploadOperations.size,
                onOpenUploads = { navController.navigate(ManagerRoute.Uploads.route) },
                onOpenLogistics = { navController.navigate(ManagerRoute.Logistics.route) },
                onOpenInventory = { navController.navigate(ManagerRoute.Inventory.route) },
                onOpenMaintenance = {
                    navController.navigate(ManagerRoute.Maintenance.route) { launchSingleTop = true }
                },
            )
        }
        composable(ManagerRoute.Uploads.route) {
            BackgroundUploadsScreen(
                operations = uploadOperations,
                onBack = navController::popManagerBackStack,
                onRetryOperation = viewModel::retryBackgroundUpload,
                onRetryPhoto = viewModel::retryBackgroundPhoto,
                onConfirmUnaccountedFurniture =
                    viewModel::confirmUnaccountedFurnitureBackgroundUpload,
                onCancelOperation = viewModel::cancelBackgroundUpload,
            )
        }
        composable(ManagerRoute.Logistics.route) {
            LogisticsMenuScreen(
                onBack = navController::popManagerBackStack,
                onOpenReturns = { navController.navigate(ManagerRoute.Returns.route) },
                onOpenShipments = { navController.navigate(ManagerRoute.Shipments.route) },
                onOpenTransfers = { navController.navigate(ManagerRoute.Transfers.route) },
            )
        }
        composable(ManagerRoute.Returns.route) {
            ReturnsListScreen(
                uiState = uiState,
                onBack = navController::popManagerBackStack,
                onLoadReturns = viewModel::loadReturns,
                onOpenReturn = viewModel::openReturn,
                onOpenInspection = { navController.navigate(ManagerRoute.ReturnInspection.route) },
            )
        }
        composable(ManagerRoute.ReturnInspection.route) {
            val close = {
                viewModel.closeReturn()
                navController.popManagerBackStack()
            }
            ReturnInspectionScreen(
                uiState = uiState,
                onBack = close,
                onOpenPhotos = { lineId ->
                    navController.navigate(ManagerRoute.PhotoCapture.returnRoute(lineId))
                },
                onConfirmEquipment = viewModel::confirmReturnEquipment,
                onAccept = viewModel::acceptReturn,
                onStartEstimates = viewModel::startReturnEstimates,
                onEstimatesReady = { sources ->
                    if (sources.size == 1) {
                        viewModel.openEstimateEditor(sources.single().estimateId) {
                            navController.navigate(ManagerRoute.MaintenanceEditor.route) {
                                popUpTo(ManagerRoute.Returns.route) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                    } else {
                        navController.navigate(ManagerRoute.Estimates.route) {
                            popUpTo(ManagerRoute.Returns.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
            )
        }
        composable(ManagerRoute.Shipments.route) {
            ShipmentsListScreen(
                uiState = uiState,
                onBack = navController::popManagerBackStack,
                onLoad = viewModel::loadShipments,
                onOpen = { documentId ->
                    viewModel.openShipment(documentId) {
                        navController.navigate(ManagerRoute.ShipmentDetail.route) {
                            launchSingleTop = true
                        }
                    }
                },
            )
        }
        composable(ManagerRoute.ShipmentDetail.route) {
            val close = {
                viewModel.closeShipment()
                navController.popManagerBackStack()
            }
            ShipmentDetailScreen(
                uiState = uiState,
                onBack = close,
                onPlan = viewModel::planShipment,
                onCreateFurnitureTasks = viewModel::createShipmentFurnitureTasks,
                onConfirm = viewModel::confirmShipment,
                onCancel = viewModel::cancelShipment,
            )
        }
        composable(ManagerRoute.Transfers.route) {
            TransfersListScreen(
                uiState = uiState,
                onBack = navController::popManagerBackStack,
                onLoad = viewModel::loadTransfers,
                onCreate = {
                    viewModel.startTransferEditor {
                        navController.navigate(ManagerRoute.TransferCreate.route) {
                            launchSingleTop = true
                        }
                    }
                },
                onOpen = { documentId ->
                    viewModel.openTransfer(documentId) {
                        navController.navigate(ManagerRoute.TransferDetail.route) {
                            launchSingleTop = true
                        }
                    }
                },
            )
        }
        composable(ManagerRoute.TransferCreate.route) {
            val close = {
                viewModel.closeTransferEditor()
                navController.popManagerBackStack()
            }
            TransferCreateScreen(
                uiState = uiState,
                onBack = close,
                onEdit = viewModel::editTransferEditor,
                onToggleAsset = viewModel::toggleTransferAsset,
                onFurnitureQuantity = viewModel::updateTransferFurnitureQuantity,
                onResetFurniture = viewModel::resetTransferFurniture,
                onCreate = { viewModel.createTransfer(close) },
            )
        }
        composable(ManagerRoute.TransferDetail.route) {
            val close = {
                viewModel.closeTransfer()
                navController.popManagerBackStack()
            }
            TransferDetailScreen(
                uiState = uiState,
                onBack = close,
                onDepart = viewModel::departTransferLine,
                onStartArrival = { lineId ->
                    viewModel.startTransferArrival(lineId) {
                        navController.navigate(ManagerRoute.PhotoCapture.transferRoute(lineId)) {
                            launchSingleTop = true
                        }
                    }
                },
                onOpenArrivalPhotos = {
                    uiState.transferArrivalLineId?.let { lineId ->
                        navController.navigate(ManagerRoute.PhotoCapture.transferRoute(lineId)) {
                            launchSingleTop = true
                        }
                    }
                },
                onArrive = { viewModel.arriveTransferLine {} },
                onCloseArrival = viewModel::closeTransferArrival,
                onCancel = viewModel::cancelTransfer,
                onReconcile = viewModel::reconcileTransfer,
            )
        }
        composable(ManagerRoute.Inventory.route) {
            InventoryDashboardScreen(
                uiState = uiState,
                onBack = navController::popManagerBackStack,
                onLoadInventory = viewModel::loadInventory,
                onPrepareNewNumber = viewModel::prepareNewInventoryNumber,
                onOpenEditor = { navController.navigate(ManagerRoute.InventoryEditor.route) },
                onResolveConflict = viewModel::resolveInventoryConflict,
                onOpenInventoryFinding = viewModel::openInventoryFinding,
            )
        }
        composable(ManagerRoute.InventoryEditor.route) {
            val close = {
                viewModel.closeInventoryEditor()
                navController.popManagerBackStack()
            }
            InventoryEditorScreen(
                editor = uiState.inventoryEditor,
                busy = uiState.busy,
                onBack = close,
                onEdit = viewModel::editInventory,
                onChooseOrigin = viewModel::chooseInventoryOrigin,
                onOpenPhotos = {
                    navController.navigate(ManagerRoute.InventoryPhotos.route)
                },
            )
        }
        composable(ManagerRoute.InventoryPhotos.route) {
            InventoryPhotosScreen(
                editor = uiState.inventoryEditor,
                busy = uiState.busy,
                onBack = navController::popManagerBackStack,
                onOpenCamera = {
                    navController.navigate(ManagerRoute.PhotoCapture.inventoryRoute())
                },
                onAddPhoto = viewModel::addInventoryPhoto,
                onSelectCoverPhoto = viewModel::selectInventoryCoverPhoto,
                onRemovePhoto = viewModel::removeInventoryPhoto,
                onAddFurniture = {
                    navController.navigate(ManagerRoute.InventoryFurnitureDecision.route)
                },
            )
        }
        composable(ManagerRoute.InventoryFurnitureDecision.route) {
            InventoryFurnitureDecisionScreen(
                editor = uiState.inventoryEditor,
                onBack = navController::popManagerBackStack,
                onFurnitureAbsent = {
                    viewModel.editInventory { current ->
                        current.copy(
                            equipmentObservationRequested = false,
                            equipmentQuantities = current.equipmentCatalog
                                .inventoryFurnitureCatalog()
                                .associate { equipment ->
                                    equipment.id to "0"
                                },
                        )
                    }
                    navController.navigate(ManagerRoute.InventoryCatalog.route)
                },
                onFurniturePresent = {
                    viewModel.editInventory { current ->
                        current.copy(equipmentObservationRequested = true)
                    }
                    navController.navigate(ManagerRoute.InventoryFurniture.route)
                },
            )
        }
        composable(ManagerRoute.InventoryFurniture.route) {
            InventoryFurnitureScreen(
                editor = uiState.inventoryEditor,
                busy = uiState.busy,
                onBack = navController::popManagerBackStack,
                onEdit = viewModel::editInventory,
                onContinue = {
                    navController.navigate(ManagerRoute.InventoryCatalog.route) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(ManagerRoute.InventoryCatalog.route) {
            InventoryCatalogScreen(
                editor = uiState.inventoryEditor,
                uiState = uiState,
                onBack = navController::popManagerBackStack,
                onAddCatalogNodes = viewModel::addInventoryCatalogNodes,
                onRefreshCatalog = viewModel::refreshMaintenanceCatalog,
                onEditPlan = viewModel::editInventoryPlan,
                onContinue = {
                    navController.navigate(ManagerRoute.InventoryConfirmation.route) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(ManagerRoute.InventoryConfirmation.route) {
            InventoryConfirmationScreen(
                editor = uiState.inventoryEditor,
                busy = uiState.busy,
                onBack = navController::popManagerBackStack,
                onEditPlan = viewModel::editInventoryPlan,
                onSave = { _ ->
                    viewModel.saveInventoryInspection {
                        navController.navigate(ManagerRoute.Inventory.route) {
                            popUpTo(ManagerRoute.Inventory.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
            )
        }
        composable(ManagerRoute.Maintenance.route) {
            MaintenanceMenuScreen(
                onBack = navController::popManagerBackStack,
                onOpenEstimates = {
                    navController.navigate(ManagerRoute.Estimates.route) { launchSingleTop = true }
                },
                onOpenRepairs = {
                    navController.navigate(ManagerRoute.Repairs.route) {
                        launchSingleTop = true
                    }
                },
                onOpenRepairQueue = {
                    navController.navigate(ManagerRoute.RepairQueue.route) {
                        launchSingleTop = true
                    }
                },
                onOpenAcceptance = {
                    navController.navigate(ManagerRoute.Acceptance.route) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(ManagerRoute.Estimates.route) {
            EstimatesListScreen(
                uiState = uiState,
                onBack = navController::popManagerBackStack,
                onLoadMaintenance = viewModel::loadMaintenance,
                onStartEstimate = {
                    viewModel.startMaintenanceEditor(MaintenanceEditorMode.ESTIMATE) {
                        navController.navigate(ManagerRoute.MaintenanceEditor.route) {
                            launchSingleTop = true
                        }
                    }
                },
                onOpenEstimate = { estimateId ->
                    viewModel.openEstimateEditor(estimateId) {
                        navController.navigate(ManagerRoute.MaintenanceEditor.route) {
                            launchSingleTop = true
                        }
                    }
                },
            )
        }
        composable(ManagerRoute.Repairs.route) {
            RepairsListScreen(
                uiState = uiState,
                onBack = navController::popManagerBackStack,
                onLoadMaintenance = viewModel::loadMaintenance,
                onStartRepair = {
                    viewModel.startMaintenanceEditor(MaintenanceEditorMode.REPAIR) {
                        navController.navigate(ManagerRoute.MaintenanceEditor.route) {
                            launchSingleTop = true
                        }
                    }
                },
                onOpenRepair = { repairId ->
                    viewModel.openRepairEditor(repairId) {
                        navController.navigate(ManagerRoute.MaintenanceEditor.route) {
                            launchSingleTop = true
                        }
                    }
                },
            )
        }
        composable(ManagerRoute.RepairQueue.route) {
            RepairQueueScreen(
                uiState = uiState,
                onBack = navController::popManagerBackStack,
                onLoadQueue = viewModel::loadRepairQueue,
                onOpenRepair = { repairId ->
                    viewModel.openRepairEditor(repairId) {
                        navController.navigate(ManagerRoute.MaintenanceEditor.route) {
                            launchSingleTop = true
                        }
                    }
                },
                onMoveQueueEntry = viewModel::moveRepairQueueEntry,
                onSwapQueueDateColumns = viewModel::swapRepairQueueDateColumns,
            )
        }
        composable(ManagerRoute.Acceptance.route) {
            MaintenanceAcceptanceScreen(
                uiState = uiState,
                onBack = navController::popManagerBackStack,
                onLoad = viewModel::loadAcceptance,
                onOpen = viewModel::openAcceptance,
                onCloseDetail = viewModel::closeAcceptance,
                onEditComment = viewModel::editAcceptanceComment,
                onOpenPhotos = {
                    navController.navigate(ManagerRoute.PhotoCapture.acceptanceRoute()) {
                        launchSingleTop = true
                    }
                },
                onOpenCabinPhotos = viewModel::openAcceptanceCabinPhotos,
                onOpenStagePhotos = viewModel::openAcceptanceStagePhotos,
                onOpenWorkSourcePhotos = viewModel::openAcceptanceWorkSourcePhotos,
                onCloseGallery = viewModel::closeAcceptanceGallery,
                onAccept = { viewModel.acceptMaintenanceRepair {} },
                onAcceptWork = viewModel::acceptAcceptanceWork,
                onReworkWork = { repairId, workLineId ->
                    viewModel.startReworkEditorForLine(repairId, workLineId) {
                        navController.navigate(ManagerRoute.MaintenanceEditor.route) {
                            launchSingleTop = true
                        }
                    }
                },
            )
        }
        composable(ManagerRoute.MaintenanceEditor.route) {
            val closeEditor = {
                viewModel.closeMaintenanceEditor()
                navController.popManagerBackStack()
            }
            val exitEditor = {
                if (!uiState.busy) closeEditor()
            }
            MaintenanceEditorScreen(
                editor = uiState.maintenanceEditor,
                uiState = uiState,
                onBack = exitEditor,
                onUpdateAssetSearch = viewModel::updateAssetSearch,
                onSearchAssets = viewModel::searchAssets,
                onSelectAsset = viewModel::selectMaintenanceAsset,
                onAddCatalogNodes = viewModel::addMaintenanceCatalogNodes,
                onToggleReworkCandidate = viewModel::toggleReworkCandidate,
                onRefreshCatalog = viewModel::refreshMaintenanceCatalog,
                onEdit = viewModel::editMaintenance,
                onOpenPhotos = {
                    navController.navigate(ManagerRoute.PhotoCapture.maintenanceRoute()) {
                        launchSingleTop = true
                    }
                },
                onAddPhoto = viewModel::addMaintenancePhoto,
                onOpenFurniture = {
                    openMaintenanceFurnitureFlow(viewModel, navController)
                },
                onSelectCoverPhoto = viewModel::selectMaintenanceCoverPhoto,
                onSaveDraft = { _ -> viewModel.saveMaintenanceDraft(closeEditor) },
                onSubmit = { _ -> viewModel.submitMaintenance(closeEditor) },
            )
        }
        composable(ManagerRoute.MaintenanceFurniture.route) {
            val closeFurniture = {
                if (!uiState.busy) {
                    viewModel.closeMaintenanceFurniture()
                    navController.popManagerBackStack()
                }
            }
            MaintenanceFurnitureScreen(
                editor = uiState.maintenanceFurnitureEditor,
                busy = uiState.busy,
                onBack = closeFurniture,
                onQuantityChange = viewModel::updateMaintenanceFurnitureQuantity,
                onComplete = { action ->
                    viewModel.completeMaintenanceFurniture(action) {
                        viewModel.editMaintenance { editor -> editor.copy(step = 5) }
                        navController.popManagerBackStack()
                    }
                },
            )
        }
        composable(
            route = ManagerRoute.PhotoCapture.route,
            arguments = listOf(
                navArgument("target") { type = NavType.StringType; defaultValue = "" },
                navArgument("lineId") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val target = entry.arguments?.getString("target").orEmpty()
            val lineId = entry.arguments?.getString("lineId")
                ?.takeIf { it.isNotBlank() }
            val onBack: () -> Unit = navController::popManagerBackStack
            when (target) {
                ManagerRoute.PhotoCapture.TARGET_INVENTORY -> {
                    val editor = uiState.inventoryEditor
                    if (editor == null) {
                        ManagerMissingPhotoTarget(onBack)
                    } else {
                        ManagerPhotoCaptureScreen(
                            title = INVENTORY_PHOTOS_TITLE,
                            photoUris = editor.photoUris,
                            onPhotoCaptured = viewModel::addInventoryPhoto,
                            onRemovePhotoUri = viewModel::removeInventoryPhoto,
                            onBack = onBack,
                        )
                    }
                }
                ManagerRoute.PhotoCapture.TARGET_RETURN -> {
                    val validLine = lineId?.takeIf { id ->
                        uiState.selectedReturn?.lines?.any { it.id == id } == true
                    }
                    if (validLine == null) {
                        ManagerMissingPhotoTarget(onBack)
                    } else {
                        ManagerPhotoCaptureScreen(
                            title = "Фото возврата",
                            photoUris = uiState.returnPhotoUris[validLine].orEmpty(),
                            onPhotoCaptured = { uri -> viewModel.addReturnPhoto(validLine, uri) },
                            onRemovePhotoUri = { uri -> viewModel.removeReturnPhoto(validLine, uri) },
                            onBack = onBack,
                        )
                    }
                }
                ManagerRoute.PhotoCapture.TARGET_MAINTENANCE -> {
                    val editor = uiState.maintenanceEditor
                    if (editor == null) {
                        ManagerMissingPhotoTarget(onBack)
                    } else {
                        ManagerPhotoCaptureScreen(
                            title = if (editor.mode == MaintenanceEditorMode.ESTIMATE) {
                                "Фото сметы"
                            } else {
                                "Фото ремонта"
                            },
                            photoUris = editor.photoUris,
                            onPhotoCaptured = viewModel::addMaintenancePhoto,
                            onRemovePhotoUri = viewModel::removeMaintenancePhoto,
                            onBack = onBack,
                        )
                    }
                }
                ManagerRoute.PhotoCapture.TARGET_ACCEPTANCE -> {
                    val editor = uiState.acceptanceEditor
                    if (editor == null) {
                        ManagerMissingPhotoTarget(onBack)
                    } else {
                        ManagerPhotoCaptureScreen(
                            title = "Фото приёмки",
                            photoUris = editor.photoUris,
                            onPhotoCaptured = viewModel::addAcceptancePhoto,
                            onRemovePhotoUri = viewModel::removeAcceptancePhoto,
                            onBack = onBack,
                        )
                    }
                }
                ManagerRoute.PhotoCapture.TARGET_TRANSFER -> {
                    val validLine = lineId?.takeIf { id ->
                        uiState.selectedTransfer?.lines?.any { it.id == id } == true &&
                            uiState.transferArrivalLineId == id
                    }
                    if (validLine == null) {
                        ManagerMissingPhotoTarget(onBack)
                    } else {
                        ManagerPhotoCaptureScreen(
                            title = "Фото приёмки перемещения",
                            photoUris = uiState.transferPhotoUris,
                            onPhotoCaptured = viewModel::addTransferPhoto,
                            onRemovePhotoUri = viewModel::removeTransferPhoto,
                            onBack = onBack,
                        )
                    }
                }
                else -> ManagerMissingPhotoTarget(onBack)
            }
        }
    }
    }
}

@Composable
private fun ManagerMissingPhotoTarget(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        EmptyState(
            title = "Нечего фотографировать",
            description = "Вернитесь к проверке, возврату, смете или ремонту и откройте фотографии ещё раз.",
            actionLabel = "Назад",
            onAction = onBack,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

private fun NavHostController.popManagerBackStack() {
    popBackStack()
}

/**
 * Explicit navigation hook used by the maintenance editor's `onOpenFurniture` callback.
 * It loads the current cabin projection before showing the composition route.
 */
fun openMaintenanceFurnitureFlow(
    viewModel: ManagerViewModel,
    navController: NavHostController,
) {
    viewModel.openMaintenanceFurniture {
        navController.navigate(ManagerRoute.MaintenanceFurniture.route) {
            launchSingleTop = true
        }
    }
}
