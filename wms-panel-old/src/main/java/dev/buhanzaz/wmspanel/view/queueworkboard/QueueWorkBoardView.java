package dev.buhanzaz.wmspanel.view.queueworkboard;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.dnd.DragSource;
import com.vaadin.flow.component.dnd.DropTarget;
import com.vaadin.flow.component.dnd.EffectAllowed;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.streams.UploadHandler;
import dev.buhanzaz.wmspanel.entity.BoardTask;
import dev.buhanzaz.wmspanel.entity.BoardTaskPhotoLink;
import dev.buhanzaz.wmspanel.entity.BoardTaskPhotoType;
import dev.buhanzaz.wmspanel.entity.BoardTaskStatus;
import dev.buhanzaz.wmspanel.entity.QueueEntry;
import dev.buhanzaz.wmspanel.entity.QueueEntryStatus;
import dev.buhanzaz.wmspanel.entity.QueueEntryType;
import dev.buhanzaz.wmspanel.entity.RentalItem;
import dev.buhanzaz.wmspanel.entity.RepairEstimateTaskPlan;
import dev.buhanzaz.wmspanel.entity.RepairProcess;
import dev.buhanzaz.wmspanel.entity.RepairProcessTaskKind;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.entity.WorkQueue;
import dev.buhanzaz.wmspanel.entity.WorkQueueKind;
import dev.buhanzaz.wmspanel.entity.WorkerClass;
import dev.buhanzaz.wmspanel.entity.WorkerGroup;
import dev.buhanzaz.wmspanel.entity.Worker;
import dev.buhanzaz.wmspanel.entity.RentalItemEventPhoto;
import dev.buhanzaz.wmspanel.service.QueueBoardService;
import dev.buhanzaz.wmspanel.service.RepairEstimateService;
import dev.buhanzaz.wmspanel.service.RepairProcessService;
import dev.buhanzaz.wmspanel.service.ViewStateService;
import dev.buhanzaz.wmspanel.service.QueueBoardService.AssignmentInfo;
import dev.buhanzaz.wmspanel.service.QueueBoardService.EntryCard;
import dev.buhanzaz.wmspanel.service.QueueBoardService.QueueColumn;
import dev.buhanzaz.wmspanel.service.QueueBoardService.QueueTaskStep;
import dev.buhanzaz.wmspanel.service.QueueBoardService.RouteChip;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.core.Messages;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Route(value = "queue-work-board", layout = MainView.class)
@ViewController(id = "QueueWorkBoard.view")
@ViewDescriptor(path = "queue-work-board-view.xml")
public class QueueWorkBoardView extends StandardView {

    @Autowired
    private QueueBoardService queueBoardService;
    @Autowired
    private RepairProcessService repairProcessService;
    @Autowired
    private RepairEstimateService repairEstimateService;

    @Autowired
    private Notifications notifications;
    @Autowired
    private MessageBundle messageBundle;

    @Autowired
    private Messages messages;

    @Autowired
    private ViewStateService viewStateService;

    @ViewComponent
    private VerticalLayout boardRoot;

    private final Checkbox showShadowField = new Checkbox("Показывать SHADOW", true);
    private final ComboBox<Warehouse> warehouseField = new ComboBox<>("Склад");
    private final HorizontalLayout board = new HorizontalLayout();
    private List<Warehouse> warehouses = List.of();

    @Subscribe
    public void onInit(final InitEvent event) {
        configureLayout();
        refreshBoard();
    }

    private void configureLayout() {
        boardRoot.setPadding(false);
        boardRoot.setSpacing(true);
        boardRoot.setSizeFull();

        warehouses = queueBoardService.loadActiveWarehouses();
        warehouseField.setItems(warehouses);
        warehouseField.setItemLabelGenerator(warehouse -> valueOr(warehouse.getCity(), warehouse.getName()));
        warehouseField.setWidth("220px");
        warehouseField.setClearButtonVisible(false);
        warehouseField.addValueChangeListener(event -> {
            viewStateService.setTaskBoardWarehouseId(event.getValue() == null ? null : event.getValue().getId());
            refreshBoard();
        });
        warehouseField.setValue(viewStateService.resolveWarehouse(warehouses, viewStateService.getTaskBoardWarehouseId(), null));

        Button addQueueButton = iconButton(VaadinIcon.PLUS, "Добавить очередь");
        addQueueButton.setText("Добавить очередь");
        addQueueButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        addQueueButton.addClickListener(event -> openQueueDialog(null));

        Button addTaskButton = iconButton(VaadinIcon.CLIPBOARD_TEXT, "Добавить задачу");
        addTaskButton.setText("Добавить задачу");
        addTaskButton.addClickListener(event -> openTaskDialog());

        Button showAllQueuesButton = iconButton(VaadinIcon.EYE, "Показать скрытые колонки");
        showAllQueuesButton.setText("Показать скрытые");
        showAllQueuesButton.addClickListener(event -> runAction(() -> {
            queueBoardService.showAllQueues(selectedWarehouse());
            refreshBoard();
            notifyInfo("Все очереди показаны");
        }));

        showShadowField.setValue(viewStateService.isTaskBoardShowShadow());
        showShadowField.addValueChangeListener(event -> {
            viewStateService.setTaskBoardShowShadow(Boolean.TRUE.equals(event.getValue()));
            refreshBoard();
        });

        HorizontalLayout toolbar = new HorizontalLayout(warehouseField, addQueueButton, addTaskButton, showShadowField, showAllQueuesButton);
        toolbar.setPadding(false);
        toolbar.setSpacing(true);
        toolbar.setAlignItems(FlexComponent.Alignment.CENTER);
        toolbar.setWidthFull();

        board.setPadding(false);
        board.setSpacing(true);
        board.setWidthFull();
        board.setHeightFull();
        board.getStyle()
                .set("overflow-x", "auto")
                .set("padding", "0 0 12px")
                .set("align-items", "stretch");

        boardRoot.add(toolbar, board);
        boardRoot.expand(board);
    }

    private void refreshBoard() {
        board.removeAll();
        Warehouse warehouse = selectedWarehouse();
        if (warehouse == null) {
            Div empty = new Div("Создайте активный склад в настройках, чтобы появилась доска задач.");
            empty.getStyle().set("color", "#64748b").set("padding", "24px");
            board.add(empty);
            return;
        }
        QueueBoardService.BoardState state = queueBoardService.getBoardState(warehouse, showShadowField.getValue());
        if (state.columns().isEmpty()) {
            Div empty = new Div("Очередей пока нет. Добавьте первую очередь для доски задач.");
            empty.getStyle().set("color", "#64748b").set("padding", "24px");
            board.add(empty);
            return;
        }
        state.columns().forEach(column -> board.add(createColumn(column)));
    }

    private Component createColumn(QueueColumn column) {
        WorkQueue queue = column.queue();
        VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);
        layout.setSpacing(false);
        layout.setWidth(Boolean.TRUE.equals(queue.getCollapsed()) ? "72px" : "360px");
        layout.setMinWidth(Boolean.TRUE.equals(queue.getCollapsed()) ? "72px" : "360px");
        layout.setHeightFull();
        layout.getStyle()
                .set("border", "1px solid #cbd5e1")
                .set("border-radius", "8px")
                .set("background", "#f8fafc")
                .set("flex-shrink", "0");

