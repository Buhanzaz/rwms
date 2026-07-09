package dev.buhanzaz.wmspanel.view.kpiandrework;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.QueueEntryStatus;
import dev.buhanzaz.wmspanel.entity.WorkerClass;
import dev.buhanzaz.wmspanel.entity.WorkerGroup;
import dev.buhanzaz.wmspanel.service.KpiAndReworkService;
import dev.buhanzaz.wmspanel.service.RepairEstimateService;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.core.Messages;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Route(value = "kpi-and-rework", layout = MainView.class)
@ViewController(id = "KpiAndRework.view")
@ViewDescriptor(path = "kpi-and-rework-view.xml")
public class KpiAndReworkView extends StandardView {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    @Autowired
    private KpiAndReworkService kpiAndReworkService;
    @Autowired
    private RepairEstimateService repairEstimateService;
    @Autowired
    private Messages messages;

    @ViewComponent
    private VerticalLayout root;

    private final ComboBox<WorkerClass> workerClassField = new ComboBox<>();
    private final ComboBox<WorkerGroup> workerGroupField = new ComboBox<>();
    private final Div seamNotice = new Div();
    private final Div summaryLine = new Div();
    private final Grid<KpiAndReworkService.GroupHistoryEntry> historyGrid = new Grid<>(KpiAndReworkService.GroupHistoryEntry.class, false);

    @Subscribe
    public void onInit(InitEvent event) {
        build();
        loadWorkerClasses();
    }

    private void build() {
        root.setPadding(false);
        root.setSpacing(true);
        root.setSizeFull();

        workerClassField.setLabel(msg("kpiAndRework.filter.workerClass"));
        workerClassField.setWidth("280px");
        workerClassField.setClearButtonVisible(false);
        workerClassField.setItemLabelGenerator(workerClass -> workerClass == null ? "" : valueOr(workerClass.getName(), workerClass.getCode()));
        workerClassField.addValueChangeListener(event -> {
            loadGroups(event.getValue());
            refreshHistory();
        });

        workerGroupField.setLabel(msg("kpiAndRework.filter.workerGroup"));
        workerGroupField.setWidth("320px");
        workerGroupField.setClearButtonVisible(false);
        workerGroupField.setItemLabelGenerator(this::workerGroupLabel);
        workerGroupField.addValueChangeListener(event -> refreshHistory());

        seamNotice.getStyle()
                .set("border", "1px solid #fcd34d")
                .set("background", "#fffbeb")
                .set("color", "#92400e")
                .set("border-radius", "8px")
                .set("padding", "10px 12px")
                .set("font-size", "13px");

        summaryLine.getStyle()
                .set("font-size", "13px")
                .set("color", "#475569");

        configureGrid();

        HorizontalLayout filters = new HorizontalLayout(workerClassField, workerGroupField);
        filters.setPadding(false);
        filters.setSpacing(true);
        filters.setAlignItems(FlexComponent.Alignment.END);
        filters.setWidthFull();

        root.add(filters, seamNotice, summaryLine, historyGrid);
        root.expand(historyGrid);
    }

