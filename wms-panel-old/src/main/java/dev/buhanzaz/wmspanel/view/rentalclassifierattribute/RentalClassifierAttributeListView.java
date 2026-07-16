package dev.buhanzaz.wmspanel.view.rentalclassifierattribute;

import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.RentalCategory;
import dev.buhanzaz.wmspanel.entity.RentalClassifierAttribute;
import dev.buhanzaz.wmspanel.entity.RentalClassifierAttributeCategoryLink;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.core.DataManager;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Target;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Route(value = "rental-classifier-attributes", layout = MainView.class)
@ViewController(id = "RentalClassifierAttribute.list")
@ViewDescriptor(path = "rental-classifier-attribute-list-view.xml")
@LookupComponent("rentalClassifierAttributesDataGrid")
@DialogMode(width = "72em")
public class RentalClassifierAttributeListView extends StandardListView<RentalClassifierAttribute> {

    @Autowired
    private DataManager dataManager;

    @ViewComponent
    private DataGrid<RentalClassifierAttribute> rentalClassifierAttributesDataGrid;
    @ViewComponent
    private CollectionContainer<RentalClassifierAttribute> rentalClassifierAttributesDc;

    private final Map<UUID, String> categoryLabelsByBindingId = new LinkedHashMap<>();

    @Subscribe
    public void onInit(final InitEvent event) {
        var categoryColumn = rentalClassifierAttributesDataGrid.getColumnByKey("category");
        if (categoryColumn != null) {
            categoryColumn.setHeader("Категории");
            categoryColumn.setRenderer(new ComponentRenderer<>(binding -> new Span(categoryLabelsByBindingId.getOrDefault(binding.getId(), ""))));
        }
    }

    @Subscribe(id = "rentalClassifierAttributesDc", target = Target.DATA_CONTAINER)
    public void onRentalClassifierAttributesDcCollectionChange(final CollectionContainer.CollectionChangeEvent<RentalClassifierAttribute> event) {
        reloadCategoryLabels(event.getSource().getItems());
        rentalClassifierAttributesDataGrid.getDataProvider().refreshAll();
    }

    private void reloadCategoryLabels(Collection<RentalClassifierAttribute> bindings) {
        categoryLabelsByBindingId.clear();
        if (bindings == null || bindings.isEmpty()) {
            return;
        }

        List<UUID> bindingIds = bindings.stream()
                .map(RentalClassifierAttribute::getId)
                .filter(Objects::nonNull)
                .toList();
        if (bindingIds.isEmpty()) {
            return;
        }

        Map<UUID, StringBuilder> labels = new LinkedHashMap<>();
        for (RentalClassifierAttributeCategoryLink link : dataManager.load(RentalClassifierAttributeCategoryLink.class)
                .query("""
                        select e from RentalClassifierAttributeCategoryLink e
                        where e.rentalClassifierAttribute.id in :bindingIds
                        order by e.category.sortOrder, e.category.name
                        """)
                .parameter("bindingIds", bindingIds)
                .fetchPlan(builder -> builder
                        .add("category", "_base")
                        .add("rentalClassifierAttribute", "_base"))
                .list()) {
            if (link.getRentalClassifierAttribute() == null || link.getRentalClassifierAttribute().getId() == null
                    || link.getCategory() == null || link.getCategory().getName() == null || link.getCategory().getName().isBlank()) {
                continue;
            }
            labels.computeIfAbsent(link.getRentalClassifierAttribute().getId(), ignored -> new StringBuilder());
            StringBuilder builder = labels.get(link.getRentalClassifierAttribute().getId());
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(link.getCategory().getName());
        }

        for (RentalClassifierAttribute binding : bindings) {
            if (binding == null || binding.getId() == null) {
                continue;
            }
            String label = labels.containsKey(binding.getId())
                    ? labels.get(binding.getId()).toString()
                    : fallbackCategoryLabel(binding);
            categoryLabelsByBindingId.put(binding.getId(), label);
        }
    }

    private String fallbackCategoryLabel(RentalClassifierAttribute binding) {
        RentalCategory category = binding.getCategory();
        return category == null || category.getName() == null ? "" : category.getName();
    }
}
