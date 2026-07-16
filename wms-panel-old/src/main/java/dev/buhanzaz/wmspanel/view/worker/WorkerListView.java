package dev.buhanzaz.wmspanel.view.worker;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.entity.Worker;
import dev.buhanzaz.wmspanel.service.WorkerManagementService;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.core.Messages;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.View.BeforeShowEvent;
import io.jmix.flowui.view.View.InitEvent;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import io.jmix.flowui.view.ViewComponent;
import org.springframework.beans.factory.annotation.Autowired;

@Route(value = "workers", layout = MainView.class)
@ViewController(id = "Worker.list")
@ViewDescriptor(path = "worker-list-view.xml")
@LookupComponent("workersDataGrid")
@DialogMode(width = "72em")
public class WorkerListView extends StandardListView<Worker> {

    @ViewComponent
    private CollectionLoader<Worker> workersDl;

    @ViewComponent
    private DataGrid<Worker> workersDataGrid;

    @Autowired
    private WorkerManagementService workerManagementService;
    @Autowired
    private Messages messages;

    @Subscribe
    public void onInit(final InitEvent event) {
        configureMutatingActions(workerManagementService.canManageAnyAccessibleWarehouse());
        configureColumns();
    }

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        workersDl.setParameter("warehouseIds", workerManagementService.accessibleWarehouseIds());
        workersDl.load();
    }

    private void configureMutatingActions(boolean enabled) {
        var createAction = workersDataGrid.getAction("createAction");
        if (createAction != null) {
            createAction.setEnabled(enabled);
        }
        var editAction = workersDataGrid.getAction("editAction");
        if (editAction != null) {
            editAction.setEnabled(enabled);
        }
        var removeAction = workersDataGrid.getAction("removeAction");
        if (removeAction != null) {
            removeAction.setEnabled(enabled);
        }
    }

    private void configureColumns() {
        workersDataGrid.removeAllColumns();
        workersDataGrid.addColumn(worker -> valueOrBlank(worker.getDisplayName()))
                .setKey("displayName")
                .setHeader(messages.getMessage("dev.buhanzaz.wmspanel.entity/Worker.displayName"))
                .setAutoWidth(true)
                .setFlexGrow(1);
        workersDataGrid.addColumn(worker -> warehouseName(worker.getWarehouse()))
                .setKey("warehouse")
                .setHeader(messages.getMessage("dev.buhanzaz.wmspanel.entity/Worker.warehouse"))
                .setAutoWidth(true)
                .setFlexGrow(0);
        workersDataGrid.addColumn(worker -> workerManagementService.loadWorkerGroupLabel(worker.getId()))
                .setKey("workerGroups")
                .setHeader(messages.getMessage("dev.buhanzaz.wmspanel.entity/Worker.workerGroups"))
                .setAutoWidth(true)
                .setFlexGrow(1);
    }

    private String warehouseName(Warehouse warehouse) {
        return warehouse == null ? "" : valueOrBlank(warehouse.getName());
    }

    private String valueOrBlank(String value) {
        return value == null ? "" : value;
    }
}
