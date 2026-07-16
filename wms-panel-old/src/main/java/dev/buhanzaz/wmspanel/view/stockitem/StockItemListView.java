package dev.buhanzaz.wmspanel.view.stockitem;

import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.StockItem;
import dev.buhanzaz.wmspanel.service.ReservationService;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

@Route(value = "stock-items", layout = MainView.class)
@ViewController(id = "StockItem.list")
@ViewDescriptor(path = "stock-item-list-view.xml")
@LookupComponent("stockItemsDataGrid")
@DialogMode(width = "72em")
public class StockItemListView extends StandardListView<StockItem> {

    @Autowired
    private ReservationService reservationService;

    @ViewComponent
    private DataGrid<StockItem> stockItemsDataGrid;

    @Subscribe
    public void onInit(final InitEvent event) {
        configureGeneratedColumns();
    }

    private void configureGeneratedColumns() {
        Grid.Column<StockItem> reservedColumn = stockItemsDataGrid.getColumnByKey("reservedQuantity");
        if (reservedColumn != null) {
            reservedColumn.setRenderer(new ComponentRenderer<>(item -> new Span(String.valueOf(reservationService.activeReservedQuantity(item)))));
            reservedColumn.setAutoWidth(true);
            reservedColumn.setFlexGrow(0);
        }

        Grid.Column<StockItem> availableColumn = stockItemsDataGrid.getColumnByKey("availableQuantity");
        if (availableColumn != null) {
            availableColumn.setRenderer(new ComponentRenderer<>(item -> new Span(String.valueOf(reservationService.available(item)))));
            availableColumn.setAutoWidth(true);
            availableColumn.setFlexGrow(0);
        }
    }
}