    private void configureGrid() {
        historyGrid.addColumn(entry -> formatDate(entry.date()))
                .setHeader(msg("kpiAndRework.history.date"))
                .setAutoWidth(true)
                .setFlexGrow(0);
        historyGrid.addColumn(entry -> valueOr(entry.unitNumber(), "-"))
                .setHeader(msg("kpiAndRework.history.unitNumber"))
                .setAutoWidth(true)
                .setFlexGrow(0);
        historyGrid.addColumn(entry -> valueOr(entry.warehouseName(), "-"))
                .setHeader(msg("kpiAndRework.history.warehouse"))
                .setAutoWidth(true);
        historyGrid.addColumn(entry -> valueOr(entry.taskTitle(), "-"))
                .setHeader(msg("kpiAndRework.history.task"))
                .setAutoWidth(true);
        historyGrid.addColumn(entry -> valueOr(entry.subtaskTitle(), "-"))
                .setHeader(msg("kpiAndRework.history.subtask"))
                .setAutoWidth(true);
        historyGrid.addColumn(entry -> valueOr(entry.performedBy(), "-"))
                .setHeader(msg("kpiAndRework.history.performedBy"))
                .setAutoWidth(true);
        historyGrid.addColumn(entry -> formatMinutes(entry.plannedMinutes()))
                .setHeader(msg("kpiAndRework.history.plannedTime"))
                .setAutoWidth(true)
                .setFlexGrow(0);
        historyGrid.addColumn(entry -> formatMinutes(entry.actualMinutes()))
                .setHeader(msg("kpiAndRework.history.actualTime"))
                .setAutoWidth(true)
                .setFlexGrow(0);
        historyGrid.addComponentColumn(this::photosCell)
                .setHeader(msg("kpiAndRework.history.photos"))
                .setAutoWidth(true)
                .setFlexGrow(0);
        historyGrid.addComponentColumn(this::statusBadge)
                .setHeader(msg("kpiAndRework.history.status"))
                .setAutoWidth(true)
                .setFlexGrow(0);
        historyGrid.addComponentColumn(this::workTypeBadge)
                .setHeader(msg("kpiAndRework.history.workType"))
                .setAutoWidth(true)
                .setFlexGrow(0);

        historyGrid.setWidthFull();
        historyGrid.setHeightFull();
        historyGrid.setAllRowsVisible(true);
    }

    private void loadWorkerClasses() {
        List<WorkerClass> classes = kpiAndReworkService.loadWorkerClasses();
        workerClassField.setItems(classes);
        WorkerClass selected = classes.isEmpty() ? null : classes.get(0);
        workerClassField.setValue(selected);
        loadGroups(selected);
        refreshHistory();
    }

    private void loadGroups(WorkerClass workerClass) {
        List<WorkerGroup> groups = workerClass == null
                ? List.of()
                : kpiAndReworkService.loadGroupsForClass(workerClass.getId());
        WorkerGroup previous = workerGroupField.getValue();
        workerGroupField.setItems(groups);
        WorkerGroup resolved = groups.stream()
                .filter(group -> previous != null && Objects.equals(group.getId(), previous.getId()))
                .findFirst()
                .orElse(groups.isEmpty() ? null : groups.get(0));
        workerGroupField.setValue(resolved);
        workerGroupField.setEnabled(!groups.isEmpty());
    }

    private void refreshHistory() {
        WorkerGroup workerGroup = workerGroupField.getValue();
        if (workerGroup == null || workerGroup.getId() == null) {
            seamNotice.setText(msg("kpiAndRework.empty.selectGroup"));
            summaryLine.setText("");
            historyGrid.setItems(List.of());
            return;
        }

        KpiAndReworkService.GroupHistoryResult result = kpiAndReworkService.loadHistory(workerGroup.getId());
        historyGrid.setItems(result.entries());
        seamNotice.setText(result.reworkMarkerAvailable()
                ? msg("kpiAndRework.seam.ready")
                : msg("kpiAndRework.seam.waiting"));
        summaryLine.setText(result.entries().isEmpty()
                ? msg("kpiAndRework.empty.history")
                : msg("kpiAndRework.summary.count").formatted(result.entries().size()));
    }

