package dev.buhanzaz.rwms.inventory.api;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.*;

import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.service.InventoryApplicationService;
import dev.buhanzaz.rwms.inventory.service.InventoryIdempotencyPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.WebRequest;
import tools.jackson.databind.ObjectMapper;

@RestController
@Validated
@RequestMapping("/api/inventory/v1")
public class InventoryController {
  private final InventoryApplicationService inventory;
  private final ObjectMapper objectMapper;

  public InventoryController(InventoryApplicationService inventory, ObjectMapper objectMapper) {
    this.inventory = inventory;
    this.objectMapper = objectMapper;
  }

  @GetMapping("/sessions")
  public PageResponse<SessionSummary> sessions(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestParam(required = false) SessionLifecycle status,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate businessDateFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate businessDateTo,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime startedFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime startedTo,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime terminalFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime terminalTo,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
      @RequestParam(defaultValue = "startedAt,desc") String sort) {
    return inventory.sessions(
        jwt,
        warehouseId,
        status,
        businessDateFrom,
        businessDateTo,
        startedFrom,
        startedTo,
        terminalFrom,
        terminalTo,
        page,
        size,
        sort);
  }

  @PostMapping("/sessions")
  public ResponseEntity<SessionView> start(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody StartSessionRequest request) {
    return idempotent(inventory.start(jwt, idempotencyKey, request), HttpStatus.CREATED);
  }

  @GetMapping("/sessions/active")
  public ResponseEntity<SessionView> active(
      @AuthenticationPrincipal Jwt jwt, @RequestParam UUID warehouseId, WebRequest webRequest) {
    Optional<SessionView> active = inventory.active(jwt, warehouseId);
    String eTag = activeEtag(warehouseId, active);
    if (webRequest.checkNotModified(eTag)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(eTag).build();
    }
    if (active.isPresent()) {
      return ResponseEntity.ok().eTag(eTag).body(active.orElseThrow());
    }
    return ResponseEntity.status(HttpStatus.NO_CONTENT).eTag(eTag).build();
  }

  @GetMapping("/sessions/{inventoryId}")
  public SessionView session(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID inventoryId) {
    return inventory.session(jwt, inventoryId);
  }

  @GetMapping("/sessions/{inventoryId}/findings")
  public PageResponse<FindingView> findings(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inventoryId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
      @RequestParam(defaultValue = "createdAt,asc") String sort) {
    return inventory.findings(jwt, inventoryId, page, size, sort);
  }

  @PostMapping("/sessions/{inventoryId}/number-resolutions")
  public ResponseEntity<NumberResolutionView> resolveNumber(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inventoryId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ResolveNumberRequest request) {
    return idempotent(
        inventory.resolveNumber(jwt, inventoryId, idempotencyKey, request), HttpStatus.OK);
  }

  @PostMapping("/sessions/{inventoryId}/findings/{findingId}/assets")
  public ResponseEntity<FindingView> createAsset(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inventoryId,
      @PathVariable UUID findingId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateFindingAssetRequest request) {
    return idempotent(
        inventory.createAsset(jwt, inventoryId, findingId, idempotencyKey, request), HttpStatus.OK);
  }

  @PutMapping("/sessions/{inventoryId}/findings/{findingId}/inspection")
  public FindingView inspection(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inventoryId,
      @PathVariable UUID findingId,
      @Valid @RequestBody SaveInspectionRequest request) {
    return inventory.saveInspection(jwt, inventoryId, findingId, request);
  }

  @PutMapping("/sessions/{inventoryId}/findings/{findingId}/conflict-resolution")
  public FindingView resolveConflict(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inventoryId,
      @PathVariable UUID findingId,
      @Valid @RequestBody ResolveConflictRequest request) {
    return inventory.resolveConflict(jwt, inventoryId, findingId, request);
  }

  @PostMapping("/sessions/{inventoryId}/furniture-review/start")
  public ResponseEntity<FurnitureReviewView> startFurnitureReview(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inventoryId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody StartFurnitureReviewRequest request) {
    return idempotent(
        inventory.startFurnitureReview(jwt, inventoryId, idempotencyKey, request), HttpStatus.OK);
  }

