package dev.buhanzaz.rwms.asset.api;

import static dev.buhanzaz.rwms.asset.api.RentalItemHtmlImportApiModels.*;

import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import dev.buhanzaz.rwms.asset.service.RentalItemHtmlImportService;
import dev.buhanzaz.rwms.asset.service.RentalItemHtmlParser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Validated
@RequestMapping("/api/asset/v1/html-imports")
@RequiredArgsConstructor
public class RentalItemHtmlImportController {
  private final RentalItemHtmlImportService service;
  private final AssetAuthorizer access;

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<HtmlImportDetailResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @RequestParam UUID warehouseId,
      @RequestParam MultipartFile html) {
    access.requireEdit(jwt, warehouseId);
    validateHtmlPart(html);
    try {
      HtmlImportDetailResponse response =
          service.create(access.subjectId(jwt), idempotencyKey, warehouseId, html.getBytes());
      return ResponseEntity.status(HttpStatus.CREATED).body(response);
    } catch (IOException exception) {
      throw new IllegalArgumentException("HTML file could not be read", exception);
    }
  }

  @GetMapping
  public List<HtmlImportSummaryResponse> list(
      @AuthenticationPrincipal Jwt jwt, @RequestParam UUID warehouseId) {
    access.requireEdit(jwt, warehouseId);
    return service.list(warehouseId);
  }

  @GetMapping("/{id}")
  public HtmlImportDetailResponse get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    access.requireEdit(jwt, service.warehouseId(id));
    return service.get(id);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> cancel(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam @Min(0) long expectedVersion) {
    access.requireEdit(jwt, service.warehouseId(id));
    service.cancel(id, expectedVersion);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{id}/rows")
  public HtmlImportRowPage rows(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "100") @Min(1) @Max(200) int size) {
    access.requireEdit(jwt, service.warehouseId(id));
    return service.rowPage(id, page, size);
  }

  @PutMapping("/{id}/plan")
  public HtmlImportDetailResponse updatePlan(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody UpdateHtmlImportPlanRequest request) {
    access.requireEdit(jwt, service.warehouseId(id));
    return service.updatePlan(id, request);
  }

  @PostMapping("/{id}/commit")
  public ResponseEntity<HtmlImportDetailResponse> commit(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CommitHtmlImportRequest request) {
    access.requireHtmlImportCommit(jwt, service.warehouseId(id));
    return ResponseEntity.accepted()
        .body(service.commit(access.subjectId(jwt), idempotencyKey, id, request));
  }

  @PostMapping("/{id}/retry-media")
  public ResponseEntity<HtmlImportDetailResponse> retryMedia(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody RetryHtmlImportMediaRequest request) {
    access.requireHtmlImportCommit(jwt, service.warehouseId(id));
    return ResponseEntity.accepted()
        .body(service.retryMedia(access.subjectId(jwt), idempotencyKey, id, request));
  }

  @PostMapping("/{id}/replace-media")
  public ResponseEntity<HtmlImportDetailResponse> replaceMedia(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody ReplaceHtmlImportMediaRequest request) {
    access.requireHtmlImportCommit(jwt, service.warehouseId(id));
    return ResponseEntity.accepted()
        .body(service.replaceMedia(access.subjectId(jwt), idempotencyKey, id, request));
  }

  @PostMapping("/{id}/skip-media")
  public ResponseEntity<HtmlImportDetailResponse> skipMedia(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody SkipHtmlImportMediaRequest request) {
    access.requireHtmlImportCommit(jwt, service.warehouseId(id));
    return ResponseEntity.accepted()
        .body(service.skipMedia(access.subjectId(jwt), idempotencyKey, id, request));
  }

  private static void validateHtmlPart(MultipartFile html) {
    if (html == null
        || html.isEmpty()
        || html.getSize() > RentalItemHtmlParser.MAX_HTML_BYTES) {
      throw new IllegalArgumentException("HTML file must contain 1 byte to 10 MiB");
    }
    String contentType = html.getContentType();
    if (contentType != null
        && !contentType.isBlank()
        && !contentType.toLowerCase(Locale.ROOT).startsWith(MediaType.TEXT_HTML_VALUE)
        && !contentType.toLowerCase(Locale.ROOT).startsWith("application/xhtml+xml")) {
      throw new IllegalArgumentException("Import source must be an HTML file");
    }
    String filename = html.getOriginalFilename();
    if (filename != null
        && !filename.isBlank()
        && !filename.toLowerCase(Locale.ROOT).endsWith(".html")
        && !filename.toLowerCase(Locale.ROOT).endsWith(".htm")) {
      throw new IllegalArgumentException("Import source filename must end with .html or .htm");
    }
  }
}
