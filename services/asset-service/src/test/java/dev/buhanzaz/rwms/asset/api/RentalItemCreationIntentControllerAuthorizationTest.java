package dev.buhanzaz.rwms.asset.api;

import static dev.buhanzaz.rwms.asset.api.RentalItemCreationIntentApiModels.CreateRentalItemWithPhotoIntentRequest;
import static dev.buhanzaz.rwms.asset.api.RentalItemCreationIntentApiModels.RentalItemCreationIntentCommand;
import static dev.buhanzaz.rwms.asset.api.RentalItemCreationIntentApiModels.RentalItemCreationPhotoManifestEntry;
import static dev.buhanzaz.rwms.asset.api.RentalItemCreationIntentApiModels.RentalItemCreationPhotoManifestInput;
import static dev.buhanzaz.rwms.asset.api.RentalItemCreationIntentApiModels.RentalItemCreationIntentResponse;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.domain.RentalItemCreationIntentState;
import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import dev.buhanzaz.rwms.asset.service.RentalItemCreationIntentService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

/** Proves that creation-intent reads and commands remain warehouse isolated. */
class RentalItemCreationIntentControllerAuthorizationTest {
  private final RentalItemCreationIntentService intents =
      mock(RentalItemCreationIntentService.class);
  private final RentalItemCreationIntentController controller =
      new RentalItemCreationIntentController(
          intents, new AssetAuthorizer(new MockEnvironment(), false));

  @Test
  void rejectsCreateAndListBeforeCallingServiceForAnUnauthorizedWarehouse() {
    UUID allowedWarehouseId = UUID.randomUUID();
    UUID deniedWarehouseId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                controller.listPending(
                    user(allowedWarehouseId), deniedWarehouseId, 0, 50))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("warehouse access");
    assertThatThrownBy(
            () ->
                controller.create(
                    user(allowedWarehouseId),
                    UUID.randomUUID(),
                    new CreateRentalItemWithPhotoIntentRequest(
                        rentalRequest(deniedWarehouseId),
                        List.of(
                            new RentalItemCreationPhotoManifestInput(
                                0, "a".repeat(64), "image/webp", 100L)))))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("warehouse access");
    verifyNoInteractions(intents);
  }

  @Test
  void rejectsTerminalCommandWhenIntentBelongsToAnotherWarehouse() {
    UUID allowedWarehouseId = UUID.randomUUID();
    UUID deniedWarehouseId = UUID.randomUUID();
    UUID intentId = UUID.randomUUID();
    when(intents.get(intentId))
        .thenReturn(intent(intentId, deniedWarehouseId));

    assertThatThrownBy(() -> controller.get(user(allowedWarehouseId), intentId))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("warehouse access");
    assertThatThrownBy(
            () ->
                controller.complete(
                    user(allowedWarehouseId),
                    intentId,
                    UUID.randomUUID(),
                    new RentalItemCreationIntentCommand(0L)))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("warehouse access");
    assertThatThrownBy(
            () ->
                controller.abandon(
                    user(allowedWarehouseId),
                    intentId,
                    UUID.randomUUID(),
                    new RentalItemCreationIntentCommand(0L)))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("warehouse access");
  }

  private static CreateRentalItemRequest rentalRequest(UUID warehouseId) {
    return new CreateRentalItemRequest(
        warehouseId,
        "AUTH-" + UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        List.of(),
        false,
        Map.of(),
        List.of());
  }

  private static RentalItemCreationIntentResponse intent(
      UUID intentId, UUID warehouseId) {
    return new RentalItemCreationIntentResponse(
        intentId,
        0,
        UUID.randomUUID(),
        warehouseId,
        RentalItemCreationIntentState.PENDING,
        1,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "a".repeat(64),
        List.of(
            new RentalItemCreationPhotoManifestEntry(
                0, UUID.randomUUID(), "b".repeat(64), "image/webp", 100L)),
        null,
        null,
        OffsetDateTime.now(ZoneOffset.UTC),
        null,
        null);
  }

  private static Jwt user(UUID warehouseId) {
    return new Jwt(
        "token",
        Instant.now(),
        Instant.now().plusSeconds(60),
        Map.of("alg", "none"),
        Map.of(
            "sub",
            UUID.randomUUID().toString(),
            "principal_type",
            "USER",
            "scope",
            "rwms.read rwms.write",
            "global_role",
            "WAREHOUSE_MANAGER",
            "warehouse_access",
            List.of(
                Map.of(
                    "warehouseId",
                    warehouseId.toString(),
                    "level",
                    "MANAGE"))));
  }
}
