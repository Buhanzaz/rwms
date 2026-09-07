package dev.buhanzaz.rwms.logistics.customer.service;

import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinPage;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinPhoto;
import static dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinResponse;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerRentalSessionRepository;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.CabinFacetWarehouse;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.CabinFacetsResponse;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.CabinTypeDimensionRelation;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.pricing.api.CabinRentalPricesResponse;
import dev.buhanzaz.rwms.logistics.pricing.service.RentalPricingService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer cabin catalogue and scoped media reads. Anonymous browsing exposes only the current
 * public catalogue; signed-in reads additionally retain the customer's own inquiry holds.
 * Asset-service remains authoritative for availability and media-service for photo bytes.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, propagation = Propagation.NEVER)
public class CustomerCabinCatalogService {
  private static final String PUBLIC_WAREHOUSE_BASE =
      "/api/logistics/public/v1/catalog/warehouses/";
  private final CustomerRentalSessionRepository sessions;
  private final LogisticsDependencyGateway dependencies;
  private final CustomerDeliveryEstimateService deliveryEstimate;
  private final RentalPricingService pricing;
  private final CustomerWarehouseService warehouses;

  /** Returns one page of currently bookable cabin cards with protected photo URLs. */
  public CustomerCabinPage page(
      CustomerIdentity identity,
      UUID inquiryId,
      String query,
      String cabinType,
      String finish,
      String dimensions,
      String category,
      Boolean linoleum,
      List<String> characteristics,
      int page,
      int size) {
    CustomerRentalSession session = required(identity, inquiryId);
    return pageForWarehouse(
        session.getWarehouseId(),
        inquiryId,
        query,
        cabinType,
        finish,
        dimensions,
        category,
        linoleum,
        characteristics,
        page,
        size);
  }

  /**
   * Returns the live guest catalogue without creating a profile, inquiry or hold. Warehouse
   * visibility is verified before any asset, pricing or media read; arbitrary passport facts are
   * never included in the anonymous response.
   */
  public CustomerCabinPage publicPage(
      UUID warehouseId,
      String query,
      String cabinType,
      String finish,
      String dimensions,
      String category,
      Boolean linoleum,
      List<String> characteristics,
      int page,
      int size) {
    warehouses.required(warehouseId);
    return pageForWarehouse(
        warehouseId,
        null,
        query,
        cabinType,
        finish,
        dimensions,
        category,
        linoleum,
        characteristics,
        page,
        size);
  }

  /** Returns asset-owned facets for one visible warehouse with all live holds excluded. */
  public CabinFacetsResponse publicFacets(UUID warehouseId) {
    var warehouse = warehouses.required(warehouseId);
    try {
      var facets = dependencies.readAvailableCabinFacets(warehouseId, null);
      return new CabinFacetsResponse(
          List.of(
              new CabinFacetWarehouse(
                  warehouseId,
                  warehouse.name(),
                  warehouse.city(),
                  facets.cabinTypes(),
                  facets.finishes(),
                  facets.dimensions(),
                  facets.categories(),
                  facets.characteristics(),
                  facets.typeDimensions().stream()
                      .map(
                          relation ->
                              new CabinTypeDimensionRelation(
                                  relation.cabinType(), relation.dimensions()))
                      .toList())));
    } catch (LogisticsDependencyException exception) {
      throw dependencyProblem(exception, "CUSTOMER_CABIN_CATALOG_UNAVAILABLE");
    }
  }

  private CustomerCabinPage pageForWarehouse(
      UUID warehouseId,
      UUID inquiryId,
      String query,
      String cabinType,
      String finish,
      String dimensions,
      String category,
      Boolean linoleum,
      List<String> characteristics,
      int page,
      int size) {
    if (page < 0 || size < 1 || size > 100) {
      throw new IllegalArgumentException("Customer cabin page is invalid");
    }
    try {
      LogisticsDependencyGateway.CabinCatalogPage result =
          dependencies.readCustomerCabinCatalog(
              warehouseId,
              inquiryId,
              normalize(query),
              normalize(cabinType),
              normalize(finish),
              normalize(dimensions),
              normalize(category),
              linoleum,
              characteristics == null ? List.of() : List.copyOf(characteristics),
              page,
              size);
      validate(result, warehouseId, page, size);
      var prices = prices(warehouseId, result.content());
      Map<UUID, List<LogisticsDependencyGateway.CabinMediaPhoto>> photos =
          media(
              warehouseId,
              result.content().stream()
                  .map(LogisticsDependencyGateway.AvailableCabin::id)
                  .toList());
      return new CustomerCabinPage(
          result.content().stream()
              .map(
                  cabin ->
                      map(
                          inquiryId,
                          cabin,
                          photos.getOrDefault(cabin.id(), List.of()),
                          prices.get(cabin.id())))
              .toList(),
          result.page(),
          result.size(),
          result.totalElements(),
          result.totalPages(),
          deliveryEstimate.estimatedDates(warehouseId));
    } catch (LogisticsDependencyException exception) {
      throw dependencyProblem(exception, "CUSTOMER_CABIN_CATALOG_UNAVAILABLE");
    }
  }

