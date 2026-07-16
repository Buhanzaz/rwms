package dev.buhanzaz.rwms.asset.api;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import dev.buhanzaz.rwms.asset.security.AssetAuthorizer;
import dev.buhanzaz.rwms.asset.service.AssetService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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

@RestController
@Validated
@RequestMapping("/api/asset/v1/classifiers")
@RequiredArgsConstructor
public class AssetClassifierController {
  private final AssetService service;
  private final AssetAuthorizer access;

  @GetMapping
  public List<ClassifierResponse> list(
      @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String type) {
    access.requireGlobalCatalogRead(jwt);
    return service.classifiers(type == null ? null : type.trim().toUpperCase(java.util.Locale.ROOT));
  }

  @PostMapping
  public ResponseEntity<ClassifierResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody CreateClassifierRequest request) {
    access.requireGlobalCatalogManagement(jwt);
    AssetService.CreateResult<ClassifierResponse> result = service.createClassifier(access.subjectId(jwt), idempotencyKey, request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
    if (result.replayed()) response.header("Idempotency-Replayed", "true");
    return response.body(result.response());
  }

  @PutMapping("/{id}")
  public ClassifierResponse update(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ClassifierRequest request) {
    access.requireGlobalCatalogManagement(jwt);
    return service.updateClassifier(id, request);
  }
}
