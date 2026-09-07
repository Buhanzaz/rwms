package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import dev.buhanzaz.rwms.asset.domain.CabinCatalogItem;
import dev.buhanzaz.rwms.asset.domain.CabinCatalogKind;
import dev.buhanzaz.rwms.asset.domain.CabinTypeDimension;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemCharacteristic;
import dev.buhanzaz.rwms.asset.mapper.CabinCatalogItemMapper;
import dev.buhanzaz.rwms.asset.repository.CabinCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.CabinTypeDimensionRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemCharacteristicRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns cabin passport catalog values and the UUID-only relationships between
 * them. Human-readable names are read projections, never command inputs.
 */
@Service
public class CabinCompositionService {
  private static final String NEW_CATEGORY = "Новая";
  private static final List<String> USED_CATEGORIES = List.of("ИТР", "Обычная");

  private final CabinCatalogItemRepository catalog;
  private final CabinTypeDimensionRepository typeDimensions;
  private final RentalItemRepository rentalItems;
  private final RentalItemCharacteristicRepository rentalItemCharacteristics;
  private final CabinCatalogItemMapper mapper;
  private final AssetIdempotencyStore idempotency;
  private final ObjectMapper objectMapper;

  public CabinCompositionService(
      CabinCatalogItemRepository catalog,
      CabinTypeDimensionRepository typeDimensions,
      RentalItemRepository rentalItems,
      RentalItemCharacteristicRepository rentalItemCharacteristics,
      CabinCatalogItemMapper mapper,
      AssetIdempotencyStore idempotency,
      ObjectMapper objectMapper) {
    this.catalog = catalog;
    this.typeDimensions = typeDimensions;
    this.rentalItems = rentalItems;
    this.rentalItemCharacteristics = rentalItemCharacteristics;
    this.mapper = mapper;
    this.idempotency = idempotency;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public CabinSettingsResponse settings() {
    return new CabinSettingsResponse(
        responses(CabinCatalogKind.TYPE),
        responses(CabinCatalogKind.DIMENSION),
        responses(CabinCatalogKind.FINISHING),
        responses(CabinCatalogKind.CATEGORY),
        responses(CabinCatalogKind.CHARACTERISTIC),
        typeDimensionResponses(allTypeIds()));
  }

  @Transactional(readOnly = true)
  public RentalItemCreationOptionsResponse creationOptions() {
    List<CabinCatalogItem> types = active(CabinCatalogKind.TYPE);
    List<CabinCatalogItem> dimensions = active(CabinCatalogKind.DIMENSION);
    List<CabinCatalogItem> finishings = active(CabinCatalogKind.FINISHING);
    List<CabinCatalogItem> categories = active(CabinCatalogKind.CATEGORY);
    List<CabinCatalogItem> characteristics = active(CabinCatalogKind.CHARACTERISTIC);
    Set<UUID> activeTypeIds = types.stream().map(CabinCatalogItem::getId).collect(Collectors.toSet());
    Set<UUID> activeDimensionIds =
        dimensions.stream().map(CabinCatalogItem::getId).collect(Collectors.toSet());
    List<CabinTypeDimensionResponse> links =
        typeDimensionResponses(activeTypeIds).stream()
            .filter(link -> activeDimensionIds.contains(link.dimensionId()))
            .toList();
    return new RentalItemCreationOptionsResponse(
        NEW_CATEGORY,
        USED_CATEGORIES,
        types.stream().map(mapper::toValue).toList(),
        dimensions.stream().map(mapper::toValue).toList(),
        finishings.stream().map(mapper::toValue).toList(),
        categories.stream().map(mapper::toValue).toList(),
        characteristics.stream().map(mapper::toValue).toList(),
        links);
  }

  @Transactional(readOnly = true)
  public List<CabinCatalogValueResponse> activeCharacteristics() {
    return active(CabinCatalogKind.CHARACTERISTIC).stream().map(mapper::toValue).toList();
  }

  @Transactional(readOnly = true)
  public CategorySelection requireCategory(String name) {
    String value =
        name == null || name.isBlank()
            ? NEW_CATEGORY
            : name.trim().replaceAll("[\\p{Z}\\s]+", " ");
    CabinCatalogItem item =
        catalog
            .findByKindAndNameNormalized(CabinCatalogKind.CATEGORY, value.toLowerCase(Locale.ROOT))
            .orElseThrow(() -> new AssetNotFoundException("Cabin category was not found"));
    requireActiveKind(item, CabinCatalogKind.CATEGORY);
    return new CategorySelection(item.getId(), item.getName());
  }

  @Transactional
  public CreateResult<CabinCatalogItemResponse> createCatalogItem(
      UUID subjectId, UUID idempotencyKey, CreateCabinCatalogItemRequest request) {
    String requestHash = hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "cabin-catalog.create", idempotencyKey, requestHash);
    if (replay.isPresent()) {
      return new CreateResult<>(read(replay.get(), CabinCatalogItemResponse.class), true);
    }
    try {
      CabinCatalogItem created = catalog.saveAndFlush(CabinCatalogItem.create(request.kind(), request.name()));
      CabinCatalogItemResponse response = mapper.toResponse(created);
      idempotency.store(
          subjectId, "cabin-catalog.create", idempotencyKey, requestHash, 201, response);
      return new CreateResult<>(response, false);
    } catch (DataIntegrityViolationException exception) {
      throw new AssetConflictException("A cabin setting with this name already exists");
    }
  }

