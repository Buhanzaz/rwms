package dev.buhanzaz.wmspanel.view.warehouseai;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.TextFieldVariant;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.ReservationType;
import dev.buhanzaz.wmspanel.entity.RentalItem;
import dev.buhanzaz.wmspanel.entity.StockItem;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.service.WarehouseAiQueryInterpreter;
import dev.buhanzaz.wmspanel.service.ReservationService;
import dev.buhanzaz.wmspanel.service.WarehouseAccessService;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.core.DataManager;
import io.jmix.core.ValueLoadContext;
import io.jmix.core.entity.KeyValueEntity;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.model.KeyValueCollectionLoader;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

@Route(value = "warehouse-ai-search", layout = MainView.class)
@ViewController(id = "WarehouseAiSearch.view")
@ViewDescriptor(path = "warehouse-ai-search-view.xml")
public class WarehouseAiSearchView extends StandardListView<KeyValueEntity> {

    @Autowired
    private DataManager dataManager;

    @Autowired
    private Notifications notifications;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private WarehouseAccessService warehouseAccessService;

    @ViewComponent
    private TextField searchField;

    @ViewComponent
    private Div searchHint;

    @ViewComponent
    private KeyValueCollectionLoader searchResultsDl;

    @ViewComponent
    private DataGrid<KeyValueEntity> searchResultsDataGrid;

    @Autowired
    private WarehouseAiQueryInterpreter warehouseAiQueryInterpreter;

    private boolean searchPerformed;

    @Subscribe
    public void onInit(final InitEvent event) {
        configureSearchField();
        searchResultsDl.setLoadDelegate(this::loadSearchResults);
        configureActionsColumn();
    }

    @Subscribe("searchButton")
    public void onSearchButtonClick(final com.vaadin.flow.component.ClickEvent<Button> event) {
        if (isSearchTextBlank()) {
            notifications.create("Введите текст поиска")
                    .withPosition(Notification.Position.TOP_END)
                    .withDuration(2000)
                    .show();
            return;
        }
        searchPerformed = true;
        searchResultsDl.load();
    }

    private void configureSearchField() {
        searchField.setPlaceholder("Например: свободная БК-6 СПБ или линолеум Москва");
        searchHint.setText(warehouseAiQueryInterpreter.isConfigured()
                ? "Можно писать свободным текстом: AI сам попробует выделить склад и условия поиска."
                : "Можно писать склад прямо в строке поиска. Без API-ключа используется локальный разбор запроса.");
    }

    private boolean isSearchTextBlank() {
        return searchField.getValue() == null || searchField.getValue().isBlank();
    }

    private List<KeyValueEntity> loadSearchResults(ValueLoadContext context) {
        WarehouseAiQueryInterpreter.SearchInterpretation interpretation =
                warehouseAiQueryInterpreter.interpret(searchField.getValue(), warehouseAccessService.availableWarehouses());
        Warehouse warehouse = interpretation.warehouse();
        List<String> searchTokens = interpretation.searchTokens();
        if (searchTokens.isEmpty() && warehouse == null) {
            return List.of();
        }

        List<KeyValueEntity> rows = new ArrayList<>();
        rows.addAll(filterRows(loadRentalRows(warehouse), searchTokens));
        rows.addAll(filterRows(loadStockRows(warehouse), searchTokens));
        rows.sort(rowComparator());
        return rows;
    }

    private List<KeyValueEntity> loadRentalRows(Warehouse warehouse) {
        List<KeyValueEntity> rows = dataManager.loadValues("""
                        select e.id,
                               e.warehouse.id,
                               e.warehouse.name,
                               e.number,
                               case
                                   when e.type is not null then e.type.name
                                   when e.subcategory is not null then e.subcategory.name
                                   else e.category.name
                               end,
                               e.type.name,
                               e.category.name,
                               e.status,
                               e.comment
                        from RentalItem e
                        where (:warehouse is null or e.warehouse = :warehouse)
                        order by e.warehouse.name, e.number
                        """)
                .properties("entityId", "warehouseId", "warehouse", "number", "name", "type", "category", "status", "comment")
                .parameter("warehouse", warehouse)
                .list();

        for (KeyValueEntity row : rows) {
            row.setValue("sourceType", "RENTAL");
            row.setValue("itemType", "Складская позиция");
            row.setValue("total", 1);
            RentalItem item = loadRentalItem(row.getValue("entityId"));
            int reserved = reservationService.activeReservedQuantity(item);
            row.setValue("reserved", reserved);
            row.setValue("available", reservationService.available(item));
        }
        return rows;
    }

