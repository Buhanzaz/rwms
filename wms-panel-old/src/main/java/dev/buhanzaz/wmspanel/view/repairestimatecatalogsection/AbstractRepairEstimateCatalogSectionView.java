package dev.buhanzaz.wmspanel.view.repairestimatecatalogsection;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.ItemDoubleClickEvent;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNode;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNodeType;
import io.jmix.core.DataManager;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public abstract class AbstractRepairEstimateCatalogSectionView extends StandardView {

    @Autowired
    protected DataManager dataManager;
    @Autowired
    protected MessageBundle messageBundle;
    @Autowired
    protected DataSource dataSource;

    @ViewComponent
    protected VerticalLayout root;

    private final Grid<RepairEstimateCatalogNode> categoryGrid = new Grid<>();
    private final Grid<RepairEstimateCatalogNode> itemGrid = new Grid<>();
    private final Button backButton = new Button("Назад к категориям");
    private final Button createButton = new Button("Создать");
    private final Button deleteButton = new Button("Удалить");
    private final H3 title = new H3();

    private RepairEstimateCatalogNode selectedCategory;

    protected abstract RepairEstimateCatalogNodeType sectionType();

    protected abstract String sectionTitleKey();

    protected String sectionTitleFallback() {
        return switch (sectionType()) {
            case WORK -> "Работы";
            case MATERIAL -> "Материалы";
            default -> sectionType().name();
        };
    }

    protected boolean includeCategory(RepairEstimateCatalogNode category,
                                      List<RepairEstimateCatalogNode> allNodes,
                                      Map<UUID, RepairEstimateCatalogNode> nodesById) {
        if (category == null || category.getNodeType() != RepairEstimateCatalogNodeType.CATEGORY || category.getParent() != null) {
            return false;
        }
        if (!includeCategoryBySection(category)) {
            return false;
        }
        return true;
    }

    protected boolean includeCategoryBySection(RepairEstimateCatalogNode category) {
        return true;
    }

    protected boolean includeItem(RepairEstimateCatalogNode node,
                                  UUID categoryId,
                                  Map<UUID, RepairEstimateCatalogNode> nodesById) {
        return Boolean.TRUE.equals(node.getCommonItem()) || belongsToCategory(node, categoryId, nodesById);
    }

    @Subscribe
    public void onInit(final InitEvent event) {
        configureLayout();
        configureCategoryGrid();
        configureItemGrid();
        showCategories();
    }

    private void configureLayout() {
        root.removeAll();
        root.setPadding(true);
        root.setSpacing(true);
        root.setSizeFull();

        HorizontalLayout toolbar = new HorizontalLayout();
        toolbar.setWidthFull();
        toolbar.setAlignItems(FlexComponent.Alignment.CENTER);

        backButton.addClickListener(click -> showCategories());
        createButton.addClickListener(click -> {
            if (selectedCategory == null) {
                Notification.show(localized("repairEstimateCatalogSection.selectCategoryFirst", "Сначала выберите категорию"));
                return;
            }
            openItemDialog(null);
        });
        deleteButton.addClickListener(click -> deleteSelectedItem());

        title.getStyle().set("margin", "0").set("font-size", "24px").set("font-weight", "700");

        toolbar.add(backButton, title, createButton, deleteButton);
        toolbar.expand(title);

        root.add(toolbar, categoryGrid, itemGrid);
        root.expand(categoryGrid, itemGrid);
    }

    private void configureCategoryGrid() {
        categoryGrid.setSizeFull();
        categoryGrid.addColumn(RepairEstimateCatalogNode::getName)
                .setHeader(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.name", "Имя"))
                .setAutoWidth(true)
                .setFlexGrow(1);
        categoryGrid.addColumn(RepairEstimateCatalogNode::getCode)
                .setHeader(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.code", "Код"))
                .setAutoWidth(true);
        categoryGrid.addColumn(node -> yesNo(node.getActive()))
                .setHeader(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.active", "Активно"))
                .setAutoWidth(true);
        categoryGrid.addColumn(node -> nullableInt(node.getSortOrder()))
                .setHeader(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.sortOrder", "Сортировка"))
                .setAutoWidth(true);
        categoryGrid.addItemDoubleClickListener(this::openCategoryFromGrid);
    }

    private void configureItemGrid() {
        itemGrid.setSizeFull();
        itemGrid.addColumn(RepairEstimateCatalogNode::getName)
                .setHeader(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.name", "Имя"))
                .setAutoWidth(true)
                .setFlexGrow(1);
        itemGrid.addColumn(RepairEstimateCatalogNode::getCode)
                .setHeader(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.code", "Код"))
                .setAutoWidth(true);
        itemGrid.addColumn(node -> yesNo(node.getIncludeInEstimate()))
                .setHeader(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.includeInEstimate", "Учет в смете"))
                .setAutoWidth(true);
        itemGrid.addColumn(node -> yesNo(node.getCommonItem()))
                .setHeader(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.commonItem", "Общий"))
                .setAutoWidth(true);
        itemGrid.addColumn(node -> safe(node.getUnit()))
                .setHeader(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.unit", "Единица измерения"))
                .setAutoWidth(true);
        itemGrid.addColumn(node -> node.getUnitPrice() == null ? "" : node.getUnitPrice().stripTrailingZeros().toPlainString())
                .setHeader(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.unitPrice", "Цена за единицу"))
                .setAutoWidth(true);
        itemGrid.addColumn(node -> nullableInt(node.getDefaultQuantity()))
                .setHeader(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.defaultQuantity", "Количество по умолчанию"))
                .setAutoWidth(true);
        itemGrid.addColumn(node -> sectionType() == RepairEstimateCatalogNodeType.WORK ? nullableInt(node.getDurationMinutes()) : "")
                .setHeader(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.durationMinutes", "Длительность, мин"))
                .setAutoWidth(true)
                .setVisible(sectionType() == RepairEstimateCatalogNodeType.WORK);
        itemGrid.addColumn(node -> yesNo(node.getActive()))
                .setHeader(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.active", "Активно"))
                .setAutoWidth(true);
        itemGrid.addColumn(node -> nullableInt(node.getSortOrder()))
                .setHeader(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.sortOrder", "Сортировка"))
                .setAutoWidth(true);
        itemGrid.addColumn(node -> safe(node.getComment()))
                .setHeader(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.comment", "Комментарий"))
                .setAutoWidth(true)
                .setFlexGrow(1);
        itemGrid.addItemDoubleClickListener(event -> openItemDialog(event.getItem()));
    }

    private void openCategoryFromGrid(ItemDoubleClickEvent<RepairEstimateCatalogNode> event) {
        selectedCategory = event.getItem();
        showItems();
    }

    private void showCategories() {
        selectedCategory = null;
        title.setText(localized(sectionTitleKey(), sectionTitleFallback()));
        backButton.setVisible(false);
        deleteButton.setVisible(false);
        createButton.setText(localized("actions.Create", "Создать"));
        createButton.setVisible(false);
        categoryGrid.setVisible(true);
        itemGrid.setVisible(false);
        categoryGrid.setItems(loadCategories());
    }

    private void showItems() {
        if (selectedCategory == null) {
            showCategories();
            return;
        }
        title.setText(localized(sectionTitleKey(), sectionTitleFallback())
                + ": " + safe(selectedCategory.getName()));
        backButton.setVisible(true);
        deleteButton.setVisible(true);
        createButton.setVisible(true);
        createButton.setText(localized("repairEstimateCatalogSection.createItem", "Добавить"));
        categoryGrid.setVisible(false);
        itemGrid.setVisible(true);
        itemGrid.setItems(loadItems(selectedCategory.getId(), sectionType()));
    }

    private List<RepairEstimateCatalogNode> loadCategories() {
        List<RepairEstimateCatalogNode> allLoaded = dataManager.load(RepairEstimateCatalogNode.class)
                .query("""
                        select e from RepairEstimateCatalogNode e
                        left join fetch e.parent
                        order by coalesce(e.sortOrder, 2147483647), e.name
                        """)
                .list();
        Map<UUID, RepairEstimateCatalogNode> nodesById = allLoaded.stream()
                .filter(node -> node.getId() != null)
                .collect(Collectors.toMap(RepairEstimateCatalogNode::getId, Function.identity(), (left, right) -> left));
        return allLoaded.stream()
                .filter(node -> includeCategory(node, allLoaded, nodesById))
                .sorted(nodeComparator())
                .toList();
    }

    private List<RepairEstimateCatalogNode> loadItems(UUID categoryId, RepairEstimateCatalogNodeType type) {
        List<RepairEstimateCatalogNode> allNodes = dataManager.load(RepairEstimateCatalogNode.class)
                .query("""
                        select e from RepairEstimateCatalogNode e
                        left join fetch e.parent
                        order by coalesce(e.sortOrder, 2147483647), e.name
                        """)
                .list();

        Map<UUID, RepairEstimateCatalogNode> nodesById = allNodes.stream()
                .filter(node -> node.getId() != null)
                .collect(Collectors.toMap(RepairEstimateCatalogNode::getId, Function.identity(), (left, right) -> left));

        return allNodes.stream()
                .filter(node -> node.getNodeType() == type)
                .filter(node -> includeItem(node, categoryId, nodesById))
                .sorted(nodeComparator())
                .toList();
    }

    private boolean belongsToCategory(RepairEstimateCatalogNode node,
                                      UUID categoryId,
                                      Map<UUID, RepairEstimateCatalogNode> nodesById) {
        RepairEstimateCatalogNode current = node;
        List<UUID> visited = new ArrayList<>();
        while (current != null) {
            if (current.getId() == null || visited.contains(current.getId())) {
                return false;
            }
            visited.add(current.getId());
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

    private void openItemDialog(RepairEstimateCatalogNode source) {
        boolean creating = source == null;
        RepairEstimateCatalogNode node = creating
                ? dataManager.create(RepairEstimateCatalogNode.class)
                : dataManager.load(RepairEstimateCatalogNode.class).id(source.getId()).one();

        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(creating
                ? localized("repairEstimateCatalogSection.createDialog", "Добавить запись")
                : localized("repairEstimateCatalogSection.editDialog", "Редактировать запись"));
        dialog.setWidth("760px");

        TextField typeField = new TextField(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.nodeType", "Тип"));
        typeField.setValue(nodeTypeLabel(sectionType()));
        typeField.setReadOnly(true);
        typeField.setWidthFull();

        TextField name = new TextField(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.name", "Имя"));
        name.setValue(safe(node.getName()));
        name.setWidthFull();

        TextField code = new TextField(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.code", "Код"));
        code.setValue(safe(node.getCode()));
        code.setWidthFull();

        Checkbox includeInEstimate = new Checkbox(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.includeInEstimate", "Учет в смете"));
        includeInEstimate.setValue(Boolean.TRUE.equals(node.getIncludeInEstimate()));

        Checkbox commonItem = new Checkbox(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.commonItem", "Общий"));
        commonItem.setValue(Boolean.TRUE.equals(node.getCommonItem()));

        TextField unit = new TextField(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.unit", "Единица измерения"));
        unit.setValue(safe(node.getUnit()));
        unit.setWidthFull();

        BigDecimalField unitPrice = new BigDecimalField(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.unitPrice", "Цена за единицу"));
        unitPrice.setValue(node.getUnitPrice());

        IntegerField defaultQuantity = new IntegerField(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.defaultQuantity", "Количество по умолчанию"));
        defaultQuantity.setValue(node.getDefaultQuantity() == null ? 1 : node.getDefaultQuantity());

        IntegerField durationMinutes = new IntegerField(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.durationMinutes", "Длительность, мин"));
        durationMinutes.setValue(node.getDurationMinutes());
        durationMinutes.setVisible(sectionType() == RepairEstimateCatalogNodeType.WORK);

        Checkbox active = new Checkbox(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.active", "Активно"));
        active.setValue(node.getActive() == null || Boolean.TRUE.equals(node.getActive()));

        IntegerField sortOrder = new IntegerField(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.sortOrder", "Сортировка"));
        sortOrder.setValue(node.getSortOrder());

        TextArea comment = new TextArea(localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNode.comment", "Комментарий"));
        comment.setValue(safe(node.getComment()));
        comment.setWidthFull();

        VerticalLayout form = new VerticalLayout(
                typeField,
                name,
                code,
                includeInEstimate,
                commonItem,
                unit,
                unitPrice,
                defaultQuantity,
                durationMinutes,
                active,
                sortOrder,
                comment
        );
        form.setPadding(false);
        form.setSpacing(true);
        form.setWidthFull();

        Button save = new Button(localized("actions.Save", "Сохранить"), click -> {
            if (selectedCategory == null) {
                Notification.show(localized("repairEstimateCatalogSection.selectCategoryFirst", "Сначала выберите категорию"));
                return;
            }
            if (blankToNull(name.getValue()) == null || blankToNull(code.getValue()) == null) {
                Notification.show(localized("repairEstimateCatalogSection.validationNameCode", "Заполните имя и код"));
                return;
            }
            if (codeInUse(code.getValue(), creating ? null : node.getId())) {
                Notification.show(localized("repairEstimateCatalogSection.codeExists", "Код уже существует"));
                return;
            }

            node.setNodeType(sectionType());
            node.setParent(selectedCategory);
            node.setName(name.getValue().trim());
            node.setCode(code.getValue().trim().toUpperCase(Locale.ROOT));
            node.setIncludeInEstimate(Boolean.TRUE.equals(includeInEstimate.getValue()));
            node.setCommonItem(Boolean.TRUE.equals(commonItem.getValue()));
            node.setUnit(blankToNull(unit.getValue()));
            node.setUnitPrice(unitPrice.getValue());
            node.setDefaultQuantity(defaultQuantity.getValue());
            node.setDurationMinutes(sectionType() == RepairEstimateCatalogNodeType.WORK ? durationMinutes.getValue() : null);
            node.setAdditionalOption(false);
            node.setShowInMainMenu(false);
            node.setMainMenuOrder(null);
            node.setMainMenuTitle(null);
            node.setWorkQueue(null);
            node.setRouteQueueKind(null);
            node.setActive(Boolean.TRUE.equals(active.getValue()));
            node.setSortOrder(sortOrder.getValue());
            node.setComment(blankToNull(comment.getValue()));
            if (creating) {
                node.setCanvasX(null);
                node.setCanvasY(null);
            }
            dataManager.save(node);
            dialog.close();
            showItems();
        });

        dialog.add(form);
        dialog.getFooter().add(new Button(localized("actions.Cancel", "Отмена"), click -> dialog.close()), save);
        dialog.open();
    }

    private boolean codeInUse(String code, UUID currentId) {
        String normalized = code == null ? null : code.trim().toUpperCase(Locale.ROOT);
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        List<RepairEstimateCatalogNode> found = dataManager.load(RepairEstimateCatalogNode.class)
                .query("select e from RepairEstimateCatalogNode e where upper(e.code) = :code")
                .parameter("code", normalized)
                .list();
        return found.stream().anyMatch(node -> !Objects.equals(node.getId(), currentId));
    }

    private void deleteSelectedItem() {
        RepairEstimateCatalogNode selected = itemGrid.asSingleSelect().getValue();
        if (selected == null) {
            Notification.show(localized("repairEstimateCatalogSection.selectItem", "Выберите запись"));
            return;
        }
        if (hasCatalogChildren(selected.getId())) {
            Notification.show(localized("repairEstimateCatalogSection.deleteChildrenFirst",
                    "Сначала удалите дочерние блоки или связи"));
            return;
        }
        hardDeleteCatalogNode(selected.getId());
        showItems();
    }

    private boolean hasCatalogChildren(UUID nodeId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     select count(*)
                     from REPAIR_ESTIMATE_CATALOG_NODE
                     where PARENT_ID = ?
                       and DELETED_DATE is null
                     """)) {
            statement.setString(1, nodeId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getLong(1) > 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot inspect repair estimate catalog node children " + nodeId, e);
        }
    }

    private void hardDeleteCatalogNode(UUID nodeId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
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
            throw new IllegalStateException("Cannot delete repair estimate catalog node " + nodeId, e);
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

    private Comparator<RepairEstimateCatalogNode> nodeComparator() {
        return Comparator
                .comparing((RepairEstimateCatalogNode node) -> node.getSortOrder() == null ? Integer.MAX_VALUE : node.getSortOrder())
                .thenComparing(node -> safe(node.getName()), String.CASE_INSENSITIVE_ORDER);
    }

    private String localized(String key, String fallback) {
        String value = messageBundle.getMessage(key);
        if (value == null || value.isBlank() || value.equals(key)) {
            return fallback;
        }
        return value;
    }

    private String nodeTypeLabel(RepairEstimateCatalogNodeType type) {
        return localized("dev.buhanzaz.wmspanel.entity/RepairEstimateCatalogNodeType." + type.name(), type.name());
    }

    private String yesNo(Boolean value) {
        return Boolean.TRUE.equals(value) ? localized("boolean.yes", "Да") : localized("boolean.no", "Нет");
    }

    private String nullableInt(Integer value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }
}