        if (Boolean.TRUE.equals(queue.getCollapsed())) {
            Button expand = iconButton(VaadinIcon.ANGLE_DOUBLE_RIGHT, "Развернуть");
            expand.addClickListener(event -> runAction(() -> {
                queueBoardService.setQueueCollapsed(queue.getId(), false);
                refreshBoard();
            }));
            Span title = new Span(queue.getName());
            title.getStyle()
                    .set("writing-mode", "vertical-rl")
                    .set("font-weight", "700")
                    .set("padding", "8px 0")
                    .set("max-height", "420px")
                    .set("overflow", "hidden");
            layout.setAlignItems(FlexComponent.Alignment.CENTER);
            layout.add(expand, title);
            return layout;
        }

        layout.add(createColumnHeader(column), createColumnActions(queue), createColumnGroupLine(column.workerGroups()));

        VerticalLayout cards = new VerticalLayout();
        cards.setPadding(true);
        cards.setSpacing(true);
        cards.setWidthFull();
        cards.getStyle().set("overflow-y", "auto");

        List<UUID> orderedIds = column.cards().stream()
                .map(card -> card.entry().getId())
                .toList();

        for (int i = 0; i < column.cards().size(); i++) {
            EntryCard card = column.cards().get(i);
            cards.add(createCard(card, queue.getId(), orderedIds, i));
        }

        Div endDrop = new Div();
        endDrop.setText(column.cards().isEmpty() ? "Перетащите задачу сюда" : "");
        endDrop.getStyle()
                .set("min-height", "28px")
                .set("border", "1px dashed #cbd5e1")
                .set("border-radius", "6px")
                .set("color", "#94a3b8")
                .set("font-size", "12px")
                .set("padding", "6px")
                .set("text-align", "center");
        DropTarget<Div> endTarget = DropTarget.create(endDrop);
        endTarget.addDropListener(event -> handleCardDrop(event.getDragData().orElse(null), queue.getId(), orderedIds, orderedIds.size()));
        cards.add(endDrop);

