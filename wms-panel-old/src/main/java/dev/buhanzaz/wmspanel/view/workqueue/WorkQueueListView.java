package dev.buhanzaz.wmspanel.view.workqueue;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.WorkQueueKind;
import dev.buhanzaz.wmspanel.entity.WorkQueue;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.entity.WorkerClass;
import dev.buhanzaz.wmspanel.service.QueueBoardService;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.core.DataManager;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.View.InitEvent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

@Route(value = "work-queues", layout = MainView.class)
@ViewController(id = "WorkQueue.list")
@ViewDescriptor(path = "work-queue-list-view.xml")
@LookupComponent("workQueuesDataGrid")
@DialogMode(width = "64em")
public class WorkQueueListView extends StandardListView<WorkQueue> {

    @Autowired
    private QueueBoardService queueBoardService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private Notifications notifications;

    @ViewComponent
    private DataGrid<WorkQueue> workQueuesDataGrid;

    @ViewComponent
    private Button createButton;
    @ViewComponent
    private Button editButton;

    @Subscribe
    public void onInit(final InitEvent event) {
        createButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        createButton.setText("Создать");
        createButton.addClickListener(click -> openBulkDialog(null));
        editButton.setText("Настроить");
        editButton.addClickListener(click -> {
            WorkQueue selectedQueue = workQueuesDataGrid.getSingleSelectedItem();
            if (selectedQueue != null) {
                openBulkDialog(selectedQueue);
            }
        });
    }

    private void openBulkDialog(WorkQueue selectedQueue) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(selectedQueue == null ? "Создать очередь для складов" : "Обновить очередь для складов");
        dialog.setWidth("52em");

        TextField codeField = new TextField("Код очереди");
        codeField.setWidthFull();
        codeField.setRequired(true);
        codeField.setValue(selectedQueue == null ? "" : valueOr(selectedQueue.getCode()));

        TextField nameField = new TextField("Название очереди");
        nameField.setWidthFull();
        nameField.setRequired(true);
        nameField.setValue(selectedQueue == null ? "" : valueOr(selectedQueue.getName()));

        TextArea descriptionField = new TextArea("Описание");
        descriptionField.setWidthFull();
        descriptionField.setValue(selectedQueue == null ? "" : valueOr(selectedQueue.getDescription()));

        ComboBox<WorkQueueKind> kindField = new ComboBox<>("Тип очереди");
        kindField.setItems(WorkQueueKind.MOVEMENT, WorkQueueKind.REPAIR, WorkQueueKind.HOLDING);
        kindField.setItemLabelGenerator(this::kindLabel);
        kindField.setWidthFull();
        kindField.setValue(selectedQueue == null || selectedQueue.getQueueKind() == null ? WorkQueueKind.REPAIR : selectedQueue.getQueueKind());

        IntegerField holdingPeriodField = new IntegerField("Период накопления, мин.");
        holdingPeriodField.setMin(1);
        holdingPeriodField.setWidthFull();
        holdingPeriodField.setValue(selectedQueue == null ? null : selectedQueue.getHoldingPeriodMinutes());

        IntegerField thresholdField = new IntegerField("Порог уведомления");
        thresholdField.setMin(1);
        thresholdField.setWidthFull();
        thresholdField.setValue(selectedQueue == null ? null : selectedQueue.getNotificationThreshold());

        Checkbox notifyField = new Checkbox("Уведомлять при достижении порога");
        notifyField.setValue(selectedQueue != null && Boolean.TRUE.equals(selectedQueue.getNotifyWhenThresholdReached()));

        MultiSelectComboBox<Warehouse> warehousesField = new MultiSelectComboBox<>("Склады");
        List<Warehouse> warehouses = dataManager.load(Warehouse.class)
                .query("select e from Warehouse e where e.active = true order by coalesce(e.sortOrder, 999999), e.name")
                .list();
        warehousesField.setItems(warehouses);
        warehousesField.setItemLabelGenerator(this::warehouseLabel);
        warehousesField.setWidthFull();
        if (selectedQueue != null && selectedQueue.getWarehouse() != null) {
            warehousesField.setValue(Set.of(selectedQueue.getWarehouse()));
        }

        VerticalLayout bindingsLayout = new VerticalLayout();
        bindingsLayout.setPadding(false);
        bindingsLayout.setSpacing(true);
        bindingsLayout.setWidthFull();

        List<QueueBindingOption> bindingOptions = buildBindingOptions(selectedQueue);
        bindingOptions.forEach(option -> bindingsLayout.add(option.row()));

        Span holdingHint = new Span("Период и порог сохраняются как настройки. Отдельный механизм уведомлений по времени пока не подключен.");
        holdingHint.getStyle().set("color", "#64748b").set("font-size", "12px");

