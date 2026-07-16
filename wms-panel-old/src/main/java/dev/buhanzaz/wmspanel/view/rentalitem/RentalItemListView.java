package dev.buhanzaz.wmspanel.view.rentalitem;

import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.formlayout.FormLayout.ResponsiveStep;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.ItemClickEvent;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.event.SortEvent;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.RentalAttributeDataType;
import dev.buhanzaz.wmspanel.entity.RentalAttributeDefinition;
import dev.buhanzaz.wmspanel.entity.RentalAttributeOption;
import dev.buhanzaz.wmspanel.entity.RentalAttributeValue;
import dev.buhanzaz.wmspanel.entity.RentalClassifierAttribute;
import dev.buhanzaz.wmspanel.entity.RentalCategory;
import dev.buhanzaz.wmspanel.entity.RentalItem;
import dev.buhanzaz.wmspanel.entity.RentalItemAccessory;
import dev.buhanzaz.wmspanel.entity.RentalItemEvent;
import dev.buhanzaz.wmspanel.entity.RentalItemEventAccessory;
import dev.buhanzaz.wmspanel.entity.RentalItemEventPhoto;
import dev.buhanzaz.wmspanel.entity.RentalItemEventType;
import dev.buhanzaz.wmspanel.entity.RentalItemTag;
import dev.buhanzaz.wmspanel.entity.RentalTag;
import dev.buhanzaz.wmspanel.entity.RentalSubcategory;
import dev.buhanzaz.wmspanel.entity.RentalType;
import dev.buhanzaz.wmspanel.entity.BoardTask;
import dev.buhanzaz.wmspanel.entity.QueueEntry;
import dev.buhanzaz.wmspanel.entity.RepairProcess;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.service.RepairEstimateService;
import dev.buhanzaz.wmspanel.service.RentalItemService;
import dev.buhanzaz.wmspanel.service.RentalItemEventService;
import dev.buhanzaz.wmspanel.service.RentalTypeDisplayFormatter;
import dev.buhanzaz.wmspanel.service.ReservationService;
import dev.buhanzaz.wmspanel.service.ViewStateService;
import dev.buhanzaz.wmspanel.service.WarehouseAccessService;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.core.DataManager;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;

@Route(value = "rental-items", layout = MainView.class)
@ViewController(id = "RentalItem.list")
@ViewDescriptor(path = "rental-item-list-view.xml")
@LookupComponent("rentalItemsDataGrid")
@DialogMode(width = "72em")
public class RentalItemListView extends StandardListView<RentalItem> implements BeforeEnterObserver {

    private static final Set<String> HIDDEN_TERMINAL_ATTRIBUTE_CODES = Set.of(
            "SHOWERS",
            "TOILETS",
            "SINKS",
            "BOILER",
            "PARTS_COUNT",
            "SIZE"
    );

    @Autowired
    private Notifications notifications;

    @Autowired
    private WarehouseAccessService warehouseAccessService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private RentalItemService rentalItemService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private ViewStateService viewStateService;

    @Autowired
    private RentalItemEventService rentalItemEventService;

    @Autowired
    private RepairEstimateService repairEstimateService;

    @Autowired
    private RentalTypeDisplayFormatter rentalTypeDisplayFormatter;

    @ViewComponent
    private CollectionLoader<RentalItem> rentalItemsDl;

    @ViewComponent
    private CollectionContainer<RentalItem> rentalItemsDc;

    @ViewComponent
    private ComboBox<Warehouse> warehouseSelector;

    @ViewComponent
    private DataGrid<RentalItem> rentalItemsDataGrid;

    @ViewComponent
    private VerticalLayout detailsPane;

    @ViewComponent
    private VerticalLayout detailsHistoryPane;

    @ViewComponent
    private VerticalLayout detailsPassportPane;

    @ViewComponent
    private VerticalLayout detailsEditablePane;

    @ViewComponent
    private Button detailsEditButton;

    @ViewComponent
    private Button detailsSaveButton;

    @ViewComponent
    private Button detailsCancelButton;

    @ViewComponent
    private Button detailsHistoryButton;

    @ViewComponent
    private Button columnSettingsButton;

    @ViewComponent
    private Button resetFiltersButton;

    @ViewComponent
    private Button refreshButton;

    @ViewComponent
    private TextField detailsNumberField;

    @ViewComponent
    private ComboBox<Warehouse> detailsWarehouseField;

    @ViewComponent
    private ComboBox<String> detailsStatusField;

    @ViewComponent
    private ComboBox<RentalCategory> detailsCategoryField;

    @ViewComponent
    private ComboBox<RentalSubcategory> detailsSubcategoryField;

    @ViewComponent
    private ComboBox<RentalType> detailsTypeField;

    @ViewComponent
    private TextField detailsLastModifiedDateField;

    @ViewComponent
    private TextField detailsLastModifiedByField;

    @ViewComponent
    private FormLayout detailsSummaryForm;

    @ViewComponent
    private TextArea detailsCommentField;

    private RentalItem selectedDetailsItem;
    private boolean detailsEditMode;

    private List<Warehouse> availableWarehouses;
    private List<RentalCategory> availableCategories;
    private List<RentalTag> availableTags;
    private List<String> availableStatuses;
    private List<RentalClassifierAttribute> activeGridClassifierBindings = List.of();
    private final List<String> detailsHistoryPhotoUrls = new ArrayList<>();
    private final List<String> detailsHistoryPhotoPreviewUrls = new ArrayList<>();
    private final List<String> detailsHistoryPhotoOriginalUrls = new ArrayList<>();
    private final List<String> detailsHistoryPhotoCaptions = new ArrayList<>();
    private int detailsHistoryPhotoIndex;
    private boolean terminalLoadRequested;

    private final Map<UUID, Map<UUID, RentalAttributeValue>> gridAttributeValuesByItemId = new HashMap<>();
    private final Map<UUID, String> gridTagsByItemId = new HashMap<>();
    private final Map<String, Grid.Column<RentalItem>> gridColumnsByKey = new LinkedHashMap<>();
    private final Map<String, String> gridColumnLabels = new LinkedHashMap<>();
    private Map<String, Boolean> gridColumnVisibilityByKey = new LinkedHashMap<>();
    private VerticalLayout detailsEditableSystemPane;
    private VerticalLayout detailsEditableAdditionalPane;
    private MultiSelectComboBox<RentalTag> detailsTagsField;
    private final Map<UUID, AttributeFieldState> detailsEditableAttributeFields = new LinkedHashMap<>();
    private boolean filterSelectionProgrammaticChange;
    private RentalItemEventType historyDialogSelectedType;
    private LocalDate historyDialogFromDate;
    private LocalDate historyDialogToDate;
    private UUID pendingNavigateItemId;

    @ViewComponent
    private ComboBox<RentalCategory> filterCategoryField;

    @ViewComponent
    private ComboBox<RentalSubcategory> filterSubcategoryField;

    @ViewComponent
    private ComboBox<RentalType> filterTypeField;

    @ViewComponent
    private ComboBox<String> filterStatusField;

    @Subscribe
    public void onInit(final InitEvent event) {
        reservationService.expireTemporaryReservations();
        loadReferenceData();
        initWarehouseSelector();
        initFilterEditors();
        initDetailsEditors();
        configureGridColumns();
        applyGridColumnVisibility();
        columnSettingsButton.setIcon(VaadinIcon.COG.create());
        columnSettingsButton.setTooltipText("Настроить столбцы");
        detailsHistoryButton.setIcon(VaadinIcon.CLOCK.create());
        detailsHistoryButton.setTooltipText("Открыть историю объекта");

        Grid.Column<RentalItem> statusColumn = rentalItemsDataGrid.getColumnByKey("status");
        if (statusColumn != null) {
            statusColumn.setAutoWidth(true);
            statusColumn.setFlexGrow(0);
            statusColumn.setRenderer(new ComponentRenderer<>(this::createStatusBadge));
        }
        Grid.Column<RentalItem> typeColumn = rentalItemsDataGrid.getColumnByKey("type");
        if (typeColumn != null) {
            typeColumn.setAutoWidth(true);
            typeColumn.setRenderer(new ComponentRenderer<>(item -> new Span(displayTypeForGrid(item))));
        }

        clearDetails(false);
        rentalItemsDc.setItems(List.of());
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        pendingNavigateItemId = event.getLocation()
                .getQueryParameters()
                .getParameters()
                .getOrDefault("itemId", List.of())
                .stream()
                .findFirst()
                .map(this::parseUuidOrNull)
                .orElse(null);
    }

    @Subscribe
    public void onReady(final ReadyEvent event) {
        if (pendingNavigateItemId != null) {
            UUID itemId = pendingNavigateItemId;
            pendingNavigateItemId = null;
            openRentalItemFromNavigation(itemId);
            return;
        }
        UUID selectedItemId = viewStateService.getTerminalSelectedRentalItemId();
        if (selectedItemId == null) {
            return;
        }
        try {
            RentalItem item = reloadRentalItem(selectedItemId);
            if (matchesCurrentFilters(item)) {
                populateDetails(item);
            } else {
                viewStateService.setTerminalSelectedRentalItemId(null);
            }
        } catch (Exception ignored) {
            viewStateService.setTerminalSelectedRentalItemId(null);
        }
    }

    @Subscribe("createButton")
    public void onCreateButtonClick(final com.vaadin.flow.component.ClickEvent<Button> event) {
        openCreateItemDialog();
    }

    @Subscribe("columnSettingsButton")
    public void onColumnSettingsButtonClick(final com.vaadin.flow.component.ClickEvent<Button> event) {
        openColumnSettingsDialog();
    }

    @Subscribe("refreshButton")
    public void onRefreshButtonClick(final com.vaadin.flow.component.ClickEvent<Button> event) {
        reloadGrid();
    }

    @Subscribe("resetFiltersButton")
    public void onResetFiltersButtonClick(final com.vaadin.flow.component.ClickEvent<Button> event) {
        resetAllFilters();
        notifications.create("Фильтры сброшены")
                .withPosition(Notification.Position.TOP_END)
                .withDuration(2200)
                .show();
    }

    @Subscribe("detailsEditButton")
    public void onDetailsEditButtonClick(final com.vaadin.flow.component.ClickEvent<Button> event) {
        if (selectedDetailsItem == null) {
            return;
        }
        setDetailsEditMode(true);
    }

    @Subscribe("detailsSaveButton")
    public void onDetailsSaveButtonClick(final com.vaadin.flow.component.ClickEvent<Button> event) {
        if (selectedDetailsItem == null) {
            return;
        }

        try {
            RentalItem saved = rentalItemService.updateFromTerminal(
                    selectedDetailsItem.getId(),
                    detailsWarehouseField.getValue(),
                    detailsCategoryField.getValue(),
                    detailsSubcategoryField.getValue(),
                    detailsTypeField.getValue(),
                    detailsStatusField.getValue(),
                    detailsCommentField.getValue(),
                    collectAttributeValues(detailsEditableAttributeFields),
                    detailsTagsField == null ? List.of() : new ArrayList<>(detailsTagsField.getValue())
            );

            applySavedItemFilters(saved);
            selectedDetailsItem = reloadRentalItem(saved.getId());
            populateDetails(selectedDetailsItem);
            setDetailsEditMode(false);
            notifications.create("Изменения сохранены")
                    .withPosition(Notification.Position.TOP_END)
                    .withDuration(2200)
                    .show();
        } catch (Exception ex) {
            showError(ex, "Не удалось сохранить изменения");
        }
    }

    @Subscribe("detailsCancelButton")
    public void onDetailsCancelButtonClick(final com.vaadin.flow.component.ClickEvent<Button> event) {
        if (selectedDetailsItem != null) {
            populateDetails(reloadRentalItem(selectedDetailsItem.getId()));
        }
        setDetailsEditMode(false);
    }

    @Subscribe("detailsHistoryButton")
    public void onDetailsHistoryButtonClick(final com.vaadin.flow.component.ClickEvent<Button> event) {
        if (selectedDetailsItem == null) {
            return;
        }
        openHistoryDialog(selectedDetailsItem);
    }

    @Subscribe("rentalItemsDataGrid")
    public void onRentalItemsDataGridSort(final SortEvent<Grid<RentalItem>, ?> event) {
        notifications.create(event.getSortOrder().isEmpty() ? "Сортировка сброшена" : "Сортировка обновлена")
                .withPosition(Notification.Position.TOP_END)
                .withDuration(2200)
                .show();
    }