    private List<KeyValueEntity> loadStockRows(Warehouse warehouse) {
        List<KeyValueEntity> rows = dataManager.loadValues("""
                        select e.id,
                               e.warehouse.id,
                               e.warehouse.name,
                               e.name,
                               e.segment.name,
                               e.unit,
                               e.category.name,
                               case when e.active = true then 'Активен' else 'Неактивен' end,
                               e.comment
                        from StockItem e
                        where (:warehouse is null or e.warehouse = :warehouse)
                        order by e.warehouse.name, e.name
                        """)
                .properties("entityId", "warehouseId", "warehouse", "name", "segment", "type", "category", "status", "comment")
                .parameter("warehouse", warehouse)
                .list();

        for (KeyValueEntity row : rows) {
            row.setValue("sourceType", "STOCK");
            row.setValue("itemType", "Складской остаток");
            row.setValue("number", "");
            StockItem item = loadStockItem(row.getValue("entityId"));
            int reserved = reservationService.activeReservedQuantity(item);
            row.setValue("total", item.getTotalQuantity());
            row.setValue("reserved", reserved);
            row.setValue("available", reservationService.available(item));
        }
        return rows;
    }

    private List<KeyValueEntity> filterRows(List<KeyValueEntity> rows, List<String> searchTokens) {
        if (searchTokens.isEmpty()) {
            return rows;
        }
        return rows.stream()
                .filter(row -> matchesAllTokens(row, searchTokens))
                .toList();
    }

    private boolean matchesAllTokens(KeyValueEntity row, List<String> searchTokens) {
        String haystack = Stream.of(
                        row.getValue("warehouse"),
                        row.getValue("itemType"),
                        row.getValue("number"),
                        row.getValue("name"),
                        row.getValue("type"),
                        row.getValue("category"),
                        row.getValue("status"),
                        row.getValue("comment"))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .reduce("", (left, right) -> left + " " + right);

        return searchTokens.stream().allMatch(haystack::contains);
    }

    private RentalItem loadRentalItem(UUID id) {
        return dataManager.load(RentalItem.class).id(id).one();
    }

    private StockItem loadStockItem(UUID id) {
        return dataManager.load(StockItem.class).id(id).one();
    }

