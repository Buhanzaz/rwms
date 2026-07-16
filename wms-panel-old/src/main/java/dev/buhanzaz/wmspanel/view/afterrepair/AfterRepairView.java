package dev.buhanzaz.wmspanel.view.afterrepair;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.BoardTask;
import dev.buhanzaz.wmspanel.entity.BoardTaskPhotoLink;
import dev.buhanzaz.wmspanel.entity.BoardTaskPhotoType;
import dev.buhanzaz.wmspanel.entity.BoardTaskStatus;
import dev.buhanzaz.wmspanel.entity.QueueEntry;
import dev.buhanzaz.wmspanel.entity.RepairEstimate;
import dev.buhanzaz.wmspanel.entity.RepairEstimateLine;
import dev.buhanzaz.wmspanel.entity.RepairEstimateLineType;
import dev.buhanzaz.wmspanel.entity.RepairEstimateTaskPlan;
import dev.buhanzaz.wmspanel.entity.RepairEstimateTaskPlanGenerationStatus;
import dev.buhanzaz.wmspanel.entity.RepairProcessKind;
import dev.buhanzaz.wmspanel.entity.RepairProcess;
import dev.buhanzaz.wmspanel.entity.RepairProcessStatus;
import dev.buhanzaz.wmspanel.entity.RepairProcessTaskKind;
import dev.buhanzaz.wmspanel.entity.RepairProcessTaskLine;
import dev.buhanzaz.wmspanel.entity.RentalItemEventPhoto;
import dev.buhanzaz.wmspanel.entity.RentalItemEventType;
import dev.buhanzaz.wmspanel.entity.TaskAssignment;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.entity.WorkQueue;
import dev.buhanzaz.wmspanel.entity.Worker;
import dev.buhanzaz.wmspanel.entity.WorkerGroup;
import dev.buhanzaz.wmspanel.service.QueueBoardService;
import dev.buhanzaz.wmspanel.service.RepairEstimateService;
import dev.buhanzaz.wmspanel.service.RepairProcessService;
import dev.buhanzaz.wmspanel.service.RepairReworkService;
import dev.buhanzaz.wmspanel.service.ViewStateService;
import dev.buhanzaz.wmspanel.service.WarehouseAccessService;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.core.Messages;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.io.Serializable;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import com.vaadin.flow.server.streams.UploadHandler;

@Route(value = "after-repair", layout = MainView.class)
@ViewController(id = "AfterRepair.view")
@ViewDescriptor(path = "after-repair-view.xml")
public class AfterRepairView extends StandardView implements BeforeEnterObserver {

    @Autowired
    private RepairProcessService repairProcessService;
    @Autowired
    private RepairReworkService repairReworkService;
    @Autowired
    private QueueBoardService queueBoardService;
    @Autowired
    private RepairEstimateService repairEstimateService;
    @Autowired
    private WarehouseAccessService warehouseAccessService;
    @Autowired
    private ViewStateService viewStateService;
    @Autowired
    private Notifications notifications;
    @Autowired
    private Messages messages;

    @ViewComponent
    private VerticalLayout root;

    private final Grid<RepairProcess> grid = new Grid<>(RepairProcess.class, false);
    private final ComboBox<Warehouse> warehouseField = new ComboBox<>();
    private List<Warehouse> warehouses = List.of();
    private UUID requestedProcessId;
    private boolean warehouseSelectionProgrammaticChange;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        requestedProcessId = event.getLocation().getQueryParameters().getParameters()
                .getOrDefault("processId", List.of())
                .stream()
                .findFirst()
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
    public void onInit(InitEvent event) {
        build();
        reload();
    }

    private void build() {
        root.setPadding(false);
        root.setSpacing(true);
        root.setSizeFull();

        warehouses = warehouseAccessService.availableWarehouses();
        warehouseField.setItems(warehouses);
        warehouseField.setItemLabelGenerator(this::warehouseLabel);
        warehouseField.setLabel(msg("afterRepair.filter.warehouse"));
        warehouseField.setWidth("260px");
        warehouseField.setClearButtonVisible(false);
        warehouseField.setValue(viewStateService.resolveWarehouse(warehouses, viewStateService.getSelectedWarehouseId(), warehouseAccessService.defaultWarehouse()));
        warehouseField.addValueChangeListener(event -> {
            if (warehouseSelectionProgrammaticChange) {
                return;
            }
            viewStateService.setSelectedWarehouseId(event.getValue() == null ? null : event.getValue().getId());
            requestedProcessId = null;
            reload();
        });

        grid.addColumn(item -> item.getRentalItem() == null ? "-" : item.getRentalItem().getNumber()).setHeader(msg("afterRepair.grid.object")).setAutoWidth(true);
        grid.addColumn(this::repairProcessStatusLabel).setHeader(msg("afterRepair.grid.status"));
        grid.addComponentColumn(this::actions).setHeader("").setAutoWidth(true);
        grid.setItemDetailsRenderer(new ComponentRenderer<>(this::processDetails));
        grid.addItemClickListener(event -> grid.setDetailsVisible(event.getItem(), !grid.isDetailsVisible(event.getItem())));
        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES);
        grid.setWidthFull();
        grid.setHeightFull();

