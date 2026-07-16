package dev.buhanzaz.wmspanel.web;

import dev.buhanzaz.wmspanel.entity.AccessoryItem;
import dev.buhanzaz.wmspanel.entity.RentalItem;
import dev.buhanzaz.wmspanel.entity.RentalItemAccessory;
import dev.buhanzaz.wmspanel.entity.RentalItemCondition;
import dev.buhanzaz.wmspanel.entity.RentalItemEvent;
import dev.buhanzaz.wmspanel.entity.RentalItemEventPhoto;
import dev.buhanzaz.wmspanel.entity.RentalItemEventType;
import dev.buhanzaz.wmspanel.entity.PhotoProcessingStatus;
import dev.buhanzaz.wmspanel.entity.RentalAttributeDataType;
import dev.buhanzaz.wmspanel.entity.RentalAttributeDefinition;
import dev.buhanzaz.wmspanel.entity.RentalAttributeOption;
import dev.buhanzaz.wmspanel.entity.RentalAttributeValue;
import dev.buhanzaz.wmspanel.entity.RentalCategory;
import dev.buhanzaz.wmspanel.entity.RentalClassifierAttribute;
import dev.buhanzaz.wmspanel.entity.RentalClassifierAttributeCategoryLink;
import dev.buhanzaz.wmspanel.entity.RentalSubcategory;
import dev.buhanzaz.wmspanel.entity.RentalType;
import dev.buhanzaz.wmspanel.entity.RepairEstimate;
import dev.buhanzaz.wmspanel.entity.RepairEstimateLine;
import dev.buhanzaz.wmspanel.entity.RepairEstimateLineType;
import dev.buhanzaz.wmspanel.entity.RepairEstimateStatus;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.service.LocalMediaStorageService;
import dev.buhanzaz.wmspanel.service.PhotoProcessingQueueService;
import dev.buhanzaz.wmspanel.service.PhotoProcessingTaskMessage;
import dev.buhanzaz.wmspanel.service.RepairCatalogService;
import dev.buhanzaz.wmspanel.service.RepairMediaStorageProperties;
import dev.buhanzaz.wmspanel.service.RentalItemEventService;
import dev.buhanzaz.wmspanel.service.RentalItemService;
import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/mobile")
public class MobileApiController {
    private static final Logger log = LoggerFactory.getLogger(MobileApiController.class);

    private final RepairCatalogService repairCatalogService;
    private final DataManager dataManager;
    private final SystemAuthenticator systemAuthenticator;
    private final LocalMediaStorageService localMediaStorageService;
    private final RentalItemEventService rentalItemEventService;
    private final RentalItemService rentalItemService;
    private final PhotoProcessingQueueService photoProcessingQueueService;
    private final RepairMediaStorageProperties mediaStorageProperties;

    public MobileApiController(RepairCatalogService repairCatalogService,
                               DataManager dataManager,
                               SystemAuthenticator systemAuthenticator,
                               LocalMediaStorageService localMediaStorageService,
                               RentalItemEventService rentalItemEventService,
                               RentalItemService rentalItemService,
                               PhotoProcessingQueueService photoProcessingQueueService,
                               RepairMediaStorageProperties mediaStorageProperties) {
        this.repairCatalogService = repairCatalogService;
        this.dataManager = dataManager;
        this.systemAuthenticator = systemAuthenticator;
        this.localMediaStorageService = localMediaStorageService;
        this.rentalItemEventService = rentalItemEventService;
        this.rentalItemService = rentalItemService;
        this.photoProcessingQueueService = photoProcessingQueueService;
        this.mediaStorageProperties = mediaStorageProperties;
    }

    @GetMapping("/catalog")
    public CatalogResponse getCatalog() {
        return traceMobile("/catalog", "{}", this::loadCatalog,
                response -> "version=" + response.version() + ", nodes=" + response.nodes().size() + ", roots=" + response.rootCategoryIds().size());
    }

