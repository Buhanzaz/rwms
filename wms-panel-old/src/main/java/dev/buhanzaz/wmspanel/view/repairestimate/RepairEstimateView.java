package dev.buhanzaz.wmspanel.view.repairestimate;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.dnd.DragSource;
import com.vaadin.flow.component.dnd.DropTarget;
import com.vaadin.flow.component.dnd.EffectAllowed;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.radiobutton.RadioGroupVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.server.streams.UploadHandler;
import com.vaadin.flow.data.provider.ListDataProvider;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import dev.buhanzaz.wmspanel.entity.RepairEstimate;
import dev.buhanzaz.wmspanel.entity.RepairEstimateLine;
import dev.buhanzaz.wmspanel.entity.RepairEstimateLineType;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNode;
import dev.buhanzaz.wmspanel.entity.RepairEstimateTaskPlan;
import dev.buhanzaz.wmspanel.entity.RepairEstimateTaskPlanGenerationStatus;
import dev.buhanzaz.wmspanel.entity.RepairEstimateStatus;
import dev.buhanzaz.wmspanel.entity.RepairProcess;
import dev.buhanzaz.wmspanel.entity.RepairProcessStatus;
import dev.buhanzaz.wmspanel.entity.RentalItem;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.entity.WorkQueue;
import dev.buhanzaz.wmspanel.service.RepairCatalogService;
import dev.buhanzaz.wmspanel.service.RepairCatalogService.CatalogNode;
import dev.buhanzaz.wmspanel.service.RepairCatalogService.CatalogNodeType;
import dev.buhanzaz.wmspanel.service.RepairEstimateService;
import dev.buhanzaz.wmspanel.service.RepairEstimateTaskPlanGenerationService;
import dev.buhanzaz.wmspanel.service.RepairEstimateTaskPlanService;
import dev.buhanzaz.wmspanel.service.RepairProcessService;
import dev.buhanzaz.wmspanel.service.ViewStateService;
import dev.buhanzaz.wmspanel.service.WarehouseAccessService;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.core.Messages;
import io.jmix.core.DataManager;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

@Route(value = "repair-estimates", layout = MainView.class)
@ViewController(id = "RepairEstimate.view")
@ViewDescriptor(path = "repair-estimate-view.xml")
public class RepairEstimateView extends StandardView implements BeforeEnterObserver {

    @Autowired
    private RepairEstimateService repairEstimateService;
    @Autowired
    private RepairEstimateTaskPlanService repairEstimateTaskPlanService;
    @Autowired
    private RepairEstimateTaskPlanGenerationService repairEstimateTaskPlanGenerationService;
    @Autowired
    private RepairCatalogService repairCatalogService;
    @Autowired
    private RepairProcessService repairProcessService;
    @Autowired
    private WarehouseAccessService warehouseAccessService;
    @Autowired
    private ViewStateService viewStateService;
    @Autowired
    private DataManager dataManager;
    @Autowired
    private Notifications notifications;
    @Autowired
    private Messages messages;
    @ViewComponent
    private MessageBundle messageBundle;

    @ViewComponent
    private VerticalLayout contentRoot;

