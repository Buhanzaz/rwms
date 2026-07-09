package dev.buhanzaz.wmspanel.view.workergroup;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.WorkerGroup;
import dev.buhanzaz.wmspanel.service.WorkerManagementService;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.View.BeforeShowEvent;
import io.jmix.flowui.view.View.InitEvent;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

@Route(value = "worker-groups", layout = MainView.class)
@ViewController(id = "WorkerGroup.list")
@ViewDescriptor(path = "worker-group-list-view.xml")
@LookupComponent("workerGroupsDataGrid")
@DialogMode(width = "72em")
public class WorkerGroupListView extends StandardListView<WorkerGroup> {

    @ViewComponent
    private CollectionLoader<WorkerGroup> workerGroupsDl;
    @ViewComponent
    private DataGrid<WorkerGroup> workerGroupsDataGrid;
    @Autowired
    private WorkerManagementService workerManagementService;

    @Subscribe
    public void onInit(final InitEvent event) {
        boolean enabled = workerManagementService.canManageAnyAccessibleWarehouse();
        var createAction = workerGroupsDataGrid.getAction("createAction");
        if (createAction != null) {
            createAction.setEnabled(enabled);
        }
        var editAction = workerGroupsDataGrid.getAction("editAction");
        if (editAction != null) {
            editAction.setEnabled(enabled);
        }
        var removeAction = workerGroupsDataGrid.getAction("removeAction");
        if (removeAction != null) {
            removeAction.setEnabled(enabled);
        }
    }

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        workerGroupsDl.setParameter("warehouseIds", workerManagementService.accessibleWarehouseIds());
        workerGroupsDl.load();
    }
}
