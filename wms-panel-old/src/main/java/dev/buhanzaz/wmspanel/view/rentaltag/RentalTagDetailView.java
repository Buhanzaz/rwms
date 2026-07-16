package dev.buhanzaz.wmspanel.view.rentaltag;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.RentalTag;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "rental-tags/:id", layout = MainView.class)
@ViewController(id = "RentalTag.detail")
@ViewDescriptor(path = "rental-tag-detail-view.xml")
@EditedEntityContainer("rentalTagDc")
public class RentalTagDetailView extends StandardDetailView<RentalTag> {
}
