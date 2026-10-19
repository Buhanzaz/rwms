package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueDefinitionDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueDefinitionOrderRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueDefinitionRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueLinkRequest;

import dev.buhanzaz.rwms.taskboard.security.WarehouseAccessAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public administration API for the shared task-board queue catalog.
 *
 * <p>{@code GENERAL} definitions are global standards. The registry derives a stable physical
 * queue projection for every active warehouse, so an administrator changes process structure once
 * instead of maintaining divergent copies in each warehouse.
 */
@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api")
public class QueueDefinitionController {
  private final RegistryService service;
  private final WarehouseAccessAuthorizer access;

  /** Returns the common queue definitions and the configured logistics-driver definition. */
  @GetMapping("/queue-definitions")
  public List<QueueDefinitionDto> list(@AuthenticationPrincipal Jwt jwt) {
    access.requireUserScope(jwt, "rwms.read");
    return service.listQueueDefinitions();
  }

  /** Creates a new global {@code GENERAL} process definition. */
  @PostMapping("/queue-definitions")
  @ResponseStatus(HttpStatus.CREATED)
  public QueueDefinitionDto create(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody QueueDefinitionRequest request) {
    requireWrite(jwt);
    return service.createQueueDefinition(request);
  }

  /** Replaces a version-fenced global queue definition and synchronizes its warehouse projections. */
  @PutMapping("/queue-definitions/{id}")
  public QueueDefinitionDto update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody QueueDefinitionRequest request) {
    requireWrite(jwt);
    return service.updateQueueDefinition(id, request);
  }

  /** Changes one continuation pair atomically under both definition versions. */
  @PutMapping("/queue-definitions/{id}/link")
  public List<QueueDefinitionDto> link(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody QueueLinkRequest request) {
    requireWrite(jwt);
    return service.linkQueueDefinitions(id, request);
  }

  /** Reorders the complete global standard without moving entries between physical queues. */
  @PutMapping("/queue-definitions/order")
  public List<QueueDefinitionDto> reorder(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody QueueDefinitionOrderRequest request) {
    requireWrite(jwt);
    return service.reorderQueueDefinitions(request);
  }

  /** Deletes an unused global definition under its optimistic-concurrency version. */
  @DeleteMapping("/queue-definitions/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam @Min(0) long expectedVersion) {
    requireWrite(jwt);
    service.deleteQueueDefinition(id, expectedVersion);
  }

  private void requireWrite(Jwt jwt) {
    access.requireUserScope(jwt, "rwms.write");
    access.requireGlobalManagement(jwt);
  }
}
