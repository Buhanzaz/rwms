package dev.buhanzaz.wmspanel.service;

import com.vaadin.flow.spring.annotation.VaadinSessionScope;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
@VaadinSessionScope
public class ViewStateService implements Serializable {

    private UUID selectedWarehouseId;
    private UUID terminalSelectedRentalItemId;
    private boolean taskBoardShowShadow = true;

    public UUID getSelectedWarehouseId() {
        return selectedWarehouseId;
    }

    public void setSelectedWarehouseId(UUID selectedWarehouseId) {
        this.selectedWarehouseId = selectedWarehouseId;
    }

    public UUID getTerminalWarehouseId() {
        return selectedWarehouseId;
    }

    public void setTerminalWarehouseId(UUID terminalWarehouseId) {
        this.selectedWarehouseId = terminalWarehouseId;
    }

    public UUID getTerminalSelectedRentalItemId() {
        return terminalSelectedRentalItemId;
    }

    public void setTerminalSelectedRentalItemId(UUID terminalSelectedRentalItemId) {
        this.terminalSelectedRentalItemId = terminalSelectedRentalItemId;
    }

    public UUID getTaskBoardWarehouseId() {
        return selectedWarehouseId;
    }

    public void setTaskBoardWarehouseId(UUID taskBoardWarehouseId) {
        this.selectedWarehouseId = taskBoardWarehouseId;
    }

    public boolean isTaskBoardShowShadow() {
        return taskBoardShowShadow;
    }

    public void setTaskBoardShowShadow(boolean taskBoardShowShadow) {
        this.taskBoardShowShadow = taskBoardShowShadow;
    }

    public Warehouse resolveWarehouse(List<Warehouse> availableWarehouses, UUID preferredId, Warehouse fallback) {
        if (availableWarehouses == null || availableWarehouses.isEmpty()) {
            return null;
        }
        if (preferredId != null) {
            return availableWarehouses.stream()
                    .filter(warehouse -> Objects.equals(warehouse.getId(), preferredId))
                    .findFirst()
                    .orElseGet(() -> resolveWarehouse(availableWarehouses, fallback));
        }
        return resolveWarehouse(availableWarehouses, fallback);
    }

    public Warehouse resolveWarehouse(List<Warehouse> availableWarehouses, Warehouse fallback) {
        if (availableWarehouses == null || availableWarehouses.isEmpty()) {
            return null;
        }
        if (fallback != null) {
            return availableWarehouses.stream()
                    .filter(warehouse -> Objects.equals(warehouse.getId(), fallback.getId()))
                    .findFirst()
                    .orElse(availableWarehouses.get(0));
        }
        return availableWarehouses.get(0);
    }
}
