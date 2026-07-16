package dev.buhanzaz.wmspanel.view.accessorystockbalance;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.AccessoryStockBalance;
import dev.buhanzaz.wmspanel.entity.RentalItem;
import dev.buhanzaz.wmspanel.entity.RentalItemAccessory;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.service.RentalItemEventService;
import dev.buhanzaz.wmspanel.service.RentalItemService;
import dev.buhanzaz.wmspanel.service.ViewStateService;
import io.jmix.core.DataManager;
import io.jmix.flowui.Notifications;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

@Route(value = "accessory-stock-balances", layout = MainView.class)
@ViewController(id = "AccessoryStockBalance.list")
@ViewDescriptor(path = "accessory-stock-balance-list-view.xml")
public class AccessoryStockBalanceListView extends StandardListView<AccessoryStockBalance> {

    @Autowired
    private RentalItemService rentalItemService;

    @Autowired
    private RentalItemEventService rentalItemEventService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private Notifications notifications;

    @Autowired
    private ViewStateService viewStateService;

    @ViewComponent
    private DataGrid<AccessoryStockBalance> accessoryStockBalancesDataGrid;

    @ViewComponent
    private CollectionLoader<AccessoryStockBalance> accessoryStockBalancesDl;

    @ViewComponent
    private ComboBox<Warehouse> warehouseFilter;

    @Subscribe
    public void onInit(final InitEvent event) {
        initWarehouseFilter();

        Grid.Column<AccessoryStockBalance> categoryColumn = accessoryStockBalancesDataGrid.getColumnByKey("category");
        if (categoryColumn != null) {
            categoryColumn.setRenderer(new ComponentRenderer<>(balance -> new Span(categoryName(balance))));
            categoryColumn.setAutoWidth(true);
            categoryColumn.setFlexGrow(0);
        }

        Grid.Column<AccessoryStockBalance> subcategoryColumn = accessoryStockBalancesDataGrid.getColumnByKey("subcategory");
        if (subcategoryColumn != null) {
            subcategoryColumn.setRenderer(new ComponentRenderer<>(balance -> new Span(subcategoryName(balance))));
            subcategoryColumn.setAutoWidth(true);
            subcategoryColumn.setFlexGrow(0);
        }

        accessoryStockBalancesDataGrid.setItemDetailsRenderer(new ComponentRenderer<>(this::buildAccessoryAssignmentsDetails));
        accessoryStockBalancesDataGrid.addItemClickListener(click -> toggleDetails(click.getItem()));
    }

    private void initWarehouseFilter() {
        List<Warehouse> warehouses = dataManager.load(Warehouse.class)
                .query("select e from Warehouse e where e.active = true order by coalesce(e.sortOrder, 999999), e.name")
                .list();
        warehouseFilter.setItems(warehouses);
        warehouseFilter.setItemLabelGenerator(this::warehouseLabel);
        warehouseFilter.addValueChangeListener(event -> {
            viewStateService.setSelectedWarehouseId(event.getValue() == null ? null : event.getValue().getId());
            reloadBalances();
        });
        warehouseFilter.setValue(viewStateService.resolveWarehouse(
                warehouses,
                viewStateService.getSelectedWarehouseId(),
                null));
    }

    private void reloadBalances() {
        Warehouse warehouse = warehouseFilter.getValue();
        accessoryStockBalancesDataGrid.getGenericDataView().getItems()
                .forEach(balance -> accessoryStockBalancesDataGrid.setDetailsVisible(balance, false));
        if (warehouse == null) {
            return;
        }
        accessoryStockBalancesDl.setParameter("warehouse", warehouse);
        accessoryStockBalancesDl.load();
    }

