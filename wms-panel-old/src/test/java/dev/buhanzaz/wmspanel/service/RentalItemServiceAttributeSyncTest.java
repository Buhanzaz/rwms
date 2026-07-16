package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.RentalAttributeDataType;
import dev.buhanzaz.wmspanel.entity.RentalAttributeDefinition;
import dev.buhanzaz.wmspanel.entity.RentalAttributeOption;
import dev.buhanzaz.wmspanel.entity.RentalAttributeValue;
import dev.buhanzaz.wmspanel.entity.RentalCategory;
import dev.buhanzaz.wmspanel.entity.RentalItem;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.test_support.AuthenticatedAsAdmin;
import io.jmix.core.DataManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
@ActiveProfiles("test")
class RentalItemServiceAttributeSyncTest {

    @Autowired
    DataManager dataManager;

    @Autowired
    RentalItemService rentalItemService;

    private RentalItem rentalItemToRemove;
    private RentalAttributeOption firstOptionToRemove;
    private RentalAttributeOption secondOptionToRemove;
    private RentalAttributeDefinition definitionToRemove;
    private RentalCategory categoryToRemove;
    private Warehouse warehouseToRemove;

    @AfterEach
    void tearDown() {
        if (rentalItemToRemove != null && rentalItemToRemove.getId() != null) {
            dataManager.load(RentalAttributeValue.class)
                    .query("select e from RentalAttributeValue e where e.rentalItem.id = :itemId")
                    .parameter("itemId", rentalItemToRemove.getId())
                    .list()
                    .forEach(value -> {
                        try {
                            dataManager.remove(value);
                        } catch (RuntimeException ignored) {
                            // best-effort cleanup
                        }
                    });
            try {
                dataManager.remove(rentalItemToRemove);
            } catch (RuntimeException ignored) {
                // best-effort cleanup
            }
        }

        removeIfPresent(secondOptionToRemove);
        removeIfPresent(firstOptionToRemove);
        removeIfPresent(definitionToRemove);
        removeIfPresent(categoryToRemove);
        removeIfPresent(warehouseToRemove);
    }

    @Test
    void updateFromTerminalReusesExistingAttributeValueForSameDefinition() {
        warehouseToRemove = createWarehouse();
        categoryToRemove = createCategory();
        definitionToRemove = createAttributeDefinition();
        firstOptionToRemove = createAttributeOption(definitionToRemove, "NO", "Нет", 10);
        secondOptionToRemove = createAttributeOption(definitionToRemove, "YES", "Да", 20);

        rentalItemToRemove = rentalItemService.createNewItem(
                uniqueNumber("ITEM"),
                warehouseToRemove,
                categoryToRemove,
                null,
                null,
                "READY",
                null,
                List.of(attributeValue(definitionToRemove, firstOptionToRemove)),
                List.of()
        );

        rentalItemService.updateFromTerminal(
                rentalItemToRemove.getId(),
                warehouseToRemove,
                categoryToRemove,
                null,
                null,
                "READY",
                null,
                List.of(attributeValue(definitionToRemove, secondOptionToRemove)),
                List.of()
        );

        List<RentalAttributeValue> values = rentalItemService.loadAttributeValues(rentalItemToRemove.getId());
        assertThat(values).hasSize(1);
        assertThat(values.get(0).getAttributeDefinition().getId()).isEqualTo(definitionToRemove.getId());
        assertThat(values.get(0).getValueOption()).isNotNull();
        assertThat(values.get(0).getValueOption().getId()).isEqualTo(secondOptionToRemove.getId());
    }

    private Warehouse createWarehouse() {
        Warehouse warehouse = dataManager.create(Warehouse.class);
        warehouse.setName("Attr sync warehouse " + UUID.randomUUID());
        warehouse.setCode(uniqueCode("WH"));
        warehouse.setCity("Test City");
        warehouse.setAddress("Test Address");
        warehouse.setTimeZone("Europe/Moscow");
        warehouse.setActive(true);
        warehouse.setSortOrder(10);
        return dataManager.save(warehouse);
    }

    private RentalCategory createCategory() {
        RentalCategory category = dataManager.create(RentalCategory.class);
        category.setName("Attr sync category " + UUID.randomUUID());
        category.setCode(uniqueCode("CAT"));
        category.setActive(true);
        category.setSortOrder(10);
        return dataManager.save(category);
    }

    private RentalAttributeDefinition createAttributeDefinition() {
        RentalAttributeDefinition definition = dataManager.create(RentalAttributeDefinition.class);
        definition.setName("Attr sync definition " + UUID.randomUUID());
        definition.setCode(uniqueCode("ATTR"));
        definition.setDataType(RentalAttributeDataType.ENUM);
        definition.setActive(true);
        definition.setSortOrder(10);
        return dataManager.save(definition);
    }

    private RentalAttributeOption createAttributeOption(RentalAttributeDefinition definition, String code, String name, int sortOrder) {
        RentalAttributeOption option = dataManager.create(RentalAttributeOption.class);
        option.setAttributeDefinition(definition);
        option.setCode(code + "_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        option.setName(name);
        option.setActive(true);
        option.setSortOrder(sortOrder);
        return dataManager.save(option);
    }

    private RentalAttributeValue attributeValue(RentalAttributeDefinition definition, RentalAttributeOption option) {
        RentalAttributeValue value = dataManager.create(RentalAttributeValue.class);
        value.setAttributeDefinition(definition);
        value.setValueOption(option);
        return value;
    }

    private void removeIfPresent(Object entity) {
        if (entity == null) {
            return;
        }
        try {
            dataManager.remove(entity);
        } catch (RuntimeException ignored) {
            // best-effort cleanup
        }
    }

    private String uniqueCode(String prefix) {
        return (prefix + "_" + UUID.randomUUID().toString().replace("-", ""))
                .substring(0, 24)
                .toUpperCase(Locale.ROOT);
    }

    private String uniqueNumber(String prefix) {
        return (prefix + UUID.randomUUID().toString().replace("-", ""))
                .substring(0, 16)
                .toUpperCase(Locale.ROOT);
    }
}
