package dev.buhanzaz.wmspanel.view.workerclassassignment;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.WorkerClassAssignment;
import dev.buhanzaz.wmspanel.service.WorkerManagementService;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.View.BeforeShowEvent;
import io.jmix.flowui.view.View.InitEvent;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

@Route(value = "worker-class-assignments", layout = MainView.class)
@ViewController(id = "WorkerClassAssignment.list")
@ViewDescriptor(path = "worker-class-assignment-list-view.xml")
@LookupComponent("workerClassAssignmentsDataGrid")
@DialogMode(width = "78em")
public class WorkerClassAssignmentListView extends StandardListView<WorkerClassAssignment> {

    @ViewComponent
    private CollectionLoader<WorkerClassAssignment> workerClassAssignmentsDl;

    @ViewComponent
    private DataGrid<WorkerClassAssignment> workerClassAssignmentsDataGrid;

    @Autowired
    private WorkerManagementService workerManagementService;

    @Subscribe
    public void onInit(final InitEvent event) {
        configureMutatingActions(workerManagementService.canManageAnyAccessibleWarehouse());
    }

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        workerClassAssignmentsDl.setParameter("warehouseIds", workerManagementService.accessibleWarehouseIds());
        workerClassAssignmentsDl.load();
    }

    private void configureMutatingActions(boolean enabled) {
        var createAction = workerClassAssignmentsDataGrid.getAction("createAction");
        if (createAction != null) {
            createAction.setEnabled(enabled);
        }
        var editAction = workerClassAssignmentsDataGrid.getAction("editAction");
        if (editAction != null) {
            editAction.setEnabled(enabled);
        }
        var removeAction = workerClassAssignmentsDataGrid.getAction("removeAction");
        if (removeAction != null) {
            removeAction.setEnabled(enabled);
        }
    }
}
