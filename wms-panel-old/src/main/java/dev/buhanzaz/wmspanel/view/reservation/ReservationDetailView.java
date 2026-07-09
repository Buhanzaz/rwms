package dev.buhanzaz.wmspanel.view.reservation;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.*;
import dev.buhanzaz.wmspanel.service.ReservationExpirationService;
import dev.buhanzaz.wmspanel.service.ReservationService;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.core.DataManager;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.model.InstanceContainer;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Route(value = "reservations/:id", layout = MainView.class)
@ViewController(id = "Reservation.detail")
@ViewDescriptor(path = "reservation-detail-view.xml")
@EditedEntityContainer("reservationDc")
public class ReservationDetailView extends StandardDetailView<Reservation> implements BeforeEnterObserver {

    @Autowired
    private DataManager dataManager;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationExpirationService reservationExpirationService;

    @Autowired
    private Notifications notifications;

    @ViewComponent
    private TextField reservationNumberField;

    @ViewComponent
    private TextField statusField;

    @ViewComponent
    private TextField warehouseField;

    @ViewComponent
    private TextField responsibleManagerField;

    @ViewComponent
    private TextField clientTypeField;

    @ViewComponent
    private TextField individualLastNameField;

    @ViewComponent
    private TextField individualFirstNameField;

    @ViewComponent
    private TextField individualMiddleNameField;

    @ViewComponent
    private TextField companyNameField;

    @ViewComponent
    private DateTimePicker temporaryExpiresAtField;

    @ViewComponent
    private DateTimePicker paymentDueAtField;

    @ViewComponent
    private DateTimePicker confirmedAtField;

    @ViewComponent
    private DateTimePicker cancelledAtField;

    @ViewComponent
    private TextArea cancelReasonField;

    @ViewComponent
    private TextArea commentField;

    @ViewComponent
    private TextField createdByField;

    @ViewComponent
    private DateTimePicker createdDateField;

    @ViewComponent
    private DateTimePicker updatedDateField;

    @ViewComponent
    private DataGrid<ReservationLine> reservationItemsDataGrid;

    @ViewComponent
    private DataGrid<ReservationAccessory> reservationAccessoriesDataGrid;

    @ViewComponent
    private CollectionLoader<ReservationLine> reservationItemsDl;

    @ViewComponent
    private CollectionLoader<ReservationAccessory> reservationAccessoriesDl;

    @ViewComponent
    private Button moveToWaitingPaymentButton;

    @ViewComponent
    private Button confirmPaymentButton;

    @ViewComponent
    private Button releaseItemButton;

    @ViewComponent
    private Button addAccessoryButton;

    @ViewComponent
    private Button cancelButton;

    @ViewComponent
    private Button deleteExpiredButton;

    @ViewComponent
    private Button closeButton;

