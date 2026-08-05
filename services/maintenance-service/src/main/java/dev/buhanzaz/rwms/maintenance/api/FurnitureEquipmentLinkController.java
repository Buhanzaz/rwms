package dev.buhanzaz.rwms.maintenance.api;

import static dev.buhanzaz.rwms.maintenance.api.FurnitureEquipmentLinkApiModels.*;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.PageResponse;
import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.FurnitureEquipmentLinkReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public, administrator-only inspection and review of bounded furniture auto-link work. */
@RestController
@Validated
@RequestMapping("/api/maintenance/v1/catalog/furniture-equipment-links")
@RequiredArgsConstructor
public class FurnitureEquipmentLinkController {
  private final FurnitureEquipmentLinkReviewService review;
  private final MaintenanceAuthorizer access;

  @GetMapping
  public PageResponse<FurnitureEquipmentLinkResponse> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
      @RequestParam(required = false) FurnitureEquipmentLinkState state) {
    access.requireFurnitureEquipmentLinkAdministrator(jwt, warehouseId);
    return review.list(warehouseId, state, page, size);
  }

  @PostMapping("/{nodeId}/review")
  public ResponseEntity<FurnitureEquipmentLinkResponse> review(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID nodeId,
      @RequestParam UUID warehouseId,
      @Valid @RequestBody FurnitureEquipmentLinkReviewRequest request) {
    access.requireFurnitureEquipmentLinkAdministrator(jwt, warehouseId);
    FurnitureEquipmentLinkReviewService.ReviewedLink result = review.review(
        warehouseId,
        nodeId,
        request.expectedReviewVersion(),
        request.action(),
        access.subjectId(jwt),
        request.reason());
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }
}
