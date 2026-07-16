package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.AccessoryItem;
import dev.buhanzaz.wmspanel.entity.AccessoryStockBalance;
import dev.buhanzaz.wmspanel.entity.RentalCategory;
import dev.buhanzaz.wmspanel.entity.RentalAttributeDefinition;
import dev.buhanzaz.wmspanel.entity.RentalAttributeOption;
import dev.buhanzaz.wmspanel.entity.RentalAttributeValue;
import dev.buhanzaz.wmspanel.entity.RentalClassifierAttribute;
import dev.buhanzaz.wmspanel.entity.RentalClassifierAttributeCategoryLink;
import dev.buhanzaz.wmspanel.entity.RepairEstimateLine;
import dev.buhanzaz.wmspanel.entity.RentalItemCondition;
import dev.buhanzaz.wmspanel.entity.RentalItem;
import dev.buhanzaz.wmspanel.entity.RentalItemAccessory;
import dev.buhanzaz.wmspanel.entity.RentalItemTag;
import dev.buhanzaz.wmspanel.entity.RentalTag;
import dev.buhanzaz.wmspanel.entity.RentalSubcategory;
import dev.buhanzaz.wmspanel.entity.RentalType;
import dev.buhanzaz.wmspanel.entity.User;
import dev.buhanzaz.wmspanel.entity.UserGridColumnSettings;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.view.rentalitem.RentalItemStatusSupport;
import io.jmix.core.DataManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RentalItemService {

    private final DataManager dataManager;
    private final WarehouseAccessService warehouseAccessService;

    public RentalItemService(DataManager dataManager, WarehouseAccessService warehouseAccessService) {
        this.dataManager = dataManager;
        this.warehouseAccessService = warehouseAccessService;
    }

    public Optional<RentalItem> findByNumber(String number) {
        String normalizedNumber = normalizeNumber(number);
        if (normalizedNumber == null) {
            return Optional.empty();
        }

        return dataManager.load(RentalItem.class)
                .query("""
                        select e from RentalItem e
                        where lower(e.number) = :number
                        """)
                .parameter("number", normalizedNumber.toLowerCase(Locale.ROOT))
                .optional();
    }


    @Transactional
    public RentalItem createNewItem(String number,
                                    Warehouse warehouse,
                                    RentalCategory category,
                                    RentalSubcategory subcategory,
                                    RentalType type,
                                    String status,
                                    String comment,
                                    List<RentalAttributeValue> attributeValues,
                                    List<RentalTag> tags) {
        return createNewItem(number, warehouse, category, subcategory, type, status, null, comment, attributeValues, tags);
    }

    @Transactional
    public RentalItem createNewItem(String number,
                                    Warehouse warehouse,
                                    RentalCategory category,
                                    RentalSubcategory subcategory,
                                    RentalType type,
                                    String status,
                                    RentalItemCondition condition,
                                    String comment,
                                    List<RentalAttributeValue> attributeValues,
                                    List<RentalTag> tags) {
        validateRequired(number, warehouse, category);
        String normalizedStatus = normalizeRequiredStatus(status);
        validateNewItemStatus(normalizedStatus);
        ensureNumberIsUnique(number);
        warehouseAccessService.checkAccess(warehouse);

        RentalItem item = dataManager.create(RentalItem.class);
        item.setNumber(number);
        applyEditableFields(item, warehouse, category, subcategory, type, normalizedStatus, condition, comment);
        RentalItem saved = saveNewItem(item);
        replaceAttributeValues(saved, attributeValues);
        replaceItemTags(saved, tags);
        return saved;
    }

    @Transactional
    public RentalItem updateFromTerminal(UUID id,
                                         Warehouse warehouse,
                                         RentalCategory category,
                                         RentalSubcategory subcategory,
                                         RentalType type,
                                         String status,
                                         String comment,
                                         List<RentalAttributeValue> attributeValues,
                                         List<RentalTag> tags) {
        return updateFromTerminal(id, warehouse, category, subcategory, type, status, null, comment, attributeValues, tags);
    }

    @Transactional
    public RentalItem updateFromTerminal(UUID id,
                                         Warehouse warehouse,
                                         RentalCategory category,
                                         RentalSubcategory subcategory,
                                         RentalType type,
                                         String status,
                                         RentalItemCondition condition,
                                         String comment,
                                         List<RentalAttributeValue> attributeValues,
                                         List<RentalTag> tags) {
        validateRequired("existing", warehouse, category);
        String normalizedStatus = normalizeRequiredStatus(status);
        warehouseAccessService.checkAccess(warehouse);

        RentalItem item = dataManager.load(RentalItem.class)
                .id(id)
                .one();
        applyEditableFields(item, warehouse, category, subcategory, type, normalizedStatus, condition, comment);
        RentalItem saved = dataManager.save(item);
        replaceAttributeValues(saved, attributeValues);
        replaceItemTags(saved, tags);
        return saved;
    }

    public List<RentalClassifierAttribute> loadActiveClassifierAttributes() {
        return loadActiveClassifierAttributes(null);
    }

    public List<RentalClassifierAttribute> loadActiveClassifierAttributes(RentalCategory category) {
        List<RentalClassifierAttribute> bindings = category == null
                ? dataManager.load(RentalClassifierAttribute.class)
                .query("""
                    select e from RentalClassifierAttribute e
                    join e.attributeDefinition d
                    where e.active = true
                      and d.active = true
                    order by coalesce(e.sortOrder, 999999), coalesce(d.sortOrder, 999999), d.name
                    """)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("attributeDefinition", "_base")
                        .add("category", "_base"))
                .list()
                : dataManager.load(RentalClassifierAttributeCategoryLink.class)
                .query("""
                    select e from RentalClassifierAttributeCategoryLink e
                    join e.rentalClassifierAttribute a
                    join a.attributeDefinition d
                    where e.category = :category
                      and (a.active = true or a.active is null)
                      and (d.active = true or d.active is null)
                    order by coalesce(a.sortOrder, 999999), coalesce(d.sortOrder, 999999), d.name
                    """)
                .parameter("category", category)
                .fetchPlan(builder -> builder.add("rentalClassifierAttribute", nested -> nested
                        .addFetchPlan("_base")
                        .add("attributeDefinition", "_base")
                        .add("category", "_base")))
                .list()
                .stream()
                .map(RentalClassifierAttributeCategoryLink::getRentalClassifierAttribute)
                .toList();
        return deduplicateBindings(bindings);
    }

    public List<RentalClassifierAttribute> loadClassifierAttributes(RentalCategory category,
                                                                    RentalSubcategory subcategory,
                                                                    RentalType type) {
        RentalCategory effectiveCategory = category;
        if (effectiveCategory == null && type != null && type.getSubcategory() != null) {
            effectiveCategory = type.getSubcategory().getCategory();
        }
        if (effectiveCategory == null && subcategory != null) {
            effectiveCategory = subcategory.getCategory();
        }
        if (effectiveCategory == null) {
            effectiveCategory = defaultCategory();
        }
        return loadActiveClassifierAttributes(effectiveCategory);
    }

    public List<RentalAttributeOption> loadAttributeOptions(RentalAttributeDefinition definition) {
        if (definition == null) {
            return List.of();
        }
        return dataManager.load(RentalAttributeOption.class)
                .query("""
                        select e from RentalAttributeOption e
                        where e.active = true and e.attributeDefinition = :definition
                        order by coalesce(e.sortOrder, 999999), e.name
                        """)
                .parameter("definition", definition)
                .list();
    }

    public List<RentalAttributeValue> loadAttributeValues(UUID rentalItemId) {
        if (rentalItemId == null) {
            return List.of();
        }
        return dataManager.load(RentalAttributeValue.class)
                .query("""
                    select e from RentalAttributeValue e
                    join e.attributeDefinition d
                    where e.rentalItem.id = :rentalItemId
                    order by coalesce(d.sortOrder, 999999), d.name
                    """)
                .parameter("rentalItemId", rentalItemId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", "_base")
                        .add("attributeDefinition", "_base")
                        .add("valueOption", "_base"))
                .list();
    }

    public List<RentalAttributeValue> loadAttributeValues(Collection<UUID> rentalItemIds) {
        if (rentalItemIds == null || rentalItemIds.isEmpty()) {
            return List.of();
        }
        return dataManager.load(RentalAttributeValue.class)
                .query("""
                    select e from RentalAttributeValue e
                    join e.rentalItem item
                    join e.attributeDefinition d
                    where item.id in :rentalItemIds
                    order by item.id, coalesce(d.sortOrder, 999999), d.name
                    """)
                .parameter("rentalItemIds", rentalItemIds)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", "_base")
                        .add("attributeDefinition", "_base")
                        .add("valueOption", "_base"))
                .list();
    }

    public List<RentalItemTag> loadItemTags(UUID rentalItemId) {
        if (rentalItemId == null) {
            return List.of();
        }
        return dataManager.load(RentalItemTag.class)
                .query("""
                    select e from RentalItemTag e
                    join e.tag t
                    where e.rentalItem.id = :rentalItemId
                    order by coalesce(t.sortOrder, 999999), t.name
                    """)
                .parameter("rentalItemId", rentalItemId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", "_base")
                        .add("tag", "_base"))
                .list();
    }

    public List<RentalItemTag> loadItemTags(Collection<UUID> rentalItemIds) {
        if (rentalItemIds == null || rentalItemIds.isEmpty()) {
            return List.of();
        }
        return dataManager.load(RentalItemTag.class)
                .query("""
                    select e from RentalItemTag e
                    join e.tag t
                    where e.rentalItem.id in :rentalItemIds
                    order by e.rentalItem.id, coalesce(t.sortOrder, 999999), t.name
                    """)
                .parameter("rentalItemIds", rentalItemIds)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", "_base")
                        .add("tag", "_base"))
                .list();
    }

    public List<RentalTag> loadActiveTags() {
        return dataManager.load(RentalTag.class)
                .query("select e from RentalTag e where e.active = true order by coalesce(e.sortOrder, 999999), e.name")
                .list();
    }

    public List<AccessoryItem> loadActiveAccessoryItems() {
        return dataManager.load(AccessoryItem.class)
                .query("""
                        select e from AccessoryItem e
                        where e.active = true
                        order by coalesce(e.sortOrder, 999999), e.name
                        """)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("category", "_base")
                        .add("subcategory", "_base"))
                .list();
    }

    public List<RentalItemAccessory> loadItemAccessories(UUID rentalItemId) {
        if (rentalItemId == null) {
            return List.of();
        }
        return dataManager.load(RentalItemAccessory.class)
                .query("""
                        select e from RentalItemAccessory e
                        where e.rentalItem.id = :rentalItemId
                        order by coalesce(e.accessoryItem.sortOrder, 999999), e.accessoryItem.name
                        """)
                .parameter("rentalItemId", rentalItemId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", "_base")
                        .add("accessoryItem", nested -> nested.addFetchPlan("_base")
                                .add("category", "_base")
                                .add("subcategory", "_base")))
                .list();
    }

    public List<RentalItemAccessory> loadAssignmentsByAccessory(UUID accessoryItemId) {
        if (accessoryItemId == null) {
            return List.of();
        }
        return dataManager.load(RentalItemAccessory.class)
                .query("""
                        select e from RentalItemAccessory e
                        where e.accessoryItem.id = :accessoryItemId
                        order by e.rentalItem.number
                        """)
                .parameter("accessoryItemId", accessoryItemId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", nested -> nested.addFetchPlan("_base")
                                .add("warehouse", "_base")
                                .add("category", "_base")
                                .add("subcategory", "_base")
                                .add("type", "_base"))
                        .add("accessoryItem", nested -> nested.addFetchPlan("_base")
                                .add("category", "_base")
                                .add("subcategory", "_base")))
                .list();
    }

    public List<RentalItem> loadAccessoryTransferTargets(UUID warehouseId, UUID sourceRentalItemId) {
        if (warehouseId == null) {
            return List.of();
        }
        String query = """
                select e from RentalItem e
                where e.warehouse.id = :warehouseId
                  and (:sourceRentalItemId is null or e.id <> :sourceRentalItemId)
                order by e.number
                """;
        return dataManager.load(RentalItem.class)
                .query(query)
                .parameter("warehouseId", warehouseId)
                .parameter("sourceRentalItemId", sourceRentalItemId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("warehouse", "_base")
                        .add("category", "_base")
                        .add("subcategory", "_base")
                        .add("type", "_base"))
                .list();
    }

    public Map<String, Boolean> loadGridColumnVisibility(String gridCode) {
        User user = currentUser();
        if (user == null || gridCode == null || gridCode.isBlank()) {
            return Map.of();
        }

        List<UserGridColumnSettings> settings = dataManager.load(UserGridColumnSettings.class)
                .query("""
                        select e from UserGridColumnSettings e
                        where e.user = :user and e.gridCode = :gridCode
                        order by coalesce(e.sortOrder, 999999), e.columnKey
                        """)
                .parameter("user", user)
                .parameter("gridCode", gridCode)
                .list();

        Map<String, Boolean> visibilityByKey = new LinkedHashMap<>();
        for (UserGridColumnSettings setting : settings) {
            if (setting.getColumnKey() == null || setting.getColumnKey().isBlank()) {
                continue;
            }
            visibilityByKey.put(setting.getColumnKey(), Boolean.TRUE.equals(setting.getVisible()));
        }
        return visibilityByKey;
    }

    @Transactional
    public void saveGridColumnVisibility(String gridCode, Map<String, Boolean> visibilityByKey) {
        User user = currentUser();
        if (user == null || gridCode == null || gridCode.isBlank() || visibilityByKey == null) {
            return;
        }

        Map<String, UserGridColumnSettings> existingByKey = new LinkedHashMap<>();
        for (UserGridColumnSettings setting : dataManager.load(UserGridColumnSettings.class)
                .query("""
                        select e from UserGridColumnSettings e
                        where e.user = :user and e.gridCode = :gridCode
                        """)
                .parameter("user", user)
                .parameter("gridCode", gridCode)
                .list()) {
            if (setting.getColumnKey() != null) {
                existingByKey.put(setting.getColumnKey(), setting);
            }
        }

        int sortOrder = 0;
        for (Map.Entry<String, Boolean> entry : visibilityByKey.entrySet()) {
            String columnKey = entry.getKey();
            if (columnKey == null || columnKey.isBlank()) {
                continue;
            }

            UserGridColumnSettings setting = existingByKey.get(columnKey);
            if (setting == null) {
                setting = dataManager.create(UserGridColumnSettings.class);
                setting.setUser(user);
                setting.setGridCode(gridCode);
                setting.setColumnKey(columnKey);
            }
            setting.setVisible(Boolean.TRUE.equals(entry.getValue()));
            setting.setSortOrder(sortOrder++);
            dataManager.saveWithoutReload(setting);
        }
    }

    protected void replaceAttributeValues(RentalItem item, List<RentalAttributeValue> values) {
        if (item == null) {
            return;
        }
        Map<UUID, RentalAttributeValue> existingByDefinitionId = new LinkedHashMap<>();
        for (RentalAttributeValue existingValue : loadAttributeValues(item.getId())) {
            UUID definitionId = existingValue.getAttributeDefinition() == null ? null : existingValue.getAttributeDefinition().getId();
            if (definitionId != null) {
                existingByDefinitionId.putIfAbsent(definitionId, existingValue);
            }
        }

        Map<UUID, RentalAttributeValue> incomingByDefinitionId = new LinkedHashMap<>();
        if (values != null) {
            for (RentalAttributeValue value : values) {
                UUID definitionId = value == null || value.getAttributeDefinition() == null ? null : value.getAttributeDefinition().getId();
                if (definitionId != null) {
                    incomingByDefinitionId.put(definitionId, value);
                }
            }
        }

        for (Map.Entry<UUID, RentalAttributeValue> entry : incomingByDefinitionId.entrySet()) {
            RentalAttributeValue source = entry.getValue();
            RentalAttributeValue target = existingByDefinitionId.remove(entry.getKey());
            if (target == null) {
                target = dataManager.create(RentalAttributeValue.class);
                target.setRentalItem(item);
                target.setAttributeDefinition(source.getAttributeDefinition());
            }
            copyAttributeValue(source, target);
            dataManager.saveWithoutReload(target);
        }

        if (!existingByDefinitionId.isEmpty()) {
            dataManager.remove(existingByDefinitionId.values());
        }
    }

    private void copyAttributeValue(RentalAttributeValue source, RentalAttributeValue target) {
        target.setRentalItem(source.getRentalItem() == null ? target.getRentalItem() : source.getRentalItem());
        target.setAttributeDefinition(source.getAttributeDefinition());
        target.setValueOption(source.getValueOption());
        target.setValueBoolean(source.getValueBoolean());
        target.setValueNumber(source.getValueNumber());
        target.setValueString(source.getValueString());
        target.setValueText(source.getValueText());
    }

    @Transactional
    public void applyInventoryPassportAttributes(RentalItem item, List<RentalAttributeValue> values) {
        if (item == null) {
            return;
        }
        replaceAttributeValues(item, values);
    }

    protected void replaceItemTags(RentalItem item, List<RentalTag> tags) {
        if (item == null) {
            return;
        }
        List<RentalItemTag> existing = loadItemTags(item.getId());
        if (!existing.isEmpty()) {
            dataManager.remove(existing);
        }
        if (tags == null || tags.isEmpty()) {
            return;
        }

        Set<UUID> seenTagIds = new LinkedHashSet<>();
        for (RentalTag tag : tags) {
            if (tag == null || tag.getId() == null || !seenTagIds.add(tag.getId())) {
                continue;
            }
            RentalItemTag itemTag = dataManager.create(RentalItemTag.class);
            itemTag.setRentalItem(item);
            itemTag.setTag(tag);
            dataManager.saveWithoutReload(itemTag);
        }
    }

    @Transactional
    public AccessorySyncResult replaceItemAccessories(RentalItem item, Collection<AccessoryQuantityDraft> drafts) {
        if (item == null) {
            return new AccessorySyncResult(false, List.of());
        }

        Map<UUID, RentalItemAccessory> existingByAccessoryId = new LinkedHashMap<>();
        for (RentalItemAccessory existing : loadItemAccessories(item.getId())) {
            UUID accessoryId = existing.getAccessoryItem() == null ? null : existing.getAccessoryItem().getId();
            if (accessoryId != null) {
                existingByAccessoryId.put(accessoryId, existing);
            }
        }

        Map<UUID, Integer> normalizedIncoming = new LinkedHashMap<>();
        if (drafts != null) {
            for (AccessoryQuantityDraft draft : drafts) {
                if (draft == null || draft.accessoryItemId() == null) {
                    continue;
                }
                int quantity = Math.max(0, draft.quantity());
                if (quantity > 0) {
                    normalizedIncoming.put(draft.accessoryItemId(), quantity);
                }
            }
        }

        boolean changed = false;
        Map<UUID, Integer> deltaByAccessoryId = new LinkedHashMap<>();

        for (Map.Entry<UUID, Integer> entry : normalizedIncoming.entrySet()) {
            RentalItemAccessory target = existingByAccessoryId.remove(entry.getKey());

            if (target == null) {
                target = dataManager.create(RentalItemAccessory.class);
                target.setRentalItem(item);
                target.setAccessoryItem(dataManager.getReference(AccessoryItem.class, entry.getKey()));
                target.setQuantity(entry.getValue());

                changed = true;
                deltaByAccessoryId.put(entry.getKey(), entry.getValue());
                dataManager.saveWithoutReload(target);

            } else if (!Objects.equals(target.getQuantity(), entry.getValue())) {
                int oldQuantity = target.getQuantity() == null ? 0 : target.getQuantity();
                target.setQuantity(entry.getValue());

                changed = true;
                deltaByAccessoryId.put(entry.getKey(), entry.getValue() - oldQuantity);
                dataManager.saveWithoutReload(target);
            }
        }

        if (!existingByAccessoryId.isEmpty()) {
            changed = true;
            existingByAccessoryId.forEach((accessoryId, existing) ->
                    deltaByAccessoryId.merge(accessoryId, -(existing.getQuantity() == null ? 0 : existing.getQuantity()), Integer::sum));
            dataManager.remove(existingByAccessoryId.values());
        }

        applyInventoryStockDeltas(item, deltaByAccessoryId);
        return new AccessorySyncResult(changed, loadItemAccessories(item.getId()));
    }

    @Transactional
    public AccessorySyncResult syncFurnitureEstimateAccessories(RentalItem item, Collection<RepairEstimateLine> estimateLines) {
        if (item == null || item.getId() == null || item.getWarehouse() == null || item.getWarehouse().getId() == null) {
            return new AccessorySyncResult(false, List.of());
        }

        List<AccessoryItem> linkedAccessories = dataManager.load(AccessoryItem.class)
                .query("""
                        select e from AccessoryItem e
                        left join fetch e.furnitureMaterial
                        where e.furnitureMaterial is not null
                        """)
                .list();
        if (linkedAccessories.isEmpty()) {
            return new AccessorySyncResult(false, loadItemAccessories(item.getId()));
        }

        Map<UUID, List<AccessoryItem>> accessoriesByMaterialId = linkedAccessories.stream()
                .filter(accessoryItem -> accessoryItem.getFurnitureMaterial() != null && accessoryItem.getFurnitureMaterial().getId() != null)
                .collect(Collectors.groupingBy(accessoryItem -> accessoryItem.getFurnitureMaterial().getId(), LinkedHashMap::new, Collectors.toList()));
        List<String> duplicateMappings = accessoriesByMaterialId.values().stream()
                .filter(accessories -> accessories.size() > 1)
                .map(accessories -> accessories.stream()
                        .map(AccessoryItem::getName)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining(", ")))
                .filter(value -> !value.isBlank())
                .toList();
        if (!duplicateMappings.isEmpty()) {
            throw new IllegalArgumentException("Один мебельный материал привязан сразу к нескольким позициям доп. оборудования: "
                    + String.join("; ", duplicateMappings));
        }

        Map<String, AccessoryItem> accessoryByCatalogCode = linkedAccessories.stream()
                .filter(accessoryItem -> accessoryItem.getFurnitureMaterial() != null)
                .filter(accessoryItem -> accessoryItem.getFurnitureMaterial().getCode() != null
                        && !accessoryItem.getFurnitureMaterial().getCode().isBlank())
                .collect(Collectors.toMap(
                        accessoryItem -> accessoryItem.getFurnitureMaterial().getCode().trim().toUpperCase(Locale.ROOT),
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));

        LinkedHashSet<UUID> furnitureAccessoryIds = linkedAccessories.stream()
                .map(AccessoryItem::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<UUID, Integer> desiredByAccessoryId = new LinkedHashMap<>();
        if (estimateLines != null) {
            for (RepairEstimateLine line : estimateLines) {
                if (line == null || line.getCatalogCode() == null || line.getCatalogCode().isBlank()) {
                    continue;
                }
                AccessoryItem accessoryItem = accessoryByCatalogCode.get(line.getCatalogCode().trim().toUpperCase(Locale.ROOT));
                if (accessoryItem == null || accessoryItem.getId() == null) {
                    continue;
                }
                int quantity = Math.max(0, line.getQuantity() == null ? 0 : line.getQuantity());
                if (quantity > 0) {
                    desiredByAccessoryId.merge(accessoryItem.getId(), quantity, Integer::sum);
                }
            }
        }

        Map<UUID, RentalItemAccessory> existingByAccessoryId = loadItemAccessories(item.getId()).stream()
                .filter(existing -> existing.getAccessoryItem() != null && existing.getAccessoryItem().getId() != null)
                .filter(existing -> furnitureAccessoryIds.contains(existing.getAccessoryItem().getId()))
                .collect(Collectors.toMap(existing -> existing.getAccessoryItem().getId(), Function.identity(), (left, right) -> left, LinkedHashMap::new));

        boolean changed = false;
        UUID warehouseId = item.getWarehouse().getId();

        LinkedHashSet<UUID> allAccessoryIds = new LinkedHashSet<>();
        allAccessoryIds.addAll(existingByAccessoryId.keySet());
        allAccessoryIds.addAll(desiredByAccessoryId.keySet());

        for (UUID accessoryId : allAccessoryIds) {
            int currentQuantity = existingByAccessoryId.containsKey(accessoryId)
                    ? Math.max(0, existingByAccessoryId.get(accessoryId).getQuantity() == null ? 0 : existingByAccessoryId.get(accessoryId).getQuantity())
                    : 0;
            int desiredQuantity = Math.max(0, desiredByAccessoryId.getOrDefault(accessoryId, 0));
            int delta = desiredQuantity - currentQuantity;
            if (delta == 0) {
                continue;
            }

            AccessoryStockBalance balance = findAccessoryStockBalance(warehouseId, accessoryId)
                    .orElseGet(() -> createAccessoryStockBalance(warehouseId, accessoryId));
            int available = balance.getQuantityAvailable() == null ? 0 : balance.getQuantityAvailable();
            if (delta > 0 && available < delta) {
                AccessoryItem accessoryItem = linkedAccessories.stream()
                        .filter(candidate -> Objects.equals(candidate.getId(), accessoryId))
                        .findFirst()
                        .orElse(null);
                String itemName = accessoryItem == null || accessoryItem.getName() == null || accessoryItem.getName().isBlank()
                        ? "мебель"
                        : accessoryItem.getName();
                throw new IllegalArgumentException("Недостаточно свободного остатка мебели \"" + itemName
                        + "\" на складе. Доступно: " + available + ", требуется: " + delta);
            }

            RentalItemAccessory assignment = existingByAccessoryId.get(accessoryId);
            if (desiredQuantity <= 0) {
                if (assignment != null) {
                    dataManager.remove(assignment);
                    changed = true;
                }
            } else if (assignment == null) {
                RentalItemAccessory created = dataManager.create(RentalItemAccessory.class);
                created.setRentalItem(item);
                created.setAccessoryItem(dataManager.getReference(AccessoryItem.class, accessoryId));
                created.setQuantity(desiredQuantity);
                dataManager.saveWithoutReload(created);
                changed = true;
            } else {
                assignment.setQuantity(desiredQuantity);
                dataManager.saveWithoutReload(assignment);
                changed = true;
            }

            balance.setQuantityAvailable(addNonNegative(balance.getQuantityAvailable(), -delta));
            dataManager.saveWithoutReload(balance);
        }

        return new AccessorySyncResult(changed, loadItemAccessories(item.getId()));
    }

    @Transactional
    public AccessoryTransferResult transferItemAccessory(UUID sourceAssignmentId, UUID targetRentalItemId, int quantity) {
        if (sourceAssignmentId == null || targetRentalItemId == null) {
            throw new IllegalArgumentException("Не выбрана исходная позиция или целевой объект");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Количество для перемещения должно быть больше 0");
        }

        RentalItemAccessory sourceAssignment = dataManager.load(RentalItemAccessory.class)
                .id(sourceAssignmentId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", nested -> nested.addFetchPlan("_base").add("warehouse", "_base"))
                        .add("accessoryItem", "_base"))
                .one();
        RentalItem sourceItem = sourceAssignment.getRentalItem();
        AccessoryItem accessoryItem = sourceAssignment.getAccessoryItem();
        RentalItem targetItem = dataManager.load(RentalItem.class)
                .id(targetRentalItemId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("warehouse", "_base")
                        .add("category", "_base")
                        .add("subcategory", "_base")
                        .add("type", "_base"))
                .one();

        if (sourceItem == null || sourceItem.getWarehouse() == null || targetItem.getWarehouse() == null
                || !Objects.equals(sourceItem.getWarehouse().getId(), targetItem.getWarehouse().getId())) {
            throw new IllegalArgumentException("Перемещать допоборудование можно только внутри одного склада");
        }
        int sourceQuantity = sourceAssignment.getQuantity() == null ? 0 : sourceAssignment.getQuantity();
        if (sourceQuantity < quantity) {
            throw new IllegalArgumentException("В исходном объекте доступно только " + sourceQuantity + " шт.");
        }

        int remaining = sourceQuantity - quantity;
        if (remaining == 0) {
            dataManager.remove(sourceAssignment);
        } else {
            sourceAssignment.setQuantity(remaining);
            dataManager.saveWithoutReload(sourceAssignment);
        }

        RentalItemAccessory targetAssignment = dataManager.load(RentalItemAccessory.class)
                .query("""
                        select e from RentalItemAccessory e
                        where e.rentalItem = :rentalItem
                          and e.accessoryItem = :accessoryItem
                        """)
                .parameter("rentalItem", targetItem)
                .parameter("accessoryItem", accessoryItem)
                .optional()
                .orElseGet(() -> {
                    RentalItemAccessory created = dataManager.create(RentalItemAccessory.class);
                    created.setRentalItem(targetItem);
                    created.setAccessoryItem(accessoryItem);
                    created.setQuantity(0);
                    return created;
                });
        targetAssignment.setQuantity((targetAssignment.getQuantity() == null ? 0 : targetAssignment.getQuantity()) + quantity);
        dataManager.saveWithoutReload(targetAssignment);

        return new AccessoryTransferResult(
                sourceItem,
                targetItem,
                accessoryItem,
                quantity,
                loadItemAccessories(sourceItem.getId()),
                loadItemAccessories(targetItem.getId())
        );
    }

    private void applyInventoryStockDeltas(RentalItem item, Map<UUID, Integer> deltaByAccessoryId) {
        if (item == null || item.getWarehouse() == null || item.getWarehouse().getId() == null || deltaByAccessoryId == null || deltaByAccessoryId.isEmpty()) {
            return;
        }
        UUID warehouseId = item.getWarehouse().getId();
        for (Map.Entry<UUID, Integer> entry : deltaByAccessoryId.entrySet()) {
            UUID accessoryItemId = entry.getKey();
            Integer delta = entry.getValue();
            if (accessoryItemId == null || delta == null || delta == 0) {
                continue;
            }
            AccessoryStockBalance balance = findAccessoryStockBalance(warehouseId, accessoryItemId)
                    .orElseGet(() -> createAccessoryStockBalance(warehouseId, accessoryItemId));
            if (balance.getId() == null && delta < 0) {
                continue;
            }
            balance.setQuantityTotal(addNonNegative(balance.getQuantityTotal(), delta));
            balance.setQuantityAvailable(addNonNegative(balance.getQuantityAvailable(), delta));
            dataManager.saveWithoutReload(balance);
        }
    }

    private Optional<AccessoryStockBalance> findAccessoryStockBalance(UUID warehouseId, UUID accessoryItemId) {
        return dataManager.load(AccessoryStockBalance.class)
                .query("""
                        select e from AccessoryStockBalance e
                        where e.warehouse.id = :warehouseId
                          and e.accessoryItem.id = :accessoryItemId
                        """)
                .parameter("warehouseId", warehouseId)
                .parameter("accessoryItemId", accessoryItemId)
                .optional();
    }

    private AccessoryStockBalance createAccessoryStockBalance(UUID warehouseId, UUID accessoryItemId) {
        AccessoryStockBalance created = dataManager.create(AccessoryStockBalance.class);
        created.setWarehouse(dataManager.getReference(Warehouse.class, warehouseId));
        created.setAccessoryItem(dataManager.getReference(AccessoryItem.class, accessoryItemId));
        created.setQuantityTotal(0);
        created.setQuantityAvailable(0);
        created.setQuantityReserved(0);
        created.setQuantityInRent(0);
        created.setQuantityBroken(0);
        created.setQuantityWrittenOff(0);
        return created;
    }

    private int addNonNegative(Integer value, Integer delta) {
        return Math.max(0, (value == null ? 0 : value) + (delta == null ? 0 : delta));
    }

    public User currentUser() {
        String username = warehouseAccessService.username();
        if (username == null || username.isBlank()) {
            return null;
        }
        return dataManager.load(User.class)
                .query("select e from wmspanel_User e where e.username = :username")
                .parameter("username", username)
                .optional()
                .orElse(null);
    }

    private void applyEditableFields(RentalItem item,
                                     Warehouse warehouse,
                                     RentalCategory category,
                                     RentalSubcategory subcategory,
                                     RentalType type,
                                     String status,
                                     RentalItemCondition condition,
                                     String comment) {
        item.setWarehouse(warehouse);
        item.setCategory(category != null ? category : (item.getCategory() != null ? item.getCategory() : defaultCategory()));
        item.setSubcategory(subcategory);
        item.setType(type);
        item.setStatus(normalize(status));
        if (condition != null || item.getId() == null) {
            item.setCondition(condition);
        }
        item.setComment(normalize(comment));
    }

    private void validateRequired(String number, Warehouse warehouse, RentalCategory category) {
        if (number == null || number.isBlank()) {
            throw new IllegalArgumentException("Укажите номер");
        }
        if (warehouse == null) {
            throw new IllegalArgumentException("Выберите склад");
        }
        if (category == null) {
            throw new IllegalArgumentException("Выберите категорию");
        }
    }

    private void ensureNumberIsUnique(String number) {
        if (findByNumber(number).isPresent()) {
            throw new IllegalArgumentException("Единица с таким номером уже существует");
        }
    }

    private RentalItem saveNewItem(RentalItem item) {
        try {
            return dataManager.save(item);
        } catch (RuntimeException ex) {
            if (isDuplicateNumberViolation(ex)) {
                throw new IllegalArgumentException("Единица с таким номером уже существует", ex);
            }
            throw ex;
        }
    }

    private void validateNewItemStatus(String status) {
        if (status == null || !RentalItemStatusSupport.NEW_ITEM_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Для новой единицы доступен только статус Готов или Требует осмотра");
        }
    }

    private String normalizeRequiredStatus(String status) {
        String normalized = normalize(status);
        if (normalized == null) {
            throw new IllegalArgumentException("Укажите статус");
        }
        return normalized;
    }

    private String normalizeNumber(String value) {
        String normalized = normalize(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isDuplicateNumberViolation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null
                    && (message.contains("IDX_RENTAL_ITEM_UNQ_NUMBER")
                    || message.contains("unique constraint or index violation"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private RentalCategory defaultCategory() {
        return dataManager.load(RentalCategory.class)
                .query("select e from RentalCategory e where e.active = true order by e.sortOrder, e.name")
                .list()
                .stream()
                .findFirst()
                .orElse(null);
    }

    private List<RentalClassifierAttribute> deduplicateBindings(List<RentalClassifierAttribute> bindings) {
        Map<UUID, RentalClassifierAttribute> uniqueByDefinition = new LinkedHashMap<>();
        for (RentalClassifierAttribute binding : bindings) {
            if (binding == null || binding.getAttributeDefinition() == null || binding.getAttributeDefinition().getId() == null) {
                continue;
            }
            uniqueByDefinition.putIfAbsent(binding.getAttributeDefinition().getId(), binding);
        }
        return new ArrayList<>(uniqueByDefinition.values());
    }

    public record AccessoryQuantityDraft(UUID accessoryItemId, int quantity) {
    }

    public record AccessorySyncResult(boolean changed, List<RentalItemAccessory> assignments) {
    }

    public record AccessoryTransferResult(RentalItem sourceItem,
                                          RentalItem targetItem,
                                          AccessoryItem accessoryItem,
                                          int quantity,
                                          List<RentalItemAccessory> sourceAssignments,
                                          List<RentalItemAccessory> targetAssignments) {
    }
}
