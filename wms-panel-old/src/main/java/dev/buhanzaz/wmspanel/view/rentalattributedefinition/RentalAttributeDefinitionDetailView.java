package dev.buhanzaz.wmspanel.view.rentalattributedefinition;

import com.vaadin.flow.router.Route;
import com.vaadin.flow.component.combobox.ComboBox;
import dev.buhanzaz.wmspanel.entity.RentalAttributeDefinition;
import dev.buhanzaz.wmspanel.entity.RentalAttributeDataType;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import io.jmix.flowui.view.View.InitEvent;

@Route(value = "rental-attribute-definitions/:id", layout = MainView.class)
@ViewController(id = "RentalAttributeDefinition.detail")
@ViewDescriptor(path = "rental-attribute-definition-detail-view.xml")
@EditedEntityContainer("rentalAttributeDefinitionDc")
public class RentalAttributeDefinitionDetailView extends StandardDetailView<RentalAttributeDefinition> {

    @ViewComponent
    private ComboBox<RentalAttributeDataType> dataTypeField;

    @Subscribe
    public void onInit(final InitEvent event) {
        dataTypeField.setItems(RentalAttributeDataType.values());
    }
}
