package dev.buhanzaz.wmspanel.view.workerclass;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.WorkerClass;
import dev.buhanzaz.wmspanel.service.WorkerManagementService;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.StandardDetailView.BeforeSaveEvent;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

@Route(value = "worker-classes/:id", layout = MainView.class)
@ViewController(id = "WorkerClass.detail")
@ViewDescriptor(path = "worker-class-detail-view.xml")
@EditedEntityContainer("workerClassDc")
public class WorkerClassDetailView extends StandardDetailView<WorkerClass> {

    @Autowired
    private WorkerManagementService workerManagementService;

    @Subscribe
    public void onBeforeSave(final BeforeSaveEvent event) {
        workerManagementService.validateWorkerClass(getEditedEntity());
    }
}
