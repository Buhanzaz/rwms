package dev.buhanzaz.wmspanel.view.workergroup;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.entity.WorkerClass;
import dev.buhanzaz.wmspanel.entity.WorkerGroup;
import dev.buhanzaz.wmspanel.service.WorkerManagementService;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.component.combobox.EntityComboBox;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.StandardDetailView.BeforeSaveEvent;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.View.InitEvent;
import io.jmix.flowui.view.View.ReadyEvent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import io.jmix.flowui.view.ViewComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

@Route(value = "worker-groups/:id", layout = MainView.class)
@ViewController(id = "WorkerGroup.detail")
@ViewDescriptor(path = "worker-group-detail-view.xml")
@EditedEntityContainer("workerGroupDc")
public class WorkerGroupDetailView extends StandardDetailView<WorkerGroup> {

    @ViewComponent
    private EntityComboBox<Warehouse> warehouseField;
    @ViewComponent
    private EntityComboBox<WorkerClass> workerClassField;
    @Autowired
    private WorkerManagementService workerManagementService;
    private List<Warehouse> accessibleWarehouses = List.of();

    @Subscribe
    public void onInit(final InitEvent event) {
        accessibleWarehouses = workerManagementService.accessibleWarehouses();
        warehouseField.setItems(accessibleWarehouses);
        workerClassField.setItems(workerManagementService.loadActiveWorkerClasses());
    }

    @Subscribe
    public void onReady(final ReadyEvent event) {
        WorkerGroup workerGroup = getEditedEntity();
        if (workerGroup.getWarehouse() == null && accessibleWarehouses.size() == 1) {
            Warehouse warehouse = accessibleWarehouses.get(0);
            workerGroup.setWarehouse(warehouse);
            warehouseField.setValue(warehouse);
        }
    }

    @Subscribe
    public void onBeforeSave(final BeforeSaveEvent event) {
        workerManagementService.validateWorkerGroup(getEditedEntity());
    }
}
