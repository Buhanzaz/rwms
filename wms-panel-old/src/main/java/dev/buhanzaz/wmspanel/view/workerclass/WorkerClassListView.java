package dev.buhanzaz.wmspanel.view.workerclass;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.WorkerClass;
import dev.buhanzaz.wmspanel.service.WorkerManagementService;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.View.InitEvent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

@Route(value = "worker-classes", layout = MainView.class)
@ViewController(id = "WorkerClass.list")
@ViewDescriptor(path = "worker-class-list-view.xml")
@LookupComponent("workerClassesDataGrid")
@DialogMode(width = "64em")
public class WorkerClassListView extends StandardListView<WorkerClass> {

    @ViewComponent
    private DataGrid<WorkerClass> workerClassesDataGrid;

    @Autowired
    private WorkerManagementService workerManagementService;

    @Subscribe
    public void onInit(final InitEvent event) {
        configureMutatingActions(workerManagementService.canManageGlobalSettings());
    }

    private void configureMutatingActions(boolean enabled) {
        var createAction = workerClassesDataGrid.getAction("createAction");
        if (createAction != null) {
            createAction.setEnabled(enabled);
        }
        var editAction = workerClassesDataGrid.getAction("editAction");
        if (editAction != null) {
            editAction.setEnabled(enabled);
        }
        var removeAction = workerClassesDataGrid.getAction("removeAction");
        if (removeAction != null) {
            removeAction.setEnabled(enabled);
        }
    }
}