  @Transactional
  public CabinCatalogItemResponse updateCatalogItem(UUID id, UpdateCabinCatalogItemRequest request) {
    CabinCatalogItem item = requireCatalogItem(id);
    assertVersion(item.getVersion(), request.expectedVersion());
    if (!item.change(request.name(), request.active())) {
      return mapper.toResponse(item);
    }
    try {
      return mapper.toResponse(catalog.saveAndFlush(item));
    } catch (DataIntegrityViolationException exception) {
      throw new AssetConflictException("A cabin setting with this name already exists");
    }
  }

  /**
   * Deletes a catalog value only when no cabin or composition relation still refers to it. The
   * database foreign keys enforce the same invariant if a concurrent command wins the race.
   */
  @Transactional
  public void deleteCatalogItem(UUID id, long expectedVersion) {
    CabinCatalogItem item = requireCatalogItem(id);
    assertVersion(item.getVersion(), expectedVersion);
    assertUnused(item);
    catalog.delete(item);
    catalog.flush();
  }

  @Transactional
  public CabinSettingsResponse replaceTypeDimensions(
      UUID typeId, ReplaceCabinTypeDimensionsRequest request) {
    List<UUID> requestedDimensions = orderedDistinct(request.dimensionIds(), "dimensionIds");
    CabinCatalogItem type = requireCatalogItem(typeId);
    requireKind(type, CabinCatalogKind.TYPE);
    assertVersion(type.getVersion(), request.expectedVersion());
    Map<UUID, CabinCatalogItem> dimensions = requireCatalogItems(requestedDimensions);
    requestedDimensions.forEach(id -> requireKind(dimensions.get(id), CabinCatalogKind.DIMENSION));

    List<UUID> current =
        typeDimensions.findAllByCabinTypeIdOrderBySortOrderAscIdAsc(typeId).stream()
            .map(CabinTypeDimension::getDimensionId)
            .toList();
    if (current.equals(requestedDimensions)) {
      return settings();
    }
    if (requestedDimensions.isEmpty() && rentalItems.existsByRentalTypeId(typeId)) {
      throw new AssetConflictException(
          "Нельзя снять все габариты: у типа уже есть существующие бытовки.");
    }

    typeDimensions.deleteAllByCabinTypeId(typeId);
    typeDimensions.flush();
    List<CabinTypeDimension> next = new ArrayList<>();
    for (int index = 0; index < requestedDimensions.size(); index += 1) {
      next.add(CabinTypeDimension.create(typeId, requestedDimensions.get(index), index));
    }
    typeDimensions.saveAll(next);
    type.touchConfiguration();
    catalog.saveAndFlush(type);
    return settings();
  }

