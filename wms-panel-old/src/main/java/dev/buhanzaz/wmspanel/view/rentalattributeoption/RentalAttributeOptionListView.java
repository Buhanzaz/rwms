package dev.buhanzaz.wmspanel.view.rentalattributeoption;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.RentalAttributeOption;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "rental-attribute-options", layout = MainView.class)
@ViewController(id = "RentalAttributeOption.list")
@ViewDescriptor(path = "rental-attribute-option-list-view.xml")
@LookupComponent("rentalAttributeOptionsDataGrid")
@DialogMode(width = "64em")
public class RentalAttributeOptionListView extends StandardListView<RentalAttributeOption> {
}
