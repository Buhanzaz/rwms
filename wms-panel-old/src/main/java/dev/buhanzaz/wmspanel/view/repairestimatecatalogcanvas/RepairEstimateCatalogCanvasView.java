package dev.buhanzaz.wmspanel.view.repairestimatecatalogcanvas;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridMultiSelectionModel;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.Scroller;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogLink;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogLinkType;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNode;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNodeType;
import dev.buhanzaz.wmspanel.entity.WorkQueue;
import dev.buhanzaz.wmspanel.entity.WorkQueueKind;
import dev.buhanzaz.wmspanel.service.RepairCatalogService;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.core.DataManager;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;
import java.util.stream.Collectors;

@Route(value = "repair-estimate-catalog-canvas", layout = MainView.class)
@ViewController(id = "RepairEstimateCatalogCanvas.view")
@ViewDescriptor(path = "repair-estimate-catalog-canvas-view.xml")
public class RepairEstimateCatalogCanvasView extends StandardView {

    private static final int MIN_CANVAS_WIDTH = 2200;
    private static final int MIN_CANVAS_HEIGHT = 1400;
    private static final int CANVAS_PADDING = 80;
    private static final int NODE_WIDTH = 280;
    private static final int NODE_MIN_HEIGHT = 132;
    private static final int LEVEL_GAP = 180;
    private static final int SIBLING_GAP = 40;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MessageBundle messageBundle;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RepairCatalogService repairCatalogService;

    @ViewComponent
    private VerticalLayout root;

    private final VerticalLayout categoryScreen = new VerticalLayout();
    private final VerticalLayout editorScreen = new VerticalLayout();
    private final Grid<RepairEstimateCatalogNode> categoryGrid = new Grid<>();
    private final Div canvas = new Div();
    private final Span editorTitle = new Span();
    private final Button deleteLinkButton = new Button("Удалить связь");
    private final Button floatingDeleteLinkButton = new Button("Удалить связь");
    private final Button saveCatalogButton = new Button("Сохранить каталог");

    private List<RepairEstimateCatalogNode> allNodes = List.of();
    private List<RepairEstimateCatalogNode> editorNodes = List.of();
    private List<RepairEstimateCatalogLink> editorLinks = List.of();
    private UUID selectedCategoryId;
    private RepairEstimateCatalogNode selectedNode;
    private UUID selectedLinkId;
    private RepairEstimateCatalogLinkType draftLinkType = RepairEstimateCatalogLinkType.FOLLOW_UP;

    @Subscribe
    public void onInit(InitEvent event) {
        buildLayout();
        reloadCategories();
        showCategoryScreen();
    }

    @ClientCallable
    public void updateNodePosition(String nodeId, double x, double y) {
        Notification.show("Позиция сохраняется только кнопкой \"Сохранить каталог\"");
    }

    @ClientCallable
    public void createLink(String sourceNodeId, String targetNodeId, String sourceAnchor, String targetAnchor) {
        Notification.show("Связь сохраняется только кнопкой \"Сохранить каталог\"");
    }

    @ClientCallable
    public void selectLink(String linkId) {
        selectedLinkId = linkId == null || linkId.isBlank() ? null : UUID.fromString(linkId);
        updateDeleteLinkButton();
    }

    @ClientCallable
    public void clearSelectedLink() {
        selectedLinkId = null;
        updateDeleteLinkButton();
    }