  /** Maps already-authorized held cabin snapshots for cart and selection responses. */
  public List<CustomerCabinResponse> heldCabins(
      CustomerIdentity identity,
      UUID inquiryId,
      List<LogisticsDependencyGateway.AvailableCabin> cabins) {
    CustomerRentalSession session = required(identity, inquiryId);
    if (cabins.stream().anyMatch(cabin -> !session.getWarehouseId().equals(cabin.warehouseId()))) {
      throw new OrderProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "CUSTOMER_CABIN_SNAPSHOT_INVALID",
          "Сервис имущества вернул бытовку другого склада");
    }
    Map<UUID, List<LogisticsDependencyGateway.CabinMediaPhoto>> photos =
        media(
            session.getWarehouseId(),
            cabins.stream().map(LogisticsDependencyGateway.AvailableCabin::id).toList());
    var prices = prices(session.getWarehouseId(), cabins);
    return cabins.stream()
        .map(
            cabin ->
                map(
                    inquiryId,
                    cabin,
                    photos.getOrDefault(cabin.id(), List.of()),
                    prices.get(cabin.id())))
        .toList();
  }

  /** Resolves one current photo only after verifying customer ownership and media generation. */
  public LogisticsDependencyGateway.MediaContent media(
      CustomerIdentity identity,
      UUID inquiryId,
      UUID cabinId,
      UUID mediaId,
      long generation,
      String variant) {
    CustomerRentalSession session = required(identity, inquiryId);
    return mediaForCabin(session.getWarehouseId(), cabinId, mediaId, generation, variant);
  }

  /**
   * Re-proves visible warehouse, current cabin availability and gallery membership on every guest
   * image read. No public request can address another cabin's image or a private original.
   */
  public LogisticsDependencyGateway.MediaContent publicMedia(
      UUID warehouseId, UUID cabinId, UUID mediaId, long generation, String variant) {
    warehouses.required(warehouseId);
    if (generation < 1 || !Set.of("SMALL", "LARGE").contains(variant)) {
      throw notFound("Фотография не найдена");
    }
    try {
      var availability = dependencies.readCabinAvailability(warehouseId, List.of(cabinId));
      if (availability == null
          || !warehouseId.equals(availability.warehouseId())
          || availability.items() == null
          || availability.items().size() != 1
          || !cabinId.equals(availability.items().getFirst().rentalItemId())
          || !availability.items().getFirst().available()) {
        throw notFound("Фотография не найдена");
      }
      return mediaForCabin(warehouseId, cabinId, mediaId, generation, variant);
    } catch (LogisticsDependencyException exception) {
      if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
        throw notFound("Фотография не найдена");
      }
      throw dependencyProblem(exception, "CUSTOMER_CABIN_MEDIA_UNAVAILABLE");
    }
  }

  private LogisticsDependencyGateway.MediaContent mediaForCabin(
      UUID warehouseId, UUID cabinId, UUID mediaId, long generation, String variant) {
    if (!Set.of("SMALL", "LARGE").contains(variant)) throw notFound("Фотография не найдена");
    try {
      List<LogisticsDependencyGateway.AvailableCabin> cabins =
          dependencies.readCabinSnapshots(warehouseId, List.of(cabinId));
      if (cabins.size() != 1
          || !cabinId.equals(cabins.getFirst().id())
          || !warehouseId.equals(cabins.getFirst().warehouseId())) {
        throw notFound("Фотография не найдена");
      }
      boolean exists =
          dependencies.readCabinMediaSnapshots(warehouseId, List.of(cabinId)).stream()
              .filter(snapshot -> cabinId.equals(snapshot.cabinId()))
              .flatMap(snapshot -> snapshot.photos().stream())
              .anyMatch(
                  photo ->
                      mediaId.equals(photo.mediaId())
                          && generation == photo.generation()
                          && photo.availableVariants().contains(variant));
      if (!exists) throw notFound("Фотография не найдена");
      return dependencies.readCabinPresentationMedia(
          warehouseId, cabinId, mediaId, generation, variant);
    } catch (LogisticsDependencyException exception) {
      if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
        throw notFound("Фотография не найдена");
      }
      throw dependencyProblem(exception, "CUSTOMER_CABIN_MEDIA_UNAVAILABLE");
    }
  }

  private Map<UUID, List<LogisticsDependencyGateway.CabinMediaPhoto>> media(
      UUID warehouseId, List<UUID> cabinIds) {
    if (cabinIds.isEmpty()) return Map.of();
    try {
      return dependencies.readCabinMediaSnapshots(warehouseId, cabinIds).stream()
          .collect(
              Collectors.toMap(
                  LogisticsDependencyGateway.CabinMediaSnapshot::cabinId,
                  LogisticsDependencyGateway.CabinMediaSnapshot::photos,
                  (left, right) -> left,
                  LinkedHashMap::new));
    } catch (LogisticsDependencyException exception) {
      throw dependencyProblem(exception, "CUSTOMER_CABIN_MEDIA_UNAVAILABLE");
    }
  }

  private CustomerRentalSession required(CustomerIdentity identity, UUID inquiryId) {
    return sessions
        .findByInquiryIdAndCustomerSubjectId(inquiryId, identity.subjectId())
        .orElseThrow(() -> notFound("Корзина не найдена"));
  }

  private Map<UUID, CurrentPrice> prices(
      UUID warehouseId, List<LogisticsDependencyGateway.AvailableCabin> cabins) {
    if (cabins.isEmpty()) return Map.of();
    CabinRentalPricesResponse quote =
        pricing.prices(
            warehouseId,
            cabins.stream().map(LogisticsDependencyGateway.AvailableCabin::id).toList());
    return quote.cabins().stream()
        .collect(
            Collectors.toMap(
                CabinRentalPricesResponse.Price::rentalItemId,
                price -> new CurrentPrice(quote.pricingVersion(), price.monthlyPriceRubles())));
  }

  /** One monthly tariff from the same authoritative price-table snapshot as the response batch. */
  private record CurrentPrice(long version, long monthlyPriceRubles) {}

  private static CustomerCabinResponse map(
      UUID inquiryId,
      LogisticsDependencyGateway.AvailableCabin cabin,
      List<LogisticsDependencyGateway.CabinMediaPhoto> photos,
      CurrentPrice price) {
    String base =
        (inquiryId == null
                ? PUBLIC_WAREHOUSE_BASE + cabin.warehouseId()
                : "/api/logistics/customer/v1/inquiries/" + inquiryId)
            + "/cabins/"
            + cabin.id()
            + "/photos/";
    return new CustomerCabinResponse(
        cabin.id(),
        cabin.version(),
        cabin.number(),
        price.version(),
        price.monthlyPriceRubles(),
        cabin.rentalType(),
        cabin.finishing(),
        cabin.dimensions(),
        cabin.category(),
        cabin.linoleum(),
        characteristics(cabin.characteristics()),
        inquiryId == null ? Map.of() : customerFacts(cabin.passport()),
        photos.stream()
            .sorted(
                java.util.Comparator.comparingInt(
                    LogisticsDependencyGateway.CabinMediaPhoto::sortOrder))
            .map(
                photo ->
                    new CustomerCabinPhoto(
                        photo.mediaId(),
                        photo.generation(),
                        base
                            + photo.mediaId()
                            + "?generation="
                            + photo.generation()
                            + "&variant=SMALL",
                        base
                            + photo.mediaId()
                            + "?generation="
                            + photo.generation()
                            + "&variant=LARGE"))
            .toList());
  }

  private static List<String> characteristics(String value) {
    if (value == null || value.isBlank()) return List.of();
    List<String> result = new ArrayList<>();
    for (String part : value.split("[,;\\n]")) {
      String normalized = part.trim();
      if (!normalized.isEmpty() && !result.contains(normalized)) result.add(normalized);
    }
    return List.copyOf(result);
  }

  private static Map<String, Object> customerFacts(Map<String, Object> source) {
    if (source == null || source.isEmpty()) return Map.of();
    Map<String, Object> facts = new LinkedHashMap<>();
    source.forEach(
        (key, value) -> {
          if (key != null && value != null) facts.put(key, value);
        });
    return facts.isEmpty() ? Map.of() : Collections.unmodifiableMap(facts);
  }

  private static void validate(
      LogisticsDependencyGateway.CabinCatalogPage result,
      UUID warehouseId,
      int requestedPage,
      int requestedSize) {
    if (result == null
        || !warehouseId.equals(result.warehouseId())
        || result.content() == null
        || result.page() != requestedPage
        || result.size() != requestedSize
        || result.totalElements() < 0
        || result.totalPages() < 0
        || result.content().size() > requestedSize
        || result.content().stream().anyMatch(cabin -> !warehouseId.equals(cabin.warehouseId()))
        || result.content().stream()
                .map(LogisticsDependencyGateway.AvailableCabin::id)
                .distinct()
                .count()
            != result.content().size()) {
      throw new OrderProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "CUSTOMER_CABIN_CATALOG_INVALID",
          "Каталог бытовок вернул несогласованные данные");
    }
  }

  private static String normalize(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static OrderProblemException dependencyProblem(
      LogisticsDependencyException exception, String code) {
    HttpStatus status =
        exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION
            ? HttpStatus.CONFLICT
            : HttpStatus.SERVICE_UNAVAILABLE;
    return new OrderProblemException(status, code, "Каталог бытовок временно недоступен");
  }

  private static OrderProblemException notFound(String message) {
    return new OrderProblemException(HttpStatus.NOT_FOUND, "CUSTOMER_RESOURCE_NOT_FOUND", message);
  }
}