    private Component photosCell(KpiAndReworkService.GroupHistoryEntry entry) {
        List<KpiAndReworkService.PhotoInfo> photos = entry.photos();
        if (photos == null || photos.isEmpty()) {
            Span empty = new Span(msg("kpiAndRework.photos.none"));
            empty.getStyle().set("font-size", "12px").set("color", "#64748b");
            return empty;
        }

        HorizontalLayout row = new HorizontalLayout();
        row.setPadding(false);
        row.setSpacing(true);
        row.setAlignItems(FlexComponent.Alignment.CENTER);

        int limit = Math.min(3, photos.size());
        for (int index = 0; index < limit; index++) {
            KpiAndReworkService.PhotoInfo photo = photos.get(index);
            if (photo == null || photo.photoId() == null) {
                continue;
            }
            Image image = new Image(repairEstimateService.mediaUrl(photo.photoId(), RepairEstimateService.PhotoVariant.THUMB),
                    valueOr(photo.fileName(), msg("kpiAndRework.photos.previewAlt")));
            image.setWidth("44px");
            image.setHeight("44px");
            image.getStyle()
                    .set("object-fit", "cover")
                    .set("border-radius", "6px")
                    .set("border", "1px solid #cbd5e1")
                    .set("background", "#f8fafc")
                    .set("cursor", "pointer");
            image.addClickListener(event -> getUI().ifPresent(ui -> ui.getPage()
                    .open(repairEstimateService.mediaUrl(photo.photoId(), RepairEstimateService.PhotoVariant.ORIGINAL))));
            row.add(image);
        }

        if (photos.size() > limit) {
            Span more = badge("+" + (photos.size() - limit), "#e2e8f0", "#334155");
            row.add(more);
        }
        return row;
    }

    private Component statusBadge(KpiAndReworkService.GroupHistoryEntry entry) {
        QueueEntryStatus status = entry.status();
        String text = status == null ? "-" : msg("dev.buhanzaz.wmspanel.entity/QueueEntryStatus." + status.name());
        String background = switch (status == null ? QueueEntryStatus.WAITING : status) {
            case DONE -> "#dcfce7";
            case IN_PROGRESS -> "#fef3c7";
            case PAUSED -> "#e2e8f0";
            case CANCELLED -> "#fee2e2";
            case WAITING -> "#e0f2fe";
        };
        String color = switch (status == null ? QueueEntryStatus.WAITING : status) {
            case DONE -> "#166534";
            case IN_PROGRESS -> "#92400e";
            case PAUSED -> "#334155";
            case CANCELLED -> "#991b1b";
            case WAITING -> "#0c4a6e";
        };
        return badge(text, background, color);
    }

    private Component workTypeBadge(KpiAndReworkService.GroupHistoryEntry entry) {
        Optional<KpiAndReworkService.WorkType> workType = entry.workType();
        if (workType == null || workType.isEmpty()) {
            return badge(msg("kpiAndRework.workType.pending"), "#f8fafc", "#64748b");
        }
        if (workType.get() == KpiAndReworkService.WorkType.REWORK) {
            return badge(msg("kpiAndRework.workType.rework"), "#fee2e2", "#991b1b");
        }
        return badge(msg("kpiAndRework.workType.regular"), "#dcfce7", "#166534");
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
                .set("color", color)
                .set("white-space", "nowrap");
        return badge;
    }

    private String formatDate(OffsetDateTime value) {
        return value == null ? "-" : DATE_TIME_FORMATTER.format(value);
    }

    private String formatMinutes(Integer value) {
        if (value == null || value <= 0) {
            return "-";
        }
        if (value < 60) {
            return value + " " + msg("kpiAndRework.minutes.short");
        }
        int hours = value / 60;
        int minutes = value % 60;
        return minutes == 0
                ? hours + " " + msg("kpiAndRework.hours.short")
                : hours + " " + msg("kpiAndRework.hours.short") + " " + minutes + " " + msg("kpiAndRework.minutes.short");
    }

    private String workerGroupLabel(WorkerGroup workerGroup) {
        if (workerGroup == null) {
            return "";
        }
        String warehouse = workerGroup.getWarehouse() == null ? null : workerGroup.getWarehouse().getName();
        String groupName = valueOr(workerGroup.getName(), "-");
        return warehouse == null || warehouse.isBlank() ? groupName : groupName + " / " + warehouse;
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String msg(String key) {
        String value = messages.getMessage(key);
        return value == null || value.isBlank() || value.equals(key) ? key : value;
    }
}