  /** Validates the command against active catalog values and one allowed type-dimension pair. */
  @Transactional(readOnly = true)
  public CabinSelection requireSelection(
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      List<UUID> characteristicIds) {
    if (rentalTypeId == null || dimensionId == null || finishingId == null) {
      throw new IllegalArgumentException("Cabin type, dimensions and finishing are required");
    }
    List<UUID> selectedCharacteristics = orderedDistinct(characteristicIds, "characteristicIds");
    Set<UUID> requested = new LinkedHashSet<>();
    requested.add(rentalTypeId);
    requested.add(dimensionId);
    requested.add(finishingId);
    requested.addAll(selectedCharacteristics);
    Map<UUID, CabinCatalogItem> items = requireCatalogItems(requested);
    CabinCatalogItem type = items.get(rentalTypeId);
    CabinCatalogItem dimension = items.get(dimensionId);
    CabinCatalogItem finishing = items.get(finishingId);
    requireActiveKind(type, CabinCatalogKind.TYPE);
    requireActiveKind(dimension, CabinCatalogKind.DIMENSION);
    requireActiveKind(finishing, CabinCatalogKind.FINISHING);
    selectedCharacteristics.forEach(
        id -> requireActiveKind(items.get(id), CabinCatalogKind.CHARACTERISTIC));
    boolean allowed =
        typeDimensions.findAllByCabinTypeIdOrderBySortOrderAscIdAsc(rentalTypeId).stream()
            .anyMatch(link -> link.getDimensionId().equals(dimensionId));
    if (!allowed) {
      throw new IllegalArgumentException("Selected dimensions are not available for this cabin type");
    }
    return new CabinSelection(rentalTypeId, dimensionId, finishingId, selectedCharacteristics);
  }

  /**
   * Resolves the manager client's name-based inventory passport inside the catalogue owner. This
   * is deliberately available only to the inventory boundary: normal cabin commands remain UUID
   * based and do not accept display labels.
   */
  @Transactional(readOnly = true)
  public CabinSelection requireLegacyInventorySelection(
      String rentalType,
      String dimensions,
      String finishing,
      String characteristics) {
    CabinCatalogItem type = requireActiveCatalogItemByName(
        CabinCatalogKind.TYPE, rentalType, "rentalType");
    CabinCatalogItem dimension = requireActiveCatalogItemByName(
        CabinCatalogKind.DIMENSION, dimensions, "dimensions");
    CabinCatalogItem finishingItem = requireActiveCatalogItemByName(
        CabinCatalogKind.FINISHING, finishing, "finishing");
    return requireSelection(
        type.getId(),
        dimension.getId(),
        finishingItem.getId(),
        legacyCharacteristicIds(characteristics));
  }

  /**
   * Resolves a completed-inventory passport using exact active catalogue names. Every raw
   * characteristic value is split on commas because supported manager revisions persisted both
   * comma-delimited strings and arrays containing comma-delimited elements. Empty input is the
   * authoritative empty characteristic set; no catalogue row is ever created here.
   */
  @Transactional(readOnly = true)
  public CabinSelection requireInventoryOutcomeSelection(
      String rentalType,
      String dimensions,
      String finishing,
      List<String> characteristics) {
    CabinCatalogItem type =
        requireActiveCatalogItemByName(CabinCatalogKind.TYPE, rentalType, "rentalType");
    CabinCatalogItem dimension =
        requireActiveCatalogItemByName(CabinCatalogKind.DIMENSION, dimensions, "dimensions");
    CabinCatalogItem finishingItem =
        requireActiveCatalogItemByName(CabinCatalogKind.FINISHING, finishing, "finishing");
    return requireSelection(
        type.getId(),
        dimension.getId(),
        finishingItem.getId(),
        inventoryOutcomeCharacteristicIds(characteristics));
  }

