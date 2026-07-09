package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.AccessoryCategory;
import dev.buhanzaz.wmspanel.entity.AccessoryItem;
import dev.buhanzaz.wmspanel.entity.AccessorySubcategory;
import dev.buhanzaz.wmspanel.entity.RentalCategory;
import dev.buhanzaz.wmspanel.entity.RentalItemAccessory;
import dev.buhanzaz.wmspanel.entity.RentalItem;
import dev.buhanzaz.wmspanel.entity.RentalItemEvent;
import dev.buhanzaz.wmspanel.entity.RentalItemEventAccessory;
import dev.buhanzaz.wmspanel.entity.RentalItemEventPhoto;
import dev.buhanzaz.wmspanel.entity.RentalItemStatus;
import dev.buhanzaz.wmspanel.entity.RepairEstimate;
import dev.buhanzaz.wmspanel.entity.RepairEstimateStatus;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.test_support.AuthenticatedAsAdmin;
import io.jmix.core.DataManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
@ActiveProfiles("test")
class RentalItemEventServiceTest {

    @Autowired
    RentalItemEventService rentalItemEventService;

    @Autowired
    DataManager dataManager;

    private final List<Object> entitiesToRemove = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (int i = entitiesToRemove.size() - 1; i >= 0; i--) {
            removeIfPresent(entitiesToRemove.get(i));
        }
        entitiesToRemove.clear();
    }

    @Test
    void loadLatestPhotosIgnoresLaterStatusOnlyEvents() {
        RentalItem rentalItem = createRentalItem();
        Warehouse warehouse = rentalItem.getWarehouse();

        RentalItemEvent initialEvent = rentalItemEventService.recordInventoryNewItem(
                rentalItem,
                warehouse,
                "TASK-" + UUID.randomUUID(),
                "INVENTORY_NEW",
                OffsetDateTime.now().minusMinutes(10),
                "Создание объекта",
                "Создание объекта"
        );
        RentalItemEventPhoto photo = dataManager.create(RentalItemEventPhoto.class);
        photo.setEvent(initialEvent);
        photo.setStoragePath("test/photo.jpg");
        photo.setOriginalFileName("photo.jpg");
        photo.setContentType("image/jpeg");
        photo.setSizeBytes(1024L);
        photo.setSortOrder(0);
        dataManager.save(photo);
        entitiesToRemove.add(initialEvent);
        entitiesToRemove.add(photo);

        RentalItemEvent statusEvent = rentalItemEventService.recordStatusChange(
                rentalItem,
                RentalItemStatus.WAITING_REPAIR,
                "Переведён в ремонт",
                null,
                "TEST",
                null,
                OffsetDateTime.now().minusMinutes(5)
        );
        entitiesToRemove.add(statusEvent);

        List<RentalItemEventPhoto> latestPhotos = rentalItemEventService.loadLatestPhotos(rentalItem.getId());
        assertThat(latestPhotos).hasSize(1);
        assertThat(latestPhotos.get(0).getEvent().getId()).isEqualTo(initialEvent.getId());
        assertThat(latestPhotos.get(0).getOriginalFileName()).isEqualTo("photo.jpg");
    }

    @Test
    void recordAccessorySnapshotStoresEventAccessoryRows() {
        RentalItem rentalItem = createRentalItem();
        Warehouse warehouse = rentalItem.getWarehouse();
        AccessoryItem accessoryItem = createAccessoryItem();

        RentalItemEvent parentEvent = rentalItemEventService.recordInventoryExistingItem(
                rentalItem,
                warehouse,
                "TASK-" + UUID.randomUUID(),
                "INVENTORY_EXISTING",
                OffsetDateTime.now().minusMinutes(3),
                "Инвентаризация с мебелью",
                "Инвентаризация"
        );
        entitiesToRemove.add(parentEvent);

        RentalItemAccessory assignment = dataManager.create(RentalItemAccessory.class);
        assignment.setRentalItem(rentalItem);
        assignment.setAccessoryItem(accessoryItem);
        assignment.setQuantity(3);

        RentalItemEvent accessoryEvent = rentalItemEventService.recordAccessorySnapshot(
                rentalItem,
                warehouse,
                parentEvent,
                OffsetDateTime.now().minusMinutes(2),
                "Добавлены кровати",
                "TEST",
                "Инвентаризация",
                List.of(assignment)
        );
        List<RentalItemEventAccessory> stored = rentalItemEventService.loadEventAccessories(accessoryEvent.getId());
        stored.forEach(entitiesToRemove::add);
        entitiesToRemove.add(accessoryEvent);

        assertThat(accessoryEvent.getEventType()).isEqualTo(dev.buhanzaz.wmspanel.entity.RentalItemEventType.ACCESSORY_UPDATED);
        assertThat(accessoryEvent.getParentEvent()).isNotNull();
        assertThat(accessoryEvent.getParentEvent().getId()).isEqualTo(parentEvent.getId());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getAccessoryItem().getId()).isEqualTo(accessoryItem.getId());
        assertThat(stored.get(0).getQuantity()).isEqualTo(3);
    }

    @Test
    void recordEstimateCompletedKeepsRootEventWhenLatestEstimateEventIsAccessoryChild() {
        RentalItem rentalItem = createRentalItem();
        Warehouse warehouse = rentalItem.getWarehouse();
        RepairEstimate estimate = createEstimate(rentalItem, warehouse);
        AccessoryItem accessoryItem = createAccessoryItem();

        RentalItemEvent rootEvent = rentalItemEventService.recordEstimateDraft(
                estimate,
                "Черновик сметы",
                OffsetDateTime.now().minusMinutes(5),
                "TEST"
        );
        entitiesToRemove.add(rootEvent);

        RentalItemAccessory assignment = dataManager.create(RentalItemAccessory.class);
        assignment.setRentalItem(rentalItem);
        assignment.setAccessoryItem(accessoryItem);
        assignment.setQuantity(2);

        RentalItemEvent childEvent = rentalItemEventService.recordAccessorySnapshot(
                rentalItem,
                warehouse,
                rootEvent,
                OffsetDateTime.now().minusMinutes(4),
                "Добавили мебель",
                "TEST",
                "TEST",
                List.of(assignment)
        );
        entitiesToRemove.add(childEvent);
        rentalItemEventService.loadEventAccessories(childEvent.getId()).forEach(entitiesToRemove::add);
        estimate = dataManager.load(RepairEstimate.class).id(estimate.getId()).one();

        RentalItemEvent completedEvent = rentalItemEventService.recordEstimateCompleted(
                estimate,
                false,
                "Смета подтверждена",
                OffsetDateTime.now().minusMinutes(3)
        );

        assertThat(completedEvent.getId()).isEqualTo(rootEvent.getId());
        assertThat(completedEvent.getParentEvent()).isNull();
        assertThat(completedEvent.getEventType()).isEqualTo(dev.buhanzaz.wmspanel.entity.RentalItemEventType.ESTIMATE_COMPLETED);
        assertThat(childEvent.getParentEvent()).isNotNull();
        assertThat(childEvent.getParentEvent().getId()).isEqualTo(rootEvent.getId());
    }

    private RentalItem createRentalItem() {
        Warehouse warehouse = dataManager.create(Warehouse.class);
        warehouse.setName("Test warehouse " + UUID.randomUUID());
        warehouse.setCode("WH_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        warehouse.setCity("Test City");
        warehouse.setTimeZone("Europe/Moscow");
        warehouse.setActive(true);
        warehouse = dataManager.save(warehouse);
        entitiesToRemove.add(warehouse);

        RentalCategory category = dataManager.create(RentalCategory.class);
        category.setName("Test category " + UUID.randomUUID());
        category.setCode("CAT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        category.setActive(true);
        category = dataManager.save(category);
        entitiesToRemove.add(category);

        RentalItem item = dataManager.create(RentalItem.class);
        item.setWarehouse(warehouse);
        item.setCategory(category);
        item.setNumber("TEST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        item.setStatus("READY");
        item = dataManager.save(item);
        entitiesToRemove.add(item);
        return item;
    }

    private AccessoryItem createAccessoryItem() {
        AccessoryCategory category = dataManager.create(AccessoryCategory.class);
        category.setName("Accessory category " + UUID.randomUUID());
        category.setCode(uniqueCode("ACC_CAT"));
        category.setActive(true);
        category = dataManager.save(category);
        entitiesToRemove.add(category);

        AccessorySubcategory subcategory = dataManager.create(AccessorySubcategory.class);
        subcategory.setCategory(category);
        subcategory.setName("Accessory subcategory " + UUID.randomUUID());
        subcategory.setCode(uniqueCode("ACC_SUB"));
        subcategory.setActive(true);
        subcategory = dataManager.save(subcategory);
        entitiesToRemove.add(subcategory);

        AccessoryItem item = dataManager.create(AccessoryItem.class);
        item.setCategory(category);
        item.setSubcategory(subcategory);
        item.setName("Accessory item " + UUID.randomUUID());
        item.setCode(uniqueCode("ACC_ITEM"));
        item.setActive(true);
        item = dataManager.save(item);
        entitiesToRemove.add(item);
        return item;
    }

    private RepairEstimate createEstimate(RentalItem rentalItem, Warehouse warehouse) {
        RepairEstimate estimate = dataManager.create(RepairEstimate.class);
        estimate.setRentalItem(rentalItem);
        estimate.setWarehouse(warehouse);
        estimate.setCabinNumber(rentalItem.getNumber());
        estimate.setDestinationParty("QR: " + UUID.randomUUID());
        estimate.setSourceParty("Test");
        estimate.setStatus(RepairEstimateStatus.DRAFT);
        estimate = dataManager.save(estimate);
        entitiesToRemove.add(estimate);
        return estimate;
    }

    private String uniqueCode(String prefix) {
        return (prefix + "_" + UUID.randomUUID().toString().replace("-", ""))
                .substring(0, 24)
                .toUpperCase(Locale.ROOT);
    }

    private void removeIfPresent(Object entity) {
        if (entity == null) {
            return;
        }
        try {
            dataManager.remove(entity);
        } catch (RuntimeException ignored) {
            // best effort cleanup
        }
    }
}
