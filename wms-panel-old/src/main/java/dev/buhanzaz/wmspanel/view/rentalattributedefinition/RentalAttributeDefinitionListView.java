package dev.buhanzaz.wmspanel.view.rentalattributedefinition;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.RentalAttributeDefinition;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "rental-attribute-definitions", layout = MainView.class)
@ViewController(id = "RentalAttributeDefinition.list")
@ViewDescriptor(path = "rental-attribute-definition-list-view.xml")
@LookupComponent("rentalAttributeDefinitionsDataGrid")
@DialogMode(width = "64em")
public class RentalAttributeDefinitionListView extends StandardListView<RentalAttributeDefinition> {
}
