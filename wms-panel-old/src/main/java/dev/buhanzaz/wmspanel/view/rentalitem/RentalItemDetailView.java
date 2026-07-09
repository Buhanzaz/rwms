package dev.buhanzaz.wmspanel.view.rentalitem;

import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.RentalCategory;
import dev.buhanzaz.wmspanel.entity.RentalItem;
import dev.buhanzaz.wmspanel.entity.RentalSubcategory;
import dev.buhanzaz.wmspanel.entity.RentalType;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.model.InstanceContainer;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Target;
import io.jmix.flowui.view.View.BeforeShowEvent;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

import java.util.Objects;

@Route(value = "rental-items/:id", layout = MainView.class)
@ViewController(id = "RentalItem.detail")
@ViewDescriptor(path = "rental-item-detail-view.xml")
@EditedEntityContainer("rentalItemDc")
public class RentalItemDetailView extends StandardDetailView<RentalItem> {

    @ViewComponent
    private CollectionLoader<RentalSubcategory> subcategoriesDl;

    @ViewComponent
    private CollectionLoader<RentalType> typesDl;

    @ViewComponent
    private ComboBox<String> statusField;

    @Subscribe
    public void onInit(final InitEvent event) {
        statusField.setItems(RentalItemStatusSupport.STATUSES);
        statusField.setItemLabelGenerator(status -> RentalItemStatusSupport.presentationFor(status).label());
    }

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        reloadDependentOptions();
    }

    @Subscribe(id = "rentalItemDc", target = Target.DATA_CONTAINER)
    public void onRentalItemDcItemPropertyChange(final InstanceContainer.ItemPropertyChangeEvent<RentalItem> event) {
        if ("category".equals(event.getProperty()) && !Objects.equals(event.getPrevValue(), event.getValue())) {
            RentalItem item = event.getItem();
            item.setSubcategory(null);
            item.setType(null);
            reloadDependentOptions();
        }

        if ("subcategory".equals(event.getProperty()) && !Objects.equals(event.getPrevValue(), event.getValue())) {
            event.getItem().setType(null);
            reloadTypes();
        }
    }

    private void reloadDependentOptions() {
        reloadSubcategories();
        reloadTypes();
    }

    private void reloadSubcategories() {
        RentalCategory category = getEditedEntity().getCategory();
        subcategoriesDl.setParameter("category", category);
        subcategoriesDl.load();
    }

    private void reloadTypes() {
        RentalSubcategory subcategory = getEditedEntity().getSubcategory();
        typesDl.setParameter("subcategory", subcategory);
        typesDl.load();
    }
}