  @GetMapping("/sessions/{inventoryId}/furniture-review")
  public FurnitureReviewView furnitureReview(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID inventoryId) {
    return inventory.furnitureReview(jwt, inventoryId);
  }

  @PutMapping("/sessions/{inventoryId}/furniture-review")
  public FurnitureReviewView saveFurnitureReview(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inventoryId,
      @Valid @RequestBody SaveFurnitureReviewRequest request) {
    return inventory.saveFurnitureReview(jwt, inventoryId, request);
  }

  @PostMapping("/sessions/{inventoryId}/completion-preview")
  public ResponseEntity<CompletionPreview> preview(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inventoryId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CompletionPreviewRequest request) {
    return idempotent(inventory.preview(jwt, inventoryId, idempotencyKey, request), HttpStatus.OK);
  }

  @PostMapping("/sessions/{inventoryId}/complete")
  public ResponseEntity<SessionView> complete(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inventoryId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CompleteSessionRequest request) {
    return idempotent(inventory.complete(jwt, inventoryId, idempotencyKey, request), HttpStatus.OK);
  }

  @PostMapping("/sessions/{inventoryId}/cancel")
  public ResponseEntity<SessionView> cancel(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inventoryId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CancelSessionRequest request) {
    return idempotent(inventory.cancel(jwt, inventoryId, idempotencyKey, request), HttpStatus.OK);
  }

  @PostMapping("/sessions/{inventoryId}/publications")
  public ResponseEntity<PublicationBatch> publish(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inventoryId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody PublishFindingsRequest request) {
    return idempotent(inventory.publish(jwt, inventoryId, idempotencyKey, request), HttpStatus.OK);
  }

  @PostMapping("/sessions/{inventoryId}/findings/{findingId}/publication/retry")
  public ResponseEntity<PublicationView> retry(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inventoryId,
      @PathVariable UUID findingId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody RetryPublicationRequest request) {
    return idempotent(
        inventory.retryPublication(jwt, inventoryId, findingId, idempotencyKey, request),
        HttpStatus.OK);
  }

  @PostMapping("/sessions/{inventoryId}/findings/{findingId}/publication/close")
  public ResponseEntity<PublicationView> close(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID inventoryId,
      @PathVariable UUID findingId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ClosePublicationRequest request) {
    return idempotent(
        inventory.closePublication(jwt, inventoryId, findingId, idempotencyKey, request),
        HttpStatus.OK);
  }

  @GetMapping("/statistics/sessions")
  public PageResponse<SessionStatistics> statistics(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate businessDateFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate businessDateTo,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime startedFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime startedTo,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime terminalFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime terminalTo,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
      @RequestParam(defaultValue = "completedAt,desc") String sort) {
    return inventory.statistics(
        jwt,
        warehouseId,
        businessDateFrom,
        businessDateTo,
        startedFrom,
        startedTo,
        terminalFrom,
        terminalTo,
        page,
        size,
        sort);
  }

  @GetMapping("/statistics/summary")
  public StatisticsSummary statisticsSummary(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam UUID warehouseId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate businessDateFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate businessDateTo,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime startedFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime startedTo,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime terminalFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime terminalTo) {
    return inventory.statisticsSummary(
        jwt,
        warehouseId,
        businessDateFrom,
        businessDateTo,
        startedFrom,
        startedTo,
        terminalFrom,
        terminalTo);
  }

  private <T> ResponseEntity<T> idempotent(T body, HttpStatus status) {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    Object replayed =
        attributes == null
            ? null
            : attributes.getAttribute(
                InventoryIdempotencyPort.REPLAY_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    return ResponseEntity.status(status)
        .header("Idempotency-Replayed", Boolean.TRUE.equals(replayed) ? "true" : "false")
        .body(body);
  }

  private String activeEtag(UUID warehouseId, Optional<SessionView> active) {
    byte[] representation =
        active
            .map(objectMapper::writeValueAsBytes)
            .orElseGet(
                () ->
                    ("active-inventory:none:" + warehouseId)
                        .getBytes(StandardCharsets.UTF_8));
    try {
      String fingerprint =
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(representation));
      return "\"" + fingerprint + "\"";
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("The Java runtime does not provide SHA-256", exception);
    }
  }
}
