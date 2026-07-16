package dev.buhanzaz.wmspanel.view.stockitem;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.StockItem;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "stock-items/:id", layout = MainView.class)
@ViewController(id = "StockItem.detail")
@ViewDescriptor(path = "stock-item-detail-view.xml")
@EditedEntityContainer("stockItemDc")
public class StockItemDetailView extends StandardDetailView<StockItem> {
}
