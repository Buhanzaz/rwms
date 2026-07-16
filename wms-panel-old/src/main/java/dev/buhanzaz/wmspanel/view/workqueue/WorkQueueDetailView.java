package dev.buhanzaz.wmspanel.view.workqueue;

import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.WorkQueue;
import dev.buhanzaz.wmspanel.entity.WorkQueueKind;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.View.InitEvent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "work-queues/:id", layout = MainView.class)
@ViewController(id = "WorkQueue.detail")
@ViewDescriptor(path = "work-queue-detail-view.xml")
@EditedEntityContainer("workQueueDc")
public class WorkQueueDetailView extends StandardDetailView<WorkQueue> {

    @ViewComponent
    private ComboBox<WorkQueueKind> queueKindField;

    @ViewComponent
    private Checkbox sinkQueueField;

    @ViewComponent
    private IntegerField holdingPeriodField;

    @ViewComponent
    private IntegerField notificationThresholdField;

    @ViewComponent
    private Checkbox notifyWhenThresholdReachedField;

    @Subscribe
    public void onInit(final InitEvent event) {
        sinkQueueField.setEnabled(false);
        queueKindField.addValueChangeListener(valueChangeEvent -> syncQueueKindSettings(valueChangeEvent.getValue()));
        syncQueueKindSettings(queueKindField.getValue());
    }

    private void syncQueueKindSettings(WorkQueueKind queueKind) {
        boolean holding = queueKind == WorkQueueKind.HOLDING;
        sinkQueueField.setValue(holding);
        holdingPeriodField.setVisible(holding);
        notificationThresholdField.setVisible(holding);
        notifyWhenThresholdReachedField.setVisible(holding);
    }
}