    @Subscribe("rentalItemsDataGrid")
    public void onRentalItemsDataGridItemClick(final ItemClickEvent<RentalItem> event) {
        RentalItem item = event.getItem();
        if (detailsEditMode) {
            notifications.create("Сначала сохраните или отмените изменения в карточке")
                    .withType(Notifications.Type.WARNING)
                    .withPosition(Notification.Position.TOP_END)
                    .withDuration(2500)
                    .show();
            return;
        }
        if (selectedDetailsItem != null && selectedDetailsItem.getId().equals(item.getId()) && detailsPane.isVisible()) {
            clearDetails();
            return;
        }
        populateDetails(item);
    }

    private void loadReferenceData() {
        availableWarehouses = warehouseAccessService.availableWarehouses();
        availableCategories = dataManager.load(RentalCategory.class)
                .query("select e from RentalCategory e where e.active = true order by e.sortOrder, e.name")
                .list();
        availableTags = rentalItemService.loadActiveTags();
        availableStatuses = RentalItemStatusSupport.STATUSES;
    }

    private void initWarehouseSelector() {
        warehouseSelector.setItemLabelGenerator(Warehouse::getName);
        warehouseSelector.setItems(availableWarehouses);
        Warehouse warehouse = viewStateService.resolveWarehouse(
                availableWarehouses,
                viewStateService.getTerminalWarehouseId(),
                warehouseAccessService.defaultWarehouse());
        warehouseSelector.setValue(warehouse);
        warehouseSelector.setReadOnly(availableWarehouses.size() <= 1);
        rentalItemsDl.setParameter("warehouse", warehouse);
        warehouseSelector.addValueChangeListener(event -> {
            if (filterSelectionProgrammaticChange) {
                return;
            }
            viewStateService.setTerminalWarehouseId(event.getValue() == null ? null : event.getValue().getId());
            rentalItemsDl.setParameter("warehouse", event.getValue());
            reloadGrid();
            clearDetails();
        });
    }

    private void initFilterEditors() {
        filterCategoryField.setItemLabelGenerator(RentalCategory::getName);
        filterCategoryField.setItems(availableCategories);
        filterSubcategoryField.setItemLabelGenerator(RentalSubcategory::getName);
        filterStatusField.setItems(availableStatuses);
        filterStatusField.setItemLabelGenerator(status -> RentalItemStatusSupport.presentationFor(status).label());
        filterTypeField.setItemLabelGenerator(rentalTypeDisplayFormatter::displayName);
        filterCategoryField.addValueChangeListener(event -> {
            if (filterSelectionProgrammaticChange) {
                return;
            }
            if (!Objects.equals(event.getOldValue(), event.getValue())) {
                filterSubcategoryField.clear();
                filterTypeField.clear();
                reloadFilterSubcategories(event.getValue());
                updateTypeFieldState(filterTypeField, List.of());
                applyFilterParameters();
            }
        });
        filterSubcategoryField.addValueChangeListener(event -> {
            if (filterSelectionProgrammaticChange) {
                return;
            }
            if (!Objects.equals(event.getOldValue(), event.getValue())) {
                filterTypeField.clear();
                reloadFilterTypes(event.getValue());
                applyFilterParameters();
            }
        });
        filterTypeField.addValueChangeListener(event -> {
            if (!filterSelectionProgrammaticChange) {
                applyFilterParameters();
            }
        });
        filterStatusField.addValueChangeListener(event -> {
            if (!filterSelectionProgrammaticChange) {
                applyFilterParameters();
            }
        });
        filterTypeField.setEnabled(false);
        filterTypeField.setPlaceholder("Недоступно");
        reloadFilterSubcategories(null);
        reloadFilterTypes(null);
        applyFilterParameters();
    }

    private void initDetailsEditors() {
        detailsWarehouseField.setItemLabelGenerator(Warehouse::getName);
        detailsWarehouseField.setItems(availableWarehouses);
        detailsCategoryField.setItemLabelGenerator(RentalCategory::getName);
        detailsCategoryField.setItems(availableCategories);
        detailsSubcategoryField.setItemLabelGenerator(RentalSubcategory::getName);
        detailsTypeField.setItemLabelGenerator(rentalTypeDisplayFormatter::displayName);
        detailsStatusField.setItems(availableStatuses);
        detailsStatusField.setItemLabelGenerator(status -> RentalItemStatusSupport.presentationFor(status).label());

        detailsCategoryField.addValueChangeListener(event -> {
            if (filterSelectionProgrammaticChange) {
                return;
            }
            if (!Objects.equals(event.getOldValue(), event.getValue())) {
                detailsSubcategoryField.clear();
                detailsTypeField.clear();
                reloadDetailsSubcategories(event.getValue());
                updateTypeFieldState(detailsTypeField, List.of());
                if (detailsEditMode) {
                    refreshDetailsEditableSections(selectedDetailsItem);
                }
            }
        });
        detailsSubcategoryField.addValueChangeListener(event -> {
            if (filterSelectionProgrammaticChange) {
                return;
            }
            if (!Objects.equals(event.getOldValue(), event.getValue())) {
                detailsTypeField.clear();
                reloadDetailsTypes(event.getValue());
                if (detailsEditMode) {
                    refreshDetailsEditableSections(selectedDetailsItem);
                }
            }
        });

        detailsNumberField.setReadOnly(true);
        detailsLastModifiedDateField.setReadOnly(true);
        detailsLastModifiedByField.setReadOnly(true);
        detailsSummaryForm.setWidthFull();
        detailsSummaryForm.setResponsiveSteps(
                new ResponsiveStep("0", 1),
                new ResponsiveStep("48em", 2)
        );
        detailsSummaryForm.add(
                detailsNumberField,
                detailsWarehouseField,
                detailsStatusField,
                detailsCategoryField,
                detailsSubcategoryField,
                detailsTypeField,
                detailsLastModifiedDateField,
                detailsLastModifiedByField,
                detailsCommentField
        );
        detailsSummaryForm.setColspan(detailsCommentField, 2);

        detailsEditablePane.removeAll();
        detailsEditableSystemPane = new VerticalLayout();
        detailsEditableSystemPane.setPadding(false);
        detailsEditableSystemPane.setSpacing(true);
        detailsEditableSystemPane.setWidthFull();
        detailsEditableAdditionalPane = new VerticalLayout();
        detailsEditableAdditionalPane.setPadding(false);
        detailsEditableAdditionalPane.setSpacing(true);
        detailsEditableAdditionalPane.setWidthFull();
        detailsTagsField = new MultiSelectComboBox<>("Теги");
        detailsTagsField.setItemLabelGenerator(RentalTag::getName);
        detailsTagsField.setItems(availableTags);
        detailsTagsField.setWidthFull();
        VerticalLayout additionalContent = new VerticalLayout(detailsEditableAdditionalPane, detailsTagsField);
        additionalContent.setPadding(false);
        additionalContent.setSpacing(true);
        additionalContent.setWidthFull();
        Details additionalDetails = new Details("Дополнительные параметры", additionalContent);
        additionalDetails.setWidthFull();
        additionalDetails.setOpened(true);

        detailsEditablePane.add(detailsEditableSystemPane, additionalDetails);
        detailsEditablePane.setVisible(false);
        setDetailsEditMode(false);
    }

    private List<RentalSubcategory> reloadDetailsSubcategories(RentalCategory category) {
        List<RentalSubcategory> subcategories = category == null
                ? List.of()
                : dataManager.load(RentalSubcategory.class)
                .query("""
                        select e from RentalSubcategory e
                        where e.active = true and e.category = :category
                        order by e.sortOrder, e.name
                        """)
                .parameter("category", category)
                .list();
        detailsSubcategoryField.setItems(subcategories);
        return subcategories;
    }

    private List<RentalType> reloadDetailsTypes(RentalSubcategory subcategory) {
        List<RentalType> types = subcategory == null
                ? List.of()
                : dataManager.load(RentalType.class)
                .query("""
                        select e from RentalType e
                        where e.active = true and e.subcategory = :subcategory
                        order by e.sortOrder, e.name
                        """)
                .parameter("subcategory", subcategory)
                .list();
        detailsTypeField.setItems(types);
        updateTypeFieldState(detailsTypeField, types);
        return types;
    }

    private List<RentalSubcategory> reloadFilterSubcategories(RentalCategory category) {
        List<RentalSubcategory> subcategories = reloadSubcategoriesForCategory(category);
        filterSubcategoryField.setItems(subcategories);
        return subcategories;
    }

    private List<RentalType> reloadFilterTypes(RentalSubcategory subcategory) {
        List<RentalType> types = subcategory == null
                ? List.of()
                : dataManager.load(RentalType.class)
                .query("""
                        select e from RentalType e
                        where e.active = true and e.subcategory = :subcategory
                        order by e.sortOrder, e.name
                        """)
                .parameter("subcategory", subcategory)
                .list();
        filterTypeField.setItems(types);
        updateTypeFieldState(filterTypeField, types);
        return types;
    }

    private void applyFilterParameters() {
        terminalLoadRequested = filterCategoryField.getValue() != null && filterCategoryField.getValue().getId() != null;
        rentalItemsDl.setParameter("loadAllowed", terminalLoadRequested);
        rentalItemsDl.setParameter("category", filterCategoryField.getValue());
        rentalItemsDl.setParameter("subcategory", filterSubcategoryField.getValue());
        rentalItemsDl.setParameter("type", filterTypeField.getValue());
        rentalItemsDl.setParameter("status", filterStatusField.getValue());
        reloadGrid();
    }

    private void reloadGrid() {
        List<RentalItem> items = loadGridItems();
        rentalItemsDc.setItems(items);
        refreshGridAttributeCache();
        applyGridColumnVisibility();
        rentalItemsDataGrid.getDataProvider().refreshAll();
        rentalItemsDataGrid.scrollToStart();
        if (selectedDetailsItem != null && items.stream().noneMatch(item -> Objects.equals(item.getId(), selectedDetailsItem.getId()))) {
            clearDetails();
        }
    }

    private List<RentalItem> loadGridItems() {
        if (!terminalLoadRequested) {
            return List.of();
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        StringBuilder query = new StringBuilder("""
                select e from RentalItem e
                where 1 = 1
                """);

        Warehouse warehouse = warehouseSelector == null ? null : warehouseSelector.getValue();
        if (warehouse != null && warehouse.getId() != null) {
            query.append(" and e.warehouse.id = :warehouseId");
            parameters.put("warehouseId", warehouse.getId());
        }
        RentalCategory category = filterCategoryField == null ? null : filterCategoryField.getValue();
        if (category == null || category.getId() == null) {
            return List.of();
        }
        if (category != null && category.getId() != null) {
            query.append(" and e.category.id = :categoryId");
            parameters.put("categoryId", category.getId());
        }
        RentalSubcategory subcategory = filterSubcategoryField == null ? null : filterSubcategoryField.getValue();
        if (subcategory != null && subcategory.getId() != null) {
            query.append(" and e.subcategory.id = :subcategoryId");
            parameters.put("subcategoryId", subcategory.getId());
        }
        RentalType type = filterTypeField == null ? null : filterTypeField.getValue();
        if (type != null && type.getId() != null) {
            query.append(" and e.type.id = :typeId");
            parameters.put("typeId", type.getId());
        }
        String status = filterStatusField == null ? null : filterStatusField.getValue();
        if (status != null && !status.isBlank()) {
            query.append(" and e.status = :status");
            parameters.put("status", status);
        }
        query.append(" order by e.number");
        return dataManager.load(RentalItem.class)
                .query(query.toString())
                .parameters(parameters)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("warehouse", "_base")
                        .add("category", "_base")
                        .add("subcategory", "_base")
                        .add("type", "_base"))
                .list();
    }