    private CatalogResponse loadCatalog() {
        Collection<RepairCatalogService.CatalogNode> allNodes = repairCatalogService.allNodes();
        List<CatalogNodeDto> nodes = allNodes.stream()
                .map(this::toCatalogNode)
                .sorted(Comparator.comparing(CatalogNodeDto::sortOrder).thenComparing(CatalogNodeDto::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<String> rootCategoryIds = repairCatalogService.mainMenuNodes().stream()
                .map(RepairCatalogService.CatalogNode::code)
                .map(this::mobileCode)
                .toList();
        String updatedAt = Instant.now().toString();
        return new CatalogResponse(
                "repair-catalog-" + updatedAt,
                updatedAt,
                rootCategoryIds,
                nodes
        );
    }

    @GetMapping("/warehouses")
    public List<WarehouseOptionResponse> getWarehouses() {
        return traceMobile("/warehouses", "{}", this::loadWarehouses,
                response -> "count=" + response.size());
    }

    private List<WarehouseOptionResponse> loadWarehouses() {
        return dataManager.load(Warehouse.class)
                .query("""
                        select e from Warehouse e
                        where e.active = true
                        order by coalesce(e.sortOrder, 2147483647), e.name
                        """)
                .list()
                .stream()
                .map(warehouse -> new WarehouseOptionResponse(
                        warehouse.getCode(),
                        warehouse.getName(),
                        warehouse.getCity(),
                        warehouseLabel(warehouse)
                ))
                .toList();
    }

    @GetMapping("/rental-items/validate")
    public RentalItemValidationResponse validateRentalItem(
            @RequestParam("warehouseCode") String warehouseCode,
            @RequestParam("number") String number
    ) {
        return traceMobile("/rental-items/validate",
                "warehouseCode=" + safe(warehouseCode) + ", number=" + safe(number),
                () -> validateRentalItemInternal(warehouseCode, number),
                response -> "valid=" + response.valid() + ", warehouseCode=" + response.warehouseCode() + ", number=" + response.number());
    }

    private RentalItemValidationResponse validateRentalItemInternal(String warehouseCode, String number) {
        String normalizedWarehouseCode = normalize(warehouseCode);
        String normalizedNumber = normalize(number);
        if (normalizedWarehouseCode == null || normalizedNumber == null) {
            return new RentalItemValidationResponse(
                    false,
                    false,
                    false,
                    normalizedNumber == null ? "" : normalizedNumber,
                    normalizedWarehouseCode == null ? "" : normalizedWarehouseCode,
                    null,
                    "Выберите склад и укажите номер бытовки."
            );
        }

        RentalItem rentalItem = findRentalItem(warehouseCode, number).orElse(null);
        if (rentalItem != null && rentalItem.getWarehouse() != null) {
            return new RentalItemValidationResponse(
                    true,
                    true,
                    true,
                    rentalItem.getNumber(),
                    rentalItem.getWarehouse().getCode(),
                    warehouseLabel(rentalItem.getWarehouse()),
                    "Номер " + rentalItem.getNumber() + " найден на складе " + warehouseLabel(rentalItem.getWarehouse()) + "."
            );
        }

        RentalItem duplicate = findRentalItemByNumber(number).orElse(null);
        if (duplicate != null && duplicate.getWarehouse() != null) {
            return new RentalItemValidationResponse(
                    false,
                    true,
                    false,
                    duplicate.getNumber(),
                    duplicate.getWarehouse().getCode(),
                    warehouseLabel(duplicate.getWarehouse()),
                    "Номер " + duplicate.getNumber() + " уже существует в WMS на складе " + warehouseLabel(duplicate.getWarehouse()) + "."
            );
        }

        return new RentalItemValidationResponse(
                false,
                false,
                false,
                normalizedNumber,
                normalizedWarehouseCode,
                findWarehouse(warehouseCode).map(this::warehouseLabel).orElse(null),
                "Номер " + normalizedNumber + " не найден в WMS. Можно создать новый объект."
        );
    }

    @GetMapping("/rental-items/passport/options")
    public InventoryPassportOptionsResponse getInventoryPassportOptions() {
        return traceMobile("/rental-items/passport/options", "{}", this::loadInventoryPassportOptions,
                response -> "categories=" + response.categories().size()
                        + ", classes=" + response.subcategories().size()
                        + ", types=" + response.types().size()
                        + ", attributes=" + response.attributes().size()
                        + ", accessories=" + response.accessories().size());
    }

    private InventoryPassportOptionsResponse loadInventoryPassportOptions() {
        List<RentalCategory> categories = dataManager.load(RentalCategory.class)
                .query("""
                        select e from RentalCategory e
                        where e.active = true
                        order by coalesce(e.sortOrder, 2147483647), e.name
                        """)
                .list();
        List<RentalSubcategory> subcategories = dataManager.load(RentalSubcategory.class)
                .query("""
                        select e from RentalSubcategory e
                        where e.active = true
                        order by coalesce(e.sortOrder, 2147483647), e.name
                        """)
                .fetchPlan(builder -> builder.addFetchPlan("_base").add("category", "_base"))
                .list();
        List<RentalType> types = dataManager.load(RentalType.class)
                .query("""
                        select e from RentalType e
                        where e.active = true
                        order by coalesce(e.sortOrder, 2147483647), e.name
                        """)
                .fetchPlan(builder -> builder.addFetchPlan("_base").add("subcategory", "_base"))
                .list();
        List<RentalAttributeDefinition> attributes = dataManager.load(RentalAttributeDefinition.class)
                .query("""
                        select e from RentalAttributeDefinition e
                        where e.active = true
                        order by coalesce(e.sortOrder, 2147483647), e.name
                        """)
                .list();
        Map<UUID, List<RentalAttributeOption>> optionsByAttributeId = dataManager.load(RentalAttributeOption.class)
                .query("""
                        select e from RentalAttributeOption e
                        where e.active = true
                        order by coalesce(e.sortOrder, 2147483647), e.name
                        """)
                .fetchPlan(builder -> builder.addFetchPlan("_base").add("attributeDefinition", "_base"))
                .list()
                .stream()
                .filter(option -> option.getAttributeDefinition() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        option -> option.getAttributeDefinition().getId(),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.toList()
                ));
        Map<UUID, Set<String>> categoryIdsByAttributeId = loadAttributeCategoryIds();
        List<AccessoryItem> accessories = rentalItemService.loadActiveAccessoryItems();

        return new InventoryPassportOptionsResponse(
                categories.stream()
                        .map(category -> new InventoryClassifierOption(
                                category.getId().toString(),
                                category.getName(),
                                null,
                                category.getSortOrder() == null ? Integer.MAX_VALUE : category.getSortOrder()
                        ))
                        .toList(),
                subcategories.stream()
                        .map(subcategory -> new InventoryClassifierOption(
                                subcategory.getId().toString(),
                                subcategory.getName(),
                                subcategory.getCategory() == null ? null : subcategory.getCategory().getId().toString(),
                                subcategory.getSortOrder() == null ? Integer.MAX_VALUE : subcategory.getSortOrder()
                        ))
                        .toList(),
                types.stream()
                        .map(type -> new InventoryClassifierOption(
                                type.getId().toString(),
                                type.getName(),
                                type.getSubcategory() == null ? null : type.getSubcategory().getId().toString(),
                                type.getSortOrder() == null ? Integer.MAX_VALUE : type.getSortOrder()
                        ))
                        .toList(),
                attributes.stream()
                        .map(attribute -> new InventoryAttributeDefinitionDto(
                                attribute.getId().toString(),
                                attribute.getCode(),
                                attribute.getName(),
                                attribute.getDataType() == null ? RentalAttributeDataType.STRING.getId() : attribute.getDataType().getId(),
                                categoryIdsByAttributeId.getOrDefault(attribute.getId(), Set.of()).stream().toList(),
                                optionsByAttributeId.getOrDefault(attribute.getId(), List.of()).stream()
                                        .map(option -> new InventoryAttributeOptionDto(
                                                option.getId().toString(),
                                                option.getName(),
                                                option.getSortOrder() == null ? Integer.MAX_VALUE : option.getSortOrder()
                                        ))
                                        .toList()
                        ))
                        .toList(),
                accessories.stream()
                        .map(accessory -> new InventoryAccessoryOptionDto(
                                accessory.getId().toString(),
                                accessory.getName(),
                                accessory.getCategory() == null ? null : accessory.getCategory().getName(),
                                accessory.getSubcategory() == null ? null : accessory.getSubcategory().getName(),
                                accessory.getSortOrder() == null ? Integer.MAX_VALUE : accessory.getSortOrder()
                        ))
                        .toList()
        );
    }

    @GetMapping("/rental-items/passport")
    public InventoryPassportResponse getInventoryPassport(
            @RequestParam("warehouseCode") String warehouseCode,
            @RequestParam("number") String number
    ) {
        return traceMobile("/rental-items/passport",
                "warehouseCode=" + safe(warehouseCode) + ", number=" + safe(number),
                () -> loadInventoryPassport(warehouseCode, number),
                response -> "exists=" + response.exists() + ", number=" + response.number());
    }

    private InventoryPassportResponse loadInventoryPassport(String warehouseCode, String number) {
        String normalizedWarehouseCode = normalize(warehouseCode);
        String normalizedNumber = normalize(number);
        RentalItem rentalItem = findRentalItemByNumber(number).or(() -> findRentalItem(warehouseCode, number)).orElse(null);
        if (rentalItem == null) {
            return new InventoryPassportResponse(
                    false,
                    normalizedNumber == null ? safe(number).toUpperCase(Locale.ROOT) : normalizedNumber,
                    normalizedWarehouseCode == null ? safe(warehouseCode).toUpperCase(Locale.ROOT) : normalizedWarehouseCode,
                    null,
                    null,
                    null,
                    Map.of(),
                    Map.of(),
                    "Новый объект. Заполни паспорт, он будет создан в WMS."
            );
        }
        Map<String, InventoryAttributeValueDto> attributes = dataManager.load(RentalAttributeValue.class)
                .query("select e from RentalAttributeValue e where e.rentalItem = :rentalItem")
                .parameter("rentalItem", rentalItem)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("attributeDefinition", "_base")
                        .add("valueOption", "_base"))
                .list()
                .stream()
                .filter(value -> value.getAttributeDefinition() != null)
                .collect(java.util.stream.Collectors.toMap(
                        value -> value.getAttributeDefinition().getId().toString(),
                        value -> new InventoryAttributeValueDto(
                                attributeValueAsString(value),
                                value.getValueOption() == null ? null : value.getValueOption().getId().toString()
                        ),
                        (left, right) -> right,
                        LinkedHashMap::new
                ));
        Map<String, Integer> accessories = rentalItemService.loadItemAccessories(rentalItem.getId()).stream()
                .filter(value -> value.getAccessoryItem() != null && value.getAccessoryItem().getId() != null)
                .collect(java.util.stream.Collectors.toMap(
                        value -> value.getAccessoryItem().getId().toString(),
                        value -> value.getQuantity() == null ? 0 : value.getQuantity(),
                        (left, right) -> right,
                        LinkedHashMap::new
                ));
        return new InventoryPassportResponse(
                true,
                rentalItem.getNumber(),
                rentalItem.getWarehouse() == null ? null : rentalItem.getWarehouse().getCode(),
                rentalItem.getCategory() == null ? null : rentalItem.getCategory().getId().toString(),
                rentalItem.getSubcategory() == null ? null : rentalItem.getSubcategory().getId().toString(),
                rentalItem.getType() == null ? null : rentalItem.getType().getId().toString(),
                attributes,
                accessories,
                "Паспорт объекта загружен из WMS."
        );
    }

