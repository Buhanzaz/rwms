package dev.buhanzaz.wmspanel.view.queueorder;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dnd.DragSource;
import com.vaadin.flow.component.dnd.DropTarget;
import com.vaadin.flow.component.dnd.EffectAllowed;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.entity.WorkQueue;
import dev.buhanzaz.wmspanel.entity.WorkQueueKind;
import dev.buhanzaz.wmspanel.service.QueueBoardService;
import dev.buhanzaz.wmspanel.service.ViewStateService;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.core.DataManager;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.View.BeforeShowEvent;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Route(value = "queue-order", layout = MainView.class)
@ViewController(id = "QueueOrder.view")
@ViewDescriptor(path = "queue-order-view.xml")
public class QueueOrderView extends StandardView {

    @Autowired
    private DataManager dataManager;

    @Autowired
    private Notifications notifications;

    @Autowired
    private QueueBoardService queueBoardService;

    @Autowired
    private ViewStateService viewStateService;

    @ViewComponent
    private MessageBundle messageBundle;

    @ViewComponent
    private ComboBox<Warehouse> warehouseField;

    @ViewComponent
    private VerticalLayout queuesLayout;

    @ViewComponent
    private Button saveButton;

    private final List<WorkQueue> queues = new ArrayList<>();
    private boolean controlsInitialized;

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        if (!controlsInitialized) {
            initControls();
        }
        loadWarehouses();
    }

    private void initControls() {
        controlsInitialized = true;
        warehouseField.setItemLabelGenerator(this::warehouseLabel);
        warehouseField.addValueChangeListener(event -> {
            viewStateService.setSelectedWarehouseId(event.getValue() == null ? null : event.getValue().getId());
            loadQueues();
        });
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveButton.addClickListener(event -> saveOrder());
    }

    private void loadWarehouses() {
        List<Warehouse> warehouses = dataManager.load(Warehouse.class)
                .query("""
                        select e from Warehouse e
                        where e.active = true
                        order by coalesce(e.sortOrder, 2147483647), e.name
                        """)
                .list();
        warehouseField.setItems(warehouses);
        Warehouse resolvedWarehouse = viewStateService.resolveWarehouse(
                warehouses,
                viewStateService.getSelectedWarehouseId(),
                warehouseField.getValue());
        if (!Objects.equals(warehouseField.getValue(), resolvedWarehouse)) {
            warehouseField.setValue(resolvedWarehouse);
        } else {
            loadQueues();
        }
    }

    private void loadQueues() {
        queues.clear();
        Warehouse warehouse = warehouseField.getValue();
        if (warehouse != null) {
            queues.addAll(queueBoardService.loadAllQueues(warehouse).stream()
                    .sorted(this::compareForEditor)
                    .toList());
        }
        renderQueues();
    }

    private int compareForEditor(WorkQueue left, WorkQueue right) {
        boolean leftHolding = isHoldingQueue(left);
        boolean rightHolding = isHoldingQueue(right);
        if (leftHolding != rightHolding) {
            return leftHolding ? 1 : -1;
        }
        return Comparator
                .comparing((WorkQueue queue) -> queue.getSortOrder() == null ? Integer.MAX_VALUE : queue.getSortOrder())
                .thenComparing(queue -> valueOr(queue.getName()))
                .compare(left, right);
    }

    private void renderQueues() {
        queuesLayout.removeAll();
        if (queues.isEmpty()) {
            Span empty = new Span(messageBundle.getMessage("emptyQueues.text"));
            empty.getStyle().set("color", "#64748b");
            queuesLayout.add(empty);
            saveButton.setEnabled(false);
            return;
        }
        saveButton.setEnabled(true);
        for (int i = 0; i < queues.size(); i++) {
            WorkQueue queue = queues.get(i);
            queuesLayout.add(createQueueCard(queue));
            if (i < queues.size() - 1) {
                queuesLayout.add(createArrow(queue, queues.get(i + 1)));
            }
        }
    }

    private Div createQueueCard(WorkQueue queue) {
        Div card = new Div();
        card.getStyle()
                .set("border", "1px solid #cbd5e1")
                .set("border-radius", "8px")
                .set("background", isHoldingQueue(queue) ? "#f8fafc" : "#ffffff")
                .set("padding", "12px")
                .set("box-shadow", "0 1px 2px rgba(15, 23, 42, 0.08)")
                .set("max-width", "760px")
                .set("cursor", isHoldingQueue(queue) ? "default" : "grab");

        H3 title = new H3(queue.getName());
        title.getStyle().set("margin", "0").set("font-size", "18px");
        Span meta = new Span(queueMeta(queue));
        meta.getStyle().set("color", "#64748b").set("font-size", "12px");

        Span dragHint = new Span(isHoldingQueue(queue)
                ? messageBundle.getMessage("holdingHint.text")
                : messageBundle.getMessage("dragHint.text"));
        dragHint.getStyle().set("color", "#64748b").set("font-size", "12px");

        VerticalLayout content = new VerticalLayout(title, meta, dragHint);
        content.setPadding(false);
        content.setSpacing(false);
        card.add(content);

        if (!isHoldingQueue(queue)) {
            DragSource<Div> dragSource = DragSource.create(card);
            dragSource.setEffectAllowed(EffectAllowed.MOVE);
            dragSource.setDragData(queue.getId().toString());
        }
        DropTarget<Div> dropTarget = DropTarget.create(card);
        dropTarget.addDropListener(event -> handleQueueDrop(event.getDragData().orElse(null), queue));
        return card;
    }

    private Span createArrow(WorkQueue source, WorkQueue target) {
        Span arrow = new Span(isHoldingQueue(source) || isHoldingQueue(target) ? "↓" : "↓");
        arrow.getStyle()
                .set("display", "block")
                .set("width", "760px")
                .set("text-align", "center")
                .set("font-size", "30px")
                .set("line-height", "34px")
                .set("color", isHoldingQueue(target) ? "#94a3b8" : "#2563eb");
        return arrow;
    }

    private void handleQueueDrop(Object dragData, WorkQueue target) {
        if (dragData == null || target == null || isHoldingQueue(target)) {
            return;
        }
        UUID draggedId;
        try {
            draggedId = UUID.fromString(dragData.toString());
        } catch (IllegalArgumentException ignored) {
            return;
        }
        WorkQueue dragged = queues.stream()
                .filter(queue -> Objects.equals(queue.getId(), draggedId))
                .findFirst()
                .orElse(null);
        if (dragged == null || dragged == target || isHoldingQueue(dragged)) {
            return;
        }
        queues.remove(dragged);
        int targetIndex = queues.indexOf(target);
        queues.add(Math.max(0, targetIndex), dragged);
        queues.sort(this::compareHoldingOnly);
        renderQueues();
    }

    private int compareHoldingOnly(WorkQueue left, WorkQueue right) {
        boolean leftHolding = isHoldingQueue(left);
        boolean rightHolding = isHoldingQueue(right);
        if (leftHolding != rightHolding) {
            return leftHolding ? 1 : -1;
        }
        return 0;
    }

    private void saveOrder() {
        Warehouse warehouse = warehouseField.getValue();
        if (warehouse == null) {
            notifications.create(messageBundle.getMessage("warehouseRequired.text"))
                    .withType(Notifications.Type.WARNING)
                    .withPosition(Notification.Position.TOP_END)
                    .show();
            return;
        }
        List<UUID> orderedQueueIds = queues.stream()
                .map(WorkQueue::getId)
                .toList();
        queueBoardService.saveQueueOrder(warehouse, orderedQueueIds);
        notifications.create(messageBundle.getMessage("saved.text"))
                .withPosition(Notification.Position.TOP_END)
                .withDuration(2500)
                .show();
        loadQueues();
    }

    private String queueMeta(WorkQueue queue) {
        String kind = switch (queue.getQueueKind() == null ? WorkQueueKind.REPAIR : queue.getQueueKind()) {
            case MOVEMENT -> messageBundle.getMessage("queueKind.movement");
            case REPAIR -> messageBundle.getMessage("queueKind.repair");
            case HOLDING -> messageBundle.getMessage("queueKind.holding");
        };
        return messageBundle.formatMessage("queueMeta.text", queue.getCode(), kind,
                queue.getSortOrder() == null ? "" : queue.getSortOrder());
    }

    private boolean isHoldingQueue(WorkQueue queue) {
        return queue != null && (Boolean.TRUE.equals(queue.getSinkQueue()) || queue.getQueueKind() == WorkQueueKind.HOLDING);
    }

    private String warehouseLabel(Warehouse warehouse) {
        if (warehouse == null) {
            return "";
        }
        String city = valueOr(warehouse.getCity());
        if (!city.isBlank()) {
            return city + " / " + warehouse.getName();
        }
        return warehouse.getName();
    }

    private String valueOr(String value) {
        return value == null ? "" : value;
    }
}