    private void configureGridColumns() {
        registerGridColumn("number", "Номер");
        registerGridColumn("status", "Статус");
        registerGridColumn("warehouse", "Склад");
        registerGridColumn("category", "Категория");
        registerGridColumn("subcategory", "Класс");
        registerGridColumn("type", "Тип");
        registerGridColumn("tags", "Теги");
        registerGridColumn("comment", "Комментарий");
        registerGridColumn("lastModifiedDate", "Дата изменения");
        registerGridColumn("lastModifiedBy", "Кто изменил");

        activeGridClassifierBindings = rentalItemService.loadActiveClassifierAttributes();
        for (RentalClassifierAttribute binding : activeGridClassifierBindings) {
            RentalAttributeDefinition definition = binding.getAttributeDefinition();
            if (definition == null || definition.getId() == null || definition.getCode() == null || definition.getCode().isBlank()
                    || hiddenInTerminal(definition)) {
                continue;
            }
            String columnKey = dynamicAttributeColumnKey(definition);
            if (gridColumnsByKey.containsKey(columnKey)) {
                continue;
            }
            Grid.Column<RentalItem> column = rentalItemsDataGrid.addColumn(item -> gridAttributeDisplayValue(item, definition.getId()));
            column.setKey(columnKey);
            column.setHeader(definition.getName());
            column.setAutoWidth(true);
            column.setFlexGrow(0);
            gridColumnsByKey.put(columnKey, column);
            gridColumnLabels.put(columnKey, definition.getName());
        }

        Grid.Column<RentalItem> tagsColumn = rentalItemsDataGrid.addColumn(this::gridTagsDisplayValue);
        tagsColumn.setKey("tags");
        tagsColumn.setHeader("Теги");
        tagsColumn.setAutoWidth(true);
        tagsColumn.setFlexGrow(0);
        gridColumnsByKey.put("tags", tagsColumn);
        gridColumnLabels.put("tags", "Теги");
    }

    private void registerGridColumn(String key, String label) {
        Grid.Column<RentalItem> column = rentalItemsDataGrid.getColumnByKey(key);
        if (column == null) {
            return;
        }
        gridColumnsByKey.put(key, column);
        gridColumnLabels.put(key, label);
    }

    private String dynamicAttributeColumnKey(RentalAttributeDefinition definition) {
        return "attr_" + definition.getCode();
    }

    private void applyGridColumnVisibility() {
        if (gridColumnVisibilityByKey.isEmpty()) {
            gridColumnVisibilityByKey = new LinkedHashMap<>(rentalItemService.loadGridColumnVisibility("RENTAL_ITEM_GRID"));
        }
        Set<String> applicableColumnKeys = applicableGridColumnKeys();
        for (Map.Entry<String, Grid.Column<RentalItem>> entry : gridColumnsByKey.entrySet()) {
            boolean visible = applicableColumnKeys.contains(entry.getKey())
                    && gridColumnVisibilityByKey.getOrDefault(entry.getKey(), true);
            entry.getValue().setVisible(visible);
        }
    }

    private Set<String> applicableGridColumnKeys() {
        Set<String> keys = new LinkedHashSet<>();
        keys.add("number");
        keys.add("status");
        keys.add("warehouse");
        keys.add("category");
        keys.add("subcategory");
        keys.add("type");
        keys.add("tags");
        keys.add("comment");
        keys.add("lastModifiedDate");
        keys.add("lastModifiedBy");

        List<RentalClassifierAttribute> bindings = currentGridClassifierBindings();
        for (RentalClassifierAttribute binding : bindings) {
            RentalAttributeDefinition definition = binding.getAttributeDefinition();
            if (definition == null || definition.getCode() == null || definition.getCode().isBlank() || hiddenInTerminal(definition)) {
                continue;
            }
            keys.add(dynamicAttributeColumnKey(definition));
        }
        return keys;
    }

    private List<RentalClassifierAttribute> currentGridClassifierBindings() {
        if (filterCategoryField == null || filterCategoryField.getValue() == null) {
            return activeGridClassifierBindings;
        }
        return rentalItemService.loadClassifierAttributes(
                filterCategoryField.getValue(),
                filterSubcategoryField == null ? null : filterSubcategoryField.getValue(),
                filterTypeField == null ? null : filterTypeField.getValue()
        );
    }

    private void refreshGridAttributeCache() {
        gridAttributeValuesByItemId.clear();
        gridTagsByItemId.clear();
        if (rentalItemsDc == null) {
            return;
        }
        List<RentalItem> items = new ArrayList<>(rentalItemsDc.getItems());
        if (items.isEmpty()) {
            return;
        }
        Map<UUID, Map<UUID, RentalAttributeValue>> valuesByItemId = new LinkedHashMap<>();
        for (RentalAttributeValue value : rentalItemService.loadAttributeValues(items.stream().map(RentalItem::getId).toList())) {
            if (value.getRentalItem() == null || value.getRentalItem().getId() == null
                    || value.getAttributeDefinition() == null || value.getAttributeDefinition().getId() == null) {
                continue;
            }
            valuesByItemId
                    .computeIfAbsent(value.getRentalItem().getId(), ignored -> new LinkedHashMap<>())
                    .put(value.getAttributeDefinition().getId(), value);
        }
        gridAttributeValuesByItemId.putAll(valuesByItemId);

        for (RentalItem item : items) {
            if (item == null || item.getId() == null) {
                continue;
            }
            gridTagsByItemId.put(item.getId(), formatGridTags(rentalItemService.loadItemTags(item.getId())));
        }
    }

    private String gridAttributeDisplayValue(RentalItem item, UUID definitionId) {
        if (item == null || item.getId() == null || definitionId == null) {
            return "";
        }
        Map<UUID, RentalAttributeValue> valuesByDefinition = gridAttributeValuesByItemId.get(item.getId());
        if (valuesByDefinition == null) {
            return "";
        }
        RentalAttributeValue value = valuesByDefinition.get(definitionId);
        if (value == null) {
            return "";
        }
        return formatAttributeValue(value);
    }

    private String gridTagsDisplayValue(RentalItem item) {
        if (item == null || item.getId() == null) {
            return "";
        }
        return gridTagsByItemId.getOrDefault(item.getId(), "");
    }

    private void openColumnSettingsDialog() {
        Map<String, Boolean> persistedVisibility = new LinkedHashMap<>(rentalItemService.loadGridColumnVisibility("RENTAL_ITEM_GRID"));
        if (!persistedVisibility.isEmpty()) {
            gridColumnVisibilityByKey = persistedVisibility;
        }
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Настроить столбцы");
        dialog.setWidth("42em");

        VerticalLayout content = new VerticalLayout();
        content.setPadding(false);
        content.setSpacing(true);
        content.setWidthFull();
        content.getStyle()
                .set("maxHeight", "60vh")
                .set("overflow", "auto");

        Map<String, Checkbox> columnCheckboxes = new LinkedHashMap<>();
        for (Map.Entry<String, Grid.Column<RentalItem>> entry : visibleGridColumnsForSettings().entrySet()) {
            String columnKey = entry.getKey();
            Checkbox checkbox = new Checkbox(gridColumnLabels.getOrDefault(columnKey, columnKey));
            checkbox.setValue(gridColumnVisibilityByKey.getOrDefault(columnKey, true));
            checkbox.setWidthFull();
            columnCheckboxes.put(columnKey, checkbox);
            content.add(checkbox);
        }

        Button saveButton = new Button("Сохранить", event -> {
            LinkedHashMap<String, Boolean> visibilityByKey = new LinkedHashMap<>();
            for (Map.Entry<String, Checkbox> entry : columnCheckboxes.entrySet()) {
                visibilityByKey.put(entry.getKey(), entry.getValue().getValue());
            }
            rentalItemService.saveGridColumnVisibility("RENTAL_ITEM_GRID", visibilityByKey);
            gridColumnVisibilityByKey = new LinkedHashMap<>(visibilityByKey);
            applyGridColumnVisibility();
            dialog.close();
            notifications.create("Настройки столбцов сохранены")
                    .withPosition(Notification.Position.TOP_END)
                    .withDuration(2200)
                    .show();
        });
        Button cancelButton = new Button("Отмена", event -> dialog.close());

        dialog.add(content);
        dialog.getFooter().add(cancelButton, saveButton);
        dialog.open();
    }

    private Map<String, Grid.Column<RentalItem>> visibleGridColumnsForSettings() {
        Map<String, Grid.Column<RentalItem>> visibleColumns = new LinkedHashMap<>();
        Set<String> applicableColumnKeys = applicableGridColumnKeys();
        for (Map.Entry<String, Grid.Column<RentalItem>> entry : gridColumnsByKey.entrySet()) {
            if (applicableColumnKeys.contains(entry.getKey())) {
                visibleColumns.put(entry.getKey(), entry.getValue());
            }
        }
        return visibleColumns;
    }

    private Span createStatusBadge(RentalItem item) {
        RentalItemStatusSupport.StatusPresentation presentation =
                RentalItemStatusSupport.presentationFor(item.getStatus());

        Span badge = new Span();
        badge.getElement().setAttribute("theme", "badge");
        badge.addClassNames("status-badge", presentation.className());

        Icon icon = presentation.icon().create();
        icon.addClassName("status-badge__icon");

        badge.add(icon, new Text(presentation.label()));
        return badge;
    }

    private void populateDetails(RentalItem item) {
        selectedDetailsItem = reloadRentalItem(item.getId());
        viewStateService.setTerminalSelectedRentalItemId(selectedDetailsItem.getId());
        detailsNumberField.setValue(valueOrBlank(selectedDetailsItem.getNumber()));
        detailsWarehouseField.setValue(matchWarehouse(selectedDetailsItem.getWarehouse()));
        setComboStringValue(detailsStatusField, selectedDetailsItem.getStatus(), availableStatuses);
        detailsCategoryField.setValue(matchCategory(selectedDetailsItem.getCategory()));
        List<RentalSubcategory> subcategories = reloadDetailsSubcategories(selectedDetailsItem.getCategory());
        detailsSubcategoryField.setValue(matchSubcategory(subcategories, selectedDetailsItem.getSubcategory()));
        List<RentalType> types = reloadDetailsTypes(selectedDetailsItem.getSubcategory());
        detailsTypeField.setValue(matchType(types, selectedDetailsItem.getType()));
        detailsLastModifiedDateField.setValue(selectedDetailsItem.getLastModifiedDate() == null
                ? ""
                : selectedDetailsItem.getLastModifiedDate().toString());
        detailsLastModifiedByField.setValue(valueOrBlank(selectedDetailsItem.getLastModifiedBy()));
        detailsCommentField.setValue(valueOrBlank(selectedDetailsItem.getComment()));
        refreshDetailsEditableSections(selectedDetailsItem);
        renderPassport(selectedDetailsItem);
        renderHistory(selectedDetailsItem);
        detailsPane.setVisible(true);
    }

    private void clearDetails() {
        clearDetails(true);
    }

    private void clearDetails(boolean clearStoredSelection) {
        selectedDetailsItem = null;
        if (clearStoredSelection) {
            viewStateService.setTerminalSelectedRentalItemId(null);
        }
        detailsPane.setVisible(false);
        detailsNumberField.clear();
        detailsWarehouseField.clear();
        detailsStatusField.clear();
        detailsCategoryField.clear();
        detailsSubcategoryField.clear();
        detailsSubcategoryField.setItems(List.of());
        detailsTypeField.clear();
        detailsTypeField.setItems(List.of());
        detailsTypeField.setEnabled(false);
        detailsTypeField.setPlaceholder("Недоступно");
        detailsLastModifiedDateField.clear();
        detailsLastModifiedByField.clear();
        detailsCommentField.clear();
        if (detailsTagsField != null) {
            detailsTagsField.clear();
        }
        detailsEditableAttributeFields.clear();
        if (detailsEditableSystemPane != null) {
            detailsEditableSystemPane.removeAll();
        }
        if (detailsEditableAdditionalPane != null) {
            detailsEditableAdditionalPane.removeAll();
        }
        if (detailsEditablePane != null) {
            detailsEditablePane.setVisible(false);
        }
        detailsPassportPane.removeAll();
        detailsHistoryPane.removeAll();
        detailsHistoryPhotoUrls.clear();
        detailsHistoryPhotoPreviewUrls.clear();
        detailsHistoryPhotoOriginalUrls.clear();
        detailsHistoryPhotoCaptions.clear();
        detailsHistoryPhotoIndex = 0;
        setDetailsEditMode(false);
    }

    private void setDetailsEditMode(boolean editMode) {
        detailsEditMode = editMode;
        detailsWarehouseField.setReadOnly(!editMode || availableWarehouses.size() <= 1);
        detailsStatusField.setReadOnly(!editMode);
        detailsCategoryField.setReadOnly(!editMode);
        detailsSubcategoryField.setReadOnly(!editMode);
        detailsTypeField.setReadOnly(!editMode);
        detailsCommentField.setReadOnly(!editMode);
        detailsEditablePane.setVisible(editMode);
        detailsEditButton.setVisible(!editMode);
        detailsSaveButton.setVisible(editMode);
        detailsCancelButton.setVisible(editMode);
        if (editMode) {
            refreshDetailsEditableSections(selectedDetailsItem);
        }
    }

    private void openCreateItemDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Добавить объект");
        dialog.setWidth("72em");

