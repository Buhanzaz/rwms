package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.api.PublicCustomerCatalogController;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerRentalSessionRepository;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.pricing.api.CabinRentalPricesResponse;
import dev.buhanzaz.rwms.logistics.pricing.service.RentalPricingService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

/** Proves anonymous catalogue isolation and live image membership without creating customer state. */
class PublicCustomerCatalogServiceTest {
  private static final UUID WAREHOUSE = UUID.randomUUID();
  private static final UUID CABIN = UUID.randomUUID();
  private static final UUID MEDIA = UUID.randomUUID();
  private static final UUID OTHER = UUID.randomUUID();
  private final CustomerRentalSessionRepository sessions =
      mock(CustomerRentalSessionRepository.class);
  private final LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
  private final CustomerDeliveryEstimateService delivery =
      mock(CustomerDeliveryEstimateService.class);
  private final RentalPricingService pricing = mock(RentalPricingService.class);
  private final CustomerWarehouseService warehouses = mock(CustomerWarehouseService.class);
  private final CustomerCabinCatalogService service =
      new CustomerCabinCatalogService(sessions, dependencies, delivery, pricing, warehouses);

  @BeforeEach
  void visibleWarehouse() {
    when(warehouses.required(WAREHOUSE))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseIdentity(
                WAREHOUSE, 1, true, "Склад", "Санкт-Петербург", "Europe/Moscow"));
  }

  @Test
  void publicPageUsesNullHoldScopeAndRealPricesWithoutExposingPassportOrCreatingAnInquiry() {
    when(dependencies.readCustomerCabinCatalog(
            WAREHOUSE, null, null, "БК-1", null, null, null, null, List.of("Электрика"), 0, 20))
        .thenReturn(
            new LogisticsDependencyGateway.CabinCatalogPage(
                WAREHOUSE, List.of(cabin(WAREHOUSE, CABIN)), 0, 20, 1, 1));
    when(pricing.prices(WAREHOUSE, List.of(CABIN)))
        .thenReturn(
            new CabinRentalPricesResponse(
                WAREHOUSE,
                6,
                List.of(
                    new CabinRentalPricesResponse.Price(
                        CABIN, 4, UUID.randomUUID(), UUID.randomUUID(), 18_000))));
    when(dependencies.readCabinMediaSnapshots(WAREHOUSE, List.of(CABIN)))
        .thenReturn(List.of(photo(CABIN, MEDIA, 2, List.of("SMALL", "LARGE"))));
    when(delivery.estimatedDates(WAREHOUSE)).thenReturn(List.of());

    var page =
        service.publicPage(
            WAREHOUSE, null, " БК-1 ", null, null, null, null, List.of("Электрика"), 0, 20);

    assertThat(page.content())
        .singleElement()
        .satisfies(
            cabin -> {
              assertThat(cabin.type()).isEqualTo("БК-1");
              assertThat(cabin.monthlyPriceRubles()).isEqualTo(18_000);
              assertThat(cabin.pricingVersion()).isEqualTo(6);
              assertThat(cabin.facts()).isEmpty();
              assertThat(cabin.photos())
                  .singleElement()
                  .satisfies(
                      photo -> {
                        String path =
                            "/api/logistics/public/v1/catalog/warehouses/"
                                + WAREHOUSE
                                + "/cabins/"
                                + CABIN
                                + "/photos/"
                                + MEDIA
                                + "?generation=2&variant=";
                        assertThat(photo.thumbnailUrl()).isEqualTo(path + "SMALL");
                        assertThat(photo.url()).isEqualTo(path + "LARGE");
                      });
            });
    verify(dependencies)
        .readCustomerCabinCatalog(
            WAREHOUSE, null, null, "БК-1", null, null, null, null, List.of("Электрика"), 0, 20);
    verifyNoInteractions(sessions);
  }

  @Test
  void unavailableWarehouseStopsBeforeAssetPricingOrSessionReads() {
    when(warehouses.required(WAREHOUSE))
        .thenThrow(
            new OrderProblemException(
                HttpStatus.NOT_FOUND, "CUSTOMER_WAREHOUSE_NOT_FOUND", "Недоступен"));

    assertNotFound(() -> publicPage());

    verifyNoInteractions(dependencies, pricing, delivery, sessions);
  }

  @Test
  void rejectsPageContainingAnotherWarehousesCabinBeforeReadingPricesOrPhotos() {
    when(dependencies.readCustomerCabinCatalog(
            WAREHOUSE, null, null, null, null, null, null, null, List.of(), 0, 20))
        .thenReturn(
            new LogisticsDependencyGateway.CabinCatalogPage(
                WAREHOUSE, List.of(cabin(OTHER, CABIN)), 0, 20, 1, 1));

    assertThatThrownBy(this::publicPage)
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

    verifyNoInteractions(pricing, delivery, sessions);
    verify(dependencies, never()).readCabinMediaSnapshots(any(), any());
  }

  @Test
  void facetsUseOnlyTheSelectedVisibleWarehouseWithoutAnInquiry() {
    when(dependencies.readAvailableCabinFacets(WAREHOUSE, null, true))
        .thenReturn(
            new LogisticsDependencyGateway.CabinFacets(
                WAREHOUSE,
                List.of("БК-1"),
                List.of("ДВП"),
                List.of("6x2.4"),
                List.of("Новая"),
                List.of("Электрика"),
                List.of(
                    new LogisticsDependencyGateway.CabinTypeDimensionRelation(
                        "БК-1", List.of("6x2.4")))));

    var response = service.publicFacets(WAREHOUSE);

    assertThat(response.warehouses())
        .singleElement()
        .satisfies(
            warehouse -> {
              assertThat(warehouse.warehouseId()).isEqualTo(WAREHOUSE);
              assertThat(warehouse.cabinTypes()).containsExactly("БК-1");
              assertThat(warehouse.typeDimensions())
                  .singleElement()
                  .satisfies(
                      relation -> assertThat(relation.dimensions()).containsExactly("6x2.4"));
            });
    verify(dependencies).readAvailableCabinFacets(WAREHOUSE, null, true);
    verifyNoInteractions(sessions);
  }

  @Test
  void currentGuestPhotoStreamsThroughTheOwnerWithNoStoreAndNosniff() {
    prepareMedia();
    var bytes = new byte[] {1, 2, 3};
    when(dependencies.readCabinPresentationMedia(WAREHOUSE, CABIN, MEDIA, 2, "SMALL"))
        .thenReturn(new LogisticsDependencyGateway.MediaContent(bytes, "image/webp"));

    var response =
        new PublicCustomerCatalogController(warehouses, service)
            .photo(WAREHOUSE, CABIN, MEDIA, 2, "SMALL");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo(bytes);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/webp");
    verify(warehouses).required(WAREHOUSE);
    verifyNoInteractions(sessions);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ORDER_RESERVED",
        "PRESENTATION_HELD",
        "OPERATION_LEASED",
        "STATUS",
        "WAREHOUSE_MISMATCH",
        "NOT_FOUND"
      })
  void rejectsPhotosForEveryUnavailableCabinReason(String reason) {
    when(dependencies.readCabinAvailability(WAREHOUSE, List.of(CABIN)))
        .thenReturn(
            new LogisticsDependencyGateway.CabinAvailability(
                WAREHOUSE,
                List.of(
                    new LogisticsDependencyGateway.CabinAvailabilityItem(CABIN, false, reason))));

    assertNotFound(() -> service.publicMedia(WAREHOUSE, CABIN, MEDIA, 2, "SMALL"));

    verify(dependencies, never()).readCabinMediaSnapshots(any(), any());
    verify(dependencies, never()).readCabinPresentationMedia(any(), any(), any(), anyLong(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"media", "generation", "variant", "cabin", "warehouse"})
  void rejectsAnyPhotoMembershipMismatchBeforeLoadingBytes(String mismatch) {
    prepareMedia();
    switch (mismatch) {
      case "media" ->
          when(dependencies.readCabinMediaSnapshots(WAREHOUSE, List.of(CABIN)))
              .thenReturn(List.of(photo(CABIN, OTHER, 2, List.of("SMALL", "LARGE"))));
      case "generation" ->
          when(dependencies.readCabinMediaSnapshots(WAREHOUSE, List.of(CABIN)))
              .thenReturn(List.of(photo(CABIN, MEDIA, 3, List.of("SMALL", "LARGE"))));
      case "variant" ->
          when(dependencies.readCabinMediaSnapshots(WAREHOUSE, List.of(CABIN)))
              .thenReturn(List.of(photo(CABIN, MEDIA, 2, List.of("LARGE"))));
      case "cabin" ->
          when(dependencies.readCabinMediaSnapshots(WAREHOUSE, List.of(CABIN)))
              .thenReturn(List.of(photo(OTHER, MEDIA, 2, List.of("SMALL", "LARGE"))));
      case "warehouse" ->
          when(dependencies.readCabinSnapshots(WAREHOUSE, List.of(CABIN)))
              .thenReturn(List.of(cabin(OTHER, CABIN)));
      default -> throw new IllegalArgumentException(mismatch);
    }

    assertNotFound(() -> service.publicMedia(WAREHOUSE, CABIN, MEDIA, 2, "SMALL"));

    verify(dependencies, never()).readCabinPresentationMedia(any(), any(), any(), anyLong(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ORIGINAL", "MEDIUM", "original", "../LARGE"})
  void rejectsNonPublicVariantsWithoutContactingAssetOrMedia(String variant) {
    assertNotFound(() -> service.publicMedia(WAREHOUSE, CABIN, MEDIA, 2, variant));

    verifyNoInteractions(dependencies, sessions);
  }

  @Test
  void rechecksAvailabilityAfterAFormerlyPublicCabinIsHeld() {
    prepareMedia();
    when(dependencies.readCabinAvailability(WAREHOUSE, List.of(CABIN)))
        .thenReturn(available(true), available(false));
    when(dependencies.readCabinPresentationMedia(WAREHOUSE, CABIN, MEDIA, 2, "SMALL"))
        .thenReturn(new LogisticsDependencyGateway.MediaContent(new byte[] {1}, "image/webp"));

    service.publicMedia(WAREHOUSE, CABIN, MEDIA, 2, "SMALL");
    assertNotFound(() -> service.publicMedia(WAREHOUSE, CABIN, MEDIA, 2, "SMALL"));

    verify(warehouses, times(2)).required(WAREHOUSE);
    verify(dependencies, times(2)).readCabinAvailability(WAREHOUSE, List.of(CABIN));
    verify(dependencies).readCabinPresentationMedia(WAREHOUSE, CABIN, MEDIA, 2, "SMALL");
  }

  @Test
  void reportsDependencyFailureRatherThanReturningAnEmptyCatalog() {
    when(dependencies.readCustomerCabinCatalog(
            WAREHOUSE, null, null, null, null, null, null, null, List.of(), 0, 20))
        .thenThrow(
            new LogisticsDependencyException(
                LogisticsDependencyException.FailureKind.TRANSIENT, "Unavailable"));

    assertThatThrownBy(this::publicPage)
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    verifyNoInteractions(sessions);
  }

  @Test
  void signedInPhotoStillRequiresTheExactInquiryOwner() {
    when(sessions.findByInquiryIdAndCustomerSubjectId(OTHER, OTHER)).thenReturn(Optional.empty());

    assertNotFound(
        () ->
            service.media(
                new CustomerIdentity(OTHER, "customer"), OTHER, CABIN, MEDIA, 2, "SMALL"));

    verifyNoInteractions(dependencies, warehouses);
  }

  private void publicPage() {
    service.publicPage(WAREHOUSE, null, null, null, null, null, null, List.of(), 0, 20);
  }

  private void prepareMedia() {
    when(dependencies.readCabinAvailability(WAREHOUSE, List.of(CABIN))).thenReturn(available(true));
    when(dependencies.readCabinSnapshots(WAREHOUSE, List.of(CABIN)))
        .thenReturn(List.of(cabin(WAREHOUSE, CABIN)));
    when(dependencies.readCabinMediaSnapshots(WAREHOUSE, List.of(CABIN)))
        .thenReturn(List.of(photo(CABIN, MEDIA, 2, List.of("SMALL", "LARGE"))));
  }

  private static LogisticsDependencyGateway.CabinAvailability available(boolean available) {
    return new LogisticsDependencyGateway.CabinAvailability(
        WAREHOUSE,
        List.of(
            new LogisticsDependencyGateway.CabinAvailabilityItem(
                CABIN, available, available ? "AVAILABLE" : "PRESENTATION_HELD")));
  }

  private static LogisticsDependencyGateway.CabinMediaSnapshot photo(
      UUID cabinId, UUID mediaId, long generation, List<String> variants) {
    return new LogisticsDependencyGateway.CabinMediaSnapshot(
        cabinId,
        1,
        List.of(new LogisticsDependencyGateway.CabinMediaPhoto(mediaId, generation, 0, variants)));
  }

  private static LogisticsDependencyGateway.AvailableCabin cabin(UUID warehouseId, UUID cabinId) {
    return new LogisticsDependencyGateway.AvailableCabin(
        cabinId,
        4,
        warehouseId,
        "FREE",
        "СПБ-001",
        "БК-1",
        "6x2.4",
        "ДВП",
        "Новая",
        "Электрика",
        true,
        Map.of("privateNote", "Not public"),
        List.of(),
        OffsetDateTime.now());
  }

  private static void assertNotFound(Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.status()).isEqualTo(HttpStatus.NOT_FOUND));
  }
}
