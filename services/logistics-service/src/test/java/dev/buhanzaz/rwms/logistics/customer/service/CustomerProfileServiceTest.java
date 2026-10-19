package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.PrepareCustomerProfileAvatarUploadRequest;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.SetCustomerProfileAvatarRequest;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.UpdateCustomerProfileRequest;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerEntityType;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerProfile;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerProfileRepository;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerAuthorizer;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import dev.buhanzaz.rwms.logistics.order.service.OrderClientService;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Covers profile editing, subject-bound avatar preparation and READY-generation binding. */
class CustomerProfileServiceTest {
  private static final UUID SUBJECT = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID PROFILE = UUID.fromString("20000000-0000-0000-0000-000000000002");
  private static final UUID CLIENT = UUID.fromString("30000000-0000-0000-0000-000000000003");
  private static final UUID WAREHOUSE = UUID.fromString("40000000-0000-0000-0000-000000000004");
  private static final UUID MEDIA = UUID.fromString("50000000-0000-0000-0000-000000000005");

  private final CustomerProfileRepository profiles = mock(CustomerProfileRepository.class);
  private final OrderClientService clients = mock(OrderClientService.class);
  private final CustomerAuthorizer access = mock(CustomerAuthorizer.class);
  private final LogisticsTransactionLock transactionLock = mock(LogisticsTransactionLock.class);
  private final CustomerWarehouseService warehouses = mock(CustomerWarehouseService.class);
  private final LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
  private final CustomerProfile profile = mock(CustomerProfile.class);
  private final CustomerIdentity identity = new CustomerIdentity(SUBJECT, "customer");
  private CustomerProfileService service;

  @BeforeEach
  void setUp() {
    service =
        new CustomerProfileService(
            profiles, clients, access, transactionLock, warehouses, dependencies);
    when(profiles.findByAuthSubjectId(SUBJECT)).thenReturn(Optional.of(profile));
    when(profiles.saveAndFlush(profile)).thenReturn(profile);
    when(profile.getId()).thenReturn(PROFILE);
    when(profile.getClientId()).thenReturn(CLIENT);
    when(profile.getVersion()).thenReturn(8L);
    when(profile.getEntityType()).thenReturn(CustomerEntityType.INDIVIDUAL);
    when(profile.getFirstName()).thenReturn("Иван");
    when(profile.getLastName()).thenReturn("Иванов");
    when(profile.getPhone()).thenReturn("+79990000000");
    when(profile.displayName()).thenReturn("Иван Иванов");
  }

  @Test
  void updatesProfileAndRentalClientProjectionUnderOneFence() {
    var request =
        new UpdateCustomerProfileRequest(
            7L, "Иван", "Иванов", null, "+79990000000", "client@example.test", "Комментарий");
    when(profile.getEmail()).thenReturn("client@example.test");
    when(profile.getAdditionalInfo()).thenReturn("Комментарий");

    var response = service.update(identity, request);

    verify(profile)
        .updateDetails(
            7L, "Иван", "Иванов", null, "+79990000000", "client@example.test", "Комментарий");
    verify(clients)
        .updateCustomerProfile(
            CLIENT,
            ClientType.INDIVIDUAL,
            "Иван Иванов",
            "+79990000000",
            null,
            "client@example.test",
            "Комментарий");
    assertThat(response.version()).isEqualTo(8L);
  }

  @Test
  void mapsAStaleProfileFenceToAnExplicitConflict() {
    var request =
        new UpdateCustomerProfileRequest(6L, "Иван", "Иванов", null, "+79990000000", null, null);
    org.mockito.Mockito.doThrow(new IllegalStateException("stale"))
        .when(profile)
        .updateDetails(6L, "Иван", "Иванов", null, "+79990000000", null, null);

    assertThatThrownBy(() -> service.update(identity, request))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> {
              assertThat(problem.status().value()).isEqualTo(409);
              assertThat(problem.code()).isEqualTo("CUSTOMER_PROFILE_VERSION_CONFLICT");
            });
  }

  @Test
  void preparesOneDeterministicSubjectBoundAvatarOwnerProof() {
    when(profile.prepareAvatarScope(7L, WAREHOUSE)).thenReturn(true);
    when(profile.getAvatarWarehouseId()).thenReturn(WAREHOUSE);

    var scope =
        service.prepareAvatarUpload(
            identity, new PrepareCustomerProfileAvatarUploadRequest(7L, WAREHOUSE));

    verify(warehouses).required(WAREHOUSE);
    verify(dependencies)
        .upsertCustomerProfileMediaOwnerProof(
            org.mockito.ArgumentMatchers.eq(PROFILE),
            org.mockito.ArgumentMatchers.eq(WAREHOUSE),
            org.mockito.ArgumentMatchers.eq(SUBJECT),
            org.mockito.ArgumentMatchers.any(UUID.class));
    assertThat(scope.ownerType()).isEqualTo("LOGISTICS_CUSTOMER_PROFILE");
    assertThat(scope.ownerId()).isEqualTo(PROFILE);
    assertThat(scope.context()).isEqualTo("PROFILE_AVATAR");
  }

  @Test
  void bindsOnlyTheValidatedReadyAvatarAndReturnsAuthenticatedMediaPaths() {
    when(profile.getAvatarWarehouseId()).thenReturn(WAREHOUSE);
    when(profile.getAvatarMediaId()).thenReturn(MEDIA);
    when(profile.getAvatarGeneration()).thenReturn(3L);

    var response = service.setAvatar(identity, new SetCustomerProfileAvatarRequest(7L, MEDIA, 3L));

    verify(profile).bindAvatar(7L, MEDIA, 3L);
    verify(dependencies)
        .validateCustomerProfileMediaReference(
            PROFILE,
            WAREHOUSE,
            SUBJECT,
            new LogisticsDependencyGateway.MediaReference(MEDIA, 3L));
    assertThat(response.avatar().thumbnailUrl())
        .contains("/variants/SMALL/content")
        .contains("ownerType=LOGISTICS_CUSTOMER_PROFILE")
        .contains("ownerId=" + PROFILE)
        .contains("warehouseId=" + WAREHOUSE)
        .contains("context=PROFILE_AVATAR")
        .contains("generation=3");
  }
}