    @PostMapping("/tasks")
    @Transactional
    public TaskSaveResponse saveTask(@RequestBody TaskSaveRequest request) {
        return traceMobile("/tasks",
                describeTaskSaveRequest(request),
                () -> saveTaskInternal(request),
                response -> "taskId=" + response.taskId() + ", photoOwnerId=" + safe(response.photoOwnerId()) + ", qrCode=" + response.qrCode() + ", updatedAt=" + response.updatedAt());
    }

    private TaskSaveResponse saveTaskInternal(TaskSaveRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required.");
        }
        InventoryRequestMode inventoryMode = InventoryRequestMode.fromCreationMode(request.creationMode());
        if (inventoryMode == null && !"ESTIMATE".equalsIgnoreCase(safe(request.creationMode()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mobile app currently supports only estimate drafts.");
        }

        Warehouse warehouse = findWarehouse(request.warehouseCode())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Warehouse is not found."));
        InventoryRentalItemResolution inventoryResolution = inventoryMode == null
                ? null
                : resolveInventoryRentalItem(warehouse, request.unitNumber(), request.inventoryPassport(), inventoryMode);
        RentalItem rentalItem = inventoryResolution == null
                ? findRentalItem(warehouse.getCode(), request.unitNumber())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rental item is not found on selected warehouse."))
                : inventoryResolution.rentalItem();
        if (inventoryMode != null && request.inventoryPassport() != null) {
            rentalItem = applyInventoryPassport(rentalItem, request.inventoryPassport(), warehouse);
        }
        RentalItemService.AccessorySyncResult accessorySync = inventoryMode == null
                ? new RentalItemService.AccessorySyncResult(false, List.of())
                : syncInventoryAccessories(rentalItem, request.inventoryPassport());
        OffsetDateTime eventDate = parseEventDate(request.eventDate());
        String comment = blankToNull(request.comment());

        if (inventoryMode == InventoryRequestMode.NEW) {
            findEstimateByQr(request.qrCode()).ifPresent(this::removeEstimate);
            RentalItemEvent event = rentalItemEventService.recordInventoryNewItem(
                    rentalItem,
                    rentalItem.getWarehouse(),
                    mobileTaskKey(request.qrCode()),
                    inventoryMode.creationMode(),
                    eventDate,
                    comment,
                    "Создан объект " + rentalItem.getNumber() + " при инвентаризации"
            );
            recordAccessoryInventoryEvent(rentalItem, accessorySync, event, eventDate, inventoryMode);
            return new TaskSaveResponse(
                    event.getId().toString(),
                    request.qrCode(),
                    event.getId().toString(),
                    Instant.now().toString()
            );
        }

        RepairEstimate estimate = findEstimateByQr(request.qrCode()).orElseGet(() -> dataManager.create(RepairEstimate.class));
        estimate.setRentalItem(rentalItem);
        estimate.setWarehouse(warehouse);
        estimate.setCabinNumber(rentalItem.getNumber());
        estimate.setDestinationParty(mobileQrDisplay(request.qrCode()));
        estimate.setSourceParty(inventoryMode == null ? "Мобильное приложение" : "Мобильная инвентаризация");
        estimate.setComment(comment);
        estimate.setDispatchDate(eventDate);
        estimate.setStatus(RepairEstimateStatus.DRAFT);
        estimate.setTotalAmount(BigDecimal.ZERO);
        // Сохраняем estimate перед созданием event, чтобы избежать cascade PERSIST
        // (EclipseLink не позволяет сохранять event со ссылкой на несохранённый estimate)
        estimate = dataManager.save(estimate);
        RentalItemEvent event = rentalItemEventService.recordEstimateDraft(
                estimate,
                estimate.getComment(),
                estimate.getDispatchDate(),
                inventoryMode == null ? "Мобильное приложение" : "Мобильная инвентаризация"
        );
        recordAccessoryInventoryEvent(rentalItem, accessorySync, event, eventDate, inventoryMode);
        RepairEstimate latestEstimate = dataManager.load(RepairEstimate.class)
                .id(estimate.getId())
                .one();
        latestEstimate.setLatestEvent(event);
        estimate = dataManager.save(latestEstimate);

        List<RepairEstimateLine> oldLines = dataManager.load(RepairEstimateLine.class)
                .query("select e from RepairEstimateLine e where e.estimate = :estimate")
                .parameter("estimate", estimate)
                .list();
        oldLines.forEach(dataManager::remove);

        BigDecimal total = BigDecimal.ZERO;
        List<TaskItemRequest> items = request.selectedItems() == null ? List.of() : request.selectedItems();
        for (int i = 0; i < items.size(); i++) {
            TaskItemRequest item = items.get(i);
            RepairEstimateLine line = dataManager.create(RepairEstimateLine.class);
            line.setEstimate(estimate);
            line.setLineType(mapLineType(item.catalogItemType()));
            line.setDescription(safe(item.name()));
            line.setLineComment(blankToNull(item.comment()));
            line.setUnit(blankToNull(item.unit()) == null ? "ед." : item.unit());
            line.setQuantity(parseQuantity(item.quantity()));
            line.setUnitPrice(parseMoney(item.price()));
            line.setLineTotal(line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity())));
            line.setRowOrder(i);
            line.setCatalogCode(blankToNull(item.catalogItemId()));
            line.setSourceLineKey("mobile-" + i + "-" + UUID.nameUUIDFromBytes(safe(item.name()).getBytes()));
            dataManager.save(line);
            total = total.add(line.getLineTotal());
        }

