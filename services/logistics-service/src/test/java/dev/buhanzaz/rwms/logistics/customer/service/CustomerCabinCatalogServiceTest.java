package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerRentalSessionRepository;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Covers nullable legacy facts at the CustomerApp cabin-card boundary. */
class CustomerCabinCatalogServiceTest {
  private static final UUID SUBJECT =
      UUID.fromString("00000000-0000-0000-0000-000000000611");
  private static final UUID INQUIRY =
      UUID.fromString("00000000-0000-0000-0000-000000000612");
  private static final UUID WAREHOUSE =
      UUID.fromString("c89b65f1-2891-4176-bd88-1d231e869a25");
  private static final UUID CABIN =
      UUID.fromString("00000000-0000-0000-0000-000000000613");

  @Test
  void omitsNullLegacyFactInsteadOfFailingTheWholeCatalogPage() {
    CustomerRentalSessionRepository sessions = mock(CustomerRentalSessionRepository.class);
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    CustomerRentalSession session = mock(CustomerRentalSession.class);
    when(session.getWarehouseId()).thenReturn(WAREHOUSE);
    when(sessions.findByInquiryIdAndCustomerSubjectId(INQUIRY, SUBJECT))
        .thenReturn(Optional.of(session));
    Map<String, Object> passport = new LinkedHashMap<>();
    passport.put("wall", "ДВП");
    passport.put("legacy", null);
    LogisticsDependencyGateway.AvailableCabin cabin =
        new LogisticsDependencyGateway.AvailableCabin(
            CABIN,
            4,
            WAREHOUSE,
            "FREE",
            "СПБ-001",
            "БК",
            "6x2.4",
            "ДВП",
            "Стандарт",
            "Пластиковое окно",
            true,
            passport,
            List.of(),
            OffsetDateTime.of(2026, 8, 26, 12, 0, 0, 0, ZoneOffset.UTC));
    when(
            dependencies.readCustomerCabinCatalog(
                WAREHOUSE,
                INQUIRY,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                0,
                20))
        .thenReturn(
            new LogisticsDependencyGateway.CabinCatalogPage(
                WAREHOUSE, List.of(cabin), 0, 20, 1, 1));
    when(dependencies.readCabinMediaSnapshots(WAREHOUSE, List.of(CABIN)))
        .thenReturn(List.of());
    CustomerCabinCatalogService service =
        new CustomerCabinCatalogService(sessions, dependencies);

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

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().getFirst().facts()).containsEntry("wall", "ДВП");
    assertThat(result.content().getFirst().facts()).doesNotContainKey("legacy");
  }
}
