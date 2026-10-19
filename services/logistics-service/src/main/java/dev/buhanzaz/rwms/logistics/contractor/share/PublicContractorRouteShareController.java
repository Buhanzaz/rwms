package dev.buhanzaz.rwms.logistics.contractor.share;

import static dev.buhanzaz.rwms.logistics.contractor.share.ContractorRouteShareApiModels.*;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Anonymous no-store capability boundary exposing only one exact contractor route. */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/logistics/public/v1/contractor-route-shares")
public class PublicContractorRouteShareController {
  private final ContractorRouteShareService routeShares;

  /** Resolves current route state after signature, lifecycle and exact-worker checks. */
  @GetMapping("/{token}")
  public ResponseEntity<PublicContractorRouteShareResponse> get(@PathVariable String token) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(routeShares.publicRoute(token));
  }

  /** Applies one exact replay-safe route-entry action without exposing service credentials. */
  @PostMapping("/{token}/tasks/{externalTaskId}/entries/{entryId}/actions")
  public ResponseEntity<PublicContractorRouteTaskActionResponse> action(
      @PathVariable String token,
      @PathVariable UUID externalTaskId,
      @PathVariable UUID entryId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ApplyContractorRouteTaskActionRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(routeShares.applyAction(token, externalTaskId, entryId, idempotencyKey, request));
  }

  /** Accepts one bounded image only after resolving the exact route, task and entry capability. */
  @PostMapping("/{token}/tasks/{externalTaskId}/entries/{entryId}/evidence/{evidenceId}")
  public ResponseEntity<PublicContractorEvidenceUploadResponse> uploadEvidence(
      @PathVariable String token,
      @PathVariable UUID externalTaskId,
      @PathVariable UUID entryId,
      @PathVariable UUID evidenceId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @RequestHeader("X-Content-SHA256") String contentSha256,
      @RequestHeader("X-Captured-At") OffsetDateTime capturedAt,
      @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
      @RequestBody byte[] bytes) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .body(
            routeShares.uploadEvidence(
                token,
                externalTaskId,
                entryId,
                evidenceId,
                idempotencyKey,
                capturedAt,
                contentType,
                contentSha256,
                bytes));
  }

  /** Streams one exact proven immutable image derivative without disclosing a private locator. */
  @GetMapping(
      value =
          "/{token}/tasks/{externalTaskId}/entries/{entryId}/media/{mediaId}/generations/{generation}/variants/{variant}/content",
      produces = "image/webp")
  public ResponseEntity<byte[]> media(
      @PathVariable String token,
      @PathVariable UUID externalTaskId,
      @PathVariable UUID entryId,
      @PathVariable UUID mediaId,
      @PathVariable long generation,
      @PathVariable String variant) {
    var content = routeShares.media(token, externalTaskId, entryId, mediaId, generation, variant);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
        .contentType(MediaType.parseMediaType(content.contentType()))
        .body(content.bytes());
  }
}
