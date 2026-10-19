package dev.buhanzaz.rwms.asset.operations.outbox;

import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP adapter for administrator-reviewed asset outbox recovery requests.
 */
@RestController
@Validated
@RequestMapping("/api/asset/v1/operations/outbox")
public class AssetOutboxRecoveryController {
  private final AssetOutboxRecoveryService recovery;
  private final AssetAuthorizer access;

  public AssetOutboxRecoveryController(AssetOutboxRecoveryService recovery, AssetAuthorizer access) {
    this.recovery = recovery;
    this.access = access;
  }

  @PostMapping("/{eventId}/requeue")
  public AssetOutboxRequeueResponse requeue(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID eventId,
      @Valid @RequestBody AssetOutboxRequeueRequest request) {
    access.requireOutboxRecovery(jwt);
    return recovery.requeue(
        eventId,
        request.expectedReviewVersion(),
        access.subjectId(jwt),
        request.reason());
  }
}
