package dev.buhanzaz.wmspanel.view.accessorystockbalance;

import com.vaadin.flow.router.Route;
import dev.buhanzaz.wmspanel.entity.AccessoryItem;
import dev.buhanzaz.wmspanel.entity.AccessoryStockBalance;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.view.main.MainView;
import io.jmix.flowui.component.combobox.EntityComboBox;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

import java.util.ArrayList;
import java.util.List;

@Route(value = "accessory-stock-balances/:id", layout = MainView.class)
@ViewController(id = "AccessoryStockBalance.detail")
@ViewDescriptor(path = "accessory-stock-balance-detail-view.xml")
@EditedEntityContainer("accessoryStockBalanceDc")
public class AccessoryStockBalanceDetailView extends StandardDetailView<AccessoryStockBalance> {

    @ViewComponent
    private EntityComboBox<Warehouse> warehouseField;

    @ViewComponent
    private EntityComboBox<AccessoryItem> accessoryItemField;

    @Subscribe
    public void onInit(final InitEvent event) {
        warehouseField.setItemLabelGenerator(Warehouse::getName);
        accessoryItemField.setItemLabelGenerator(this::accessoryItemLabel);
    }

    private String accessoryItemLabel(AccessoryItem item) {
        if (item == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (item.getCategory() != null && item.getCategory().getName() != null && !item.getCategory().getName().isBlank()) {
            parts.add(item.getCategory().getName());
        }
        if (item.getSubcategory() != null && item.getSubcategory().getName() != null && !item.getSubcategory().getName().isBlank()) {
            parts.add(item.getSubcategory().getName());
        }
        if (item.getName() != null && !item.getName().isBlank()) {
            parts.add(item.getName());
        }
        return String.join(" / ", parts);
    }
}