    private Comparator<KeyValueEntity> rowComparator() {
        return Comparator
                .comparing((KeyValueEntity row) -> stringValue(row, "warehouse"), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(row -> stringValue(row, "itemType"), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(row -> stringValue(row, "name"), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(row -> stringValue(row, "number"), String.CASE_INSENSITIVE_ORDER);
    }

    private String stringValue(KeyValueEntity row, String property) {
        Object value = row.getValue(property);
        return value == null ? "" : value.toString();
    }

    private void configureActionsColumn() {
        Grid.Column<KeyValueEntity> actionsColumn = searchResultsDataGrid.getColumnByKey("actions");
        if (actionsColumn == null) {
            return;
        }
        actionsColumn.setRenderer(new ComponentRenderer<>(this::createActionsCell));
        actionsColumn.setAutoWidth(true);
        actionsColumn.setFlexGrow(0);
    }

    private HorizontalLayout createActionsCell(KeyValueEntity row) {
        if (!"RENTAL".equals(Objects.toString(row.getValue("sourceType"), ""))) {
            HorizontalLayout empty = new HorizontalLayout(new Span(""));
            empty.setPadding(false);
            empty.setSpacing(false);
            return empty;
        }

        Button temporaryButton = new Button("Временный резерв", event -> reserve(row, ReservationType.TEMPORARY));
        Button clientButton = new Button("Резерв для клиента", event -> reserve(row, ReservationType.CLIENT));

        int available = asInt(row.getValue("available"));
        boolean enabled = available > 0;
        temporaryButton.setEnabled(enabled);
        clientButton.setEnabled(enabled);

        HorizontalLayout layout = new HorizontalLayout(temporaryButton, clientButton);
        layout.setPadding(false);
        layout.setSpacing(true);
        layout.setAlignItems(FlexComponent.Alignment.CENTER);
        return layout;
    }

    private void reserve(KeyValueEntity row, ReservationType type) {
        if (type == ReservationType.CLIENT) {
            openClientReservationDialog(row);
            return;
        }

        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Временный резерв");

        IntegerField quantityField = new IntegerField("Количество");
        quantityField.setMin(1);
        quantityField.setValue(1);
        quantityField.setReadOnly(true);
        quantityField.setHelperText("Доступно: " + asInt(row.getValue("available")));

        ComboBox<String> durationField = new ComboBox<>("Срок");
        durationField.setItems("30 минут", "1 час", "2 часа", "4 часа", "До конца дня", "24 часа");
        durationField.setValue("24 часа");

        TextArea commentField = new TextArea("Комментарий");
        commentField.setWidthFull();

        Button confirmButton = new Button("Подтвердить", event -> {
            createReservation(row, type, quantityField.getValue(), expiresAt(durationField.getValue()),
                    null, null, null, commentField.getValue(), null);
            dialog.close();
        });
        Button cancelButton = new Button("Отмена", event -> dialog.close());

        VerticalLayout content = new VerticalLayout(quantityField, durationField, commentField);
        content.setPadding(false);
        dialog.add(content);
        dialog.getFooter().add(cancelButton, confirmButton);
        dialog.open();
    }

    private void openClientReservationDialog(KeyValueEntity row) {
        RentalItem rentalItem = loadRentalItem(row.getValue("entityId"));
        List<StockItem> stockItems = loadWarehouseStockItems(row);

        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Резерв для клиента");
        dialog.setWidth("56em");

        TextField rentalItemNumberField = new TextField("Номер бытовки");
        rentalItemNumberField.setReadOnly(true);
        rentalItemNumberField.setValue(valueOrBlank(rentalItem.getNumber()));

        ComboBox<String> counterpartyTypeField = new ComboBox<>("Тип контрагента");
        counterpartyTypeField.setItems("Компания", "Юрлицо");
        counterpartyTypeField.setValue("Компания");

        TextField counterpartyNameField = new TextField("Название");
        counterpartyNameField.setWidthFull();

        TextArea commentField = new TextArea("Комментарий");
        commentField.setWidthFull();

        VerticalLayout equipmentRows = new VerticalLayout();
        equipmentRows.setPadding(false);
        equipmentRows.setSpacing(true);

        List<EquipmentRow> equipmentSelections = new ArrayList<>();
        addEquipmentRow(equipmentRows, equipmentSelections, stockItems);

        Button addRowButton = new Button("+", event -> addEquipmentRow(equipmentRows, equipmentSelections, stockItems));
        addRowButton.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_TERTIARY);

        Button confirmButton = new Button("Подтвердить", event -> {
            try {
                String companyName = "Компания".equals(counterpartyTypeField.getValue()) ? counterpartyNameField.getValue() : null;
                String legalEntity = "Юрлицо".equals(counterpartyTypeField.getValue()) ? counterpartyNameField.getValue() : null;
                reservationService.reserveClientPackage(
                        rentalItem,
                        companyName,
                        legalEntity,
                        commentField.getValue(),
                        equipmentSelections.stream()
                                .map(selection -> selection.toRequest())
                                .filter(Objects::nonNull)
                                .toList());

                notifications.create("Клиентский резерв создан")
                        .withPosition(Notification.Position.TOP_END)
                        .withDuration(2200)
                        .show();
                searchResultsDl.load();
                dialog.close();
            } catch (Exception ex) {
                notifications.create(ex.getMessage() == null ? "Не удалось создать резерв" : ex.getMessage())
                        .withType(Notifications.Type.ERROR)
                        .withPosition(Notification.Position.TOP_END)
                        .withDuration(4000)
                        .show();
            }
        });
        Button cancelButton = new Button("Отмена", event -> dialog.close());

        VerticalLayout content = new VerticalLayout(
                rentalItemNumberField,
                counterpartyTypeField,
                counterpartyNameField,
                commentField,
                equipmentRows,
                addRowButton);
        content.setPadding(false);
        content.setSpacing(true);
        dialog.add(content);
        dialog.getFooter().add(cancelButton, confirmButton);
        dialog.open();
    }

    private void createReservation(KeyValueEntity row, ReservationType type, Integer quantity, OffsetDateTime expiresAt,
                                   String companyName, String legalEntity, String contactPerson, String comment,
                                   RentalItem linkedRentalItem) {
        try {
            String sourceType = Objects.toString(row.getValue("sourceType"), "");
            UUID id = row.getValue("entityId");

            if ("RENTAL".equals(sourceType)) {
                RentalItem item = loadRentalItem(id);
                reservationService.reserveWarehouseItem(item, type, expiresAt, companyName, legalEntity, contactPerson, comment, linkedRentalItem);
            } else {
                StockItem item = loadStockItem(id);
                reservationService.reserveStockItem(item, quantity == null ? 0 : quantity, type, expiresAt, companyName, legalEntity, contactPerson, comment, linkedRentalItem);
            }

            notifications.create("Резерв создан")
                    .withPosition(Notification.Position.TOP_END)
                    .withDuration(2200)
                    .show();
            searchResultsDl.load();
        } catch (Exception ex) {
            notifications.create(ex.getMessage() == null ? "Не удалось создать резерв" : ex.getMessage())
                    .withType(Notifications.Type.ERROR)
                    .withPosition(Notification.Position.TOP_END)
                    .withDuration(4000)
                    .show();
        }
    }

    private List<RentalItem> loadWarehouseRentalItems(KeyValueEntity row) {
        UUID warehouseId = row.getValue("warehouseId");
        if (warehouseId == null) {
            return List.of();
        }
        return dataManager.load(RentalItem.class)
                .query("""
                        select e from RentalItem e
                        where e.warehouse.id = :warehouseId
                        order by e.number
                        """)
                .parameter("warehouseId", warehouseId)
                .list();
    }

    private List<StockItem> loadWarehouseStockItems(KeyValueEntity row) {
        UUID warehouseId = row.getValue("warehouseId");
        if (warehouseId == null) {
            return List.of();
        }
        return dataManager.load(StockItem.class)
                .query("""
                        select e from StockItem e
                        where e.warehouse.id = :warehouseId and e.active = true
                        order by e.segment.name, e.name
                        """)
                .parameter("warehouseId", warehouseId)
                .list();
    }

    private void addEquipmentRow(VerticalLayout rowsContainer, List<EquipmentRow> selections, List<StockItem> stockItems) {
        EquipmentRow equipmentRow = new EquipmentRow(stockItems);
        selections.add(equipmentRow);
        equipmentRow.removeButton.addClickListener(event -> {
            selections.remove(equipmentRow);
            rowsContainer.remove(equipmentRow.layout);
        });
        rowsContainer.add(equipmentRow.layout);
    }

    private OffsetDateTime expiresAt(String duration) {
        OffsetDateTime now = OffsetDateTime.now();
        return switch (duration == null ? "24 часа" : duration) {
            case "30 минут" -> now.plusMinutes(30);
            case "1 час" -> now.plusHours(1);
            case "2 часа" -> now.plusHours(2);
            case "4 часа" -> now.plusHours(4);
            case "До конца дня" -> now.withHour(23).withMinute(59).withSecond(59).withNano(0);
            default -> now.plusHours(24);
        };
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String valueOrBlank(String value) {
        return value == null ? "" : value;
    }

    private final class EquipmentRow {
        private final HorizontalLayout layout;
        private final ComboBox<StockItem> stockItemField;
        private final IntegerField quantityField;
        private final Button removeButton;

        private EquipmentRow(List<StockItem> stockItems) {
            stockItemField = new ComboBox<>("Доп. оборудование");
            stockItemField.setItems(stockItems);
            stockItemField.setItemLabelGenerator(item -> {
                String category = item.getSegment() == null ? "" : item.getSegment().getName();
                return category.isBlank() ? item.getName() : category + " - " + item.getName();
            });
            stockItemField.setWidth("26em");

            quantityField = new IntegerField("Кол-во");
            quantityField.setMin(1);
            quantityField.setValue(1);
            quantityField.setWidth("10em");

            removeButton = new Button("-");
            removeButton.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_TERTIARY);

            stockItemField.addValueChangeListener(event -> {
                StockItem item = event.getValue();
                if (item != null) {
                    quantityField.setHelperText("Доступно: " + reservationService.available(item) + " " + item.getUnit());
                } else {
                    quantityField.setHelperText(null);
                }
            });

            layout = new HorizontalLayout(stockItemField, quantityField, removeButton);
            layout.setPadding(false);
            layout.setSpacing(true);
            layout.setAlignItems(FlexComponent.Alignment.END);
            layout.setWidthFull();
        }

        private ReservationService.StockReservationRequest toRequest() {
            if (stockItemField.getValue() == null || quantityField.getValue() == null || quantityField.getValue() <= 0) {
                return null;
            }
            return new ReservationService.StockReservationRequest(stockItemField.getValue(), quantityField.getValue());
        }
    }
}
