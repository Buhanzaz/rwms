package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;

import dev.buhanzaz.rwms.taskboard.security.*;
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
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/warehouses/{warehouseId}")
@Validated
public class WorkQueueController {
  private final RegistryService service;
  private final WarehouseAccessAuthorizer access;

  @GetMapping("/work-queues")
  public List<WorkQueueDto> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID warehouseId) {
    read(jwt, warehouseId);
    return service.listQueues(warehouseId);
  }

  @PostMapping("/work-queues")
  @ResponseStatus(HttpStatus.CREATED)
  public WorkQueueDto create(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @Valid @RequestBody WorkQueueRequest request) {
    write(jwt, warehouseId);
    return service.createQueue(warehouseId, request);
  }

  @PutMapping("/work-queues/{id}")
  public WorkQueueDto update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID id,
      @Valid @RequestBody WorkQueueRequest request) {
    write(jwt, warehouseId);
    return service.updateQueue(warehouseId, id, request);
  }

  @DeleteMapping("/work-queues/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @PathVariable UUID id,
      @RequestParam @Min(0) long expectedVersion) {
    write(jwt, warehouseId);
    service.deleteQueue(warehouseId, id, expectedVersion);
  }

  @PutMapping("/work-queue-order")
  public List<WorkQueueDto> order(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID warehouseId,
      @Valid @RequestBody QueueOrderRequest request) {
    write(jwt, warehouseId);
    return service.reorder(warehouseId, request);
  }

  private void read(Jwt jwt, UUID id) {
    access.requireUserScope(jwt, "rwms.read");
    access.requireWarehouse(jwt, id, AccessLevel.VIEW, false);
  }

  private void write(Jwt jwt, UUID id) {
    access.requireUserScope(jwt, "rwms.write");
    access.requireWarehouse(jwt, id, AccessLevel.MANAGE, false);
  }
}
