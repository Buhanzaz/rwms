package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueReferenceDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueReferenceRequest;

import dev.buhanzaz.rwms.taskboard.domain.QueueReferenceType;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/api/internal/work-queues")
@Validated
public class InternalQueueReferenceController {
  private final RegistryService registry;
  private final java.util.Set<String> allowedClients;

  public InternalQueueReferenceController(
      RegistryService registry,
      @Value("${rwms.security.queue-registry-client-ids:}") List<String> allowedClients) {
    this.registry = registry;
    this.allowedClients =
        allowedClients.stream()
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  @PostMapping("/{queueId}/references")
  public QueueReferenceDto register(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID queueId,
      @Valid @RequestBody QueueReferenceRequest request) {
    requireScope(jwt);
    return registry.registerReference(queueId, request);
  }

  @DeleteMapping("/references/{type}/{externalReferenceId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable QueueReferenceType type,
      @PathVariable String externalReferenceId,
      @RequestParam @Min(0) long expectedVersion) {
    requireScope(jwt);
    registry.deleteReference(type, externalReferenceId, expectedVersion);
  }

  private void requireScope(Jwt jwt) {
    Object claim = jwt.getClaims().get("scope");
    boolean allowed =
        claim instanceof String scopes
            && List.of(scopes.split(" ")).contains("queue-registry.write");
    String type = jwt.getClaimAsString("principal_type");
    String clientId = jwt.getClaimAsString("client_id");
    if (!allowed || !"SERVICE".equals(type) || !allowedClients.contains(clientId)) {
      throw new AccessDeniedException("queue-registry.write scope required");
    }
  }
}