  /** Replaces only the relation rows, preserving each characteristic as an individual UUID. */
  @Transactional
  public boolean replaceRentalItemCharacteristics(
      UUID rentalItemId, List<UUID> characteristicIds) {
    List<UUID> desired = orderedDistinct(characteristicIds, "characteristicIds");
    List<UUID> current =
        rentalItemCharacteristics.findAllByRentalItemIdOrderBySortOrderAscIdAsc(rentalItemId).stream()
            .map(RentalItemCharacteristic::getCharacteristicId)
            .toList();
    if (current.equals(desired)) {
      return false;
    }
    rentalItemCharacteristics.deleteAllByRentalItemId(rentalItemId);
    rentalItemCharacteristics.flush();
    List<RentalItemCharacteristic> next = new ArrayList<>();
    for (int index = 0; index < desired.size(); index += 1) {
      next.add(RentalItemCharacteristic.create(rentalItemId, desired.get(index), index));
    }
    rentalItemCharacteristics.saveAll(next);
    return true;
  }

  /**
   * Appends one active canonical characteristic without replacing the cabin's
   * existing composition. The aggregate owner serializes calls per rental item.
   */
  @Transactional
  public boolean appendRentalItemCharacteristic(
      UUID rentalItemId, UUID characteristicId) {
    CabinCatalogItem characteristic = requireCatalogItem(characteristicId);
    requireActiveKind(characteristic, CabinCatalogKind.CHARACTERISTIC);
    if (rentalItemCharacteristics.existsByRentalItemIdAndCharacteristicId(
        rentalItemId, characteristicId)) {
      return false;
    }
    int nextSortOrder =
        rentalItemCharacteristics
                .findAllByRentalItemIdOrderBySortOrderAscIdAsc(rentalItemId)
                .stream()
                .mapToInt(RentalItemCharacteristic::getSortOrder)
                .max()
                .orElse(-1)
            + 1;
    rentalItemCharacteristics.saveAndFlush(
        RentalItemCharacteristic.create(rentalItemId, characteristicId, nextSortOrder));
    return true;
  }

  @Transactional(readOnly = true)
  public Map<UUID, CabinComposition> compositionsFor(Collection<RentalItem> rentalItems) {
    if (rentalItems == null || rentalItems.isEmpty()) return Map.of();
    List<RentalItem> values = List.copyOf(rentalItems);
    List<UUID> rentalItemIds = values.stream().map(RentalItem::getId).toList();
    Map<UUID, List<RentalItemCharacteristic>> characteristicLinks =
        rentalItemCharacteristics
            .findAllByRentalItemIdInOrderByRentalItemIdAscSortOrderAscIdAsc(rentalItemIds)
            .stream()
            .collect(
                Collectors.groupingBy(
                    RentalItemCharacteristic::getRentalItemId,
                    LinkedHashMap::new,
                    Collectors.toList()));
    Set<UUID> catalogIds = new LinkedHashSet<>();
    for (RentalItem item : values) {
      addIfPresent(catalogIds, item.getRentalTypeId());
      addIfPresent(catalogIds, item.getDimensionId());
      addIfPresent(catalogIds, item.getFinishingId());
      characteristicLinks.getOrDefault(item.getId(), List.of()).forEach(
          link -> catalogIds.add(link.getCharacteristicId()));
    }
    Map<UUID, CabinCatalogItem> catalogItems =
        catalogIds.isEmpty()
            ? Map.of()
            : catalog.findAllByIdIn(catalogIds).stream()
                .collect(Collectors.toMap(CabinCatalogItem::getId, item -> item));
    Map<UUID, CabinComposition> result = new LinkedHashMap<>();
    for (RentalItem item : values) {
      List<CabinCatalogValueResponse> characteristics =
          characteristicLinks.getOrDefault(item.getId(), List.of()).stream()
              .map(link -> value(catalogItems, link.getCharacteristicId()))
              .toList();
      result.put(
          item.getId(),
          new CabinComposition(
              value(catalogItems, item.getRentalTypeId()),
              value(catalogItems, item.getDimensionId()),
              value(catalogItems, item.getFinishingId()),
              characteristics));
    }
    return result;
  }