    private Reservation currentReservation;
    private UUID routeReservationId;

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        routeReservationId = event.getRouteParameters()
                .get("id")
                .filter(value -> !value.isBlank())
                .map(value -> {
                    try {
                        return UUID.fromString(value);
                    } catch (IllegalArgumentException ignored) {
                        return null;
                    }
                })
                .orElse(null);
    }

    @Subscribe
    public void onInit(final InitEvent event) {
        configureButtons();
        configureItemColumns();
        configureAccessoryColumns();
        updateActionButtons();
        reservationItemsDataGrid.addSelectionListener(selection -> {
            ReservationLine selectedItem = selection.getFirstSelectedItem().orElse(null);
            loadAccessories(selectedItem);
            updateActionButtons();
        });
    }

    @Subscribe
    public void onReady(final ReadyEvent event) {
        currentReservation = loadRouteReservation();
        if (currentReservation == null || currentReservation.getId() == null) {
            clearHeader();
            loadAccessories(null);
            updateActionButtons();
            return;
        }
        populateHeader(currentReservation);
        loadReservationItems();
        loadAccessories(null);
        updateActionButtons();
    }

    private Reservation loadRouteReservation() {
        if (routeReservationId == null) {
            return null;
        }
        return dataManager.load(Reservation.class)
                .id(routeReservationId)
                .optional()
                .orElse(null);
    }

    @Subscribe(id = "reservationDc", target = Target.DATA_CONTAINER)
    public void onReservationDcItemChange(final InstanceContainer.ItemChangeEvent<Reservation> event) {
        currentReservation = event.getItem();
        if (currentReservation == null) {
            return;
        }
        populateHeader(currentReservation);
        loadReservationItems();
        loadAccessories(null);
        updateActionButtons();
    }

    @Subscribe("moveToWaitingPaymentButton")
    public void onMoveToWaitingPaymentButtonClick(final com.vaadin.flow.component.ClickEvent<Button> event) {
        if (!isModifiable(currentReservation)) {
            notifyError("Нет доступа к операции");
            return;
        }

        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("В ожидание оплаты");

        DateTimePicker paymentDueField = new DateTimePicker("Срок оплаты");
        paymentDueField.setValue(OffsetDateTime.now().plusDays(3).toLocalDateTime());
        paymentDueField.setWidthFull();

        Button saveButton = new Button("Подтвердить", click -> {
            try {
                OffsetDateTime paymentDueAt = paymentDueField.getValue() == null
                        ? null
                        : paymentDueField.getValue().atOffset(ZoneOffset.ofHours(3));
                reservationExpirationService.moveToWaitingPayment(currentReservation.getId(), paymentDueAt);
                currentReservation.setStatus(ReservationStatus.WAITING_PAYMENT);
                currentReservation.setPaymentDueAt(paymentDueAt == null ? OffsetDateTime.now().plusDays(3) : paymentDueAt);
                refreshReservationState();
                dialog.close();
                notifyInfo("Резерв переведён в ожидание оплаты");
            } catch (Exception ex) {
                notifyError(ex.getMessage() == null ? "Не удалось перевести резерв" : ex.getMessage());
            }
        });
        Button cancel = new Button("Отмена", click -> dialog.close());

        VerticalLayout content = new VerticalLayout(paymentDueField);
        content.setPadding(false);
        dialog.add(content);
        dialog.getFooter().add(cancel, saveButton);
        dialog.open();
    }

    @Subscribe("confirmPaymentButton")
    public void onConfirmPaymentButtonClick(final com.vaadin.flow.component.ClickEvent<Button> event) {
        if (!isModifiable(currentReservation)) {
            notifyError("Нет доступа к операции");
            return;
        }
        try {
            reservationExpirationService.confirmPayment(currentReservation.getId());
            currentReservation.setStatus(ReservationStatus.CONFIRMED);
            currentReservation.setConfirmedAt(OffsetDateTime.now());
            refreshReservationState();
            notifyInfo("Оплата подтверждена");
        } catch (Exception ex) {
            notifyError(ex.getMessage() == null ? "Не удалось подтвердить оплату" : ex.getMessage());
        }
    }

    @Subscribe("releaseItemButton")
    public void onReleaseItemButtonClick(final com.vaadin.flow.component.ClickEvent<Button> event) {
        ReservationLine selectedItem = reservationItemsDataGrid.getSelectedItems().stream().findFirst().orElse(null);
        if (selectedItem == null) {
            notifyError("Выберите строку резерва");
            return;
        }
        if (!isModifiable(currentReservation)) {
            notifyError("Нет доступа к операции");
            return;
        }
        try {
            reservationExpirationService.releaseReservationItem(selectedItem.getId());
            refreshReservationState();
            notifyInfo("Строка резерва освобождена");
        } catch (Exception ex) {
            notifyError(ex.getMessage() == null ? "Не удалось освободить строку" : ex.getMessage());
        }
    }

    @Subscribe("addAccessoryButton")
    public void onAddAccessoryButtonClick(final com.vaadin.flow.component.ClickEvent<Button> event) {
        ReservationLine selectedItem = reservationItemsDataGrid.getSelectedItems().stream().findFirst().orElse(null);
        if (selectedItem == null) {
            notifyError("Выберите строку резерва");
            return;
        }
        if (!isModifiable(currentReservation)) {
            notifyError("Нет доступа к операции");
            return;
        }
        openAccessoryDialog(selectedItem);
    }

    @Subscribe("cancelButton")
    public void onCancelButtonClick(final com.vaadin.flow.component.ClickEvent<Button> event) {
        if (!isModifiable(currentReservation)) {
            notifyError("Нет доступа к операции");
            return;
        }

        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Отмена резерва");

        TextArea reasonField = new TextArea("Причина");
        reasonField.setWidthFull();
        reasonField.setMinHeight("10rem");

        Button saveButton = new Button("Отменить резерв", click -> {
            try {
                reservationExpirationService.cancelReservation(currentReservation.getId(), reasonField.getValue());
                currentReservation.setStatus(ReservationStatus.CANCELLED);
                currentReservation.setCancelledAt(OffsetDateTime.now());
                currentReservation.setCancelReason(reasonField.getValue());
                refreshReservationState();
                dialog.close();
                notifyInfo("Резерв отменён");
            } catch (Exception ex) {
                notifyError(ex.getMessage() == null ? "Не удалось отменить резерв" : ex.getMessage());
            }
        });
        Button cancel = new Button("Закрыть", click -> dialog.close());

        VerticalLayout content = new VerticalLayout(reasonField);
        content.setPadding(false);
        dialog.add(content);
        dialog.getFooter().add(cancel, saveButton);
        dialog.open();
    }

    @Subscribe("closeButton")
    public void onCloseButtonClick(final com.vaadin.flow.component.ClickEvent<Button> event) {
        closeWithDiscard();
    }

    @Subscribe("deleteExpiredButton")
    public void onDeleteExpiredButtonClick(final com.vaadin.flow.component.ClickEvent<Button> event) {
        if (currentReservation == null) {
            return;
        }
        try {
            reservationExpirationService.deleteExpiredReservation(currentReservation.getId());
            notifyInfo("Истёкший резерв удалён");
            closeWithDiscard();
        } catch (Exception ex) {
            notifyError(ex.getMessage() == null ? "Не удалось удалить резерв" : ex.getMessage());
        }
    }

    private void configureButtons() {
        moveToWaitingPaymentButton.setText("В ожидание оплаты");
        moveToWaitingPaymentButton.setIcon(VaadinIcon.CLOCK.create());
        confirmPaymentButton.setText("Подтвердить оплату");
        confirmPaymentButton.setIcon(VaadinIcon.CHECK.create());
        releaseItemButton.setText("Освободить позицию");
        releaseItemButton.setIcon(VaadinIcon.ROTATE_LEFT.create());
        addAccessoryButton.setText("Добавить допоборудование");
        addAccessoryButton.setIcon(VaadinIcon.PLUS.create());
        cancelButton.setText("Отменить резерв");
        cancelButton.setIcon(VaadinIcon.CLOSE.create());
        deleteExpiredButton.setText("Удалить резерв");
        deleteExpiredButton.setIcon(VaadinIcon.TRASH.create());
        closeButton.setText("Закрыть");
    }

    private void configureItemColumns() {
        Grid.Column<ReservationLine> statusColumn = reservationItemsDataGrid.getColumnByKey("status");
        if (statusColumn != null) {
            statusColumn.setRenderer(new ComponentRenderer<>(this::buildReservationItemStatusCell));
            statusColumn.setAutoWidth(true);
            statusColumn.setFlexGrow(0);
        }
    }

    private HorizontalLayout buildReservationItemStatusCell(ReservationLine item) {
        Span status = new Span(reservationItemStatusLabel(item));
        status.getStyle().set("white-space", "nowrap");

        Button releaseButton = new Button(VaadinIcon.CLOSE_SMALL.create(), click -> releaseReservationItem(item));
        releaseButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR);
        releaseButton.setTooltipText("Снять с резерва");
        releaseButton.setEnabled(canReleaseReservationItem(item));

        HorizontalLayout cell = new HorizontalLayout(status, releaseButton);
        cell.setPadding(false);
        cell.setSpacing(true);
        cell.setAlignItems(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.CENTER);
        return cell;
    }

    private void releaseReservationItem(ReservationLine item) {
        if (item == null || item.getId() == null) {
            return;
        }
        if (!canReleaseReservationItem(item)) {
            notifyError("Позицию нельзя снять с резерва");
            return;
        }
        try {
            reservationExpirationService.releaseReservationItem(item.getId());
            refreshReservationState();
            notifyInfo("Позиция снята с резерва");
        } catch (Exception ex) {
            notifyError(ex.getMessage() == null ? "Не удалось снять позицию с резерва" : ex.getMessage());
        }
    }

    private void configureAccessoryColumns() {
        Grid.Column<ReservationAccessory> statusColumn = reservationAccessoriesDataGrid.getColumnByKey("status");
        if (statusColumn != null) {
            statusColumn.setRenderer(new ComponentRenderer<>(item -> new Span(accessoryStatusLabel(item))));
            statusColumn.setAutoWidth(true);
            statusColumn.setFlexGrow(0);
        }
    }

    private void populateHeader(Reservation reservation) {
        if (reservation == null) {
            return;
        }
        reservationNumberField.setValue(valueOrBlank(reservation.getReservationNumber()));
        statusField.setValue(statusLabel(reservation));
        warehouseField.setValue(reservation.getWarehouse() == null ? "" : reservation.getWarehouse().getName());
        responsibleManagerField.setValue(reservation.getResponsibleRentalManager() == null
                ? ""
                : reservation.getResponsibleRentalManager().getDisplayName());
        clientTypeField.setValue(clientTypeLabel(reservation));
        individualLastNameField.setValue(valueOrBlank(reservation.getIndividualLastName()));
        individualFirstNameField.setValue(valueOrBlank(reservation.getIndividualFirstName()));
        individualMiddleNameField.setValue(valueOrBlank(reservation.getIndividualMiddleName()));
        companyNameField.setValue(valueOrBlank(reservation.getCompanyName()));
        temporaryExpiresAtField.setValue(reservation.getTemporaryExpiresAt() == null ? null : reservation.getTemporaryExpiresAt().toLocalDateTime());
        paymentDueAtField.setValue(reservation.getPaymentDueAt() == null ? null : reservation.getPaymentDueAt().toLocalDateTime());
        confirmedAtField.setValue(reservation.getConfirmedAt() == null ? null : reservation.getConfirmedAt().toLocalDateTime());
        cancelledAtField.setValue(reservation.getCancelledAt() == null ? null : reservation.getCancelledAt().toLocalDateTime());
        cancelReasonField.setValue(valueOrBlank(reservation.getCancelReason()));
        commentField.setValue(valueOrBlank(reservation.getComment()));
        createdByField.setValue(valueOrBlank(reservation.getCreatedBy()));
        createdDateField.setValue(reservation.getCreatedDate() == null ? null : reservation.getCreatedDate().toLocalDateTime());
        updatedDateField.setValue(reservation.getLastModifiedDate() == null ? null : reservation.getLastModifiedDate().toLocalDateTime());
    }

    private void clearHeader() {
        reservationNumberField.clear();
        statusField.clear();
        warehouseField.clear();
        responsibleManagerField.clear();
        clientTypeField.clear();
        individualLastNameField.clear();
        individualFirstNameField.clear();
        individualMiddleNameField.clear();
        companyNameField.clear();
        temporaryExpiresAtField.clear();
        paymentDueAtField.clear();
        confirmedAtField.clear();
        cancelledAtField.clear();
        cancelReasonField.clear();
        commentField.clear();
        createdByField.clear();
        createdDateField.clear();
        updatedDateField.clear();
    }

    private void loadReservationItems() {
        if (currentReservation == null) {
            reservationItemsDl.setParameter("reservationId", null);
        } else {
            reservationItemsDl.setParameter("reservationId", currentReservation.getId());
        }
        reservationItemsDl.load();
    }

    private void refreshReservationState() {
        populateHeader(currentReservation);
        loadReservationItems();
        loadAccessories(null);
        updateActionButtons();
    }

    private void loadAccessories(ReservationLine reservationItem) {
        reservationAccessoriesDl.setParameter("reservationItem", reservationItem);
        reservationAccessoriesDl.load();
    }

    private void updateActionButtons() {
        Reservation reservation = currentReservation;
        boolean canManage = isModifiable(reservation);
        ReservationStatus status = reservation == null ? null : reservation.getStatus();
        boolean openStatus = status != null && status != ReservationStatus.CANCELLED && status != ReservationStatus.EXPIRED && status != ReservationStatus.COMPLETED;
        moveToWaitingPaymentButton.setEnabled(canManage && (status == ReservationStatus.ACTIVE || status == ReservationStatus.TEMPORARY));
        confirmPaymentButton.setEnabled(canManage && status == ReservationStatus.WAITING_PAYMENT);
        cancelButton.setEnabled(canManage && openStatus);
        deleteExpiredButton.setEnabled(canManage && reservationExpirationService.isExpired(reservation, OffsetDateTime.now()));

        ReservationLine selectedItem = reservationItemsDataGrid.getSelectedItems().stream().findFirst().orElse(null);
        boolean selectedActive = selectedItem != null
                && (selectedItem.getStatus() == ReservationItemStatus.ACTIVE || selectedItem.getStatus() == ReservationItemStatus.RESERVED);
        releaseItemButton.setEnabled(canManage && selectedActive);
        addAccessoryButton.setEnabled(canManage && selectedActive);
    }

    private boolean isModifiable(Reservation reservation) {
        return reservation != null && reservationService.canManageReservation(reservation);
    }

    private String statusLabel(Reservation reservation) {
        return reservation == null ? "" : statusLabel(reservation.getStatus());
    }

    private String statusLabel(ReservationStatus status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case ACTIVE -> "Активен";
            case TEMPORARY -> "Временный";
            case WAITING_PAYMENT -> "Ожидание оплаты";
            case CONFIRMED -> "Подтверждён";
            case EXPIRED -> "Истёк";
            case CANCELLED -> "Отменён";
            case COMPLETED -> "Завершён";
        };
    }

    private String statusLabel(ReservationLine item) {
        return reservationItemStatusLabel(item);
    }

    private String reservationItemStatusLabel(ReservationLine item) {
        if (item == null || item.getStatus() == null) {
            return "";
        }
        ReservationItemStatus itemStatus = item.getStatus();
        if (itemStatus == ReservationItemStatus.ACTIVE || itemStatus == ReservationItemStatus.RESERVED) {
            Reservation reservation = item.getReservation() == null ? currentReservation : item.getReservation();
            ReservationStatus reservationStatus = reservation == null ? null : reservation.getStatus();
            ReservationType reservationType = reservation == null ? null : reservation.getReservationType();
            if (reservationStatus == ReservationStatus.TEMPORARY || reservationType == ReservationType.TEMPORARY) {
                return "Временный резерв";
            }
            if (reservationStatus == ReservationStatus.WAITING_PAYMENT
                    || reservationStatus == ReservationStatus.CONFIRMED
                    || reservationType == ReservationType.CLIENT
                    || itemStatus == ReservationItemStatus.RESERVED) {
                return "Полный резерв";
            }
            return "Резерв";
        }
        return switch (itemStatus) {
            case ACTIVE -> "Резерв";
            case RESERVED -> "Полный резерв";
            case RELEASED -> "Освобождена";
            case CANCELLED -> "Отменена";
            case EXPIRED -> "Истекла";
            case COMPLETED -> "Завершена";
        };
    }

    private boolean canReleaseReservationItem(ReservationLine item) {
        if (item == null || item.getId() == null || !isModifiable(currentReservation)) {
            return false;
        }
        ReservationItemStatus itemStatus = item.getStatus();
        if (itemStatus != ReservationItemStatus.ACTIVE && itemStatus != ReservationItemStatus.RESERVED) {
            return false;
        }
        Reservation reservation = item.getReservation() == null ? currentReservation : item.getReservation();
        if (reservation == null) {
            return false;
        }
        ReservationStatus reservationStatus = reservation.getStatus();
        return reservationStatus != ReservationStatus.CANCELLED
                && reservationStatus != ReservationStatus.EXPIRED
                && reservationStatus != ReservationStatus.COMPLETED;
    }

    private String accessoryStatusLabel(ReservationAccessory item) {
        if (item == null || item.getStatus() == null) {
            return "";
        }
        return switch (item.getStatus()) {
            case ACTIVE -> "Активно";
            case RELEASED -> "Освобождено";
            case CANCELLED -> "Отменено";
            case EXPIRED -> "Истекло";
            case COMPLETED -> "Завершено";
        };
    }

    private String clientTypeLabel(Reservation reservation) {
        if (reservation == null || reservation.getClientType() == null) {
            return "";
        }
        return switch (reservation.getClientType()) {
            case INDIVIDUAL -> "Физлицо";
            case LEGAL_ENTITY -> "Юрлицо";
        };
    }

    private void openAccessoryDialog(ReservationLine reservationItem) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Добавить допоборудование");

        List<AccessoryStockBalance> balances = dataManager.load(AccessoryStockBalance.class)
                .query("""
                        select e
                        from AccessoryStockBalance e
                        where e.warehouse = :warehouse
                          and e.quantityAvailable > 0
                        order by e.accessoryItem.category.name, e.accessoryItem.subcategory.name, e.accessoryItem.name
                        """)
                .parameter("warehouse", currentReservation.getWarehouse())
                .list();

        ComboBox<AccessoryStockBalance> accessoryField = new ComboBox<>("Позиция");
        accessoryField.setItems(balances);
        accessoryField.setItemLabelGenerator(balance -> {
            if (balance == null || balance.getAccessoryItem() == null) {
                return "";
            }
            return balance.getAccessoryItem().getName() + " (" + balance.getQuantityAvailable() + " шт.)";
        });
        accessoryField.setWidthFull();

        com.vaadin.flow.component.textfield.IntegerField quantityField = new com.vaadin.flow.component.textfield.IntegerField("Количество");
        quantityField.setMin(1);
        quantityField.setValue(1);
        quantityField.setWidth("10em");

        TextArea accessoryCommentField = new TextArea("Комментарий");
        accessoryCommentField.setWidthFull();

        accessoryField.addValueChangeListener(change -> {
            AccessoryStockBalance selected = change.getValue();
            if (selected != null) {
                quantityField.setMax(selected.getQuantityAvailable());
                quantityField.setHelperText("Доступно: " + selected.getQuantityAvailable());
            } else {
                quantityField.setMax(Integer.MAX_VALUE);
                quantityField.setHelperText(null);
            }
        });

        Button saveButton = new Button("Добавить", click -> {
            try {
                AccessoryStockBalance selected = accessoryField.getValue();
                if (selected == null) {
                    notifyError("Выберите позицию");
                    return;
                }
                reservationService.addAccessoryToReservationItem(new ReservationService.AddAccessoryCommand(
                        reservationItem,
                        selected.getAccessoryItem(),
                        quantityField.getValue(),
                        accessoryCommentField.getValue()));
                refreshReservationState();
                dialog.close();
                notifyInfo("Допоборудование добавлено");
            } catch (Exception ex) {
                notifyError(ex.getMessage() == null ? "Не удалось добавить допоборудование" : ex.getMessage());
            }
        });
        Button cancel = new Button("Отмена", click -> dialog.close());

        VerticalLayout content = new VerticalLayout(accessoryField, quantityField, accessoryCommentField);
        content.setPadding(false);
        dialog.add(content);
        dialog.getFooter().add(cancel, saveButton);
        dialog.open();
    }

    private String valueOrBlank(String value) {
        return value == null ? "" : value;
    }

    private void notifyInfo(String message) {
        notifications.create(message)
                .withPosition(Notification.Position.TOP_END)
                .withDuration(2200)
                .show();
    }

    private void notifyError(String message) {
        notifications.create(message)
                .withType(Notifications.Type.ERROR)
                .withPosition(Notification.Position.TOP_END)
                .withDuration(4200)
                .show();
    }
}
