package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerRentalSessionRepository;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.pricing.service.RentalPricingService;
import dev.buhanzaz.rwms.logistics.pricing.service.RentalPricingSnapshot;
import dev.buhanzaz.rwms.logistics.pricing.service.RentalPricingStore;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Exercises customer ownership and real tariff resolution without manufacturing local prices. */
class CustomerCabinPricingServiceTest {
  private static final UUID SUBJECT = UUID.randomUUID();
  private static final UUID INQUIRY = UUID.randomUUID();
  private static final UUID WAREHOUSE = UUID.randomUUID();
  private static final UUID CABIN = UUID.randomUUID();
  private static final UUID SECOND_CABIN = UUID.randomUUID();
  private static final UUID TYPE = UUID.randomUUID();
  private static final UUID CATEGORY = UUID.randomUUID();
  private final CustomerRentalSessionRepository sessions =
      mock(CustomerRentalSessionRepository.class);
  private final LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
  private final CustomerDeliveryEstimateService delivery =
      mock(CustomerDeliveryEstimateService.class);
  private final RentalPricingStore priceStore = mock(RentalPricingStore.class);
  private final CustomerCabinCatalogService service =
      new CustomerCabinCatalogService(
          sessions, dependencies, delivery, new RentalPricingService(dependencies, priceStore));

  @BeforeEach
  void prepare() {
    var session = mock(CustomerRentalSession.class);
    when(session.getWarehouseId()).thenReturn(WAREHOUSE);
    when(sessions.findByInquiryIdAndCustomerSubjectId(INQUIRY, SUBJECT))
        .thenReturn(Optional.of(session));
    when(priceStore.read())
        .thenReturn(
            new RentalPricingSnapshot(
                5,
                List.of(new RentalPricingSnapshot.Rate(TYPE, CATEGORY, Long.MAX_VALUE)),
                SUBJECT,
                OffsetDateTime.now()));
    when(dependencies.readCabinPricingReferences(eq(WAREHOUSE), anyList()))
        .thenAnswer(
            invocation -> {
              List<UUID> ids = invocation.getArgument(1);
              return new LogisticsDependencyGateway.CabinPricingReferences(
                  WAREHOUSE,
                  ids.stream()
                      .map(
                          id ->
                              new LogisticsDependencyGateway.CabinPricingReference(
                                  id, 4, TYPE, CATEGORY))
                      .toList());
            });
  }

  @Test
  void freeCatalogUsesExactOwnerPriceAndSerializesItAsAString() {
    when(dependencies.readCustomerCabinCatalog(
            WAREHOUSE, INQUIRY, null, null, null, null, null, null, List.of(), 0, 20))
        .thenReturn(
            new LogisticsDependencyGateway.CabinCatalogPage(
                WAREHOUSE, List.of(cabin(CABIN)), 0, 20, 1, 1));
    var result =
        service.page(
            new CustomerIdentity(SUBJECT, "customer"),
            INQUIRY,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            0,
            20);
    var value = result.content().getFirst();
    assertThat(value.pricingVersion()).isEqualTo(5);
    assertThat(value.monthlyPriceRubles()).isEqualTo(Long.MAX_VALUE);
    assertThat(new ObjectMapper().writeValueAsString(value))
        .contains("\"monthlyPriceRubles\":\"9223372036854775807\"");
  }

  @Test
  void heldCabinsWithSameClassificationReceiveTheSamePriceAndFreshChanges() {
    var identity = new CustomerIdentity(SUBJECT, "customer");
    var held = service.heldCabins(identity, INQUIRY, List.of(cabin(CABIN), cabin(SECOND_CABIN)));
    assertThat(held)
        .allSatisfy(
            value -> {
              assertThat(value.monthlyPriceRubles()).isEqualTo(Long.MAX_VALUE);
              assertThat(value.pricingVersion()).isEqualTo(5);
            });
    when(priceStore.read())
        .thenReturn(new RentalPricingSnapshot(6, List.of(), SUBJECT, OffsetDateTime.now()));
    assertThat(
            service
                .heldCabins(identity, INQUIRY, List.of(cabin(CABIN)))
                .getFirst()
                .monthlyPriceRubles())
        .isZero();
  }

  @Test
  void doesNotReadCabinOrPricingOwnersForAnotherCustomer() {
    assertThatThrownBy(
            () ->
                service.heldCabins(
                    new CustomerIdentity(UUID.randomUUID(), "other"),
                    INQUIRY,
                    List.of(cabin(CABIN))))
        .isInstanceOf(OrderProblemException.class);
    verifyNoInteractions(dependencies, priceStore);
  }

  @Test
  void emptySelectionDoesNotRequestAnInvalidEmptyPriceBatch() {
    assertThat(service.heldCabins(new CustomerIdentity(SUBJECT, "customer"), INQUIRY, List.of()))
        .isEmpty();
    verifyNoInteractions(dependencies, priceStore);
  }

  @Test
  void unavailableClassificationNeverBecomesZeroRent() {
    when(dependencies.readCabinPricingReferences(WAREHOUSE, List.of(CABIN)))
        .thenThrow(
            new LogisticsDependencyException(
                LogisticsDependencyException.FailureKind.TRANSIENT, "Unavailable"));
    assertThatThrownBy(
            () ->
                service.heldCabins(
                    new CustomerIdentity(SUBJECT, "customer"), INQUIRY, List.of(cabin(CABIN))))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            error -> assertThat(error.code()).isEqualTo("RENTAL_PRICING_UNAVAILABLE"));
    verifyNoInteractions(priceStore);
  }

  private static LogisticsDependencyGateway.AvailableCabin cabin(UUID id) {
    return new LogisticsDependencyGateway.AvailableCabin(
        id,
        4,
        WAREHOUSE,
        "FREE",
        "БК-1",
        "БК",
        "6×2.4",
        "ДВП",
        "Обычная",
        null,
        true,
        Map.of(),
        List.of(),
        OffsetDateTime.now());
  }
}