  /** Resolves display values for one already validated source proposal without a rental-item row. */
  public CabinComposition compositionFor(CabinSelection selection) {
    if (selection == null) {
      throw new IllegalArgumentException("Cabin selection is required");
    }
    Set<UUID> ids = new LinkedHashSet<>();
    ids.add(selection.rentalTypeId());
    ids.add(selection.dimensionId());
    ids.add(selection.finishingId());
    ids.addAll(selection.characteristicIds());
    Map<UUID, CabinCatalogItem> items =
        catalog.findAllByIdIn(ids).stream()
            .collect(Collectors.toMap(CabinCatalogItem::getId, item -> item));
    return new CabinComposition(
        value(items, selection.rentalTypeId()),
        value(items, selection.dimensionId()),
        value(items, selection.finishingId()),
        selection.characteristicIds().stream().map(id -> value(items, id)).toList());
  }

  private List<CabinCatalogItemResponse> responses(CabinCatalogKind kind) {
    return catalog.findAllByKindOrderByNameAscIdAsc(kind).stream().map(mapper::toResponse).toList();
  }

  private List<CabinCatalogItem> active(CabinCatalogKind kind) {
    return catalog.findAllByKindAndActiveTrueOrderByNameAscIdAsc(kind);
  }

  private Set<UUID> allTypeIds() {
    return catalog.findAllByKindOrderByNameAscIdAsc(CabinCatalogKind.TYPE).stream()
        .map(CabinCatalogItem::getId)
        .collect(Collectors.toSet());
  }

  private List<CabinTypeDimensionResponse> typeDimensionResponses(Collection<UUID> typeIds) {
    if (typeIds == null || typeIds.isEmpty()) return List.of();
    return typeDimensions
        .findAllByCabinTypeIdInOrderByCabinTypeIdAscSortOrderAscIdAsc(typeIds)
        .stream()
        .map(mapper::toTypeDimension)
        .sorted(
            Comparator.comparing(CabinTypeDimensionResponse::typeId)
                .thenComparingInt(CabinTypeDimensionResponse::sortOrder)
                .thenComparing(CabinTypeDimensionResponse::dimensionId))
        .toList();
  }

  private CabinCatalogItem requireCatalogItem(UUID id) {
    return catalog
        .findById(id)
        .orElseThrow(() -> new AssetNotFoundException("Cabin setting was not found"));
  }

  private CabinCatalogItem requireActiveCatalogItemByName(
      CabinCatalogKind kind, String value, String field) {
    CabinCatalogItem item = catalog
        .findByKindAndNameNormalized(kind, normalizedLegacyName(value, field))
        .orElseThrow(() -> new AssetNotFoundException("Cabin setting was not found"));
    requireActiveKind(item, kind);
    return item;
  }

