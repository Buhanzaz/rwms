package dev.buhanzaz.wmspanel.view.reservationsettings;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.ReservationSearchSettings;
import dev.buhanzaz.wmspanel.service.ReservationSearchSettingsService;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.core.Messages;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

@Route(value = "reservation-search-settings", layout = MainView.class)
@ViewController(id = "ReservationSearchSettings.view")
@ViewDescriptor(path = "reservation-search-settings-view.xml")
public class ReservationSearchSettingsView extends StandardView {

    @Autowired
    private ReservationSearchSettingsService reservationSearchSettingsService;

    @Autowired
    private Notifications notifications;

    @Autowired
    private Messages messages;

    @ViewComponent
    private IntegerField reservationSearchRefreshSecondsField;

    @ViewComponent
    private Button saveButton;

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        reservationSearchRefreshSecondsField.setMin(0);
        reservationSearchRefreshSecondsField.setStepButtonsVisible(true);
        reservationSearchRefreshSecondsField.setValue(reservationSearchSettingsService.loadOrCreate()
                .getReservationSearchRefreshSeconds());
    }

    @Subscribe("saveButton")
    public void onSaveButtonClick(final com.vaadin.flow.component.ClickEvent<Button> event) {
        Integer requestedValue = reservationSearchRefreshSecondsField.getValue();
        if (requestedValue != null && requestedValue < 0) {
            notifications.create(msg("reservationSearchSettings.validation.nonNegative"))
                    .withType(Notifications.Type.ERROR)
                    .withPosition(Notification.Position.TOP_END)
                    .show();
            return;
        }

        ReservationSearchSettings saved = reservationSearchSettingsService.save(requestedValue);
        reservationSearchRefreshSecondsField.setValue(saved.getReservationSearchRefreshSeconds());

        String messageKey = requestedValue != null
                && requestedValue > 0
                && saved.getReservationSearchRefreshSeconds() != null
                && !requestedValue.equals(saved.getReservationSearchRefreshSeconds())
                ? "reservationSearchSettings.notifications.savedClamped"
                : "reservationSearchSettings.notifications.saved";
        notifications.create(msg(messageKey))
                .withPosition(Notification.Position.TOP_END)
                .show();
    }

    private String msg(String key) {
        return messages.getMessage(key);
    }
}