    @ClientCallable
    public void saveCatalog(String payloadJson) {
        CanvasSavePayload payload;
        try {
            payload = objectMapper.readValue(payloadJson, CanvasSavePayload.class);
        } catch (JsonProcessingException e) {
            Notification.show("Не удалось прочитать изменения каталога");
            throw new IllegalArgumentException("Invalid catalog canvas payload", e);
        }

        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> saveCatalogDraft(payload));
            repairCatalogService.clearCache();
            selectedLinkId = null;
            Notification.show("Каталог сохранен");
            reloadEditor();
        } catch (RuntimeException e) {
            Notification.show(e.getMessage() == null ? "Не удалось сохранить каталог" : e.getMessage());
            throw e;
        }
    }

    @ClientCallable
    public void editNode(String nodeId) {
        UUID id = parseOptionalUuid(nodeId);
        if (id == null) {
            Notification.show("Некорректный блок");
            return;
        }
        RepairEstimateCatalogNode node = dataManager.load(RepairEstimateCatalogNode.class)
                .id(id)
                .optional()
                .orElse(null);
        if (node == null) {
            Notification.show("Блок не найден");
            return;
        }
        if (node.getNodeType() == RepairEstimateCatalogNodeType.CATEGORY) {
            openCategoryDialog(node);
        } else {
            openNodeDialog(node);
        }
    }

    private void requestClientCatalogSave() {
        canvas.getElement().executeJs("window.__catalogSaveDraft && window.__catalogSaveDraft();");
    }

    private void setClientDraftLinkType(RepairEstimateCatalogLinkType linkType) {
        canvas.getElement().executeJs("window.__catalogSetDraftLinkType && window.__catalogSetDraftLinkType($0);",
                linkType == null ? RepairEstimateCatalogLinkType.FOLLOW_UP.name() : linkType.name());
    }

    private void saveCatalogDraft(CanvasSavePayload payload) {
        UUID payloadCategoryId = parseRequiredUuid(payload.categoryId(), "Категория не указана");
        if (selectedCategoryId == null || !Objects.equals(selectedCategoryId, payloadCategoryId)) {
            throw new IllegalArgumentException("Категория изменилась. Обновите экран и повторите сохранение.");
        }

        RepairEstimateCatalogNode category = dataManager.load(RepairEstimateCatalogNode.class)
                .id(payloadCategoryId)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Категория не найдена"));
        if (category.getNodeType() != RepairEstimateCatalogNodeType.CATEGORY) {
            throw new IllegalArgumentException("Выбранный блок не является категорией");
        }

        List<CanvasNodePayload> nodePayloads = payload.nodes() == null ? List.of() : payload.nodes();
        List<CanvasLinkPayload> linkPayloads = payload.links() == null ? List.of() : payload.links();
        Map<UUID, RepairEstimateCatalogNode> nodeById = loadPayloadNodes(nodePayloads, linkPayloads);
        requireCanvasLinksValid(linkPayloads, nodeById);

        for (CanvasNodePayload nodePayload : nodePayloads) {
            UUID nodeId = parseRequiredUuid(nodePayload.id(), "Некорректный блок каталога");
            RepairEstimateCatalogNode node = nodeById.get(nodeId);
            if (node == null) {
                continue;
            }
            if (Boolean.TRUE.equals(nodePayload.removedFromCanvas())
                    && (node.getNodeType() == RepairEstimateCatalogNodeType.WORK
                    || node.getNodeType() == RepairEstimateCatalogNodeType.MATERIAL)) {
                node.setCanvasX(null);
                node.setCanvasY(null);
                RepairEstimateCatalogNode savedNode = dataManager.save(node);
                nodeById.put(nodeId, savedNode);
                hardDeleteLinksForNode(nodeId);
                continue;
            }
            applyNodeDraft(node, nodePayload);
            if (nodePayload.x() != null && nodePayload.y() != null) {
                node.setCanvasX(Math.max(0, nodePayload.x()));
                node.setCanvasY(Math.max(0, nodePayload.y()));
            }
            RepairEstimateCatalogNode savedNode = dataManager.save(node);
            nodeById.put(nodeId, savedNode);
        }

        for (CanvasLinkPayload linkPayload : linkPayloads) {
            UUID existingId = parseOptionalUuid(linkPayload.id());
            if (Boolean.TRUE.equals(linkPayload.deleted())) {
                if (existingId != null) {
                    hardDeleteCatalogLink(existingId);
                }
                continue;
            }

            UUID sourceId = parseRequiredUuid(linkPayload.sourceNodeId(), "У связи не указан исходный блок");
            UUID targetId = parseRequiredUuid(linkPayload.targetNodeId(), "У связи не указан целевой блок");
            RepairEstimateCatalogNode source = nodeById.get(sourceId);
            RepairEstimateCatalogNode target = nodeById.get(targetId);
            RepairEstimateCatalogLinkType type = parseLinkType(linkPayload.linkType());
            AnchorSide sourceAnchor = normalizeAnchor(linkPayload.sourceAnchor());
            AnchorSide targetAnchor = normalizeAnchor(linkPayload.targetAnchor());

            RepairEstimateCatalogLink link = existingId == null
                    ? findExistingLink(source, target, type)
                    : dataManager.load(RepairEstimateCatalogLink.class)
                    .id(existingId)
                    .optional()
                    .orElse(null);
            if (link == null) {
                link = dataManager.create(RepairEstimateCatalogLink.class);
            }
            link.setSourceNode(source);
            link.setTargetNode(target);
            link.setLinkType(type);
            link.setActive(true);
            link.setComment(anchorComment(sourceAnchor, targetAnchor));
            applyParentFromLink(source, target, type);
            if (type == RepairEstimateCatalogLinkType.FOLLOW_UP
                    && target.getNodeType() != RepairEstimateCatalogNodeType.CATEGORY) {
                target = dataManager.save(target);
                nodeById.put(targetId, target);
                link.setTargetNode(target);
            }
            dataManager.save(link);
        }
    }

    private void applyNodeDraft(RepairEstimateCatalogNode node, CanvasNodePayload payload) {
        if (payload.nodeType() == null && payload.name() == null && payload.code() == null) {
            return;
        }
        RepairEstimateCatalogNodeType nodeType = payload.nodeType() == null
                ? node.getNodeType()
                : parseNodeType(payload.nodeType());
        String name = payload.name() == null ? node.getName() : payload.name().trim();
        String code = payload.code() == null ? node.getCode() : normalizeCode(payload.code());
        if (name == null || name.isBlank() || code == null || code.isBlank()) {
            throw new IllegalArgumentException("Заполните тип, имя и код блока");
        }
        requireUniqueNodeCode(code, node.getId());
        node.setNodeType(nodeType);
        node.setName(name);
        node.setCode(code);
        node.setIncludeInEstimate(payload.includeInEstimate() == null
                ? node.getIncludeInEstimate()
                : payload.includeInEstimate());
        node.setCommonItem(payload.commonItem() == null
                ? node.getCommonItem()
                : payload.commonItem());
        node.setUnit(pricedType(nodeType) ? blankToNull(payload.unit()) : null);
        node.setUnitPrice(pricedType(nodeType) ? payload.unitPrice() : null);
        node.setDefaultQuantity(pricedType(nodeType) ? payload.defaultQuantity() : null);
        node.setDurationMinutes(nodeType == RepairEstimateCatalogNodeType.WORK ? payload.durationMinutes() : null);
        node.setAdditionalOption(nodeType == RepairEstimateCatalogNodeType.OPTION
                && Boolean.TRUE.equals(payload.additionalOption()));
        node.setActive(payload.active() == null ? node.getActive() : payload.active());
        node.setSortOrder(payload.sortOrder());
        node.setComment(blankToNull(payload.comment()));
        node.setRouteQueueKind(null);
    }

    private void requireUniqueNodeCode(String normalizedCode, UUID currentNodeId) {
        RepairEstimateCatalogNode existingNode = loadNodeByCode(normalizedCode);
        if (existingNode != null && !Objects.equals(existingNode.getId(), currentNodeId)) {
            throw new IllegalArgumentException("Код уже занят существующим блоком");
        }
    }

    private RepairEstimateCatalogNodeType parseNodeType(String value) {
        try {
            return RepairEstimateCatalogNodeType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Некорректный тип блока: " + value);
        }
    }

    private Map<UUID, RepairEstimateCatalogNode> loadPayloadNodes(List<CanvasNodePayload> nodePayloads,
                                                                  List<CanvasLinkPayload> linkPayloads) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (CanvasNodePayload nodePayload : nodePayloads) {
            ids.add(parseRequiredUuid(nodePayload.id(), "Некорректный блок каталога"));
        }
        for (CanvasLinkPayload linkPayload : linkPayloads) {
            if (!Boolean.TRUE.equals(linkPayload.deleted())) {
                ids.add(parseRequiredUuid(linkPayload.sourceNodeId(), "У связи не указан исходный блок"));
                ids.add(parseRequiredUuid(linkPayload.targetNodeId(), "У связи не указан целевой блок"));
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        return dataManager.load(RepairEstimateCatalogNode.class)
                .query("select e from RepairEstimateCatalogNode e where e.id in :ids")
                .parameter("ids", ids)
                .fetchPlan(builder -> builder.addFetchPlan("_base").add("parent", "_base").add("workQueue", "_base"))
                .list()
                .stream()
                .collect(Collectors.toMap(RepairEstimateCatalogNode::getId, node -> node));
    }

    private void requireCanvasLinksValid(List<CanvasLinkPayload> linkPayloads,
                                         Map<UUID, RepairEstimateCatalogNode> nodeById) {
        Set<String> uniqueLinks = new HashSet<>();
        Map<UUID, List<UUID>> graph = new HashMap<>();
        for (CanvasLinkPayload linkPayload : linkPayloads) {
            if (Boolean.TRUE.equals(linkPayload.deleted())) {
                continue;
            }
            UUID sourceId = parseRequiredUuid(linkPayload.sourceNodeId(), "У связи не указан исходный блок");
            UUID targetId = parseRequiredUuid(linkPayload.targetNodeId(), "У связи не указан целевой блок");
            RepairEstimateCatalogNode source = nodeById.get(sourceId);
            RepairEstimateCatalogNode target = nodeById.get(targetId);
            if (source == null || target == null) {
                throw new IllegalArgumentException("Один из блоков связи не найден");
            }
            if (Objects.equals(sourceId, targetId)) {
                throw new IllegalArgumentException("Блок не может ссылаться сам на себя");
            }
            if (target.getNodeType() == RepairEstimateCatalogNodeType.CATEGORY) {
                throw new IllegalArgumentException("Категория не может быть дочерним блоком");
            }
            RepairEstimateCatalogLinkType type = parseLinkType(linkPayload.linkType());
            String uniqueKey = sourceId + "|" + targetId + "|" + type.name();
            if (!uniqueLinks.add(uniqueKey)) {
                throw new IllegalArgumentException("Одинаковая связь уже есть в черновике");
            }
            graph.computeIfAbsent(sourceId, ignored -> new ArrayList<>()).add(targetId);
        }
        if (hasCycle(graph)) {
            throw new IllegalArgumentException("Связи создают цикл в каталоге");
        }
    }

    private boolean hasCycle(Map<UUID, List<UUID>> graph) {
        Set<UUID> visiting = new HashSet<>();
        Set<UUID> visited = new HashSet<>();
        for (UUID nodeId : graph.keySet()) {
            if (hasCycle(nodeId, graph, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCycle(UUID nodeId,
                             Map<UUID, List<UUID>> graph,
                             Set<UUID> visiting,
                             Set<UUID> visited) {
        if (visited.contains(nodeId)) {
            return false;
        }
        if (!visiting.add(nodeId)) {
            return true;
        }
        for (UUID target : graph.getOrDefault(nodeId, List.of())) {
            if (hasCycle(target, graph, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(nodeId);
        visited.add(nodeId);
        return false;
    }

    private RepairEstimateCatalogLinkType parseLinkType(String value) {
        if (value == null || value.isBlank()) {
            return RepairEstimateCatalogLinkType.FOLLOW_UP;
        }
        try {
            return RepairEstimateCatalogLinkType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Некорректный тип связи: " + value);
        }
    }

    private UUID parseRequiredUuid(String value, String message) {
        UUID uuid = parseOptionalUuid(value);
        if (uuid == null) {
            throw new IllegalArgumentException(message);
        }
        return uuid;
    }

    private UUID parseOptionalUuid(String value) {
        if (value == null || value.isBlank() || value.startsWith("tmp-")) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void buildLayout() {
        root.setSizeFull();
        root.setPadding(false);
        root.setSpacing(false);

        buildCategoryScreen();
        buildEditorScreen();

        root.add(categoryScreen, editorScreen);
    }

    private void buildCategoryScreen() {
        categoryScreen.setSizeFull();
        categoryScreen.setPadding(true);
        categoryScreen.setSpacing(true);

        HorizontalLayout toolbar = new HorizontalLayout();
        toolbar.setWidthFull();
        toolbar.setAlignItems(FlexComponent.Alignment.CENTER);

        H3 title = new H3("Категории каталога смет");
        title.getStyle().set("margin", "0");
        Button addCategoryButton = new Button("Добавить категорию", event -> openCategoryDialog(null));
        Button editCategoryButton = new Button("Изменить категорию", event -> {
            RepairEstimateCatalogNode item = categoryGrid.asSingleSelect().getValue();
            if (item == null) {
                Notification.show("Сначала выберите категорию в таблице");
                return;
            }
            openCategoryDialog(item);
        });
        Button deleteCategoryButton = new Button("Удалить каталог", event -> {
            RepairEstimateCatalogNode item = categoryGrid.asSingleSelect().getValue();
            if (item == null) {
                Notification.show("Сначала выберите каталог в таблице");
                return;
            }
            confirmDeleteCatalog(item);
        });
        deleteCategoryButton.getStyle().set("color", "var(--lumo-error-text-color)");
        Button refreshButton = new Button("Обновить", event -> reloadCategories());

        toolbar.add(title, addCategoryButton, editCategoryButton, deleteCategoryButton, refreshButton);
        toolbar.expand(title);

        categoryGrid.setSizeFull();
        categoryGrid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COLUMN_BORDERS);
        categoryGrid.addColumn(RepairEstimateCatalogNode::getName).setHeader("Категория").setAutoWidth(true).setFlexGrow(1);
        categoryGrid.addColumn(RepairEstimateCatalogNode::getCode).setHeader("Код").setAutoWidth(true);
        categoryGrid.addColumn(node -> queueBindingLabel(node.getWorkQueue())).setHeader("Рабочая очередь").setAutoWidth(true);
        categoryGrid.addColumn(node -> Boolean.TRUE.equals(node.getFurnitureCategory()) ? "Да" : "Нет").setHeader("Мебель").setAutoWidth(true);
        categoryGrid.addColumn(node -> Boolean.TRUE.equals(node.getIncludeInEstimate()) ? "Да" : "Нет").setHeader("Учет в смете").setAutoWidth(true);
        categoryGrid.addColumn(node -> Boolean.TRUE.equals(node.getActive()) ? "Да" : "Нет").setHeader("Активно").setAutoWidth(true);
        categoryGrid.addColumn(node -> node.getSortOrder() == null ? "" : String.valueOf(node.getSortOrder())).setHeader("Сортировка").setAutoWidth(true);
        categoryGrid.addItemDoubleClickListener(event -> openCategory(event.getItem()));
        categoryGrid.asSingleSelect().addValueChangeListener(event -> {
            if (event.getValue() != null) {
                selectedCategoryId = event.getValue().getId();
            }
        });

        Span hint = new Span("Двойной клик по строке открывает редактор блоков выбранной категории.");
        hint.getStyle().set("color", "#64748b");

        categoryScreen.add(toolbar, hint, categoryGrid);
        categoryScreen.expand(categoryGrid);
    }

    private void buildEditorScreen() {
        editorScreen.setSizeFull();
        editorScreen.setPadding(true);
        editorScreen.setSpacing(true);

        HorizontalLayout toolbar = new HorizontalLayout();
        toolbar.setWidthFull();
        toolbar.setAlignItems(FlexComponent.Alignment.CENTER);

        Button backButton = new Button("Назад к категориям", event -> showCategoryScreen());
        editorTitle.getStyle().set("font-size", "24px").set("font-weight", "700");
        Button editCategoryButton = new Button("Изменить категорию", event -> {
            RepairEstimateCatalogNode category = selectedCategory();
            if (category != null) {
                openCategoryDialog(category);
            }
        });
        Button addNewBlockButton = new Button("Добавить новый блок", event -> openNodeDialog(null));
        Button addExistingBlockButton = new Button("Добавить блок из списка", event -> openNodeSelectionDialog());
        Button refreshButton = new Button("Обновить", event -> reloadEditor());
        saveCatalogButton.addClickListener(event -> requestClientCatalogSave());
        saveCatalogButton.addClassName("catalog-save-button");
        saveCatalogButton.getStyle().set("font-weight", "700");
        deleteLinkButton.addClickListener(event -> deleteSelectedLink());
        deleteLinkButton.setVisible(false);
        floatingDeleteLinkButton.addClickListener(event -> deleteSelectedLink());
        floatingDeleteLinkButton.addClassName("catalog-floating-delete-link");
        floatingDeleteLinkButton.setVisible(false);
        floatingDeleteLinkButton.getStyle()
                .set("position", "absolute")
                .set("z-index", "40")
                .set("height", "30px")
                .set("font-size", "12px")
                .set("box-shadow", "0 8px 18px rgba(15, 23, 42, 0.18)");

        ComboBox<RepairEstimateCatalogLinkType> linkType = new ComboBox<>("Тип связи");
        linkType.setItems(RepairEstimateCatalogLinkType.FOLLOW_UP, RepairEstimateCatalogLinkType.DEPENDENCY);
        linkType.setItemLabelGenerator(this::linkTypeLabel);
        linkType.setValue(selectableLinkType(draftLinkType));
        linkType.setWidth("220px");
        linkType.addValueChangeListener(event -> {
            draftLinkType = event.getValue() == null
                    ? RepairEstimateCatalogLinkType.FOLLOW_UP
                    : event.getValue();
            setClientDraftLinkType(draftLinkType);
        });

        toolbar.add(backButton, editorTitle, editCategoryButton, addNewBlockButton, addExistingBlockButton,
                saveCatalogButton, linkType, deleteLinkButton, refreshButton);
        toolbar.expand(editorTitle);

        Div hint = new Div();
        hint.setText("Выдели карточку, чтобы редактировать. Для стрелки потяни синюю точку снизу одного блока на другой.");
        hint.getStyle().set("color", "#64748b").set("font-size", "13px");

        canvas.setClassName("estimate-catalog-canvas");
        canvas.setWidth(MIN_CANVAS_WIDTH + "px");
        canvas.setHeight(MIN_CANVAS_HEIGHT + "px");
        canvas.getStyle()
                .set("position", "relative")
                .set("min-width", "100%")
                .set("background-color", "#fcfcfd")
                .set("background-image", "radial-gradient(#d6dbe2 1px, transparent 1px)")
                .set("background-size", "20px 20px")
                .set("border", "1px solid #dde3ea")
                .set("border-radius", "8px");
        canvas.add(floatingDeleteLinkButton);

        Scroller scroller = new Scroller(canvas);
        scroller.setSizeFull();

        editorScreen.add(toolbar, hint, scroller);
        editorScreen.expand(scroller);
    }

    private void reloadCategories() {
        allNodes = loadAllNodes();
        List<RepairEstimateCatalogNode> categories = allNodes.stream()
                .filter(node -> node.getParent() == null)
                .filter(node -> node.getNodeType() == RepairEstimateCatalogNodeType.CATEGORY)
                .sorted(nodeComparator())
                .toList();
        categoryGrid.setItems(categories);
        if (selectedCategoryId != null && categories.stream().noneMatch(node -> Objects.equals(node.getId(), selectedCategoryId))) {
            selectedCategoryId = null;
        }
    }

    private void openCategory(RepairEstimateCatalogNode category) {
        selectedCategoryId = category.getId();
        selectedNode = null;
        reloadEditor();
        showEditorScreen();
    }

    private void reloadEditor() {
        allNodes = loadAllNodes();
        RepairEstimateCatalogNode category = selectedCategory();
        if (category == null) {
            showCategoryScreen();
            return;
        }
        editorTitle.setText("Категория: " + safe(category.getName()));
        List<RepairEstimateCatalogLink> allLinks = loadAllLinks();
        editorNodes = collectCategoryGraph(allNodes, allLinks, category.getId());
        Set<UUID> visibleIds = editorNodes.stream().map(RepairEstimateCatalogNode::getId).collect(Collectors.toSet());
        editorLinks = allLinks.stream()
                .filter(link -> visibleIds.contains(link.getSourceNode().getId()))
                .filter(link -> visibleIds.contains(link.getTargetNode().getId()))
                .toList();
        selectedNode = selectedNode == null ? null : findEditorNode(selectedNode.getId());
        if (selectedLinkId != null && editorLinks.stream().noneMatch(link -> Objects.equals(link.getId(), selectedLinkId))) {
            selectedLinkId = null;
        }
        updateDeleteLinkButton();
        renderCanvas();
    }

    private void showCategoryScreen() {
        categoryScreen.setVisible(true);
        editorScreen.setVisible(false);
        reloadCategories();
    }

    private void showEditorScreen() {
        categoryScreen.setVisible(false);
        editorScreen.setVisible(true);
    }

    private void confirmDeleteCatalog(RepairEstimateCatalogNode category) {
        if (category == null || category.getId() == null) {
            return;
        }
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Удалить каталог");
        dialog.setWidth("520px");
        Div text = new Div();
        text.setText("Каталог \"" + safe(category.getName())
                + "\" будет удален полностью вместе со всеми дочерними блоками и связями. Действие нельзя отменить.");
        text.getStyle().set("line-height", "1.5");
        Button cancel = new Button("Отмена", event -> dialog.close());
        Button delete = new Button("Удалить каталог", event -> {
            deleteCatalog(category);
            dialog.close();
        });
        delete.getStyle().set("color", "var(--lumo-error-text-color)");
        dialog.add(text);
        dialog.getFooter().add(cancel, delete);
        dialog.open();
    }

    private void deleteCatalog(RepairEstimateCatalogNode category) {
        List<UUID> nodeIds = collectCatalogNodeIds(category.getId());
        if (nodeIds.isEmpty()) {
            return;
        }
        hardDeleteCatalogTree(nodeIds);
        selectedCategoryId = null;
        selectedNode = null;
        selectedLinkId = null;
        reloadCategories();
        showCategoryScreen();
        Notification.show("Каталог удален");
    }

    private List<UUID> collectCatalogNodeIds(UUID rootCategoryId) {
        if (rootCategoryId == null) {
            return List.of();
        }
        List<RepairEstimateCatalogNode> nodes = loadAllNodes();
        Map<UUID, List<RepairEstimateCatalogNode>> childrenByParent = nodes.stream()
                .filter(node -> node.getParent() != null && node.getParent().getId() != null)
                .collect(Collectors.groupingBy(node -> node.getParent().getId(), LinkedHashMap::new, Collectors.toList()));
        List<UUID> result = new ArrayList<>();
        collectCatalogNodeIds(rootCategoryId, childrenByParent, result, new HashSet<>());
        return result;
    }

    private void collectCatalogNodeIds(UUID nodeId,
                                       Map<UUID, List<RepairEstimateCatalogNode>> childrenByParent,
                                       List<UUID> result,
                                       Set<UUID> visited) {
        if (nodeId == null || !visited.add(nodeId)) {
            return;
        }
        result.add(nodeId);
        for (RepairEstimateCatalogNode child : childrenByParent.getOrDefault(nodeId, List.of())) {
            collectCatalogNodeIds(child.getId(), childrenByParent, result, visited);
        }
    }

    private List<RepairEstimateCatalogNode> loadAllNodes() {
        return dataManager.load(RepairEstimateCatalogNode.class)
                .query("select e from RepairEstimateCatalogNode e order by e.sortOrder, e.name")
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("parent", "_base")
                        .add("workQueue", nested -> nested.addFetchPlan("_base").add("warehouse", "_base")))
                .list();
    }

    private List<RepairEstimateCatalogLink> loadAllLinks() {
        return dataManager.load(RepairEstimateCatalogLink.class)
                .query("""
                        select e from RepairEstimateCatalogLink e
                        left join fetch e.sourceNode
                        left join fetch e.targetNode
                        where e.active = true
                        order by e.sortOrder, e.id
                        """)
                .list();
    }

    private List<RepairEstimateCatalogNode> collectCategoryGraph(List<RepairEstimateCatalogNode> allNodes,
                                                                 List<RepairEstimateCatalogLink> allLinks,
                                                                 UUID categoryId) {
        Map<UUID, RepairEstimateCatalogNode> byId = new HashMap<>();
        for (RepairEstimateCatalogNode node : allNodes) {
            byId.put(node.getId(), node);
        }
        Map<UUID, List<RepairEstimateCatalogNode>> childrenByLink = new HashMap<>();
        for (RepairEstimateCatalogLink link : allLinks) {
            if (link.getSourceNode() != null && link.getTargetNode() != null
                    && link.getSourceNode().getId() != null && link.getTargetNode().getId() != null) {
                RepairEstimateCatalogNode target = byId.get(link.getTargetNode().getId());
                if (target != null) {
                    childrenByLink.computeIfAbsent(link.getSourceNode().getId(), ignored -> new ArrayList<>()).add(target);
                }
            }
        }
        List<RepairEstimateCatalogNode> result = new ArrayList<>();
        collectGraph(categoryId, byId, childrenByLink, result);
        List<RepairEstimateCatalogNode> placedUnlinkedNodes = allNodes.stream()
                .filter(node -> node.getNodeType() != RepairEstimateCatalogNodeType.CATEGORY)
                .filter(node -> node.getCanvasX() != null || node.getCanvasY() != null)
                .filter(node -> !result.contains(node))
                .filter(node -> belongsToCategory(node, categoryId, byId))
                .sorted(nodeComparator())
                .toList();
        result.addAll(placedUnlinkedNodes);
        return result;
    }

    private void collectGraph(UUID nodeId,
                              Map<UUID, RepairEstimateCatalogNode> byId,
                              Map<UUID, List<RepairEstimateCatalogNode>> childrenByLink,
                              List<RepairEstimateCatalogNode> result) {
        RepairEstimateCatalogNode node = byId.get(nodeId);
        if (node == null || result.contains(node)) {
            return;
        }
        result.add(node);
        List<RepairEstimateCatalogNode> orderedChildren = new ArrayList<>(childrenByLink.getOrDefault(nodeId, List.of()));
        orderedChildren.sort(nodeComparator());
        for (RepairEstimateCatalogNode child : orderedChildren) {
            collectGraph(child.getId(), byId, childrenByLink, result);
        }
    }

    private void renderCanvas() {
        canvas.removeAll();
        canvas.add(floatingDeleteLinkButton);
        floatingDeleteLinkButton.setVisible(false);
        Map<UUID, Position> positions = calculatePositions(editorNodes);
        Div linkLayer = new Div();
        linkLayer.addClassName("catalog-link-layer");
        linkLayer.getStyle()
                .set("position", "absolute")
                .set("inset", "0")
                .set("z-index", "5")
                .set("pointer-events", "auto")
                .set("overflow", "visible");
        linkLayer.getElement().setProperty("innerHTML", buildSvg(positions));
        canvas.add(linkLayer);
        for (RepairEstimateCatalogNode node : editorNodes) {
            Position position = positions.get(node.getId());
            if (position != null) {
                canvas.add(createNodeCard(node, position));
            }
        }
        updateFloatingDeleteLinkButton(positions);
        attachCanvasBehavior();
    }

    private void updateFloatingDeleteLinkButton(Map<UUID, Position> positions) {
        if (selectedLinkId == null) {
            floatingDeleteLinkButton.setVisible(false);
            return;
        }
        RepairEstimateCatalogLink selectedLink = editorLinks.stream()
                .filter(link -> Objects.equals(link.getId(), selectedLinkId))
                .findFirst()
                .orElse(null);
        if (selectedLink == null || selectedLink.getSourceNode() == null || selectedLink.getTargetNode() == null) {
            floatingDeleteLinkButton.setVisible(false);
            return;
        }
        Position source = positions.get(selectedLink.getSourceNode().getId());
        Position target = positions.get(selectedLink.getTargetNode().getId());
        if (source == null || target == null) {
            floatingDeleteLinkButton.setVisible(false);
            return;
        }
        LinkAnchors anchors = anchorsFor(selectedLink, source, target);
        int x1 = source.x() + NODE_WIDTH / 2;
        int y1 = anchorY(source, anchors.sourceAnchor());
        int x2 = target.x() + NODE_WIDTH / 2;
        int y2 = anchorY(target, anchors.targetAnchor());
        int left = Math.max(0, ((x1 + x2) / 2) - 56);
        int top = Math.max(0, ((y1 + y2) / 2) - 44);
        floatingDeleteLinkButton.getStyle()
                .set("left", left + "px")
                .set("top", top + "px");
        floatingDeleteLinkButton.setVisible(true);
    }

    private Div createNodeCard(RepairEstimateCatalogNode node, Position position) {
        Div card = new Div();
        card.addClassName("catalog-node");
        card.getElement().setAttribute("data-node-id", node.getId().toString());
        card.getElement().setAttribute("data-node-type", node.getNodeType() == null ? "" : node.getNodeType().name());
        card.getStyle()
                .set("position", "absolute")
                .set("left", position.x() + "px")
                .set("top", position.y() + "px")
                .set("width", NODE_WIDTH + "px")
                .set("min-height", position.height() + "px")
                .set("background", "#ffffff")
                .set("border", selectedNode != null && Objects.equals(selectedNode.getId(), node.getId())
                        ? "2px solid #2563eb" : "1px solid #d6dbe2")
                .set("border-radius", "8px")
                .set("box-shadow", "0 10px 22px rgba(15, 23, 42, 0.07)")
                .set("overflow", "visible")
                .set("z-index", "10")
                .set("cursor", "grab")
                .set("user-select", "none");

        for (FieldRow row : rowsForNode(node)) {
            Div rowDiv = new Div();
            rowDiv.getStyle()
                    .set("display", "grid")
                    .set("grid-template-columns", "112px 1fr")
                    .set("min-height", "40px")
                    .set("border-bottom", "1px solid #eef2f6");
            Div left = cell(row.label());
            left.getStyle().set("font-weight", "600").set("background", "#fbfcfe");
            Div right = cell(row.value());
            rowDiv.add(left, right);
            card.add(rowDiv);
        }

        card.add(createEditRow(node));
        card.add(createHandle(node, "top"));
        card.add(createHandle(node, "bottom"));

        card.addDoubleClickListener(event -> {
            if (node.getNodeType() == RepairEstimateCatalogNodeType.CATEGORY) {
                openCategoryDialog(node);
            } else {
                openNodeDialog(node);
            }
        });
        return card;
    }

    private Div createEditRow(RepairEstimateCatalogNode node) {
        Div rowDiv = new Div();
        rowDiv.getStyle()
                .set("display", "grid")
                .set("grid-template-columns", "112px 1fr")
                .set("min-height", "40px");
        Div left = cell("");
        left.getStyle().set("background", "#fbfcfe");
        Div right = new Div();
        right.getStyle()
                .set("padding", "10px 12px")
                .set("display", "flex")
                .set("align-items", "center");
        right.add(createEditButton(node));
        rowDiv.add(left, right);
        return rowDiv;
    }

    private Button createEditButton(RepairEstimateCatalogNode node) {
        Button editButton = new Button("Редактировать", event -> {
            if (node.getNodeType() == RepairEstimateCatalogNodeType.CATEGORY) {
                openCategoryDialog(node);
            } else {
                openNodeDialog(node);
            }
        });
        editButton.addClassName("catalog-edit-button");
        editButton.getStyle()
                .set("font-size", "12px")
                .set("height", "28px")
                .set("cursor", "pointer");
        return editButton;
    }

    private Div cell(String value) {
        Div cell = new Div();
        cell.setText(safe(value));
        cell.getStyle()
                .set("padding", "10px 12px")
                .set("font-size", "13px")
                .set("line-height", "1.3")
                .set("display", "flex")
                .set("align-items", "center")
                .set("white-space", "normal");
        return cell;
    }

    private List<FieldRow> rowsForNode(RepairEstimateCatalogNode node) {
        List<FieldRow> rows = new ArrayList<>();
        rows.add(new FieldRow("Тип", nodeTypeLabel(node.getNodeType())));
        rows.add(new FieldRow("Имя", node.getName()));
        rows.add(new FieldRow("Учет в смете", yesNo(node.getIncludeInEstimate())));
        if (node.getNodeType() == RepairEstimateCatalogNodeType.WORK || node.getNodeType() == RepairEstimateCatalogNodeType.MATERIAL) {
            rows.add(new FieldRow(localizedMessage("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.commonItem", "Общий"), yesNo(node.getCommonItem())));
        }
        if (node.getNodeType() == RepairEstimateCatalogNodeType.CATEGORY) {
            rows.add(new FieldRow("Рабочая очередь", queueBindingLabel(node.getWorkQueue())));
            rows.add(new FieldRow("Мебель", yesNo(node.getFurnitureCategory())));
        }
        if (node.getNodeType() == RepairEstimateCatalogNodeType.WORK || node.getNodeType() == RepairEstimateCatalogNodeType.MATERIAL) {
            rows.add(new FieldRow("Единица измерения", node.getUnit()));
            rows.add(new FieldRow("Цена за единицу", money(node.getUnitPrice())));
        }
        if (node.getNodeType() == RepairEstimateCatalogNodeType.WORK) {
            rows.add(new FieldRow("Длительность, мин", node.getDurationMinutes() == null ? "" : String.valueOf(node.getDurationMinutes())));
        }
        return rows;
    }

    private String buildSvg(Map<UUID, Position> positions) {
        StringBuilder svg = new StringBuilder();
        svg.append("""
                <svg style="position:absolute; inset:0; width:100%%; height:100%%; pointer-events:none; overflow:visible" xmlns="http://www.w3.org/2000/svg">
                  <defs>
                    <marker id="arrowGray" viewBox="0 0 14 14" refX="12" refY="7" markerWidth="10" markerHeight="10" orient="auto-start-reverse">
                      <path d="M 0 0 L 14 7 L 0 14 z" fill="#6b7280"/>
                    </marker>
                    <marker id="arrowBlue" viewBox="0 0 14 14" refX="12" refY="7" markerWidth="10" markerHeight="10" orient="auto-start-reverse">
                      <path d="M 0 0 L 14 7 L 0 14 z" fill="#2563eb"/>
                    </marker>
                    <marker id="arrowGreen" viewBox="0 0 14 14" refX="12" refY="7" markerWidth="10" markerHeight="10" orient="auto-start-reverse">
                      <path d="M 0 0 L 14 7 L 0 14 z" fill="#16a34a"/>
                    </marker>
                    <marker id="arrowViolet" viewBox="0 0 14 14" refX="12" refY="7" markerWidth="10" markerHeight="10" orient="auto-start-reverse">
                      <path d="M 0 0 L 14 7 L 0 14 z" fill="#7c3aed"/>
                    </marker>
                  </defs>
                """);

        for (RepairEstimateCatalogLink link : editorLinks) {
            boolean twoWay = isPathDependencyLink(link.getLinkType());
            boolean dashed = !isPathDependencyLink(link.getLinkType());
            LinkAnchors anchors = anchorsFor(link,
                    positions.get(link.getSourceNode().getId()),
                    positions.get(link.getTargetNode().getId()));
            appendPath(svg,
                    link.getId(),
                    link.getSourceNode().getId(),
                    link.getTargetNode().getId(),
                    Objects.equals(selectedLinkId, link.getId()),
                    positions.get(link.getSourceNode().getId()),
                    positions.get(link.getTargetNode().getId()),
                    markerColor(link.getLinkType()),
                    link.getLinkType(),
                    dashed,
                    twoWay,
                    anchors.sourceAnchor(),
                    anchors.targetAnchor());
        }
        svg.append("</svg>");
        return svg.toString();
    }

    private void appendPath(StringBuilder svg,
                            UUID linkId,
                            UUID sourceNodeId,
                            UUID targetNodeId,
                            boolean selected,
                            Position source,
                            Position target,
                            String markerColor,
                            RepairEstimateCatalogLinkType linkType,
                            boolean dashed,
                            boolean doubleArrow,
                            AnchorSide sourceAnchor,
                            AnchorSide targetAnchor) {
        if (source == null || target == null) {
            return;
        }
        int x1 = source.x() + NODE_WIDTH / 2;
        int y1 = anchorY(source, sourceAnchor);
        int x2 = target.x() + NODE_WIDTH / 2;
        int y2 = anchorY(target, targetAnchor);
        int curve = Math.max(80, Math.abs(y2 - y1) / 2);
        String markerId = switch (markerColor) {
            case "blue" -> "arrowBlue";
            case "green" -> "arrowGreen";
            case "violet" -> "arrowViolet";
            default -> "arrowGray";
        };
        svg.append("<path class=\"catalog-link-hitbox\" data-link-id=\"")
                .append(linkId)
                .append("\" data-source-node-id=\"")
                .append(sourceNodeId)
                .append("\" data-target-node-id=\"")
                .append(targetNodeId)
                .append("\" data-link-type=\"")
                .append(linkType.name())
                .append("\" data-source-anchor=\"")
                .append(sourceAnchor.name())
                .append("\" data-target-anchor=\"")
                .append(targetAnchor.name())
                .append("\" d=\"M ")
                .append(x1).append(' ').append(y1)
                .append(" C ").append(x1).append(' ').append(controlY(y1, sourceAnchor, curve))
                .append(", ").append(x2).append(' ').append(controlY(y2, targetAnchor, curve))
                .append(", ").append(x2).append(' ').append(y2)
                .append("\" fill=\"none\" stroke=\"transparent\" stroke-width=\"22\" stroke-linecap=\"round\" pointer-events=\"stroke\" cursor=\"pointer\"/>");
        svg.append("<path class=\"catalog-link-path\" data-link-id=\"")
                .append(linkId)
                .append("\" data-source-node-id=\"")
                .append(sourceNodeId)
                .append("\" data-target-node-id=\"")
                .append(targetNodeId)
                .append("\" data-link-type=\"")
                .append(linkType.name())
                .append("\" data-source-anchor=\"")
                .append(sourceAnchor.name())
                .append("\" data-target-anchor=\"")
                .append(targetAnchor.name())
                .append("\" d=\"M ")
                .append(x1).append(' ').append(y1)
                .append(" C ").append(x1).append(' ').append(controlY(y1, sourceAnchor, curve))
                .append(", ").append(x2).append(' ').append(controlY(y2, targetAnchor, curve))
                .append(", ").append(x2).append(' ').append(y2)
                .append("\" fill=\"none\" stroke=\"")
                .append(stroke(markerColor))
                .append("\" stroke-width=\"")
                .append(selected ? "4" : "3")
                .append("\" stroke-linecap=\"round\" pointer-events=\"none\" marker-end=\"url(#")
                .append(markerId)
                .append(")\"");
        if (doubleArrow) {
            svg.append(" marker-start=\"url(#").append(markerId).append(")\"");
        }
        if (dashed) {
            svg.append(" stroke-dasharray=\"8 8\"");
        }
        svg.append("/>");
    }

    private Map<UUID, Position> calculatePositions(List<RepairEstimateCatalogNode> nodes) {
        Map<UUID, Position> automatic = calculateTreePositions(nodes);
        Map<UUID, Position> result = new LinkedHashMap<>();
        for (RepairEstimateCatalogNode node : nodes) {
            Position fallback = automatic.getOrDefault(node.getId(), new Position(80, 80, nodeHeight(node)));
            int x = node.getCanvasX() == null ? fallback.x() : node.getCanvasX();
            int y = node.getCanvasY() == null ? fallback.y() : node.getCanvasY();
            result.put(node.getId(), new Position(x, y, nodeHeight(node)));
        }
        return result;
    }

    private Map<UUID, Position> calculateTreePositions(List<RepairEstimateCatalogNode> nodes) {
        Map<UUID, RepairEstimateCatalogNode> byId = nodes.stream()
                .collect(Collectors.toMap(RepairEstimateCatalogNode::getId, node -> node));
        Map<UUID, List<RepairEstimateCatalogNode>> childrenByParent = new HashMap<>();
        for (RepairEstimateCatalogNode node : nodes) {
            if (node.getParent() != null && byId.containsKey(node.getParent().getId())) {
                childrenByParent.computeIfAbsent(node.getParent().getId(), ignored -> new ArrayList<>()).add(node);
            }
        }
        childrenByParent.values().forEach(children -> children.sort(nodeComparator()));

        Map<UUID, Position> result = new LinkedHashMap<>();
        RepairEstimateCatalogNode rootNode = selectedCategory();
        if (rootNode == null) {
            return result;
        }
        layoutTree(rootNode, 60, 60, childrenByParent, result);
        return result;
    }

    private int layoutTree(RepairEstimateCatalogNode node,
                           int left,
                           int top,
                           Map<UUID, List<RepairEstimateCatalogNode>> childrenByParent,
                           Map<UUID, Position> result) {
        List<RepairEstimateCatalogNode> children = childrenByParent.getOrDefault(node.getId(), List.of());
        int currentNodeHeight = nodeHeight(node);
        if (children.isEmpty()) {
            result.put(node.getId(), new Position(left, top, currentNodeHeight));
            return left + NODE_WIDTH;
        }
        int currentLeft = left;
        for (RepairEstimateCatalogNode child : children) {
            currentLeft = layoutTree(child, currentLeft, top + currentNodeHeight + 48, childrenByParent, result) + SIBLING_GAP;
        }
        int subtreeRight = currentLeft - SIBLING_GAP;
        int nodeCenterLeft = left + Math.max(0, (subtreeRight - left - NODE_WIDTH) / 2);
        result.put(node.getId(), new Position(nodeCenterLeft, top, currentNodeHeight));
        return Math.max(subtreeRight, nodeCenterLeft + NODE_WIDTH);
    }

    private void attachCanvasBehavior() {
        getElement().executeJs("""
                const canvas = $0;
                window.__catalogServer = this.$server;
                window.__catalogCanvas = canvas;
                const minCanvasWidth = %d;
                const minCanvasHeight = %d;
                const canvasPadding = %d;
                const categoryId = $1;
                const defaultLinkType = $2 || 'FOLLOW_UP';
                const scroller = canvas.closest('vaadin-scroller');
                const state = {
                  canvas,
                  categoryId,
                  draftLinkType: defaultLinkType,
                  dirty: false,
                  links: new Map(),
                  nodeDrafts: new Map(),
                  deletedLinkIds: new Set()
                };
                window.__catalogDraftState = state;

                const previewId = 'catalog-link-preview';
                let preview = canvas.querySelector('#' + previewId);
                if (!preview) {
                  preview = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
                  preview.setAttribute('id', previewId);
                  preview.style.position = 'absolute';
                  preview.style.inset = '0';
                  preview.style.pointerEvents = 'none';
                  preview.style.overflow = 'visible';
                  preview.style.zIndex = '20';
                  canvas.appendChild(preview);
                }
                preview.replaceChildren();

                let localDelete = canvas.querySelector('.catalog-local-delete-link');
                if (!localDelete) {
                  localDelete = document.createElement('button');
                  localDelete.type = 'button';
                  localDelete.textContent = 'Удалить связь';
                  localDelete.className = 'catalog-local-delete-link';
                  localDelete.style.position = 'absolute';
                  localDelete.style.zIndex = '60';
                  localDelete.style.display = 'none';
                  localDelete.style.height = '30px';
                  localDelete.style.fontSize = '12px';
                  localDelete.style.border = '1px solid #fecaca';
                  localDelete.style.borderRadius = '6px';
                  localDelete.style.background = '#fff1f2';
                  localDelete.style.color = '#be123c';
                  localDelete.style.cursor = 'pointer';
                  canvas.appendChild(localDelete);
                }

                const saveButton = document.querySelector('vaadin-button.catalog-save-button');
                const setDirty = (value = true) => {
                  state.dirty = value;
                  if (saveButton) {
                    saveButton.style.border = value ? '2px solid #2563eb' : '';
                    saveButton.style.background = value ? '#dbeafe' : '';
                  }
                };
                window.__catalogSetDraftLinkType = (value) => {
                  state.draftLinkType = value || 'FOLLOW_UP';
                };
                window.__catalogBeforeUnload = (event) => {
                  if (!window.__catalogDraftState?.dirty) return;
                  event.preventDefault();
                  event.returnValue = '';
                };
                window.addEventListener('beforeunload', window.__catalogBeforeUnload);

                const canvasPoint = (clientX, clientY) => {
                  const rect = canvas.getBoundingClientRect();
                  return { x: clientX - rect.left, y: clientY - rect.top };
                };
                const linkPathD = (x1, y1, x2, y2, sourceAnchor, targetAnchor) => {
                  const curve = Math.max(80, Math.abs(y2 - y1) / 2);
                  const c1 = sourceAnchor === 'TOP' ? y1 - curve : y1 + curve;
                  const c2 = targetAnchor === 'TOP' ? y2 - curve : y2 + curve;
                  return `M ${x1} ${y1} C ${x1} ${c1}, ${x2} ${c2}, ${x2} ${y2}`;
                };
                const linkHandlePoint = (nodeId, anchor) => {
                  if (!nodeId) return null;
                  const node = canvas.querySelector(`.catalog-node[data-node-id="${CSS.escape(nodeId)}"]`);
                  if (!node) return null;
                  const handle = node.querySelector(`.catalog-link-handle[data-anchor="${CSS.escape(anchor || 'BOTTOM')}"]`);
                  if (!handle) return null;
                  const rect = handle.getBoundingClientRect();
                  return canvasPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
                };
                const syncCanvasSize = () => {
                  const viewportWidth = Math.max(0, scroller?.clientWidth || canvas.parentElement?.clientWidth || 0);
                  const viewportHeight = Math.max(0, scroller?.clientHeight || canvas.parentElement?.clientHeight || 0);
                  let contentRight = 0;
                  let contentBottom = 0;
                  canvas.querySelectorAll('.catalog-node').forEach((node) => {
                    contentRight = Math.max(contentRight, node.offsetLeft + node.offsetWidth);
                    contentBottom = Math.max(contentBottom, node.offsetTop + node.offsetHeight);
                  });
                  const width = Math.max(minCanvasWidth, viewportWidth, contentRight + canvasPadding);
                  const height = Math.max(minCanvasHeight, viewportHeight, contentBottom + canvasPadding);
                  canvas.style.width = width + 'px';
                  canvas.style.height = height + 'px';
                  [preview, canvas.querySelector('.catalog-link-layer svg')].forEach((svg) => {
                    if (!svg) return;
                    svg.setAttribute('width', String(width));
                    svg.setAttribute('height', String(height));
                  });
                };

                const isPathDependency = (type) => type === 'DEPENDENCY';
                const markerFor = (type) => isPathDependency(type) ? 'arrowBlue'
                  : type === 'FOLLOW_UP' ? 'arrowGreen'
                  : 'arrowGray';
                const strokeFor = (type) => isPathDependency(type) ? '#2563eb'
                  : type === 'FOLLOW_UP' ? '#16a34a'
                  : '#6b7280';
                const redrawLink = (link) => {
                  const sourcePoint = linkHandlePoint(link.sourceNodeId, link.sourceAnchor);
                  const targetPoint = linkHandlePoint(link.targetNodeId, link.targetAnchor);
                  if (!sourcePoint || !targetPoint) return;
                  const d = linkPathD(sourcePoint.x, sourcePoint.y, targetPoint.x, targetPoint.y,
                    link.sourceAnchor, link.targetAnchor);
                  canvas.querySelectorAll(`[data-link-id="${CSS.escape(link.id)}"]`).forEach((path) => {
                    path.setAttribute('d', d);
                  });
                };
                const redrawAllLinks = () => {
                  state.links.forEach((link) => {
                    if (!link.deleted) redrawLink(link);
                  });
                  positionDeleteButton();
                };
                const selectedPath = () => state.selectedLinkId
                  ? canvas.querySelector(`.catalog-link-path[data-link-id="${CSS.escape(state.selectedLinkId)}"]`)
                  : null;
                const positionDeleteButton = () => {
                  if (!state.selectedLinkId) {
                    localDelete.style.display = 'none';
                    return;
                  }
                  const link = state.links.get(state.selectedLinkId);
                  if (!link || link.deleted) {
                    localDelete.style.display = 'none';
                    return;
                  }
                  const a = linkHandlePoint(link.sourceNodeId, link.sourceAnchor);
                  const b = linkHandlePoint(link.targetNodeId, link.targetAnchor);
                  if (!a || !b) {
                    localDelete.style.display = 'none';
                    return;
                  }
                  localDelete.style.left = `${Math.max(0, ((a.x + b.x) / 2) - 54)}px`;
                  localDelete.style.top = `${Math.max(0, ((a.y + b.y) / 2) - 42)}px`;
                  localDelete.style.display = 'block';
                };
                const selectLink = (id) => {
                  state.selectedLinkId = id || null;
                  canvas.querySelectorAll('.catalog-link-path').forEach((path) => {
                    const selected = path.dataset.linkId === state.selectedLinkId;
                    path.setAttribute('stroke-width', selected ? '5' : '3');
                    path.style.filter = selected ? 'drop-shadow(0 2px 4px rgba(37,99,235,.35))' : '';
                  });
                  positionDeleteButton();
                };
                const deleteSelectedLink = () => {
                  if (!state.selectedLinkId) return;
                  const link = state.links.get(state.selectedLinkId);
                  if (!link) return;
                  if (!link.id.startsWith('tmp-')) state.deletedLinkIds.add(link.id);
                  link.deleted = true;
                  canvas.querySelectorAll(`[data-link-id="${CSS.escape(link.id)}"]`).forEach((path) => path.remove());
                  state.selectedLinkId = null;
                  localDelete.style.display = 'none';
                  setDirty();
                };
                localDelete.onclick = (event) => {
                  event.preventDefault();
                  event.stopPropagation();
                  deleteSelectedLink();
                };

                const activeSvg = () => canvas.querySelector('.catalog-link-layer svg');
                const createSvgPath = (tag, link, stroke, width) => {
                  const sourcePoint = linkHandlePoint(link.sourceNodeId, link.sourceAnchor);
                  const targetPoint = linkHandlePoint(link.targetNodeId, link.targetAnchor);
                  if (!sourcePoint || !targetPoint) return null;
                  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
                  path.setAttribute('class', tag);
                  path.dataset.linkId = link.id;
                  path.dataset.sourceNodeId = link.sourceNodeId;
                  path.dataset.targetNodeId = link.targetNodeId;
                  path.dataset.sourceAnchor = link.sourceAnchor;
                  path.dataset.targetAnchor = link.targetAnchor;
                  path.dataset.linkType = link.linkType;
                  path.setAttribute('d', linkPathD(sourcePoint.x, sourcePoint.y, targetPoint.x, targetPoint.y,
                    link.sourceAnchor, link.targetAnchor));
                  path.setAttribute('fill', 'none');
                  path.setAttribute('stroke', stroke);
                  path.setAttribute('stroke-width', String(width));
                  path.setAttribute('stroke-linecap', 'round');
                  if (tag === 'catalog-link-hitbox') {
                    path.setAttribute('pointer-events', 'stroke');
                    path.setAttribute('cursor', 'pointer');
                    path.addEventListener('click', (event) => {
                      event.preventDefault();
                      event.stopPropagation();
                      selectLink(link.id);
                    });
                  } else {
                    path.setAttribute('pointer-events', 'none');
                    path.setAttribute('marker-end', `url(#${markerFor(link.linkType)})`);
                    if (isPathDependency(link.linkType)) {
                      path.setAttribute('marker-start', `url(#${markerFor(link.linkType)})`);
                    }
                    if (!isPathDependency(link.linkType)) {
                      path.setAttribute('stroke-dasharray', '8 8');
                    }
                  }
                  return path;
                };
                const appendLink = (link) => {
                  const svg = activeSvg();
                  if (!svg) return;
                  const hitbox = createSvgPath('catalog-link-hitbox', link, 'transparent', 22);
                  const path = createSvgPath('catalog-link-path', link, strokeFor(link.linkType), 3);
                  if (hitbox && path) {
                    svg.appendChild(hitbox);
                    svg.appendChild(path);
                  }
                  state.links.set(link.id, link);
                  redrawLink(link);
                };
                const createLocalLink = (sourceNodeId, targetNodeId, sourceAnchor, targetAnchor) => {
                  if (!sourceNodeId || !targetNodeId || sourceNodeId === targetNodeId) return;
                  const target = canvas.querySelector(`.catalog-node[data-node-id="${CSS.escape(targetNodeId)}"]`);
                  if (target?.dataset.nodeType === 'CATEGORY') return;
                  const duplicate = Array.from(state.links.values()).some((link) =>
                    !link.deleted
                    && link.sourceNodeId === sourceNodeId
                    && link.targetNodeId === targetNodeId
                    && link.linkType === state.draftLinkType);
                  if (duplicate) return;
                  const link = {
                    id: `tmp-${Date.now()}-${Math.random().toString(16).slice(2)}`,
                    sourceNodeId,
                    targetNodeId,
                    sourceAnchor: sourceAnchor || 'BOTTOM',
                    targetAnchor: targetAnchor || 'TOP',
                    linkType: state.draftLinkType,
                    deleted: false
                  };
                  appendLink(link);
                  selectLink(link.id);
                  setDirty();
                };

                const drawDraftLink = (x1, y1, x2, y2) => {
                  preview.replaceChildren();
                  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
                  path.setAttribute('d', linkPathD(x1, y1, x2, y2, 'BOTTOM', 'TOP'));
                  path.setAttribute('fill', 'none');
                  path.setAttribute('stroke', '#2563eb');
                  path.setAttribute('stroke-width', '3');
                  path.setAttribute('stroke-linecap', 'round');
                  path.setAttribute('stroke-dasharray', '8 8');
                  preview.appendChild(path);
                };
                const clearDraftLink = () => preview.replaceChildren();

                const bindNode = (node) => {
                  if (node.dataset.dragReady === 'true') return;
                  node.dataset.dragReady = 'true';
                  let dragging = false;
                  let startX = 0;
                  let startY = 0;
                  let initialLeft = 0;
                  let initialTop = 0;
                  let initialScrollLeft = 0;
                  let initialScrollTop = 0;
                  node.querySelectorAll('.catalog-link-handle').forEach((handle) => {
                    handle.addEventListener('pointerdown', (event) => {
                      event.preventDefault();
                      event.stopPropagation();
                      const rect = handle.getBoundingClientRect();
                      const center = canvasPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
                      window.__catalogLinkStart = {
                        canvas,
                        nodeId: node.dataset.nodeId,
                        anchor: handle.dataset.anchor || 'BOTTOM',
                        x: center.x,
                        y: center.y
                      };
                      drawDraftLink(center.x, center.y, center.x, center.y);
                    });
                  });
                  node.addEventListener('pointerdown', (event) => {
                    if (event.target?.closest?.('.catalog-link-handle')) return;
                    if (event.target?.closest?.('vaadin-button, button, .catalog-edit-button')) return;
                    if (event.button !== 0) return;
                    dragging = true;
                    startX = event.clientX;
                    startY = event.clientY;
                    initialLeft = parseInt(node.style.left || '0', 10);
                    initialTop = parseInt(node.style.top || '0', 10);
                    initialScrollLeft = scroller?.scrollLeft || 0;
                    initialScrollTop = scroller?.scrollTop || 0;
                    node.style.cursor = 'grabbing';
                    node.setPointerCapture(event.pointerId);
                  });
                  node.addEventListener('click', (event) => {
                    if (event.target?.closest?.('.catalog-link-handle')) return;
                    if (event.target?.closest?.('vaadin-button, button, .catalog-edit-button')) return;
                    selectLink(null);
                    canvas.querySelectorAll('.catalog-node').forEach((item) => item.style.border = '1px solid #d6dbe2');
                    node.style.border = '2px solid #2563eb';
                  });
                  node.addEventListener('pointermove', (event) => {
                    if (!dragging) return;
                    if (scroller) {
                      const edge = 72;
                      const step = 28;
                      const rect = scroller.getBoundingClientRect();
                      if (event.clientY > rect.bottom - edge) scroller.scrollTop += step;
                      if (event.clientY < rect.top + edge) scroller.scrollTop -= step;
                      if (event.clientX > rect.right - edge) scroller.scrollLeft += step;
                      if (event.clientX < rect.left + edge) scroller.scrollLeft -= step;
                    }
                    const scrollDeltaX = (scroller?.scrollLeft || 0) - initialScrollLeft;
                    const scrollDeltaY = (scroller?.scrollTop || 0) - initialScrollTop;
                    const left = Math.max(0, initialLeft + event.clientX - startX + scrollDeltaX);
                    const top = Math.max(0, initialTop + event.clientY - startY + scrollDeltaY);
                    node.style.left = left + 'px';
                    node.style.top = top + 'px';
                    syncCanvasSize();
                    redrawAllLinks();
                  });
                  node.addEventListener('pointerup', () => {
                    if (!dragging) return;
                    dragging = false;
                    node.style.cursor = 'grab';
                    syncCanvasSize();
                    redrawAllLinks();
                    setDirty();
                  });
                };

                canvas.querySelectorAll('.catalog-link-hitbox').forEach((path) => {
                  const link = {
                    id: path.dataset.linkId,
                    sourceNodeId: path.dataset.sourceNodeId,
                    targetNodeId: path.dataset.targetNodeId,
                    sourceAnchor: path.dataset.sourceAnchor || 'BOTTOM',
                    targetAnchor: path.dataset.targetAnchor || 'TOP',
                    linkType: path.dataset.linkType || 'FOLLOW_UP',
                    deleted: false
                  };
                  state.links.set(link.id, link);
                  path.addEventListener('click', (event) => {
                    event.preventDefault();
                    event.stopPropagation();
                    selectLink(link.id);
                  });
                });
                canvas.querySelectorAll('.catalog-node').forEach(bindNode);
                canvas.addEventListener('click', (event) => {
                  if (event.target === canvas) selectLink(null);
                });

                if (window.__catalogMoveListener) {
                  window.removeEventListener('pointermove', window.__catalogMoveListener, true);
                }
                if (window.__catalogUpListener) {
                  window.removeEventListener('pointerup', window.__catalogUpListener, true);
                }
                window.__catalogMoveListener = (event) => {
                  const source = window.__catalogLinkStart;
                  if (!source || source.canvas !== canvas) return;
                  const point = canvasPoint(event.clientX, event.clientY);
                  drawDraftLink(source.x, source.y, point.x, point.y);
                };
                window.__catalogUpListener = (event) => {
                  const source = window.__catalogLinkStart;
                  if (!source || source.canvas !== canvas) return;
                  clearDraftLink();
                  window.__catalogLinkStart = null;
                  const element = document.elementFromPoint(event.clientX, event.clientY);
                  const targetNode = element?.closest?.('.catalog-node');
                  if (!targetNode || !targetNode.dataset.nodeId || targetNode.dataset.nodeId === source.nodeId) return;
                  const targetHandle = element?.closest?.('.catalog-link-handle');
                  let targetAnchor = targetHandle?.dataset?.anchor;
                  if (!targetAnchor) {
                    const rect = targetNode.getBoundingClientRect();
                    targetAnchor = event.clientY <= rect.top + rect.height / 2 ? 'TOP' : 'BOTTOM';
                  }
                  createLocalLink(source.nodeId, targetNode.dataset.nodeId, source.anchor, targetAnchor);
                };
                window.addEventListener('pointermove', window.__catalogMoveListener, true);
                window.addEventListener('pointerup', window.__catalogUpListener, true);
                if (window.__catalogResizeListener) {
                  window.removeEventListener('resize', window.__catalogResizeListener);
                }
                window.__catalogResizeListener = () => {
                  syncCanvasSize();
                  redrawAllLinks();
                };
                window.addEventListener('resize', window.__catalogResizeListener);

                window.__catalogAddNodes = (nodes) => {
                  const existingIds = new Set(Array.from(canvas.querySelectorAll('.catalog-node')).map((node) => node.dataset.nodeId));
                  const currentNodes = Array.from(canvas.querySelectorAll('.catalog-node'));
                  let nextLeft = 80 + (currentNodes.length %% 4) * 320;
                  let nextTop = 260 + Math.floor(currentNodes.length / 4) * 260;
                  const appendDisplayRows = (node, data) => {
                    node.querySelectorAll(':scope > :not(.catalog-link-handle)').forEach((child) => child.remove());
                    const firstHandle = node.querySelector('.catalog-link-handle');
                    const rows = data.rows || [];
                    rows.forEach((row) => {
                      const div = document.createElement('div');
                      div.style.display = 'grid';
                      div.style.gridTemplateColumns = '112px 1fr';
                      div.style.minHeight = '40px';
                      div.style.borderBottom = '1px solid #eef2f6';
                      const left = document.createElement('div');
                      left.textContent = row.label || '';
                      left.style.padding = '10px 12px';
                      left.style.fontSize = '13px';
                      left.style.fontWeight = '600';
                      left.style.background = '#fbfcfe';
                      const right = document.createElement('div');
                      right.textContent = row.value || '';
                      right.style.padding = '10px 12px';
                      right.style.fontSize = '13px';
                      div.append(left, right);
                      node.insertBefore(div, firstHandle);
                    });
                    const editRow = document.createElement('div');
                    editRow.style.display = 'grid';
                    editRow.style.gridTemplateColumns = '112px 1fr';
                    editRow.style.minHeight = '40px';
                    const editLeft = document.createElement('div');
                    editLeft.style.background = '#fbfcfe';
                    const editRight = document.createElement('div');
                    editRight.style.padding = '10px 12px';
                    const edit = document.createElement('button');
                    edit.type = 'button';
                    edit.textContent = 'Редактировать';
                    edit.className = 'catalog-edit-button';
                    edit.onclick = () => window.__catalogServer.editNode(data.id);
                    editRight.appendChild(edit);
                    editRow.append(editLeft, editRight);
                    node.insertBefore(editRow, firstHandle);
                  };
                  nodes.forEach((data) => {
                    if (!data || !data.id) return;
                    if (existingIds.has(data.id)) {
                      const existing = canvas.querySelector(`.catalog-node[data-node-id="${CSS.escape(data.id)}"]`);
                      if (!existing) return;
                      existing.dataset.nodeType = data.nodeType || '';
                      state.nodeDrafts.set(data.id, data);
                      appendDisplayRows(existing, data);
                      syncCanvasSize();
                      redrawAllLinks();
                      setDirty();
                      return;
                    }
                    const node = document.createElement('div');
                    node.className = 'catalog-node';
                    node.dataset.nodeId = data.id;
                    node.dataset.nodeType = data.nodeType || '';
                    node.style.position = 'absolute';
                    node.style.left = `${data.x ?? nextLeft}px`;
                    node.style.top = `${data.y ?? nextTop}px`;
                    node.style.width = '280px';
                    node.style.minHeight = '132px';
                    node.style.background = '#ffffff';
                    node.style.border = '1px solid #d6dbe2';
                    node.style.borderRadius = '8px';
                    node.style.boxShadow = '0 10px 22px rgba(15, 23, 42, 0.07)';
                    node.style.overflow = 'visible';
                    node.style.zIndex = '10';
                    node.style.cursor = 'grab';
                    node.style.userSelect = 'none';
                    ['TOP', 'BOTTOM'].forEach((anchor) => {
                      const handle = document.createElement('div');
                      handle.className = 'catalog-link-handle';
                      handle.dataset.nodeId = data.id;
                      handle.dataset.anchor = anchor;
                      handle.style.position = 'absolute';
                      handle.style.left = 'calc(50%% - 10px)';
                      handle.style[anchor === 'TOP' ? 'top' : 'bottom'] = '-12px';
                      handle.style.width = '20px';
                      handle.style.height = '20px';
                      handle.style.borderRadius = '50%%';
                      handle.style.background = '#2563eb';
                      handle.style.border = '2px solid #ffffff';
                      handle.style.zIndex = '30';
                      handle.style.cursor = 'crosshair';
                      node.appendChild(handle);
                    });
                    appendDisplayRows(node, data);
                    canvas.appendChild(node);
                    bindNode(node);
                    state.nodeDrafts.set(data.id, data);
                    existingIds.add(data.id);
                    nextLeft += 320;
                    if (nextLeft > 1160) {
                      nextLeft = 80;
                      nextTop += 260;
                    }
                  });
                  syncCanvasSize();
                  setDirty();
                };

                window.__catalogSaveDraft = () => {
                  const nodes = Array.from(canvas.querySelectorAll('.catalog-node')).map((node) => {
                    const draft = state.nodeDrafts.get(node.dataset.nodeId) || {};
                    return {
                      ...draft,
                      id: node.dataset.nodeId,
                      x: parseInt(node.style.left || '0', 10),
                      y: parseInt(node.style.top || '0', 10),
                      removedFromCanvas: false
                    };
                  });
                  const links = Array.from(state.links.values()).map((link) => ({
                    id: link.id,
                    sourceNodeId: link.sourceNodeId,
                    targetNodeId: link.targetNodeId,
                    sourceAnchor: link.sourceAnchor,
                    targetAnchor: link.targetAnchor,
                    linkType: link.linkType,
                    deleted: Boolean(link.deleted || state.deletedLinkIds.has(link.id))
                  }));
                  const payload = { categoryId: state.categoryId, nodes, links };
                  window.__catalogServer.saveCatalog(JSON.stringify(payload));
                };

                requestAnimationFrame(() => {
                  syncCanvasSize();
                  redrawAllLinks();
                });
                """.formatted(MIN_CANVAS_WIDTH, MIN_CANVAS_HEIGHT, CANVAS_PADDING),
                canvas.getElement(),
                selectedCategoryId == null ? "" : selectedCategoryId.toString(),
                draftLinkType.name());
    }

    private void openCategoryDialog(RepairEstimateCatalogNode source) {
        boolean creating = source == null;
        RepairEstimateCatalogNode node = creating
                ? dataManager.create(RepairEstimateCatalogNode.class)
                : dataManager.load(RepairEstimateCatalogNode.class).id(source.getId()).one();

        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(creating ? "Добавить категорию" : "Редактировать категорию");
        dialog.setWidth("620px");

        TextField name = new TextField("Имя");
        name.setValue(safe(node.getName()));
        name.setWidthFull();

        TextField code = new TextField("Код");
        code.setValue(safe(node.getCode()));
        code.setWidthFull();

        Checkbox includeInEstimate = new Checkbox("Учет в смете");
        includeInEstimate.setValue(Boolean.TRUE.equals(node.getIncludeInEstimate()));

        Checkbox furnitureCategory = new Checkbox("Является мебелью");
        furnitureCategory.setValue(Boolean.TRUE.equals(node.getFurnitureCategory()));

        ComboBox<QueueBindingOption> workQueue = new ComboBox<>("Рабочая очередь");
        List<QueueBindingOption> queueOptions = loadQueueBindingOptions();
        workQueue.setItems(queueOptions);
        workQueue.setItemLabelGenerator(QueueBindingOption::label);
        workQueue.setValue(resolveQueueBindingOption(node.getWorkQueue(), queueOptions));
        workQueue.setRequired(true);
        workQueue.setWidthFull();

        Checkbox active = new Checkbox("Активно");
        active.setValue(node.getActive() == null || Boolean.TRUE.equals(node.getActive()));

        IntegerField sortOrder = new IntegerField("Сортировка");
        sortOrder.setValue(node.getSortOrder());

        VerticalLayout form = new VerticalLayout(
                categoryStaticRow("Тип", "Категория"),
                name,
                code,
                includeInEstimate,
                furnitureCategory,
                workQueue,
                active,
                sortOrder
        );
        form.setPadding(false);

        Button save = new Button("Сохранить", event -> {
            if (name.getValue().isBlank() || code.getValue().isBlank() || workQueue.getValue() == null) {
                Notification.show("Заполните имя, код и рабочую очередь");
                return;
            }
            String normalizedCode = normalizeCode(code.getValue());
            RepairEstimateCatalogNode nodeToSave = prepareNodeForSave(node, creating, normalizedCode);
            if (nodeToSave == null) {
                return;
            }
            nodeToSave.setNodeType(RepairEstimateCatalogNodeType.CATEGORY);
            nodeToSave.setParent(null);
            nodeToSave.setName(name.getValue());
            nodeToSave.setCode(normalizedCode);
            nodeToSave.setIncludeInEstimate(includeInEstimate.getValue());
            nodeToSave.setFurnitureCategory(Boolean.TRUE.equals(furnitureCategory.getValue()));
            QueueBindingOption selectedBinding = workQueue.getValue();
            nodeToSave.setWorkQueue(selectedBinding == null ? null : selectedBinding.queue());
            nodeToSave.setRouteQueueKind(selectedBinding == null ? WorkQueueKind.REPAIR : selectedBinding.queue().getQueueKind());
            nodeToSave.setActive(active.getValue());
            nodeToSave.setSortOrder(sortOrder.getValue());
            if (creating) {
                nodeToSave.setCanvasX(null);
                nodeToSave.setCanvasY(null);
            }
            RepairEstimateCatalogNode saved = dataManager.save(nodeToSave);
            selectedCategoryId = saved.getId();
            dialog.close();
            reloadCategories();
            openCategory(saved);
        });
        dialog.add(form);
        dialog.getFooter().add(new Button("Отмена", e -> dialog.close()), save);
        dialog.open();
    }

    private Div categoryStaticRow(String label, String value) {
        Div row = new Div();
        row.getStyle()
                .set("display", "grid")
                .set("grid-template-columns", "140px 1fr")
                .set("align-items", "center")
                .set("border", "1px solid #dde3ea")
                .set("border-radius", "8px")
                .set("overflow", "hidden");
        Div left = cell(label);
        left.getStyle().set("font-weight", "600").set("background", "#fbfcfe");
        Div right = cell(value);
        row.add(left, right);
        return row;
    }

    private void openNodeDialog(RepairEstimateCatalogNode source) {
        boolean creating = source == null;
        RepairEstimateCatalogNode node = creating
                ? dataManager.create(RepairEstimateCatalogNode.class)
                : dataManager.load(RepairEstimateCatalogNode.class).id(source.getId()).one();

        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(creating ? "Добавить блок" : "Редактировать блок");
        dialog.setWidth("680px");

        ComboBox<RepairEstimateCatalogNodeType> nodeType = new ComboBox<>("Тип");
        nodeType.setItems(RepairEstimateCatalogNodeType.SUBCATEGORY,
                RepairEstimateCatalogNodeType.WORK,
                RepairEstimateCatalogNodeType.MATERIAL,
                RepairEstimateCatalogNodeType.LOCATION,
                RepairEstimateCatalogNodeType.OPTION);
        nodeType.setItemLabelGenerator(this::nodeTypeLabel);
        nodeType.setValue(node.getNodeType() == RepairEstimateCatalogNodeType.CATEGORY ? null : node.getNodeType());
        nodeType.setRequired(true);

        TextField name = new TextField("Имя");
        name.setValue(safe(node.getName()));
        name.setWidthFull();

        TextField code = new TextField("Код");
        code.setValue(safe(node.getCode()));
        code.setWidthFull();

        Checkbox includeInEstimate = new Checkbox("Учет в смете");
        includeInEstimate.setValue(Boolean.TRUE.equals(node.getIncludeInEstimate()));

        Checkbox commonItem = new Checkbox(localizedMessage("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.commonItem", "Общий"));
        commonItem.setValue(Boolean.TRUE.equals(node.getCommonItem()));

        TextField unit = new TextField("Единица измерения");
        unit.setValue(safe(node.getUnit()));
        unit.setWidthFull();

        BigDecimalField unitPrice = new BigDecimalField("Цена за единицу");
        unitPrice.setValue(node.getUnitPrice());

        IntegerField defaultQuantity = new IntegerField("Количество по умолчанию");
        defaultQuantity.setValue(node.getDefaultQuantity());

        IntegerField durationMinutes = new IntegerField("Длительность, мин");
        durationMinutes.setValue(node.getDurationMinutes());

        Checkbox additionalOption = new Checkbox("Доп. опция");
        additionalOption.setValue(Boolean.TRUE.equals(node.getAdditionalOption()));

        Checkbox active = new Checkbox("Активно");
        active.setValue(node.getActive() == null || Boolean.TRUE.equals(node.getActive()));

        IntegerField sortOrder = new IntegerField("Сортировка");
        sortOrder.setValue(node.getSortOrder());

        TextArea comment = new TextArea("Комментарий");
        comment.setValue(safe(node.getComment()));
        comment.setWidthFull();

        VerticalLayout form = new VerticalLayout(nodeType, name, code, includeInEstimate, commonItem,
                unit, unitPrice, defaultQuantity, durationMinutes, additionalOption, active, sortOrder, comment);
        form.setPadding(false);
        form.setWidthFull();

        Runnable toggleFields = () -> {
            RepairEstimateCatalogNodeType value = nodeType.getValue();
            boolean priced = value == RepairEstimateCatalogNodeType.WORK || value == RepairEstimateCatalogNodeType.MATERIAL;
            boolean work = value == RepairEstimateCatalogNodeType.WORK;
            unit.setVisible(priced);
            unitPrice.setVisible(priced);
            defaultQuantity.setVisible(priced);
            durationMinutes.setVisible(work);
            additionalOption.setVisible(value == RepairEstimateCatalogNodeType.OPTION);
        };
        nodeType.addValueChangeListener(event -> toggleFields.run());
        toggleFields.run();

        Button save = new Button("Сохранить", event -> {
            if (nodeType.getValue() == null || name.getValue().isBlank() || code.getValue().isBlank()) {
                Notification.show("Заполните тип, имя и код");
                return;
            }
            String normalizedCode = normalizeCode(code.getValue());
            RepairEstimateCatalogNode nodeToSave = prepareNodeForSave(node, creating, normalizedCode);
            if (nodeToSave == null) {
                return;
            }
            nodeToSave.setNodeType(nodeType.getValue());
            nodeToSave.setName(name.getValue());
            nodeToSave.setCode(normalizedCode);
            if (creating) {
                nodeToSave.setParent(selectedCategory());
            }
            nodeToSave.setIncludeInEstimate(includeInEstimate.getValue());
            nodeToSave.setCommonItem(Boolean.TRUE.equals(commonItem.getValue()));
            nodeToSave.setUnit(pricedType(nodeType.getValue()) ? blankToNull(unit.getValue()) : null);
            nodeToSave.setUnitPrice(pricedType(nodeType.getValue()) ? unitPrice.getValue() : null);
            nodeToSave.setDefaultQuantity(pricedType(nodeType.getValue()) ? defaultQuantity.getValue() : null);
            nodeToSave.setDurationMinutes(nodeType.getValue() == RepairEstimateCatalogNodeType.WORK ? durationMinutes.getValue() : null);
            nodeToSave.setAdditionalOption(nodeType.getValue() == RepairEstimateCatalogNodeType.OPTION && additionalOption.getValue());
            nodeToSave.setActive(active.getValue());
            nodeToSave.setSortOrder(sortOrder.getValue());
            nodeToSave.setComment(blankToNull(comment.getValue()));
            nodeToSave.setRouteQueueKind(null);
            if (creating) {
                Position nextPosition = nextCanvasPosition();
                nodeToSave.setCanvasX(nextPosition.x());
                nodeToSave.setCanvasY(nextPosition.y());
                RepairEstimateCatalogNode savedNode = dataManager.save(nodeToSave);
                selectedNode = savedNode;
                dialog.close();
                pushNodesToClientCanvas(List.of(savedNode));
                return;
            }
            selectedNode = nodeToSave;
            dialog.close();
            pushNodesToClientCanvas(List.of(nodeToSave));
        });
        dialog.add(form);
        if (!creating) {
            Button delete = new Button(deleteNodeActionLabel(node), event -> {
                deleteNodeFromCanvasOrCatalog(node);
                dialog.close();
            });
            delete.getStyle().set("color", "var(--lumo-error-text-color)");
            dialog.getFooter().add(delete);
        }
        dialog.getFooter().add(new Button("Отмена", e -> dialog.close()), save);
        dialog.open();
    }

    private String deleteNodeActionLabel(RepairEstimateCatalogNode node) {
        if (node == null) {
            return "Удалить";
        }
        RepairEstimateCatalogNodeType type = node.getNodeType();
        if (type == RepairEstimateCatalogNodeType.WORK || type == RepairEstimateCatalogNodeType.MATERIAL) {
            return "Убрать из конструктора";
        }
        return "Удалить полностью";
    }

    private void deleteNodeFromCanvasOrCatalog(RepairEstimateCatalogNode node) {
        if (node == null || node.getId() == null || node.getNodeType() == RepairEstimateCatalogNodeType.CATEGORY) {
            return;
        }
        if (node.getNodeType() == RepairEstimateCatalogNodeType.WORK || node.getNodeType() == RepairEstimateCatalogNodeType.MATERIAL) {
            removeNodeFromConstructorLayer(node.getId());
            Notification.show("Блок убран из конструктора. В справочнике он сохранен.");
        } else {
            hardDeleteCatalogNode(node.getId());
            Notification.show("Блок удален из базы данных");
        }
        if (selectedNode != null && Objects.equals(selectedNode.getId(), node.getId())) {
            selectedNode = null;
        }
        selectedLinkId = null;
        reloadEditor();
    }

    private Position nextCanvasPosition() {
        int index = Math.max(0, editorNodes.size() - 1);
        int column = index % 4;
        int row = index / 4;
        return new Position(
                80 + column * (NODE_WIDTH + SIBLING_GAP),
                260 + row * 260,
                NODE_MIN_HEIGHT
        );
    }

    private void openNodeSelectionDialog() {
        RepairEstimateCatalogNode category = selectedCategory();
        RepairEstimateCatalogNode sourceNode = selectedNode != null ? selectedNode : category;
        if (category == null || sourceNode == null) {
            Notification.show("Сначала выберите категорию или блок");
            return;
        }

        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Добавить блок из списка");
        dialog.setWidth("920px");
        dialog.setHeight("760px");

        ComboBox<RepairEstimateCatalogNodeType> sectionType = new ComboBox<>("Раздел");
        sectionType.setItems(RepairEstimateCatalogNodeType.WORK, RepairEstimateCatalogNodeType.MATERIAL);
        sectionType.setItemLabelGenerator(this::nodeTypeLabel);
        sectionType.setValue(RepairEstimateCatalogNodeType.WORK);
        sectionType.setWidth("280px");

        Checkbox showCommonOnly = new Checkbox("Показать общее");
        showCommonOnly.setValue(false);

        Grid<RepairEstimateCatalogNode> grid = new Grid<>();
        grid.setSelectionMode(Grid.SelectionMode.MULTI);
        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES);
        grid.setWidthFull();
        grid.setHeight("560px");
        grid.addColumn(RepairEstimateCatalogNode::getName).setHeader("Имя").setAutoWidth(true).setFlexGrow(1);
        grid.addColumn(RepairEstimateCatalogNode::getCode).setHeader("Код").setAutoWidth(true);
        grid.addColumn(node -> yesNo(node.getIncludeInEstimate())).setHeader("Учет в смете").setAutoWidth(true);
        grid.addColumn(node -> yesNo(node.getCommonItem())).setHeader(localizedMessage("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.commonItem", "Общий")).setAutoWidth(true);
        grid.addColumn(node -> safe(node.getUnit())).setHeader("Единица").setAutoWidth(true);
        grid.addColumn(node -> money(node.getUnitPrice())).setHeader("Цена").setAutoWidth(true);
        grid.addColumn(node -> node.getDefaultQuantity() == null ? "" : String.valueOf(node.getDefaultQuantity())).setHeader("Кол-во").setAutoWidth(true);

        Runnable reload = () -> grid.setItems(loadSectionItemsForCategory(
                category.getId(),
                sectionType.getValue(),
                sourceNode.getId(),
                Boolean.TRUE.equals(showCommonOnly.getValue())));
        sectionType.addValueChangeListener(event -> reload.run());
        showCommonOnly.addValueChangeListener(event -> reload.run());
        reload.run();

        Button save = new Button("Добавить выбранное", event -> {
            Set<RepairEstimateCatalogNode> selectedItems = ((GridMultiSelectionModel<RepairEstimateCatalogNode>) grid.getSelectionModel()).getSelectedItems();
            if (selectedItems.isEmpty()) {
                Notification.show("Выберите хотя бы один блок");
                return;
            }
            pushNodesToClientCanvas(selectedItems.stream()
                    .filter(item -> !Objects.equals(item.getId(), sourceNode.getId()))
                    .sorted(nodeComparator())
                    .toList());
            dialog.close();
        });

        HorizontalLayout filters = new HorizontalLayout(sectionType, showCommonOnly);
        filters.setAlignItems(FlexComponent.Alignment.END);
        filters.setWidthFull();

        VerticalLayout content = new VerticalLayout(filters, grid);
        content.setPadding(false);
        content.setSpacing(true);
        content.setSizeFull();
        dialog.add(content);
        dialog.getFooter().add(new Button("Отмена", event -> dialog.close()), save);
        dialog.open();
    }

    private void pushNodesToClientCanvas(List<RepairEstimateCatalogNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        List<Map<String, Object>> payload = nodes.stream()
                .map(this::clientNodePayload)
                .toList();
        try {
            canvas.getElement().executeJs("window.__catalogAddNodes && window.__catalogAddNodes(JSON.parse($0));",
                    objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize catalog nodes for canvas", e);
        }
    }

    private Map<String, Object> clientNodePayload(RepairEstimateCatalogNode node) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", node.getId() == null ? "" : node.getId().toString());
        item.put("nodeType", node.getNodeType() == null ? "" : node.getNodeType().name());
        item.put("name", safe(node.getName()));
        item.put("code", safe(node.getCode()));
        item.put("includeInEstimate", Boolean.TRUE.equals(node.getIncludeInEstimate()));
        item.put("commonItem", Boolean.TRUE.equals(node.getCommonItem()));
        item.put("unit", safe(node.getUnit()));
        item.put("unitPrice", node.getUnitPrice());
        item.put("defaultQuantity", node.getDefaultQuantity());
        item.put("durationMinutes", node.getDurationMinutes());
        item.put("additionalOption", Boolean.TRUE.equals(node.getAdditionalOption()));
        item.put("active", node.getActive() == null || Boolean.TRUE.equals(node.getActive()));
        item.put("sortOrder", node.getSortOrder());
        item.put("comment", safe(node.getComment()));
        item.put("x", node.getCanvasX());
        item.put("y", node.getCanvasY());
        item.put("rows", rowsForNode(node).stream()
                .map(row -> Map.of(
                        "label", row.label(),
                        "value", row.value()
                ))
                .toList());
        return item;
    }

    private boolean pricedType(RepairEstimateCatalogNodeType type) {
        return type == RepairEstimateCatalogNodeType.WORK || type == RepairEstimateCatalogNodeType.MATERIAL;
    }

    private boolean linkExists(UUID sourceId, UUID targetId) {
        if (sourceId == null || targetId == null) {
            return false;
        }
        return dataManager.loadValue(
                        """
                        select count(e) from RepairEstimateCatalogLink e
                        where e.sourceNode.id = :sourceId
                          and e.targetNode.id = :targetId
                          and e.active = true
                        """,
                        Long.class)
                .parameter("sourceId", sourceId)
                .parameter("targetId", targetId)
                .one() > 0;
    }

    private List<RepairEstimateCatalogNode> loadSectionItemsForCategory(UUID categoryId,
                                                                        RepairEstimateCatalogNodeType sectionType,
                                                                        UUID sourceNodeId,
                                                                        boolean commonOnly) {
        if (categoryId == null || sectionType == null) {
            return List.of();
        }
        List<RepairEstimateCatalogNode> nodes = loadAllNodes();
        Map<UUID, RepairEstimateCatalogNode> nodesById = nodes.stream()
                .filter(node -> node.getId() != null)
                .collect(Collectors.toMap(RepairEstimateCatalogNode::getId, node -> node, (left, right) -> left));
        return nodes.stream()
                .filter(node -> node.getNodeType() == sectionType)
                .filter(node -> commonOnly == Boolean.TRUE.equals(node.getCommonItem()))
                .filter(node -> commonOnly || belongsToCategory(node, categoryId, nodesById))
                .filter(node -> !Objects.equals(node.getId(), sourceNodeId))
                .sorted(nodeComparator())
                .toList();
    }

    private boolean belongsToCategory(RepairEstimateCatalogNode node,
                                      UUID categoryId,
                                      Map<UUID, RepairEstimateCatalogNode> nodesById) {
        RepairEstimateCatalogNode current = node;
        Set<UUID> visited = new LinkedHashSet<>();
        while (current != null) {
            if (current.getId() == null || !visited.add(current.getId())) {
                return false;
            }
            if (current.getParent() == null) {
                return current.getNodeType() == RepairEstimateCatalogNodeType.CATEGORY
                        && Objects.equals(current.getId(), categoryId);
            }
            if (current.getParent().getId() == null) {
                return false;
            }
            current = nodesById.get(current.getParent().getId());
        }
        return false;
    }

    private RepairEstimateCatalogNode selectedCategory() {
        if (selectedCategoryId == null) {
            return null;
        }
        return allNodes.stream()
                .filter(node -> Objects.equals(node.getId(), selectedCategoryId))
                .findFirst()
                .orElse(null);
    }

    private RepairEstimateCatalogNode findEditorNode(UUID id) {
        if (id == null) {
            return null;
        }
        return editorNodes.stream().filter(node -> Objects.equals(node.getId(), id)).findFirst().orElse(null);
    }

    private RepairEstimateCatalogNode prepareNodeForSave(RepairEstimateCatalogNode candidate,
                                                         boolean creating,
                                                         String rawCode) {
        String normalizedCode = normalizeCode(rawCode);
        RepairEstimateCatalogNode existingNode = loadNodeByCode(normalizedCode);
        if (existingNode != null && !Objects.equals(existingNode.getId(), candidate.getId())) {
            Notification.show(creating ? "Код уже занят существующим блоком" : "Код уже занят другим блоком");
            return null;
        }
        return candidate;
    }

    private RepairEstimateCatalogNode loadNodeByCode(String normalizedCode) {
        if (normalizedCode == null || normalizedCode.isBlank()) {
            return null;
        }
        return dataManager.load(RepairEstimateCatalogNode.class)
                .query("""
                        select e from RepairEstimateCatalogNode e
                        where upper(e.code) = :code
                        """)
                .parameter("code", normalizedCode)
                .optional()
                .orElse(null);
    }

    private String normalizeCode(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private RepairEstimateCatalogLink findExistingLink(RepairEstimateCatalogNode source,
                                                       RepairEstimateCatalogNode target,
                                                       RepairEstimateCatalogLinkType type) {
        return dataManager.load(RepairEstimateCatalogLink.class)
                .query("""
                        select e from RepairEstimateCatalogLink e
                        where e.sourceNode = :source
                          and e.targetNode = :target
                        """)
                .parameter("source", source)
                .parameter("target", target)
                .list()
                .stream()
                .filter(link -> link.getLinkType() == type)
                .findFirst()
                .orElse(null);
    }

    private boolean isLinkAllowed(RepairEstimateCatalogNode source, RepairEstimateCatalogNode target) {
        if (source == null || target == null || source.getId() == null || target.getId() == null) {
            Notification.show("Некорректные узлы связи");
            return false;
        }
        if (Objects.equals(source.getId(), target.getId())) {
            Notification.show("Блок не может ссылаться сам на себя");
            return false;
        }
        if (target.getNodeType() == RepairEstimateCatalogNodeType.CATEGORY) {
            Notification.show("Категория не может быть дочерним блоком");
            return false;
        }
        if (isParentChainContains(source, target.getId())) {
            Notification.show("Связь создаст цикл в иерархии");
            return false;
        }
        if (isReachableByLinks(target.getId(), source.getId())) {
            Notification.show("Связь создаст цикл в графе");
            return false;
        }
        return true;
    }

    private boolean isParentChainContains(RepairEstimateCatalogNode node, UUID possibleParentId) {
        RepairEstimateCatalogNode current = node;
        Set<UUID> visited = new LinkedHashSet<>();
        while (current != null && current.getId() != null && visited.add(current.getId())) {
            if (Objects.equals(current.getId(), possibleParentId)) {
                return true;
            }
            if (current.getParent() == null || current.getParent().getId() == null) {
                return false;
            }
            current = dataManager.load(RepairEstimateCatalogNode.class)
                    .id(current.getParent().getId())
                    .optional()
                    .orElse(null);
        }
        return true;
    }

    private boolean isReachableByLinks(UUID fromId, UUID targetId) {
        List<RepairEstimateCatalogLink> links = loadAllLinks();
        Map<UUID, List<UUID>> targetsBySource = new HashMap<>();
        for (RepairEstimateCatalogLink link : links) {
            if (link.getSourceNode() != null && link.getTargetNode() != null
                    && link.getSourceNode().getId() != null && link.getTargetNode().getId() != null) {
                targetsBySource
                        .computeIfAbsent(link.getSourceNode().getId(), ignored -> new ArrayList<>())
                        .add(link.getTargetNode().getId());
            }
        }
        Set<UUID> visited = new LinkedHashSet<>();
        List<UUID> queue = new ArrayList<>();
        queue.add(fromId);
        for (int i = 0; i < queue.size(); i++) {
            UUID current = queue.get(i);
            if (!visited.add(current)) {
                continue;
            }
            if (Objects.equals(current, targetId)) {
                return true;
            }
            queue.addAll(targetsBySource.getOrDefault(current, List.of()));
        }
        return false;
    }

    private void applyParentFromLink(RepairEstimateCatalogNode source,
                                     RepairEstimateCatalogNode target,
                                     RepairEstimateCatalogLinkType type) {
        if (type == RepairEstimateCatalogLinkType.FOLLOW_UP
                && target.getNodeType() != RepairEstimateCatalogNodeType.CATEGORY) {
            target.setParent(source);
        }
    }

    private void deleteSelectedLink() {
        if (selectedLinkId == null) {
            return;
        }
        RepairEstimateCatalogLink link = dataManager.load(RepairEstimateCatalogLink.class)
                .id(selectedLinkId)
                .optional()
                .orElse(null);
        if (link == null) {
            selectedLinkId = null;
            updateDeleteLinkButton();
            return;
        }
        RepairEstimateCatalogNode target = link.getTargetNode();
        RepairEstimateCatalogNode source = link.getSourceNode();
        hardDeleteCatalogLink(link.getId());
        if (link.getLinkType() == RepairEstimateCatalogLinkType.FOLLOW_UP
                && target != null && source != null && target.getNodeType() != RepairEstimateCatalogNodeType.CATEGORY
                && target.getParent() != null && Objects.equals(target.getParent().getId(), source.getId())) {
            target.setParent(reassignParentAfterLinkDelete(target, link.getId()));
            dataManager.save(target);
        }
        repairCatalogService.clearCache();
        selectedLinkId = null;
        reloadEditor();
    }

    private void hardDeleteCatalogLink(UUID linkId) {
        if (linkId == null) {
            return;
        }
        jdbcTemplate.update("""
                delete from REPAIR_ESTIMATE_CATALOG_LINK
                where ID = ?
                """, linkId.toString());
    }

    private void hardDeleteLinksForNode(UUID nodeId) {
        if (nodeId == null) {
            return;
        }
        jdbcTemplate.update("""
                delete from REPAIR_ESTIMATE_CATALOG_LINK
                where SOURCE_NODE_ID = ? or TARGET_NODE_ID = ?
                """, nodeId.toString(), nodeId.toString());
    }

    private void removeNodeFromConstructorLayer(UUID nodeId) {
        if (nodeId == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                executeUpdate(connection, """
                        delete from REPAIR_ESTIMATE_CATALOG_LINK
                        where SOURCE_NODE_ID = ? or TARGET_NODE_ID = ?
                        """, nodeId, nodeId);
                executeUpdate(connection, """
                        update REPAIR_ESTIMATE_CATALOG_NODE
                        set CANVAS_X = null,
                            CANVAS_Y = null
                        where ID = ?
                        """, nodeId);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot remove repair estimate catalog node from constructor " + nodeId, e);
        }
    }

    private void hardDeleteCatalogNode(UUID nodeId) {
        if (nodeId == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                UUID fallbackParentId = selectedCategoryId == null || Objects.equals(selectedCategoryId, nodeId)
                        ? null
                        : selectedCategoryId;
                if (fallbackParentId == null) {
                    executeUpdate(connection, """
                            update REPAIR_ESTIMATE_CATALOG_NODE
                            set PARENT_ID = null
                            where PARENT_ID = ?
                            """, nodeId);
                } else {
                    executeUpdate(connection, """
                            update REPAIR_ESTIMATE_CATALOG_NODE
                            set PARENT_ID = ?
                            where PARENT_ID = ?
                            """, fallbackParentId, nodeId);
                }
                executeUpdate(connection, """
                        update REPAIR_ESTIMATE_TASK_PLAN
                        set FOLLOW_UP_NODE_ID = null
                        where FOLLOW_UP_NODE_ID = ?
                        """, nodeId);
                executeUpdate(connection, """
                        delete from REPAIR_ESTIMATE_CATALOG_LINK
                        where SOURCE_NODE_ID = ? or TARGET_NODE_ID = ?
                        """, nodeId, nodeId);
                executeUpdate(connection, """
                        delete from REPAIR_ESTIMATE_CATALOG_NODE
                        where ID = ?
                        """, nodeId);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot hard delete repair estimate catalog node " + nodeId, e);
        }
    }

    private void hardDeleteCatalogTree(List<UUID> orderedNodeIds) {
        if (orderedNodeIds == null || orderedNodeIds.isEmpty()) {
            return;
        }
        List<UUID> deleteOrder = new ArrayList<>(orderedNodeIds);
        java.util.Collections.reverse(deleteOrder);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                for (UUID nodeId : orderedNodeIds) {
                    executeUpdate(connection, """
                            update ACCESSORY_ITEM
                            set FURNITURE_MATERIAL_ID = null
                            where FURNITURE_MATERIAL_ID = ?
                            """, nodeId);
                    executeUpdate(connection, """
                            update REPAIR_ESTIMATE_TASK_PLAN
                            set FOLLOW_UP_NODE_ID = null
                            where FOLLOW_UP_NODE_ID = ?
                            """, nodeId);
                    executeUpdate(connection, """
                            delete from REPAIR_ESTIMATE_CATALOG_LINK
                            where SOURCE_NODE_ID = ? or TARGET_NODE_ID = ?
                            """, nodeId, nodeId);
                }
                for (UUID nodeId : deleteOrder) {
                    executeUpdate(connection, """
                            delete from REPAIR_ESTIMATE_CATALOG_NODE
                            where ID = ?
                            """, nodeId);
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot hard delete repair estimate catalog tree " + orderedNodeIds, e);
        }
    }

    private void executeUpdate(Connection connection, String sql, UUID... ids) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < ids.length; i++) {
                statement.setString(i + 1, ids[i].toString());
            }
            statement.executeUpdate();
        }
    }

    private RepairEstimateCatalogNode reassignParentAfterLinkDelete(RepairEstimateCatalogNode target, UUID deletedLinkId) {
        RepairEstimateCatalogLink alternativeLink = dataManager.load(RepairEstimateCatalogLink.class)
                .query("""
                        select e from RepairEstimateCatalogLink e
                        where e.active = true
                          and e.targetNode = :target
                          and e.id <> :deletedId
                        order by e.sortOrder, e.id
                        """)
                .parameter("target", target)
                .parameter("deletedId", deletedLinkId)
                .maxResults(1)
                .optional()
                .orElse(null);
        if (alternativeLink != null && alternativeLink.getSourceNode() != null) {
            return alternativeLink.getSourceNode();
        }
        return selectedCategory();
    }

    private void updateDeleteLinkButton() {
        deleteLinkButton.setVisible(selectedLinkId != null);
    }

    private Div createHandle(RepairEstimateCatalogNode node, String anchor) {
        Div handle = new Div();
        handle.addClassName("catalog-link-handle");
        handle.getElement().setAttribute("data-node-id", node.getId().toString());
        handle.getElement().setAttribute("data-anchor", anchor.toUpperCase());
        handle.getStyle()
                .set("position", "absolute")
                .set("left", "calc(50% - 10px)")
                .set(anchor.equalsIgnoreCase("top") ? "top" : "bottom", "-12px")
                .set("width", "20px")
                .set("height", "20px")
                .set("border-radius", "50%")
                .set("background", "#2563eb")
                .set("border", "2px solid #ffffff")
                .set("box-shadow", "0 2px 10px rgba(37, 99, 235, 0.35)")
                .set("z-index", "30")
                .set("cursor", "crosshair");
        return handle;
    }

    private LinkAnchors anchorsFor(RepairEstimateCatalogLink link, Position source, Position target) {
        String comment = safe(link.getComment());
        if (comment.startsWith("__anchors__:")) {
            String payload = comment.substring("__anchors__:".length());
            String[] parts = payload.split("->");
            if (parts.length == 2) {
                return new LinkAnchors(normalizeAnchor(parts[0]), normalizeAnchor(parts[1]));
            }
        }
        if (source != null && target != null && source.y() > target.y()) {
            return new LinkAnchors(AnchorSide.TOP, AnchorSide.BOTTOM);
        }
        return new LinkAnchors(AnchorSide.BOTTOM, AnchorSide.TOP);
    }

    private String anchorComment(AnchorSide sourceAnchor, AnchorSide targetAnchor) {
        return "__anchors__:" + sourceAnchor.name() + "->" + targetAnchor.name();
    }

    private AnchorSide normalizeAnchor(String value) {
        return "TOP".equalsIgnoreCase(value) ? AnchorSide.TOP : AnchorSide.BOTTOM;
    }

    private int anchorY(Position position, AnchorSide anchor) {
        return anchor == AnchorSide.TOP ? position.y() - 2 : position.y() + position.height() - 2;
    }

    private int controlY(int y, AnchorSide anchor, int curve) {
        return anchor == AnchorSide.TOP ? y - curve : y + curve;
    }

    private List<WorkQueue> loadWorkQueues() {
        return dataManager.load(WorkQueue.class)
                .query("select e from WorkQueue e where e.active = true order by e.warehouse.sortOrder, e.warehouse.name, e.sortOrder, e.name")
                .fetchPlan(builder -> builder.addFetchPlan("_base").add("warehouse", "_base"))
                .list();
    }

    private List<QueueBindingOption> loadQueueBindingOptions() {
        Map<String, QueueBindingOption> uniqueByCode = new LinkedHashMap<>();
        for (WorkQueue queue : loadWorkQueues()) {
            if (queue == null || queue.getCode() == null || queue.getCode().isBlank()) {
                continue;
            }
            String normalizedCode = queue.getCode().trim().toUpperCase(Locale.ROOT);
            uniqueByCode.putIfAbsent(normalizedCode, new QueueBindingOption(queue, queueBusinessLabel(queue)));
        }
        return new ArrayList<>(uniqueByCode.values());
    }

    private QueueBindingOption resolveQueueBindingOption(WorkQueue selectedQueue, List<QueueBindingOption> options) {
        if (selectedQueue == null || selectedQueue.getCode() == null) {
            return null;
        }
        return options.stream()
                .filter(option -> selectedQueue.getCode().equalsIgnoreCase(option.queue().getCode()))
                .findFirst()
                .orElse(null);
    }

    private Comparator<RepairEstimateCatalogNode> nodeComparator() {
        return Comparator
                .comparing((RepairEstimateCatalogNode node) -> node.getSortOrder() == null ? Integer.MAX_VALUE : node.getSortOrder())
                .thenComparing(node -> safe(node.getName()), String.CASE_INSENSITIVE_ORDER);
    }

    private String nodeTypeLabel(RepairEstimateCatalogNodeType type) {
        if (type == null) {
            return "";
        }
        return localizedMessage("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNodeType." + type.name(),
                type.name());
    }

    private String yesNo(Boolean value) {
        return Boolean.TRUE.equals(value) ? "Да" : "Нет";
    }

    private String money(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String stroke(String markerColor) {
        return switch (markerColor) {
            case "blue" -> "#2563eb";
            case "green" -> "#16a34a";
            case "violet" -> "#7c3aed";
            default -> "#6b7280";
        };
    }

    private String markerColor(RepairEstimateCatalogLinkType type) {
        if (isPathDependencyLink(type)) {
            return "blue";
        }
        if (type == RepairEstimateCatalogLinkType.FOLLOW_UP) {
            return "green";
        }
        return "green";
    }

    private String linkTypeLabel(RepairEstimateCatalogLinkType type) {
        if (type == null) {
            return "";
        }
        if (type == RepairEstimateCatalogLinkType.FOLLOW_UP) {
            return localizedMessage("repairEstimateCatalogCanvas.linkType.path", "Путь");
        }
        if (isPathDependencyLink(type)) {
            return localizedMessage("repairEstimateCatalogCanvas.linkType.pathDependency", "Путь + зависимость");
        }
        return localizedMessage("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogLinkType." + type.name(),
                type.name());
    }

    private RepairEstimateCatalogLinkType selectableLinkType(RepairEstimateCatalogLinkType type) {
        return isPathDependencyLink(type) ? RepairEstimateCatalogLinkType.DEPENDENCY : RepairEstimateCatalogLinkType.FOLLOW_UP;
    }

    private boolean isPathDependencyLink(RepairEstimateCatalogLinkType type) {
        return type == RepairEstimateCatalogLinkType.DEPENDENCY;
    }

    private String localizedMessage(String key, String fallback) {
        String value = messageBundle.getMessage(key);
        if (value == null || value.isBlank() || value.equals(key)) {
            return fallback;
        }
        return value;
    }

    private String queueBusinessLabel(WorkQueue queue) {
        if (queue == null) {
            return "";
        }
        String name = safe(queue.getName());
        return name;
    }

    private String queueBindingLabel(WorkQueue queue) {
        return queueBusinessLabel(queue);
    }

    private int nodeHeight(RepairEstimateCatalogNode node) {
        return Math.max(NODE_MIN_HEIGHT, (rowsForNode(node).size() + 1) * 40);
    }

    private record Position(int x, int y, int height) {
    }

    private record FieldRow(String label, String value) {
    }

    private record LinkAnchors(AnchorSide sourceAnchor, AnchorSide targetAnchor) {
    }

    private record QueueBindingOption(WorkQueue queue, String label) {
    }

    private record CanvasSavePayload(String categoryId,
                                     List<CanvasNodePayload> nodes,
                                     List<CanvasLinkPayload> links) {
    }

    private record CanvasNodePayload(String id,
                                     Integer x,
                                     Integer y,
                                     String nodeType,
                                     String name,
                                     String code,
                                     Boolean includeInEstimate,
                                     Boolean commonItem,
                                     String unit,
                                     BigDecimal unitPrice,
                                     Integer defaultQuantity,
                                     Integer durationMinutes,
                                     Boolean additionalOption,
                                     Boolean active,
                                     Integer sortOrder,
                                     String comment,
                                     Boolean removedFromCanvas) {
    }

    private record CanvasLinkPayload(String id,
                                     String sourceNodeId,
                                     String targetNodeId,
                                     String sourceAnchor,
                                     String targetAnchor,
                                     String linkType,
                                     Boolean deleted) {
    }

    private enum AnchorSide {
        TOP,
        BOTTOM
    }
}
