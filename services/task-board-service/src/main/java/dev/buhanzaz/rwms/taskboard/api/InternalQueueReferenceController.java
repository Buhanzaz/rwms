package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueReferenceDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueReferenceRequest;

import dev.buhanzaz.rwms.taskboard.domain.QueueReferenceType;
import dev.buhanzaz.rwms.taskboard.security.QueueRegistryAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/api/internal/work-queues")
@Validated
public class InternalQueueReferenceController {
  private final RegistryService registry;
  private final QueueRegistryAuthorizer access;

  public InternalQueueReferenceController(
      RegistryService registry, QueueRegistryAuthorizer access) {
    this.registry = registry;
    this.access = access;
  }

  @PostMapping("/{queueId}/references")
  public QueueReferenceDto register(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID queueId,
      @Valid @RequestBody QueueReferenceRequest request) {
    access.requireAccess(jwt);
    return registry.registerReference(queueId, request);
  }

  @DeleteMapping("/references/{type}/{externalReferenceId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable QueueReferenceType type,
      @PathVariable String externalReferenceId,
      @RequestParam @Min(0) long expectedVersion) {
    access.requireAccess(jwt);
    registry.deleteReference(type, externalReferenceId, expectedVersion);
  }
}
