package dev.buhanzaz.wmspanel.view.workerclassassignment;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.WorkerClass;
import dev.buhanzaz.wmspanel.entity.WorkerClassAssignment;
import dev.buhanzaz.wmspanel.entity.Worker;
import dev.buhanzaz.wmspanel.service.WorkerManagementService;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.StandardDetailView.BeforeSaveEvent;
import io.jmix.flowui.view.View.InitEvent;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

@Route(value = "worker-class-assignments/:id", layout = MainView.class)
@ViewController(id = "WorkerClassAssignment.detail")
@ViewDescriptor(path = "worker-class-assignment-detail-view.xml")
@EditedEntityContainer("workerClassAssignmentDc")
public class WorkerClassAssignmentDetailView extends StandardDetailView<WorkerClassAssignment> {

    @ViewComponent
    private CollectionLoader<Worker> workersDl;
    @ViewComponent
    private CollectionLoader<WorkerClass> workerClassesDl;
    @Autowired
    private WorkerManagementService workerManagementService;

    @Subscribe
    public void onInit(final InitEvent event) {
        workersDl.setParameter("warehouseIds", workerManagementService.accessibleWarehouseIds());
    }

    @Subscribe
    public void onBeforeSave(final BeforeSaveEvent event) {
        workerManagementService.validateWorkerClassAssignment(getEditedEntity());
    }
}
