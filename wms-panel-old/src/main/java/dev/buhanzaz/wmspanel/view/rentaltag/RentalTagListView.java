package dev.buhanzaz.wmspanel.view.rentaltag;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.RentalTag;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "rental-tags", layout = MainView.class)
@ViewController(id = "RentalTag.list")
@ViewDescriptor(path = "rental-tag-list-view.xml")
@LookupComponent("rentalTagsDataGrid")
@DialogMode(width = "64em")
public class RentalTagListView extends StandardListView<RentalTag> {
}
