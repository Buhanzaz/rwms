package dev.buhanzaz.rwms.logistics.photo;

import static dev.buhanzaz.rwms.logistics.photo.CabinPhotoPresentationApiModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated HTTP boundary for creating immutable public cabin photo presentations. */
@RestController
@Validated
@RequestMapping("/api/logistics/v1/cabins")
@RequiredArgsConstructor
public class CabinPhotoPresentationController {
  private final CabinPhotoPresentationService presentations;

  /** Returns 201 for a new snapshot and 200 with a replay header for an exact idempotent replay. */
  @PostMapping("/{cabinId}/photo-presentations")
  public ResponseEntity<CabinPhotoPresentationResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID cabinId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateCabinPhotoPresentationRequest request) {
    CabinPhotoPresentationService.CreationResult result =
        presentations.create(jwt, cabinId, idempotencyKey, request);
    ResponseEntity.BodyBuilder response =
        result.replayed() ? ResponseEntity.ok() : ResponseEntity.status(201);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }
}
