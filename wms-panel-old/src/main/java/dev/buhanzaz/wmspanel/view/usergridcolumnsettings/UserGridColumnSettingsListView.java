package dev.buhanzaz.wmspanel.view.usergridcolumnsettings;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.UserGridColumnSettings;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "user-grid-column-settings", layout = MainView.class)
@ViewController(id = "UserGridColumnSettings.list")
@ViewDescriptor(path = "user-grid-column-settings-list-view.xml")
@LookupComponent("userGridColumnSettingsDataGrid")
@DialogMode(width = "72em")
public class UserGridColumnSettingsListView extends StandardListView<UserGridColumnSettings> {
}
