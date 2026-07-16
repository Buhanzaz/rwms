package dev.buhanzaz.wmspanel.web;

import dev.buhanzaz.wmspanel.entity.AccessoryCategory;
import dev.buhanzaz.wmspanel.entity.AccessoryItem;
import dev.buhanzaz.wmspanel.entity.AccessorySubcategory;
import dev.buhanzaz.wmspanel.entity.RentalCategory;
import dev.buhanzaz.wmspanel.entity.RentalItem;
import dev.buhanzaz.wmspanel.entity.RentalItemAccessory;
import dev.buhanzaz.wmspanel.entity.RentalItemCondition;
import dev.buhanzaz.wmspanel.entity.RentalItemEvent;
import dev.buhanzaz.wmspanel.entity.RentalItemEventPhoto;
import dev.buhanzaz.wmspanel.entity.RentalItemEventType;
import dev.buhanzaz.wmspanel.entity.RepairEstimate;
import dev.buhanzaz.wmspanel.entity.RepairEstimateLine;
import dev.buhanzaz.wmspanel.entity.RepairEstimateStatus;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.test_support.AuthenticatedAsAdmin;
import io.jmix.core.DataManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
@ActiveProfiles("test")
class MobileApiControllerInventoryTest {

    @Autowired
    MobileApiController mobileApiController;

    @Autowired
    DataManager dataManager;

    private final List<String> qrCodesToDelete = new ArrayList<>();
    private final List<RentalItem> rentalItemsToRemove = new ArrayList<>();
    private final List<RentalCategory> categoriesToRemove = new ArrayList<>();
    private final List<Warehouse> warehousesToRemove = new ArrayList<>();
    private final List<Object> accessoryEntitiesToRemove = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (String qrCode : qrCodesToDelete) {
            try {
                mobileApiController.deleteTaskByQr(qrCode);
            } catch (RuntimeException ignored) {
                // best-effort cleanup for tests
            }
        }
        qrCodesToDelete.clear();

        for (int i = rentalItemsToRemove.size() - 1; i >= 0; i--) {
            try {
                dataManager.remove(rentalItemsToRemove.get(i));
            } catch (RuntimeException ignored) {
                // entity may already be removed by cascade/cleanup
            }
        }
        rentalItemsToRemove.clear();

        for (int i = categoriesToRemove.size() - 1; i >= 0; i--) {
            try {
                dataManager.remove(categoriesToRemove.get(i));
            } catch (RuntimeException ignored) {
                // best-effort cleanup for tests
            }
        }
        categoriesToRemove.clear();

        for (int i = warehousesToRemove.size() - 1; i >= 0; i--) {
            try {
                dataManager.remove(warehousesToRemove.get(i));
            } catch (RuntimeException ignored) {
                // best-effort cleanup for tests
            }
        }
        warehousesToRemove.clear();

