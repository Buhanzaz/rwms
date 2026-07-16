package dev.buhanzaz.wmspanel.view.reservation;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import dev.buhanzaz.wmspanel.entity.RentalCategory;
import dev.buhanzaz.wmspanel.entity.RentalItem;
import dev.buhanzaz.wmspanel.entity.RentalSubcategory;
import dev.buhanzaz.wmspanel.entity.RentalType;
import dev.buhanzaz.wmspanel.entity.Reservation;
import dev.buhanzaz.wmspanel.entity.ReservationClientType;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.service.ReservationSearchSettingsService;
import dev.buhanzaz.wmspanel.service.ReservationService;
import dev.buhanzaz.wmspanel.service.ViewStateService;
import dev.buhanzaz.wmspanel.view.main.MainView;
import dev.buhanzaz.wmspanel.view.rentalitem.RentalItemStatusSupport;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Route(value = "reservation-search", layout = MainView.class)
@ViewController(id = "Reservation.search")
@ViewDescriptor(path = "reservation-search-view.xml")
public class ReservationSearchView extends StandardListView<RentalItem> {

    @Autowired
    private DataManager dataManager;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationSearchSettingsService reservationSearchSettingsService;

    @Autowired
    private Notifications notifications;

    @ViewComponent
    private ComboBox<Warehouse> warehouseField;

    @ViewComponent
    private ComboBox<RentalCategory> categoryField;

    @ViewComponent
    private ComboBox<RentalSubcategory> subcategoryField;

    @ViewComponent
    private ComboBox<RentalType> typeField;

    @ViewComponent
    private IntegerField quantityField;

    @ViewComponent
    private TextArea selectedItemsField;

    @ViewComponent
    private Span warningSpan;

    @ViewComponent
    private DataGrid<RentalItem> searchResultsDataGrid;

    @ViewComponent
    private Button searchButton;

    @ViewComponent
    private Button createTemporaryButton;

    @ViewComponent
    private Button reservationsButton;

    @Autowired
    private Messages messages;

    @Autowired
    private ViewStateService viewStateService;