        HorizontalLayout toolbar = new HorizontalLayout(warehouseField);
        toolbar.setPadding(false);
        toolbar.setSpacing(true);
        toolbar.setAlignItems(FlexComponent.Alignment.END);
        toolbar.setWidthFull();
        root.add(toolbar, grid);
        root.expand(grid);
    }

    private HorizontalLayout actions(RepairProcess process) {
        Button accept = new Button(msg("afterRepair.button.accept"));
        accept.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        accept.addClickListener(event -> openAcceptDialog(process));

        Button rework = new Button(msg("afterRepair.button.rework"));
        rework.addThemeVariants(ButtonVariant.LUMO_ERROR);
        rework.addClickListener(event -> openReworkEstimateDialog(process));

        HorizontalLayout actions = new HorizontalLayout(rework, accept);
        actions.setPadding(false);
        actions.setSpacing(true);
        return actions;
    }

    private Component processDetails(RepairProcess process) {
        VerticalLayout details = new VerticalLayout();
        details.setPadding(true);
        details.setSpacing(true);
        details.setWidthFull();
        details.getStyle()
                .set("background", "#f8fafc")
                .set("border-top", "1px solid #e2e8f0")
                .set("border-bottom", "1px solid #e2e8f0");
        if (process == null || process.getId() == null) {
            details.add(new Div(msg("afterRepair.selectPrompt")));
            return details;
        }
        RepairProcessService.RepairProcessDossierData dossier = repairProcessService.loadProcessDossier(process.getId());
        RepairProcess current = dossier == null ? process : dossier.process();

        details.add(new H3(msg("afterRepair.dossierTitle")));
        details.add(summaryBand(current));
        details.add(photoSection(dossier));
        details.add(tasksSection(dossier));
        details.add(commentsSection(dossier));
        details.add(acceptanceSection(current));
        return details;
    }

    private Div summaryBand(RepairProcess process) {
        Div band = new Div();
        band.getStyle()
                .set("border", "1px solid #cbd5e1")
                .set("border-radius", "8px")
                .set("padding", "12px")
                .set("background", "#f8fafc");
        VerticalLayout stack = new VerticalLayout(
                detailLine(msg("afterRepair.summary.object"), process.getRentalItem() == null ? "-" : process.getRentalItem().getNumber()),
                detailLine(msg("afterRepair.summary.warehouse"), process.getWarehouse() == null ? "-" : process.getWarehouse().getName()),
                estimateDetailLine(process.getEstimate()),
                detailLine(msg("afterRepair.summary.kind"), repairProcessKindLabel(process.getProcessKind())),
                detailLine(msg("afterRepair.summary.status"), repairProcessStatusLabel(process)),
                detailLine(msg("afterRepair.summary.done"), formatDateTime(process.getAcceptedAt())),
                detailLine(msg("afterRepair.summary.acceptedBy"), valueOr(process.getAcceptedBy(), "-")),
                detailLine(msg("afterRepair.summary.comment"), valueOr(process.getComment(), "-")));
        stack.setPadding(false);
        stack.setSpacing(true);
        band.add(stack);
        return band;
    }

    private Div estimateDetailLine(RepairEstimate estimate) {
        if (estimate == null || estimate.getId() == null) {
            return detailLine(msg("afterRepair.summary.estimate"), "-");
        }
        Div row = baseDetailRow(msg("afterRepair.summary.estimate"));
        Button open = new Button(valueOr(estimate.getCabinNumber(), msg("afterRepair.estimate.link")), event -> openEstimateDialog(estimate.getId()));
        open.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        row.add(open);
        return row;
    }

    private void openEstimateDialog(UUID estimateId) {
        if (estimateId == null) {
            return;
        }
        RepairEstimate estimate = repairEstimateService.loadEstimate(estimateId);
        List<RepairEstimateLine> lines = repairEstimateService.loadLines(estimateId);

        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(msg("afterRepair.estimate.dialogTitle"));
        dialog.setWidth("72rem");
        dialog.setMaxWidth("95vw");

        FormLayout meta = new FormLayout();
        meta.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 2));
        meta.add(detailLine(msg("afterRepair.summary.object"), estimate.getRentalItem() == null ? valueOr(estimate.getCabinNumber(), "-") : estimate.getRentalItem().getNumber()));
        meta.add(detailLine(msg("afterRepair.estimate.date"), formatDateTime(estimate.getDispatchDate() == null ? estimate.getCreatedDate() : estimate.getDispatchDate())));
        meta.add(detailLine(msg("afterRepair.estimate.from"), valueOr(estimate.getSourceParty(), "-")));
        meta.add(detailLine(msg("afterRepair.estimate.author"), valueOr(estimate.getCreatedBy(), "-")));
        meta.add(detailLine(msg("afterRepair.estimate.createdAt"), formatDateTime(estimate.getCreatedDate())));

        Grid<RepairEstimateLine> linesGrid = new Grid<>(RepairEstimateLine.class, false);
        linesGrid.addColumn(line -> lineTypeLabel(line.getLineType())).setHeader(msg("afterRepair.estimate.lineType")).setAutoWidth(true);
        linesGrid.addColumn(RepairEstimateLine::getDescription).setHeader(msg("afterRepair.estimate.description")).setFlexGrow(1);
        linesGrid.addColumn(line -> line.getQuantity() == null ? "-" : line.getQuantity().toString()).setHeader(msg("afterRepair.estimate.quantity")).setAutoWidth(true);
        linesGrid.addColumn(line -> valueOr(line.getUnit(), "-")).setHeader(msg("afterRepair.estimate.unit")).setAutoWidth(true);
        linesGrid.addColumn(line -> line.getUnitPrice() == null ? "-" : line.getUnitPrice().toString()).setHeader(msg("afterRepair.estimate.price")).setAutoWidth(true);
        linesGrid.addColumn(line -> line.getLineTotal() == null ? "-" : line.getLineTotal().toString()).setHeader(msg("afterRepair.estimate.total")).setAutoWidth(true);
        linesGrid.setItems(lines);
        linesGrid.setAllRowsVisible(true);
        linesGrid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES);
        linesGrid.setWidthFull();

        VerticalLayout content = new VerticalLayout(meta, linesGrid);
        content.setPadding(false);
        content.setSpacing(true);
        content.setWidthFull();
        dialog.add(content);
        dialog.getFooter().add(new Button(msg("afterRepair.button.close"), event -> dialog.close()));
        dialog.open();
    }

    private void openReworkEstimateDialog(RepairProcess process) {
        if (process == null || process.getId() == null || process.getEstimate() == null || process.getEstimate().getId() == null) {
            openSendToReworkDialog(process, null);
            return;
        }
        RepairEstimate estimate = repairEstimateService.loadEstimate(process.getEstimate().getId());
        List<RepairEstimateLine> lines = repairEstimateService.loadLines(estimate.getId());
        RepairProcessService.RepairProcessDossierData dossier = repairProcessService.loadProcessDossier(process.getId());

        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(msg("afterRepair.reworkEstimate.title"));
        dialog.setWidth("78rem");
        dialog.setMaxWidth("96vw");

        FormLayout meta = new FormLayout();
        meta.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 2));
        meta.add(detailLine(msg("afterRepair.summary.object"), estimate.getRentalItem() == null ? valueOr(estimate.getCabinNumber(), "-") : estimate.getRentalItem().getNumber()));
        meta.add(detailLine(msg("afterRepair.estimate.date"), formatDateTime(estimate.getDispatchDate() == null ? estimate.getCreatedDate() : estimate.getDispatchDate())));
        meta.add(detailLine(msg("afterRepair.estimate.from"), valueOr(estimate.getSourceParty(), "-")));
        meta.add(detailLine(msg("afterRepair.estimate.author"), valueOr(estimate.getCreatedBy(), "-")));

        Grid<RepairEstimateLine> linesGrid = new Grid<>(RepairEstimateLine.class, false);
        linesGrid.addColumn(line -> lineTypeLabel(line.getLineType())).setHeader(msg("afterRepair.estimate.lineType")).setAutoWidth(true);
        linesGrid.addColumn(RepairEstimateLine::getDescription).setHeader(msg("afterRepair.estimate.description")).setFlexGrow(1);
        linesGrid.addColumn(line -> line.getQuantity() == null ? "-" : line.getQuantity().toString()).setHeader(msg("afterRepair.estimate.quantity")).setAutoWidth(true);
        linesGrid.addColumn(line -> valueOr(line.getUnit(), "-")).setHeader(msg("afterRepair.estimate.unit")).setAutoWidth(true);
        linesGrid.addColumn(line -> line.getUnitPrice() == null ? "-" : line.getUnitPrice().toString()).setHeader(msg("afterRepair.estimate.price")).setAutoWidth(true);
        linesGrid.addColumn(line -> line.getLineTotal() == null ? "-" : line.getLineTotal().toString()).setHeader(msg("afterRepair.estimate.total")).setAutoWidth(true);
        linesGrid.setItems(lines);
        linesGrid.setAllRowsVisible(true);
        linesGrid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES);
        linesGrid.setWidthFull();

        Div separator = new Div();
        separator.getStyle()
                .set("height", "3px")
                .set("background", "#dc2626")
                .set("width", "100%")
                .set("margin", "12px 0");

        VerticalLayout reworks = section(msg("afterRepair.reworkEstimate.extraTasks"));
        List<RepairProcess> activeReworks = dossier == null ? List.of() : dossier.reworks();
        if (activeReworks.isEmpty()) {
            reworks.add(emptyState(msg("afterRepair.reworkEstimate.noExtraTasks")));
        } else {
            for (RepairProcess rework : activeReworks) {
                reworks.add(reworkSummaryRow(rework));
            }
        }

        Button add = new Button(msg("afterRepair.button.addReworkTask"), event -> {
            dialog.close();
            openSendToReworkDialog(process, null);
        });
        add.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);

        VerticalLayout content = new VerticalLayout(meta, linesGrid, separator, reworks, add);
        content.setPadding(false);
        content.setSpacing(true);
        content.setWidthFull();
        dialog.add(content);
        dialog.getFooter().add(new Button(msg("afterRepair.button.close"), event -> dialog.close()));
        dialog.open();
    }

    private Component reworkSummaryRow(RepairProcess rework) {
        Div row = new Div();
        row.getStyle()
                .set("border", "1px solid #fecaca")
                .set("border-radius", "8px")
                .set("background", "#fff7ed")
                .set("padding", "10px")
                .set("margin-bottom", "8px");
        String group = rework.getRequestedWorkerGroup() == null ? "-" : workerGroupLabel(rework.getRequestedWorkerGroup());
        String worker = rework.getRequestedWorker() == null ? "-" : workerLabel(rework.getRequestedWorker());
        row.add(new Span(repairProcessStatusLabel(rework) + " · " + group + " · " + worker + " · " + valueOr(rework.getComment(), "-")));
        return row;
    }

    private VerticalLayout tasksSection(RepairProcessService.RepairProcessDossierData dossier) {
        VerticalLayout section = section(msg("afterRepair.section.tasks"));
        List<BoardTask> tasks = dossier == null ? List.of() : dossier.tasks();
        if (tasks.isEmpty()) {
            section.add(emptyState(msg("afterRepair.empty.noTasks")));
            return section;
        }
        section.add(tasksGrid(taskRows(dossier)));
        return section;
    }

    private VerticalLayout plansSection(RepairProcessService.RepairProcessDossierData dossier) {
        VerticalLayout section = section(msg("afterRepair.section.plans"));
        List<RepairEstimateTaskPlan> plans = dossier == null ? List.of() : dossier.plans();
        if (plans.isEmpty()) {
            section.add(emptyState(msg("afterRepair.empty.noPlans")));
            return section;
        }
        groupedPlans(plans).forEach((groupLabel, groupPlans) -> {
            section.add(groupHeading(groupLabel));
            for (RepairEstimateTaskPlan plan : groupPlans) {
                section.add(planCard(plan));
            }
        });
        return section;
    }

    private VerticalLayout photoSection(RepairProcessService.RepairProcessDossierData dossier) {
        VerticalLayout section = section(msg("afterRepair.section.photos"));
        if (dossier == null) {
            section.add(emptyState(msg("afterRepair.empty.noPhotos")));
            return section;
        }

        RepairProcess process = dossier.process();
        List<RentalItemEventPhoto> estimatePhotos = estimatePhotos(dossier);
        List<RentalItemEventPhoto> taskPhotos = dossier.photoLinks().stream()
                .map(BoardTaskPhotoLink::getPhoto)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Div photoContainer = new Div();
        photoContainer.setWidthFull();
        Runnable renderEstimatePhotos = () -> {
            photoContainer.removeAll();
            photoContainer.add(photoListOrEmpty(msg("afterRepair.photo.estimateTitle"), estimatePhotos));
        };
        Runnable renderTaskPhotos = () -> {
            photoContainer.removeAll();
            photoContainer.add(photoListOrEmpty(msg("afterRepair.photo.reworkTitle"), taskPhotos));
        };

        if (process != null && process.getProcessKind() == RepairProcessKind.REWORK) {
            Checkbox toggle = new Checkbox(msg("afterRepair.photo.showReworkPhotos"));
            toggle.addValueChangeListener(event -> {
                if (Boolean.TRUE.equals(event.getValue())) {
                    renderTaskPhotos.run();
                } else {
                    renderEstimatePhotos.run();
                }
            });
            section.add(toggle);
            renderEstimatePhotos.run();
        } else {
            photoContainer.add(photoListOrEmpty(msg("afterRepair.photo.estimateTitle"), estimatePhotos));
            photoContainer.add(photoListOrEmpty(msg("afterRepair.photo.linkedTitle"), taskPhotos));
        }
        section.add(photoContainer);
        return section;
    }

    private List<RentalItemEventPhoto> estimatePhotos(RepairProcessService.RepairProcessDossierData dossier) {
        if (dossier == null || dossier.process() == null || dossier.process().getEstimate() == null) {
            return List.of();
        }
        UUID estimateId = dossier.process().getEstimate().getId();
        return dossier.eventPhotos().stream()
                .filter(photo -> photo.getEvent() != null)
                .filter(photo -> photo.getEvent().getEstimate() != null && Objects.equals(photo.getEvent().getEstimate().getId(), estimateId))
                .toList();
    }

    private Component photoListOrEmpty(String title, List<RentalItemEventPhoto> photos) {
        VerticalLayout group = section(title);
        if (photos == null || photos.isEmpty()) {
            group.add(emptyState(msg("afterRepair.empty.noPhotos")));
        } else {
            group.add(photoRow(photos));
        }
        return group;
    }

    private VerticalLayout commentsSection(RepairProcessService.RepairProcessDossierData dossier) {
        VerticalLayout section = section(msg("afterRepair.section.comments"));
        List<String> comments = new ArrayList<>();
        if (dossier != null && dossier.process() != null && dossier.process().getComment() != null && !dossier.process().getComment().isBlank()) {
            comments.add(msg("afterRepair.comment.process") + ": " + dossier.process().getComment());
        }
        if (dossier != null) {
            for (RepairEstimateTaskPlan plan : dossier.plans()) {
                if (plan.getGroupComment() != null && !plan.getGroupComment().isBlank()) {
                    comments.add(msg("afterRepair.comment.plan") + " " + planLabel(plan) + ": " + plan.getGroupComment());
                }
                if (plan.getComment() != null && !plan.getComment().isBlank()) {
                    comments.add(msg("afterRepair.comment.plan") + " " + planLabel(plan) + " (" + msg("afterRepair.comment.note") + "): " + plan.getComment());
                }
            }
        }
        if (comments.isEmpty()) {
            section.add(emptyState(msg("afterRepair.empty.noComments")));
            return section;
        }
        for (String comment : comments) {
            Div line = new Div(comment);
            line.getStyle().set("padding", "6px 0").set("color", "#334155");
            section.add(line);
        }
        return section;
    }

    private VerticalLayout acceptanceSection(RepairProcess process) {
        VerticalLayout section = section(msg("afterRepair.section.acceptance"));
        Button accept = new Button(msg("afterRepair.button.accept"), event -> openAcceptDialog(process));
        accept.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button rework = new Button(msg("afterRepair.button.rework"), event -> openReworkEstimateDialog(process));
        rework.addThemeVariants(ButtonVariant.LUMO_ERROR);
        section.add(new HorizontalLayout(rework, accept));
        return section;
    }

    private VerticalLayout section(String title) {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(false);
        section.setSpacing(true);
        section.setWidthFull();
        H4 heading = new H4(title);
        heading.getStyle().set("margin", "0");
        section.add(heading);
        return section;
    }

    private VerticalLayout planCard(RepairEstimateTaskPlan plan) {
        VerticalLayout card = new VerticalLayout();
        card.setPadding(true);
        card.setSpacing(true);
        card.setWidthFull();
        card.getStyle()
                .set("border", "1px solid #e2e8f0")
                .set("border-radius", "8px")
                .set("background", "#ffffff");

        card.add(detailLine(msg("afterRepair.plan.plan"), planLabel(plan)),
                detailLine(msg("afterRepair.plan.queue"), plan.getQueue() == null ? "-" : plan.getQueue().getName()),
                detailLine(msg("afterRepair.plan.status"), generationStatusLabel(plan.getGenerationStatus())),
                detailLine(msg("afterRepair.plan.generatedTask"), plan.getGeneratedBoardTask() == null ? "-" : plan.getGeneratedBoardTask().getTitle()));

        if (plan.getGroupComment() != null && !plan.getGroupComment().isBlank()) {
            card.add(detailLine(msg("afterRepair.plan.groupComment"), plan.getGroupComment()));
        }
        if (plan.getComment() != null && !plan.getComment().isBlank()) {
            card.add(detailLine(msg("afterRepair.plan.comment"), plan.getComment()));
        }

        VerticalLayout lines = new VerticalLayout();
        lines.setPadding(false);
        lines.setSpacing(false);
        lines.setWidthFull();
        List<RepairProcessTaskLine> taskLines = plan.getTaskLines() == null ? List.of() : plan.getTaskLines();
        if (taskLines.isEmpty()) {
            lines.add(emptyState(msg("afterRepair.empty.noPlanLines")));
        } else {
            for (RepairProcessTaskLine line : taskLines) {
                lines.add(taskLineRow(line));
            }
        }
        card.add(lines);
        return card;
    }

    private Div taskLineRow(RepairProcessTaskLine line) {
        Div row = new Div();
        row.getStyle()
                .set("padding", "6px 0")
                .set("border-top", "1px solid #e2e8f0");
        String description = line.getEstimateLine() == null ? "-" : line.getEstimateLine().getDescription();
        String value = lineTypeLabel(line.getLineType()) + " · " + description;
        if (line.getEstimateLine() != null) {
            value = value + " · " + line.getEstimateLine().getQuantity() + " " + valueOr(line.getEstimateLine().getUnit(), "");
        }
        if (Boolean.TRUE.equals(line.getPrimaryWorkLine())) {
            value = value + " · " + msg("afterRepair.plan.primaryWork");
        }
        row.setText(value);
        return row;
    }

    private VerticalLayout taskPhotoGroup(String title, List<List<BoardTaskPhotoLink>> photoLists) {
        VerticalLayout group = section(title);
        List<BoardTaskPhotoLink> photos = new ArrayList<>();
        if (photoLists != null) {
            for (List<BoardTaskPhotoLink> list : photoLists) {
                if (list != null) {
                    photos.addAll(list);
                }
            }
        }
        if (photos.isEmpty()) {
            group.add(emptyState(msg("afterRepair.empty.noPhotos")));
            return group;
        }
        group.add(photoRow(photos.stream().map(BoardTaskPhotoLink::getPhoto).filter(Objects::nonNull).toList()));
        return group;
    }

    private VerticalLayout eventPhotoGroup(String title, List<List<RentalItemEventPhoto>> photoLists) {
        VerticalLayout group = section(title);
        List<RentalItemEventPhoto> photos = new ArrayList<>();
        if (photoLists != null) {
            for (List<RentalItemEventPhoto> list : photoLists) {
                if (list != null) {
                    photos.addAll(list);
                }
            }
        }
        if (photos.isEmpty()) {
            group.add(emptyState(msg("afterRepair.empty.noPhotos")));
            return group;
        }
        group.add(photoRow(photos));
        return group;
    }

    private HorizontalLayout photoRow(List<RentalItemEventPhoto> photos) {
        HorizontalLayout row = new HorizontalLayout();
        row.setPadding(false);
        row.setSpacing(true);
        row.getStyle().set("flex-wrap", "wrap");
        List<String> previewUrls = photos.stream()
                .map(photo -> repairEstimateService.mediaUrl(photo.getId(), RepairEstimateService.PhotoVariant.PREVIEW))
                .toList();
        List<String> originalUrls = photos.stream()
                .map(photo -> repairEstimateService.mediaUrl(photo.getId(), RepairEstimateService.PhotoVariant.ORIGINAL))
                .toList();
        List<String> captions = photos.stream()
                .map(photo -> valueOr(photo.getOriginalFileName(), msg("afterRepair.photo.fallbackCaption")))
                .toList();
        preloadPhotoSources(previewUrls);
        for (int index = 0; index < photos.size(); index++) {
            RentalItemEventPhoto photo = photos.get(index);
            int photoIndex = index;
            VerticalLayout item = new VerticalLayout();
            item.setPadding(false);
            item.setSpacing(false);
            item.getStyle().set("width", "180px");
            Image image = new Image(repairEstimateService.mediaUrl(photo.getId(), RepairEstimateService.PhotoVariant.THUMB), valueOr(photo.getOriginalFileName(), msg("afterRepair.photo.fallbackAlt")));
            image.setWidth("180px");
            image.setHeight("120px");
            image.getStyle()
                    .set("object-fit", "contain")
                    .set("border-radius", "6px")
                    .set("border", "1px solid #cbd5e1");
            image.addClickListener(event -> openPhotoGallery(previewUrls, originalUrls, captions, photoIndex));
            Div caption = new Div(valueOr(photo.getOriginalFileName(), msg("afterRepair.photo.fallbackCaption")));
            caption.getStyle().set("font-size", "12px").set("color", "#334155").set("padding-top", "4px");
            item.add(image, caption);
            row.add(item);
        }
        return row;
    }

    private void openPhotoGallery(List<String> previewUrls, List<String> originalUrls, List<String> captions, int initialIndex) {
        if (previewUrls == null || previewUrls.isEmpty()) {
            return;
        }
        Dialog dialog = new Dialog();
        dialog.setWidth("90vw");
        dialog.setHeight("90vh");

        int[] index = new int[] {Math.max(0, Math.min(initialIndex, previewUrls.size() - 1))};
        Image full = new Image(previewUrls.get(index[0]), captions.get(index[0]));
        full.setWidthFull();
        full.setHeightFull();
        full.getStyle()
                .set("object-fit", "contain")
                .set("background", "#0f172a");

        Span caption = new Span(captions.get(index[0]));
        Button prev = new Button("<", event -> shiftPhoto(full, caption, previewUrls, captions, index, -1));
        Button next = new Button(">", event -> shiftPhoto(full, caption, previewUrls, captions, index, 1));
        Button download = new Button(msg("afterRepair.button.downloadOriginal"), event -> {
            String current = originalUrls == null || originalUrls.size() <= index[0] ? null : originalUrls.get(index[0]);
            if (current != null && !current.isBlank()) {
                getUI().ifPresent(ui -> ui.getPage().open(current));
            }
        });
        download.setEnabled(originalUrls != null && originalUrls.size() > index[0] && originalUrls.get(index[0]) != null && !originalUrls.get(index[0]).isBlank());
        Button close = new Button(msg("afterRepair.button.close"), event -> dialog.close());

        HorizontalLayout controls = new HorizontalLayout(prev, next, download, close);
        VerticalLayout content = new VerticalLayout(full, caption, controls);
        content.setSizeFull();
        dialog.add(content);
        preloadPhotoSources(previewUrls);
        dialog.open();
    }

    private void shiftPhoto(Image image,
                            Span caption,
                            List<String> previewUrls,
                            List<String> captions,
                            int[] index,
                            int delta) {
        if (previewUrls == null || previewUrls.isEmpty()) {
            return;
        }
        index[0] = Math.floorMod(index[0] + delta, previewUrls.size());
        image.setSrc(previewUrls.get(index[0]));
        caption.setText(captions.get(index[0]));
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
                    if (!window.__afterRepairPhotoPreloads) {
                      window.__afterRepairPhotoPreloads = [];
                    }
                    const preload = (src) => {
                      if (!src) {
                        return;
                      }
                      const image = new Image();
                      image.decoding = 'async';
                      image.src = src;
                      window.__afterRepairPhotoPreloads.push(image);
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
                      window.__afterRepairPhotoPreloads = window.__afterRepairPhotoPreloads || [];
                      window.__afterRepairPhotoPreloads.push(image);
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

    private Div detailLine(String label, String value) {
        Div row = baseDetailRow(label);
        row.add(new Span(valueOr(value, "-")));
        return row;
    }

    private Div baseDetailRow(String label) {
        Div row = new Div();
        row.getStyle()
                .set("display", "flex")
                .set("gap", "0.5rem")
                .set("align-items", "flex-start");
        Span left = new Span(label + ":");
        left.getStyle().set("font-weight", "700").set("min-width", "10rem");
        row.add(left);
        return row;
    }

    private Div emptyState(String text) {
        Div empty = new Div(text);
        empty.getStyle().set("color", "#64748b").set("font-size", "12px");
        return empty;
    }

    private String planLabel(RepairEstimateTaskPlan plan) {
        if (plan == null || plan.getEstimateLine() == null) {
            return "-";
        }
        String description = valueOr(plan.getEstimateLine().getDescription(), "-");
        String queue = plan.getQueue() == null ? "" : " · " + plan.getQueue().getName();
        return description + queue;
    }

    private String generationStatusLabel(RepairEstimateTaskPlanGenerationStatus status) {
        if (status == null) {
            return "-";
        }
        return msg("dev.buhanzaz.wmspanel.entity/RepairEstimateTaskPlanGenerationStatus." + status.name());
    }

    private String lineTypeLabel(RepairEstimateLineType lineType) {
        if (lineType == null) {
            return "-";
        }
        return msg("dev.buhanzaz.wmspanel.entity/RepairEstimateLineType." + lineType.name());
    }

    private String repairProcessStatusLabel(RepairProcess process) {
        if (process == null || process.getStatus() == null) {
            return "-";
        }
        return msg("dev.buhanzaz.wmspanel.entity/RepairProcessStatus." + process.getStatus().name());
    }

    private String repairProcessKindLabel(RepairProcessKind kind) {
        if (kind == null) {
            return "-";
        }
        return msg("dev.buhanzaz.wmspanel.entity/RepairProcessKind." + kind.name());
    }

    private String workQueueLabel(WorkQueue queue) {
        if (queue == null) {
            return "";
        }
        String warehouse = queue.getWarehouse() == null ? null : queue.getWarehouse().getName();
        String name = valueOr(queue.getName(), queue.getCode());
        return warehouse == null || warehouse.isBlank() ? name : warehouse + " / " + name;
    }

    private String workerGroupLabel(WorkerGroup group) {
        if (group == null) {
            return "";
        }
        String workerClass = group.getWorkerClass() == null ? null : group.getWorkerClass().getName();
        String name = valueOr(group.getName(), "-");
        return workerClass == null || workerClass.isBlank() ? name : workerClass + " / " + name;
    }

    private String workerLabel(Worker worker) {
        return worker == null ? "" : valueOr(worker.getDisplayName(), "-");
    }

    private String estimateOrKindLabel(RepairProcess process) {
        if (process == null) {
            return "-";
        }
        if (process.getEstimate() != null && process.getEstimate().getCabinNumber() != null && !process.getEstimate().getCabinNumber().isBlank()) {
            return process.getEstimate().getCabinNumber();
        }
        if (process.getProcessKind() == RepairProcessKind.REWORK) {
            return msg("afterRepair.grid.rework");
        }
        return "-";
    }

    private String repairTaskKindLabel(RepairProcessTaskKind kind) {
        if (kind == null) {
            return "-";
        }
        return msg("dev.buhanzaz.wmspanel.entity/RepairProcessTaskKind." + kind.name());
    }

    private String boardTaskStatusLabel(BoardTaskStatus status) {
        if (status == null) {
            return "-";
        }
        return msg("dev.buhanzaz.wmspanel.entity/BoardTaskStatus." + status.name());
    }

    private void openAcceptDialog(RepairProcess process) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(msg("afterRepair.dialog.acceptTitle"));
        dialog.setWidth("40rem");
        TextArea comment = new TextArea(msg("afterRepair.dialog.commentLabel"));
        comment.setWidthFull();
        Button cancel = new Button(msg("afterRepair.button.cancel"), event -> dialog.close());
        Button accept = new Button(msg("afterRepair.button.confirm"), event -> {
            try {
                repairProcessService.acceptProcess(process.getId(), comment.getValue());
                dialog.close();
                reload();
                notifications.create(msg("afterRepair.notifications.accepted")).show();
            } catch (Exception ex) {
                notifications.create(ex.getMessage() == null ? msg("afterRepair.notifications.acceptFailed") : ex.getMessage())
                        .withType(Notifications.Type.ERROR)
                        .show();
            }
        });
        accept.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.add(new VerticalLayout(
                new Div(new Span(msg("afterRepair.dialog.object") + ": " + (process.getRentalItem() == null ? "-" : process.getRentalItem().getNumber()))),
                new Div(new Span(msg("afterRepair.dialog.plans") + ": " + repairProcessService.loadProcessPlans(process.getId()).size())),
                new Div(new Span(msg("afterRepair.dialog.tasks") + ": " + repairProcessService.loadProcessTasks(process.getId()).size())),
                comment));
        dialog.getFooter().add(new HorizontalLayout(cancel, accept));
        dialog.open();
    }

    private void openSendToReworkDialog(RepairProcess process, TaskRow selectedTask) {
        if (process == null || process.getId() == null) {
            return;
        }
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(msg("afterRepair.dialog.sendToReworkTitle"));
        dialog.setWidth("44rem");

        ComboBox<WorkQueue> queueField = new ComboBox<>(msg("afterRepair.dialog.reworkQueue"));
        queueField.setWidthFull();
        queueField.setItemLabelGenerator(this::workQueueLabel);
        List<WorkQueue> queues = process.getWarehouse() == null ? List.of() : queueBoardService.loadQueues(process.getWarehouse());
        queueField.setItems(queues);

        ComboBox<WorkerGroup> groupField = new ComboBox<>(msg("afterRepair.dialog.reworkGroup"));
        groupField.setWidthFull();
        groupField.setItemLabelGenerator(this::workerGroupLabel);
        groupField.setEnabled(false);

        ComboBox<Worker> workerField = new ComboBox<>(msg("afterRepair.dialog.reworkWorker"));
        workerField.setWidthFull();
        workerField.setItemLabelGenerator(this::workerLabel);
        workerField.setEnabled(false);
        workerField.setClearButtonVisible(true);

        queueField.addValueChangeListener(event -> {
            WorkQueue queue = event.getValue();
            List<WorkerGroup> groups = queue == null ? List.of() : queueBoardService.loadWorkerGroupsForQueue(queue);
            groupField.setItems(groups);
            groupField.setValue(groups.isEmpty() ? null : groups.get(0));
            groupField.setEnabled(!groups.isEmpty());
        });
        groupField.addValueChangeListener(event -> {
            WorkerGroup group = event.getValue();
            List<Worker> workers = group == null ? List.of() : queueBoardService.loadActiveWorkersForGroup(group);
            workerField.setItems(workers);
            workerField.setValue(workers.isEmpty() ? null : workers.get(0));
            workerField.setEnabled(!workers.isEmpty());
        });
        preselectReworkTarget(selectedTask, queues, queueField, groupField, workerField);

        TextArea comment = new TextArea(msg("afterRepair.dialog.reworkComment"));
        comment.setWidthFull();
        comment.setMinHeight("8rem");

        List<PendingTaskPhoto> pendingPhotos = new ArrayList<>();
        Div pendingInfo = new Div(msg("afterRepair.dialog.reworkPhotosCount") + ": 0");
        pendingInfo.getStyle().set("font-size", "12px").set("color", "#475569");
        Upload upload = new Upload(UploadHandler.inMemory((metadata, bytes) -> {
            pendingPhotos.add(new PendingTaskPhoto(metadata.fileName(), metadata.contentType(), bytes));
            pendingInfo.setText(msg("afterRepair.dialog.reworkPhotosCount") + ": " + pendingPhotos.size());
        }));
        upload.setAcceptedFileTypes("image/*");
        upload.setDropAllowed(true);
        upload.setMaxFiles(20);
        upload.setWidthFull();
        upload.addFileRemovedListener(event -> {
            removeNewestPendingTaskPhotoByFileName(pendingPhotos, event.getFileName());
            pendingInfo.setText(msg("afterRepair.dialog.reworkPhotosCount") + ": " + pendingPhotos.size());
        });

        Button cancel = new Button(msg("afterRepair.button.cancel"), event -> dialog.close());
        Button create = new Button(msg("afterRepair.button.createRework"), event -> {
            try {
                WorkQueue queue = queueField.getValue();
                WorkerGroup group = groupField.getValue();
                Worker worker = workerField.getValue();
                RepairReworkService.CreateReworkResult result = repairReworkService.createRework(new RepairReworkService.CreateReworkCommand(
                        process.getId(),
                        queue == null ? null : queue.getId(),
                        group == null ? null : group.getId(),
                        worker == null ? null : worker.getId(),
                        comment.getValue()));
                if (!pendingPhotos.isEmpty() && result.boardTask() != null) {
                    repairProcessService.attachTaskPhotos(
                            result.boardTask().getId(),
                            null,
                            BoardTaskPhotoType.BEFORE,
                            comment.getValue(),
                            pendingPhotos.stream()
                                    .map(photo -> new RepairProcessService.RepairTaskUploadedPhoto(
                                            photo.fileName(),
                                            photo.contentType(),
                                            new ByteArrayInputStream(photo.bytes())))
                                    .toList());
                }
                dialog.close();
                reload();
                notifications.create(msg("afterRepair.notifications.reworkCreated")).show();
            } catch (Exception ex) {
                notifications.create(ex.getMessage() == null ? msg("afterRepair.notifications.reworkFailed") : ex.getMessage())
                        .withType(Notifications.Type.ERROR)
                        .show();
            }
        });
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);

        dialog.add(new VerticalLayout(
                new Div(new Span(msg("afterRepair.dialog.object") + ": " + (process.getRentalItem() == null ? "-" : process.getRentalItem().getNumber()))),
                queueField,
                groupField,
                workerField,
                comment,
                pendingInfo,
                upload,
                emptyState(msg("afterRepair.dialog.reworkPhotosHint"))));
        dialog.getFooter().add(new HorizontalLayout(cancel, create));
        dialog.open();
    }

    private void preselectReworkTarget(TaskRow selectedTask,
                                       List<WorkQueue> queues,
                                       ComboBox<WorkQueue> queueField,
                                       ComboBox<WorkerGroup> groupField,
                                       ComboBox<Worker> workerField) {
        if (selectedTask == null) {
            return;
        }
        WorkQueue selectedQueue = selectedTask.entries().stream()
                .map(QueueEntry::getQueue)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseGet(() -> selectedTask.plans().stream()
                        .map(RepairEstimateTaskPlan::getQueue)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null));
        if (selectedQueue != null && selectedQueue.getId() != null) {
            queues.stream()
                    .filter(queue -> Objects.equals(queue.getId(), selectedQueue.getId()))
                    .findFirst()
                    .ifPresent(queueField::setValue);
        }
        WorkerGroup selectedGroup = selectedTask.assignments().stream()
                .map(TaskAssignment::getWorkerGroup)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (selectedGroup != null && selectedGroup.getId() != null) {
            List<WorkerGroup> groups = queueField.getValue() == null ? List.of() : queueBoardService.loadWorkerGroupsForQueue(queueField.getValue());
            groupField.setItems(groups);
            groups.stream()
                    .filter(group -> Objects.equals(group.getId(), selectedGroup.getId()))
                    .findFirst()
                    .ifPresent(groupField::setValue);
            groupField.setEnabled(!groups.isEmpty());
        }
        Worker selectedWorker = selectedTask.assignments().stream()
                .map(TaskAssignment::getWorker)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (selectedWorker != null && selectedWorker.getId() != null && groupField.getValue() != null) {
            List<Worker> workers = queueBoardService.loadActiveWorkersForGroup(groupField.getValue());
            workerField.setItems(workers);
            workers.stream()
                    .filter(worker -> Objects.equals(worker.getId(), selectedWorker.getId()))
                    .findFirst()
                    .ifPresent(workerField::setValue);
            workerField.setEnabled(!workers.isEmpty());
        }
    }

    private void reload() {
        warehouses = warehouseAccessService.availableWarehouses();
        Warehouse selectedWarehouse = viewStateService.resolveWarehouse(
                warehouses,
                viewStateService.getSelectedWarehouseId(),
                warehouseAccessService.defaultWarehouse());
        if (!Objects.equals(warehouseField.getValue(), selectedWarehouse)) {
            warehouseSelectionProgrammaticChange = true;
            try {
                warehouseField.setItems(warehouses);
                warehouseField.setValue(selectedWarehouse);
                warehouseField.setReadOnly(warehouses.size() <= 1);
            } finally {
                warehouseSelectionProgrammaticChange = false;
            }
        }
        List<RepairProcess> processes = selectedWarehouse == null
                ? List.of()
                : repairProcessService.loadAfterRepairProcesses(List.of(selectedWarehouse));
        grid.setItems(processes);

        if (requestedProcessId != null) {
            processes.stream()
                    .filter(process -> Objects.equals(process.getId(), requestedProcessId))
                    .findFirst()
                    .ifPresent(process -> grid.setDetailsVisible(process, true));
        }
    }

    private Grid<TaskRow> tasksGrid(List<TaskRow> rows) {
        Grid<TaskRow> tasksGrid = new Grid<>(TaskRow.class, false);
        tasksGrid.addColumn(TaskRow::queueLabel).setHeader(msg("afterRepair.task.queue")).setAutoWidth(true);
        tasksGrid.addColumn(TaskRow::groupLabel).setHeader(msg("afterRepair.task.group")).setAutoWidth(true);
        tasksGrid.addColumn(TaskRow::workerLabel).setHeader(msg("afterRepair.task.workers")).setAutoWidth(true);
        tasksGrid.addColumn(TaskRow::completionLabel).setHeader(msg("afterRepair.task.status")).setAutoWidth(true);
        tasksGrid.setItemDetailsRenderer(new ComponentRenderer<>(this::taskDetails));
        tasksGrid.addItemClickListener(event -> tasksGrid.setDetailsVisible(event.getItem(), !tasksGrid.isDetailsVisible(event.getItem())));
        tasksGrid.setItems(rows);
        tasksGrid.setAllRowsVisible(true);
        tasksGrid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES);
        tasksGrid.setWidthFull();
        return tasksGrid;
    }

    private List<TaskRow> taskRows(RepairProcessService.RepairProcessDossierData dossier) {
        if (dossier == null) {
            return List.of();
        }
        Map<UUID, List<RepairEstimateTaskPlan>> plansByTaskId = dossier.plans().stream()
                .filter(plan -> plan.getGeneratedBoardTask() != null && plan.getGeneratedBoardTask().getId() != null)
                .collect(Collectors.groupingBy(plan -> plan.getGeneratedBoardTask().getId(), LinkedHashMap::new, Collectors.toList()));
        Map<UUID, List<BoardTaskPhotoLink>> photoLinksByTaskId = dossier.photoLinks().stream()
                .filter(link -> link.getBoardTask() != null && link.getBoardTask().getId() != null)
                .collect(Collectors.groupingBy(link -> link.getBoardTask().getId(), LinkedHashMap::new, Collectors.toList()));
        Map<UUID, List<TaskAssignment>> assignmentsByTaskId = dossier.assignments().stream()
                .filter(assignment -> assignment.getQueueEntry() != null
                        && assignment.getQueueEntry().getTask() != null
                        && assignment.getQueueEntry().getTask().getId() != null)
                .collect(Collectors.groupingBy(assignment -> assignment.getQueueEntry().getTask().getId(), LinkedHashMap::new, Collectors.toList()));

        List<TaskRow> rows = new ArrayList<>();
        for (BoardTask task : dossier.tasks()) {
            List<QueueEntry> entries = repairProcessService.loadTaskEntries(task.getId());
            List<RepairEstimateTaskPlan> plans = plansByTaskId.getOrDefault(task.getId(), List.of());
            List<TaskAssignment> assignments = assignmentsByTaskId.getOrDefault(task.getId(), List.of());
            List<BoardTaskPhotoLink> photos = photoLinksByTaskId.getOrDefault(task.getId(), List.of());
            boolean sentToRework = hasActiveReworkForRow(dossier, entries, assignments);
            rows.add(new TaskRow(dossier.process(),
                    task,
                    entries,
                    plans,
                    assignments,
                    photos,
                    queueLabel(entries, plans),
                    groupLabel(assignments, dossier.process()),
                    workersLabel(assignments, dossier.process()),
                    taskCompletionLabel(task, dossier.process(), sentToRework),
                    sentToRework));
        }
        return rows.stream()
                .sorted(Comparator.comparingInt(this::taskRouteOrder)
                        .thenComparing(this::taskSequenceTime, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(row -> row.task().getId()))
                .toList();
    }

    private Component taskDetails(TaskRow row) {
        VerticalLayout details = new VerticalLayout();
        details.setPadding(true);
        details.setSpacing(true);
        details.setWidthFull();
        details.getStyle().set("background", "#ffffff").set("border", "1px solid #e2e8f0").set("border-radius", "8px");
        details.add(detailLine(msg("afterRepair.task.timeSpent"), timeSpentLabel(row.entries(), row.task())));
        details.add(detailLine(msg("afterRepair.task.comment"), valueOr(row.task().getDescription(), "-")));
        Button sendToRework = new Button(msg("afterRepair.button.sendToRework"), event -> openSendToReworkDialog(row.process(), row));
        sendToRework.addThemeVariants(ButtonVariant.LUMO_ERROR);
        details.add(sendToRework);

        List<RentalItemEventPhoto> photos = row.photos().stream()
                .sorted(Comparator.comparing(link -> link.getSortOrder() == null ? 0 : link.getSortOrder()))
                .map(BoardTaskPhotoLink::getPhoto)
                .filter(Objects::nonNull)
                .toList();
        details.add(photoListOrEmpty(msg("afterRepair.task.workerPhotos"), photos));

        VerticalLayout lines = section(msg("afterRepair.task.lines"));
        List<RepairProcessTaskLine> taskLines = row.plans().stream()
                .flatMap(plan -> (plan.getTaskLines() == null ? List.<RepairProcessTaskLine>of() : plan.getTaskLines()).stream())
                .toList();
        if (taskLines.isEmpty()) {
            lines.add(emptyState(msg("afterRepair.empty.noPlanLines")));
        } else {
            for (RepairProcessTaskLine line : taskLines) {
                lines.add(taskLineRow(line));
            }
        }
        details.add(lines);
        return details;
    }

    private boolean hasActiveReworkForRow(RepairProcessService.RepairProcessDossierData dossier,
                                          List<QueueEntry> entries,
                                          List<TaskAssignment> assignments) {
        if (dossier == null || dossier.reworks() == null || dossier.reworks().isEmpty()) {
            return false;
        }
        List<UUID> queueIds = entries.stream()
                .map(QueueEntry::getQueue)
                .filter(Objects::nonNull)
                .map(WorkQueue::getId)
                .filter(Objects::nonNull)
                .toList();
        List<UUID> groupIds = assignments.stream()
                .map(TaskAssignment::getWorkerGroup)
                .filter(Objects::nonNull)
                .map(WorkerGroup::getId)
                .filter(Objects::nonNull)
                .toList();
        List<UUID> workerIds = assignments.stream()
                .map(TaskAssignment::getWorker)
                .filter(Objects::nonNull)
                .map(Worker::getId)
                .filter(Objects::nonNull)
                .toList();
        for (RepairProcess rework : dossier.reworks()) {
            if (rework.getStatus() == RepairProcessStatus.ACCEPTED || rework.getStatus() == RepairProcessStatus.CANCELLED) {
                continue;
            }
            if (rework.getRequestedWorkerGroup() != null
                    && groupIds.contains(rework.getRequestedWorkerGroup().getId())) {
                return true;
            }
            if (rework.getRequestedWorker() != null
                    && workerIds.contains(rework.getRequestedWorker().getId())) {
                return true;
            }
            if (rework.getId() != null) {
                for (BoardTask reworkTask : repairProcessService.loadProcessTasks(rework.getId())) {
                    List<QueueEntry> reworkEntries = repairProcessService.loadTaskEntries(reworkTask.getId());
                    boolean sameQueue = reworkEntries.stream()
                            .map(QueueEntry::getQueue)
                            .filter(Objects::nonNull)
                            .map(WorkQueue::getId)
                            .anyMatch(queueIds::contains);
                    if (sameQueue) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private int taskRouteOrder(TaskRow row) {
        return row.entries().stream()
                .map(QueueEntry::getRouteIndex)
                .filter(Objects::nonNull)
                .min(Integer::compareTo)
                .orElseGet(() -> row.plans().stream()
                        .map(RepairEstimateTaskPlan::getSortOrder)
                        .filter(Objects::nonNull)
                        .min(Integer::compareTo)
                        .orElse(Integer.MAX_VALUE));
    }

    private OffsetDateTime taskSequenceTime(TaskRow row) {
        OffsetDateTime entryTime = row.entries().stream()
                .map(entry -> entry.getDoneAt() != null ? entry.getDoneAt() : entry.getActiveStartedAt())
                .filter(Objects::nonNull)
                .min(OffsetDateTime::compareTo)
                .orElse(null);
        if (entryTime != null) {
            return entryTime;
        }
        if (row.task().getDoneAt() != null) {
            return row.task().getDoneAt();
        }
        return row.task().getCreatedDate();
    }

    private String queueLabel(List<QueueEntry> entries, List<RepairEstimateTaskPlan> plans) {
        String fromEntries = entries.stream()
                .map(QueueEntry::getQueue)
                .filter(Objects::nonNull)
                .map(queue -> valueOr(queue.getName(), queue.getCode()))
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .collect(Collectors.joining(" → "));
        if (!fromEntries.isBlank()) {
            return fromEntries;
        }
        return plans.stream()
                .map(RepairEstimateTaskPlan::getQueue)
                .filter(Objects::nonNull)
                .map(queue -> valueOr(queue.getName(), queue.getCode()))
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .collect(Collectors.joining(" → "));
    }

    private String groupLabel(List<TaskAssignment> assignments, RepairProcess process) {
        String label = assignments.stream()
                .map(TaskAssignment::getWorkerGroup)
                .filter(Objects::nonNull)
                .map(this::workerGroupLabel)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .collect(Collectors.joining(", "));
        if (!label.isBlank()) {
            return label;
        }
        return process == null || process.getRequestedWorkerGroup() == null ? "-" : workerGroupLabel(process.getRequestedWorkerGroup());
    }

    private String workersLabel(List<TaskAssignment> assignments, RepairProcess process) {
        String label = assignments.stream()
                .map(TaskAssignment::getWorker)
                .filter(Objects::nonNull)
                .map(this::workerLabel)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .collect(Collectors.joining(", "));
        if (!label.isBlank()) {
            return label;
        }
        return process == null || process.getRequestedWorker() == null ? "-" : workerLabel(process.getRequestedWorker());
    }

    private String taskCompletionLabel(BoardTask task, RepairProcess process, boolean sentToRework) {
        if (task == null || task.getStatus() == null) {
            return "-";
        }
        if (sentToRework) {
            return msg("afterRepair.task.sentToRework");
        }
        if (task.getStatus() == BoardTaskStatus.DONE) {
            return process != null && process.getProcessKind() == RepairProcessKind.REWORK
                    ? msg("afterRepair.task.afterRework")
                    : msg("afterRepair.task.ready");
        }
        return boardTaskStatusLabel(task.getStatus());
    }

    private String timeSpentLabel(List<QueueEntry> entries, BoardTask task) {
        long activeSeconds = entries.stream()
                .map(QueueEntry::getActiveWorkSeconds)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
        if (activeSeconds <= 0 && task != null && task.getDoneAt() != null && task.getCreatedDate() != null) {
            activeSeconds = Math.max(0, Duration.between(task.getCreatedDate(), task.getDoneAt()).toSeconds());
        }
        long minutes = activeSeconds / 60;
        long seconds = activeSeconds % 60;
        return minutes + " " + msg("afterRepair.time.minutesShort") + " " + seconds + " " + msg("afterRepair.time.secondsShort");
    }

    private LinkedHashMap<String, List<RepairEstimateTaskPlan>> groupedPlans(List<RepairEstimateTaskPlan> plans) {
        LinkedHashMap<String, List<RepairEstimateTaskPlan>> groups = new LinkedHashMap<>();
        for (RepairEstimateTaskPlan plan : plans) {
            groups.computeIfAbsent(planGroupLabel(plan), ignored -> new ArrayList<>()).add(plan);
        }
        return groups;
    }

    private LinkedHashMap<String, List<BoardTask>> groupedTasks(List<BoardTask> tasks, List<RepairEstimateTaskPlan> plans) {
        Map<UUID, String> taskQueueLabels = plans.stream()
                .filter(plan -> plan.getGeneratedBoardTask() != null && plan.getGeneratedBoardTask().getId() != null)
                .collect(Collectors.toMap(
                        plan -> plan.getGeneratedBoardTask().getId(),
                        this::planGroupLabel,
                        (left, right) -> left,
                        LinkedHashMap::new));
        LinkedHashMap<String, List<BoardTask>> groups = new LinkedHashMap<>();
        for (BoardTask task : tasks) {
            String label = taskQueueLabels.get(task.getId());
            if (label == null) {
                label = msg("afterRepair.group.byKind") + ": " + repairTaskKindLabel(task.getTaskKind());
            }
            groups.computeIfAbsent(label, ignored -> new ArrayList<>()).add(task);
        }
        return groups;
    }

    private String planGroupLabel(RepairEstimateTaskPlan plan) {
        if (plan != null && plan.getQueue() != null && plan.getQueue().getName() != null && !plan.getQueue().getName().isBlank()) {
            return msg("afterRepair.group.queue") + ": " + plan.getQueue().getName();
        }
        return msg("afterRepair.group.unassigned");
    }

    private H4 groupHeading(String text) {
        H4 heading = new H4(text);
        heading.getStyle().set("margin", "8px 0 0");
        return heading;
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String warehouseLabel(Warehouse warehouse) {
        if (warehouse == null) {
            return "";
        }
        String city = valueOr(warehouse.getCity(), "");
        if (!city.isBlank()) {
            return city + " / " + valueOr(warehouse.getName(), valueOr(warehouse.getCode(), ""));
        }
        return valueOr(warehouse.getName(), valueOr(warehouse.getCode(), ""));
    }

    private String msg(String key) {
        String value = messages.getMessage(key);
        return value == null || value.isBlank() || value.equals(key) ? key : value;
    }

    private String formatDateTime(OffsetDateTime value) {
        return value == null ? "-" : DATE_TIME_FORMATTER.format(value);
    }

    private void removeNewestPendingTaskPhotoByFileName(List<PendingTaskPhoto> pendingPhotos, String fileName) {
        if (pendingPhotos == null || pendingPhotos.isEmpty() || fileName == null) {
            return;
        }
        for (int i = pendingPhotos.size() - 1; i >= 0; i--) {
            if (Objects.equals(pendingPhotos.get(i).fileName(), fileName)) {
                pendingPhotos.remove(i);
                return;
            }
        }
    }

    private record TaskRow(RepairProcess process,
                           BoardTask task,
                           List<QueueEntry> entries,
                           List<RepairEstimateTaskPlan> plans,
                           List<TaskAssignment> assignments,
                           List<BoardTaskPhotoLink> photos,
                           String queueLabel,
                           String groupLabel,
                           String workerLabel,
                           String completionLabel,
                           boolean sentToRework) {
    }

    private record PendingTaskPhoto(String fileName, String contentType, byte[] bytes) {
    }

}
