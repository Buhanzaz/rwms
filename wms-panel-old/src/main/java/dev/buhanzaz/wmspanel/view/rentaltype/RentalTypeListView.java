package dev.buhanzaz.wmspanel.view.rentaltype;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.RentalType;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "rental-types", layout = MainView.class)
@ViewController(id = "RentalType.list")
@ViewDescriptor(path = "rental-type-list-view.xml")
@LookupComponent("rentalTypesDataGrid")
@DialogMode(width = "64em")
public class RentalTypeListView extends StandardListView<RentalType> {
}