  /**
   * The current manager APK serializes selected characteristics as one comma-separated string.
   * If a catalog value itself contains a comma, only an unambiguous spelling is accepted; this
   * prevents one saved value from being silently converted into two different characteristics.
   */
  private List<UUID> legacyCharacteristicIds(String value) {
    if (value == null || value.isBlank()) return List.of();
    List<String> names = splitLegacyCharacteristicNames(value);
    if (names.size() == 1) {
      return List.of(
          requireActiveCatalogItemByName(CabinCatalogKind.CHARACTERISTIC, names.getFirst(), "characteristics")
              .getId());
    }

    Optional<CabinCatalogItem> wholeValue = normalizedLegacyNameIfCatalogValue(value)
        .flatMap(name -> catalog.findByKindAndNameNormalized(CabinCatalogKind.CHARACTERISTIC, name));
    List<CabinCatalogItem> splitItems = new ArrayList<>();
    boolean splitIsActiveAndResolvable = true;
    for (String name : names) {
      Optional<CabinCatalogItem> item = catalog.findByKindAndNameNormalized(
          CabinCatalogKind.CHARACTERISTIC, name);
      if (item.isEmpty() || !item.get().isActive()) {
        splitIsActiveAndResolvable = false;
        break;
      }
      splitItems.add(item.get());
    }
    if (wholeValue.isPresent() && splitIsActiveAndResolvable) {
      throw new IllegalArgumentException("Legacy characteristics value is ambiguous");
    }
    if (wholeValue.isPresent()) {
      requireActiveKind(wholeValue.get(), CabinCatalogKind.CHARACTERISTIC);
      return List.of(wholeValue.get().getId());
    }
    if (!splitIsActiveAndResolvable) {
      // Return the standard not-found/inactive error rather than treating a label as free text.
      return names.stream()
          .map(
              name ->
                  requireActiveCatalogItemByName(
                      CabinCatalogKind.CHARACTERISTIC, name, "characteristics").getId())
          .toList();
    }
    if (splitItems.size() > 100) {
      throw new IllegalArgumentException("characteristics must contain at most 100 values");
    }
    return orderedDistinct(
        splitItems.stream().map(CabinCatalogItem::getId).toList(), "characteristics");
  }

  private List<UUID> inventoryOutcomeCharacteristicIds(List<String> values) {
    if (values == null || values.isEmpty()) return List.of();
    Set<String> names = new LinkedHashSet<>();
    for (String value : values) {
      if (value == null) {
        throw new IllegalArgumentException("characteristics must not contain null");
      }
      for (String part : value.split(",", -1)) {
        if (part.isBlank()) continue;
        names.add(normalizedLegacyName(part, "characteristics"));
      }
    }
    if (names.size() > 100) {
      throw new IllegalArgumentException("characteristics must contain at most 100 values");
    }
    return names.stream()
        .map(
            name ->
                requireActiveCatalogItemByName(
                        CabinCatalogKind.CHARACTERISTIC, name, "characteristics")
                    .getId())
        .toList();
  }

  private static List<String> splitLegacyCharacteristicNames(String value) {
    String[] parts = value.split(",", -1);
    boolean hasSelectedName = false;
    for (String part : parts) {
      if (!part.isBlank()) {
        hasSelectedName = true;
        break;
      }
    }
    if (!hasSelectedName) return List.of();
    List<String> result = new ArrayList<>(parts.length);
    for (String part : parts) {
      result.add(normalizedLegacyName(part, "characteristics"));
    }
    if (result.size() > 100) {
      throw new IllegalArgumentException("characteristics must contain at most 100 values");
    }
    return List.copyOf(result);
  }

  private static Optional<String> normalizedLegacyNameIfCatalogValue(String value) {
    try {
      return Optional.of(normalizedLegacyName(value, "characteristics"));
    } catch (IllegalArgumentException exception) {
      return Optional.empty();
    }
  }

  private static String normalizedLegacyName(String value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    String normalized = value.trim().replaceAll("[\\p{Z}\\s]+", " ");
    if (normalized.isEmpty() || normalized.length() > 255) {
      throw new IllegalArgumentException(field + " must contain 1 to 255 characters");
    }
    return normalized.toLowerCase(Locale.ROOT);
  }

  private Map<UUID, CabinCatalogItem> requireCatalogItems(Collection<UUID> ids) {
    if (ids == null || ids.isEmpty()) return Map.of();
    Map<UUID, CabinCatalogItem> found =
        catalog.findAllByIdIn(ids).stream()
            .collect(Collectors.toMap(CabinCatalogItem::getId, item -> item));
    for (UUID id : ids) {
      if (id == null || !found.containsKey(id)) {
        throw new AssetNotFoundException("Cabin setting was not found");
      }
    }
    return found;
  }