        for (int i = accessoryEntitiesToRemove.size() - 1; i >= 0; i--) {
            try {
                dataManager.remove(accessoryEntitiesToRemove.get(i));
            } catch (RuntimeException ignored) {
                // best-effort cleanup for tests
            }
        }
        accessoryEntitiesToRemove.clear();
    }

    @Test
    void saveTaskInventoryNewCreatesReadyRentalItemAndManualEvent() {
        Warehouse warehouse = createWarehouse("inventory-new");
        RentalCategory category = createCategory("inventory-new");
        String qrCode = uniqueQr("inventory-new");
        String number = uniqueNumber("NEW");
        qrCodesToDelete.add(qrCode);

        MobileApiController.TaskSaveResponse response = mobileApiController.saveTask(new MobileApiController.TaskSaveRequest(
                null,
                qrCode,
                number,
                warehouse.getCode(),
                "INVENTORY_NEW",
                Long.toString(System.currentTimeMillis()),
                "Новый объект без сметы",
                "DRAFT",
                List.of(),
                List.of(),
                new MobileApiController.InventoryPassportRequest(
                        category.getId().toString(),
                        null,
                        null,
                        List.of(),
                        List.of()
                )
        ));

        RentalItemEvent event = loadEvent(UUID.fromString(response.taskId()));
        RentalItem rentalItem = event.getRentalItem();
        rentalItemsToRemove.add(rentalItem);

        assertThat(rentalItem.getNumber()).isEqualTo(number);
        assertThat(rentalItem.getWarehouse().getId()).isEqualTo(warehouse.getId());
        assertThat(rentalItem.getStatus()).isEqualTo("READY");
        RentalItemCondition newCondition = findNewCondition().orElse(null);
        if (newCondition != null) {
            assertThat(rentalItem.getCondition()).isNotNull();
            assertThat(rentalItem.getCondition().getId()).isEqualTo(newCondition.getId());
        }

        assertThat(findEstimateByQr(qrCode)).isEmpty();
        assertThat(response.photoOwnerId()).isEqualTo(response.taskId());
        assertThat(event.getEstimate()).isNull();
        assertThat(event.getEventType()).isEqualTo(RentalItemEventType.INVENTORY_NEW_ITEM);
        assertThat(event.getTitle()).contains("Создан объект");
        assertThat(event.getTitle()).contains(number);
    }

    @Test
    void saveTaskInventoryExistingUsesExistingRentalItemAndCreatesInventoryEvent() {
        Warehouse warehouse = createWarehouse("inventory-existing");
        RentalCategory category = createCategory("inventory-existing");
        RentalItem existingItem = createRentalItem(warehouse, category, uniqueNumber("EXIST"), "READY");
        String qrCode = uniqueQr("inventory-existing");
        qrCodesToDelete.add(qrCode);
        createDraftEstimateForQr(qrCode, warehouse, existingItem);

        MobileApiController.TaskSaveResponse response = mobileApiController.saveTask(new MobileApiController.TaskSaveRequest(
                null,
                qrCode,
                existingItem.getNumber(),
                existingItem.getWarehouse().getCode(),
                "INVENTORY_EXISTING",
                Long.toString(System.currentTimeMillis()),
                "Проверка существующего объекта",
                "DRAFT",
                List.of(),
                List.of(),
                null
        ));

        RepairEstimate estimate = loadEstimate(response.taskId());
        assertThat(estimate.getRentalItem().getId()).isEqualTo(existingItem.getId());

        RentalItemEvent latestEvent = loadEvent(estimate.getLatestEvent().getId());
        assertThat(estimate.getRentalItem().getStatus()).isEqualTo("WAITING_ESTIMATE_CONFIRMATION");
        assertThat(latestEvent.getEventType()).isEqualTo(RentalItemEventType.ESTIMATE_DRAFT);
        assertThat(latestEvent.getTitle()).contains("Черновик сметы");
        assertThat(loadLines(estimate)).isEmpty();
    }

    @Test
    void saveTaskInventoryNewRejectsDuplicateNumber() {
        Warehouse warehouse = createWarehouse("inventory-duplicate");
        RentalCategory category = createCategory("inventory-duplicate");
        RentalItem existingItem = createRentalItem(warehouse, category, uniqueNumber("DUP"), "READY");
        String qrCode = uniqueQr("inventory-duplicate");

        assertThatThrownBy(() -> mobileApiController.saveTask(new MobileApiController.TaskSaveRequest(
                null,
                qrCode,
                existingItem.getNumber(),
                warehouse.getCode(),
                "INVENTORY_NEW",
                Long.toString(System.currentTimeMillis()),
                "Дубль",
                "DRAFT",
                List.of(),
                List.of(),
                null
        )))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(409);
                    assertThat(ex.getReason()).contains("существует");
                });
    }

    @Test
    void uploadPhotoForInventoryBindsPhotoToLatestInventoryEvent() {
        Warehouse warehouse = createWarehouse("inventory-photo");
        RentalCategory category = createCategory("inventory-photo");
        RentalItem existingItem = createRentalItem(warehouse, category, uniqueNumber("PHOTO"), "READY");
        String qrCode = uniqueQr("inventory-photo");
        qrCodesToDelete.add(qrCode);
        createDraftEstimateForQr(qrCode, warehouse, existingItem);

        MobileApiController.TaskSaveResponse response = mobileApiController.saveTask(new MobileApiController.TaskSaveRequest(
                null,
                qrCode,
                existingItem.getNumber(),
                existingItem.getWarehouse().getCode(),
                "INVENTORY_EXISTING",
                Long.toString(System.currentTimeMillis()),
                "Фото инвентаризации",
                "DRAFT",
                List.of(),
                List.of(),
                null
        ));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "inventory.jpg",
                "image/jpeg",
                "inventory-photo".getBytes(StandardCharsets.UTF_8)
        );
        MobileApiController.FileUploadResponse uploadResponse = mobileApiController.uploadPhoto(
                qrCode,
                existingItem.getNumber(),
                existingItem.getWarehouse().getCode(),
                "ESTIMATE",
                "INVENTORY_EXISTING",
                response.photoOwnerId(),
                "Фото для истории",
                file
        );

        RepairEstimate estimate = loadEstimate(response.taskId());
        RentalItemEvent latestEvent = loadEvent(estimate.getLatestEvent().getId());
        List<RentalItemEventPhoto> photos = loadPhotos(latestEvent);

        assertThat(uploadResponse.photoId()).isNotBlank();
        assertThat(latestEvent.getEventType()).isEqualTo(RentalItemEventType.ESTIMATE_DRAFT);
        assertThat(photos).hasSize(1);
        assertThat(photos.get(0).getEvent().getId()).isEqualTo(latestEvent.getId());
        assertThat(photos.get(0).getOriginalFileName()).isEqualTo("inventory.jpg");
        assertThat(photos.get(0).getStoragePath())
                .contains("/rental-items/" + warehouse.getCode() + "/" + existingItem.getNumber() + "/estimate-draft/")
                .contains("/" + latestEvent.getId() + "/");
    }

    @Test
    void uploadPhotoForInventoryNewBindsPhotoToCreatedEventWithoutEstimate() {
        Warehouse warehouse = createWarehouse("inventory-photo-new");
        RentalCategory category = createCategory("inventory-photo-new");
        String qrCode = uniqueQr("inventory-photo-new");
        String number = uniqueNumber("NPH");
        qrCodesToDelete.add(qrCode);

        MobileApiController.TaskSaveResponse response = mobileApiController.saveTask(new MobileApiController.TaskSaveRequest(
                null,
                qrCode,
                number,
                warehouse.getCode(),
                "INVENTORY_NEW",
                Long.toString(System.currentTimeMillis()),
                "Фото нового объекта",
                "DRAFT",
                List.of(),
                List.of(),
                new MobileApiController.InventoryPassportRequest(
                        category.getId().toString(),
                        null,
                        null,
                        List.of(),
                        List.of()
                )
        ));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "new-inventory.jpg",
                "image/jpeg",
                "inventory-photo-new".getBytes(StandardCharsets.UTF_8)
        );
        MobileApiController.FileUploadResponse uploadResponse = mobileApiController.uploadPhoto(
                qrCode,
                number,
                warehouse.getCode(),
                "ESTIMATE",
                "INVENTORY_NEW",
                response.photoOwnerId(),
                "Фото для нового объекта",
                file
        );

        RentalItemEvent event = loadEvent(UUID.fromString(response.taskId()));
        List<RentalItemEventPhoto> photos = loadPhotos(event);

        assertThat(findEstimateByQr(qrCode)).isEmpty();
        assertThat(uploadResponse.photoId()).isNotBlank();
        assertThat(photos).hasSize(1);
        assertThat(photos.get(0).getEvent().getId()).isEqualTo(event.getId());
        assertThat(photos.get(0).getOriginalFileName()).isEqualTo("new-inventory.jpg");
        assertThat(photos.get(0).getStoragePath())
                .contains("/rental-items/" + warehouse.getCode() + "/" + number + "/inventory-new-item/")
                .contains("/" + event.getId() + "/");
    }

    @Test
    void saveTaskInventoryExistingCreatesRentalItemIfNotFound() {
        Warehouse warehouse = createWarehouse("inv-exist-create");
        RentalCategory category = createCategory("inv-exist-create");
        String qrCode = uniqueQr("inv-exist-create");
        String number = uniqueNumber("CRE");
        qrCodesToDelete.add(qrCode);

        MobileApiController.TaskSaveResponse response = mobileApiController.saveTask(new MobileApiController.TaskSaveRequest(
                null,
                qrCode,
                number,
                warehouse.getCode(),
                "INVENTORY_EXISTING",
                Long.toString(System.currentTimeMillis()),
                "Создание отсутствующего существующего объекта",
                "DRAFT",
                List.of(),
                List.of(),
                null
        ));

        RepairEstimate estimate = loadEstimate(response.taskId());
        assertThat(estimate.getRentalItem()).isNotNull();
        assertThat(estimate.getRentalItem().getNumber()).isEqualTo(number);
        assertThat(estimate.getRentalItem().getWarehouse().getCode()).isEqualTo(warehouse.getCode());

        RentalItemEvent latestEvent = loadEvent(estimate.getLatestEvent().getId());
        assertThat(loadEstimate(response.taskId()).getRentalItem().getStatus()).isEqualTo("WAITING_ESTIMATE_CONFIRMATION");
        assertThat(latestEvent.getEventType()).isEqualTo(RentalItemEventType.ESTIMATE_DRAFT);
        assertThat(latestEvent.getTitle()).contains("Черновик сметы");
    }

    @Test
    void saveTaskInventoryExistingKeepsAccessoriesWhenFieldIsMissingAndClearsOnEmptyList() {
        Warehouse warehouse = createWarehouse("inventory-accessories");
        RentalCategory category = createCategory("inventory-accessories");
        RentalItem rentalItem = createRentalItem(warehouse, category, uniqueNumber("ACC"), "READY");
        AccessoryItem accessoryItem = createAccessoryItem();
        createRentalItemAccessory(rentalItem, accessoryItem, 2);

        String keepQr = uniqueQr("inventory-accessories-keep");
        qrCodesToDelete.add(keepQr);
        mobileApiController.saveTask(new MobileApiController.TaskSaveRequest(
                null,
                keepQr,
                rentalItem.getNumber(),
                warehouse.getCode(),
                "INVENTORY_EXISTING",
                Long.toString(System.currentTimeMillis()),
                "Не трогать аксессуары",
                "DRAFT",
                List.of(),
                List.of(),
                new MobileApiController.InventoryPassportRequest(
                        category.getId().toString(),
                        null,
                        null,
                        List.of(),
                        null
                )
        ));

        assertThat(loadRentalItemAccessories(rentalItem)).hasSize(1);
        assertThat(loadRentalItemAccessories(rentalItem).get(0).getQuantity()).isEqualTo(2);

        String clearQr = uniqueQr("inventory-accessories-clear");
        qrCodesToDelete.add(clearQr);
        mobileApiController.saveTask(new MobileApiController.TaskSaveRequest(
                null,
                clearQr,
                rentalItem.getNumber(),
                warehouse.getCode(),
                "INVENTORY_EXISTING",
                Long.toString(System.currentTimeMillis()),
                "Очистить аксессуары",
                "DRAFT",
                List.of(),
                List.of(),
                new MobileApiController.InventoryPassportRequest(
                        category.getId().toString(),
                        null,
                        null,
                        List.of(),
                        List.of()
                )
        ));

        assertThat(loadRentalItemAccessories(rentalItem)).isEmpty();
    }

    private Warehouse createWarehouse(String suffix) {
        Warehouse warehouse = dataManager.create(Warehouse.class);
        warehouse.setName("Test warehouse " + suffix + " " + UUID.randomUUID());
        warehouse.setCode(uniqueCode("WH"));
        warehouse.setCity("Test City");
        warehouse.setAddress("Test Address");
        warehouse.setTimeZone("Europe/Moscow");
        warehouse.setActive(true);
        warehouse.setSortOrder(10);
        warehouse = dataManager.save(warehouse);
        warehousesToRemove.add(warehouse);
        return warehouse;
    }

    private RentalCategory createCategory(String suffix) {
        RentalCategory category = dataManager.create(RentalCategory.class);
        category.setName("Test category " + suffix + " " + UUID.randomUUID());
        category.setCode(uniqueCode("CAT"));
        category.setActive(true);
        category.setSortOrder(10);
        category = dataManager.save(category);
        categoriesToRemove.add(category);
        return category;
    }

    private RentalItem createRentalItem(Warehouse warehouse, RentalCategory category, String number, String status) {
        RentalItem rentalItem = dataManager.create(RentalItem.class);
        rentalItem.setWarehouse(warehouse);
        rentalItem.setCategory(category);
        rentalItem.setNumber(number);
        rentalItem.setStatus(status);
        rentalItem = dataManager.save(rentalItem);
        rentalItemsToRemove.add(rentalItem);
        return rentalItem;
    }

    private AccessoryItem createAccessoryItem() {
        AccessoryCategory category = dataManager.create(AccessoryCategory.class);
        category.setName("Accessory category " + UUID.randomUUID());
        category.setCode(uniqueCode("ACC_CAT"));
        category.setActive(true);
        category = dataManager.save(category);
        accessoryEntitiesToRemove.add(category);

        AccessorySubcategory subcategory = dataManager.create(AccessorySubcategory.class);
        subcategory.setCategory(category);
        subcategory.setName("Accessory subcategory " + UUID.randomUUID());
        subcategory.setCode(uniqueCode("ACC_SUB"));
        subcategory.setActive(true);
        subcategory = dataManager.save(subcategory);
        accessoryEntitiesToRemove.add(subcategory);

        AccessoryItem item = dataManager.create(AccessoryItem.class);
        item.setCategory(category);
        item.setSubcategory(subcategory);
        item.setName("Accessory item " + UUID.randomUUID());
        item.setCode(uniqueCode("ACC_ITEM"));
        item.setActive(true);
        item = dataManager.save(item);
        accessoryEntitiesToRemove.add(item);
        return item;
    }

    private void createRentalItemAccessory(RentalItem rentalItem, AccessoryItem accessoryItem, int quantity) {
        RentalItemAccessory accessory = dataManager.create(RentalItemAccessory.class);
        accessory.setRentalItem(rentalItem);
        accessory.setAccessoryItem(accessoryItem);
        accessory.setQuantity(quantity);
        accessory = dataManager.save(accessory);
        accessoryEntitiesToRemove.add(accessory);
    }

    private RepairEstimate loadEstimate(String estimateId) {
        return dataManager.load(RepairEstimate.class)
                .id(UUID.fromString(estimateId))
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", rentalItem -> rentalItem.addFetchPlan("_base").add("warehouse", "_base").add("condition", "_base"))
                        .add("latestEvent", "_base")
                        .add("warehouse", "_base"))
                .one();
    }

    private java.util.Optional<RepairEstimate> findEstimateByQr(String qrCode) {
        return dataManager.load(RepairEstimate.class)
                .query("select e from RepairEstimate e where e.destinationParty = :qr")
                .parameter("qr", "QR: " + qrCode)
                .optional();
    }

    private RepairEstimate createDraftEstimateForQr(String qrCode, Warehouse warehouse, RentalItem rentalItem) {
        RepairEstimate estimate = dataManager.create(RepairEstimate.class);
        estimate.setRentalItem(rentalItem);
        estimate.setWarehouse(warehouse);
        estimate.setCabinNumber(rentalItem.getNumber());
        estimate.setDestinationParty("QR: " + qrCode);
        estimate.setSourceParty("Test bootstrap");
        estimate.setStatus(RepairEstimateStatus.DRAFT);
        return dataManager.save(estimate);
    }

    private RentalItemEvent loadEvent(UUID eventId) {
        return dataManager.load(RentalItemEvent.class)
                .id(eventId)
                .fetchPlan("_base")
                .one();
    }

    private List<RepairEstimateLine> loadLines(RepairEstimate estimate) {
        return dataManager.load(RepairEstimateLine.class)
                .query("select e from RepairEstimateLine e where e.estimate = :estimate")
                .parameter("estimate", estimate)
                .list();
    }

    private List<RentalItemEventPhoto> loadPhotos(RentalItemEvent event) {
        return dataManager.load(RentalItemEventPhoto.class)
                .query("select e from RentalItemEventPhoto e where e.event = :event order by e.sortOrder, e.id")
                .parameter("event", event)
                .fetchPlan(builder -> builder.addFetchPlan("_base").add("event", "_base"))
                .list();
    }

    private List<RentalItemAccessory> loadRentalItemAccessories(RentalItem rentalItem) {
        return dataManager.load(RentalItemAccessory.class)
                .query("select e from RentalItemAccessory e where e.rentalItem = :rentalItem order by e.id")
                .parameter("rentalItem", rentalItem)
                .fetchPlan(builder -> builder.addFetchPlan("_base").add("accessoryItem", "_base"))
                .list();
    }

    private java.util.Optional<RentalItemCondition> findNewCondition() {
        return dataManager.load(RentalItemCondition.class)
                .query("""
                        select e from RentalItemCondition e
                        where e.active = true
                          and (upper(e.code) = :code or upper(e.name) = :nameRu or upper(e.name) = :nameEn)
                        order by coalesce(e.sortOrder, 2147483647), e.name
                        """)
                .parameter("code", "NEW")
                .parameter("nameRu", "НОВАЯ")
                .parameter("nameEn", "NEW")
                .optional();
    }

    private String uniqueQr(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private String uniqueNumber(String prefix) {
        return (prefix + UUID.randomUUID().toString().replace("-", ""))
                .substring(0, 16)
                .toUpperCase(Locale.ROOT);
    }

    private String uniqueCode(String prefix) {
        return (prefix + "_" + UUID.randomUUID().toString().replace("-", ""))
                .substring(0, 24)
                .toUpperCase(Locale.ROOT);
    }
}
