package dev.buhanzaz.wmspanel.view.reservation;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.Reservation;
import dev.buhanzaz.wmspanel.entity.ReservationLine;
import dev.buhanzaz.wmspanel.entity.ReservationStatus;
import dev.buhanzaz.wmspanel.entity.User;
import dev.buhanzaz.wmspanel.entity.UserGlobalRole;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.service.ReservationExpirationService;
import dev.buhanzaz.wmspanel.service.ReservationService;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.core.DataManager;
import io.jmix.core.LoadContext;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.time.OffsetDateTime;
import java.util.stream.Collectors;

@Route(value = "reservations", layout = MainView.class)
@ViewController(id = "Reservation.list")
@ViewDescriptor(path = "reservation-list-view.xml")
public class ReservationListView extends StandardListView<Reservation> {

    private final java.util.List<Reservation> currentReservations = new java.util.ArrayList<>();

    @ViewComponent
    private DataGrid<Reservation> reservationsDataGrid;

    @ViewComponent
    private CollectionLoader<Reservation> reservationsDl;

    @ViewComponent
    private Button searchButton;

    @ViewComponent
    private Button openButton;

    @ViewComponent
    private Button deleteExpiredButton;

    @ViewComponent
    private Button refreshButton;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationExpirationService reservationExpirationService;

    @Subscribe
    public void onInit(final InitEvent event) {
        reservationsDl.setLoadDelegate(this::loadReservations);
        configureColumns();
        searchButton.setText("Поиск и резерв");
        searchButton.setIcon(VaadinIcon.SEARCH.create());
        openButton.setText("Открыть");
        openButton.setIcon(VaadinIcon.EXTERNAL_LINK.create());
        deleteExpiredButton.setText("Удалить резерв");
        deleteExpiredButton.setIcon(VaadinIcon.TRASH.create());
        refreshButton.setText("Обновить");
        refreshButton.setIcon(VaadinIcon.REFRESH.create());
        openButton.setEnabled(false);
        deleteExpiredButton.setEnabled(false);
        reservationsDataGrid.addSelectionListener(selection -> {
            Reservation selected = selection.getFirstSelectedItem().orElse(null);
            openButton.setEnabled(selected != null);
            deleteExpiredButton.setEnabled(isExpired(selected));
        });
        reservationsDataGrid.addItemClickListener(event1 -> openReservation(event1.getItem()));
    }

    @Subscribe("searchButton")
    public void onSearchButtonClick(final com.vaadin.flow.component.ClickEvent<Button> event) {
        getUI().ifPresent(ui -> ui.navigate("reservation-search"));
    }

    @Subscribe("refreshButton")
    public void onRefreshButtonClick(final com.vaadin.flow.component.ClickEvent<Button> event) {
        reservationsDl.load();
    }

    @Subscribe("openButton")
    public void onOpenButtonClick(final com.vaadin.flow.component.ClickEvent<Button> event) {
        reservationsDataGrid.getSelectedItems().stream().findFirst().ifPresent(this::openReservation);
    }

    @Subscribe("deleteExpiredButton")
    public void onDeleteExpiredButtonClick(final com.vaadin.flow.component.ClickEvent<Button> event) {
        reservationsDataGrid.getSelectedItems().stream().findFirst().ifPresent(reservation -> {
            reservationExpirationService.deleteExpiredReservation(reservation.getId());
            reservationsDl.load();
            openButton.setEnabled(false);
            deleteExpiredButton.setEnabled(false);
        });
    }

    private List<Reservation> loadReservations(LoadContext<Reservation> context) {
        List<Warehouse> warehouses = reservationService.reservationWarehouses();
        User currentUser = reservationService.currentUser();
        if (warehouses.isEmpty()) {
            currentReservations.clear();
            return currentReservations;
        }

        String query = """
                select e
                from Reservation e
                where e.warehouse in :warehouses
                """;
        if (currentUser != null && currentUser.getGlobalRole() == UserGlobalRole.RENTAL_MANAGER) {
            query += " and e.responsibleRentalManager = :responsibleRentalManager";
        }
        query += " order by coalesce(e.createdDate, e.reservedAt) desc, e.reservationNumber desc";

        var load = dataManager.load(Reservation.class)
                .query(query)
                .parameter("warehouses", warehouses);
        if (currentUser != null && currentUser.getGlobalRole() == UserGlobalRole.RENTAL_MANAGER) {
            load.parameter("responsibleRentalManager", currentUser);
        }

        currentReservations.clear();
        currentReservations.addAll(load.list());
        currentReservations.forEach(this::touchReservation);
        return currentReservations;
    }

    private void configureColumns() {
        Grid.Column<Reservation> statusColumn = reservationsDataGrid.getColumnByKey("status");
        if (statusColumn != null) {
            statusColumn.setRenderer(new ComponentRenderer<>(reservation -> new Span(statusLabel(reservation))));
            statusColumn.setAutoWidth(true);
            statusColumn.setFlexGrow(0);
        }

        Grid.Column<Reservation> itemsColumn = reservationsDataGrid.getColumnByKey("items");
        if (itemsColumn != null) {
            itemsColumn.setRenderer(new ComponentRenderer<>(reservation -> new Span(reservedItemsLabel(reservation))));
            itemsColumn.setAutoWidth(true);
            itemsColumn.setFlexGrow(1);
        }

        Grid.Column<Reservation> warehouseColumn = reservationsDataGrid.getColumnByKey("warehouse");
        if (warehouseColumn != null) {
            warehouseColumn.setAutoWidth(true);
            warehouseColumn.setFlexGrow(0);
        }
    }

    private void openReservation(Reservation reservation) {
        if (reservation == null) {
            return;
        }
        getUI().ifPresent(ui -> ui.navigate("reservations/" + reservation.getId()));
    }

    private String statusLabel(Reservation reservation) {
        ReservationStatus status = reservation == null ? null : reservation.getStatus();
        if (status == null) {
            return "";
        }
        return switch (status) {
            case ACTIVE -> "Активен";
            case TEMPORARY -> "Временный";
            case WAITING_PAYMENT -> "Ожидание оплаты";
            case CONFIRMED -> "Подтверждён";
            case EXPIRED -> "Истёк";
            case CANCELLED -> "Отменён";
            case COMPLETED -> "Завершён";
        };
    }

    private String reservedItemsLabel(Reservation reservation) {
        if (reservation == null || reservation.getId() == null) {
            return "";
        }
        return dataManager.load(ReservationLine.class)
                .query("""
                        select e
                        from ReservationLine e
                        where e.reservation.id = :reservationId
                        order by e.createdDate
                        """)
                .parameter("reservationId", reservation.getId())
                .list()
                .stream()
                .map(line -> {
                    if (line.getRentalItem() != null) {
                        return line.getRentalItem().getNumber();
                    }
                    if (line.getStockItem() != null) {
                        return line.getStockItem().getName();
                    }
                    return null;
                })
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(", "));
    }

    private boolean isExpired(Reservation reservation) {
        return reservationExpirationService.isExpired(reservation, OffsetDateTime.now());
    }

    private void touchReservation(Reservation reservation) {
        if (reservation == null) {
            return;
        }
        if (reservation.getWarehouse() != null) {
            reservation.getWarehouse().getName();
            reservation.getWarehouse().getCity();
            reservation.getWarehouse().getCode();
        }
        if (reservation.getResponsibleRentalManager() != null) {
            User user = reservation.getResponsibleRentalManager();
            user.getDisplayName();
        }
    }
}