    private final ComboBox<Warehouse> warehouseField = new ComboBox<>("Склад");
    private final Tab draftEstimatesTab = new Tab("Требуют доработки");
    private final Tab completedEstimatesTab = new Tab("Завершённые");
    private final Tabs estimateStatusTabs = new Tabs(draftEstimatesTab, completedEstimatesTab);
    private final Grid<RepairEstimate> grid = new Grid<>(RepairEstimate.class, false);
    private List<Warehouse> availableWarehouses = List.of();
    private RepairEstimateStatus selectedEstimateStatus = RepairEstimateStatus.DRAFT;
    private Registration estimatePollRegistration;
    private int activePollIntervalMs = -1;
    private UUID pendingEstimateId;
    private boolean viewReady;
    private boolean estimateOpenedFromQuery;

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        registerEstimatePollListener(attachEvent.getUI());
        enableEstimatePolling(attachEvent.getUI());
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        disableEstimatePolling(detachEvent.getUI());
        if (estimatePollRegistration != null) {
            estimatePollRegistration.remove();
            estimatePollRegistration = null;
        }
        super.onDetach(detachEvent);
    }

    @Subscribe
    public void onInit(InitEvent event) {
        buildView();
        loadData();
        viewReady = true;
        tryOpenEstimateFromQuery();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        pendingEstimateId = parseEstimateId(event);
        estimateOpenedFromQuery = false;
        tryOpenEstimateFromQuery();
    }

    private void buildView() {
        availableWarehouses = warehouseAccessService.availableWarehouses();
        warehouseField.setItems(availableWarehouses);
        warehouseField.setItemLabelGenerator(Warehouse::getName);
        warehouseField.setClearButtonVisible(false);
        warehouseField.setValue(viewStateService.resolveWarehouse(
                availableWarehouses,
                viewStateService.getTerminalWarehouseId(),
                warehouseAccessService.defaultWarehouse()));
        warehouseField.addValueChangeListener(change -> {
            viewStateService.setTerminalWarehouseId(change.getValue() == null ? null : change.getValue().getId());
            loadData();
        });

        Button createButton = new Button("Создать смету", this::openCreateDialog);
        createButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        HorizontalLayout toolbar = new HorizontalLayout(warehouseField, createButton);
        toolbar.setWidthFull();
        toolbar.setAlignItems(FlexComponent.Alignment.END);

        estimateStatusTabs.setWidthFull();
        estimateStatusTabs.setSelectedTab(draftEstimatesTab);
        estimateStatusTabs.addSelectedChangeListener(event -> {
            selectedEstimateStatus = event.getSelectedTab() == completedEstimatesTab
                    ? RepairEstimateStatus.COMPLETED
                    : RepairEstimateStatus.DRAFT;
            loadData();
        });

        grid.addColumn(item -> item.getCreatedDate() == null ? "" : item.getCreatedDate().toLocalDate().toString()).setHeader("Создана").setAutoWidth(true);
        grid.addColumn(RepairEstimate::getCabinNumber).setHeader("Бытовка").setAutoWidth(true);
        grid.addColumn(item -> valueOr(item.getSourceParty())).setHeader("От кого").setAutoWidth(true);
        grid.addColumn(item -> valueOr(item.getDestinationParty())).setHeader("Кому").setAutoWidth(true);
        grid.addColumn(item -> item.getTotalAmount() == null ? BigDecimal.ZERO : item.getTotalAmount()).setHeader("Итого").setAutoWidth(true);
        grid.addColumn(item -> repairEstimateStatusLabel(item.getStatus())).setHeader("Статус").setAutoWidth(true);
        grid.addItemClickListener(event -> openEditor(event.getItem()));
        grid.setWidthFull();
        grid.setHeightFull();

        contentRoot.add(toolbar, estimateStatusTabs, grid);
        contentRoot.expand(grid);
    }

    private void loadData() {
        Warehouse warehouse = warehouseField.getValue();
        grid.setItems(repairEstimateService.loadEstimates(warehouse == null ? null : warehouse.getId(), selectedEstimateStatus));
    }

    private void registerEstimatePollListener(UI ui) {
        if (ui == null || estimatePollRegistration != null) {
            return;
        }
        estimatePollRegistration = ui.addPollListener(event -> loadData());
    }

    private void enableEstimatePolling(UI ui) {
        if (ui == null || activePollIntervalMs == 3000) {
            return;
        }
        ui.setPollInterval(3000);
        activePollIntervalMs = 3000;
    }

    private void disableEstimatePolling(UI ui) {
        if (ui != null) {
            ui.setPollInterval(-1);
        }
        activePollIntervalMs = -1;
    }

    private void switchEstimateStatusTab(RepairEstimateStatus status) {
        selectedEstimateStatus = status == null ? RepairEstimateStatus.DRAFT : status;
        estimateStatusTabs.setSelectedTab(selectedEstimateStatus == RepairEstimateStatus.COMPLETED
                ? completedEstimatesTab
                : draftEstimatesTab);
    }

    private void openCreateDialog(ClickEvent<Button> event) {
        openEditor(null);
    }

    private void openEditor(RepairEstimate existingEstimate) {
        EstimateEditorDialog editor = new EstimateEditorDialog(existingEstimate);
        editor.open();
    }

    private String repairEstimateStatusLabel(RepairEstimateStatus status) {
        if (status == null) {
            return "";
        }
        return repairEstimateEnumLabel("dev.buhanzaz.wmspanel.entity/RepairEstimateStatus." + status.name(), status.name());
    }

    private String repairEstimateLineTypeLabel(RepairEstimateLineType lineType) {
        if (lineType == null) {
            return "";
        }
        return repairEstimateEnumLabel("dev.buhanzaz.wmspanel.entity/RepairEstimateLineType." + lineType.name(), lineType.name());
    }

    private String generationStatusLabel(RepairEstimateTaskPlanGenerationStatus status) {
        if (status == null) {
            return "";
        }
        return repairEstimateEnumLabel("dev.buhanzaz.wmspanel.entity/RepairEstimateTaskPlanGenerationStatus." + status.name(), status.name());
    }

    private String repairEstimateEnumLabel(String key, String fallback) {
        String value = messages.getMessage(key);
        return value == null || value.isBlank() || value.equals(key) ? fallback : value;
    }

    private void tryOpenEstimateFromQuery() {
        if (!viewReady || pendingEstimateId == null || estimateOpenedFromQuery) {
            return;
        }
        RepairEstimate estimate = repairEstimateService.loadEstimate(pendingEstimateId);
        pendingEstimateId = null;
        if (estimate == null) {
            notifications.create("Смета не найдена")
                    .withType(Notifications.Type.WARNING)
                    .withPosition(Notification.Position.TOP_END)
                    .withDuration(2500)
                    .show();
            estimateOpenedFromQuery = true;
            return;
        }
        estimateOpenedFromQuery = true;
        switchEstimateStatusTab(estimate.getStatus());
        loadData();
        openEditor(estimate);
    }

    private UUID parseEstimateId(BeforeEnterEvent event) {
        if (event == null || event.getLocation() == null || event.getLocation().getQueryParameters() == null) {
            return null;
        }
        List<String> values = event.getLocation().getQueryParameters().getParameters().get("estimateId");
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(values.get(0));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String valueOr(String value) {
        return value == null ? "" : value;
    }

    private enum CatalogMode {
        WORKS_ONLY("Только работы"),
        MATERIALS_ONLY("Только материалы"),
        LINKED_SET("Работы + материалы");

        private final String label;

        CatalogMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private enum CompletionMode {
        AUTO("completeModeDialog.auto"),
        MANUAL("completeModeDialog.manual");

        private final String labelKey;

        CompletionMode(String labelKey) {
            this.labelKey = labelKey;
        }

        public String label(Function<String, String> messageResolver) {
            return messageResolver.apply(labelKey);
        }
    }

    private final class EstimateEditorDialog extends Dialog {
        private static final int PAGE_SIZE = 9;
        private final RepairEstimate existingEstimate;
        private final ComboBox<RentalItem> rentalItemField = new ComboBox<>("Бытовка");
        private final TextField sourcePartyField = new TextField("От кого");
        private final TextField destinationPartyField = new TextField("Кому едет");
        private final DatePicker dispatchDateField = new DatePicker("Прибытие");
        private final TextArea commentField = new TextArea("Комментарий");
        private final Grid<EstimateLineDraft> linesGrid = new Grid<>(EstimateLineDraft.class, false);
        private final List<EstimateLineDraft> lines = new ArrayList<>();
        private final ListDataProvider<EstimateLineDraft> linesProvider = new ListDataProvider<>(lines);
        private final Div totalLine = new Div();
        private final List<PhotoPreview> photoPreviews = new ArrayList<>();
        private final Set<UUID> removedPhotoIds = new LinkedHashSet<>();
        private final Image previewImage = new Image();
        private final Div previewCaption = new Div();
        private final Deque<CatalogFrame> catalogFrames = new ArrayDeque<>();
        private final ComboBox<CatalogMode> catalogModeSelector = new ComboBox<>();
        private final Div catalogPath = new Div();
        private final Div catalogGrid = new Div();
        private CatalogMode catalogMode = CatalogMode.LINKED_SET;
        private int photoIndex;
        private int catalogPage;

        private EstimateEditorDialog(RepairEstimate existingEstimate) {
            this.existingEstimate = existingEstimate;
            catalogFrames.addLast(CatalogFrame.root());
            setHeaderTitle(existingEstimate == null ? "Создание сметы" : "Редактирование сметы");
            setWidth("98vw");
            setMaxWidth("none");
            setHeight("96vh");
            setResizable(true);

            Component body = buildBody();
            VerticalLayout content = new VerticalLayout(body, buildFooter());
            content.setPadding(false);
            content.setSpacing(true);
            content.setSizeFull();
            content.expand(body);
            add(content);
            addOpenedChangeListener(event -> {
                if (event.isOpened()) {
                    installCatalogShortcutListener();
                    preloadPhotoSources();
                } else {
                    removeCatalogShortcutListener();
                }
            });

            initFields();
            renderCatalogLevel();
            refreshLines();
            refreshPhotoPreview();
        }

        @Override
        protected void onAttach(AttachEvent attachEvent) {
            super.onAttach(attachEvent);
            installCatalogShortcutListener();
        }

        @Override
        protected void onDetach(DetachEvent detachEvent) {
            removeCatalogShortcutListener();
            super.onDetach(detachEvent);
        }

        private Component buildBody() {
            Component mediaPane = buildMediaPane();
            Component editorPane = buildEditorPane();
            HorizontalLayout body = new HorizontalLayout(mediaPane, editorPane);
            body.setWidthFull();
            body.setHeightFull();
            body.setAlignItems(FlexComponent.Alignment.STRETCH);
            body.setFlexGrow(10, mediaPane);
            body.setFlexGrow(10, editorPane);
            return body;
        }

        private Component buildMediaPane() {
            VerticalLayout mediaPane = new VerticalLayout();
            mediaPane.setWidth("46%");
            mediaPane.setHeightFull();
            mediaPane.setPadding(false);
            mediaPane.setMinWidth("0");
            mediaPane.setSpacing(true);

            previewImage.setWidthFull();
            previewImage.getStyle()
                    .set("background", "#0f172a")
                    .set("border-radius", "8px")
                    .set("object-fit", "contain")
                    .set("height", "36rem")
                    .set("max-height", "52vh")
                    .set("min-height", "24rem");
            previewImage.addClickListener(event -> openFullscreenGallery());

            HorizontalLayout galleryButtons = new HorizontalLayout();
            Button prev = new Button("<", event -> shiftPhoto(-1));
            Button next = new Button(">", event -> shiftPhoto(1));
            Button uploadInfo = new Button("+");
            uploadInfo.setEnabled(false);
            Button removeCurrentPhoto = new Button("Удалить фото", event -> removeCurrentPhoto());
            removeCurrentPhoto.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            galleryButtons.add(prev, uploadInfo, next, removeCurrentPhoto);
            galleryButtons.setAlignItems(FlexComponent.Alignment.CENTER);

            Upload upload = new Upload(UploadHandler.inMemory((metadata, bytes) -> {
                photoPreviews.add(new PhotoPreview(
                        null,
                        metadata.fileName(),
                        metadata.contentType(),
                        bytes,
                        dataUrl(metadata.contentType(), bytes)));
                photoIndex = photoPreviews.size() - 1;
                refreshPhotoPreview();
            }));
            upload.addFileRemovedListener(event -> {
                removeNewestUnsavedPhotoByFileName(event.getFileName());
                refreshPhotoPreview();
            });
            upload.setDropAllowed(true);
            upload.setMaxFiles(20);
            upload.setWidthFull();

            VerticalLayout mediaControls = new VerticalLayout(galleryButtons, upload);
            mediaControls.setPadding(false);
            mediaControls.setSpacing(true);
            mediaControls.setWidthFull();

            mediaPane.add(new H4("Медиа осмотра"), previewImage, previewCaption, mediaControls, buildCatalogPane());
            return mediaPane;
        }

        private Component buildEditorPane() {
            VerticalLayout pane = new VerticalLayout();
            pane.setWidth("54%");
            pane.setHeightFull();
            pane.setPadding(false);
            pane.setSpacing(true);
            pane.setMinWidth("0");

            HorizontalLayout header = new HorizontalLayout(sourcePartyField, rentalItemField, dispatchDateField);
            header.setWidthFull();
            header.expand(sourcePartyField, rentalItemField, dispatchDateField);
            header.setPadding(false);

            commentField.setWidthFull();

            linesGrid.setItems(linesProvider);
            linesGrid.setWidthFull();
            linesGrid.addComponentColumn(this::lineTypeField).setHeader("Тип").setAutoWidth(true).setFlexGrow(0);
            linesGrid.addComponentColumn(this::descriptionField).setHeader("Работа / материал").setFlexGrow(2);
            linesGrid.addComponentColumn(this::lineCommentField).setHeader("Комментарий").setFlexGrow(2);
            linesGrid.addComponentColumn(this::quantityField).setHeader("Кол-во").setAutoWidth(true).setFlexGrow(0);
            linesGrid.addComponentColumn(this::unitField).setHeader("Ед.").setAutoWidth(true).setFlexGrow(0);
            linesGrid.addComponentColumn(this::priceField).setHeader("Цена").setAutoWidth(true).setFlexGrow(0);
            linesGrid.addComponentColumn(draft -> new Div(formatMoney(draft.total()))).setHeader("Сумма").setAutoWidth(true).setFlexGrow(0);
            linesGrid.addComponentColumn(this::removeButton).setHeader("").setAutoWidth(true).setFlexGrow(0);
            linesGrid.setHeight("32rem");

            Button addLineButton = new Button("+ строка", event -> {
                lines.add(EstimateLineDraft.manual());
                refreshLines();
            });
            addLineButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

            Component reworkMarker = buildReworkMarkerPane();
            if (reworkMarker == null) {
                pane.add(header, commentField, new H4("Добавлено в смету"), linesGrid, addLineButton, totalLine);
            } else {
                pane.add(header, commentField, new H4("Добавлено в смету"), linesGrid, addLineButton, totalLine, reworkMarker);
            }
            pane.expand(linesGrid);
            return pane;
        }

        private Component buildReworkMarkerPane() {
            if (existingEstimate == null || existingEstimate.getId() == null) {
                return null;
            }
            RepairProcess process = repairProcessService.findByEstimateId(existingEstimate.getId());
            if (process == null || process.getId() == null) {
                return null;
            }
            List<RepairProcess> reworks = repairProcessService.loadProcessReworks(process.getId());
            if (reworks.isEmpty() && process.getStatus() != RepairProcessStatus.REWORK) {
                return null;
            }

            Div separator = new Div();
            separator.getStyle()
                    .set("height", "3px")
                    .set("background", "#dc2626")
                    .set("width", "100%")
                    .set("margin", "8px 0");

            VerticalLayout box = new VerticalLayout(separator, new H4("Доработки"));
            box.setPadding(false);
            box.setSpacing(true);
            box.setWidthFull();
            if (reworks.isEmpty()) {
                box.add(new Div("Доработка открыта, дополнительные задания еще не созданы."));
                return box;
            }
            for (RepairProcess rework : reworks) {
                Div row = new Div();
                row.getStyle()
                        .set("border", "1px solid #fecaca")
                        .set("border-radius", "8px")
                        .set("padding", "8px")
                        .set("background", "#fff7ed");
                String status = rework.getStatus() == null
                        ? "-"
                        : repairEstimateEnumLabel("dev.buhanzaz.wmspanel.entity/RepairProcessStatus." + rework.getStatus().name(), rework.getStatus().name());
                row.add(new Span(status + " · " + valueOr(rework.getComment())));
                box.add(row);
            }
            return box;
        }

        private Component buildCatalogPane() {
            VerticalLayout catalogPane = new VerticalLayout();
            catalogPane.setPadding(false);
            catalogPane.setSpacing(true);
            catalogPane.setWidthFull();
            catalogPane.setMaxWidth("100%");
            catalogPane.setMinWidth("0");
            catalogPane.getStyle()
                    .set("border", "1px solid #e2e8f0")
                    .set("border-radius", "8px")
                    .set("padding", "6px")
                    .set("gap", "0.25rem")
                    .set("align-self", "flex-start");

            catalogModeSelector.setLabel("Режим каталога");
            catalogModeSelector.setItems(CatalogMode.values());
            catalogModeSelector.setItemLabelGenerator(CatalogMode::label);
            catalogModeSelector.setPlaceholder("Выберите режим");
            catalogModeSelector.setValue(catalogMode);
            catalogModeSelector.setHelperText("Текущий режим: " + catalogMode.label());
            catalogModeSelector.setWidthFull();
            catalogModeSelector.setClearButtonVisible(false);
            catalogModeSelector.addValueChangeListener(event -> {
                if (event.getValue() != null) {
                    setCatalogMode(event.getValue());
                }
            });

            catalogPath.getStyle()
                    .set("min-height", "1rem")
                    .set("font-size", "0.85rem")
                    .set("line-height", "1.15");
            catalogGrid.getStyle()
                    .set("display", "grid")
                    .set("grid-template-columns", "repeat(4, minmax(0, 1fr))")
                    .set("grid-template-rows", "repeat(4, minmax(3.5rem, auto))")
                    .set("gap", "0.25rem")
                    .set("width", "100%")
                    .set("max-width", "100%")
                    .set("max-height", "18rem")
                    .set("overflow", "auto");

            catalogPane.add(new H4("Каталог"), catalogModeSelector, catalogPath, catalogGrid);
            return catalogPane;
        }

        private Component buildFooter() {
            HorizontalLayout footer = new HorizontalLayout();
            footer.setWidthFull();
            footer.setJustifyContentMode(FlexComponent.JustifyContentMode.END);

            Button cancel = new Button("Отмена", event -> close());
            Button save = new Button("Сохранить", event -> saveEstimate(false));
            save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            Button complete = new Button("Завершить", event -> saveEstimate(true));
            footer.add(cancel, complete, save);
            return footer;
        }

        private void initFields() {
            Warehouse warehouse = warehouseField.getValue();
            List<RentalItem> items = warehouse == null
                    ? List.of()
                    : dataManager.load(RentalItem.class)
                    .query("select e from RentalItem e where e.warehouse = :warehouse order by e.number")
                    .parameter("warehouse", warehouse)
                    .list();
            rentalItemField.setItems(items);
            rentalItemField.setItemLabelGenerator(RentalItem::getNumber);
            rentalItemField.setWidthFull();
            rentalItemField.setPlaceholder("Начните вводить номер");

            sourcePartyField.setWidthFull();
            destinationPartyField.setWidthFull();
            commentField.setMinHeight("5rem");

            if (existingEstimate == null) {
                dispatchDateField.setValue(LocalDate.now());
                return;
            }

            RentalItem matchingItem = items.stream()
                    .filter(item -> Objects.equals(item.getId(), existingEstimate.getRentalItem().getId()))
                    .findFirst()
                    .orElse(existingEstimate.getRentalItem());
            rentalItemField.setValue(matchingItem);
            sourcePartyField.setValue(valueOr(existingEstimate.getSourceParty()));
            destinationPartyField.setValue(valueOr(existingEstimate.getDestinationParty()));
            if (existingEstimate.getDispatchDate() != null) {
                dispatchDateField.setValue(existingEstimate.getDispatchDate().toLocalDate());
            }
            commentField.setValue(valueOr(existingEstimate.getComment()));
            for (RepairEstimateLine line : repairEstimateService.loadLines(existingEstimate.getId())) {
                lines.add(EstimateLineDraft.fromPersisted(line));
            }
            if (existingEstimate.getLatestEvent() != null) {
                repairEstimateService.loadPhotos(existingEstimate.getLatestEvent().getId()).forEach(photo ->
                        photoPreviews.add(new PhotoPreview(
                                photo.getId(),
                                valueOr(photo.getOriginalFileName()),
                                photo.getContentType(),
                                null,
                                repairEstimateService.mediaUrl(photo.getId(), RepairEstimateService.PhotoVariant.PREVIEW))));
            }
        }

        private void setCatalogMode(CatalogMode mode) {
            if (mode == null) {
                return;
            }
            if (catalogModeSelector.getValue() != mode) {
                catalogModeSelector.setValue(mode);
            }
            catalogMode = mode;
            catalogModeSelector.setHelperText("Текущий режим: " + mode.label());
            while (catalogFrames.size() > 1) {
                catalogFrames.removeLast();
            }
            catalogPage = 0;
            renderCatalogLevel();
        }

        private void renderCatalogLevel() {
            List<CatalogNode> visibleNodes = currentLevelNodes();
            int pageCount = pageCountFor(visibleNodes.size());
            catalogPage = Math.max(0, Math.min(catalogPage, pageCount - 1));
            int start = catalogPage * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, visibleNodes.size());
            List<CatalogNode> pageNodes = start >= visibleNodes.size() ? List.of() : visibleNodes.subList(start, end);

            catalogPath.setText(buildCatalogPathLabel(pageCount));
            catalogGrid.removeAll();

            for (int slot = 0; slot < 16; slot++) {
                catalogGrid.add(createCatalogSlotButton(slot, pageNodes, pageCount));
            }
        }

        private List<CatalogNode> currentLevelNodes() {
            CatalogFrame frame = currentFrame();
            if (frame == null || frame.node() == null) {
                return repairCatalogService.mainMenuNodes();
            }
            return switch (frame.kind()) {
                case TREE -> treeMenuNodes(frame.node());
                case RELATED_MATERIALS -> associatedMaterialNodes(frame.node().code());
                case MATERIAL_PICK -> reachableMaterialNodes(frame.node().code());
                case LOCATION_PICK -> locationNodes(frame.relatedNode() == null ? null : frame.relatedNode().code());
                case FOLLOW_UPS -> followUpMenuNodes(frame.node().code());
            };
        }

        private List<CatalogNode> treeMenuNodes(CatalogNode node) {
            if (node == null) {
                return repairCatalogService.mainMenuNodes();
            }
            if (node.type() == CatalogNodeType.OPTION) {
                return optionMenuNodes(node);
            }
            if (node.type() == CatalogNodeType.MATERIAL) {
                return actionableDescendants(node.code());
            }
            List<CatalogNode> children = treeChildren(node);
            if (catalogMode == CatalogMode.MATERIALS_ONLY) {
                return materialMenuNodes(children);
            }
            return standardMenuNodes(children);
        }

        private List<CatalogNode> treeChildren(CatalogNode node) {
            if (node == null) {
                return repairCatalogService.mainMenuNodes();
            }
            return navigableChildren(node.code());
        }

        private List<CatalogNode> followUpMenuNodes(String code) {
            return repairCatalogService.followUpNodes(code);
        }

        private List<CatalogNode> optionMenuNodes(CatalogNode node) {
            List<CatalogNode> result = new ArrayList<>();
            Set<String> seenCodes = new LinkedHashSet<>();
            for (CatalogNode child : navigableChildren(node.code())) {
                addUniqueNode(result, seenCodes, child);
            }
            for (CatalogNode followUp : followUpMenuNodes(node.code())) {
                addUniqueNode(result, seenCodes, followUp);
            }
            return result;
        }

        private List<CatalogNode> navigableChildren(String code) {
            List<CatalogNode> result = new ArrayList<>();
            Set<String> seenCodes = new LinkedHashSet<>();
            for (CatalogNode dependency : repairCatalogService.dependencyNodes(code)) {
                addUniqueNode(result, seenCodes, dependency);
            }
            for (CatalogNode followUp : repairCatalogService.followUpNodes(code)) {
                addUniqueNode(result, seenCodes, followUp);
            }
            return result;
        }

        private List<CatalogNode> standardMenuNodes(List<CatalogNode> children) {
            List<CatalogNode> result = new ArrayList<>();
            boolean hasWorkPath = children.stream().anyMatch(this::isWorkOrHasWorkDescendants);
            Set<String> seenCodes = new LinkedHashSet<>();
            for (CatalogNode child : children) {
                if (child == null) {
                    continue;
                }
                switch (child.type()) {
                    case CATEGORY, GROUP, SUBCATEGORY -> {
                        if (hasWorkDescendants(child.code()) || hasMaterialDescendants(child.code())) {
                            addUniqueNode(result, seenCodes, child);
                        }
                    }
                    case WORK -> addUniqueNode(result, seenCodes, child);
                    case MATERIAL -> {
                        if (!hasWorkPath) {
                            addUniqueNode(result, seenCodes, child);
                        }
                    }
                    case OPTION -> {
                        if (hasOptionMenu(child) || (!hasWorkPath && child.includeInEstimate())) {
                            addUniqueNode(result, seenCodes, child);
                        }
                    }
                    default -> {
                    }
                }
            }
            return result;
        }

        private List<CatalogNode> materialMenuNodes(List<CatalogNode> children) {
            List<CatalogNode> result = new ArrayList<>();
            Set<String> seenCodes = new LinkedHashSet<>();
            for (CatalogNode child : children) {
                if (child == null) {
                    continue;
                }
                switch (child.type()) {
                    case CATEGORY, GROUP, SUBCATEGORY -> {
                        if (hasMaterialDescendants(child.code())) {
                            addUniqueNode(result, seenCodes, child);
                        }
                    }
                    case WORK -> reachableMaterialNodes(child.code()).forEach(node -> addUniqueNode(result, seenCodes, node));
                    case MATERIAL -> addUniqueNode(result, seenCodes, child);
                    case OPTION -> {
                        if (hasOptionMenu(child) || hasMaterialDescendants(child.code())) {
                            addUniqueNode(result, seenCodes, child);
                        }
                    }
                    default -> {
                    }
                }
            }
            return result;
        }

        private List<CatalogNode> actionableDescendants(String code) {
            List<CatalogNode> result = new ArrayList<>();
            collectActionableDescendants(code, result, new LinkedHashSet<>(), new LinkedHashSet<>());
            return result;
        }

        private void collectActionableDescendants(String code, List<CatalogNode> result, Set<String> seenCodes, Set<String> visitedCodes) {
            if (code == null || !visitedCodes.add(code)) {
                return;
            }
            for (CatalogNode child : navigableChildren(code)) {
                if (child == null) {
                    continue;
                }
                switch (child.type()) {
                    case WORK, MATERIAL -> addUniqueNode(result, seenCodes, child);
                    case OPTION, LOCATION -> collectActionableDescendants(child.code(), result, seenCodes, visitedCodes);
                    case CATEGORY, GROUP, SUBCATEGORY -> collectActionableDescendants(child.code(), result, seenCodes, visitedCodes);
                    default -> {
                    }
                }
            }
        }

        private CatalogFrame currentFrame() {
            CatalogFrame frame = catalogFrames.peekLast();
            return frame == null ? CatalogFrame.root() : frame;
        }

        private boolean canStepBack() {
            return catalogFrames.size() > 1;
        }

        private boolean canGoHome() {
            return catalogFrames.size() > 1;
        }

        private void stepBack() {
            if (!canStepBack()) {
                return;
            }
            catalogFrames.removeLast();
            catalogPage = 0;
            renderCatalogLevel();
        }

        private void goHome() {
            if (!canGoHome()) {
                return;
            }
            while (catalogFrames.size() > 1) {
                catalogFrames.removeLast();
            }
            catalogPage = 0;
            renderCatalogLevel();
        }

        private String buildCatalogPathLabel(int pageCount) {
            List<String> pathParts = new ArrayList<>();
            pathParts.add("Главное меню");
            for (CatalogFrame frame : catalogFrames) {
                if (frame.node() == null) {
                    continue;
                }
                pathParts.add(frame.node().title());
                if (frame.kind() == CatalogMenuKind.RELATED_MATERIALS) {
                    pathParts.add("Связанные материалы");
                }
                if (frame.kind() == CatalogMenuKind.MATERIAL_PICK) {
                    pathParts.add("Материалы");
                }
                if (frame.kind() == CatalogMenuKind.LOCATION_PICK) {
                    pathParts.add("Расположение");
                }
                if (frame.kind() == CatalogMenuKind.FOLLOW_UPS) {
                    pathParts.add("Дополнительно");
                }
            }
            String path = String.join(" / ", pathParts);
            String pageLabel = pageCount > 1 ? " · Стр. " + (catalogPage + 1) + "/" + pageCount : "";
            return path + " · " + catalogMode.label() + pageLabel;
        }

        private boolean isWorkOrHasWorkDescendants(CatalogNode node) {
            if (node == null) {
                return false;
            }
            return switch (node.type()) {
                case WORK -> true;
                case CATEGORY, GROUP, SUBCATEGORY -> hasWorkDescendants(node.code());
                default -> false;
            };
        }

        private boolean hasWorkDescendants(String code) {
            return hasWorkDescendants(code, new LinkedHashSet<>());
        }

        private boolean hasWorkDescendants(String code, Set<String> visitedCodes) {
            if (code == null || !visitedCodes.add(code)) {
                return false;
            }
            for (CatalogNode child : navigableChildren(code)) {
                if (child.type() == CatalogNodeType.WORK) {
                    return true;
                }
                if ((child.type() == CatalogNodeType.CATEGORY
                        || child.type() == CatalogNodeType.GROUP
                        || child.type() == CatalogNodeType.SUBCATEGORY
                        || child.type() == CatalogNodeType.OPTION
                        || child.type() == CatalogNodeType.LOCATION) && hasWorkDescendants(child.code(), visitedCodes)) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasMaterialDescendants(String code) {
            return hasMaterialDescendants(code, new LinkedHashSet<>());
        }

        private boolean hasMaterialDescendants(String code, Set<String> visitedCodes) {
            if (code == null || !visitedCodes.add(code)) {
                return false;
            }
            if (!associatedMaterialNodes(code).isEmpty()) {
                return true;
            }
            for (CatalogNode child : navigableChildren(code)) {
                if (child.type() == CatalogNodeType.MATERIAL) {
                    return true;
                }
                if ((child.type() == CatalogNodeType.CATEGORY
                        || child.type() == CatalogNodeType.GROUP
                        || child.type() == CatalogNodeType.SUBCATEGORY
                        || child.type() == CatalogNodeType.OPTION
                        || child.type() == CatalogNodeType.LOCATION
                        || child.type() == CatalogNodeType.WORK) && hasMaterialDescendants(child.code(), visitedCodes)) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasOptionMenu(CatalogNode node) {
            return node != null && (!navigableChildren(node.code()).isEmpty() || !followUpMenuNodes(node.code()).isEmpty());
        }

        private void addUniqueNode(List<CatalogNode> result, Set<String> seenCodes, CatalogNode node) {
            if (node != null && seenCodes.add(node.code())) {
                result.add(node);
            }
        }

        private int pageCountFor(int itemCount) {
            return Math.max(1, (itemCount + PAGE_SIZE - 1) / PAGE_SIZE);
        }

        private int currentCatalogPageCount() {
            return pageCountFor(currentLevelNodes().size());
        }

        private List<CatalogNode> currentPageNodes() {
            List<CatalogNode> visibleNodes = currentLevelNodes();
            int pageCount = pageCountFor(visibleNodes.size());
            catalogPage = Math.max(0, Math.min(catalogPage, pageCount - 1));
            int start = catalogPage * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, visibleNodes.size());
            return start >= visibleNodes.size() ? List.of() : visibleNodes.subList(start, end);
        }

        private void previousCatalogPage() {
            if (catalogPage <= 0) {
                return;
            }
            catalogPage--;
            renderCatalogLevel();
        }

        private void nextCatalogPage() {
            int pageCount = currentCatalogPageCount();
            if (catalogPage >= pageCount - 1) {
                return;
            }
            catalogPage++;
            renderCatalogLevel();
        }

        private void selectCatalogHotkey(int itemIndex) {
            if (itemIndex < 0) {
                return;
            }
            List<CatalogNode> pageNodes = currentPageNodes();
            if (itemIndex >= pageNodes.size()) {
                return;
            }
            onCatalogNodeClick(pageNodes.get(itemIndex));
        }

        private Component createCatalogSlotButton(int slot, List<CatalogNode> pageNodes, int pageCount) {
            Button button = switch (slot) {
                case 0 -> createCatalogItemButton("7", pageNodes, 0);
                case 1 -> createCatalogItemButton("8", pageNodes, 1);
                case 2 -> createCatalogItemButton("9", pageNodes, 2);
                case 3 -> createPageButton("/ ←\nНазад стр.", catalogPage > 0, event -> previousCatalogPage());
                case 4 -> createCatalogItemButton("4", pageNodes, 3);
                case 5 -> createCatalogItemButton("5", pageNodes, 4);
                case 6 -> createCatalogItemButton("6", pageNodes, 5);
                case 7 -> createPageButton("* →\nСлед. стр.", catalogPage < pageCount - 1, event -> nextCatalogPage());
                case 8 -> createCatalogItemButton("1", pageNodes, 6);
                case 9 -> createCatalogItemButton("2", pageNodes, 7);
                case 10 -> createCatalogItemButton("3", pageNodes, 8);
                case 12 -> createPageButton("0\nГлавное меню", canGoHome(), event -> goHome());
                case 13 -> createPageButton(",\nШаг назад", canStepBack(), event -> stepBack());
                default -> createEmptySlotButton();
            };
            button.setWidthFull();
            button.setHeightFull();
            return button;
        }

        private Button createCatalogItemButton(String hotkey, List<CatalogNode> pageNodes, int itemIndex) {
            if (itemIndex >= pageNodes.size()) {
                return createEmptySlotButton();
            }
            CatalogNode node = pageNodes.get(itemIndex);
            Button button = new Button(hotkey + "\n" + shorten(node.shortTitle()));
            button.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            button.getStyle()
                    .set("min-width", "0")
                    .set("white-space", "pre-line")
                    .set("text-align", "center")
                    .set("line-height", "1.05")
                    .set("padding", "0.35rem")
                    .set("font-size", "0.85rem")
                    .set("overflow", "hidden")
                    .set("text-overflow", "ellipsis");
            button.getElement().setProperty("title", node.title());
            button.addClickListener(event -> onCatalogNodeClick(node));
            return button;
        }

        private Button createPageButton(String label, boolean enabled, ComponentEventListener<ClickEvent<Button>> listener) {
            Button button = new Button(label);
            button.getStyle()
                    .set("min-width", "0")
                    .set("white-space", "pre-line")
                    .set("text-align", "center")
                    .set("line-height", "1.05")
                    .set("padding", "0.35rem")
                    .set("font-size", "0.82rem")
                    .set("overflow", "hidden")
                    .set("text-overflow", "ellipsis");
            if (enabled) {
                button.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
                button.addClickListener(listener);
            } else {
                button.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
                button.setEnabled(false);
            }
            return button;
        }

        private Button createEmptySlotButton() {
            Button button = new Button("");
            button.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            button.setEnabled(false);
            button.getElement().setProperty("title", "");
            return button;
        }

        @ClientCallable
        private void handleCatalogShortcut(String action) {
            if (action == null || action.isBlank()) {
                return;
            }
            switch (action) {
                case "slot-7" -> selectCatalogHotkey(0);
                case "slot-8" -> selectCatalogHotkey(1);
                case "slot-9" -> selectCatalogHotkey(2);
                case "slot-4" -> selectCatalogHotkey(3);
                case "slot-5" -> selectCatalogHotkey(4);
                case "slot-6" -> selectCatalogHotkey(5);
                case "slot-1" -> selectCatalogHotkey(6);
                case "slot-2" -> selectCatalogHotkey(7);
                case "slot-3" -> selectCatalogHotkey(8);
                case "go-home" -> goHome();
                case "step-back" -> stepBack();
                case "page-left" -> previousCatalogPage();
                case "page-right" -> nextCatalogPage();
                default -> {
                }
            }
        }

        private void installCatalogShortcutListener() {
            getElement().executeJs("""
                    if (this.__repairEstimateCatalogShortcutListener) {
                      document.removeEventListener('keydown', this.__repairEstimateCatalogShortcutListener, true);
                    }
                    const ownerId = this.__repairEstimateCatalogOwnerId
                      || `repair-estimate-${Date.now()}-${Math.random().toString(36).slice(2)}`;
                    this.__repairEstimateCatalogOwnerId = ownerId;
                    const openedOverlays = () => Array.from(document.querySelectorAll('vaadin-dialog-overlay'))
                      .filter((candidate) => candidate && candidate.opened);
                    const activeElement = () => {
                      const rootActive = document.activeElement?.shadowRoot?.activeElement;
                      return rootActive || document.activeElement;
                    };
                    const resolveOverlay = () => {
                      const known = document.querySelector(`vaadin-dialog-overlay[data-repair-estimate-owner="${ownerId}"]`);
                      if (known) {
                        this.__repairEstimateCatalogOverlay = known;
                        return known;
                      }
                      const direct = this.$?.overlay
                        || this.shadowRoot?.querySelector('vaadin-dialog-overlay')
                        || this.shadowRoot?.querySelector('[part="overlay"]');
                      if (direct) {
                        direct.dataset.repairEstimateOwner = ownerId;
                        this.__repairEstimateCatalogOverlay = direct;
                        return direct;
                      }
                      const currentActive = activeElement();
                      const activeOverlay = currentActive?.closest?.('vaadin-dialog-overlay');
                      if (activeOverlay && activeOverlay.opened) {
                        activeOverlay.dataset.repairEstimateOwner = ownerId;
                        this.__repairEstimateCatalogOverlay = activeOverlay;
                        return activeOverlay;
                      }
                      const topmost = openedOverlays().at(-1) || null;
                      if (topmost && openedOverlays().length === 1) {
                        topmost.dataset.repairEstimateOwner = ownerId;
                        this.__repairEstimateCatalogOverlay = topmost;
                        return topmost;
                      }
                      return this.__repairEstimateCatalogOverlay || null;
                    };
                    const isScopedToCurrentDialog = (event) => {
                      if (!this.opened) {
                        return false;
                      }
                      const overlay = resolveOverlay();
                      if (!overlay || !overlay.opened) {
                        return false;
                      }
                      const topmost = openedOverlays().at(-1);
                      if (topmost && topmost !== overlay) {
                        return false;
                      }
                      const path = typeof event.composedPath === 'function' ? event.composedPath() : [event.target];
                      const currentActive = activeElement();
                      const activePath = currentActive && typeof currentActive.composedPath === 'function'
                        ? currentActive.composedPath()
                        : [currentActive];
                      return path.includes(overlay)
                        || activePath.includes(overlay)
                        || (event.target instanceof Node && overlay.contains(event.target))
                        || (currentActive instanceof Node && overlay.contains(currentActive));
                    };
                    const isEditable = (element) => {
                      if (!element) {
                        return false;
                      }
                      if (element.isContentEditable) {
                        return true;
                      }
                      const tagName = element.tagName ? element.tagName.toLowerCase() : '';
                      if (tagName === 'input' || tagName === 'textarea' || tagName === 'select') {
                        return true;
                      }
                      return tagName === 'vaadin-text-field'
                        || tagName === 'vaadin-text-area'
                        || tagName === 'vaadin-integer-field'
                        || tagName === 'vaadin-number-field'
                        || tagName === 'vaadin-big-decimal-field'
                        || tagName === 'vaadin-combo-box'
                        || tagName === 'vaadin-date-picker';
                    };
                    this.__repairEstimateCatalogShortcutListener = (event) => {
                      if (!isScopedToCurrentDialog(event)) {
                        return;
                      }
                      const path = typeof event.composedPath === 'function' ? event.composedPath() : [event.target];
                      if (path.some(isEditable)) {
                        return;
                      }
                      if (event.ctrlKey || event.altKey || event.metaKey) {
                        return;
                      }
                      let action = null;
                      if (event.code === 'NumpadMultiply' || event.key === '*') {
                        action = 'page-right';
                      } else if (event.code === 'NumpadDivide' || (!event.shiftKey && event.key === '/')) {
                        action = 'page-left';
                      } else if (event.code === 'NumpadDecimal' || event.code === 'NumpadComma' || (!event.shiftKey && event.key === ',')) {
                        action = 'step-back';
                      } else if (!event.shiftKey) {
                        switch (event.code) {
                          case 'Digit7':
                          case 'Numpad7':
                            action = 'slot-7';
                            break;
                          case 'Digit8':
                          case 'Numpad8':
                            action = 'slot-8';
                            break;
                          case 'Digit9':
                          case 'Numpad9':
                            action = 'slot-9';
                            break;
                          case 'Digit4':
                          case 'Numpad4':
                            action = 'slot-4';
                            break;
                          case 'Digit5':
                          case 'Numpad5':
                            action = 'slot-5';
                            break;
                          case 'Digit6':
                          case 'Numpad6':
                            action = 'slot-6';
                            break;
                          case 'Digit1':
                          case 'Numpad1':
                            action = 'slot-1';
                            break;
                          case 'Digit2':
                          case 'Numpad2':
                            action = 'slot-2';
                            break;
                          case 'Digit3':
                          case 'Numpad3':
                            action = 'slot-3';
                            break;
                          case 'Digit0':
                          case 'Numpad0':
                            action = 'go-home';
                            break;
                          default:
                            break;
                        }
                      }
                      if (!action) {
                        return;
                      }
                      event.preventDefault();
                      event.stopPropagation();
                      this.$server.handleCatalogShortcut(action);
                    };
                    resolveOverlay();
                    document.addEventListener('keydown', this.__repairEstimateCatalogShortcutListener, true);
                    """);
        }

        private void removeCatalogShortcutListener() {
            getElement().executeJs("""
                    if (this.__repairEstimateCatalogShortcutListener) {
                      document.removeEventListener('keydown', this.__repairEstimateCatalogShortcutListener, true);
                    }
                    const ownerId = this.__repairEstimateCatalogOwnerId;
                    if (ownerId) {
                      const overlay = document.querySelector(`vaadin-dialog-overlay[data-repair-estimate-owner="${ownerId}"]`);
                      if (overlay) {
                        delete overlay.dataset.repairEstimateOwner;
                      }
                    }
                    this.__repairEstimateCatalogOverlay = null;
                    this.__repairEstimateCatalogShortcutListener = null;
                    """);
        }

        private void onCatalogNodeClick(CatalogNode node) {
            if (node == null) {
                return;
            }
            if (node.type() == CatalogNodeType.CATEGORY
                    || node.type() == CatalogNodeType.GROUP
                    || node.type() == CatalogNodeType.SUBCATEGORY) {
                catalogFrames.addLast(CatalogFrame.tree(node));
                catalogPage = 0;
                renderCatalogLevel();
                return;
            }
            if (node.type() == CatalogNodeType.OPTION && hasOptionMenu(node)) {
                catalogFrames.addLast(CatalogFrame.tree(node));
                catalogPage = 0;
                renderCatalogLevel();
                return;
            }
            if (node.type() == CatalogNodeType.OPTION && !node.includeInEstimate()) {
                notifyInfo("Опция не добавляется в смету напрямую");
                return;
            }
            if (node.type() == CatalogNodeType.LOCATION) {
                onLocationNodeSelected(node);
                return;
            }
            if (node.type() != CatalogNodeType.WORK
                    && node.type() != CatalogNodeType.MATERIAL
                    && node.type() != CatalogNodeType.OPTION) {
                notifyInfo("Элемент нельзя добавить в смету");
                return;
            }
            if (node.type() == CatalogNodeType.WORK) {
                onWorkNodeSelected(node);
                return;
            }
            onMaterialNodeSelected(node);
        }

        private void onWorkNodeSelected(CatalogNode node) {
            if (catalogMode == CatalogMode.MATERIALS_ONLY) {
                List<CatalogNode> materials = reachableMaterialNodes(node.code());
                if (!materials.isEmpty()) {
                    catalogFrames.addLast(CatalogFrame.materialPick(node));
                    catalogPage = 0;
                    renderCatalogLevel();
                    return;
                }
                notifyInfo("Для этой работы не настроены материалы");
                return;
            }
            List<CatalogNode> materials = catalogMode == CatalogMode.LINKED_SET
                    ? associatedMaterialNodes(node.code())
                    : List.of();
            if (!materials.isEmpty()) {
                catalogFrames.addLast(CatalogFrame.related(node));
                catalogPage = 0;
                renderCatalogLevel();
                return;
            }
            openCatalogAddDialog(node, selection -> {
                CatalogApplySummary summary = CatalogApplySummary.none();
                if (node.includeInEstimate()) {
                    summary = summary.plus(addOrMergeCatalogLine(node, selection.quantity(), selection.comment()));
                }
                refreshLines();
                notifyCatalogApplySummary(summary);
                if (!openContinuationMenuIfNeeded(node)) {
                    openFollowUpMenuIfNeeded(node);
                }
            });
        }

        private void onMaterialNodeSelected(CatalogNode node) {
            CatalogFrame frame = currentFrame();
            CatalogNode pendingWork = frame.kind() == CatalogMenuKind.RELATED_MATERIALS ? frame.node() : null;
            if (pendingWork != null && catalogMode == CatalogMode.LINKED_SET && !locationNodes(node.code()).isEmpty()) {
                catalogFrames.addLast(CatalogFrame.locationPick(pendingWork, node));
                catalogPage = 0;
                renderCatalogLevel();
                return;
            }
            openCatalogAddDialog(node, selection -> {
                CatalogApplySummary summary = CatalogApplySummary.none();
                if (pendingWork != null) {
                    summary = summary.plus(addOrMergeCatalogLine(pendingWork, selection.quantity(), selection.comment()));
                } else if (catalogMode == CatalogMode.LINKED_SET) {
                    summary = summary.plus(addLinkedWorkLines(node.code(), selection.quantity(), selection.comment()));
                }
                summary = summary.plus(addOrMergeCatalogLine(node, selection.quantity(), selection.comment()));
                refreshLines();
                notifyCatalogApplySummary(summary);

                if (openFollowUpMenuIfNeeded(node)) {
                    return;
                }
                if (pendingWork != null && currentFrame().kind() == CatalogMenuKind.RELATED_MATERIALS) {
                    stepBack();
                } else {
                    renderCatalogLevel();
                }
            });
        }

        private void onLocationNodeSelected(CatalogNode location) {
            CatalogFrame frame = currentFrame();
            if (frame.kind() != CatalogMenuKind.LOCATION_PICK || frame.node() == null || frame.relatedNode() == null) {
                notifyInfo("Расположение выбирается после связанного материала");
                return;
            }
            CatalogNode pendingWork = frame.node();
            CatalogNode pendingMaterial = frame.relatedNode();
            openCatalogAddDialog(pendingMaterial, selection -> {
                CatalogApplySummary summary = CatalogApplySummary.none();
                summary = summary.plus(addOrMergeCatalogLine(pendingWork, selection.quantity(), selection.comment(), location.title()));
                summary = summary.plus(addOrMergeCatalogLine(pendingMaterial, selection.quantity(), selection.comment()));
                refreshLines();
                notifyCatalogApplySummary(summary);
                closeLocationAndMaterialFrames();
            });
        }

        private List<CatalogNode> associatedMaterialNodes(String code) {
            List<CatalogNode> result = new ArrayList<>();
            Set<String> seenCodes = new LinkedHashSet<>();
            for (CatalogNode dependency : repairCatalogService.dependencyRelatedNodes(code)) {
                if (dependency.type() == CatalogNodeType.MATERIAL && seenCodes.add(dependency.code())) {
                    result.add(dependency);
                }
            }
            return result;
        }

        private List<CatalogNode> locationNodes(String code) {
            if (code == null) {
                return List.of();
            }
            List<CatalogNode> result = new ArrayList<>();
            Set<String> seenCodes = new LinkedHashSet<>();
            for (CatalogNode child : navigableChildren(code)) {
                if (child != null && child.type() == CatalogNodeType.LOCATION) {
                    addUniqueNode(result, seenCodes, child);
                }
            }
            return result;
        }

        private List<CatalogNode> reachableMaterialNodes(String code) {
            List<CatalogNode> result = new ArrayList<>();
            collectReachableMaterialNodes(code, result, new LinkedHashSet<>(), new LinkedHashSet<>());
            return result;
        }

        private void collectReachableMaterialNodes(String code,
                                                   List<CatalogNode> result,
                                                   Set<String> seenCodes,
                                                   Set<String> visitedCodes) {
            if (code == null || !visitedCodes.add(code)) {
                return;
            }
            for (CatalogNode material : associatedMaterialNodes(code)) {
                addUniqueNode(result, seenCodes, material);
            }
            for (CatalogNode child : navigableChildren(code)) {
                if (child == null) {
                    continue;
                }
                if (child.type() == CatalogNodeType.MATERIAL) {
                    addUniqueNode(result, seenCodes, child);
                    continue;
                }
                if (child.type() == CatalogNodeType.CATEGORY
                        || child.type() == CatalogNodeType.GROUP
                        || child.type() == CatalogNodeType.SUBCATEGORY
                        || child.type() == CatalogNodeType.OPTION
                        || child.type() == CatalogNodeType.LOCATION
                        || child.type() == CatalogNodeType.WORK) {
                    collectReachableMaterialNodes(child.code(), result, seenCodes, visitedCodes);
                }
            }
        }

        private List<CatalogNode> workContinuationNodes(CatalogNode node) {
            if (node == null) {
                return List.of();
            }
            List<CatalogNode> result = new ArrayList<>();
            Set<String> seenCodes = new LinkedHashSet<>();
            for (CatalogNode child : navigableChildren(node.code())) {
                if (child == null || child.type() == CatalogNodeType.MATERIAL) {
                    continue;
                }
                if (child.type() == CatalogNodeType.CATEGORY
                        || child.type() == CatalogNodeType.GROUP
                        || child.type() == CatalogNodeType.SUBCATEGORY
                        || child.type() == CatalogNodeType.OPTION
                        || child.type() == CatalogNodeType.LOCATION
                        || child.type() == CatalogNodeType.WORK) {
                    addUniqueNode(result, seenCodes, child);
                }
            }
            return result;
        }

        private List<CatalogNode> linkedWorkNodes(String code) {
            List<CatalogNode> result = new ArrayList<>();
            Set<String> seenCodes = new LinkedHashSet<>();
            for (CatalogNode dependency : repairCatalogService.dependencyRelatedNodes(code)) {
                if (dependency.type() == CatalogNodeType.WORK && seenCodes.add(dependency.code())) {
                    result.add(dependency);
                }
            }
            return result;
        }

        private boolean openContinuationMenuIfNeeded(CatalogNode node) {
            if (node == null) {
                return false;
            }
            List<CatalogNode> continuations = workContinuationNodes(node);
            if (continuations.isEmpty()) {
                return false;
            }
            catalogFrames.addLast(CatalogFrame.tree(node));
            catalogPage = 0;
            renderCatalogLevel();
            return true;
        }

        private boolean openFollowUpMenuIfNeeded(CatalogNode node) {
            if (node == null) {
                return false;
            }
            List<CatalogNode> followUps = followUpMenuNodes(node.code());
            if (followUps.isEmpty()) {
                return false;
            }
            catalogFrames.addLast(CatalogFrame.followUps(node));
            catalogPage = 0;
            renderCatalogLevel();
            return true;
        }

        private void closeLocationAndMaterialFrames() {
            if (currentFrame().kind() == CatalogMenuKind.LOCATION_PICK) {
                catalogFrames.removeLast();
            }
            if (currentFrame().kind() == CatalogMenuKind.RELATED_MATERIALS) {
                catalogFrames.removeLast();
            }
            catalogPage = 0;
            renderCatalogLevel();
        }

        private void openCatalogAddDialog(CatalogNode node, Consumer<CatalogAddSelection> onConfirm) {
            Dialog dialog = new Dialog();
            dialog.setHeaderTitle(node.type() == CatalogNodeType.MATERIAL || node.type() == CatalogNodeType.OPTION
                    ? "Добавить материал"
                    : "Добавить работу");
            dialog.setWidth("28rem");
            dialog.setMaxWidth("92vw");

            VerticalLayout content = new VerticalLayout();
            content.setPadding(false);
            content.setSpacing(true);

            Div title = new Div();
            title.setText(node.title());

            IntegerField quantityField = new IntegerField("Количество");
            quantityField.setMin(1);
            quantityField.setStepButtonsVisible(true);
            quantityField.setValue(node.defaultQuantity() == null || node.defaultQuantity() < 1 ? 1 : node.defaultQuantity());
            quantityField.setWidthFull();

            TextArea lineCommentField = new TextArea("Комментарий");
            lineCommentField.setWidthFull();
            lineCommentField.setMinHeight("6rem");

            HorizontalLayout actions = new HorizontalLayout();
            actions.setWidthFull();
            actions.setJustifyContentMode(FlexComponent.JustifyContentMode.END);

            Button cancel = new Button("Отмена", event -> dialog.close());
            Button confirm = new Button("Добавить", event -> {
                int quantity = quantityField.getValue() == null || quantityField.getValue() < 1 ? 1 : quantityField.getValue();
                onConfirm.accept(new CatalogAddSelection(quantity, lineCommentField.getValue()));
                dialog.close();
            });
            confirm.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

            actions.add(cancel, confirm);
            content.add(title, quantityField, lineCommentField, actions);
            dialog.add(content);
            dialog.open();
        }

        private CatalogApplySummary addOrMergeCatalogLine(CatalogNode node, int quantity, String comment) {
            return addOrMergeCatalogLine(node, quantity, comment, null);
        }

        private CatalogApplySummary addOrMergeCatalogLine(CatalogNode node, int quantity, String comment, String titleSuffix) {
            if (node == null) {
                return CatalogApplySummary.none();
            }
            if (!node.includeInEstimate()) {
                return CatalogApplySummary.none();
            }
            String description = lineDescription(node, titleSuffix);
            EstimateLineDraft existing = findDraftByCodeAndDescription(node.code(), description);
            if (existing != null) {
                existing.setQuantity(existing.quantity() + Math.max(1, quantity));
                existing.setLineComment(mergeComments(existing.lineComment(), comment));
                return CatalogApplySummary.mergedChange();
            }
            lines.add(new EstimateLineDraft(
                    UUID.randomUUID().toString(),
                    node.code(),
                    lineTypeFor(node),
                    description,
                    comment,
                    node.unit() == null || node.unit().isBlank() ? "ед" : node.unit(),
                    Math.max(1, quantity),
                    node.unitPrice() == null ? BigDecimal.ZERO : node.unitPrice()));
            return CatalogApplySummary.addedChange();
        }

        private CatalogApplySummary addLinkedWorkLines(String code, int quantity, String comment) {
            CatalogApplySummary summary = CatalogApplySummary.none();
            for (CatalogNode linkedWork : linkedWorkNodes(code)) {
                summary = summary.plus(addOrMergeCatalogLine(linkedWork, quantity, comment));
            }
            return summary;
        }

        private String lineDescription(CatalogNode node, String titleSuffix) {
            String base = node == null ? "" : safeString(node.title());
            String suffix = safeString(titleSuffix);
            return suffix.isBlank() ? base : base + " " + suffix;
        }

        private String safeString(String value) {
            return value == null ? "" : value.trim();
        }

        private EstimateLineDraft findDraftByCode(String catalogCode) {
            if (catalogCode == null || catalogCode.isBlank()) {
                return null;
            }
            return lines.stream()
                    .filter(line -> catalogCode.equals(line.catalogCode()))
                    .findFirst()
                    .orElse(null);
        }

        private EstimateLineDraft findDraftByCodeAndDescription(String catalogCode, String description) {
            if (catalogCode == null || catalogCode.isBlank()) {
                return null;
            }
            String expectedDescription = safeString(description);
            return lines.stream()
                    .filter(line -> catalogCode.equals(line.catalogCode()))
                    .filter(line -> expectedDescription.equals(safeString(line.description())))
                    .findFirst()
                    .orElse(null);
        }

        private String mergeComments(String current, String incoming) {
            String currentValue = current == null ? "" : current.trim();
            String incomingValue = incoming == null ? "" : incoming.trim();
            if (incomingValue.isBlank()) {
                return currentValue;
            }
            if (currentValue.isBlank()) {
                return incomingValue;
            }
            if (currentValue.contains(incomingValue)) {
                return currentValue;
            }
            return currentValue + "; " + incomingValue;
        }

        private void notifyCatalogApplySummary(CatalogApplySummary summary) {
            if (summary.added() > 0 && summary.merged() > 0) {
                notifyInfo("Позиции добавлены, повторные объединены по количеству");
                return;
            }
            if (summary.added() > 0) {
                notifyInfo(summary.added() > 1 ? "Позиции добавлены в смету" : "Позиция добавлена в смету");
                return;
            }
            if (summary.merged() > 0) {
                notifyInfo("Количество существующей позиции увеличено");
            }
        }

        private RepairEstimateLineType lineTypeFor(CatalogNode node) {
            return node.type() == CatalogNodeType.MATERIAL || node.type() == CatalogNodeType.OPTION
                    ? RepairEstimateLineType.MATERIAL
                    : RepairEstimateLineType.WORK;
        }

        private void refreshLines() {
            linesProvider.refreshAll();
            BigDecimal total = lines.stream()
                    .map(EstimateLineDraft::total)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            totalLine.setText("Итого: " + formatMoney(total));
        }

        private Component lineTypeField(EstimateLineDraft draft) {
            ComboBox<RepairEstimateLineType> field = new ComboBox<>();
            field.setItems(RepairEstimateLineType.values());
            field.setItemLabelGenerator(RepairEstimateView.this::repairEstimateLineTypeLabel);
            field.setValue(draft.lineType());
            field.addValueChangeListener(event -> {
                draft.setLineType(event.getValue());
                refreshLines();
            });
            field.setWidth("8rem");
            return field;
        }

        private Component descriptionField(EstimateLineDraft draft) {
            TextField field = new TextField();
            field.setValue(draft.description());
            field.setWidthFull();
            field.addValueChangeListener(event -> draft.setDescription(event.getValue()));
            return field;
        }

        private Component lineCommentField(EstimateLineDraft draft) {
            TextArea field = new TextArea();
            field.setValue(draft.lineComment());
            field.setWidthFull();
            field.setMinHeight("4.5rem");
            field.addValueChangeListener(event -> draft.setLineComment(event.getValue()));
            return field;
        }

        private Component quantityField(EstimateLineDraft draft) {
            IntegerField field = new IntegerField();
            field.setValue(draft.quantity());
            field.setMin(1);
            field.setWidth("6rem");
            field.addValueChangeListener(event -> {
                draft.setQuantity(event.getValue() == null ? 1 : event.getValue());
                refreshLines();
            });
            return field;
        }

        private Component unitField(EstimateLineDraft draft) {
            TextField field = new TextField();
            field.setValue(draft.unit());
            field.setWidth("6rem");
            field.addValueChangeListener(event -> draft.setUnit(event.getValue()));
            return field;
        }

        private Component priceField(EstimateLineDraft draft) {
            BigDecimalField field = new BigDecimalField();
            field.setValue(draft.unitPrice());
            field.setWidth("8rem");
            field.addValueChangeListener(event -> {
                draft.setUnitPrice(event.getValue() == null ? BigDecimal.ZERO : event.getValue());
                refreshLines();
            });
            return field;
        }

        private Component removeButton(EstimateLineDraft draft) {
            Button button = new Button("x", event -> {
                lines.remove(draft);
                refreshLines();
            });
            button.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
            return button;
        }

        private void saveEstimate(boolean completed) {
            if (completed) {
                openCompletionModeDialog();
                return;
            }
            persistEstimate(false, CompletionMode.MANUAL, false);
        }

        private void persistEstimate(boolean completed, CompletionMode completionMode, boolean movementRequired) {
            if (rentalItemField.getValue() == null) {
                notifyError("Выберите бытовку");
                return;
            }
            try {
                List<RepairEstimateService.EstimateLineCommand> commands = lines.stream()
                        .map(draft -> new RepairEstimateService.EstimateLineCommand(
                                draft.sourceLineKey(),
                                draft.lineType(),
                                draft.description(),
                                draft.lineComment(),
                                draft.unit(),
                                draft.quantity(),
                                draft.unitPrice(),
                                draft.catalogCode()))
                        .toList();
                List<RepairEstimateService.UploadedPhoto> uploads = photoPreviews.stream()
                        .filter(photo -> photo.photoId() == null && photo.bytes() != null)
                        .map(photo -> new RepairEstimateService.UploadedPhoto(
                                photo.fileName(),
                                photo.contentType(),
                                new ByteArrayInputStream(photo.bytes())))
                        .toList();
                RepairEstimate savedEstimate = repairEstimateService.saveEstimate(new RepairEstimateService.SaveEstimateCommand(
                        existingEstimate == null ? null : existingEstimate.getId(),
                        rentalItemField.getValue().getId(),
                        sourcePartyField.getValue(),
                        destinationPartyField.getValue(),
                        commentField.getValue(),
                        dispatchDateField.getValue() == null ? null : dispatchDateField.getValue().atStartOfDay().atOffset(ZoneOffset.ofHours(3)),
                        completed ? RepairEstimateStatus.COMPLETED : RepairEstimateStatus.DRAFT,
                        commands,
                        uploads,
                        new LinkedHashSet<>(removedPhotoIds)));
                switchEstimateStatusTab(completed ? RepairEstimateStatus.COMPLETED : RepairEstimateStatus.DRAFT);
                loadData();
                if (completed) {
                    List<RepairEstimateTaskPlan> plans = repairEstimateTaskPlanService.prepareTaskPlans(savedEstimate.getId(), movementRequired);
                    if (plans.isEmpty()) {
                        if (movementRequired) {
                            repairEstimateTaskPlanGenerationService.generateTasks(savedEstimate.getId(), plans);
                            if (completionMode == CompletionMode.AUTO) {
                                notifyInfo(repairEstimateMessage("completeModeDialog.autoCompleted"));
                            } else {
                                notifyInfo(repairEstimateMessage("completeModeDialog.completed"));
                            }
                            close();
                            return;
                        }
                        notifyInfo(repairEstimateMessage("completeModeDialog.completed"));
                        close();
                        return;
                    }
                    if (completionMode == CompletionMode.AUTO) {
                        repairEstimateTaskPlanGenerationService.generateTasks(savedEstimate.getId(), plans);
                        notifyInfo(repairEstimateMessage("completeModeDialog.autoCompleted"));
                        close();
                        return;
                    }
                    notifyInfo(repairEstimateMessage("completeModeDialog.completed"));
                    openTaskPlanDialog(savedEstimate, plans);
                    return;
                }
                notifyInfo("Смета сохранена");
                close();
            } catch (Exception ex) {
                notifyError(ex.getMessage() == null ? "Не удалось сохранить смету" : ex.getMessage());
            }
        }

        private void openCompletionModeDialog() {
            Dialog dialog = new Dialog();
            dialog.setHeaderTitle(repairEstimateMessage("completeModeDialog.title"));
            dialog.setWidth("34rem");
            dialog.setMaxWidth("none");

            RadioButtonGroup<CompletionMode> modeField = new RadioButtonGroup<>();
            modeField.setLabel(repairEstimateMessage("completeModeDialog.description"));
            modeField.setItems(CompletionMode.values());
            modeField.setItemLabelGenerator(mode -> mode.label(this::repairEstimateMessage));
            modeField.addThemeVariants(RadioGroupVariant.LUMO_VERTICAL);
            modeField.setValue(CompletionMode.MANUAL);
            modeField.setWidthFull();

            Checkbox movementField = new Checkbox("Создать перемещение на ремонт и возврат");
            movementField.setValue(true);
            Div movementHint = new Div();
            movementHint.setText("Будут созданы два отдельных задания: на ремонт и обратное перемещение. Обратное можно отменить позже.");
            movementHint.getStyle().set("font-size", "12px").set("color", "#64748b");

            Button cancel = new Button(repairEstimateMessage("completeModeDialog.cancel"), event -> dialog.close());
            Button proceed = new Button(repairEstimateMessage("completeModeDialog.confirm"), event -> {
                CompletionMode selectedMode = modeField.getValue() == null ? CompletionMode.MANUAL : modeField.getValue();
                if (selectedMode == CompletionMode.AUTO) {
                    List<String> issues = validateAutoCompletion();
                    if (!issues.isEmpty()) {
                        notifyError(joinIssues(issues));
                        return;
                    }
                }
                dialog.close();
                persistEstimate(true, selectedMode, movementField.getValue());
            });
            proceed.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

            HorizontalLayout footer = new HorizontalLayout(cancel, proceed);
            footer.setWidthFull();
            footer.setJustifyContentMode(FlexComponent.JustifyContentMode.END);

            VerticalLayout content = new VerticalLayout(modeField, movementField, movementHint, footer);
            content.setPadding(false);
            content.setSpacing(true);
            content.setWidthFull();
            dialog.add(content);
            dialog.open();
        }

        private List<String> validateAutoCompletion() {
            List<String> issues = new ArrayList<>();
            Warehouse warehouse = warehouseField.getValue();
            if (warehouse == null) {
                issues.add(repairEstimateMessage("completeModeDialog.missingWarehouse"));
                return issues;
            }
            for (int index = 0; index < lines.size(); index++) {
                EstimateLineDraft draft = lines.get(index);
                if (draft.lineType() != RepairEstimateLineType.WORK) {
                    continue;
                }
                String lineLabel = formatLineLabel(index, draft);
                RepairEstimateCatalogNode catalogNode = repairEstimateTaskPlanService.resolveCatalogNode(draft.catalogCode());
                if (catalogNode == null) {
                    issues.add(lineLabel + ": " + repairEstimateMessage("completeModeDialog.missingCatalogNode"));
                    continue;
                }
                if (!repairEstimateTaskPlanService.hasEffectiveQueueBinding(catalogNode)) {
                    issues.add(lineLabel + ": " + repairEstimateMessage("completeModeDialog.missingQueue"));
                } else if (repairEstimateTaskPlanService.resolveDefaultQueue(warehouse, catalogNode) == null) {
                    issues.add(lineLabel + ": " + repairEstimateMessage("completeModeDialog.missingQueue"));
                }
            }
            return issues;
        }

    private String repairEstimateMessage(String suffix) {
        for (String key : List.of(
                "RepairEstimateView." + suffix,
                "dev.buhanzaz.wmspanel.view.repairestimate/RepairEstimateView." + suffix,
                suffix)) {
            String value = messages.getMessage("dev.buhanzaz.wmspanel", key);
            if (value != null && !value.isBlank() && !Objects.equals(value, key)) {
                return value;
            }
        }
        return suffix;
    }

    private String formatLineLabel(int index, EstimateLineDraft draft) {
        String label = shorten(draft.description());
        if (label.isBlank()) {
            label = shorten(draft.catalogCode());
            }
            if (label.isBlank()) {
                return "Строка " + (index + 1);
            }
            return "Строка " + (index + 1) + " (" + label + ")";
        }

        private String joinIssues(List<String> issues) {
            if (issues.isEmpty()) {
                return "";
            }
            int limit = Math.min(3, issues.size());
            String joined = String.join("; ", issues.subList(0, limit));
            if (issues.size() > limit) {
                joined = joined + "; ...";
            }
            return joined;
        }

        private void shiftPhoto(int delta) {
            if (photoPreviews.isEmpty()) {
                return;
            }
            photoIndex = Math.floorMod(photoIndex + delta, photoPreviews.size());
            refreshPhotoPreview();
        }

        private void refreshPhotoPreview() {
            if (photoPreviews.isEmpty()) {
                photoIndex = 0;
                previewImage.setSrc("");
                previewCaption.setText("Фото пока нет");
                return;
            }
            photoIndex = Math.max(0, Math.min(photoIndex, photoPreviews.size() - 1));
            PhotoPreview current = photoPreviews.get(photoIndex);
            previewImage.setSrc(current.src());
            previewCaption.setText(current.fileName());
            preloadPhotoSources();
        }

        private void removeCurrentPhoto() {
            if (photoPreviews.isEmpty()) {
                return;
            }
            PhotoPreview removed = photoPreviews.remove(Math.max(0, Math.min(photoIndex, photoPreviews.size() - 1)));
            if (removed.photoId() != null) {
                removedPhotoIds.add(removed.photoId());
            }
            if (photoIndex >= photoPreviews.size()) {
                photoIndex = Math.max(0, photoPreviews.size() - 1);
            }
            refreshPhotoPreview();
        }

        private void removeNewestUnsavedPhotoByFileName(String fileName) {
            if (fileName == null || fileName.isBlank()) {
                return;
            }
            for (int index = photoPreviews.size() - 1; index >= 0; index--) {
                PhotoPreview preview = photoPreviews.get(index);
                if (preview.photoId() == null && fileName.equals(preview.fileName())) {
                    photoPreviews.remove(index);
                    if (photoIndex >= photoPreviews.size()) {
                        photoIndex = Math.max(0, photoPreviews.size() - 1);
                    }
                    return;
                }
            }
        }

        private void openFullscreenGallery() {
            if (photoPreviews.isEmpty()) {
                return;
            }
            Dialog dialog = new Dialog();
            dialog.setWidth("90vw");
            dialog.setHeight("90vh");
            Image full = new Image(photoPreviews.get(photoIndex).src(), photoPreviews.get(photoIndex).fileName());
            full.setWidthFull();
            full.setHeightFull();
            full.getStyle().set("object-fit", "contain");
            Button prev = new Button("<", event -> {
                shiftPhoto(-1);
                full.setSrc(photoPreviews.get(photoIndex).src());
            });
            Button next = new Button(">", event -> {
                shiftPhoto(1);
                full.setSrc(photoPreviews.get(photoIndex).src());
            });
            HorizontalLayout controls = new HorizontalLayout(prev, next, new Button("Закрыть", event -> dialog.close()));
            VerticalLayout content = new VerticalLayout(full, controls);
            content.setSizeFull();
            dialog.add(content);
            dialog.open();
        }

        private void preloadPhotoSources() {
            if (photoPreviews.isEmpty() || !isAttached()) {
                return;
            }
            LinkedHashSet<String> orderedSources = new LinkedHashSet<>();
            orderedSources.add(photoPreviews.get(photoIndex).src());
            for (int distance = 1; distance < photoPreviews.size(); distance++) {
                int leftIndex = photoIndex - distance;
                if (leftIndex >= 0) {
                    orderedSources.add(photoPreviews.get(leftIndex).src());
                }
                int rightIndex = photoIndex + distance;
                if (rightIndex < photoPreviews.size()) {
                    orderedSources.add(photoPreviews.get(rightIndex).src());
                }
            }
            List<String> sources = orderedSources.stream()
                    .filter(src -> src != null && !src.isBlank() && !src.startsWith("data:"))
                    .toList();
            List<String> highPrioritySources = sources.stream().limit(3).toList();
            List<String> backgroundSources = sources.size() <= 3 ? List.of() : sources.subList(3, sources.size());
            if (!highPrioritySources.isEmpty()) {
                getElement().executeJs("""
                        if (!window.__repairEstimatePhotoPreloads) {
                          window.__repairEstimatePhotoPreloads = [];
                        }
                        const preload = (src) => {
                          if (!src) {
                            return;
                          }
                          const image = new Image();
                          image.decoding = 'async';
                          image.src = src;
                          window.__repairEstimatePhotoPreloads.push(image);
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
                          window.__repairEstimatePhotoPreloads = window.__repairEstimatePhotoPreloads || [];
                          window.__repairEstimatePhotoPreloads.push(image);
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

        private void openTaskPlanDialog(RepairEstimate savedEstimate, List<RepairEstimateTaskPlan> plans) {
            if (savedEstimate == null || plans == null || plans.isEmpty()) {
                loadData();
                close();
                return;
            }
            TaskPlanDialog dialog = new TaskPlanDialog(savedEstimate, plans, this::close);
            dialog.open();
        }

        private String formatMoney(BigDecimal amount) {
            return amount == null ? "0.00" : amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        }

        private String dataUrl(String contentType, byte[] bytes) {
            String type = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
            return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes);
        }

        private String shorten(String value) {
            if (value == null) {
                return "";
            }
            return value.trim().replaceAll("\\s+", " ");
        }

        private record CatalogAddSelection(int quantity, String comment) {
        }

        private record CatalogApplySummary(int added, int merged) {
            private static CatalogApplySummary none() {
                return new CatalogApplySummary(0, 0);
            }

            private static CatalogApplySummary addedChange() {
                return new CatalogApplySummary(1, 0);
            }

            private static CatalogApplySummary mergedChange() {
                return new CatalogApplySummary(0, 1);
            }

            private CatalogApplySummary plus(CatalogApplySummary other) {
                if (other == null) {
                    return this;
                }
                return new CatalogApplySummary(added + other.added, merged + other.merged);
            }
        }
    }

    private enum CatalogMenuKind {
        TREE,
        RELATED_MATERIALS,
        MATERIAL_PICK,
        LOCATION_PICK,
        FOLLOW_UPS
    }

    private record CatalogFrame(CatalogMenuKind kind, CatalogNode node, CatalogNode relatedNode) {
        private static CatalogFrame root() {
            return new CatalogFrame(CatalogMenuKind.TREE, null, null);
        }

        private static CatalogFrame tree(CatalogNode node) {
            return new CatalogFrame(CatalogMenuKind.TREE, node, null);
        }

        private static CatalogFrame related(CatalogNode node) {
            return new CatalogFrame(CatalogMenuKind.RELATED_MATERIALS, node, null);
        }

        private static CatalogFrame materialPick(CatalogNode node) {
            return new CatalogFrame(CatalogMenuKind.MATERIAL_PICK, node, null);
        }

        private static CatalogFrame locationPick(CatalogNode workNode, CatalogNode materialNode) {
            return new CatalogFrame(CatalogMenuKind.LOCATION_PICK, workNode, materialNode);
        }

        private static CatalogFrame followUps(CatalogNode node) {
            return new CatalogFrame(CatalogMenuKind.FOLLOW_UPS, node, null);
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

    private static final class EstimateLineDraft {
        private String sourceLineKey;
        private String catalogCode;
        private RepairEstimateLineType lineType;
        private String description;
        private String lineComment;
        private String unit;
        private Integer quantity;
        private BigDecimal unitPrice;

        private EstimateLineDraft(String sourceLineKey, String catalogCode, RepairEstimateLineType lineType, String description, String lineComment, String unit, Integer quantity, BigDecimal unitPrice) {
            this.sourceLineKey = sourceLineKey == null || sourceLineKey.isBlank() ? UUID.randomUUID().toString() : sourceLineKey;
            this.catalogCode = catalogCode;
            this.lineType = lineType == null ? RepairEstimateLineType.WORK : lineType;
            this.description = description == null ? "" : description;
            this.lineComment = lineComment == null ? "" : lineComment;
            this.unit = unit == null || unit.isBlank() ? "ед" : unit;
            this.quantity = quantity == null || quantity < 1 ? 1 : quantity;
            this.unitPrice = unitPrice == null ? BigDecimal.ZERO : unitPrice;
        }

        private static EstimateLineDraft manual() {
            return new EstimateLineDraft(UUID.randomUUID().toString(), null, RepairEstimateLineType.WORK, "", "", "ед", 1, BigDecimal.ZERO);
        }

        private static EstimateLineDraft fromPersisted(RepairEstimateLine line) {
            String rawDescription = line.getDescription() == null ? "" : line.getDescription();
            String marker = "[Комментарий:";
            int markerIndex = rawDescription.lastIndexOf(marker);
            String sourceLineKey = line.getSourceLineKey();
            if (sourceLineKey == null || sourceLineKey.isBlank()) {
                sourceLineKey = line.getId() == null ? UUID.randomUUID().toString() : line.getId().toString();
            }
            if (markerIndex < 0 || !rawDescription.trim().endsWith("]")) {
                return new EstimateLineDraft(
                        sourceLineKey,
                        line.getCatalogCode(),
                        line.getLineType(),
                        rawDescription,
                        valueOrBlank(line.getLineComment()),
                        line.getUnit(),
                        line.getQuantity(),
                        line.getUnitPrice());
            }

            String description = rawDescription.substring(0, markerIndex).stripTrailing();
            String comment = rawDescription.substring(markerIndex + marker.length(), rawDescription.lastIndexOf(']')).trim();
            if (comment.isBlank()) {
                return new EstimateLineDraft(
                        sourceLineKey,
                        line.getCatalogCode(),
                        line.getLineType(),
                        rawDescription,
                        valueOrBlank(line.getLineComment()),
                        line.getUnit(),
                        line.getQuantity(),
                        line.getUnitPrice());
            }
            return new EstimateLineDraft(
                    sourceLineKey,
                    line.getCatalogCode(),
                    line.getLineType(),
                    description,
                    valueOrBlank(line.getLineComment()).isBlank() ? comment : line.getLineComment(),
                    line.getUnit(),
                    line.getQuantity(),
                    line.getUnitPrice());
        }

        private static String valueOrBlank(String value) {
            return value == null ? "" : value;
        }

        public String sourceLineKey() {
            return sourceLineKey;
        }

        public String catalogCode() {
            return catalogCode;
        }

        public RepairEstimateLineType lineType() {
            return lineType;
        }

        public void setLineType(RepairEstimateLineType lineType) {
            this.lineType = lineType == null ? RepairEstimateLineType.WORK : lineType;
        }

        public String description() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description == null ? "" : description;
        }

        public String lineComment() {
            return lineComment;
        }

        public void setLineComment(String lineComment) {
            this.lineComment = lineComment == null ? "" : lineComment;
        }

        public String unit() {
            return unit;
        }

        public void setUnit(String unit) {
            this.unit = unit == null || unit.isBlank() ? "ед" : unit;
        }

        public Integer quantity() {
            return quantity;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity == null || quantity < 1 ? 1 : quantity;
        }

        public BigDecimal unitPrice() {
            return unitPrice;
        }

        public void setUnitPrice(BigDecimal unitPrice) {
            this.unitPrice = unitPrice == null ? BigDecimal.ZERO : unitPrice;
        }

        public BigDecimal total() {
            BigDecimal price = unitPrice == null ? BigDecimal.ZERO : unitPrice;
            int qty = quantity == null || quantity < 1 ? 1 : quantity;
            return price.multiply(BigDecimal.valueOf(qty));
        }
    }

        private record PhotoPreview(UUID photoId, String fileName, String contentType, byte[] bytes, String src) {
        }

        private final class TaskPlanDialog extends Dialog {
            private final RepairEstimate estimate;
            private final List<RepairEstimateTaskPlan> plans;
            private final List<WorkQueue> queues;
            private final VerticalLayout plansLayout = new VerticalLayout();
            private final Runnable onComplete;

            private TaskPlanDialog(RepairEstimate estimate, List<RepairEstimateTaskPlan> plans, Runnable onComplete) {
                this.estimate = estimate;
                this.plans = new ArrayList<>(plans);
                this.onComplete = onComplete;
                this.queues = dataManager.load(WorkQueue.class)
                        .query("select e from WorkQueue e where e.active = true and e.warehouse = :warehouse order by e.sortOrder, e.name")
                        .parameter("warehouse", estimate.getWarehouse())
                        .fetchPlan(builder -> builder.addFetchPlan("_base").add("warehouse", "_base"))
                        .list();
                setHeaderTitle("Распределение работ по очередям");
                setWidth("96vw");
                setMaxWidth("none");
                setHeight("90vh");

                VerticalLayout content = new VerticalLayout(buildBody(), buildFooter());
                content.setPadding(false);
                content.setSpacing(true);
                content.setSizeFull();
                add(content);
            }

            private Component buildBody() {
                VerticalLayout body = new VerticalLayout();
                body.setPadding(false);
                body.setSpacing(true);
                body.setSizeFull();

                Div hint = new Div();
                hint.setText("Перетащите карточки мышкой, чтобы изменить порядок работ. Изменения сохраняются только кнопкой ниже.");
                hint.getStyle().set("color", "#64748b").set("font-size", "13px");

                plansLayout.setPadding(false);
                plansLayout.setSpacing(false);
                plansLayout.setWidthFull();
                plansLayout.getStyle()
                        .set("overflow", "auto")
                        .set("border", "1px solid #d7dde8")
                        .set("border-radius", "8px")
                        .set("padding", "12px")
                        .set("background", "#f8fafc")
                        .set("min-height", "30rem");
                renderPlanCards();

                body.add(new H4("Планы задач"), hint, plansLayout);
                body.expand(plansLayout);
                return body;
            }

            private Component buildFooter() {
                HorizontalLayout footer = new HorizontalLayout();
                footer.setWidthFull();
                footer.setJustifyContentMode(FlexComponent.JustifyContentMode.END);

                Button cancel = new Button("Закрыть", event -> close());
                Button save = new Button("Сохранить планы", event -> savePlans());
                save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
                footer.add(cancel, save);
                return footer;
            }

            private void renderPlanCards() {
                plansLayout.removeAll();
                if (plans.isEmpty()) {
                    Div empty = new Div();
                    empty.setText("Планов задач нет");
                    empty.getStyle().set("color", "#64748b");
                    plansLayout.add(empty);
                    return;
                }
                for (int index = 0; index < plans.size(); index++) {
                    RepairEstimateTaskPlan plan = plans.get(index);
                    plansLayout.add(createPlanCard(plan, index));
                    if (index < plans.size() - 1) {
                        Span arrow = new Span("↓");
                        arrow.getStyle()
                                .set("display", "block")
                                .set("text-align", "center")
                                .set("font-size", "28px")
                                .set("line-height", "34px")
                                .set("color", "#2563eb")
                                .set("width", "100%");
                        plansLayout.add(arrow);
                    }
                }
            }

            private Div createPlanCard(RepairEstimateTaskPlan plan, int index) {
                Div card = new Div();
                card.getStyle()
                        .set("border", "1px solid #cbd5e1")
                        .set("border-radius", "8px")
                        .set("background", "#ffffff")
                        .set("box-shadow", "0 1px 2px rgba(15, 23, 42, 0.08)")
                        .set("padding", "12px")
                        .set("width", "min(980px, 100%)")
                        .set("cursor", "grab");

                H4 title = new H4((index + 1) + ". " + lineLabelText(plan));
                title.getStyle().set("margin", "0 0 8px").set("font-size", "16px");

                HorizontalLayout editor = new HorizontalLayout(queueField(plan), groupCommentField(plan), planActions(plan),
                        new Div(generationStatusLabel(plan.getGenerationStatus())));
                editor.setWidthFull();
                editor.setAlignItems(FlexComponent.Alignment.END);
                editor.setFlexGrow(1, editor.getComponentAt(1));

                card.add(title, editor);

                DragSource<Div> dragSource = DragSource.create(card);
                dragSource.setEffectAllowed(EffectAllowed.MOVE);
                dragSource.setDragData(planKey(plan));

                DropTarget<Div> dropTarget = DropTarget.create(card);
                dropTarget.addDropListener(event -> movePlanBefore(event.getDragData().orElse(null), plan));

                return card;
            }

            private void movePlanBefore(Object dragData, RepairEstimateTaskPlan target) {
                if (dragData == null || target == null) {
                    return;
                }
                RepairEstimateTaskPlan dragged = findPlanByKey(dragData.toString());
                if (dragged == null || dragged == target) {
                    return;
                }
                plans.remove(dragged);
                int targetIndex = plans.indexOf(target);
                if (targetIndex < 0) {
                    plans.add(dragged);
                } else {
                    plans.add(targetIndex, dragged);
                }
                renderPlanCards();
            }

            private RepairEstimateTaskPlan findPlanByKey(String key) {
                return plans.stream()
                        .filter(plan -> Objects.equals(planKey(plan), key))
                        .findFirst()
                        .orElse(null);
            }

            private String planKey(RepairEstimateTaskPlan plan) {
                if (plan.getId() != null) {
                    return "id:" + plan.getId();
                }
                return "obj:" + System.identityHashCode(plan);
            }

            private String lineLabelText(RepairEstimateTaskPlan plan) {
                String description = plan.getEstimateLine() == null ? "" : valueOr(plan.getEstimateLine().getDescription());
                String comment = plan.getEstimateLine() == null ? "" : valueOr(plan.getEstimateLine().getLineComment());
                if (comment.isBlank()) {
                    return description;
                }
                return description + " · " + comment;
            }

            private Component queueField(RepairEstimateTaskPlan plan) {
                ComboBox<WorkQueue> field = new ComboBox<>();
                field.setLabel("Очередь");
                field.setItems(queues);
                field.setItemLabelGenerator(WorkQueue::getName);
                field.setValue(plan.getQueue());
                field.setClearButtonVisible(true);
                field.setWidth("16rem");
                field.addValueChangeListener(event -> plan.setQueue(event.getValue()));
                return field;
            }

            private Component groupCommentField(RepairEstimateTaskPlan plan) {
                TextArea field = new TextArea();
                field.setLabel("Комментарий группы");
                field.setValue(plan.getGroupComment() == null ? "" : plan.getGroupComment());
                field.setWidthFull();
                field.setMinHeight("4rem");
                field.addValueChangeListener(event -> {
                    plan.setGroupComment(event.getValue());
                });
                return field;
            }

            private Component planActions(RepairEstimateTaskPlan plan) {
                Button duplicate = new Button(new Icon(VaadinIcon.COPY), event -> duplicatePlan(plan));
                duplicate.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
                duplicate.getElement().setAttribute("title", "Дублировать план");

                Button remove = new Button(new Icon(VaadinIcon.TRASH), event -> removePlan(plan));
                remove.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_ERROR);
                remove.getElement().setAttribute("title", "Удалить план");

                HorizontalLayout actions = new HorizontalLayout(duplicate, remove);
                actions.setPadding(false);
                actions.setSpacing(false);
                actions.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);
                return actions;
            }

            private void duplicatePlan(RepairEstimateTaskPlan source) {
                if (source == null || source.getEstimateLine() == null) {
                    return;
                }
                RepairEstimateTaskPlan duplicate = dataManager.create(RepairEstimateTaskPlan.class);
                duplicate.setEstimate(estimate);
                duplicate.setEstimateLine(source.getEstimateLine());
                duplicate.setQueue(source.getQueue());
                duplicate.setGroupComment(source.getGroupComment());
                duplicate.setFollowUpNode(null);
                duplicate.setComment(source.getComment());
                duplicate.setActive(source.getActive() == null ? true : source.getActive());
                duplicate.setGenerationStatus(RepairEstimateTaskPlanGenerationStatus.PENDING_GENERATION);
                duplicate.setGeneratedBoardTask(null);
                duplicate.setSortOrder(source.getSortOrder() == null
                        ? source.getEstimateLine().getRowOrder()
                        : source.getSortOrder() + 1);
                int index = plans.indexOf(source);
                if (index < 0) {
                    plans.add(duplicate);
                } else {
                    plans.add(index + 1, duplicate);
                }
                renderPlanCards();
            }

            private void removePlan(RepairEstimateTaskPlan plan) {
                if (plan == null) {
                    return;
                }
                plans.remove(plan);
                renderPlanCards();
            }

            private void savePlans() {
                try {
                    for (int index = 0; index < plans.size(); index++) {
                        RepairEstimateTaskPlan plan = plans.get(index);
                        plan.setFollowUpNode(null);
                        plan.setSortOrder((index + 1) * 10);
                    }
                    List<RepairEstimateTaskPlan> savedPlans = repairEstimateTaskPlanService.saveTaskPlans(estimate.getId(), plans);
                    repairEstimateTaskPlanGenerationService.generateTasks(estimate.getId(), savedPlans);
                    notifyInfo("Планы задач сохранены");
                    loadData();
                    close();
                    if (onComplete != null) {
                        onComplete.run();
                    }
                } catch (Exception ex) {
                    notifyError(ex.getMessage() == null ? "Не удалось сохранить планы задач" : ex.getMessage());
                }
            }
        }
    }
