package dev.buhanzaz.rwms.asset.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.Disposition;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.DispositionEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MovementResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.TransferEquipmentRequest;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import dev.buhanzaz.rwms.asset.service.AssetService;
import dev.buhanzaz.rwms.asset.service.OrderUnitReservationConflictException;
import dev.buhanzaz.rwms.platform.web.RwmsProblemDetailFactory;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class AssetEquipmentControllerAuthorizationTest {
  @Test
  void orderReservationConflictCodesAreReturnedAsHttp409ProblemDetails() {
    AssetApiExceptionHandler handler =
        new AssetApiExceptionHandler(new RwmsProblemDetailFactory());
    for (String code :
        List.of(
            "UNIT_ALREADY_RESERVED",
            "UNIT_NOT_AVAILABLE",
            "UNIT_WAREHOUSE_MISMATCH",
            "EQUIPMENT_QUANTITY_CONFLICT",
            "INSUFFICIENT_STOCK")) {
      MockHttpServletRequest request = new MockHttpServletRequest();
      request.setRequestURI("/api/internal/asset/v1/logistics/orders/test/units");

      var response =
          handler.orderReservationConflict(
              new OrderUnitReservationConflictException(code, "conflict"), request);

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
      assertThat(response.getBody()).isNotNull();
      assertThat(response.getBody().code()).isEqualTo(code);
    }
  }

  @Test
  void rentalManagerCannotCallGenericCabinToCabinTransferWithManageGrant() {
    UUID warehouseId = UUID.randomUUID();
    AssetService service = mock(AssetService.class);
    AssetEquipmentController controller =
        new AssetEquipmentController(
            service, new AssetAuthorizer(new MockEnvironment(), false));
    TransferEquipmentRequest request =
        new TransferEquipmentRequest(
            UUID.randomUUID(),
            warehouseId,
            UUID.randomUUID(),
            BalanceLocationKind.CABIN_NON_RENTED,
            0L,
            warehouseId,
            UUID.randomUUID(),
            BalanceLocationKind.CABIN_NON_RENTED,
            0L,
            1L);

    assertThatThrownBy(
            () -> controller.transfer(rentalManager(warehouseId), UUID.randomUUID(), request))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("cannot perform arbitrary equipment movements");
    verifyNoInteractions(service);
  }

  @Test
  void rentalManagerCannotCallGenericCabinDispositionWithManageGrant() {
    UUID warehouseId = UUID.randomUUID();
    AssetService service = mock(AssetService.class);
    AssetEquipmentController controller =
        new AssetEquipmentController(
            service, new AssetAuthorizer(new MockEnvironment(), false));
    DispositionEquipmentRequest request = dispositionRequest(warehouseId);

    AccessDeniedException denied =
        assertThrows(
            AccessDeniedException.class,
            () -> controller.disposition(rentalManager(warehouseId), UUID.randomUUID(), request));

    MockHttpServletRequest servletRequest = new MockHttpServletRequest();
    servletRequest.setRequestURI("/api/asset/v1/equipment/dispositions");
    var response =
        new AssetApiExceptionHandler(new RwmsProblemDetailFactory())
            .forbidden(denied, servletRequest);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("ASSET_FORBIDDEN");
    verifyNoInteractions(service);
  }

  @Test
  void adminsAndWarehouseManagerRetainGenericDispositionAccess() {
    UUID warehouseId = UUID.randomUUID();
    DispositionEquipmentRequest request = dispositionRequest(warehouseId);
    MovementResponse movement =
        new MovementResponse(
            UUID.randomUUID(),
            0,
            request.equipmentId(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            request.quantity(),
            "CABIN_TO_WRITTEN_OFF",
            OffsetDateTime.now());

    for (String role : List.of("SYSTEM_ADMIN", "WMS_ADMIN", "WAREHOUSE_MANAGER")) {
      UUID subjectId = UUID.randomUUID();
      UUID idempotencyKey = UUID.randomUUID();
      AssetService service = mock(AssetService.class);
      when(service.dispose(subjectId, idempotencyKey, request))
          .thenReturn(new AssetService.CreateResult<>(movement, false));
      AssetEquipmentController controller =
          new AssetEquipmentController(
              service, new AssetAuthorizer(new MockEnvironment(), false));

      var response =
          controller.disposition(user(subjectId, role, warehouseId), idempotencyKey, request);

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
      assertThat(response.getBody()).isEqualTo(movement);
      verify(service).dispose(subjectId, idempotencyKey, request);
    }
  }

  private static DispositionEquipmentRequest dispositionRequest(UUID warehouseId) {
    return new DispositionEquipmentRequest(
        UUID.randomUUID(),
        warehouseId,
        UUID.randomUUID(),
        BalanceLocationKind.CABIN_NON_RENTED,
        0L,
        1L,
        Disposition.WRITE_OFF);
  }

  private static Jwt rentalManager(UUID warehouseId) {
    return user(UUID.randomUUID(), "RENTAL_MANAGER", warehouseId);
  }

  private static Jwt user(UUID subjectId, String role, UUID warehouseId) {
    return new Jwt(
        "token",
        Instant.now(),
        Instant.now().plusSeconds(60),
        Map.of("alg", "none"),
        Map.of(
            "sub",
            subjectId.toString(),
            "principal_type",
            "USER",
            "scope",
            "rwms.read rwms.write",
            "global_role",
            role,
            "warehouse_access",
            List.of(Map.of("warehouseId", warehouseId.toString(), "level", "MANAGE"))));
  }
}
