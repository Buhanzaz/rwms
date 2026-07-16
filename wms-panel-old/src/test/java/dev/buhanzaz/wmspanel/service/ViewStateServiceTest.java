package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.Warehouse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ViewStateServiceTest {

    @Test
    void resolveWarehousePrefersSavedIdWhenItExists() {
        ViewStateService service = new ViewStateService();
        Warehouse first = warehouse("СПБ");
        Warehouse second = warehouse("МСК");

        Warehouse resolved = service.resolveWarehouse(List.of(first, second), second.getId(), first);

        assertThat(resolved).isSameAs(second);
    }

    @Test
    void resolveWarehouseFallsBackToFirstAvailableWhenSavedIdMissing() {
        ViewStateService service = new ViewStateService();
        Warehouse first = warehouse("СПБ");
        Warehouse second = warehouse("МСК");

        Warehouse resolved = service.resolveWarehouse(List.of(first, second), UUID.randomUUID(), null);

        assertThat(resolved).isSameAs(first);
    }

    private Warehouse warehouse(String name) {
        Warehouse warehouse = new Warehouse();
        warehouse.setId(UUID.randomUUID());
        warehouse.setName(name);
        warehouse.setActive(true);
        return warehouse;
    }
}