    private void toggleDetails(AccessoryStockBalance selected) {
        List<AccessoryStockBalance> balances = accessoryStockBalancesDataGrid.getGenericDataView().getItems().toList();
        boolean collapse = accessoryStockBalancesDataGrid.isDetailsVisible(selected);
        balances.forEach(balance -> accessoryStockBalancesDataGrid.setDetailsVisible(balance, false));
        if (!collapse) {
            accessoryStockBalancesDataGrid.setDetailsVisible(selected, true);
        }
    }

    private VerticalLayout buildAccessoryAssignmentsDetails(AccessoryStockBalance balance) {
        VerticalLayout container = new VerticalLayout();
        container.setPadding(false);
        container.setSpacing(true);
        container.setWidthFull();
        container.getStyle()
                .set("padding", "12px 16px 16px 16px")
                .set("border-top", "1px solid #e2e8f0")
                .set("background", "#fafafa");

        if (balance == null || balance.getAccessoryItem() == null || balance.getAccessoryItem().getId() == null) {
            container.add(new Span("Нет данных по объектам."));
            return container;
        }

        List<RentalItemAccessory> assignments = rentalItemService.loadAssignmentsByAccessory(balance.getAccessoryItem().getId()).stream()
                .filter(assignment -> assignment.getRentalItem() != null)
                .filter(assignment -> balance.getWarehouse() == null
                        || (assignment.getRentalItem().getWarehouse() != null
                        && Objects.equals(assignment.getRentalItem().getWarehouse().getId(), balance.getWarehouse().getId())))
                .toList();
        if (assignments.isEmpty()) {
            Span empty = new Span("Эта позиция пока не привязана ни к одному объекту.");
            empty.getStyle().set("color", "#64748b").set("font-size", "12px");
            container.add(empty);
            return container;
        }

        assignments.forEach(assignment -> container.add(buildAssignmentRow(assignment)));
        return container;
    }

    private HorizontalLayout buildAssignmentRow(RentalItemAccessory assignment) {
        HorizontalLayout row = new HorizontalLayout();
        row.setWidthFull();
        row.setSpacing(true);
        row.setAlignItems(FlexComponent.Alignment.CENTER);

        String number = assignment.getRentalItem() == null ? "—" : assignment.getRentalItem().getNumber();
        String warehouse = assignment.getRentalItem() != null && assignment.getRentalItem().getWarehouse() != null
                ? assignment.getRentalItem().getWarehouse().getName()
                : "";
        Span label = new Span(number + (warehouse.isBlank() ? "" : " • " + warehouse));
        label.getStyle().set("font-weight", "600");

        Span quantity = new Span((assignment.getQuantity() == null ? 0 : assignment.getQuantity()) + " шт.");
        quantity.getStyle().set("color", "#475569");

        Button open = new Button("Открыть объект", event -> {
            if (assignment.getRentalItem() != null && assignment.getRentalItem().getId() != null) {
                UI.getCurrent().navigate("rental-items?itemId=" + assignment.getRentalItem().getId());
            }
        });

        Button transfer = new Button("Переместить", event -> openTransferDialog(assignment));

        row.add(label, quantity, open, transfer);
        row.expand(label);
        return row;
    }