    private final List<RentalItem> currentResults = new ArrayList<>();
    private boolean syncingFilters;
    private boolean searchAutoRefreshEligible;
    private int activePollIntervalMs = -1;
    private Registration pollRegistration;

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        registerPollListener(attachEvent.getUI());
        refreshPollingState();
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        disablePolling(detachEvent.getUI());
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
        }
        super.onDetach(detachEvent);
    }

    @Subscribe
    public void onInit(final InitEvent event) {
        configureButtons();
        configureFilters();
        configureGrid();
        reloadFilterOptions();
        clearSearchResults("");
    }

    @Subscribe("searchButton")
    public void onSearchButtonClick(final com.vaadin.flow.component.ClickEvent<Button> event) {
        runSearch();
    }

    @Subscribe("createTemporaryButton")
    public void onCreateTemporaryButtonClick(final com.vaadin.flow.component.ClickEvent<Button> event) {
        List<RentalItem> selectedItems = searchResultsDataGrid.getSelectedItems().stream().toList();
        if (selectedItems.isEmpty()) {
            notifyError(msg("smartSearch.error.selectAtLeastOne"));
            return;
        }
        if (!hasSingleWarehouse(selectedItems)) {
            notifyError(msg("smartSearch.error.singleWarehouse"));
            return;
        }

        Warehouse warehouse = selectedItems.get(0).getWarehouse();
        if (!reservationService.canReserveWarehouse(warehouse)) {
            notifyError(msg("smartSearch.error.noWarehouseAccess"));
            return;
        }

        openTemporaryReservationDialog(selectedItems, warehouse);
    }

    @Subscribe("reservationsButton")
    public void onReservationsButtonClick(final com.vaadin.flow.component.ClickEvent<Button> event) {
        getUI().ifPresent(ui -> ui.navigate("reservations"));
    }

    private void configureButtons() {
        searchButton.setText("Найти");
        searchButton.setIcon(VaadinIcon.SEARCH.create());
        createTemporaryButton.setText(msg("smartSearch.createTemporaryButton"));
        createTemporaryButton.setIcon(VaadinIcon.CLOCK.create());
        reservationsButton.setText(msg("smartSearch.reservationsButton"));
        reservationsButton.setIcon(VaadinIcon.LIST.create());
        warehouseField.setLabel(msg("reservationSearch.warehouseLabel"));
        categoryField.setLabel(msg("reservationSearch.categoryLabel"));
        subcategoryField.setLabel(msg("reservationSearch.subcategoryLabel"));
        typeField.setLabel(msg("reservationSearch.typeLabel"));
        quantityField.setLabel(msg("reservationSearch.quantityLabel"));
        selectedItemsField.setLabel(msg("reservationSearch.selectedItemsLabel"));
        warningSpan.getStyle().set("white-space", "pre-wrap");
    }

    private void configureFilters() {
        warehouseField.setItemLabelGenerator(this::warehouseLabel);
        categoryField.setItemLabelGenerator(RentalCategory::getName);
        subcategoryField.setItemLabelGenerator(RentalSubcategory::getName);
        typeField.setItemLabelGenerator(RentalType::getName);

        warehouseField.addValueChangeListener(event -> {
            viewStateService.setSelectedWarehouseId(event.getValue() == null ? null : event.getValue().getId());
            if (!syncingFilters) {
                markResultsStale();
            }
        });
        categoryField.addValueChangeListener(event -> {
            reloadSubcategories();
            reloadTypes();
            if (!syncingFilters) {
                markResultsStale();
            }
        });
        subcategoryField.addValueChangeListener(event -> {
            reloadTypes();
            if (!syncingFilters) {
                markResultsStale();
            }
        });
        typeField.addValueChangeListener(event -> {
            if (!syncingFilters) {
                markResultsStale();
            }
        });
        quantityField.addValueChangeListener(event -> {
            if (!syncingFilters) {
                markResultsStale();
            }
        });
        quantityField.setMin(1);
        quantityField.setValue(1);
        quantityField.setStepButtonsVisible(true);
    }

    private void configureGrid() {
        searchResultsDataGrid.setSelectionMode(Grid.SelectionMode.MULTI);

        Grid.Column<RentalItem> statusColumn = searchResultsDataGrid.getColumnByKey("status");
        if (statusColumn != null) {
            statusColumn.setRenderer(new ComponentRenderer<>(item ->
                    new Span(RentalItemStatusSupport.presentationFor(item.getStatus()).label())));
            statusColumn.setAutoWidth(true);
            statusColumn.setFlexGrow(0);
        }

        Grid.Column<RentalItem> availableColumn = searchResultsDataGrid.getColumnByKey("available");
        if (availableColumn != null) {
            availableColumn.setRenderer(new ComponentRenderer<>(item -> new Span(String.valueOf(reservationService.available(item)))));
            availableColumn.setAutoWidth(true);
            availableColumn.setFlexGrow(0);
        }

        searchResultsDataGrid.addSelectionListener(selection -> {
            updateSelectionSummary();
            updateCreateButtonState();
        });
    }

    private void reloadFilterOptions() {
        List<Warehouse> warehouses = reservationService.reservationWarehouses();
        warehouses.sort(Comparator.comparing(Warehouse::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(Warehouse::getName, Comparator.nullsLast(String::compareToIgnoreCase)));
        warehouseField.setItems(warehouses);
        Warehouse resolvedWarehouse = viewStateService.resolveWarehouse(
                warehouses,
                viewStateService.getSelectedWarehouseId(),
                warehouseField.getValue());
        if (!Objects.equals(warehouseField.getValue(), resolvedWarehouse)) {
            warehouseField.setValue(resolvedWarehouse);
        }

        List<RentalCategory> categories = dataManager.load(RentalCategory.class)
                .query("select e from RentalCategory e where e.active = true order by e.sortOrder, e.name")
                .list();
        categoryField.setItems(categories);

        reloadSubcategories();
        reloadTypes();
    }

    private void reloadSubcategories() {
        RentalCategory category = categoryField.getValue();
        List<RentalSubcategory> subcategories = dataManager.load(RentalSubcategory.class)
                .query("""
                        select e from RentalSubcategory e
                        where e.active = true
                          and (:category is null or e.category = :category)
                        order by e.sortOrder, e.name
                        """)
                .parameter("category", category)
                .list();
        subcategoryField.setItems(subcategories);
        RentalSubcategory selected = subcategoryField.getValue();
        if (selected != null && subcategories.stream().noneMatch(item -> item.getId() != null && item.getId().equals(selected.getId()))) {
            subcategoryField.clear();
        }
    }

    private void reloadTypes() {
        RentalCategory category = categoryField.getValue();
        RentalSubcategory subcategory = subcategoryField.getValue();
        List<RentalType> types = dataManager.load(RentalType.class)
                .query("""
                        select e
                        from RentalType e
                        where e.active = true
                          and (:category is null or e.subcategory.category = :category)
                          and (:subcategory is null or e.subcategory = :subcategory)
                        order by e.sortOrder, e.name
                        """)
                .parameter("category", category)
                .parameter("subcategory", subcategory)
                .list();
        typeField.setItems(types);
        RentalType selected = typeField.getValue();
        if (selected != null && types.stream().noneMatch(item -> item.getId() != null && item.getId().equals(selected.getId()))) {
            typeField.clear();
        }
    }

    private void runSearch() {
        ReservationService.ReservationSearchCriteria criteria = buildCriteriaFromCurrentState();
        ReservationService.ReservationSearchResult result = reservationService.searchAvailableRentalItems(criteria);
        Set<UUID> selectedIds = selectedRentalItemIds();
        currentResults.clear();
        currentResults.addAll(result.items());
        searchResultsDataGrid.setItems(currentResults);
        restoreSelection(selectedIds);
        warningSpan.setText(result.warning() == null ? "" : result.warning());
        searchAutoRefreshEligible = hasActiveSearchCriteria(criteria);
        updateSelectionSummary();
        updateCreateButtonState();
        refreshPollingState();
    }

    private ReservationService.ReservationSearchCriteria buildCriteriaFromCurrentState() {
        return new ReservationService.ReservationSearchCriteria(
                warehouseField.getValue(),
                null,
                categoryField.getValue(),
                subcategoryField.getValue(),
                typeField.getValue(),
                quantityField.getValue(),
                null,
                null,
                List.of(),
                List.of());
    }

    private void markResultsStale() {
        searchAutoRefreshEligible = false;
        clearSearchResults("Нажмите Поиск");
    }

    private void clearSearchResults(String warning) {
        currentResults.clear();
        searchResultsDataGrid.deselectAll();
        searchResultsDataGrid.setItems(currentResults);
        selectedItemsField.clear();
        createTemporaryButton.setEnabled(false);
        warningSpan.setText(warning == null ? "" : warning);
        refreshPollingState();
    }

    private void updateSelectionSummary() {
        List<RentalItem> selectedItems = searchResultsDataGrid.getSelectedItems().stream().toList();
        selectedItemsField.setValue(selectedItems.stream()
                .map(item -> {
                    String warehouseName = item.getWarehouse() == null ? "" : item.getWarehouse().getName();
                    return (item.getNumber() == null ? "" : item.getNumber()) + (warehouseName.isBlank() ? "" : " @ " + warehouseName);
                })
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse(""));
    }

    private void updateCreateButtonState() {
        List<RentalItem> selectedItems = searchResultsDataGrid.getSelectedItems().stream().toList();
        if (selectedItems.isEmpty()) {
            createTemporaryButton.setEnabled(false);
            return;
        }
        Warehouse warehouse = selectedItems.get(0).getWarehouse();
        boolean sameWarehouse = hasSingleWarehouse(selectedItems);
        createTemporaryButton.setEnabled(sameWarehouse && reservationService.canReserveWarehouse(warehouse));
    }

    private boolean hasSingleWarehouse(List<RentalItem> items) {
        if (items.isEmpty()) {
            return false;
        }
        Warehouse warehouse = items.get(0).getWarehouse();
        return items.stream().allMatch(item -> item.getWarehouse() != null
                && warehouse != null
                && item.getWarehouse().getId().equals(warehouse.getId()));
    }

    private void openTemporaryReservationDialog(List<RentalItem> selectedItems, Warehouse warehouse) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(msg("smartSearch.temporaryDialog.title"));

        ComboBox<ReservationClientType> clientTypeField = new ComboBox<>(msg("smartSearch.temporaryDialog.clientType"));
        clientTypeField.setItems(ReservationClientType.values());
        clientTypeField.setItemLabelGenerator(this::clientTypeLabel);
        clientTypeField.setValue(ReservationClientType.INDIVIDUAL);

        TextField individualLastNameField = new TextField(msg("smartSearch.temporaryDialog.lastName"));
        TextField individualFirstNameField = new TextField(msg("smartSearch.temporaryDialog.firstName"));
        TextField individualMiddleNameField = new TextField(msg("smartSearch.temporaryDialog.middleName"));
        TextField companyNameField = new TextField(msg("smartSearch.temporaryDialog.companyName"));
        companyNameField.setWidthFull();

        DateTimePicker expiresAtField = new DateTimePicker(msg("smartSearch.temporaryDialog.expiresAt"));
        expiresAtField.setValue(OffsetDateTime.now().plusHours(24).toLocalDateTime());
        expiresAtField.setWidthFull();

        TextArea commentField = new TextArea(msg("smartSearch.temporaryDialog.comment"));
        commentField.setWidthFull();

        clientTypeField.addValueChangeListener(event -> updateClientFormState(
                clientTypeField.getValue(),
                individualLastNameField,
                individualFirstNameField,
                individualMiddleNameField,
                companyNameField));
        updateClientFormState(clientTypeField.getValue(), individualLastNameField, individualFirstNameField, individualMiddleNameField, companyNameField);

        Button saveButton = new Button(msg("smartSearch.temporaryDialog.create"), click -> {
            try {
                Reservation reservation = reservationService.createTemporaryReservation(
                        new ReservationService.TemporaryReservationCommand(
                                warehouse,
                                clientTypeField.getValue(),
                                individualLastNameField.getValue(),
                                individualFirstNameField.getValue(),
                                individualMiddleNameField.getValue(),
                                companyNameField.getValue(),
                                reservationService.currentUser(),
                                expiresAtField.getValue() == null ? null : expiresAtField.getValue().atOffset(ZoneOffset.ofHours(3)),
                                commentField.getValue(),
                                selectedItems));
                runSearch();
                dialog.close();
                notifications.create(msg("smartSearch.temporaryDialog.created"))
                        .withPosition(Notification.Position.TOP_END)
                        .withDuration(2200)
                        .show();
                getUI().ifPresent(ui -> ui.navigate("reservations/" + reservation.getId()));
            } catch (Exception ex) {
                runSearch();
                notifyError(ex.getMessage() == null ? msg("smartSearch.temporaryDialog.createFailed") : ex.getMessage());
            }
        });
        Button cancelButton = new Button(msg("smartSearch.temporaryDialog.cancel"), click -> dialog.close());

        VerticalLayout content = new VerticalLayout(
                clientTypeField,
                individualLastNameField,
                individualFirstNameField,
                individualMiddleNameField,
                companyNameField,
                expiresAtField,
                commentField);
        content.setPadding(false);
        dialog.add(content);
        dialog.getFooter().add(cancelButton, saveButton);
        dialog.open();
    }

    private void updateClientFormState(ReservationClientType clientType,
                                       TextField individualLastNameField,
                                       TextField individualFirstNameField,
                                       TextField individualMiddleNameField,
                                       TextField companyNameField) {
        boolean individual = clientType == null || clientType == ReservationClientType.INDIVIDUAL;
        individualLastNameField.setEnabled(individual);
        individualFirstNameField.setEnabled(individual);
        individualMiddleNameField.setEnabled(individual);
        companyNameField.setEnabled(!individual);
    }

    private String warehouseLabel(Warehouse warehouse) {
        if (warehouse == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        if (warehouse.getCode() != null && !warehouse.getCode().isBlank()) {
            builder.append(warehouse.getCode()).append(" - ");
        }
        builder.append(warehouse.getName() == null ? "" : warehouse.getName());
        if (warehouse.getCity() != null && !warehouse.getCity().isBlank()) {
            builder.append(" (").append(warehouse.getCity()).append(")");
        }
        return builder.toString();
    }

    private String clientTypeLabel(ReservationClientType clientType) {
        if (clientType == null) {
            return "";
        }
        return switch (clientType) {
            case INDIVIDUAL -> "Физлицо";
            case LEGAL_ENTITY -> "Юрлицо";
        };
    }

    private String msg(String key) {
        return messages.getMessage(key);
    }

    private void registerPollListener(UI ui) {
        if (ui == null || pollRegistration != null) {
            return;
        }
        pollRegistration = ui.addPollListener(event -> {
            refreshPollingState();
            if (searchAutoRefreshEligible) {
                runSearch();
            }
        });
    }

    private void refreshPollingState() {
        getUI().ifPresent(ui -> {
            int nextIntervalMs = -1;
            if (searchAutoRefreshEligible) {
                int refreshSeconds = reservationSearchSettingsService.currentRefreshSeconds();
                if (refreshSeconds > 0) {
                    nextIntervalMs = refreshSeconds * 1000;
                }
            }
            if (nextIntervalMs != activePollIntervalMs) {
                if (nextIntervalMs > 0) {
                    ui.setPollInterval(nextIntervalMs);
                } else {
                    disablePolling(ui);
                }
                activePollIntervalMs = nextIntervalMs;
            }
        });
    }

    private void disablePolling(UI ui) {
        if (ui != null) {
            ui.setPollInterval(-1);
        }
        activePollIntervalMs = -1;
    }

    private boolean hasActiveSearchCriteria(ReservationService.ReservationSearchCriteria criteria) {
        if (criteria == null) {
            return false;
        }
        return criteria.warehouse() != null && criteria.warehouse().getId() != null;
    }

    private Set<UUID> selectedRentalItemIds() {
        Set<UUID> selectedIds = new LinkedHashSet<>();
        searchResultsDataGrid.getSelectedItems().stream()
                .map(RentalItem::getId)
                .filter(id -> id != null)
                .forEach(selectedIds::add);
        return selectedIds;
    }

    private void restoreSelection(Set<UUID> selectedIds) {
        if (selectedIds == null || selectedIds.isEmpty()) {
            searchResultsDataGrid.deselectAll();
            return;
        }
        Map<UUID, RentalItem> itemsById = new LinkedHashMap<>();
        currentResults.stream()
                .filter(item -> item.getId() != null)
                .forEach(item -> itemsById.put(item.getId(), item));
        searchResultsDataGrid.deselectAll();
        selectedIds.stream()
                .map(itemsById::get)
                .filter(item -> item != null)
                .forEach(searchResultsDataGrid::select);
    }

    private void notifyError(String message) {
        notifications.create(message)
                .withType(Notifications.Type.ERROR)
                .withPosition(Notification.Position.TOP_END)
                .withDuration(4200)
                .show();
    }
}