        TextField numberField = new TextField("Номер");
        numberField.setRequired(true);
        ComboBox<Warehouse> warehouseField = new ComboBox<>("Склад");
        warehouseField.setItemLabelGenerator(Warehouse::getName);
        warehouseField.setItems(availableWarehouses);
        warehouseField.setValue(preferredWarehouse());
        warehouseField.setReadOnly(availableWarehouses.size() <= 1);
        warehouseField.setVisible(availableWarehouses.size() > 1);
        warehouseField.setRequired(true);

        ComboBox<RentalCategory> categoryField = new ComboBox<>("Категория");
        categoryField.setItemLabelGenerator(RentalCategory::getName);
        categoryField.setItems(availableCategories);
        categoryField.setRequired(true);
        ComboBox<RentalSubcategory> subcategoryField = new ComboBox<>("Класс");
        subcategoryField.setItemLabelGenerator(RentalSubcategory::getName);

        ComboBox<RentalType> typeField = new ComboBox<>("Тип");
        typeField.setItemLabelGenerator(rentalTypeDisplayFormatter::displayName);
        typeField.setEnabled(false);
        typeField.setPlaceholder("Недоступно");

        VerticalLayout systemAttributesPane = new VerticalLayout();
        systemAttributesPane.setPadding(false);
        systemAttributesPane.setSpacing(true);
        systemAttributesPane.setWidthFull();

        VerticalLayout additionalAttributesPane = new VerticalLayout();
        additionalAttributesPane.setPadding(false);
        additionalAttributesPane.setSpacing(true);
        additionalAttributesPane.setWidthFull();

        FormLayout additionalStaticForm = new FormLayout();
        additionalStaticForm.setWidthFull();
        additionalStaticForm.setResponsiveSteps(
                new ResponsiveStep("0", 1),
                new ResponsiveStep("48em", 2)
        );

        MultiSelectComboBox<RentalTag> tagsField = new MultiSelectComboBox<>("Теги");
        tagsField.setItemLabelGenerator(RentalTag::getName);
        tagsField.setItems(availableTags);
        tagsField.setWidthFull();

        TextArea commentField = new TextArea("Комментарий");
        commentField.setWidthFull();

        additionalStaticForm.add(tagsField, commentField);
        additionalStaticForm.setColspan(tagsField, 2);
        additionalStaticForm.setColspan(commentField, 2);

        VerticalLayout additionalContent = new VerticalLayout(additionalAttributesPane, additionalStaticForm);
        additionalContent.setPadding(false);
        additionalContent.setSpacing(true);
        additionalContent.setWidthFull();

        Details additionalDetails = new Details("Дополнительные параметры", additionalContent);
        additionalDetails.setWidthFull();
        additionalDetails.setOpened(false);

        Map<UUID, AttributeFieldState> createAttributeFields = new LinkedHashMap<>();

        Runnable refreshAttributeSections = () -> rebuildAttributeSections(
                categoryField.getValue(),
                subcategoryField.getValue(),
                typeField.getValue(),
                systemAttributesPane,
                additionalAttributesPane,
                createAttributeFields
        );

        categoryField.addValueChangeListener(event -> {
            subcategoryField.clear();
            typeField.clear();
            subcategoryField.setItems(reloadSubcategoriesForCategory(event.getValue()));
            updateTypeFieldState(typeField, List.of());
            refreshAttributeSections.run();
        });
        refreshAttributeSections.run();
        subcategoryField.addValueChangeListener(event -> {
            List<RentalType> types = event.getValue() == null
                    ? List.of()
                    : dataManager.load(RentalType.class)
                    .query("""
                            select e from RentalType e
                            where e.active = true and e.subcategory = :subcategory
                            order by e.sortOrder, e.name
                            """)
                    .parameter("subcategory", event.getValue())
                    .list();
            typeField.clear();
            typeField.setItems(types);
            updateTypeFieldState(typeField, types);
            refreshAttributeSections.run();
        });
        typeField.addValueChangeListener(event -> refreshAttributeSections.run());

        ComboBox<String> statusField = new ComboBox<>("Статус");
        statusField.setItems(RentalItemStatusSupport.NEW_ITEM_STATUSES);
        statusField.setItemLabelGenerator(status -> RentalItemStatusSupport.presentationFor(status).label());
        statusField.setRequired(true);
        statusField.setValue("READY");

        FormLayout coreForm = new FormLayout();
        coreForm.setWidthFull();
        coreForm.setResponsiveSteps(
                new ResponsiveStep("0", 1),
                new ResponsiveStep("48em", 2),
                new ResponsiveStep("70em", 3)
        );
        coreForm.add(numberField, warehouseField, categoryField, subcategoryField, typeField, statusField);

        VerticalLayout content = new VerticalLayout(coreForm, systemAttributesPane, additionalDetails);
        content.setPadding(false);
        content.setSpacing(true);
        content.setWidthFull();

        Button saveButton = new Button("Сохранить", event -> {
            try {
                RentalItem saved = rentalItemService.createNewItem(
                        numberField.getValue(),
                        warehouseValue(warehouseField),
                        categoryField.getValue(),
                        subcategoryField.getValue(),
                        typeField.getValue(),
                        statusField.getValue(),
                        commentField.getValue(),
                        collectAttributeValues(createAttributeFields),
                        new ArrayList<>(tagsField.getValue())
                );
                applySavedItemFilters(saved);
                populateDetails(reloadRentalItem(saved.getId()));
                dialog.close();
                notifications.create("Объект добавлен")
                        .withPosition(Notification.Position.TOP_END)
                        .withDuration(2200)
                        .show();
            } catch (Exception ex) {
                showError(ex, "Не удалось добавить объект");
            }
        });
        Button cancelButton = new Button("Отмена", event -> dialog.close());

