package dev.buhanzaz.wmspanel.view.usergridcolumnsettings;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.User;
import dev.buhanzaz.wmspanel.entity.UserGridColumnSettings;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.StandardDetailView.InitEntityEvent;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.View.BeforeShowEvent;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "user-grid-column-settings/:id", layout = MainView.class)
@ViewController(id = "UserGridColumnSettings.detail")
@ViewDescriptor(path = "user-grid-column-settings-detail-view.xml")
@EditedEntityContainer("userGridColumnSettingsDc")
public class UserGridColumnSettingsDetailView extends StandardDetailView<UserGridColumnSettings> {

    @ViewComponent
    private CollectionLoader<User> usersDl;

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        usersDl.load();
    }

    @Subscribe
    public void onInitEntity(final InitEntityEvent<UserGridColumnSettings> event) {
        event.getEntity().setGridCode("RENTAL_ITEM_GRID");
    }
}