        estimate.setTotalAmount(total);
        estimate = dataManager.save(estimate);
        return new TaskSaveResponse(
                estimate.getId().toString(),
                request.qrCode(),
                event.getId().toString(),
                Instant.now().toString()
        );
    }

    private InventoryRentalItemResolution resolveInventoryRentalItem(Warehouse warehouse,
                                                                     String number,
                                                                     InventoryPassportRequest passport,
                                                                     InventoryRequestMode mode) {
        java.util.Optional<RentalItem> existing = findRentalItemByNumber(number);
        if (mode == InventoryRequestMode.NEW) {
            if (existing.isPresent()) {
                RentalItem duplicate = existing.get();
                String location = duplicate.getWarehouse() == null ? safe(warehouse.getCode()) : warehouseLabel(duplicate.getWarehouse());
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Номер " + duplicate.getNumber() + " уже существует в WMS на складе " + location + ".");
            }
            return new InventoryRentalItemResolution(createInventoryRentalItem(warehouse, number, passport), true);
        }
        if (mode == InventoryRequestMode.EXISTING) {
            if (existing.isPresent()) {
                return new InventoryRentalItemResolution(existing.get(), false);
            } else {
                return new InventoryRentalItemResolution(createInventoryRentalItem(warehouse, number, passport), true);
            }
        }
        if (existing.isPresent()) {
            return new InventoryRentalItemResolution(existing.get(), false);
        }
        return new InventoryRentalItemResolution(createInventoryRentalItem(warehouse, number, passport), true);
    }

    private RentalItem createInventoryRentalItem(Warehouse warehouse, String number, InventoryPassportRequest passport) {
        String normalizedNumber = normalize(number);
        if (normalizedNumber == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rental item number is required.");
        }
        RentalItem item = dataManager.create(RentalItem.class);
        item.setNumber(normalizedNumber);
        item.setWarehouse(warehouse);
        item.setCategory(resolveEntity(RentalCategory.class, passport == null ? null : passport.categoryId())
                .orElseGet(this::defaultRentalCategory));
        item.setSubcategory(resolveEntity(RentalSubcategory.class, passport == null ? null : passport.subcategoryId()).orElse(null));
        item.setType(resolveEntity(RentalType.class, passport == null ? null : passport.typeId()).orElse(null));
        item.setStatus("READY");
        item.setCondition(findInventoryNewCondition().orElse(null));
        return dataManager.save(item);
    }

    private RentalItem applyInventoryPassport(RentalItem item, InventoryPassportRequest passport, Warehouse warehouse) {
        if (passport == null) {
            return item;
        }
        resolveEntity(RentalCategory.class, passport.categoryId()).ifPresent(item::setCategory);
        item.setSubcategory(resolveEntity(RentalSubcategory.class, passport.subcategoryId()).orElse(null));
        item.setType(resolveEntity(RentalType.class, passport.typeId()).orElse(null));
        item.setWarehouse(warehouse);
        item = dataManager.save(item);

        List<InventoryAttributeValueRequest> attributes = passport.attributes() == null ? List.of() : passport.attributes();
        List<RentalAttributeValue> values = new java.util.ArrayList<>();
        for (InventoryAttributeValueRequest request : attributes) {
            RentalAttributeDefinition definition = resolveEntity(RentalAttributeDefinition.class, request.attributeId()).orElse(null);
            if (definition == null) {
                continue;
            }
            RentalAttributeValue value = dataManager.create(RentalAttributeValue.class);
            value.setAttributeDefinition(definition);
            applyAttributeValue(value, definition, request);
            values.add(value);
        }
        rentalItemService.applyInventoryPassportAttributes(item, values);
        return item;
    }

    private RentalItemService.AccessorySyncResult syncInventoryAccessories(RentalItem rentalItem, InventoryPassportRequest passport) {
        if (rentalItem == null || passport == null) {
            return new RentalItemService.AccessorySyncResult(false, List.of());
        }
        if (passport.accessories() == null) {
            return new RentalItemService.AccessorySyncResult(false, rentalItemService.loadItemAccessories(rentalItem.getId()));
        }
        List<RentalItemService.AccessoryQuantityDraft> drafts = passport.accessories().stream()
                .map(request -> new RentalItemService.AccessoryQuantityDraft(parseUuid(request.accessoryItemId()), request.quantity() == null ? 0 : request.quantity()))
                .filter(draft -> draft.accessoryItemId() != null)
                .toList();
        return rentalItemService.replaceItemAccessories(rentalItem, drafts);
    }

    private void recordAccessoryInventoryEvent(RentalItem rentalItem,
                                               RentalItemService.AccessorySyncResult accessorySync,
                                               RentalItemEvent parentEvent,
                                               OffsetDateTime eventDate,
                                               InventoryRequestMode inventoryMode) {
        if (rentalItem == null || accessorySync == null || !accessorySync.changed()) {
            return;
        }
        rentalItemEventService.recordAccessorySnapshot(
                rentalItem,
                rentalItem.getWarehouse(),
                parentEvent,
                eventDate,
                null,
                "MOBILE_INVENTORY",
                inventoryMode == null ? "Инвентаризация" : inventoryMode.creationMode(),
                accessorySync.assignments()
        );
    }

    @GetMapping("/tasks/by-qr/{qrCode}")
    public ResponseEntity<TaskResponse> getTaskByQr(@PathVariable String qrCode) {
        return traceMobile("/tasks/by-qr/{qrCode}",
                "qrCode=" + safe(qrCode),
                () -> findEstimateByQr(qrCode)
                        .map(estimate -> ResponseEntity.ok(toTaskResponse(estimate, qrCode)))
                        .or(() -> findInventoryEventByTaskKey(qrCode)
                                .map(event -> ResponseEntity.ok(toTaskResponse(event, qrCode))))
                        .orElseGet(() -> ResponseEntity.noContent().build()),
                response -> {
                    TaskResponse body = response.getBody();
                    return "status=" + response.getStatusCode().value() + ", found=" + (body != null) + (body == null ? "" : ", taskId=" + body.taskId());
                });
    }

    @DeleteMapping("/tasks/by-qr/{qrCode}")
    @Transactional
    public ResponseEntity<Void> deleteTaskByQr(@PathVariable String qrCode) {
        return asSystem(() -> {
            if (findEstimateByQr(qrCode).map(estimate -> {
                removeEstimate(estimate);
                return true;
            }).orElse(false)) {
                return ResponseEntity.noContent().build();
            }
            findInventoryEventByTaskKey(qrCode).ifPresent(this::removeEventOnly);
            return ResponseEntity.noContent().build();
        });
    }

    @PostMapping("/photos/upload-url")
    public PhotoUploadUrlResponse createPhotoUploadUrl(@RequestBody PhotoUploadUrlRequest request) {
        String photoId = UUID.randomUUID().toString();
        return new PhotoUploadUrlResponse(
                photoId,
                request == null ? null : request.ownerId(),
                "/api/mobile/photos/upload",
                "POST"
        );
    }

    @PostMapping("/photos/upload")
    public FileUploadResponse uploadPhoto(
            @RequestParam(value = "qrCode", required = false) String qrCode,
            @RequestParam(value = "unitNumber", required = false) String unitNumber,
            @RequestParam(value = "warehouseCode", required = false) String warehouseCode,
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "ownerType", required = false) String ownerType,
            @RequestParam(value = "ownerId", required = false) String ownerId,
            @RequestParam(value = "comment", required = false) String comment,
            @RequestParam("file") MultipartFile file
    ) {
        return traceMobile("/photos/upload",
                "qrCode=" + safe(qrCode)
                        + ", unitNumber=" + safe(unitNumber)
                        + ", warehouseCode=" + safe(warehouseCode)
                        + ", eventType=" + safe(eventType)
                        + ", ownerType=" + safe(ownerType)
                        + ", ownerId=" + safe(ownerId)
                        + ", comment=" + safe(comment)
                        + ", fileName=" + safe(file.getOriginalFilename())
                        + ", contentType=" + safe(file.getContentType())
                        + ", size=" + file.getSize(),
                () -> uploadPhotoInternal(qrCode, ownerType, ownerId, comment, file),
                response -> "photoId=" + response.photoId() + ", fileName=" + response.fileName() + ", size=" + response.size());
    }

    @PostMapping("/estimates/send-email")
    public SendEstimateEmailResponse sendEstimateEmail(@RequestBody SendEstimateEmailRequest request) {
        return new SendEstimateEmailResponse(
                false,
                "Отправка email из мобильного приложения пока не подключена. Смета сохранена как черновик."
        );
    }

    private TaskResponse toTaskResponse(RepairEstimate estimate, String qrCode) {
        List<RepairEstimateLine> lines = dataManager.load(RepairEstimateLine.class)
                .query("select e from RepairEstimateLine e where e.estimate = :estimate order by e.rowOrder")
                .parameter("estimate", estimate)
                .list();
        String creationMode = resolveCreationMode(estimate.getLatestEvent(), estimate);
        return new TaskResponse(
                estimate.getId().toString(),
                qrCode,
                estimate.getCabinNumber(),
                estimate.getWarehouse() == null ? null : estimate.getWarehouse().getCode(),
                creationMode,
                estimate.getDispatchDate() == null ? null : Long.toString(estimate.getDispatchDate().toInstant().toEpochMilli()),
                estimate.getComment(),
                estimate.getStatus() == null ? RepairEstimateStatus.DRAFT.name() : estimate.getStatus().name(),
                lines.stream().map(this::toTaskItemResponse).toList(),
                estimate.getRentalItem() == null ? List.of() : rentalItemEventService.loadLatestPhotos(estimate.getRentalItem().getId()).stream()
                        .map(this::toPhotoResponse)
                        .toList(),
                estimate.getLatestEvent() == null ? null : estimate.getLatestEvent().getId().toString(),
                null,
                null
        );
    }

    private TaskResponse toTaskResponse(RentalItemEvent event, String qrCode) {
        RentalItem rentalItem = event.getRentalItem();
        Warehouse warehouse = event.getWarehouse();
        return new TaskResponse(
                event.getId().toString(),
                qrCode,
                rentalItem == null ? null : rentalItem.getNumber(),
                warehouse == null ? null : warehouse.getCode(),
                resolveCreationMode(event, null),
                event.getEventDate() == null ? null : Long.toString(event.getEventDate().toInstant().toEpochMilli()),
                event.getComment(),
                RepairEstimateStatus.DRAFT.name(),
                List.of(),
                rentalItemEventService.loadLatestPhotos(rentalItem == null ? null : rentalItem.getId()).stream().map(this::toPhotoResponse).toList(),
                event.getId().toString(),
                null,
                null
        );
    }

    private TaskItemResponse toTaskItemResponse(RepairEstimateLine line) {
        return new TaskItemResponse(
                line.getCatalogCode(),
                line.getLineType() == RepairEstimateLineType.MATERIAL ? CatalogItemType.MATERIAL : CatalogItemType.WORK,
                line.getDescription(),
                line.getUnit(),
                line.getQuantity() == null ? "1" : line.getQuantity().toString(),
                formatPrice(line.getUnitPrice()),
                null,
                line.getLineComment()
        );
    }

    private FileUploadResponse uploadPhotoInternal(String qrCode,
                                                   String ownerType,
                                                   String ownerId,
                                                   String comment,
                                                   MultipartFile file) {
        RentalItemEvent directEvent = findInventoryEventByOwnerId(ownerId)
                .or(() -> isInventoryOwner(ownerType) ? findInventoryEventByTaskKey(qrCode) : java.util.Optional.empty())
                .orElse(null);
        if (directEvent != null) {
            return storePhoto(directEvent, comment, file);
        }

        RepairEstimate estimate = findEstimateByOwnerId(ownerId)
                .or(() -> findEstimateByQr(qrCode))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Estimate is not found for photo upload."));
        RentalItem rentalItem = estimate.getRentalItem();
        if (rentalItem == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estimate has no rental item.");
        }
        RentalItemEvent event = loadLatestEvent(estimate).orElse(null);
        if (event == null) {
            event = rentalItemEventService.recordEstimateDraft(
                    estimate,
                    estimate.getComment(),
                    estimate.getDispatchDate() == null ? OffsetDateTime.now() : estimate.getDispatchDate(),
                    estimate.getSourceParty()
            );
        }
        if (estimate.getLatestEvent() == null || !estimate.getLatestEvent().getId().equals(event.getId())) {
            RepairEstimate latestEstimate = dataManager.load(RepairEstimate.class)
                    .id(estimate.getId())
                    .one();
            latestEstimate.setLatestEvent(event);
            estimate = dataManager.save(latestEstimate);
        }
        return storePhoto(event, comment, file);
    }

    private FileUploadResponse storePhoto(RentalItemEvent event, String comment, MultipartFile file) {
        if (blankToNull(comment) != null) {
            event.setComment(blankToNull(comment));
            event = dataManager.save(event);
        }
        try {
            RentalItemEventPhoto photo = dataManager.create(RentalItemEventPhoto.class);
            if (photo.getId() == null) {
                photo.setId(UUID.randomUUID());
            }
            LocalMediaStorageService.StoredMedia storedMedia = localMediaStorageService.storeIncoming(
                    file.getInputStream(),
                    file.getOriginalFilename(),
                    blankToNull(file.getContentType()) == null ? "application/octet-stream" : file.getContentType(),
                    storagePathContext(event),
                    photo.getId()
            );
            int sortOrder = loadPhotos(event.getId()).size();
            photo.setEvent(event);
            photo.setStoragePath(storedMedia.relativePath());
            photo.setFamilyRootKey(storedMedia.familyRootKey());
            photo.setOriginalFileName(file.getOriginalFilename());
            photo.setContentType(blankToNull(file.getContentType()) == null ? "application/octet-stream" : file.getContentType());
            photo.setSizeBytes(storedMedia.sizeBytes());
            photo.setSortOrder(sortOrder);
            photo.setProcessingStatus(photoProcessingQueueService.queueEnabled() ? PhotoProcessingStatus.PENDING : PhotoProcessingStatus.READY);
            photo = dataManager.save(photo);
            enqueuePhotoProcessing(event, photo);
            return new FileUploadResponse(
                    photo.getId().toString(),
                    mediaUrl(photo.getId(), MediaVariant.THUMB),
                    mediaUrl(photo.getId(), MediaVariant.PREVIEW),
                    mediaUrl(photo.getId(), MediaVariant.ORIGINAL),
                    mediaUrl(photo.getId(), MediaVariant.PREVIEW),
                    photo.getOriginalFileName(),
                    photo.getContentType(),
                    photo.getSizeBytes()
            );
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to store photo.", ex);
        }
    }

    private void enqueuePhotoProcessing(RentalItemEvent event, RentalItemEventPhoto photo) {
        if (!photoProcessingQueueService.queueEnabled() || event == null || photo == null) {
            return;
        }
        try {
            photoProcessingQueueService.publish(new PhotoProcessingTaskMessage(
                    photo.getId(),
                    mediaStorageProperties.getMinioBucket(),
                    photo.getStoragePath(),
                    photo.getFamilyRootKey(),
                    photo.getContentType(),
                    event.getRentalItem() == null ? null : event.getRentalItem().getId(),
                    event.getId(),
                    event.getWarehouse() == null ? null : event.getWarehouse().getCode(),
                    event.getRentalItem() == null ? null : event.getRentalItem().getNumber(),
                    event.getEventType() == null ? null : event.getEventType().getId(),
                    mediaStorageProperties.getPreviewLongEdge(),
                    mediaStorageProperties.getThumbLongEdge(),
                    mediaStorageProperties.getTinyLongEdge()
            ));
        } catch (RuntimeException ex) {
            photoProcessingQueueService.markFailed(photo.getId(), ex.getMessage());
        }
    }

    private void removeEstimate(RepairEstimate estimate) {
        RentalItemEvent event = estimate.getLatestEvent();
        if (event != null) {
            removeEventWithPhotos(event);
        }
        dataManager.load(RepairEstimateLine.class)
                .query("select e from RepairEstimateLine e where e.estimate = :estimate")
                .parameter("estimate", estimate)
                .list()
                .forEach(dataManager::remove);
        dataManager.remove(estimate);
    }

    private void removeEventOnly(RentalItemEvent event) {
        removeEventWithPhotos(event);
    }

    private void removeEventWithPhotos(RentalItemEvent event) {
        loadPhotos(event.getId()).forEach(photo -> {
            if (photo.getFamilyRootKey() != null && !photo.getFamilyRootKey().isBlank()) {
                localMediaStorageService.deleteFamily(photo.getFamilyRootKey());
            } else {
                localMediaStorageService.delete(photo.getStoragePath());
            }
            dataManager.remove(photo);
        });
        dataManager.remove(event);
    }

    private PhotoResponse toPhotoResponse(RentalItemEventPhoto photo) {
        return new PhotoResponse(
                photo.getId().toString(),
                mediaUrl(photo.getId(), MediaVariant.THUMB),
                mediaUrl(photo.getId(), MediaVariant.PREVIEW),
                mediaUrl(photo.getId(), MediaVariant.ORIGINAL),
                mediaUrl(photo.getId(), MediaVariant.PREVIEW),
                photo.getOriginalFileName(),
                photo.getContentType()
        );
    }

    private CatalogNodeDto toCatalogNode(RepairCatalogService.CatalogNode node) {
        LinkedHashSet<String> childCodes = new LinkedHashSet<>();
        repairCatalogService.dependencyNodes(node.code()).stream()
                .map(RepairCatalogService.CatalogNode::code)
                .forEach(childCodes::add);
        repairCatalogService.followUpNodes(node.code()).stream()
                .map(RepairCatalogService.CatalogNode::code)
                .forEach(childCodes::add);
        List<String> childrenIds = childCodes.stream()
                .map(this::mobileCode)
                .toList();
        return new CatalogNodeDto(
                mobileCode(node.code()),
                safe(node.title()),
                mapNodeType(node, childrenIds.isEmpty()),
                blankToNull(node.unit()),
                formatPrice(node.unitPrice()),
                true,
                node.includeInEstimate(),
                node.mainMenuOrder() == null ? Integer.MAX_VALUE : node.mainMenuOrder(),
                childrenIds
        );
    }

    private CatalogItemType mapNodeType(RepairCatalogService.CatalogNode node, boolean leaf) {
        return switch (node.type()) {
            case CATEGORY -> CatalogItemType.CATEGORY;
            case WORK -> CatalogItemType.WORK;
            case MATERIAL -> CatalogItemType.MATERIAL;
            case OPTION -> leaf ? CatalogItemType.MATERIAL : CatalogItemType.SUBCATEGORY;
            case GROUP, SUBCATEGORY -> CatalogItemType.SUBCATEGORY;
            default -> leaf ? CatalogItemType.MATERIAL : CatalogItemType.SUBCATEGORY;
        };
    }

    private String formatPrice(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private String mobileCode(String code) {
        return safe(code).toLowerCase(Locale.ROOT);
    }

    private String warehouseLabel(Warehouse warehouse) {
        String city = blankToNull(warehouse.getCity());
        String name = blankToNull(warehouse.getName());
        if (city == null) {
            return name == null ? warehouse.getCode() : name;
        }
        if (name == null || city.equalsIgnoreCase(name)) {
            return city;
        }
        return city + " / " + name;
    }

    private Map<UUID, Set<String>> loadAttributeCategoryIds() {
        Map<UUID, Set<String>> result = new LinkedHashMap<>();
        dataManager.load(RentalClassifierAttribute.class)
                .query("select e from RentalClassifierAttribute e where e.active = true and e.category is not null")
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("attributeDefinition", "_base")
                        .add("category", "_base"))
                .list()
                .forEach(link -> {
                    if (link.getAttributeDefinition() != null && link.getCategory() != null) {
                        result.computeIfAbsent(link.getAttributeDefinition().getId(), ignored -> new java.util.LinkedHashSet<>())
                                .add(link.getCategory().getId().toString());
                    }
                });
        dataManager.load(RentalClassifierAttributeCategoryLink.class)
                .query("select e from RentalClassifierAttributeCategoryLink e")
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalClassifierAttribute", fetchPlanBuilder -> fetchPlanBuilder.addFetchPlan("_base").add("attributeDefinition", "_base"))
                        .add("category", "_base"))
                .list()
                .forEach(link -> {
                    RentalClassifierAttribute classifierAttribute = link.getRentalClassifierAttribute();
                    if (classifierAttribute != null && classifierAttribute.getAttributeDefinition() != null && link.getCategory() != null) {
                        result.computeIfAbsent(classifierAttribute.getAttributeDefinition().getId(), ignored -> new java.util.LinkedHashSet<>())
                                .add(link.getCategory().getId().toString());
                    }
                });
        return result;
    }

    private String attributeValueAsString(RentalAttributeValue value) {
        if (value.getValueOption() != null) {
            return value.getValueOption().getName();
        }
        if (value.getValueString() != null) {
            return value.getValueString();
        }
        if (value.getValueText() != null) {
            return value.getValueText();
        }
        if (value.getValueNumber() != null) {
            return value.getValueNumber().toString();
        }
        if (value.getValueBoolean() != null) {
            return value.getValueBoolean().toString();
        }
        return null;
    }

    private void applyAttributeValue(RentalAttributeValue value,
                                     RentalAttributeDefinition definition,
                                     InventoryAttributeValueRequest request) {
        String rawValue = blankToNull(request.value());
        if (definition.getDataType() == RentalAttributeDataType.ENUM) {
            value.setValueOption(resolveEntity(RentalAttributeOption.class, request.optionId()).orElse(null));
            value.setValueString(rawValue);
            return;
        }
        if (definition.getDataType() == RentalAttributeDataType.BOOLEAN) {
            value.setValueBoolean(rawValue == null ? null : Boolean.parseBoolean(rawValue));
            return;
        }
        if (definition.getDataType() == RentalAttributeDataType.NUMBER) {
            try {
                value.setValueNumber(rawValue == null ? null : Double.parseDouble(rawValue.replace(',', '.')));
            } catch (RuntimeException ignored) {
                value.setValueString(rawValue);
            }
            return;
        }
        if (definition.getDataType() == RentalAttributeDataType.TEXT) {
            value.setValueText(rawValue);
        } else {
            value.setValueString(rawValue);
        }
    }

    private <T> java.util.Optional<T> resolveEntity(Class<T> entityClass, String id) {
        String normalized = safe(id);
        if (normalized.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return dataManager.load(entityClass).id(UUID.fromString(normalized)).optional();
        } catch (RuntimeException ignored) {
            return java.util.Optional.empty();
        }
    }

    private UUID parseUuid(String value) {
        String normalized = safe(value);
        if (normalized.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(normalized);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private java.util.Optional<Warehouse> findWarehouse(String warehouseCode) {
        String normalizedWarehouseCode = normalize(warehouseCode);
        if (normalizedWarehouseCode == null) {
            return java.util.Optional.empty();
        }
        return dataManager.load(Warehouse.class)
                .query("select e from Warehouse e where upper(e.code) = :code")
                .parameter("code", normalizedWarehouseCode)
                .optional();
    }

    private RentalCategory defaultRentalCategory() {
        return dataManager.load(RentalCategory.class)
                .query("""
                        select e from RentalCategory e
                        where e.active = true
                        order by coalesce(e.sortOrder, 2147483647), e.name
                        """)
                .list()
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rental item category is not configured."));
    }

    private java.util.Optional<RentalItemCondition> findInventoryNewCondition() {
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

    private java.util.Optional<RentalItem> findRentalItem(String warehouseCode, String number) {
        String normalizedWarehouseCode = normalize(warehouseCode);
        String normalizedNumber = normalize(number);
        if (normalizedWarehouseCode == null || normalizedNumber == null) {
            return java.util.Optional.empty();
        }
        return dataManager.load(RentalItem.class)
                .query("""
                        select e from RentalItem e
                        where lower(e.number) = :number
                          and upper(e.warehouse.code) = :warehouseCode
                        """)
                .parameter("number", normalizedNumber.toLowerCase(Locale.ROOT))
                .parameter("warehouseCode", normalizedWarehouseCode)
                .optional();
    }

    private java.util.Optional<RentalItem> findRentalItemByNumber(String number) {
        String normalizedNumber = normalize(number);
        if (normalizedNumber == null) {
            return java.util.Optional.empty();
        }
        return dataManager.load(RentalItem.class)
                .query("select e from RentalItem e where lower(e.number) = :number")
                .parameter("number", normalizedNumber.toLowerCase(Locale.ROOT))
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("warehouse", "_base")
                        .add("category", "_base")
                        .add("subcategory", "_base")
                        .add("type", "_base"))
                .optional();
    }

    private java.util.Optional<RepairEstimate> findEstimateByQr(String qrCode) {
        String marker = mobileQr(qrCode);
        String displayMarker = mobileQrDisplay(qrCode);
        return dataManager.load(RepairEstimate.class)
                .query("select e from RepairEstimate e where e.destinationParty = :marker or e.destinationParty = :displayMarker")
                .parameter("marker", marker)
                .parameter("displayMarker", displayMarker)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", "_base")
                        .add("warehouse", "_base")
                        .add("latestEvent", "_base"))
                .optional();
    }

    private java.util.Optional<RepairEstimate> findEstimateByOwnerId(String ownerId) {
        String normalized = safe(ownerId);
        if (normalized.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            UUID id = UUID.fromString(normalized);
            return dataManager.load(RepairEstimate.class)
                    .id(id)
                    .fetchPlan(builder -> builder.addFetchPlan("_base")
                            .add("rentalItem", "_base")
                            .add("warehouse", "_base")
                            .add("latestEvent", "_base"))
                    .optional();
        } catch (RuntimeException ignored) {
            return java.util.Optional.empty();
        }
    }

    private java.util.Optional<RentalItemEvent> findInventoryEventByTaskKey(String qrCode) {
        String taskKey = mobileTaskKeyOrNull(qrCode);
        if (taskKey == null) {
            return java.util.Optional.empty();
        }
        List<RentalItemEvent> events = dataManager.load(RentalItemEvent.class)
                .query("""
                        select e from RentalItemEvent e
                        where e.mobileTaskKey = :taskKey
                        order by e.createdDate desc
                        """)
                .parameter("taskKey", taskKey)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", "_base")
                        .add("warehouse", "_base")
                        .add("estimate", "_base"))
                .list();
        return events.stream().findFirst();
    }

    private java.util.Optional<RentalItemEvent> findInventoryEventByOwnerId(String ownerId) {
        String normalized = safe(ownerId);
        if (normalized.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            UUID id = UUID.fromString(normalized);
            return dataManager.load(RentalItemEvent.class)
                    .id(id)
                    .fetchPlan(builder -> builder.addFetchPlan("_base")
                            .add("rentalItem", "_base")
                            .add("warehouse", "_base")
                            .add("estimate", "_base"))
                    .optional();
        } catch (RuntimeException ignored) {
            return java.util.Optional.empty();
        }
    }

    private List<RentalItemEventPhoto> loadPhotos(UUID eventId) {
        return rentalItemEventService.loadPhotos(eventId);
    }

    private java.util.Optional<RentalItemEvent> loadLatestEvent(RepairEstimate estimate) {
        RentalItemEvent latestEvent = estimate.getLatestEvent();
        if (latestEvent == null) {
            RentalItem rentalItem = estimate.getRentalItem();
            if (rentalItem == null || rentalItem.getId() == null) {
                return java.util.Optional.empty();
            }
            latestEvent = rentalItemEventService.latestEvent(rentalItem.getId());
            if (latestEvent == null) {
                return java.util.Optional.empty();
            }
        }
        return java.util.Optional.of(dataManager.load(RentalItemEvent.class)
                .id(latestEvent.getId())
                .fetchPlan("_base")
                .one());
    }

    private String mobileQr(String qrCode) {
        String normalized = safe(qrCode);
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "QR code is required.");
        }
        return "MOBILE_QR:" + normalized;
    }

    private String mobileQrDisplay(String qrCode) {
        String normalized = safe(qrCode);
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "QR code is required.");
        }
        return "QR: " + normalized;
    }

    private String mobileTaskKey(String qrCode) {
        String normalized = safe(qrCode);
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "QR code is required.");
        }
        return "MOBILE_TASK:" + normalized;
    }

    private String mobileTaskKeyOrNull(String qrCode) {
        String normalized = safe(qrCode);
        return normalized.isBlank() ? null : "MOBILE_TASK:" + normalized;
    }

    private String mediaUrl(UUID photoId) {
        return mediaUrl(photoId, MediaVariant.PREVIEW);
    }

    private String mediaUrl(UUID photoId, MediaVariant variant) {
        MediaVariant resolvedVariant = variant == null ? MediaVariant.PREVIEW : variant;
        return "/api/repair-media/" + photoId + "?variant=" + resolvedVariant.urlValue;
    }

    private LocalMediaStorageService.StoragePathContext storagePathContext(RentalItemEvent event) {
        return new LocalMediaStorageService.StoragePathContext(
                event == null || event.getWarehouse() == null ? null : event.getWarehouse().getCode(),
                event == null || event.getWarehouse() == null ? null : event.getWarehouse().getCity(),
                event == null || event.getRentalItem() == null ? null : event.getRentalItem().getNumber(),
                event == null || event.getEventType() == null ? null : event.getEventType().getId(),
                event == null ? null : event.getEventDate(),
                event == null ? null : event.getId(),
                null
        );
    }

    private RepairEstimateLineType mapLineType(CatalogItemType type) {
        return type == CatalogItemType.MATERIAL ? RepairEstimateLineType.MATERIAL : RepairEstimateLineType.WORK;
    }

    private OffsetDateTime parseEventDate(String eventDate) {
        try {
            long millis = Long.parseLong(safe(eventDate));
            return OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
        } catch (RuntimeException ignored) {
            return OffsetDateTime.now();
        }
    }

    private Integer parseQuantity(String quantity) {
        try {
            return Math.max(1, new BigDecimal(safe(quantity).replace(',', '.')).intValue());
        } catch (RuntimeException ignored) {
            return 1;
        }
    }

    private BigDecimal parseMoney(String value) {
        String normalized = safe(value).replace(',', '.');
        if (normalized.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(normalized);
        } catch (RuntimeException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private String normalize(String value) {
        String normalized = safe(value).trim();
        return normalized.isBlank() ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        String normalized = safe(value);
        return normalized.isBlank() ? null : normalized;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String describeTaskSaveRequest(TaskSaveRequest request) {
        if (request == null) {
            return "request=null";
        }
        return "qrCode=" + safe(request.qrCode())
                + ", warehouseCode=" + safe(request.warehouseCode())
                + ", unitNumber=" + safe(request.unitNumber())
                + ", creationMode=" + safe(request.creationMode())
                + ", items=" + (request.selectedItems() == null ? 0 : request.selectedItems().size())
                + ", photoIds=" + (request.photoIds() == null ? 0 : request.photoIds().size());
    }

    private String resolveCreationMode(RentalItemEvent event, RepairEstimate estimate) {
        if (event != null && blankToNull(event.getMobileCreationMode()) != null) {
            return event.getMobileCreationMode();
        }
        if (estimate != null && "Мобильная инвентаризация".equalsIgnoreCase(safe(estimate.getSourceParty()))) {
            return InventoryRequestMode.LEGACY.creationMode();
        }
        return "ESTIMATE";
    }

    private boolean isInventoryOwner(String ownerType) {
        return InventoryRequestMode.fromCreationMode(ownerType) != null;
    }

    private <T> T traceMobile(String endpoint, String requestDetails, Supplier<T> supplier, Function<T, String> successSummary) {
        log.info("mobile-api {} request {}", endpoint, requestDetails);
        systemAuthenticator.begin("admin");
        try {
            T result = supplier.get();
            log.info("mobile-api {} success {}", endpoint, successSummary.apply(result));
            return result;
        } catch (RuntimeException ex) {
            log.warn("mobile-api {} error {}: {}", endpoint, requestDetails, ex.getMessage(), ex);
            throw ex;
        } finally {
            systemAuthenticator.end();
        }
    }

    private <T> T asSystem(Supplier<T> supplier) {
        systemAuthenticator.begin("admin");
        try {
            return supplier.get();
        } finally {
            systemAuthenticator.end();
        }
    }

    public record CatalogResponse(
            String version,
            String updatedAt,
            List<String> rootCategoryIds,
            List<CatalogNodeDto> nodes
    ) {
    }

    public record CatalogNodeDto(
            String id,
            String name,
            CatalogItemType type,
            String unit,
            String price,
            boolean requiresQuantity,
            boolean includeInEstimate,
            int sortOrder,
            List<String> childrenIds
    ) {
    }

    public enum CatalogItemType {
        CATEGORY,
        SUBCATEGORY,
        WORK,
        MATERIAL
    }

    public record WarehouseOptionResponse(
            String code,
            String name,
            String city,
            String label
    ) {
    }

    public record RentalItemValidationResponse(
            boolean valid,
            boolean exists,
            boolean exactWarehouseMatch,
            String number,
            String warehouseCode,
            String warehouseLabel,
            String message
    ) {
    }

    public record InventoryPassportOptionsResponse(
            List<InventoryClassifierOption> categories,
            List<InventoryClassifierOption> subcategories,
            List<InventoryClassifierOption> types,
            List<InventoryAttributeDefinitionDto> attributes,
            List<InventoryAccessoryOptionDto> accessories
    ) {
    }

    public record InventoryClassifierOption(
            String id,
            String name,
            String parentId,
            int sortOrder
    ) {
    }

    public record InventoryAttributeDefinitionDto(
            String id,
            String code,
            String name,
            String dataType,
            List<String> categoryIds,
            List<InventoryAttributeOptionDto> options
    ) {
    }

    public record InventoryAttributeOptionDto(
            String id,
            String name,
            int sortOrder
    ) {
    }

    public record InventoryAccessoryOptionDto(
            String id,
            String name,
            String categoryName,
            String subcategoryName,
            int sortOrder
    ) {
    }

    public record InventoryPassportResponse(
            boolean exists,
            String number,
            String warehouseCode,
            String categoryId,
            String subcategoryId,
            String typeId,
            Map<String, InventoryAttributeValueDto> attributes,
            Map<String, Integer> accessories,
            String message
    ) {
    }

    public record InventoryAttributeValueDto(
            String value,
            String optionId
    ) {
    }

    public record InventoryPassportRequest(
            String categoryId,
            String subcategoryId,
            String typeId,
            List<InventoryAttributeValueRequest> attributes,
            List<InventoryAccessoryQuantityRequest> accessories
    ) {
    }

    public record InventoryAttributeValueRequest(
            String attributeId,
            String value,
            String optionId
    ) {
    }

    public record InventoryAccessoryQuantityRequest(
            String accessoryItemId,
            Integer quantity
    ) {
    }

    public record TaskSaveRequest(
            Long localTaskId,
            String qrCode,
            String unitNumber,
            String warehouseCode,
            String creationMode,
            String eventDate,
            String comment,
            String status,
            List<TaskItemRequest> selectedItems,
            List<String> photoIds,
            InventoryPassportRequest inventoryPassport
    ) {
    }

    public record TaskItemRequest(
            String catalogItemId,
            CatalogItemType catalogItemType,
            String name,
            String unit,
            String quantity,
            String price,
            String workerGroupLabel,
            String comment
    ) {
    }

    public record TaskSaveResponse(
            String taskId,
            String qrCode,
            String photoOwnerId,
            String updatedAt
    ) {
    }

    public record TaskResponse(
            String taskId,
            String qrCode,
            String unitNumber,
            String warehouseCode,
            String creationMode,
            String eventDate,
            String comment,
            String status,
            List<TaskItemResponse> selectedItems,
            List<PhotoResponse> photos,
            String photoOwnerId,
            String createdAt,
            String updatedAt
    ) {
    }

    public record TaskItemResponse(
            String catalogItemId,
            CatalogItemType catalogItemType,
            String name,
            String unit,
            String quantity,
            String price,
            String workerGroupLabel,
            String comment
    ) {
    }

    public record PhotoResponse(
            String photoId,
            String thumbUrl,
            String previewUrl,
            String originalUrl,
            String downloadUrl,
            String fileName,
            String contentType
    ) {
    }

    public record PhotoUploadUrlRequest(
            String qrCode,
            String unitNumber,
            String warehouseCode,
            String eventType,
            String ownerType,
            String ownerId,
            String eventDate,
            String comment,
            String fileName,
            String contentType
    ) {
    }

    public record PhotoUploadUrlResponse(
            String photoId,
            String eventId,
            String uploadUrl,
            String method
    ) {
    }

    public record FileUploadResponse(
            String photoId,
            String thumbUrl,
            String previewUrl,
            String originalUrl,
            String downloadUrl,
            String fileName,
            String contentType,
            Long size
    ) {
    }

    private enum MediaVariant {
        THUMB("thumb"),
        PREVIEW("preview"),
        TINY("tiny"),
        ORIGINAL("original");

        private final String urlValue;

        MediaVariant(String urlValue) {
            this.urlValue = urlValue;
        }
    }

    public record SendEstimateEmailRequest(
            String taskId,
            String qrCode,
            String email,
            String comment
    ) {
    }

    public record SendEstimateEmailResponse(
            boolean accepted,
            String message
    ) {
    }

    private enum InventoryRequestMode {
        LEGACY("INVENTORY"),
        NEW("INVENTORY_NEW"),
        EXISTING("INVENTORY_EXISTING");

        private final String creationMode;

        InventoryRequestMode(String creationMode) {
            this.creationMode = creationMode;
        }

        public String creationMode() {
            return creationMode;
        }

        static InventoryRequestMode fromCreationMode(String creationMode) {
            String normalized = creationMode == null ? "" : creationMode.trim().toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "INVENTORY" -> LEGACY;
                case "INVENTORY_NEW" -> NEW;
                case "INVENTORY_EXISTING" -> EXISTING;
                default -> null;
            };
        }
    }

    private record InventoryRentalItemResolution(RentalItem rentalItem, boolean createdNew) {
    }
}