        Runnable syncVisibility = () -> {
            boolean holding = kindField.getValue() == WorkQueueKind.HOLDING;
            holdingPeriodField.setVisible(holding);
            thresholdField.setVisible(holding);
            notifyField.setVisible(holding);
            holdingHint.setVisible(holding);
        };
        syncVisibility.run();
        kindField.addValueChangeListener(event -> syncVisibility.run());

        Button saveButton = new Button("Сохранить", event -> {
            try {
                Set<Warehouse> selectedWarehouses = warehousesField.getValue();
                if (selectedWarehouses == null || selectedWarehouses.isEmpty()) {
                    throw new IllegalArgumentException("Выберите хотя бы один склад");
                }
                List<WorkQueue> saved = queueBoardService.syncQueues(
                        selectedWarehouses,
                        codeField.getValue(),
                        nameField.getValue(),
                        descriptionField.getValue(),
                        kindField.getValue(),
                        holdingPeriodField.getValue(),
                        thresholdField.getValue(),
                        notifyField.getValue(),
                        bindingOptions.stream()
                                .filter(QueueBindingOption::selected)
                                .map(option -> new QueueBoardService.QueueWorkerClassBinding(option.workerClass(), option.stopTaskOnTake()))
                                .toList());
                notifications.create("Сохранено очередей: " + saved.size())
                        .withPosition(Notification.Position.TOP_END)
                        .withDuration(2500)
                        .show();
                dialog.close();
                refresh();
            } catch (Exception ex) {
                notifications.create(ex.getMessage() == null ? "Не удалось сохранить очереди" : ex.getMessage())
                        .withType(Notifications.Type.ERROR)
                        .withPosition(Notification.Position.TOP_END)
                        .withDuration(4500)
                        .show();
            }
        });
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button cancelButton = new Button("Отмена", event -> dialog.close());

        VerticalLayout content = new VerticalLayout(codeField, nameField, descriptionField, kindField,
                holdingPeriodField, thresholdField, notifyField, warehousesField, bindingsLayout, holdingHint);
        content.setPadding(false);
        content.setSpacing(true);
        dialog.add(content);
        dialog.getFooter().add(cancelButton, saveButton);
        dialog.open();
    }

    private void refresh() {
        workQueuesDataGrid.getDataProvider().refreshAll();
    }

    private String kindLabel(WorkQueueKind kind) {
        if (kind == null) {
            return "";
        }
        return switch (kind) {
            case MOVEMENT -> "Перемещение";
            case REPAIR -> "Ремонт";
            case HOLDING -> "Накопительная";
        };
    }

    private String warehouseLabel(Warehouse warehouse) {
        if (warehouse == null) {
            return "";
        }
        return valueOr(warehouse.getCity(), warehouse.getName());
    }

    private String valueOr(String value) {
        return value == null || value.isBlank() ? "" : value;
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? valueOr(fallback) : value;
    }

    private List<QueueBindingOption> buildBindingOptions(WorkQueue selectedQueue) {
        List<WorkerClass> activeClasses = queueBoardService.loadActiveWorkerClasses();
        LinkedHashMap<UUID, QueueBoardService.QueueWorkerClassBinding> existingBindings = new LinkedHashMap<>();
        if (selectedQueue != null) {
            queueBoardService.loadBindingsForQueue(selectedQueue).forEach(binding ->
                    existingBindings.put(binding.workerClass().getId(), binding));
        }
        return activeClasses.stream()
                .map(workerClass -> {
                    QueueBoardService.QueueWorkerClassBinding existing = existingBindings.get(workerClass.getId());
                    return new QueueBindingOption(workerClass,
                            existing != null,
                            existing != null && existing.stopTaskOnTake());
                })
                .toList();
    }

    private record QueueBindingOption(WorkerClass workerClass, Checkbox selectedField, Checkbox stopField, HorizontalLayout row) {
        private QueueBindingOption(WorkerClass workerClass, boolean selected, boolean stopTaskOnTake) {
            this(workerClass, new Checkbox(workerClass.getName(), selected), new Checkbox("Остановить текущее задание при принятии", stopTaskOnTake), new HorizontalLayout());
            stopField.setEnabled(selected);
            selectedField.addValueChangeListener(event -> {
                boolean enabled = Boolean.TRUE.equals(event.getValue());
                stopField.setEnabled(enabled);
                if (!enabled) {
                    stopField.setValue(false);
                }
            });
            row.setWidthFull();
            row.setAlignItems(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.CENTER);
            row.add(selectedField, stopField);
            row.expand(selectedField);
        }

        private boolean selected() {
            return Boolean.TRUE.equals(selectedField.getValue());
        }

        private boolean stopTaskOnTake() {
            return Boolean.TRUE.equals(stopField.getValue());
        }
    }
}