  private static void requireKind(CabinCatalogItem item, CabinCatalogKind kind) {
    if (item == null || item.getKind() != kind) {
      throw new IllegalArgumentException("Cabin setting kind does not match its use");
    }
  }

  private void assertUnused(CabinCatalogItem item) {
    UUID id = item.getId();
    switch (item.getKind()) {
      case TYPE -> {
        if (typeDimensions.existsByCabinTypeId(id)) {
          throw referenced(item, "сначала отвяжите габариты от типа бытовки");
        }
        if (rentalItems.existsByRentalTypeId(id)) {
          throw referenced(item, "тип уже выбран у существующих бытовок");
        }
      }
      case DIMENSION -> {
        if (typeDimensions.existsByDimensionId(id)) {
          throw referenced(item, "сначала отвяжите габарит от типов бытовок");
        }
        if (rentalItems.existsByDimensionId(id)) {
          throw referenced(item, "габарит уже выбран у существующих бытовок");
        }
      }
      case FINISHING -> {
        if (rentalItems.existsByFinishingId(id)) {
          throw referenced(item, "отделка уже выбрана у существующих бытовок");
        }
      }
      case CATEGORY -> {
        if (rentalItems.existsByCategoryId(id)) {
          throw referenced(item, "категория уже выбрана у существующих бытовок");
        }
      }
      case CHARACTERISTIC -> {
        if (rentalItemCharacteristics.existsByCharacteristicId(id)) {
          throw referenced(item, "характеристика уже выбрана у существующих бытовок");
        }
      }
    }
  }

  private static AssetConflictException referenced(CabinCatalogItem item, String reason) {
    return new AssetConflictException("Нельзя удалить «" + item.getName() + "»: " + reason + ".");
  }

  private static void requireActiveKind(CabinCatalogItem item, CabinCatalogKind kind) {
    requireKind(item, kind);
    if (!item.isActive()) {
      throw new AssetConflictException("Cabin setting is inactive");
    }
  }

  private static List<UUID> orderedDistinct(List<UUID> values, String field) {
    if (values == null) return List.of();
    List<UUID> result = new ArrayList<>();
    Set<UUID> seen = new LinkedHashSet<>();
    for (UUID value : values) {
      if (value == null) throw new IllegalArgumentException(field + " must not contain null");
      if (!seen.add(value)) throw new IllegalArgumentException(field + " must not contain duplicates");
      result.add(value);
    }
    return List.copyOf(result);
  }

  private static void addIfPresent(Set<UUID> target, UUID value) {
    if (value != null) target.add(value);
  }

  private CabinCatalogValueResponse value(
      Map<UUID, CabinCatalogItem> catalogItems, UUID id) {
    if (id == null) return null;
    CabinCatalogItem item = catalogItems.get(id);
    if (item == null) {
      throw new IllegalStateException("Cabin catalog reference is missing");
    }
    return mapper.toValue(item);
  }

  private static void assertVersion(long actual, Long expected) {
    if (expected == null || expected < 0) {
      throw new IllegalArgumentException("expectedVersion is required");
    }
    if (actual != expected) {
      throw new AssetConflictException("Cabin setting changed concurrently");
    }
  }

  private String hash(Object value) {
    try {
      return AssetChecksum.sha256(objectMapper.writeValueAsBytes(value));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Cabin setting command cannot be fingerprinted", exception);
    }
  }

  private <T> T read(JsonNode node, Class<T> type) {
    try {
      return objectMapper.readerFor(type).readValue(node);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored idempotent cabin setting response is corrupt", exception);
    }
  }

  public record CabinSelection(
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      List<UUID> characteristicIds) {}

  public record CategorySelection(UUID id, String name) {}

  public record CabinComposition(
      CabinCatalogValueResponse rentalType,
      CabinCatalogValueResponse dimensions,
      CabinCatalogValueResponse finishing,
      List<CabinCatalogValueResponse> characteristics) {}

  public record CreateResult<T>(T response, boolean replayed) {}
}