    private void openTransferDialog(RentalItemAccessory assignment) {
        if (assignment == null || assignment.getRentalItem() == null || assignment.getAccessoryItem() == null) {
            return;
        }
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Переместить доп. оборудование");
        dialog.setWidth("42em");

        ComboBox<RentalItem> targetField = new ComboBox<>("Куда перемещаем");
        List<RentalItem> targets = rentalItemService.loadAccessoryTransferTargets(
                assignment.getRentalItem().getWarehouse() == null ? null : assignment.getRentalItem().getWarehouse().getId(),
                assignment.getRentalItem().getId());
        targetField.setItems(targets);
        targetField.setItemLabelGenerator(this::rentalItemLabel);
        targetField.setWidthFull();

        IntegerField quantityField = new IntegerField("Количество");
        quantityField.setMin(1);
        quantityField.setMax(assignment.getQuantity() == null ? 0 : assignment.getQuantity());
        quantityField.setValue(Math.min(assignment.getQuantity() == null ? 1 : assignment.getQuantity(), 1));
        quantityField.setStepButtonsVisible(true);
        quantityField.setWidthFull();

        Span hint = new Span("Источник: " + rentalItemLabel(assignment.getRentalItem()) + ". Позиция: "
                + (assignment.getAccessoryItem().getName() == null ? "доп. оборудование" : assignment.getAccessoryItem().getName()));
        hint.getStyle().set("color", "#64748b").set("font-size", "12px");

        VerticalLayout content = new VerticalLayout(hint, targetField, quantityField);
        content.setPadding(false);
        content.setSpacing(true);
        dialog.add(content);

        Button cancel = new Button("Отмена", event -> dialog.close());
        Button move = new Button("Переместить", event -> {
            try {
                RentalItem target = targetField.getValue();
                Integer quantity = quantityField.getValue();
                RentalItemService.AccessoryTransferResult result = rentalItemService.transferItemAccessory(
                        assignment.getId(),
                        target == null ? null : target.getId(),
                        quantity == null ? 0 : quantity
                );
                recordTransferEvents(result);
                notifications.create("Допоборудование перемещено")
                        .withType(Notifications.Type.SUCCESS)
                        .show();
                dialog.close();
                reloadBalances();
            } catch (RuntimeException ex) {
                notifications.create(ex.getMessage() == null ? "Не удалось переместить допоборудование" : ex.getMessage())
                        .withType(Notifications.Type.ERROR)
                        .show();
            }
        });
        dialog.getFooter().add(cancel, move);
        dialog.open();
    }

    private void recordTransferEvents(RentalItemService.AccessoryTransferResult result) {
        if (result == null) {
            return;
        }
        String accessoryName = result.accessoryItem() == null ? "допоборудование" : result.accessoryItem().getName();
        String fromComment = "Перемещено " + result.quantity() + " шт. " + accessoryName + " в объект " + rentalItemLabel(result.targetItem());
        String toComment = "Получено " + result.quantity() + " шт. " + accessoryName + " из объекта " + rentalItemLabel(result.sourceItem());
        OffsetDateTime now = OffsetDateTime.now();
        rentalItemEventService.recordAccessorySnapshot(
                result.sourceItem(),
                result.sourceItem() == null ? null : result.sourceItem().getWarehouse(),
                null,
                now,
                fromComment,
                "WMS_ACCESSORY_TRANSFER",
                "Перемещение допоборудования",
                result.sourceAssignments()
        );
        rentalItemEventService.recordAccessorySnapshot(
                result.targetItem(),
                result.targetItem() == null ? null : result.targetItem().getWarehouse(),
                null,
                now,
                toComment,
                "WMS_ACCESSORY_TRANSFER",
                "Перемещение допоборудования",
                result.targetAssignments()
        );
    }

    private String categoryName(AccessoryStockBalance balance) {
        if (balance.getAccessoryItem() == null || balance.getAccessoryItem().getCategory() == null) {
            return "";
        }
        return balance.getAccessoryItem().getCategory().getName();
    }

    private String subcategoryName(AccessoryStockBalance balance) {
        if (balance.getAccessoryItem() == null || balance.getAccessoryItem().getSubcategory() == null) {
            return "";
        }
        return balance.getAccessoryItem().getSubcategory().getName();
    }

    private String warehouseLabel(Warehouse warehouse) {
        if (warehouse == null) {
            return "";
        }
        return warehouse.getName() == null || warehouse.getName().isBlank() ? warehouse.getCode() : warehouse.getName();
    }

    private String rentalItemLabel(RentalItem item) {
        if (item == null) {
            return "";
        }
        String number = item.getNumber() == null ? "без номера" : item.getNumber();
        String type = item.getType() == null || item.getType().getName() == null ? "" : " / " + item.getType().getName();
        return number + type;
    }
}