        layout.add(cards);
        layout.expand(cards);
        return layout;
    }

    private Component createColumnHeader(QueueColumn column) {
        WorkQueue queue = column.queue();
        H3 title = new H3(queue.getName());
        title.getStyle().set("margin", "0").set("font-size", "17px").set("line-height", "1.2");

        Span count = badge(column.cards().size() + " задач", "#e2e8f0", "#334155");
        Span sink = queue.getQueueKind() == WorkQueueKind.HOLDING
                ? badge(workQueueKindLabel(WorkQueueKind.HOLDING), "#fee2e2", "#991b1b")
                : queue.getQueueKind() == WorkQueueKind.MOVEMENT
                ? badge(workQueueKindLabel(WorkQueueKind.MOVEMENT), "#e0f2fe", "#0c4a6e")
                : new Span();

        HorizontalLayout header = new HorizontalLayout(title, count, sink);
        header.setPadding(true);
        header.setSpacing(true);
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.expand(title);
        header.setWidthFull();
        return header;
    }

    private Component createColumnActions(WorkQueue queue) {
        Button take = iconButton(VaadinIcon.PLAY, "Взять задачу");
        take.addClickListener(event -> openTakeTaskDialog(queue));

        Button complete = iconButton(VaadinIcon.CHECK, "Завершить задачу");
        complete.addClickListener(event -> runAction(() -> {
            queueBoardService.completeCurrentTask(queue.getId()).forEach(this::notifyInfo);
            notifyThresholdIfNeeded(queue);
            refreshBoard();
        }));

        Button left = iconButton(VaadinIcon.ARROW_LEFT, "Переместить левее");
        left.addClickListener(event -> runAction(() -> {
            queueBoardService.moveQueueLeft(queue.getId());
            refreshBoard();
        }));

        Button right = iconButton(VaadinIcon.ARROW_RIGHT, "Переместить правее");
        right.addClickListener(event -> runAction(() -> {
            queueBoardService.moveQueueRight(queue.getId());
            refreshBoard();
        }));

        Button collapse = iconButton(VaadinIcon.COMPRESS_SQUARE, "Свернуть колонку");
        collapse.addClickListener(event -> runAction(() -> {
            queueBoardService.setQueueCollapsed(queue.getId(), true);
            refreshBoard();
        }));

        Button hide = iconButton(VaadinIcon.EYE_SLASH, "Скрыть колонку");
        hide.addClickListener(event -> runAction(() -> {
            queueBoardService.setQueueHidden(queue.getId(), true);
            refreshBoard();
        }));

        Button settings = iconButton(VaadinIcon.COG, "Настройки очереди");
        settings.addClickListener(event -> openQueueDialog(queue));

        HorizontalLayout actions = new HorizontalLayout(take, complete, left, right, collapse, hide, settings);
        actions.setPadding(true);
        actions.setSpacing(false);
        actions.setWidthFull();
        actions.getStyle().set("border-top", "1px solid #e2e8f0").set("border-bottom", "1px solid #e2e8f0");
        return actions;
    }

    private Component createColumnGroupLine(List<String> workerGroups) {
        Div line = new Div();
        line.getStyle()
                .set("padding", "6px 12px")
                .set("font-size", "12px")
                .set("color", "#475569")
                .set("border-bottom", "1px solid #e2e8f0");
        line.setText(workerGroups.isEmpty() ? "Группы не привязаны" : "Группы: " + String.join(", ", workerGroups));
        return line;
    }

    private Component createCard(EntryCard card, UUID queueId, List<UUID> orderedIds, int targetIndex) {
        QueueEntry entry = card.entry();
        Div wrapper = new Div();
        wrapper.setWidthFull();
        wrapper.getStyle()
                .set("position", "relative")
                .set("background", cardBackground(card))
                .set("border", cardBorder(card))
                .set("border-radius", "8px")
                .set("padding", "10px")
                .set("box-sizing", "border-box")
                .set("cursor", entry.getStatus() == QueueEntryStatus.IN_PROGRESS ? "default" : "grab");

        wrapper.add(progressLine(card));

        QueueEntryType effectiveEntryType = card.effectiveEntryType();
        Span title = new Span(effectiveEntryType == QueueEntryType.SHADOW
                ? "[[ " + valueOr(entry.getTask().getUnitNumber(), entry.getTask().getTitle()) + " ]]"
                : entry.getTask().getTitle());
        title.getStyle().set("font-weight", "700").set("font-size", "15px");

        HorizontalLayout titleRow = new HorizontalLayout(title,
                badge(queueEntryTypeLabel(effectiveEntryType), typeColor(effectiveEntryType), "#0f172a"),
                badge(queueEntryStatusLabel(entry.getStatus()), statusColor(entry.getStatus()), "#0f172a"));
        titleRow.setAlignItems(FlexComponent.Alignment.CENTER);
        titleRow.setSpacing(true);
        titleRow.setWidthFull();
        titleRow.expand(title);

        Span unit = new Span("Номер: " + valueOr(entry.getTask().getUnitNumber(), "-"));
        unit.getStyle().set("font-size", "12px").set("color", "#475569");

        Span text = new Span(valueOr(entry.getTaskText(), valueOr(entry.getTask().getDescription(), "")));
        text.getStyle().set("display", "block").set("font-size", "13px").set("color", "#334155").set("margin-top", "8px");

        wrapper.add(titleRow, unit, text, routeLine(card.route()), avatarLine(card.assignments()), timerLine(card));
        if (entry.getTask().getRepairProcess() != null) {
            wrapper.add(processSummaryLine(entry.getTask()), taskMetaLine(entry.getTask()), processCommentLine(entry.getTask()));
        }

        HorizontalLayout cardActions = new HorizontalLayout();
        cardActions.setSpacing(false);
        if (entry.getStatus() == QueueEntryStatus.IN_PROGRESS) {
            Button pause = iconButton(VaadinIcon.PAUSE, "Пауза");
            pause.addClickListener(event -> runAction(() -> {
                queueBoardService.pauseTask(entry.getId(), "Пауза с доски");
                refreshBoard();
            }));
            cardActions.add(pause);
        }
        if (entry.getStatus() == QueueEntryStatus.PAUSED) {
            Button resume = iconButton(VaadinIcon.PLAY, "Возобновить");
            resume.addClickListener(event -> runAction(() -> {
                queueBoardService.resumeTask(entry.getId());
                refreshBoard();
            }));
            cardActions.add(resume);
        }
        Button details = iconButton(VaadinIcon.INFO_CIRCLE, "Подробности");
        details.addClickListener(event -> openDetailsDialog(card));
        cardActions.add(details);
        if (entry.getTask().getRepairProcess() != null) {
            Button photo = iconButton(VaadinIcon.CAMERA, "Фото выполнения");
            photo.addClickListener(event -> openTaskPhotoDialog(entry));
            cardActions.add(photo);
        }
        if (entry.getTask().getRepairProcess() != null) {
            Button dossier = iconButton(VaadinIcon.BOOK, messageBundle.getMessage("queueBoard.button.processDossier"));
            dossier.addClickListener(event -> openProcessDossier(entry.getTask().getRepairProcess()));
            cardActions.add(dossier);
        }
        wrapper.add(cardActions);

        if (entry.getStatus() != QueueEntryStatus.IN_PROGRESS) {
            DragSource<Div> dragSource = DragSource.create(wrapper);
            dragSource.setEffectAllowed(EffectAllowed.MOVE);
            dragSource.setDragData(entry.getId().toString());
        }
        DropTarget<Div> dropTarget = DropTarget.create(wrapper);
        dropTarget.addDropListener(event -> handleCardDrop(event.getDragData().orElse(null), queueId, orderedIds, targetIndex));

        return wrapper;
    }

    private Component progressLine(EntryCard card) {
        Div track = new Div();
        track.getStyle()
                .set("height", "6px")
                .set("background", "#e2e8f0")
                .set("border-radius", "999px")
                .set("margin", "0 0 8px");
        Div fill = new Div();
        if (card.plannedMinutes() <= 0) {
            fill.getStyle()
                    .set("height", "6px")
                    .set("width", "100%")
                    .set("background", "#cbd5e1")
                    .set("border-radius", "999px");
            track.getElement().setProperty("title", "Без планового времени");
            track.add(fill);
            return track;
        }
        long plannedSeconds = Math.max(1L, card.plannedMinutes() * 60L);
        long remainingSeconds = Math.max(0L, plannedSeconds - card.activeSeconds());
        double ratio = remainingSeconds / (double) plannedSeconds;
        long remainingMinutes = (remainingSeconds + 59L) / 60L;
        fill.getStyle()
                .set("height", "6px")
                .set("width", Math.round(ratio * 100) + "%")
                .set("background", ratio >= 0.6 ? "#22c55e" : ratio >= 0.3 ? "#f59e0b" : "#dc2626")
                .set("border-radius", "999px");
        track.getElement().setProperty("title", remainingMinutes + " из " + card.plannedMinutes() + " мин");
        track.add(fill);
        return track;
    }

    private Component routeLine(List<RouteChip> chips) {
        HorizontalLayout route = new HorizontalLayout();
        route.setSpacing(true);
        route.setPadding(false);
        route.getStyle().set("flex-wrap", "wrap").set("margin-top", "8px");
        chips.forEach(chip -> route.add(badge(chip.queueName(), chip.current() ? "#bbf7d0" : "#e0f2fe", "#0f172a")));
        return route;
    }

    private Component avatarLine(List<AssignmentInfo> assignments) {
        HorizontalLayout line = new HorizontalLayout();
        line.setSpacing(true);
        line.setAlignItems(FlexComponent.Alignment.CENTER);
        line.getStyle().set("margin-top", "8px");
        if (assignments.isEmpty()) {
            Span empty = new Span("Рабочие не назначены");
            empty.getStyle().set("font-size", "12px").set("color", "#64748b");
            line.add(empty);
            return line;
        }
        for (AssignmentInfo assignment : assignments) {
            Span avatar = new Span(initials(assignment.workerName()));
            avatar.getStyle()
                    .set("display", "inline-flex")
                    .set("align-items", "center")
                    .set("justify-content", "center")
                    .set("width", "28px")
                    .set("height", "28px")
                    .set("border-radius", "50%")
                    .set("background", "#dbeafe")
                    .set("font-size", "11px")
                    .set("font-weight", "700");
            Span group = new Span(valueOr(assignment.groupName(), ""));
            group.getStyle().set("font-size", "12px").set("color", "#475569");
            line.add(avatar, group);
        }
        return line;
    }

    private Component timerLine(EntryCard card) {
        long plannedSeconds = Math.max(0, card.plannedMinutes() * 60L);
        long remaining = plannedSeconds - card.activeSeconds();
        String state = plannedSeconds == 0
                ? "без плана"
                : remaining < 0 ? "просрочено" : remaining < plannedSeconds * 0.2 ? "почти просрочено" : "в норме";
        Span line = new Span("План: " + formatSeconds(plannedSeconds)
                + " · Активно: " + formatSeconds(card.activeSeconds())
                + " · " + state);
        line.getStyle()
                .set("display", "block")
                .set("font-size", "12px")
                .set("color", remaining < 0 ? "#b91c1c" : "#475569")
                .set("margin-top", "8px");
        return line;
    }

    private Component processSummaryLine(BoardTask task) {
        Span line = new Span("Процесс: " + repairProcessService.buildProcessSummary(task.getRepairProcess()));
        line.getStyle()
                .set("display", "block")
                .set("font-size", "12px")
                .set("color", "#0f172a")
                .set("margin-top", "8px");
        return line;
    }

    private Component taskMetaLine(BoardTask task) {
        Span line = new Span("Категория: " + repairTaskKindLabel(task.getTaskKind())
                + " · Статус: " + boardTaskStatusLabel(task.getStatus()));
        line.getStyle()
                .set("display", "block")
                .set("font-size", "12px")
                .set("color", "#475569")
                .set("margin-top", "4px");
        return line;
    }

    private Component processCommentLine(BoardTask task) {
        RepairProcess process = task.getRepairProcess();
        String comment = process == null ? null : process.getComment();
        if (comment == null || comment.isBlank()) {
            return new Span();
        }
        Span line = new Span("Комментарий процесса: " + comment);
        line.getStyle()
                .set("display", "block")
                .set("font-size", "12px")
                .set("color", "#334155")
                .set("margin-top", "4px");
        return line;
    }

    private Component detailLine(String label, String value) {
        HorizontalLayout row = new HorizontalLayout();
        row.setPadding(false);
        row.setSpacing(true);
        row.setWidthFull();
        Span labelSpan = new Span(label + ":");
        labelSpan.getStyle().set("font-weight", "700").set("color", "#0f172a");
        Span valueSpan = new Span(valueOr(value, "-"));
        valueSpan.getStyle().set("color", "#334155");
        row.add(labelSpan, valueSpan);
        row.expand(valueSpan);
        return row;
    }

    private String repairTaskKindLabel(RepairProcessTaskKind kind) {
        if (kind == null) {
            return "-";
        }
        return localizedEnum("dev.buhanzaz.wmspanel.entity/RepairProcessTaskKind." + kind.name(), kind.name());
    }

    private String boardTaskStatusLabel(BoardTaskStatus status) {
        if (status == null) {
            return "-";
        }
        return localizedEnum("dev.buhanzaz.wmspanel.entity/BoardTaskStatus." + status.name(), status.name());
    }

    private String queueEntryTypeLabel(QueueEntryType type) {
        if (type == null) {
            return "-";
        }
        return localizedEnum("dev.buhanzaz.wmspanel.entity/QueueEntryType." + type.name(), type.name());
    }

    private String queueEntryStatusLabel(QueueEntryStatus status) {
        if (status == null) {
            return "-";
        }
        return localizedEnum("dev.buhanzaz.wmspanel.entity/QueueEntryStatus." + status.name(), status.name());
    }

    private String localizedEnum(String key, String fallback) {
        String value = messages.getMessage(key);
        if (value == null || value.isBlank() || value.equals(key)) {
            return fallback;
        }
        return value;
    }

    private String workQueueKindLabel(WorkQueueKind kind) {
        if (kind == null) {
            return "";
        }
        return localizedEnum("dev.buhanzaz.wmspanel.entity/WorkQueueKind." + kind.name(), kind.name());
    }

    private String photoSummary(List<BoardTaskPhotoLink> links, UUID boardTaskId) {
        if (links == null || links.isEmpty() || boardTaskId == null) {
            return "-";
        }
        long before = 0;
        long work = 0;
        long after = 0;
        long acceptance = 0;
        for (BoardTaskPhotoLink link : links) {
            if (link == null || link.getBoardTask() == null || !Objects.equals(link.getBoardTask().getId(), boardTaskId)) {
                continue;
            }
            BoardTaskPhotoType type = link.getPhotoType();
            if (type == BoardTaskPhotoType.BEFORE) {
                before++;
            } else if (type == BoardTaskPhotoType.AFTER) {
                after++;
            } else if (type == BoardTaskPhotoType.ACCEPTANCE) {
                acceptance++;
            } else {
                work++;
            }
        }
        List<String> parts = new ArrayList<>();
        if (before > 0) {
            parts.add("до: " + before);
        }
        if (work > 0) {
            parts.add("работы: " + work);
        }
        if (after > 0) {
            parts.add("после: " + after);
        }
        if (acceptance > 0) {
            parts.add("приемка: " + acceptance);
        }
        return parts.isEmpty() ? "-" : String.join(", ", parts);
    }

    private void openProcessDossier(RepairProcess process) {
        if (process == null || process.getId() == null) {
            return;
        }
        getUI().ifPresent(ui -> ui.navigate("after-repair?processId=" + process.getId()));
    }

    private void openTaskPhotoDialog(QueueEntry entry) {
        if (entry == null || entry.getId() == null || entry.getTask() == null || entry.getTask().getId() == null) {
            return;
        }
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Фото выполнения подзадачи");
        dialog.setWidth("58em");

        BoardTask task = entry.getTask();
        List<BoardTaskPhotoLink> existingLinks = repairProcessService.loadTaskPhotoLinks(task.getId());
        VerticalLayout content = new VerticalLayout();
        content.setPadding(false);
        content.setSpacing(true);
        content.setWidthFull();
        content.add(
                detailLine("Подзадача", task.getTitle()),
                detailLine("Очередь", entry.getQueue() == null ? "-" : entry.getQueue().getName()),
                detailLine("Уже прикреплено", photoSummary(existingLinks, task.getId()))
        );
        content.add(taskPhotoStrip(existingLinks));

        ComboBox<BoardTaskPhotoType> photoTypeField = new ComboBox<>("Тип фото");
        photoTypeField.setItems(BoardTaskPhotoType.values());
        photoTypeField.setItemLabelGenerator(this::photoTypeLabel);
        photoTypeField.setValue(BoardTaskPhotoType.WORK);
        photoTypeField.setWidth("18rem");

        TextArea commentField = new TextArea("Комментарий");
        commentField.setWidthFull();
        commentField.setPlaceholder("Например: заменили замок, исправили проводку");

        List<PendingTaskPhoto> pendingPhotos = new ArrayList<>();
        Div pendingInfo = new Div("Новых файлов: 0");
        pendingInfo.getStyle().set("font-size", "12px").set("color", "#475569");

        Upload upload = new Upload(UploadHandler.inMemory((metadata, bytes) -> {
            pendingPhotos.add(new PendingTaskPhoto(metadata.fileName(), metadata.contentType(), bytes));
            pendingInfo.setText("Новых файлов: " + pendingPhotos.size());
        }));
        upload.setAcceptedFileTypes("image/*");
        upload.setDropAllowed(true);
        upload.setMaxFiles(20);
        upload.setWidthFull();
        upload.addFileRemovedListener(event -> {
            removeNewestPendingTaskPhotoByFileName(pendingPhotos, event.getFileName());
            pendingInfo.setText("Новых файлов: " + pendingPhotos.size());
        });

        Div hint = new Div("Фото сохранятся в историю объекта и будут видны в разделе \"После ремонта\".");
        hint.getStyle().set("font-size", "12px").set("color", "#64748b");
        content.add(photoTypeField, commentField, pendingInfo, upload, hint);

        Button cancel = new Button("Отмена", event -> dialog.close());
        Button save = new Button("Сохранить фото", event -> runAction(() -> {
            if (pendingPhotos.isEmpty()) {
                throw new IllegalArgumentException("Сначала добавьте хотя бы одно фото");
            }
            int saved = repairProcessService.attachTaskPhotos(
                    task.getId(),
                    entry.getId(),
                    photoTypeField.getValue(),
                    commentField.getValue(),
                    pendingPhotos.stream()
                            .map(photo -> new RepairProcessService.RepairTaskUploadedPhoto(
                                    photo.fileName(),
                                    photo.contentType(),
                                    new ByteArrayInputStream(photo.bytes())))
                            .toList());
            notifyInfo("Прикреплено фото: " + saved);
            dialog.close();
            refreshBoard();
        }));
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        dialog.add(content);
        dialog.getFooter().add(cancel, save);
        dialog.open();
    }

    private void openQueueDialog(WorkQueue queue) {
        boolean edit = queue != null;
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(edit ? "Настройки очереди" : "Добавить очередь");
        dialog.setWidth("52em");

        TextField nameField = new TextField("Название очереди");
        nameField.setWidthFull();
        nameField.setValue(edit ? valueOr(queue.getName(), "") : "");

        TextArea descriptionField = new TextArea("Описание");
        descriptionField.setWidthFull();
        descriptionField.setValue(edit ? valueOr(queue.getDescription(), "") : "");

        ComboBox<WorkQueueKind> kindField = new ComboBox<>("Тип очереди");
        kindField.setItems(WorkQueueKind.MOVEMENT, WorkQueueKind.REPAIR, WorkQueueKind.HOLDING);
        kindField.setItemLabelGenerator(this::workQueueKindLabel);
        kindField.setWidthFull();
        kindField.setValue(edit && queue.getQueueKind() != null ? queue.getQueueKind() : WorkQueueKind.REPAIR);

        IntegerField holdingPeriodField = new IntegerField("Период накопления, мин.");
        holdingPeriodField.setMin(1);
        holdingPeriodField.setWidthFull();
        holdingPeriodField.setValue(edit ? queue.getHoldingPeriodMinutes() : null);

        IntegerField thresholdField = new IntegerField("Порог уведомления");
        thresholdField.setMin(1);
        thresholdField.setWidthFull();
        thresholdField.setValue(edit ? queue.getNotificationThreshold() : null);

        Checkbox notifyField = new Checkbox("Уведомлять при достижении порога");
        notifyField.setValue(edit && Boolean.TRUE.equals(queue.getNotifyWhenThresholdReached()));
        notifyField.setWidthFull();

        kindField.addValueChangeListener(event -> {
            boolean holding = event.getValue() == WorkQueueKind.HOLDING;
            holdingPeriodField.setVisible(holding);
            thresholdField.setVisible(holding);
            notifyField.setVisible(holding);
            if (!holding) {
                holdingPeriodField.clear();
                thresholdField.clear();
                notifyField.setValue(false);
            }
        });
        boolean holding = kindField.getValue() == WorkQueueKind.HOLDING;
        holdingPeriodField.setVisible(holding);
        thresholdField.setVisible(holding);
        notifyField.setVisible(holding);

        VerticalLayout bindingsLayout = new VerticalLayout();
        bindingsLayout.setPadding(false);
        bindingsLayout.setSpacing(true);
        bindingsLayout.setWidthFull();
        List<QueueBindingOption> bindingOptions = buildBindingOptions(queue);
        bindingOptions.forEach(option -> bindingsLayout.add(option.row()));

        Button saveButton = new Button(edit ? "Сохранить" : "Добавить", event -> runAction(() -> {
            if (edit) {
                queueBoardService.updateQueue(queue, nameField.getValue(), descriptionField.getValue(),
                        kindField.getValue(), holdingPeriodField.getValue(), thresholdField.getValue(), notifyField.getValue(),
                        bindingOptions.stream()
                                .filter(QueueBindingOption::selected)
                                .map(option -> new QueueBoardService.QueueWorkerClassBinding(option.workerClass(), option.stopTaskOnTake()))
                                .toList());
                notifyInfo("Очередь обновлена");
            } else {
                queueBoardService.createQueue(selectedWarehouse(), nameField.getValue(), descriptionField.getValue(),
                        kindField.getValue(), holdingPeriodField.getValue(), thresholdField.getValue(), notifyField.getValue(),
                        bindingOptions.stream()
                                .filter(QueueBindingOption::selected)
                                .map(option -> new QueueBoardService.QueueWorkerClassBinding(option.workerClass(), option.stopTaskOnTake()))
                                .toList());
                notifyInfo("Очередь добавлена: " + nameField.getValue());
            }
            dialog.close();
            refreshBoard();
        }));
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancelButton = new Button("Отмена", event -> dialog.close());

        VerticalLayout content = new VerticalLayout(nameField, descriptionField, kindField, holdingPeriodField, thresholdField, notifyField, bindingsLayout);
        content.setPadding(false);
        dialog.add(content);
        dialog.getFooter().add(cancelButton, saveButton);
        dialog.open();
    }

    private void openTaskDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Добавить задачу");
        dialog.setWidth("64em");

        ComboBox<RentalItem> rentalItemField = new ComboBox<>("Номер бытовки");
        List<RentalItem> rentalItems = selectedWarehouse() == null
                ? List.of()
                : queueBoardService.loadRentalItems(selectedWarehouse());
        rentalItemField.setItems(rentalItems);
        rentalItemField.setItemLabelGenerator(RentalItem::getNumber);
        rentalItemField.setRequired(true);
        rentalItemField.setPlaceholder("Начните вводить номер");
        rentalItemField.setWidthFull();
        TextArea descriptionField = new TextArea("Общее описание задачи");
        descriptionField.setWidthFull();
        DateTimePicker deadlineField = new DateTimePicker("Срок выполнения");

        CheckboxGroup<WorkQueue> routeField = new CheckboxGroup<>("Очереди выполнения");
        List<WorkQueue> queues = selectedWarehouse() == null
                ? List.of()
                : queueBoardService.loadQueues(selectedWarehouse());
        routeField.setItems(queues);
        routeField.setItemLabelGenerator(WorkQueue::getName);

        VerticalLayout stepRows = new VerticalLayout();
        stepRows.setPadding(false);
        stepRows.setSpacing(true);
        List<TaskStepRow> rows = new ArrayList<>();

        routeField.addValueChangeListener(event -> syncTaskStepRows(stepRows, rows, event.getValue()));

        Button saveButton = new Button("Добавить", event -> runAction(() -> {
            List<QueueTaskStep> steps = rows.stream()
                    .map(row -> new QueueTaskStep(row.queue, row.taskTextField.getValue(), row.plannedMinutesField.getValue()))
                    .toList();
            OffsetDateTime deadline = deadlineField.getValue() == null
                    ? null
                    : deadlineField.getValue().atZone(ZoneId.systemDefault()).toOffsetDateTime();
            queueBoardService.createTask(new QueueBoardService.CreateTaskCommand(
                    rentalItemField.getValue(),
                    descriptionField.getValue(),
                    deadline,
                    steps,
                    null,
                    null));
            notifyInfo("Задача добавлена: " + rentalItemField.getValue().getNumber());
            dialog.close();
            refreshBoard();
        }));
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancelButton = new Button("Отмена", event -> dialog.close());

        VerticalLayout content = new VerticalLayout(rentalItemField, descriptionField, deadlineField, routeField, stepRows);
        content.setPadding(false);
        content.setSpacing(true);
        dialog.add(content);
        dialog.getFooter().add(cancelButton, saveButton);
        dialog.open();
    }

    private void syncTaskStepRows(VerticalLayout container, List<TaskStepRow> rows, Set<WorkQueue> selectedQueues) {
        List<WorkQueue> selected = selectedQueues.stream()
                .sorted(Comparator.comparing(queue -> queue.getSortOrder() == null ? 0 : queue.getSortOrder()))
                .toList();
        rows.removeIf(row -> selected.stream().noneMatch(queue -> Objects.equals(queue.getId(), row.queue.getId())));
        for (WorkQueue queue : selected) {
            if (rows.stream().noneMatch(row -> Objects.equals(row.queue.getId(), queue.getId()))) {
                rows.add(new TaskStepRow(queue));
            }
        }
        renderTaskStepRows(container, rows);
    }

    private void renderTaskStepRows(VerticalLayout container, List<TaskStepRow> rows) {
        container.removeAll();
        for (int i = 0; i < rows.size(); i++) {
            TaskStepRow row = rows.get(i);
            Span index = badge(String.valueOf(i + 1), "#dcfce7", "#166534");
            TextArea text = row.taskTextField;
            ComboBox<Integer> minutes = row.plannedMinutesField;
            Button up = iconButton(VaadinIcon.ARROW_UP, "Выше");
            int currentIndex = i;
            up.setEnabled(i > 0);
            up.addClickListener(event -> {
                TaskStepRow moved = rows.remove(currentIndex);
                rows.add(currentIndex - 1, moved);
                renderTaskStepRows(container, rows);
            });
            Button down = iconButton(VaadinIcon.ARROW_DOWN, "Ниже");
            down.setEnabled(i < rows.size() - 1);
            down.addClickListener(event -> {
                TaskStepRow moved = rows.remove(currentIndex);
                rows.add(currentIndex + 1, moved);
                renderTaskStepRows(container, rows);
            });
            H4 title = new H4(row.queue.getName());
            title.getStyle().set("margin", "0").set("font-size", "14px");
            HorizontalLayout header = new HorizontalLayout(index, title, up, down);
            header.setAlignItems(FlexComponent.Alignment.CENTER);
            header.expand(title);
            VerticalLayout card = new VerticalLayout(header, text, minutes);
            card.setPadding(true);
            card.setSpacing(true);
            card.getStyle().set("border", "1px solid #e2e8f0").set("border-radius", "8px");
            container.add(card);
        }
    }

    private void openTakeTaskDialog(WorkQueue queue) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Взять задачу");
        dialog.setWidth("32em");

        ComboBox<WorkerGroup> groupField = new ComboBox<>("Подгруппа / команда");
        List<WorkerGroup> groups = queueBoardService.loadWorkerGroupsForQueue(queue);
        if (groups.isEmpty()) {
            groups = queueBoardService.loadActiveWorkerGroups();
        }
        groupField.setItems(groups);
        groupField.setItemLabelGenerator(WorkerGroup::getName);
        groupField.setWidthFull();
        groupField.setRequired(true);
        if (!groups.isEmpty()) {
            groupField.setValue(groups.get(0));
        }

        ComboBox<Worker> userField = new ComboBox<>("Конкретный рабочий");
        userField.setItemLabelGenerator(Worker::getDisplayName);
        userField.setWidthFull();
        Runnable reloadWorkers = () -> {
            WorkerGroup selectedGroup = groupField.getValue();
            List<Worker> workers = selectedGroup == null
                    ? queueBoardService.loadActiveWorkers(queue.getWarehouse())
                    : queueBoardService.loadActiveWorkersForGroup(selectedGroup);
            userField.setItems(workers);
            Worker selectedWorker = userField.getValue();
            if (selectedWorker != null && workers.stream().noneMatch(worker -> Objects.equals(worker.getId(), selectedWorker.getId()))) {
                userField.clear();
            }
        };
        groupField.addValueChangeListener(event -> reloadWorkers.run());
        reloadWorkers.run();

        Button takeButton = new Button("Взять", event -> runAction(() -> {
            String message = queueBoardService.takeNextTask(queue.getId(), groupField.getValue(), userField.getValue());
            notifyInfo(message);
            dialog.close();
            refreshBoard();
        }));
        takeButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cancelButton = new Button("Отмена", event -> dialog.close());

        VerticalLayout content = new VerticalLayout(groupField, userField);
        content.setPadding(false);
        dialog.add(content);
        dialog.getFooter().add(cancelButton, takeButton);
        dialog.open();
    }

    private void openDetailsDialog(EntryCard card) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Задача");
        dialog.setWidth("56em");
        QueueEntry entry = card.entry();
        BoardTask task = entry.getTask();
        VerticalLayout content = new VerticalLayout(
                detailLine("Название", task.getTitle()),
                detailLine("Номер", valueOr(task.getUnitNumber(), "-")),
                detailLine("Очередь", entry.getQueue().getName()),
                detailLine("Статус", queueEntryTypeLabel(card.effectiveEntryType()) + " / " + queueEntryStatusLabel(entry.getStatus())),
                detailLine("Активное время", formatSeconds(card.activeSeconds())),
                routeLine(card.route()));
        content.setPadding(false);
        content.setSpacing(true);

        if (task.getRepairProcess() != null) {
            RepairProcessService.RepairProcessDossierData dossier = repairProcessService.loadProcessDossier(task.getRepairProcess().getId());
            List<RepairEstimateTaskPlan> linkedPlans = dossier == null ? List.of() : dossier.plans().stream()
                    .filter(plan -> plan.getGeneratedBoardTask() != null && Objects.equals(plan.getGeneratedBoardTask().getId(), task.getId()))
                    .toList();
            content.add(detailLine("Процесс", repairProcessService.buildProcessSummary(task.getRepairProcess())));
            content.add(detailLine("Категория", repairTaskKindLabel(task.getTaskKind())));
            content.add(detailLine("Комментарий", valueOr(task.getDescription(), valueOr(task.getRepairProcess().getComment(), "-"))));
            content.add(detailLine("Планы по задаче", String.valueOf(linkedPlans.size())));
            content.add(detailLine("Фото задачи", photoSummary(dossier == null ? List.of() : dossier.photoLinks(), task.getId())));
            content.add(editableProcessRoute(task));
        }

        dialog.add(content);
        Button photoButton = new Button("Фото выполнения", event -> {
            dialog.close();
            openTaskPhotoDialog(entry);
        });
        photoButton.setEnabled(task.getRepairProcess() != null);
        Button dossierButton = new Button(messageBundle.getMessage("queueBoard.button.processDossier"), event -> {
            dialog.close();
            openProcessDossier(task.getRepairProcess());
        });
        dossierButton.setEnabled(task.getRepairProcess() != null);
        dialog.getFooter().add(new Button("Закрыть", event -> dialog.close()), photoButton, dossierButton);
        dialog.open();
    }

    private Component taskPhotoStrip(List<BoardTaskPhotoLink> links) {
        if (links == null || links.isEmpty()) {
            Div empty = new Div("Фото по этой подзадаче пока нет.");
            empty.getStyle().set("font-size", "12px").set("color", "#64748b");
            return empty;
        }
        HorizontalLayout row = new HorizontalLayout();
        row.setPadding(false);
        row.setSpacing(true);
        row.getStyle().set("flex-wrap", "wrap");
        for (BoardTaskPhotoLink link : links) {
            if (link == null || link.getPhoto() == null || link.getPhoto().getId() == null) {
                continue;
            }
            RentalItemEventPhoto photo = link.getPhoto();
            VerticalLayout item = new VerticalLayout();
            item.setPadding(false);
            item.setSpacing(false);
            item.getStyle().set("width", "156px");
            Image image = new Image(repairEstimateService.mediaUrl(photo.getId(), RepairEstimateService.PhotoVariant.THUMB), valueOr(photo.getOriginalFileName(), "Фото"));
            image.setWidth("156px");
            image.setHeight("108px");
            image.getStyle()
                    .set("object-fit", "contain")
                    .set("border", "1px solid #cbd5e1")
                    .set("border-radius", "6px")
                    .set("background", "#f8fafc");
            image.addClickListener(event -> getUI().ifPresent(ui -> ui.getPage().open(repairEstimateService.mediaUrl(photo.getId(), RepairEstimateService.PhotoVariant.ORIGINAL))));
            Div caption = new Div(photoTypeLabel(link.getPhotoType()) + " · " + valueOr(photo.getOriginalFileName(), "Фото"));
            caption.getStyle().set("font-size", "12px").set("color", "#334155");
            item.add(image, caption);
            row.add(item);
        }
        return row;
    }

    private String photoTypeLabel(BoardTaskPhotoType type) {
        if (type == null) {
            return "Фото";
        }
        return switch (type) {
            case BEFORE -> "До";
            case WORK -> "Работы";
            case AFTER -> "После";
            case ACCEPTANCE -> "Приемка";
        };
    }

    private void removeNewestPendingTaskPhotoByFileName(List<PendingTaskPhoto> pendingPhotos, String fileName) {
        if (pendingPhotos == null || pendingPhotos.isEmpty() || fileName == null) {
            return;
        }
        for (int index = pendingPhotos.size() - 1; index >= 0; index--) {
            PendingTaskPhoto photo = pendingPhotos.get(index);
            if (Objects.equals(fileName, photo.fileName())) {
                pendingPhotos.remove(index);
                return;
            }
        }
    }

    private Component editableProcessRoute(BoardTask task) {
        VerticalLayout wrapper = new VerticalLayout();
        wrapper.setPadding(false);
        wrapper.setSpacing(true);
        wrapper.setWidthFull();

        H4 title = new H4("Путь объекта по очередям");
        title.getStyle().set("margin", "12px 0 0");
        Div hint = new Div();
        hint.setText("Перетащите карточки мышкой. Порядок сохранится только после нажатия кнопки.");
        hint.getStyle().set("color", "#64748b").set("font-size", "12px");

        List<QueueEntry> routeEntries = new ArrayList<>(queueBoardService.loadProcessRouteEntries(task.getRepairProcess().getId()));
        HorizontalLayout cards = new HorizontalLayout();
        cards.setPadding(false);
        cards.setSpacing(true);
        cards.setWidthFull();
        cards.setAlignItems(FlexComponent.Alignment.CENTER);
        cards.getStyle()
                .set("border", "1px solid #d7dde8")
                .set("border-radius", "8px")
                .set("padding", "10px")
                .set("background", "#f8fafc")
                .set("overflow-x", "auto")
                .set("overflow-y", "hidden")
                .set("min-height", "96px");
        renderProcessRouteCards(cards, routeEntries);

        Button save = new Button("Сохранить маршрут", event -> runAction(() -> {
            queueBoardService.reorderProcessRoute(task.getRepairProcess().getId(), routeEntries.stream()
                    .map(QueueEntry::getId)
                    .toList());
            notifyInfo("Маршрут объекта сохранен");
            refreshBoard();
            routeEntries.clear();
            routeEntries.addAll(queueBoardService.loadProcessRouteEntries(task.getRepairProcess().getId()));
            renderProcessRouteCards(cards, routeEntries);
        }));
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        wrapper.add(title, hint, cards, save);
        return wrapper;
    }

    private void renderProcessRouteCards(HorizontalLayout cards, List<QueueEntry> routeEntries) {
        cards.removeAll();
        if (routeEntries.isEmpty()) {
            Div empty = new Div();
            empty.setText("Маршрут не найден");
            empty.getStyle().set("color", "#64748b");
            cards.add(empty);
            return;
        }
        for (int index = 0; index < routeEntries.size(); index++) {
            QueueEntry routeEntry = routeEntries.get(index);
            cards.add(processRouteCard(routeEntry, routeEntries, cards));
            if (index < routeEntries.size() - 1) {
                Span arrow = new Span("→");
                arrow.getStyle()
                        .set("display", "inline-flex")
                        .set("align-items", "center")
                        .set("justify-content", "center")
                        .set("text-align", "center")
                        .set("font-size", "24px")
                        .set("line-height", "1")
                        .set("color", "#2563eb")
                        .set("width", "32px")
                        .set("flex", "0 0 32px");
                cards.add(arrow);
            }
        }
    }

    private Div processRouteCard(QueueEntry routeEntry, List<QueueEntry> routeEntries, HorizontalLayout cards) {
        Div card = new Div();
        card.getStyle()
                .set("border", "1px solid #cbd5e1")
                .set("border-radius", "8px")
                .set("background", "#ffffff")
                .set("box-shadow", "0 1px 2px rgba(15, 23, 42, 0.08)")
                .set("padding", "10px")
                .set("width", "190px")
                .set("min-width", "190px")
                .set("cursor", routeEntry.getStatus() == QueueEntryStatus.IN_PROGRESS ? "default" : "grab");
        BoardTask routeTask = routeEntry.getTask();
        H4 queueTitle = new H4(valueOr(routeEntry.getQueue() == null ? null : routeEntry.getQueue().getName(), "-"));
        queueTitle.getStyle().set("margin", "0").set("font-size", "15px");
        Div meta = new Div();
        meta.setText(valueOr(routeTask == null ? null : routeTask.getTitle(), "-")
                + " · " + queueEntryStatusLabel(routeEntry.getStatus())
                + " · этап " + (routeEntry.getRouteIndex() == null ? 0 : routeEntry.getRouteIndex()));
        meta.getStyle().set("color", "#64748b").set("font-size", "12px");
        card.add(queueTitle, meta);

        if (routeEntry.getStatus() != QueueEntryStatus.IN_PROGRESS) {
            DragSource<Div> dragSource = DragSource.create(card);
            dragSource.setEffectAllowed(EffectAllowed.MOVE);
            dragSource.setDragData(routeEntry.getId().toString());
        }
        DropTarget<Div> dropTarget = DropTarget.create(card);
        dropTarget.addDropListener(event -> handleProcessRouteDrop(event.getDragData().orElse(null), routeEntry, routeEntries, cards));
        return card;
    }

    private void handleProcessRouteDrop(Object dragData, QueueEntry target, List<QueueEntry> routeEntries, HorizontalLayout cards) {
        if (dragData == null || target == null) {
            return;
        }
        UUID draggedId;
        try {
            draggedId = UUID.fromString(dragData.toString());
        } catch (IllegalArgumentException ignored) {
            return;
        }
        QueueEntry dragged = routeEntries.stream()
                .filter(entry -> Objects.equals(entry.getId(), draggedId))
                .findFirst()
                .orElse(null);
        if (dragged == null || dragged == target || dragged.getStatus() == QueueEntryStatus.IN_PROGRESS) {
            return;
        }
        routeEntries.remove(dragged);
        int targetIndex = routeEntries.indexOf(target);
        routeEntries.add(Math.max(0, targetIndex), dragged);
        renderProcessRouteCards(cards, routeEntries);
    }

    private void handleCardDrop(Object dragData, UUID queueId, List<UUID> orderedIds, int targetIndex) {
        if (dragData == null) {
            return;
        }
        UUID draggedId;
        try {
            draggedId = UUID.fromString(dragData.toString());
        } catch (IllegalArgumentException ignored) {
            return;
        }
        runAction(() -> {
            if (orderedIds.contains(draggedId)) {
                List<UUID> updated = new ArrayList<>(orderedIds);
                updated.remove(draggedId);
                int safeIndex = Math.max(0, Math.min(targetIndex, updated.size()));
                updated.add(safeIndex, draggedId);
                queueBoardService.reorderEntriesInQueue(queueId, updated);
            } else {
                queueBoardService.moveEntryToQueue(draggedId, queueId, targetIndex);
                notifyInfo("Маршрут задачи обновлен");
            }
            refreshBoard();
        });
    }

    private List<QueueBindingOption> buildBindingOptions(WorkQueue queue) {
        List<WorkerClass> activeClasses = queueBoardService.loadActiveWorkerClasses();
        LinkedHashMap<UUID, QueueBoardService.QueueWorkerClassBinding> existingBindings = new LinkedHashMap<>();
        if (queue != null) {
            queueBoardService.loadBindingsForQueue(queue).forEach(binding ->
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

    private Warehouse selectedWarehouse() {
        return warehouseField.getValue();
    }

    private Button iconButton(VaadinIcon icon, String tooltip) {
        Button button = new Button(new Icon(icon));
        button.setTooltipText(tooltip);
        button.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        return button;
    }

    private Span badge(String text, String background, String color) {
        Span badge = new Span(text);
        badge.getStyle()
                .set("display", "inline-flex")
                .set("align-items", "center")
                .set("border-radius", "999px")
                .set("padding", "2px 8px")
                .set("font-size", "11px")
                .set("font-weight", "700")
                .set("background", background)
                .set("color", color);
        return badge;
    }

    private String cardBackground(EntryCard card) {
        QueueEntry entry = card.entry();
        if (entry.getStatus() == QueueEntryStatus.IN_PROGRESS) {
            return "#fef9c3";
        }
        if (entry.getStatus() == QueueEntryStatus.PAUSED) {
            return "#f1f5f9";
        }
        if (entry.getTask() != null && entry.getTask().getTaskKind() == RepairProcessTaskKind.REWORK) {
            return "#fff7ed";
        }
        if (card.effectiveEntryType() == QueueEntryType.SHADOW) {
            return "#eaf3ff";
        }
        return "#ffffff";
    }

    private String cardBorder(EntryCard card) {
        QueueEntry entry = card.entry();
        if (entry.getStatus() == QueueEntryStatus.IN_PROGRESS) {
            return "1px solid #eab308";
        }
        if (entry.getTask() != null && entry.getTask().getTaskKind() == RepairProcessTaskKind.REWORK) {
            return "1px solid #fb923c";
        }
        if (card.effectiveEntryType() == QueueEntryType.SHADOW) {
            return "1px dashed #60a5fa";
        }
        return "1px solid #cbd5e1";
    }

    private String typeColor(QueueEntryType type) {
        return type == QueueEntryType.REAL ? "#bbf7d0" : "#bfdbfe";
    }

    private String statusColor(QueueEntryStatus status) {
        return switch (status) {
            case IN_PROGRESS -> "#fde68a";
            case PAUSED -> "#e2e8f0";
            case DONE -> "#d1fae5";
            default -> "#f1f5f9";
        };
    }

    private String initials(String value) {
        if (value == null || value.isBlank()) {
            return "?";
        }
        String[] parts = value.trim().split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (!part.isBlank() && result.length() < 2) {
                result.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            }
        }
        return result.toString();
    }

    private String formatSeconds(long seconds) {
        if (seconds <= 0) {
            return "0м";
        }
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        if (hours > 0) {
            return hours + "ч " + minutes + "м";
        }
        return minutes + "м";
    }

    private String shortId(UUID id) {
        return id == null ? "-" : id.toString().substring(0, 8);
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void notifyThresholdIfNeeded(WorkQueue queue) {
        if (Boolean.TRUE.equals(queue.getSinkQueue())
                && Boolean.TRUE.equals(queue.getNotifyWhenThresholdReached())
                && queue.getNotificationThreshold() != null) {
            QueueColumn refreshed = queueBoardService.getBoardState(selectedWarehouse(), true).columns().stream()
                    .filter(column -> Objects.equals(column.queue().getId(), queue.getId()))
                    .findFirst()
                    .orElse(null);
            if (refreshed != null && refreshed.cards().size() >= queue.getNotificationThreshold()) {
                notifyInfo("В очереди " + queue.getName() + " накопилось " + refreshed.cards().size() + " задач");
            }
        }
    }

    private void runAction(Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            notifyError(ex.getMessage() == null ? "Не удалось выполнить действие" : ex.getMessage());
        }
    }

    private void notifyInfo(String message) {
        notifications.create(message)
                .withPosition(Notification.Position.TOP_END)
                .withDuration(2500)
                .show();
    }

    private void notifyError(String message) {
        notifications.create(message)
                .withType(Notifications.Type.ERROR)
                .withPosition(Notification.Position.TOP_END)
                .withDuration(4500)
                .show();
    }

    private static final class TaskStepRow {
        private final WorkQueue queue;
        private final TextArea taskTextField = new TextArea("Задание для очереди");
        private final ComboBox<Integer> plannedMinutesField = new ComboBox<>("Таймер этапа");

        private TaskStepRow(WorkQueue queue) {
            this.queue = queue;
            taskTextField.setWidthFull();
            plannedMinutesField.setItems(10, 15, 30, 45, 60, 90, 120, 180, 240, 480, 720, 1440);
            plannedMinutesField.setItemLabelGenerator(TaskStepRow::formatMinutes);
            plannedMinutesField.setValue(30);
            plannedMinutesField.setWidth("180px");
        }

        private static String formatMinutes(Integer minutes) {
            if (minutes == null) {
                return "";
            }
            if (minutes < 60) {
                return minutes + " мин";
            }
            int hours = minutes / 60;
            int rest = minutes % 60;
            return rest == 0 ? hours + " ч" : hours + " ч " + rest + " мин";
        }
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
            row.setAlignItems(FlexComponent.Alignment.CENTER);
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

    private record PendingTaskPhoto(String fileName, String contentType, byte[] bytes) {
    }
}
