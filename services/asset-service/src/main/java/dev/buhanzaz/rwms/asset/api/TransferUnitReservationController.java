package dev.buhanzaz.rwms.asset.api;

import static dev.buhanzaz.rwms.asset.api.TransferUnitReservationApiModels.*;

import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import dev.buhanzaz.rwms.asset.service.AssetService;
import dev.buhanzaz.rwms.asset.service.TransferUnitReservationService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Least-privilege logistics boundary for asset-owned inter-warehouse cabin reservations. */
@RestController
@Validated
@RequestMapping("/api/internal/asset/v1/logistics/transfer-unit-reservations")
@RequiredArgsConstructor
public class TransferUnitReservationController {
  private final TransferUnitReservationService reservations;
  private final AssetAuthorizer access;

  /** Confirms one all-or-nothing selected-cabin batch under the logistics service scope. */
  @PostMapping
  public ResponseEntity<TransferUnitReservationReceipt> confirm(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ConfirmTransferUnitReservationsRequest request) {
    access.requireLogisticsAssetAccess(jwt);
    AssetService.CreateResult<TransferUnitReservationReceipt> result =
        reservations.confirm(access.logisticsSubjectId(jwt), idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  /** Releases one exact pre-departure batch and preserves terminal ownership history. */
  @PutMapping("/release")
  public ResponseEntity<TransferUnitReservationReceipt> release(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ReleaseTransferUnitReservationsRequest request) {
    access.requireLogisticsAssetAccess(jwt);
    AssetService.CreateResult<TransferUnitReservationReceipt> result =
        reservations.release(access.logisticsSubjectId(jwt), idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }
}
