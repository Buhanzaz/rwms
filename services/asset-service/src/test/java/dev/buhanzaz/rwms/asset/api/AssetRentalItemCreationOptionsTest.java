package dev.buhanzaz.rwms.asset.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.CabinCatalogValueResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CabinTypeDimensionResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemPage;
import dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.CabinAvailability;
import dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.CabinAvailabilityRequest;
import dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.CabinAvailabilityResponse;
import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import dev.buhanzaz.rwms.asset.service.AssetService;
import dev.buhanzaz.rwms.asset.service.CabinCompositionService;
import dev.buhanzaz.rwms.asset.service.PresentationHoldService;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class AssetRentalItemCreationOptionsTest {
  private final AssetService service = mock(AssetService.class);
  private final CabinCompositionService composition = mock(CabinCompositionService.class);
  private final PresentationHoldService presentationHolds = mock(PresentationHoldService.class);
  private final AssetRentalItemController controller =
      new AssetRentalItemController(
          service,
          composition,
          presentationHolds,
          new AssetAuthorizer(new MockEnvironment(), false));

  @Test
  void readsAvailableRentalItemsOnlyForAnAccessibleWarehouse() {
    UUID warehouseId = UUID.randomUUID();
    RentalItemPage expected = new RentalItemPage(List.of(), 0, 50, 0, 0);
    when(presentationHolds.availableRentalItems(warehouseId, 0, 50, "CAB-1"))
        .thenReturn(expected);

    assertThat(controller.available(user(warehouseId), warehouseId, 0, 50, "CAB-1"))
        .isSameAs(expected);
    verify(presentationHolds).availableRentalItems(warehouseId, 0, 50, "CAB-1");
  }

  @Test
  void rechecksAvailabilityOnlyForAnAccessibleWarehouse() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    CabinAvailabilityRequest request =
        new CabinAvailabilityRequest(warehouseId, List.of(rentalItemId));
    CabinAvailabilityResponse expected =
        new CabinAvailabilityResponse(
            warehouseId, List.of(new CabinAvailability(rentalItemId, true, "AVAILABLE")));
    when(presentationHolds.availability(request)).thenReturn(expected);

    assertThat(controller.availability(user(warehouseId), request)).isSameAs(expected);
    verify(presentationHolds).availability(request);
  }

  @Test
  void rejectsPublicAvailabilityReadsOutsideUserWarehouseAccess() {
    UUID allowedWarehouseId = UUID.randomUUID();
    UUID deniedWarehouseId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                controller.available(
                    user(allowedWarehouseId), deniedWarehouseId, 0, 50, null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("warehouse access");
    assertThatThrownBy(
            () ->
                controller.availability(
                    user(allowedWarehouseId),
                    new CabinAvailabilityRequest(
                        deniedWarehouseId, List.of(UUID.randomUUID()))))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("warehouse access");
    verifyNoInteractions(presentationHolds);
  }

  @Test
  void returnsServiceManagedCabinChoicesForAccessibleWarehouse() {
    UUID warehouseId = UUID.randomUUID();
    UUID typeId = UUID.randomUUID();
    UUID dimensionId = UUID.randomUUID();
    when(composition.creationOptions())
        .thenReturn(
            new AssetApiModels.RentalItemCreationOptionsResponse(
                "Новая",
                List.of("ИТР", "Обычная"),
                List.of(new CabinCatalogValueResponse(typeId, "БК-Санблок")),
                List.of(new CabinCatalogValueResponse(dimensionId, "2.4x6")),
                List.of(new CabinCatalogValueResponse(UUID.randomUUID(), "ПВХ")),
                List.of(new CabinCatalogValueResponse(UUID.randomUUID(), "Пластиковое окно")),
                List.of(new CabinTypeDimensionResponse(typeId, dimensionId, 0))));

    var response = controller.creationOptions(user(warehouseId), warehouseId);

    assertThat(response.newCategory()).isEqualTo("Новая");
    assertThat(response.usedCategories()).containsExactly("ИТР", "Обычная");
    assertThat(response.finishings()).extracting(CabinCatalogValueResponse::name).containsExactly("ПВХ");
    assertThat(response.characteristics())
        .extracting(CabinCatalogValueResponse::name)
        .containsExactly("Пластиковое окно");
    assertThat(response.rentalTypes()).containsExactly(new CabinCatalogValueResponse(typeId, "БК-Санблок"));
    assertThat(response.typeDimensions())
        .containsExactly(new CabinTypeDimensionResponse(typeId, dimensionId, 0));
    verifyNoInteractions(service);
  }

  @Test
  void rejectsChoicesForWarehouseOutsideUserAccess() {
    UUID allowedWarehouseId = UUID.randomUUID();

    assertThatThrownBy(
            () -> controller.creationOptions(user(allowedWarehouseId), UUID.randomUUID()))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("warehouse access");
    verifyNoInteractions(service);
  }

  @Test
  void requiresExplicitLinoleumForEveryNewRentalItem() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      var publicRequest =
          new AssetApiModels.CreateRentalItemRequest(
              UUID.randomUUID(),
              "БЫТ-101",
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              "Новая",
              List.of(UUID.randomUUID()),
              null,
              Map.of(),
              List.of());
      var inventoryRequest =
          new AssetApiModels.InventorySourceAssetRequest(
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              "БЫТ-102",
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              "Обычная",
              List.of(UUID.randomUUID()),
              null,
              Map.of(),
              List.of());

      assertThat(validator.validate(publicRequest))
          .extracting(violation -> violation.getPropertyPath().toString())
          .containsExactly("linoleum");
      assertThat(validator.validate(inventoryRequest))
          .extracting(violation -> violation.getPropertyPath().toString())
          .containsExactly("linoleum");
    }
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
            "rwms.read",
            "global_role",
            "WAREHOUSE_MANAGER",
            "warehouse_access",
            List.of(Map.of("warehouseId", warehouseId.toString(), "level", "VIEW"))));
  }
}
