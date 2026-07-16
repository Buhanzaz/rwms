package dev.buhanzaz.wmspanel.view.rentalclassifierattribute;

import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.RentalAttributeDefinition;
import dev.buhanzaz.wmspanel.entity.RentalCategory;
import dev.buhanzaz.wmspanel.entity.RentalClassifierAttribute;
import dev.buhanzaz.wmspanel.entity.RentalClassifierAttributeCategoryLink;
import dev.buhanzaz.wmspanel.entity.RentalSubcategory;
import dev.buhanzaz.wmspanel.entity.RentalType;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.core.DataManager;
import io.jmix.core.SaveContext;
import io.jmix.flowui.component.combobox.EntityComboBox;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.StandardDetailView.AfterSaveEvent;
import io.jmix.flowui.view.StandardDetailView.BeforeSaveEvent;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.View.BeforeShowEvent;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Route(value = "rental-classifier-attributes/:id", layout = MainView.class)
@ViewController(id = "RentalClassifierAttribute.detail")
@ViewDescriptor(path = "rental-classifier-attribute-detail-view.xml")
@EditedEntityContainer("rentalClassifierAttributeDc")
public class RentalClassifierAttributeDetailView extends StandardDetailView<RentalClassifierAttribute> {

    @Autowired
    private DataManager dataManager;

    @ViewComponent
    private EntityComboBox<RentalAttributeDefinition> attributeDefinitionField;
    @ViewComponent
    private FormLayout form;

    private MultiSelectComboBox<RentalCategory> categoriesField;
    private List<RentalCategory> availableCategories = List.of();

    @Subscribe
    public void onInit(final InitEvent event) {
        attributeDefinitionField.setItemLabelGenerator(RentalAttributeDefinition::getName);
        installCategoriesField();
    }

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        availableCategories = dataManager.load(RentalCategory.class)
                .query("select e from RentalCategory e where e.active = true order by e.sortOrder, e.name")
                .list();
        categoriesField.setItems(availableCategories);
        categoriesField.setValue(new LinkedHashSet<>(loadSelectedCategories(getEditedEntity())));
    }

    @Subscribe
    public void onBeforeSave(final BeforeSaveEvent event) {
        Set<RentalCategory> selectedCategories = new LinkedHashSet<>(categoriesField.getSelectedItems());
        if (selectedCategories.isEmpty()) {
            throw new IllegalArgumentException("Выберите хотя бы одну категорию");
        }

        RentalClassifierAttribute entity = getEditedEntity();
        entity.setSubcategory(null);
        entity.setType(null);
        entity.setCategory(selectedCategories.iterator().next());
        validateNoDuplicateCoverage(entity, selectedCategories);
    }

    @Subscribe
    public void onAfterSave(final AfterSaveEvent event) {
        syncCategoryLinks(getEditedEntity(), new LinkedHashSet<>(categoriesField.getSelectedItems()));
    }

    private void installCategoriesField() {
        categoriesField = new MultiSelectComboBox<>("Категории");
        categoriesField.setWidthFull();
        categoriesField.setItemLabelGenerator(RentalCategory::getName);
        categoriesField.setHelperText("Одна привязка может действовать сразу для нескольких категорий.");
        form.add(categoriesField);
        form.setColspan(categoriesField, 2);
    }

    private List<RentalCategory> loadSelectedCategories(RentalClassifierAttribute entity) {
        if (entity == null || entity.getId() == null) {
            return entity == null ? List.of() : fallbackCategories(entity);
        }

        List<RentalCategory> categories = dataManager.load(RentalClassifierAttributeCategoryLink.class)
                .query("""
                        select e from RentalClassifierAttributeCategoryLink e
                        where e.rentalClassifierAttribute = :binding
                        order by e.category.sortOrder, e.category.name
                        """)
                .parameter("binding", entity)
                .fetchPlan(builder -> builder.add("category", "_base"))
                .list()
                .stream()
                .map(RentalClassifierAttributeCategoryLink::getCategory)
                .filter(Objects::nonNull)
                .toList();
        return categories.isEmpty() ? fallbackCategories(entity) : categories;
    }

    private List<RentalCategory> fallbackCategories(RentalClassifierAttribute entity) {
        RentalCategory category = deriveCategory(entity);
        return category == null ? List.of() : List.of(category);
    }

    private void validateNoDuplicateCoverage(RentalClassifierAttribute entity, Set<RentalCategory> selectedCategories) {
        if (entity == null || entity.getAttributeDefinition() == null || entity.getAttributeDefinition().getId() == null) {
            return;
        }

        List<RentalClassifierAttributeCategoryLink> existingLinks = dataManager.load(RentalClassifierAttributeCategoryLink.class)
                .query("""
                        select e from RentalClassifierAttributeCategoryLink e
                        join e.rentalClassifierAttribute a
                        where a.attributeDefinition = :definition
                          and (:selfId is null or a.id <> :selfId)
                          and (a.active = true or a.active is null)
                        """)
                .parameter("definition", entity.getAttributeDefinition())
                .parameter("selfId", entity.getId())
                .fetchPlan(builder -> builder
                        .add("category", "_base")
                        .add("rentalClassifierAttribute", "_base"))
                .list();

        Map<UUID, RentalClassifierAttributeCategoryLink> existingByCategoryId = new LinkedHashMap<>();
        for (RentalClassifierAttributeCategoryLink link : existingLinks) {
            if (link.getCategory() != null && link.getCategory().getId() != null) {
                existingByCategoryId.putIfAbsent(link.getCategory().getId(), link);
            }
        }

        for (RentalCategory category : selectedCategories) {
            if (category == null || category.getId() == null) {
                continue;
            }
            if (existingByCategoryId.containsKey(category.getId())) {
                throw new IllegalArgumentException("Для этой характеристики уже существует привязка к категории " + category.getName());
            }
        }
    }

    private void syncCategoryLinks(RentalClassifierAttribute entity, Set<RentalCategory> selectedCategories) {
        if (entity == null || entity.getId() == null) {
            return;
        }

        List<RentalClassifierAttributeCategoryLink> existing = dataManager.load(RentalClassifierAttributeCategoryLink.class)
                .query("select e from RentalClassifierAttributeCategoryLink e where e.rentalClassifierAttribute = :binding")
                .parameter("binding", entity)
                .list();

        SaveContext saveContext = new SaveContext();
        if (!existing.isEmpty()) {
            existing.forEach(saveContext::removing);
        }
        for (RentalCategory category : selectedCategories) {
            if (category == null) {
                continue;
            }
            RentalClassifierAttributeCategoryLink link = dataManager.create(RentalClassifierAttributeCategoryLink.class);
            link.setRentalClassifierAttribute(entity);
            link.setCategory(category);
            saveContext.saving(link);
        }
        dataManager.save(saveContext);
    }

    private RentalCategory deriveCategory(RentalClassifierAttribute entity) {
        if (entity == null) {
            return null;
        }
        RentalType type = entity.getType();
        if (type != null && type.getSubcategory() != null && type.getSubcategory().getCategory() != null) {
            return type.getSubcategory().getCategory();
        }
        RentalSubcategory subcategory = entity.getSubcategory();
        if (subcategory != null && subcategory.getCategory() != null) {
            return subcategory.getCategory();
        }
        return entity.getCategory();
    }
}
