package dev.buhanzaz.wmspanel.view.worker;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.entity.Worker;
import dev.buhanzaz.wmspanel.entity.WorkerGroup;
import dev.buhanzaz.wmspanel.service.WorkerManagementService;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.component.combobox.EntityComboBox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView.AfterSaveEvent;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.StandardDetailView.BeforeSaveEvent;
import io.jmix.flowui.view.View.InitEvent;
import io.jmix.flowui.view.View.ReadyEvent;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import io.jmix.flowui.view.ViewComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Route(value = "workers/:id", layout = MainView.class)
@ViewController(id = "Worker.detail")
@ViewDescriptor(path = "worker-detail-view.xml")
@EditedEntityContainer("workerDc")
public class WorkerDetailView extends StandardDetailView<Worker> {

    @ViewComponent
    private EntityComboBox<Warehouse> warehouseField;
    @ViewComponent
    private MultiSelectComboBox<WorkerGroup> workerGroupsField;
    @Autowired
    private WorkerManagementService workerManagementService;
    private List<Warehouse> accessibleWarehouses = List.of();
    private Set<WorkerGroup> selectedGroups = new LinkedHashSet<>();

    @Subscribe
    public void onInit(final InitEvent event) {
        accessibleWarehouses = workerManagementService.accessibleWarehouses();
        warehouseField.setItems(accessibleWarehouses);
        warehouseField.addValueChangeListener(change -> reloadAvailableGroups());
        workerGroupsField.setItemLabelGenerator(WorkerGroup::getName);
    }

    @Subscribe
    public void onBeforeSave(final BeforeSaveEvent event) {
        workerManagementService.validateWorker(getEditedEntity());
        selectedGroups = new LinkedHashSet<>(workerGroupsField.getSelectedItems());
    }

    @Subscribe
    public void onAfterSave(final AfterSaveEvent event) {
        workerManagementService.syncWorkerGroups(getEditedEntity(), selectedGroups);
    }

    @Subscribe
    public void onReady(final ReadyEvent event) {
        applyDefaultWarehouseIfNeeded();
        reloadAvailableGroups();
    }

    private void applyDefaultWarehouseIfNeeded() {
        Worker worker = getEditedEntity();
        if (worker.getWarehouse() == null && accessibleWarehouses.size() == 1) {
            Warehouse warehouse = accessibleWarehouses.get(0);
            worker.setWarehouse(warehouse);
            warehouseField.setValue(warehouse);
        }
    }

    private void reloadAvailableGroups() {
        Worker worker = getEditedEntity();
        if (warehouseField.getValue() != null) {
            worker.setWarehouse(warehouseField.getValue());
        }
        var availableGroups = workerManagementService.loadAvailableGroupsForWorker(worker);
        workerGroupsField.setItems(availableGroups);
        if (worker.getId() == null) {
            workerGroupsField.clear();
            return;
        }
        Set<java.util.UUID> selectedIds = workerManagementService.loadGroupIdsForWorker(worker.getId());
        workerGroupsField.setValue(availableGroups.stream()
                .filter(group -> selectedIds.contains(group.getId()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
    }
}