        subcategoryField.setItems(reloadSubcategoriesForCategory(categoryField.getValue()));
        refreshAttributeSections.run();
        dialog.add(content);
        dialog.getFooter().add(cancelButton, saveButton);
        dialog.open();
    }

    private RentalItem reloadRentalItem(UUID id) {
        return dataManager.load(RentalItem.class)
                .id(id)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("warehouse", "_base")
                        .add("category", "_base")
                        .add("subcategory", "_base")
                        .add("type", "_base"))
                .one();
    }

    private Warehouse preferredWarehouse() {
        Warehouse current = warehouseSelector != null ? warehouseSelector.getValue() : null;
        if (current != null) {
            return current;
        }
        return viewStateService.resolveWarehouse(
                availableWarehouses,
                viewStateService.getTerminalWarehouseId(),
                warehouseAccessService.defaultWarehouse());
    }

    private Warehouse warehouseValue(ComboBox<Warehouse> warehouseField) {
        Warehouse value = warehouseField.getValue();
        return value != null ? value : preferredWarehouse();
    }

    private List<RentalSubcategory> reloadSubcategoriesForCategory(RentalCategory category) {
        return category == null
                ? List.of()
                : dataManager.load(RentalSubcategory.class)
                .query("""
                        select e from RentalSubcategory e
                        where e.active = true and e.category = :category
                        order by e.sortOrder, e.name
                        """)
                .parameter("category", category)
                .list();
    }

    private boolean warehouseMatchesFilter(RentalItem item) {
        Warehouse filterWarehouse = warehouseSelector.getValue();
        return filterWarehouse == null
                || (item.getWarehouse() != null && Objects.equals(item.getWarehouse().getId(), filterWarehouse.getId()));
    }

    private boolean matchesCurrentFilters(RentalItem item) {
        if (item == null || !terminalLoadRequested || !warehouseMatchesFilter(item)) {
            return false;
        }

        RentalCategory category = filterCategoryField.getValue();
        if (category == null || item.getCategory() == null || !Objects.equals(item.getCategory().getId(), category.getId())) {
            return false;
        }

        RentalSubcategory subcategory = filterSubcategoryField.getValue();
        if (subcategory != null && (item.getSubcategory() == null || !Objects.equals(item.getSubcategory().getId(), subcategory.getId()))) {
            return false;
        }

        RentalType type = filterTypeField.getValue();
        if (type != null && (item.getType() == null || !Objects.equals(item.getType().getId(), type.getId()))) {
            return false;
        }

        String status = filterStatusField.getValue();
        return status == null || status.isBlank() || Objects.equals(status, item.getStatus());
    }

    private void applySavedItemFilters(RentalItem item) {
        if (item == null) {
            return;
        }
        RentalItem savedItem = reloadRentalItem(item.getId());
        filterSelectionProgrammaticChange = true;
        try {
            Warehouse matchedWarehouse = matchWarehouse(savedItem.getWarehouse());
            if (matchedWarehouse != null
                    && (warehouseSelector.getValue() == null
                    || !Objects.equals(warehouseSelector.getValue().getId(), matchedWarehouse.getId()))) {
                warehouseSelector.setValue(matchedWarehouse);
                viewStateService.setTerminalWarehouseId(matchedWarehouse.getId());
                rentalItemsDl.setParameter("warehouse", matchedWarehouse);
            }

            RentalCategory matchedCategory = matchCategory(savedItem.getCategory());
            if (!Objects.equals(filterCategoryField.getValue(), matchedCategory)) {
                filterCategoryField.setValue(matchedCategory);
            }

            List<RentalSubcategory> subcategories = reloadFilterSubcategories(matchedCategory);
            RentalSubcategory matchedSubcategory = matchSubcategory(subcategories, savedItem.getSubcategory());
            if (!Objects.equals(filterSubcategoryField.getValue(), matchedSubcategory)) {
                filterSubcategoryField.setValue(matchedSubcategory);
            }

            List<RentalType> types = reloadFilterTypes(matchedSubcategory);
            RentalType matchedType = matchType(types, savedItem.getType());
            if (!Objects.equals(filterTypeField.getValue(), matchedType)) {
                filterTypeField.setValue(matchedType);
            }
            if (matchedType == null) {
                filterTypeField.clear();
            }
        } finally {
            filterSelectionProgrammaticChange = false;
        }

        applyFilterParameters();

        RentalItem visibleItem = rentalItemsDc == null ? null : rentalItemsDc.getItems().stream()
                .filter(candidate -> candidate != null && Objects.equals(candidate.getId(), savedItem.getId()))
                .findFirst()
                .orElse(null);
        if (visibleItem != null) {
            rentalItemsDataGrid.select(visibleItem);
            rentalItemsDataGrid.scrollToItem(visibleItem);
        }
    }

    private void resetAllFilters() {
        filterSelectionProgrammaticChange = true;
        try {
            terminalLoadRequested = false;
            rentalItemsDl.setParameter("loadAllowed", false);
            filterCategoryField.clear();
            filterSubcategoryField.clear();
            filterTypeField.clear();
            filterStatusField.clear();
            reloadFilterSubcategories(null);
            reloadFilterTypes(null);
        } finally {
            filterSelectionProgrammaticChange = false;
        }

        applyFilterParameters();
    }

    private void showError(Exception ex, String fallbackMessage) {
        notifications.create(ex.getMessage() == null ? fallbackMessage : ex.getMessage())
                .withType(Notifications.Type.ERROR)
                .withPosition(Notification.Position.TOP_END)
                .withDuration(4000)
                .show();
    }

    private String valueOrBlank(String value) {
        return value == null ? "" : value;
    }

    private Warehouse matchWarehouse(Warehouse warehouse) {
        if (warehouse == null) {
            return null;
        }
        return availableWarehouses.stream()
                .filter(item -> Objects.equals(item.getId(), warehouse.getId()))
                .findFirst()
                .orElse(warehouse);
    }

    private RentalCategory matchCategory(RentalCategory category) {
        if (category == null) {
            return null;
        }
        return availableCategories.stream()
                .filter(item -> Objects.equals(item.getId(), category.getId()))
                .findFirst()
                .orElse(category);
    }

    private RentalSubcategory matchSubcategory(List<RentalSubcategory> items, RentalSubcategory subcategory) {
        if (subcategory == null) {
            return null;
        }
        return items.stream()
                .filter(item -> Objects.equals(item.getId(), subcategory.getId()))
                .findFirst()
                .orElse(subcategory);
    }

    private RentalType matchType(List<RentalType> items, RentalType type) {
        if (type == null) {
            return null;
        }
        return items.stream()
                .filter(item -> Objects.equals(item.getId(), type.getId()))
                .findFirst()
                .orElse(type);
    }

    private void updateTypeFieldState(ComboBox<RentalType> typeField, List<RentalType> types) {
        boolean available = types != null && !types.isEmpty();
        typeField.setEnabled(available);
        typeField.setPlaceholder(available ? "" : "Недоступно");
    }

    private void setComboStringValue(ComboBox<String> field, String value, List<String> allowedValues) {
        if (value == null || value.isBlank()) {
            field.setItems(allowedValues);
            field.clear();
            return;
        }
        List<String> items = new ArrayList<>(allowedValues == null ? List.of() : allowedValues);
        if (!items.contains(value)) {
            items = new ArrayList<>(new LinkedHashSet<>(items));
            items.add(value);
        }
        field.setItems(items);
        field.setValue(value);
    }

    private void rebuildAttributeSections(RentalCategory category,
                                          RentalSubcategory subcategory,
                                          RentalType type,
                                          VerticalLayout systemPane,
                                          VerticalLayout additionalPane,
                                          Map<UUID, AttributeFieldState> attributeFields) {
        rebuildAttributeSections(category, subcategory, type, systemPane, additionalPane, attributeFields, Map.of());
    }

    private void rebuildAttributeSections(RentalCategory category,
                                          RentalSubcategory subcategory,
                                          RentalType type,
                                          VerticalLayout systemPane,
                                          VerticalLayout additionalPane,
                                          Map<UUID, AttributeFieldState> attributeFields,
                                          Map<UUID, RentalAttributeValue> initialValuesByDefinition) {
        systemPane.removeAll();
        additionalPane.removeAll();
        attributeFields.clear();

        List<RentalClassifierAttribute> bindings = rentalItemService.loadClassifierAttributes(category, subcategory, type);
        List<RentalClassifierAttribute> categoryBindings = bindings.stream()
                .filter(binding -> !hiddenInTerminal(binding.getAttributeDefinition()))
                .toList();

        if (categoryBindings.isEmpty()) {
            if (category != null) {
                systemPane.add(createHint("Системные характеристики для выбранной категории не найдены"));
            }
        } else {
            systemPane.add(createSectionHeader("Параметры категории"));
            systemPane.add(buildAttributeForm(categoryBindings, attributeFields, initialValuesByDefinition));
        }
    }

    private FormLayout buildAttributeForm(List<RentalClassifierAttribute> bindings,
                                          Map<UUID, AttributeFieldState> attributeFields,
                                          Map<UUID, RentalAttributeValue> initialValuesByDefinition) {
        FormLayout layout = new FormLayout();
        layout.setWidthFull();
        layout.setResponsiveSteps(
                new ResponsiveStep("0", 1),
                new ResponsiveStep("48em", 2)
        );
        for (RentalClassifierAttribute binding : bindings) {
            RentalAttributeDefinition definition = binding.getAttributeDefinition();
            if (definition == null) {
                continue;
            }
            AttributeFieldState fieldState = createAttributeFieldState(definition, initialValuesByDefinition.get(definition.getId()));
            attributeFields.put(definition.getId(), fieldState);
            layout.add(fieldState.component());
        }
        return layout;
    }

    private AttributeFieldState createAttributeFieldState(RentalAttributeDefinition definition, RentalAttributeValue initialValue) {
        RentalAttributeDataType dataType = definition.getDataType();
        String label = definition.getName();
        if (dataType == RentalAttributeDataType.ENUM) {
            ComboBox<RentalAttributeOption> field = new ComboBox<>(label);
            field.setWidthFull();
            field.setItemLabelGenerator(RentalAttributeOption::getName);
            List<RentalAttributeOption> options = new ArrayList<>(rentalItemService.loadAttributeOptions(definition));
            RentalAttributeOption selectedOption = resolveInitialAttributeOption(definition, initialValue, options);
            if (selectedOption != null && selectedOption.getId() != null) {
                options = mergeAttributeOptions(options, selectedOption);
            }
            field.setItems(options);
            if (selectedOption != null && selectedOption.getId() != null) {
                field.setValue(options.stream()
                        .filter(option -> option.getId() != null && option.getId().equals(selectedOption.getId()))
                        .findFirst()
                        .orElse(selectedOption));
            }
            return new AttributeFieldState(definition, dataType, field);
        }
        if (dataType == RentalAttributeDataType.TEXT) {
            TextArea field = new TextArea(label);
            field.setWidthFull();
            field.setHeight("7em");
            if (initialValue != null) {
                String text = initialValue.getValueText() != null && !initialValue.getValueText().isBlank()
                        ? initialValue.getValueText()
                        : initialValue.getValueString();
                if (text != null && !text.isBlank()) {
                    field.setValue(text);
                }
            }
            return new AttributeFieldState(definition, dataType, field);
        }
        if (dataType == RentalAttributeDataType.NUMBER) {
            IntegerField field = new IntegerField(label);
            field.setWidthFull();
            if (initialValue != null && initialValue.getValueNumber() != null) {
                field.setValue((int) Math.round(initialValue.getValueNumber()));
            }
            return new AttributeFieldState(definition, dataType, field);
        }
        if (dataType == RentalAttributeDataType.BOOLEAN) {
            Checkbox field = new Checkbox(label);
            if (initialValue != null && initialValue.getValueBoolean() != null) {
                field.setValue(Boolean.TRUE.equals(initialValue.getValueBoolean()));
            }
            return new AttributeFieldState(definition, dataType, field);
        }
        TextField field = new TextField(label);
        field.setWidthFull();
        if (initialValue != null) {
            String text = initialValue.getValueString() != null && !initialValue.getValueString().isBlank()
                    ? initialValue.getValueString()
                    : initialValue.getValueText();
            if (text != null && !text.isBlank()) {
                field.setValue(text);
            }
        }
        return new AttributeFieldState(definition, dataType, field);
    }

    private RentalAttributeOption resolveInitialAttributeOption(RentalAttributeDefinition definition,
                                                                RentalAttributeValue initialValue,
                                                                List<RentalAttributeOption> options) {
        if (initialValue != null && initialValue.getValueOption() != null && initialValue.getValueOption().getId() != null) {
            return initialValue.getValueOption();
        }
        if (definition == null || definition.getCode() == null || initialValue == null || initialValue.getValueBoolean() == null) {
            return null;
        }
        if (!"BOILER".equalsIgnoreCase(definition.getCode())) {
            return null;
        }
        String legacyOptionCode = Boolean.TRUE.equals(initialValue.getValueBoolean()) ? "OTHER" : "NONE";
        return options.stream()
                .filter(option -> legacyOptionCode.equalsIgnoreCase(option.getCode()))
                .findFirst()
                .orElse(null);
    }

    private List<RentalAttributeValue> collectAttributeValues(Map<UUID, AttributeFieldState> attributeFields) {
        List<RentalAttributeValue> values = new ArrayList<>();
        for (AttributeFieldState state : attributeFields.values()) {
            RentalAttributeValue value = collectAttributeValue(state);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    private RentalAttributeValue collectAttributeValue(AttributeFieldState state) {
        RentalAttributeValue value = dataManager.create(RentalAttributeValue.class);
        value.setAttributeDefinition(state.definition());

        switch (state.dataType()) {
            case ENUM -> {
                @SuppressWarnings("unchecked")
                ComboBox<RentalAttributeOption> field = (ComboBox<RentalAttributeOption>) state.component();
                RentalAttributeOption selected = field.getValue();
                if (selected == null) {
                    return null;
                }
                value.setValueOption(selected);
            }
            case TEXT -> {
                TextArea field = (TextArea) state.component();
                String text = field.getValue();
                if (text == null || text.isBlank()) {
                    return null;
                }
                value.setValueText(text.trim());
            }
            case NUMBER -> {
                IntegerField field = (IntegerField) state.component();
                Integer number = field.getValue();
                if (number == null) {
                    return null;
                }
                value.setValueNumber(number.doubleValue());
            }
            case BOOLEAN -> {
                com.vaadin.flow.component.checkbox.Checkbox field = (com.vaadin.flow.component.checkbox.Checkbox) state.component();
                value.setValueBoolean(Boolean.TRUE.equals(field.getValue()));
            }
            case STRING -> {
                TextField field = (TextField) state.component();
                String text = field.getValue();
                if (text == null || text.isBlank()) {
                    return null;
                }
                value.setValueString(text.trim());
            }
        }

        return value;
    }

    private void refreshDetailsEditableSections(RentalItem sourceItem) {
        if (detailsEditablePane == null || detailsEditableSystemPane == null || detailsEditableAdditionalPane == null || detailsTagsField == null) {
            return;
        }
        if (detailsCategoryField == null) {
            return;
        }

        Map<UUID, RentalAttributeValue> initialValuesByDefinition = detailsEditableAttributeFields.isEmpty()
                ? loadAttributeValuesByDefinition(sourceItem)
                : snapshotAttributeValuesByDefinition(detailsEditableAttributeFields);
        Collection<RentalTag> initialTags = detailsEditableAttributeFields.isEmpty()
                ? loadTagsForItem(sourceItem)
                : new ArrayList<>(detailsTagsField.getValue());

        rebuildAttributeSections(
                detailsCategoryField.getValue(),
                detailsSubcategoryField.getValue(),
                detailsTypeField.getValue(),
                detailsEditableSystemPane,
                detailsEditableAdditionalPane,
                detailsEditableAttributeFields,
                initialValuesByDefinition
        );

        List<RentalTag> mergedTags = mergeTags(availableTags, initialTags);
        detailsTagsField.setItems(mergedTags);
        detailsTagsField.setValue(new LinkedHashSet<>(initialTags));
    }

    private Map<UUID, RentalAttributeValue> snapshotAttributeValuesByDefinition(Map<UUID, AttributeFieldState> attributeFields) {
        Map<UUID, RentalAttributeValue> snapshot = new LinkedHashMap<>();
        for (RentalAttributeValue value : collectAttributeValues(attributeFields)) {
            if (value.getAttributeDefinition() != null && value.getAttributeDefinition().getId() != null) {
                snapshot.put(value.getAttributeDefinition().getId(), value);
            }
        }
        return snapshot;
    }

    private Map<UUID, RentalAttributeValue> loadAttributeValuesByDefinition(RentalItem item) {
        if (item == null || item.getId() == null) {
            return Map.of();
        }
        Map<UUID, RentalAttributeValue> valuesByDefinition = new LinkedHashMap<>();
        for (RentalAttributeValue value : rentalItemService.loadAttributeValues(item.getId())) {
            if (value.getAttributeDefinition() != null && value.getAttributeDefinition().getId() != null) {
                valuesByDefinition.put(value.getAttributeDefinition().getId(), value);
            }
        }
        return valuesByDefinition;
    }

    private Collection<RentalTag> loadTagsForItem(RentalItem item) {
        if (item == null || item.getId() == null) {
            return List.of();
        }
        List<RentalTag> tags = new ArrayList<>();
        for (RentalItemTag itemTag : rentalItemService.loadItemTags(item.getId())) {
            if (itemTag.getTag() != null) {
                tags.add(itemTag.getTag());
            }
        }
        return tags;
    }

    private List<RentalTag> mergeTags(Collection<RentalTag> baseTags, Collection<RentalTag> selectedTags) {
        Map<UUID, RentalTag> merged = new LinkedHashMap<>();
        if (baseTags != null) {
            for (RentalTag tag : baseTags) {
                if (tag != null && tag.getId() != null) {
                    merged.put(tag.getId(), tag);
                }
            }
        }
        if (selectedTags != null) {
            for (RentalTag tag : selectedTags) {
                if (tag != null && tag.getId() != null) {
                    merged.putIfAbsent(tag.getId(), tag);
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    private List<RentalAttributeOption> mergeAttributeOptions(List<RentalAttributeOption> baseOptions, RentalAttributeOption selectedOption) {
        Map<UUID, RentalAttributeOption> merged = new LinkedHashMap<>();
        if (baseOptions != null) {
            for (RentalAttributeOption option : baseOptions) {
                if (option != null && option.getId() != null) {
                    merged.put(option.getId(), option);
                }
            }
        }
        if (selectedOption != null && selectedOption.getId() != null) {
            merged.putIfAbsent(selectedOption.getId(), selectedOption);
        }
        return new ArrayList<>(merged.values());
    }

    private void renderPassport(RentalItem item) {
        detailsPassportPane.removeAll();
        detailsPassportPane.add(createSectionHeader("Паспорт объекта"));

        List<RentalClassifierAttribute> bindings = rentalItemService.loadClassifierAttributes(
                item.getCategory(),
                item.getSubcategory(),
                item.getType()
        );
        List<RentalAttributeValue> values = rentalItemService.loadAttributeValues(item.getId());
        Map<UUID, RentalAttributeValue> valuesByDefinition = new HashMap<>();
        for (RentalAttributeValue value : values) {
            if (value.getAttributeDefinition() != null && value.getAttributeDefinition().getId() != null) {
                valuesByDefinition.put(value.getAttributeDefinition().getId(), value);
            }
        }

        String formattedType = rentalTypeDisplayFormatter.displayName(item.getType(), valuesByDefinition);
        if (!formattedType.isBlank()) {
            detailsPassportPane.add(renderPassportRow("Тип", formattedType));
        }

        List<RentalClassifierAttribute> categoryBindings = bindings.stream()
                .filter(binding -> !hiddenInTerminal(binding.getAttributeDefinition()))
                .toList();

        if (!categoryBindings.isEmpty()) {
            detailsPassportPane.add(createSectionHeader("Параметры категории"));
            detailsPassportPane.add(renderPassportSection(categoryBindings, valuesByDefinition));
        }

        List<RentalItemAccessory> accessories = rentalItemService.loadItemAccessories(item.getId());
        if (!accessories.isEmpty()) {
            detailsPassportPane.add(createSectionHeader("Доп. оборудование"));
            detailsPassportPane.add(renderAccessoryAssignmentsSection(accessories));
        }

        List<RentalItemTag> itemTags = rentalItemService.loadItemTags(item.getId());
        if (!itemTags.isEmpty()) {
            detailsPassportPane.add(createSectionHeader("Теги"));
            detailsPassportPane.add(renderTags(itemTags));
        }

        if (item.getComment() != null && !item.getComment().isBlank()) {
            detailsPassportPane.add(createSectionHeader("Комментарий"));
            detailsPassportPane.add(createTextBlock(item.getComment()));
        }
    }

    private VerticalLayout renderPassportSection(List<RentalClassifierAttribute> bindings,
                                                 Map<UUID, RentalAttributeValue> valuesByDefinition) {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(false);
        section.setSpacing(false);
        section.setWidthFull();
        for (RentalClassifierAttribute binding : bindings) {
            RentalAttributeDefinition definition = binding.getAttributeDefinition();
            if (definition == null) {
                continue;
            }
            RentalAttributeValue value = valuesByDefinition.get(definition.getId());
            section.add(renderPassportRow(definition.getName(), value == null ? "—" : formatAttributeValue(value)));
        }
        return section;
    }

    private Div renderTags(List<RentalItemTag> itemTags) {
        Div container = new Div();
        container.getStyle()
                .set("display", "flex")
                .set("flex-wrap", "wrap")
                .set("gap", "6px");
        for (RentalItemTag itemTag : itemTags) {
            if (itemTag.getTag() == null || itemTag.getTag().getName() == null || itemTag.getTag().getName().isBlank()) {
                continue;
            }
            Span tag = new Span("#" + itemTag.getTag().getName().toLowerCase(Locale.ROOT).replace(' ', '_'));
            tag.getElement().setAttribute("theme", "badge");
            container.add(tag);
        }
        return container;
    }

    private String formatGridTags(List<RentalItemTag> itemTags) {
        if (itemTags == null || itemTags.isEmpty()) {
            return "";
        }
        List<String> labels = new ArrayList<>();
        for (RentalItemTag itemTag : itemTags) {
            if (itemTag == null || itemTag.getTag() == null || itemTag.getTag().getName() == null || itemTag.getTag().getName().isBlank()) {
                continue;
            }
            labels.add("#" + itemTag.getTag().getName().trim());
        }
        return String.join(" ", labels);
    }

    private Span createSectionHeader(String text) {
        Span header = new Span(text);
        header.getStyle()
                .set("font-weight", "700")
                .set("font-size", "13px")
                .set("margin-top", "0.5rem");
        return header;
    }

    private Span createHint(String text) {
        Span hint = new Span(text);
        hint.getStyle()
                .set("font-size", "12px")
                .set("color", "#64748b");
        return hint;
    }

    private Div renderPassportRow(String label, String value) {
        Div row = new Div();
        row.addClassName("warehouse-details__passport-row");

        Span labelSpan = new Span(label);
        labelSpan.addClassName("warehouse-details__passport-label");

        Span valueSpan = new Span(value);
        valueSpan.addClassName("warehouse-details__passport-value");

        row.add(labelSpan, valueSpan);
        return row;
    }

    private Div createTextBlock(String text) {
        Div block = new Div();
        block.setText(text);
        block.getStyle()
                .set("white-space", "pre-wrap")
                .set("font-size", "13px")
                .set("color", "#334155");
        return block;
    }

    private String formatAttributeValue(RentalAttributeValue value) {
        if (value.getValueOption() != null && value.getValueOption().getName() != null) {
            return value.getValueOption().getName();
        }
        if (value.getAttributeDefinition() != null
                && "BOILER".equalsIgnoreCase(value.getAttributeDefinition().getCode())
                && value.getValueBoolean() != null) {
            return Boolean.TRUE.equals(value.getValueBoolean()) ? "Есть" : "Нет";
        }
        if (value.getValueString() != null && !value.getValueString().isBlank()) {
            return value.getValueString();
        }
        if (value.getValueText() != null && !value.getValueText().isBlank()) {
            return value.getValueText();
        }
        if (value.getValueNumber() != null) {
            double number = value.getValueNumber();
            if (Math.rint(number) == number) {
                return Long.toString(Math.round(number));
            }
            return Double.toString(number);
        }
        if (value.getValueBoolean() != null) {
            return Boolean.TRUE.equals(value.getValueBoolean()) ? "Да" : "Нет";
        }
        return "—";
    }

    private VerticalLayout renderAccessoryAssignmentsSection(List<RentalItemAccessory> assignments) {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(false);
        section.setSpacing(false);
        section.setWidthFull();
        for (RentalItemAccessory assignment : assignments) {
            if (assignment == null || assignment.getAccessoryItem() == null) {
                continue;
            }
            String name = valueOrBlank(assignment.getAccessoryItem().getName());
            String quantity = assignment.getQuantity() == null ? "0" : Integer.toString(assignment.getQuantity());
            section.add(renderPassportRow(name, quantity + " шт."));
        }
        return section;
    }

    private String displayTypeForGrid(RentalItem item) {
        if (item == null || item.getType() == null) {
            return "";
        }
        Map<UUID, RentalAttributeValue> valuesByDefinition = item.getId() == null
                ? Map.of()
                : gridAttributeValuesByItemId.getOrDefault(item.getId(), Map.of());
        return rentalTypeDisplayFormatter.displayName(item.getType(), valuesByDefinition);
    }

    private boolean hiddenInTerminal(RentalAttributeDefinition definition) {
        return definition != null
                && definition.getCode() != null
                && HIDDEN_TERMINAL_ATTRIBUTE_CODES.contains(definition.getCode().toUpperCase(Locale.ROOT));
    }

    private void renderHistory(RentalItem item) {
        detailsHistoryPane.removeAll();
        detailsHistoryPhotoUrls.clear();
        detailsHistoryPhotoPreviewUrls.clear();
        detailsHistoryPhotoCaptions.clear();
        detailsHistoryPhotoIndex = 0;

        List<RentalItemEvent> historyEntries = rentalItemEventService.loadTimeline(item.getId());
        if (historyEntries.isEmpty()) {
            Span empty = new Span("История по объекту пока пустая");
            empty.getStyle().set("font-size", "12px").set("color", "#64748b");
            detailsHistoryPane.add(empty);
            return;
        }

        RentalItemEvent latestEvent = rentalItemEventService.latestEvent(item.getId());
        if (latestEvent == null) {
            latestEvent = historyEntries.get(0);
        }
        List<RentalItemEventPhoto> latestPhotos = rentalItemEventService.loadLatestPhotos(item.getId());
        for (var photo : latestPhotos) {
            detailsHistoryPhotoUrls.add(repairEstimateService.mediaUrl(photo.getId(), RepairEstimateService.PhotoVariant.THUMB));
            detailsHistoryPhotoPreviewUrls.add(repairEstimateService.mediaUrl(photo.getId(), RepairEstimateService.PhotoVariant.PREVIEW));
            detailsHistoryPhotoOriginalUrls.add(repairEstimateService.mediaUrl(photo.getId(), RepairEstimateService.PhotoVariant.ORIGINAL));
            detailsHistoryPhotoCaptions.add(valueOrBlank(photo.getOriginalFileName()));
        }
        preloadPhotoSources(detailsHistoryPhotoPreviewUrls);

        VerticalLayout latestBlock = new VerticalLayout();
        latestBlock.setPadding(false);
        latestBlock.setSpacing(true);
        latestBlock.getStyle()
                .set("border", "1px solid #e2e8f0")
                .set("border-radius", "8px")
                .set("padding", "10px");

        if (!detailsHistoryPhotoUrls.isEmpty()) {
            Image image = new Image(detailsHistoryPhotoUrls.get(0), detailsHistoryPhotoCaptions.get(0));
            image.setWidthFull();
            image.getStyle()
                    .set("height", "220px")
                    .set("object-fit", "contain")
                    .set("border-radius", "8px")
                    .set("background", "#e2e8f0");
            image.addClickListener(event -> openPhotoGallery(detailsHistoryPhotoPreviewUrls, detailsHistoryPhotoOriginalUrls, detailsHistoryPhotoCaptions, detailsHistoryPhotoIndex));

            Span caption = new Span(detailsHistoryPhotoCaptions.get(0));
            caption.getStyle().set("font-size", "12px").set("color", "#475569");

            Button prev = new Button("<");
            Button next = new Button(">");
            prev.addClickListener(event -> shiftHistoryPhoto(image, caption, -1));
            next.addClickListener(event -> shiftHistoryPhoto(image, caption, 1));
            HorizontalLayout controls = new HorizontalLayout(prev, next);
            controls.setAlignItems(FlexComponent.Alignment.CENTER);

            latestBlock.add(image, caption, controls);
        }

        Span latestTitle = new Span(valueOrBlank(latestEvent.getTitle()));
        latestTitle.getStyle().set("font-weight", "700");
        Span latestMeta = new Span(buildHistoryMeta(latestEvent));
        latestMeta.getStyle().set("font-size", "12px").set("color", "#64748b");
        latestBlock.add(latestTitle, latestMeta);
        if (latestEvent.getComment() != null && !latestEvent.getComment().isBlank()) {
            Span latestComment = new Span(latestEvent.getComment());
            latestComment.getStyle().set("font-size", "13px").set("color", "#334155");
            latestBlock.add(latestComment);
        }

        HorizontalLayout latestActions = new HorizontalLayout();
        latestActions.setSpacing(true);
        Button downloadLatestPhotosZip = new Button("Скачать последние фото ZIP", new Icon(VaadinIcon.DOWNLOAD_ALT));
        downloadLatestPhotosZip.setEnabled(!latestPhotos.isEmpty());
        downloadLatestPhotosZip.setTooltipText(latestPhotos.isEmpty()
                ? "У последнего события нет фото"
                : "Скачать ZIP с последними фото объекта");
        downloadLatestPhotosZip.addClickListener(event -> {
            if (item.getId() == null || latestPhotos.isEmpty()) {
                notifications.create("У последнего события нет фото для скачивания")
                        .withType(Notifications.Type.WARNING)
                        .withPosition(Notification.Position.TOP_END)
                        .withDuration(2200)
                        .show();
                return;
            }
            getUI().ifPresent(ui -> ui.getPage().open("/api/rental-items/" + item.getId() + "/latest-photos.zip"));
        });
        latestActions.add(downloadLatestPhotosZip);
        if (latestEvent.getEstimate() != null && latestEvent.getEstimate().getId() != null) {
            UUID latestEstimateId = latestEvent.getEstimate().getId();
            Button estimateButton = new Button("Смета", new Icon(VaadinIcon.FILE_TEXT_O));
            estimateButton.addClickListener(event -> navigateToRepairEstimate(latestEstimateId));
            latestActions.add(estimateButton);
        }
        if (latestEvent.getRepairProcess() != null && latestEvent.getRepairProcess().getId() != null) {
            UUID latestRepairProcessId = latestEvent.getRepairProcess().getId();
            Button dossierButton = new Button("Досье", new Icon(VaadinIcon.CLIPBOARD_TEXT));
            dossierButton.addClickListener(event -> getUI().ifPresent(ui -> ui.navigate("after-repair?processId=" + latestRepairProcessId)));
            latestActions.add(dossierButton);
        }
        if (latestActions.getComponentCount() > 0) {
            latestBlock.add(latestActions);
        }

        VerticalLayout historyList = new VerticalLayout();
        historyList.setPadding(false);
        historyList.setSpacing(true);
        for (int i = 0; i < Math.min(historyEntries.size(), 5); i++) {
            historyList.add(createHistorySummaryRow(historyEntries.get(i), eventDepth(historyEntries.get(i))));
        }

        detailsHistoryPane.add(latestBlock, historyList);
    }

    private void shiftHistoryPhoto(Image image, Span caption, int delta) {
        if (detailsHistoryPhotoUrls.isEmpty()) {
            return;
        }
        detailsHistoryPhotoIndex = Math.floorMod(detailsHistoryPhotoIndex + delta, detailsHistoryPhotoUrls.size());
        image.setSrc(detailsHistoryPhotoUrls.get(detailsHistoryPhotoIndex));
        caption.setText(detailsHistoryPhotoCaptions.get(detailsHistoryPhotoIndex));
    }

    private void openPhotoGallery(List<String> urls, List<String> downloadUrls, List<String> captions, int initialIndex) {
        if (urls == null || urls.isEmpty()) {
            return;
        }
        Dialog dialog = new Dialog();
        dialog.setWidth("90vw");
        dialog.setHeight("90vh");

        int[] index = new int[] {Math.max(0, Math.min(initialIndex, urls.size() - 1))};
        Image full = new Image(urls.get(index[0]), captions.get(index[0]));
        full.setWidthFull();
        full.setHeightFull();
        full.getStyle()
                .set("object-fit", "contain")
                .set("background", "#0f172a");

        Span caption = new Span(captions.get(index[0]));
        Button prev = new Button("<", event -> shiftHistoryPhoto(full, caption, urls, captions, index, -1));
        Button next = new Button(">", event -> shiftHistoryPhoto(full, caption, urls, captions, index, 1));
        Button download = new Button("Скачать оригинал", event -> {
            String current = downloadUrls == null || downloadUrls.size() <= index[0] ? null : downloadUrls.get(index[0]);
            if (current != null && !current.isBlank()) {
                getUI().ifPresent(ui -> ui.getPage().open(current));
            }
        });
        download.setEnabled(downloadUrls != null && downloadUrls.size() > index[0] && downloadUrls.get(index[0]) != null && !downloadUrls.get(index[0]).isBlank());
        Button close = new Button("Закрыть", event -> dialog.close());

        HorizontalLayout controls = new HorizontalLayout(prev, next, download, close);
        VerticalLayout content = new VerticalLayout(full, caption, controls);
        content.setSizeFull();
        dialog.add(content);
        preloadPhotoSources(urls);
        dialog.open();
    }

    private void shiftHistoryPhoto(Image image,
                                   Span caption,
                                   List<String> urls,
                                   List<String> captions,
                                   int[] index,
                                   int delta) {
        if (urls == null || urls.isEmpty()) {
            return;
        }
        index[0] = Math.floorMod(index[0] + delta, urls.size());
        image.setSrc(urls.get(index[0]));
        caption.setText(captions.get(index[0]));
    }

    private Span createHistorySummaryRow(RentalItemEvent event, int depth) {
        Span row = new Span(buildHistoryMeta(event) + " • " + valueOrBlank(event.getTitle()));
        row.getStyle()
                .set("font-size", "12px")
                .set("color", "#475569")
                .set("margin-left", Math.max(0, depth) * 16 + "px");
        return row;
    }

    private String buildHistoryMeta(RentalItemEvent event) {
        if (event == null) {
            return "—";
        }
        String date = event.getEventDate() == null ? "" : event.getEventDate().toLocalDate().toString();
        String type = eventTypeLabel(event.getEventType());
        String status = event.getStatusAfter() == null ? null : RentalItemStatusSupport.presentationFor(event.getStatusAfter()).label();
        String actor = event.getActorDisplayName() == null || event.getActorDisplayName().isBlank() ? null : event.getActorDisplayName();
        List<String> parts = new ArrayList<>();
        if (date != null && !date.isBlank()) {
            parts.add(date);
        }
        parts.add(type);
        if (status != null && !status.isBlank()) {
            parts.add(status);
        }
        if (actor != null) {
            parts.add(actor);
        }
        return String.join(" • ", parts);
    }

    private int eventDepth(RentalItemEvent event) {
        int depth = 0;
        RentalItemEvent current = event == null ? null : event.getParentEvent();
        while (current != null) {
            depth++;
            current = current.getParentEvent();
            if (depth > 8) {
                break;
            }
        }
        return depth;
    }

    private String eventTypeLabel(RentalItemEventType eventType) {
        if (eventType == null) {
            return "Событие";
        }
        return switch (eventType) {
            case INVENTORY_NEW_ITEM -> "Создание объекта";
            case INVENTORY_EXISTING_ITEM -> "Инвентаризация существующего";
            case ESTIMATE_DRAFT -> "Черновик сметы";
            case ESTIMATE_COMPLETED -> "Смета завершена";
            case STATUS_CHANGED -> "Изменение статуса";
            case REPAIR_TASK_STARTED -> "Ремонт начат";
            case REPAIR_TASK_COMPLETED -> "Этап ремонта завершён";
            case REPAIR_READY_FOR_CHECK -> "Готов к проверке";
            case REPAIR_ACCEPTED -> "Ремонт принят";
            case ACCESSORY_UPDATED -> "Изменение мебели";
            case BEFORE_RENT -> "Перед арендой";
            case AFTER_RENT -> "После аренды";
            case AFTER_REPAIR -> "После ремонта";
            case CAPITAL_REPAIR -> "Капитальный ремонт";
            case MANUAL -> "Ручная запись";
            case ESTIMATE -> "Смета";
        };
    }

    private void openHistoryDialog(RentalItem item) {
        List<RentalItemEvent> timeline = rentalItemEventService.loadTimeline(item.getId());
        Dialog dialog = new Dialog();
        dialog.setWidth("94vw");
        dialog.setHeight("90vh");
        dialog.setResizable(true);

        ComboBox<RentalItemEventType> typeFilter = new ComboBox<>("Тип события");
        typeFilter.setItems(RentalItemEventType.values());
        typeFilter.setItemLabelGenerator(this::eventTypeLabel);
        typeFilter.setClearButtonVisible(true);
        typeFilter.setValue(historyDialogSelectedType);

        DatePicker fromDate = new DatePicker("С");
        DatePicker toDate = new DatePicker("По");
        fromDate.setValue(historyDialogFromDate);
        toDate.setValue(historyDialogToDate);

        Grid<RentalItemEvent> historyGrid = new Grid<>(RentalItemEvent.class, false);
        historyGrid.setSelectionMode(Grid.SelectionMode.NONE);
        historyGrid.setWidthFull();
        historyGrid.setHeight("100%");
        historyGrid.addColumn(event -> event.getId() == null ? "" : event.getId().toString())
                .setHeader("ID события")
                .setAutoWidth(true)
                .setFlexGrow(0);
        historyGrid.addColumn(event -> eventTypeLabel(event.getEventType()))
                .setHeader("Тип события")
                .setAutoWidth(true)
                .setFlexGrow(0);
        historyGrid.addColumn(event -> valueOrBlank(historyActor(event)))
                .setHeader("Кто менял")
                .setAutoWidth(true)
                .setFlexGrow(0);
        historyGrid.addColumn(event -> formatHistoryDate(event.getEventDate()))
                .setHeader("Дата")
                .setAutoWidth(true)
                .setFlexGrow(0);
        AtomicReference<List<RentalItemEvent>> currentItems = new AtomicReference<>(List.of());
        AtomicReference<UUID> expandedEventId = new AtomicReference<>();
        historyGrid.setItemDetailsRenderer(new ComponentRenderer<>(historyEvent -> createHistoryEventDetailsLayout(historyEvent, timeline)));

        Runnable refresh = () -> {
            historyDialogSelectedType = typeFilter.getValue();
            historyDialogFromDate = fromDate.getValue();
            historyDialogToDate = toDate.getValue();

            List<RentalItemEvent> filtered = filterHistoryEvents(timeline, historyDialogSelectedType, historyDialogFromDate, historyDialogToDate);
            currentItems.set(filtered);
            historyGrid.setItems(filtered);
            if (expandedEventId.get() != null
                    && filtered.stream().noneMatch(historyEvent -> Objects.equals(historyEvent.getId(), expandedEventId.get()))) {
                expandedEventId.set(null);
            }
            filtered.forEach(historyEvent -> historyGrid.setDetailsVisible(historyEvent, Objects.equals(historyEvent.getId(), expandedEventId.get())));
        };

        historyGrid.addItemClickListener(click -> {
            UUID clickedId = click.getItem().getId();
            boolean collapse = Objects.equals(expandedEventId.get(), clickedId);
            expandedEventId.set(collapse ? null : clickedId);
            currentItems.get().forEach(historyEvent ->
                    historyGrid.setDetailsVisible(historyEvent, !collapse && Objects.equals(historyEvent.getId(), clickedId)));
        });

        typeFilter.addValueChangeListener(event -> refresh.run());
        fromDate.addValueChangeListener(event -> refresh.run());
        toDate.addValueChangeListener(event -> refresh.run());

        HorizontalLayout filters = new HorizontalLayout(typeFilter, fromDate, toDate);
        filters.setAlignItems(FlexComponent.Alignment.END);
        filters.setWidthFull();

        Button close = new Button("Закрыть", event -> dialog.close());
        HorizontalLayout footer = new HorizontalLayout(close);
        footer.setJustifyContentMode(FlexComponent.JustifyContentMode.END);
        footer.setWidthFull();

        VerticalLayout content = new VerticalLayout(filters, historyGrid, footer);
        content.setSizeFull();
        dialog.add(content);
        refresh.run();
        dialog.open();
    }

    private List<RentalItemEvent> filterHistoryEvents(List<RentalItemEvent> timeline,
                                                     RentalItemEventType selectedType,
                                                     LocalDate fromDate,
                                                     LocalDate toDate) {
        if (timeline == null || timeline.isEmpty()) {
            return List.of();
        }
        return timeline.stream()
                .filter(event -> selectedType == null || selectedType == event.getEventType())
                .filter(event -> fromDate == null || event.getEventDate() == null || !event.getEventDate().toLocalDate().isBefore(fromDate))
                .filter(event -> toDate == null || event.getEventDate() == null || !event.getEventDate().toLocalDate().isAfter(toDate))
                .toList();
    }

    private void renderHistoryEventDetails(RentalItemEvent event,
                                           VerticalLayout detailsContainer,
                                           List<RentalItemEvent> timeline) {
        detailsContainer.removeAll();
        if (event == null) {
            Span empty = new Span("Выберите событие в таблице");
            empty.getStyle().set("font-size", "12px").set("color", "#64748b");
            detailsContainer.add(empty);
            return;
        }

        detailsContainer.add(createSectionHeader("Детали события"));
        Span title = new Span(valueOrBlank(event.getTitle()));
        title.getStyle()
                .set("font-weight", "700")
                .set("font-size", "16px")
                .set("color", "#0f172a");
        detailsContainer.add(title);
        detailsContainer.add(renderPassportRow("ID события", valueOrBlank(event.getId() == null ? null : event.getId().toString())));
        detailsContainer.add(renderPassportRow("Тип события", eventTypeLabel(event.getEventType())));
        detailsContainer.add(renderPassportRow("Кто менял", valueOrBlank(historyActor(event))));
        detailsContainer.add(renderPassportRow("Дата", formatHistoryDate(event.getEventDate())));

        if (event.getStatusBefore() != null || event.getStatusAfter() != null) {
            detailsContainer.add(renderPassportRow("Статус", formatStatusTransition(event.getStatusBefore(), event.getStatusAfter())));
        }
        if (event.getSource() != null || event.getSourceComment() != null) {
            detailsContainer.add(renderPassportRow("Источник", valueOrBlank(event.getSource())));
            detailsContainer.add(renderPassportRow("Комментарий источника", valueOrBlank(event.getSourceComment())));
        }
        if (event.getComment() != null && !event.getComment().isBlank()) {
            detailsContainer.add(renderPassportRow("Комментарий", event.getComment()));
        }
        detailsContainer.add(renderPassportRow("UUID объекта", event.getRentalItem() == null || event.getRentalItem().getId() == null
                ? "—"
                : event.getRentalItem().getId().toString()));

        VerticalLayout relations = new VerticalLayout();
        relations.setPadding(false);
        relations.setSpacing(false);
        addRelationRow(relations, "Смета", event.getEstimate() == null ? null : "Смета " + event.getEstimate().getId());
        addRelationRow(relations, "Досье", event.getRepairProcess() == null ? null : describeRepairProcess(event.getRepairProcess()));
        addRelationRow(relations, "Задача", event.getBoardTask() == null ? null : describeBoardTask(event.getBoardTask()));
        addRelationRow(relations, "Очередь", event.getQueueEntry() == null ? null : describeQueueEntry(event.getQueueEntry()));
        addRelationRow(relations, "Группа", event.getWorkerGroup() == null ? null : valueOrBlank(event.getWorkerGroup().getName()));
        addRelationRow(relations, "Рабочий", event.getWorker() == null ? null : valueOrBlank(event.getWorker().getDisplayName()));
        if (relations.getComponentCount() > 0) {
            detailsContainer.add(createSectionHeader("Связи"), relations);
        }

        HorizontalLayout actions = new HorizontalLayout();
        actions.setSpacing(true);
        if (event.getEstimate() != null && event.getEstimate().getId() != null) {
            UUID estimateId = event.getEstimate().getId();
            Button estimateButton = new Button("Открыть смету", new Icon(VaadinIcon.FILE_TEXT_O));
            estimateButton.addClickListener(action -> navigateToRepairEstimate(estimateId));
            actions.add(estimateButton);
        }
        if (event.getRepairProcess() != null && event.getRepairProcess().getId() != null) {
            UUID processId = event.getRepairProcess().getId();
            Button dossierButton = new Button("Открыть досье", new Icon(VaadinIcon.CLIPBOARD_TEXT));
            dossierButton.addClickListener(action -> getUI().ifPresent(ui -> ui.navigate("after-repair?processId=" + processId)));
            actions.add(dossierButton);
        }
        if (actions.getComponentCount() > 0) {
            detailsContainer.add(actions);
        }

        List<RentalItemEventAccessory> eventAccessories = rentalItemEventService.loadEventAccessories(event.getId());
        if (!eventAccessories.isEmpty()) {
            VerticalLayout accessoryContainer = new VerticalLayout();
            accessoryContainer.setPadding(false);
            accessoryContainer.setSpacing(false);
            eventAccessories.forEach(accessory -> accessoryContainer.add(renderPassportRow(
                    valueOrBlank(accessory.getAccessoryItem() == null ? null : accessory.getAccessoryItem().getName()),
                    (accessory.getQuantity() == null ? 0 : accessory.getQuantity()) + " шт."
            )));
            detailsContainer.add(createSectionHeader("Мебель / доп. оборудование"), accessoryContainer);
        }

        List<RentalItemEventPhoto> photos = rentalItemEventService.loadPhotos(event.getId());
        if (!photos.isEmpty()) {
            detailsContainer.add(createSectionHeader("Фото события"), buildEventPhotoPanel(photos));
        }

        List<RentalItemEvent> childEvents = childEventsOf(event, timeline);
        if (!childEvents.isEmpty()) {
            VerticalLayout childrenContainer = new VerticalLayout();
            childrenContainer.setPadding(false);
            childrenContainer.setSpacing(true);
            renderChildEventRows(event, timeline, childrenContainer, 0, new LinkedHashSet<>());
            detailsContainer.add(createSectionHeader("Подивенты"), childrenContainer);
        }
    }

    private void renderChildEventRows(RentalItemEvent parent,
                                      List<RentalItemEvent> timeline,
                                      VerticalLayout container,
                                      int depth,
                                      Set<UUID> visited) {
        if (parent == null || parent.getId() == null || container == null || timeline == null || !visited.add(parent.getId())) {
            return;
        }
        for (RentalItemEvent child : childEventsOf(parent, timeline)) {
            container.add(renderChildEventRow(child, depth + 1));
            renderChildEventRows(child, timeline, container, depth + 1, visited);
        }
    }

    private Div renderChildEventRow(RentalItemEvent event, int depth) {
        Div row = new Div();
        row.getStyle()
                .set("margin-left", Math.max(0, depth) * 16 + "px")
                .set("padding-left", "10px")
                .set("border-left", depth > 0 ? "2px solid #cbd5e1" : "none")
                .set("display", "grid")
                .set("gap", "4px");

        Span title = new Span(valueOrBlank(event.getTitle()));
        title.getStyle().set("font-weight", "600");
        row.add(title);

        Span meta = new Span(buildHistoryMeta(event));
        meta.getStyle().set("font-size", "12px").set("color", "#64748b");
        row.add(meta);
        return row;
    }

    private VerticalLayout buildEventPhotoPanel(List<RentalItemEventPhoto> photos) {
        VerticalLayout panel = new VerticalLayout();
        panel.setPadding(false);
        panel.setSpacing(true);
        panel.setWidthFull();
        panel.getStyle()
                .set("border", "1px solid #e2e8f0")
                .set("border-radius", "8px")
                .set("padding", "10px");

        List<String> urls = new ArrayList<>();
        List<String> previewUrls = new ArrayList<>();
        List<String> originalUrls = new ArrayList<>();
        List<String> captions = new ArrayList<>();
        for (RentalItemEventPhoto photo : photos) {
            urls.add(repairEstimateService.mediaUrl(photo.getId(), RepairEstimateService.PhotoVariant.THUMB));
            previewUrls.add(repairEstimateService.mediaUrl(photo.getId(), RepairEstimateService.PhotoVariant.PREVIEW));
            originalUrls.add(repairEstimateService.mediaUrl(photo.getId(), RepairEstimateService.PhotoVariant.ORIGINAL));
            captions.add(valueOrBlank(photo.getOriginalFileName()));
        }
        preloadPhotoSources(previewUrls);

        Image preview = new Image(urls.get(0), captions.get(0));
        preview.setWidthFull();
        preview.getStyle()
                .set("height", "240px")
                .set("object-fit", "contain")
                .set("border-radius", "8px")
                .set("background", "#e2e8f0");
        preview.addClickListener(event -> openPhotoGallery(previewUrls, originalUrls, captions, 0));

        Span caption = new Span(captions.get(0));
        caption.getStyle().set("font-size", "12px").set("color", "#475569");

        Button galleryButton = new Button("Галерея", new Icon(VaadinIcon.PICTURE));
        galleryButton.addClickListener(event -> openPhotoGallery(previewUrls, originalUrls, captions, 0));

        panel.add(preview, caption, galleryButton);
        return panel;
    }

    private void preloadPhotoSources(List<String> sources) {
        if (sources == null || sources.isEmpty() || !isAttached()) {
            return;
        }
        List<String> filteredSources = sources.stream()
                .filter(src -> src != null && !src.isBlank() && !src.startsWith("data:"))
                .distinct()
                .toList();
        if (filteredSources.isEmpty()) {
            return;
        }
        List<String> highPrioritySources = filteredSources.stream().limit(3).toList();
        List<String> backgroundSources = filteredSources.size() <= 3 ? List.of() : filteredSources.subList(3, filteredSources.size());
        if (!highPrioritySources.isEmpty()) {
            getElement().executeJs("""
                    if (!window.__rentalItemPhotoPreloads) {
                      window.__rentalItemPhotoPreloads = [];
                    }
                    const preload = (src) => {
                      if (!src) {
                        return;
                      }
                      const image = new Image();
                      image.decoding = 'async';
                      image.src = src;
                      window.__rentalItemPhotoPreloads.push(image);
                    };
                    const sources = $0 || [];
                    sources.forEach(preload);
                    """, highPrioritySources.toArray(new Serializable[0]));
        }
        if (!backgroundSources.isEmpty()) {
            getElement().executeJs("""
                    const preload = (src) => {
                      if (!src) {
                        return;
                      }
                      const image = new Image();
                      image.decoding = 'async';
                      image.src = src;
                      window.__rentalItemPhotoPreloads = window.__rentalItemPhotoPreloads || [];
                      window.__rentalItemPhotoPreloads.push(image);
                    };
                    const sources = $0 || [];
                    const runBackground = () => sources.forEach(preload);
                    if (window.requestIdleCallback) {
                      window.requestIdleCallback(runBackground, { timeout: 1000 });
                    } else {
                      setTimeout(runBackground, 0);
                    }
                    """, backgroundSources.toArray(new Serializable[0]));
        }
    }

    private void addRelationRow(VerticalLayout container, String label, String value) {
        if (container == null || value == null || value.isBlank()) {
            return;
        }
        container.add(renderPassportRow(label, value));
    }

    private String historyActor(RentalItemEvent event) {
        if (event == null) {
            return null;
        }
        if (event.getActorDisplayName() != null && !event.getActorDisplayName().isBlank()) {
            return event.getActorDisplayName();
        }
        return valueOrBlank(event.getActorUsername());
    }

    private String formatHistoryDate(OffsetDateTime eventDate) {
        if (eventDate == null) {
            return "—";
        }
        return eventDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    private String formatStatusTransition(String before, String after) {
        String beforeLabel = before == null || before.isBlank() ? "—" : RentalItemStatusSupport.presentationFor(before).label();
        String afterLabel = after == null || after.isBlank() ? "—" : RentalItemStatusSupport.presentationFor(after).label();
        return beforeLabel + " -> " + afterLabel;
    }

    private String describeRepairProcess(RepairProcess process) {
        if (process == null || process.getId() == null) {
            return null;
        }
        String status = process.getStatus() == null ? null : process.getStatus().name();
        return status == null ? "Процесс " + process.getId() : "Процесс " + process.getId() + " (" + status + ")";
    }

    private String describeBoardTask(BoardTask boardTask) {
        if (boardTask == null) {
            return null;
        }
        String title = valueOrBlank(boardTask.getTitle());
        String status = boardTask.getStatus() == null ? null : boardTask.getStatus().name();
        if (status == null || status.isBlank()) {
            return title;
        }
        return title + " (" + status + ")";
    }

    private String describeQueueEntry(QueueEntry queueEntry) {
        if (queueEntry == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (queueEntry.getQueue() != null && queueEntry.getQueue().getName() != null) {
            parts.add(queueEntry.getQueue().getName());
        }
        if (queueEntry.getTask() != null && queueEntry.getTask().getTitle() != null) {
            parts.add(queueEntry.getTask().getTitle());
        }
        if (queueEntry.getStatus() != null) {
            parts.add(queueEntry.getStatus().name());
        }
        return parts.isEmpty() ? valueOrBlank(queueEntry.getId() == null ? null : queueEntry.getId().toString()) : String.join(" / ", parts);
    }

    private List<RentalItemEvent> childEventsOf(RentalItemEvent parent, List<RentalItemEvent> timeline) {
        if (parent == null || parent.getId() == null || timeline == null || timeline.isEmpty()) {
            return List.of();
        }
        return timeline.stream()
                .filter(event -> event != null
                        && event.getParentEvent() != null
                        && parent.getId().equals(event.getParentEvent().getId()))
                .toList();
    }

    private VerticalLayout createHistoryEventDetailsLayout(RentalItemEvent event, List<RentalItemEvent> timeline) {
        VerticalLayout detailsContainer = new VerticalLayout();
        detailsContainer.setPadding(false);
        detailsContainer.setSpacing(true);
        detailsContainer.setWidthFull();
        detailsContainer.getStyle()
                .set("border-top", "1px solid #e2e8f0")
                .set("padding", "12px 16px 16px 16px")
                .set("background", "#fafafa");
        renderHistoryEventDetails(event, detailsContainer, timeline);
        return detailsContainer;
    }

    private void openRentalItemFromNavigation(UUID itemId) {
        if (itemId == null) {
            return;
        }
        try {
            RentalItem item = reloadRentalItem(itemId);
            applySavedItemFilters(item);
            RentalItem visibleItem = rentalItemsDc.getItems().stream()
                    .filter(candidate -> candidate != null && Objects.equals(candidate.getId(), itemId))
                    .findFirst()
                    .orElse(item);
            populateDetails(visibleItem);
            rentalItemsDataGrid.select(visibleItem);
            rentalItemsDataGrid.scrollToItem(visibleItem);
        } catch (Exception ignored) {
            viewStateService.setTerminalSelectedRentalItemId(null);
        }
    }

    private UUID parseUuidOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void navigateToRepairEstimate(UUID estimateId) {
        if (estimateId == null) {
            return;
        }
        getUI().ifPresent(ui -> ui.navigate("repair-estimates?estimateId=" + estimateId));
    }

    private record AttributeFieldState(RentalAttributeDefinition definition,
                                       RentalAttributeDataType dataType,
                                       com.vaadin.flow.component.Component component) {
    }
}

